package com.tronprotocol.app.mindnexus

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32

/**
 * Binary codec for reading and writing .mnx (Mind Nexus) files.
 *
 * ## Writing
 * Build an [MnxFile] with populated sections, then call [encode] to produce
 * the binary representation. Section payloads are serialized via the
 * [MnxSectionSerializers] companion.
 *
 * ## Reading
 * Call [decode] with an [InputStream] or [File] to get an [MnxFile] back.
 * Unknown section types are preserved as raw byte arrays but won't be
 * deserialized into typed models.
 *
 * ## Integrity
 * - Each section has a CRC32 checksum verified on read.
 * - The file footer contains a SHA-256 digest of all section data concatenated.
 */
object MnxCodec {

    // =========================================================================
    //  TOP-LEVEL ENCODE / DECODE
    // =========================================================================

    /**
     * Encode an [MnxFile] to binary and write to an [OutputStream].
     */
    fun encode(file: MnxFile, output: OutputStream) {
        val writer = MnxWriter(output)

        // ---- Build section table entries ------------------------------------
        // Combine known and raw (user-defined/unknown) sections into a
        // unified ordered list for the section table.
        val allSectionTypes = mutableListOf<Short>()     // type IDs in order
        val allPayloads = mutableListOf<ByteArray>()     // payloads in order

        for ((type, payload) in file.sections) {
            allSectionTypes.add(type.typeId)
            allPayloads.add(payload)
        }
        for ((typeId, payload) in file.rawSections) {
            allSectionTypes.add(typeId)
            allPayloads.add(payload)
        }

        val totalSections = allSectionTypes.size
        val sectionTableSize = totalSections * MnxFormat.SECTION_ENTRY_SIZE

        // Data starts after header + section table
        var dataOffset = (MnxFormat.HEADER_SIZE + sectionTableSize).toLong()

        data class RawSectionEntry(val typeId: Short, val offset: Long, val size: Int, val crc32: Int)
        val rawEntries = mutableListOf<RawSectionEntry>()

        for (i in 0 until totalSections) {
            val payload = allPayloads[i]
            val crc = crc32(payload)
            rawEntries.add(RawSectionEntry(allSectionTypes[i], dataOffset, payload.size, crc))
            dataOffset += payload.size
        }

        // ---- Write header (64 bytes) ----------------------------------------
        val header = file.header.copy(
            sectionCount = totalSections.toShort(),
            sectionTableOffset = MnxFormat.HEADER_SIZE,
            modifiedTimestamp = System.currentTimeMillis(),
            totalUncompressedSize = allPayloads.sumOf { it.size.toLong() }
        )
        writeHeader(writer, header)

        // ---- Write section table --------------------------------------------
        for (entry in rawEntries) {
            writer.writeShort(entry.typeId)
            writer.writeLong(entry.offset)
            writer.writeInt(entry.size)
            writer.writeInt(entry.crc32)
            writer.writeShort(0) // reserved
        }

        // ---- Write section data ---------------------------------------------
        val sha256 = MessageDigest.getInstance("SHA-256")
        for (payload in allPayloads) {
            writer.writeBytes(payload)
            sha256.update(payload)
        }

        // ---- Write footer (36 bytes) ----------------------------------------
        val digest = sha256.digest()
        writer.writeBytes(digest)              // 32 bytes SHA-256
        writer.writeInt(MnxFormat.FOOTER_MAGIC) // 4 bytes end marker

        writer.flush()
    }

    /**
     * Encode an [MnxFile] to a byte array.
     */
    fun encodeToBytes(file: MnxFile): ByteArray {
        val baos = ByteArrayOutputStream()
        encode(file, baos)
        return baos.toByteArray()
    }

    /**
     * Encode an [MnxFile] and write to a [File].
     */
    fun encodeToFile(mnxFile: MnxFile, outputFile: File) {
        FileOutputStream(outputFile).buffered().use { encode(mnxFile, it) }
    }

    /**
     * Decode a .mnx file from an [InputStream].
     */
    fun decode(input: InputStream): MnxFile {
        try {
        val reader = MnxReader(input)

        // ---- Read header ----------------------------------------------------
        val header = readHeader(reader)

        // ---- Read section table ---------------------------------------------
        // Read raw type IDs so we can handle unknown section types.
        data class RawEntry(val typeId: Short, val offset: Long, val size: Int, val crc32: Int)
        val rawEntries = mutableListOf<RawEntry>()
        repeat(header.sectionCount.toInt()) {
            val typeId = reader.readShort()
            val offset = reader.readLong()
            val size = reader.readInt()
            val crc = reader.readInt()
            reader.readShort() // reserved
            rawEntries.add(RawEntry(typeId, offset, size, crc))
        }

        // ---- Read section data ----------------------------------------------
        val sections = LinkedHashMap<MnxSectionType, ByteArray>()
        val rawSections = LinkedHashMap<Short, ByteArray>()
        val sha256 = MessageDigest.getInstance("SHA-256")

        for (entry in rawEntries) {
            val payload = reader.readBytes(entry.size)

            // Verify CRC32
            val actualCrc = crc32(payload)
            if (actualCrc != entry.crc32) {
                throw MnxFormatException(
                    "CRC32 mismatch for section typeId=0x${entry.typeId.toUInt().toString(16)}: " +
                    "expected 0x${entry.crc32.toUInt().toString(16)}, " +
                    "got 0x${actualCrc.toUInt().toString(16)}"
                )
            }

            sha256.update(payload)

            // Route to known sections or raw sections
            val knownType = MnxSectionType.fromTypeId(entry.typeId)
            if (knownType != null) {
                sections[knownType] = payload
            } else {
                // Unknown/user-defined section: preserve as raw bytes
                rawSections[entry.typeId] = payload
            }
        }

        // ---- Read footer ----------------------------------------------------
        val expectedSha256 = reader.readBytes(32)
        val footerMagic = reader.readInt()

        if (footerMagic != MnxFormat.FOOTER_MAGIC) {
            throw MnxFormatException(
                "Invalid footer magic: 0x${footerMagic.toUInt().toString(16)}"
            )
        }

        val actualSha256 = sha256.digest()
        if (!actualSha256.contentEquals(expectedSha256)) {
            throw MnxFormatException("SHA-256 checksum mismatch — file may be corrupted")
        }

        val footer = MnxFooter(expectedSha256, footerMagic)

        return MnxFile(header, sections, rawSections, footer)
        } catch (e: EOFException) {
            throw MnxFormatException("Truncated or incomplete MNX data", e)
        } catch (e: IOException) {
            throw MnxFormatException("I/O error reading MNX data: ${e.message}", e)
        }
    }

    /**
     * Decode from a byte array.
     */
    fun decodeFromBytes(data: ByteArray): MnxFile {
        return decode(ByteArrayInputStream(data))
    }

    /**
     * Decode from a [File].
     */
    fun decodeFromFile(file: File): MnxFile {
        return FileInputStream(file).buffered().use { decode(it) }
    }

    // =========================================================================
    //  HEADER
    // =========================================================================

    private fun writeHeader(w: MnxWriter, h: MnxHeader) {
        // Compute CRC32 of header fields (bytes 0–55)
        val headerBytes = mnxSerialize {
            writeInt(MnxFormat.MAGIC)               //  0: magic (4)
            writeByte(h.versionMajor)                //  4: version major (1)
            writeByte(h.versionMinor)                //  5: version minor (1)
            writeByte(h.versionPatch)                //  6: version patch (1)
            writeByte(h.flags)                       //  7: flags (1)
            writeLong(h.createdTimestamp)             //  8: created (8)
            writeLong(h.modifiedTimestamp)            // 16: modified (8)
            writeShort(h.sectionCount)               // 24: section count (2)
            writeInt(h.sectionTableOffset)           // 26: table offset (4)
            writeShort(0)                            // 30: reserved (2)
            writeUuid(h.fileUuid)                    // 32: UUID (16)
            writeLong(h.totalUncompressedSize)        // 48: total size (8)
        }
        // headerBytes is 56 bytes; CRC32 at bytes 56-59, reserved at 60-63
        val crc = crc32(headerBytes)

        w.writeBytes(headerBytes)
        w.writeInt(crc)                              // 56: header CRC32 (4)
        w.writeInt(0)                                // 60: reserved (4)
    }

    private fun readHeader(r: MnxReader): MnxHeader {
        val magic = r.readInt()
        if (magic != MnxFormat.MAGIC) {
            throw MnxFormatException(
                "Invalid magic bytes: 0x${magic.toUInt().toString(16)} " +
                "(expected 0x${MnxFormat.MAGIC.toUInt().toString(16)})"
            )
        }

        val vMajor = r.readByte()
        val vMinor = r.readByte()
        val vPatch = r.readByte()
        val flags = r.readByte()
        val created = r.readLong()
        val modified = r.readLong()
        val sectionCount = r.readShort()
        val tableOffset = r.readInt()
        r.readShort() // reserved
        val uuid = r.readUuid()
        val totalSize = r.readLong()
        val headerCrc = r.readInt()
        r.readInt() // reserved

        return MnxHeader(
            versionMajor = vMajor,
            versionMinor = vMinor,
            versionPatch = vPatch,
            flags = flags,
            createdTimestamp = created,
            modifiedTimestamp = modified,
            sectionCount = sectionCount,
            sectionTableOffset = tableOffset,
            fileUuid = uuid,
            totalUncompressedSize = totalSize,
            headerCrc32 = headerCrc
        )
    }

    // =========================================================================
    //  SECTION SERIALIZERS
    // =========================================================================

    /**
     * Serialize an [MnxIdentity] to bytes.
     */
    fun serializeIdentity(identity: MnxIdentity): ByteArray = mnxSerialize {
        writeString(identity.name)
        writeLong(identity.createdAt)
        writeString(identity.species)
        writeString(identity.pronouns)
        writeStringList(identity.coreTraits)
        writeString(identity.biography)
        writeStringMap(identity.attributes)
    }

    fun deserializeIdentity(data: ByteArray): MnxIdentity = mnxDeserialize(data) {
        MnxIdentity(
            name = readString(),
            createdAt = readLong(),
            species = readString(),
            pronouns = readString(),
            coreTraits = readStringList(),
            biography = readString(),
            attributes = readStringMap()
        )
    }

    /**
     * Serialize an [MnxMemoryStore] to bytes.
     */
    fun serializeMemoryStore(store: MnxMemoryStore): ByteArray = mnxSerialize {
        writeList(store.chunks) { chunk ->
            writeString(chunk.chunkId)
            writeString(chunk.content)
            writeString(chunk.source)
            writeString(chunk.sourceType)
            writeString(chunk.timestamp)
            writeInt(chunk.tokenCount)
            writeFloat(chunk.qValue)
            writeInt(chunk.retrievalCount)
            writeInt(chunk.successCount)
            writeString(chunk.memoryStage)
            writeNullableFloatArray(chunk.embedding)
            writeMetadataMap(chunk.metadata)
        }
    }

    fun deserializeMemoryStore(data: ByteArray): MnxMemoryStore = mnxDeserialize(data) {
        val chunks = readList {
            MnxMemoryChunk(
                chunkId = readString(),
                content = readString(),
                source = readString(),
                sourceType = readString(),
                timestamp = readString(),
                tokenCount = readInt(),
                qValue = readFloat(),
                retrievalCount = readInt(),
                successCount = readInt(),
                memoryStage = readString(),
                embedding = readNullableFloatArray(),
                metadata = readMetadataMap()
            )
        }
        MnxMemoryStore(chunks)
    }

    /**
     * Serialize an [MnxKnowledgeGraph] to bytes.
     */
    fun serializeKnowledgeGraph(graph: MnxKnowledgeGraph): ByteArray = mnxSerialize {
        // Entities
        writeList(graph.entities) { e ->
            writeString(e.entityId)
            writeString(e.name)
            writeString(e.entityType)
            writeString(e.description)
            writeInt(e.mentionCount)
            writeLong(e.lastSeen)
        }
        // Chunk nodes
        writeList(graph.chunkNodes) { c ->
            writeString(c.chunkId)
            writeString(c.summary)
            writeStringList(c.entityIds)
        }
        // Relationship edges
        writeList(graph.edges) { r ->
            writeString(r.sourceEntityId)
            writeString(r.targetEntityId)
            writeString(r.relationship)
            writeFloat(r.strength)
            writeStringList(r.keywords)
        }
    }

    fun deserializeKnowledgeGraph(data: ByteArray): MnxKnowledgeGraph = mnxDeserialize(data) {
        val entities = readList {
            MnxEntityNode(
                entityId = readString(),
                name = readString(),
                entityType = readString(),
                description = readString(),
                mentionCount = readInt(),
                lastSeen = readLong()
            )
        }
        val chunkNodes = readList {
            MnxChunkNode(
                chunkId = readString(),
                summary = readString(),
                entityIds = readStringList()
            )
        }
        val edges = readList {
            MnxRelationshipEdge(
                sourceEntityId = readString(),
                targetEntityId = readString(),
                relationship = readString(),
                strength = readFloat(),
                keywords = readStringList()
            )
        }
        MnxKnowledgeGraph(entities, chunkNodes, edges)
    }

    /**
     * Serialize an [MnxAffectState] to bytes.
     */
    fun serializeAffectState(state: MnxAffectState): ByteArray = mnxSerialize {
        writeStringFloatMap(state.dimensions)
        writeLong(state.timestamp)
    }

    fun deserializeAffectState(data: ByteArray): MnxAffectState = mnxDeserialize(data) {
        MnxAffectState(
            dimensions = readStringFloatMap(),
            timestamp = readLong()
        )
    }

    /**
     * Serialize an [MnxAffectLog] to bytes.
     */
    fun serializeAffectLog(log: MnxAffectLog): ByteArray = mnxSerialize {
        writeList(log.entries) { e ->
            writeString(e.timestamp)
            writeStringFloatMap(e.affectVector)
            writeStringList(e.inputSources)
            writeStringMap(e.expressionCommands)
            writeFloat(e.motorNoiseLevel)
            writeStringFloatMap(e.noiseDistribution)
            writeString(e.hash)
        }
        writeString(log.lastHash)
        writeLong(log.entryCount)
    }

    fun deserializeAffectLog(data: ByteArray): MnxAffectLog = mnxDeserialize(data) {
        val entries = readList {
            MnxAffectLogEntry(
                timestamp = readString(),
                affectVector = readStringFloatMap(),
                inputSources = readStringList(),
                expressionCommands = readStringMap(),
                motorNoiseLevel = readFloat(),
                noiseDistribution = readStringFloatMap(),
                hash = readString()
            )
        }
        MnxAffectLog(
            entries = entries,
            lastHash = readString(),
            entryCount = readLong()
        )
    }

    /**
     * Serialize an [MnxExpressionMap] to bytes.
     */
    fun serializeExpressionMap(expr: MnxExpressionMap): ByteArray = mnxSerialize {
        writeStringMap(expr.channels)
        writeStringFloatMap(expr.noiseChannels)
        writeStringFloatMap(expr.noiseSensitivities)
    }

    fun deserializeExpressionMap(data: ByteArray): MnxExpressionMap = mnxDeserialize(data) {
        MnxExpressionMap(
            channels = readStringMap(),
            noiseChannels = readStringFloatMap(),
            noiseSensitivities = readStringFloatMap()
        )
    }

    /**
     * Serialize an [MnxPersonality] to bytes.
     */
    fun serializePersonality(p: MnxPersonality): ByteArray = mnxSerialize {
        writeStringFloatMap(p.traits)
        writeString(p.currentEmotion)
        writeFloat(p.emotionalIntensity)
        writeStringFloatMap(p.emotionalBias)
        writeInt(p.curiosityStreak)
        writeInt(p.embarrassmentCount)
    }

    fun deserializePersonality(data: ByteArray): MnxPersonality = mnxDeserialize(data) {
        MnxPersonality(
            traits = readStringFloatMap(),
            currentEmotion = readString(),
            emotionalIntensity = readFloat(),
            emotionalBias = readStringFloatMap(),
            curiosityStreak = readInt(),
            embarrassmentCount = readInt()
        )
    }

    /**
     * Serialize an [MnxBeliefStore] to bytes.
     */
    fun serializeBeliefStore(store: MnxBeliefStore): ByteArray = mnxSerialize {
        writeList(store.beliefs) { topic ->
            writeString(topic.topic)
            writeList(topic.versions) { v ->
                writeInt(v.version)
                writeString(v.belief)
                writeFloat(v.confidence)
                writeString(v.reason)
                writeLong(v.timestamp)
            }
        }
    }

    fun deserializeBeliefStore(data: ByteArray): MnxBeliefStore = mnxDeserialize(data) {
        val beliefs = readList {
            MnxBeliefTopic(
                topic = readString(),
                versions = readList {
                    MnxBeliefVersion(
                        version = readInt(),
                        belief = readString(),
                        confidence = readFloat(),
                        reason = readString(),
                        timestamp = readLong()
                    )
                }
            )
        }
        MnxBeliefStore(beliefs)
    }

    /**
     * Serialize an [MnxValueAlignment] to bytes.
     */
    fun serializeValueAlignment(va: MnxValueAlignment): ByteArray = mnxSerialize {
        writeList(va.values) { v ->
            writeString(v.name)
            writeString(v.description)
            writeInt(v.weight)
            writeLong(v.createdAt)
        }
    }

    fun deserializeValueAlignment(data: ByteArray): MnxValueAlignment = mnxDeserialize(data) {
        val values = readList {
            MnxValue(
                name = readString(),
                description = readString(),
                weight = readInt(),
                createdAt = readLong()
            )
        }
        MnxValueAlignment(values)
    }

    /**
     * Serialize an [MnxRelationshipWeb] to bytes.
     */
    fun serializeRelationshipWeb(web: MnxRelationshipWeb): ByteArray = mnxSerialize {
        writeList(web.relationships) { r ->
            writeString(r.entityName)
            writeString(r.relationshipType)
            writeLong(r.createdAt)
            writeList(r.interactions) { i ->
                writeInt(i.quality)
                writeString(i.context)
                writeLong(i.timestamp)
            }
        }
    }

    fun deserializeRelationshipWeb(data: ByteArray): MnxRelationshipWeb = mnxDeserialize(data) {
        val relationships = readList {
            MnxRelationship(
                entityName = readString(),
                relationshipType = readString(),
                createdAt = readLong(),
                interactions = readList {
                    MnxInteraction(
                        quality = readInt(),
                        context = readString(),
                        timestamp = readLong()
                    )
                }
            )
        }
        MnxRelationshipWeb(relationships)
    }

    /**
     * Serialize an [MnxPreferenceStore] to bytes.
     */
    fun serializePreferenceStore(store: MnxPreferenceStore): ByteArray = mnxSerialize {
        // Observations: Map<String, List<MnxObservation>>
        writeInt(store.observations.size)
        for ((category, observations) in store.observations) {
            writeString(category)
            writeList(observations) { o ->
                writeString(o.text)
                writeLong(o.timestamp)
            }
        }
        // Crystals: Map<String, MnxCrystal>
        writeInt(store.crystals.size)
        for ((category, crystal) in store.crystals) {
            writeString(category)
            writeString(crystal.category)
            writeString(crystal.summary)
            writeDouble(crystal.confidence)
            writeInt(crystal.observationCount)
            writeStringList(crystal.topKeywords)
            writeLong(crystal.crystallizedAt)
        }
    }

    fun deserializePreferenceStore(data: ByteArray): MnxPreferenceStore = mnxDeserialize(data) {
        // Observations
        val obsCount = readInt()
        val observations = LinkedHashMap<String, List<MnxObservation>>(obsCount)
        repeat(obsCount) {
            val category = readString()
            val obs = readList {
                MnxObservation(text = readString(), timestamp = readLong())
            }
            observations[category] = obs
        }
        // Crystals
        val crystalCount = readInt()
        val crystals = LinkedHashMap<String, MnxCrystal>(crystalCount)
        repeat(crystalCount) {
            val key = readString()
            crystals[key] = MnxCrystal(
                category = readString(),
                summary = readString(),
                confidence = readDouble(),
                observationCount = readInt(),
                topKeywords = readStringList(),
                crystallizedAt = readLong()
            )
        }
        MnxPreferenceStore(observations, crystals)
    }

    /**
     * Serialize an [MnxTimeline] to bytes.
     */
    fun serializeTimeline(timeline: MnxTimeline): ByteArray = mnxSerialize {
        writeList(timeline.milestones) { m ->
            writeString(m.id)
            writeLong(m.timestamp)
            writeString(m.category)
            writeString(m.description)
            writeFloat(m.significance)
            writeBoolean(m.isFirst)
            writeStringMap(m.metadata)
        }
        // Firsts map
        writeInt(timeline.firsts.size)
        for ((category, time) in timeline.firsts) {
            writeString(category)
            writeLong(time)
        }
    }

    fun deserializeTimeline(data: ByteArray): MnxTimeline = mnxDeserialize(data) {
        val milestones = readList {
            MnxMilestone(
                id = readString(),
                timestamp = readLong(),
                category = readString(),
                description = readString(),
                significance = readFloat(),
                isFirst = readBoolean(),
                metadata = readStringMap()
            )
        }
        val firstsCount = readInt()
        val firsts = LinkedHashMap<String, Long>(firstsCount)
        repeat(firstsCount) {
            firsts[readString()] = readLong()
        }
        MnxTimeline(milestones, firsts)
    }

    /**
     * Serialize an [MnxEmbeddingIndex] to bytes.
     */
    fun serializeEmbeddingIndex(index: MnxEmbeddingIndex): ByteArray = mnxSerialize {
        writeInt(index.dimensionality)
        writeList(index.entries) { e ->
            writeString(e.chunkId)
            writeFloatArray(e.vector)
        }
    }

    fun deserializeEmbeddingIndex(data: ByteArray): MnxEmbeddingIndex = mnxDeserialize(data) {
        val dim = readInt()
        val entries = readList {
            MnxEmbeddingEntry(
                chunkId = readString(),
                vector = readFloatArray()
            )
        }
        MnxEmbeddingIndex(dim, entries)
    }

    /**
     * Serialize an [MnxOpinionMap] to bytes.
     */
    fun serializeOpinionMap(opinions: MnxOpinionMap): ByteArray = mnxSerialize {
        writeList(opinions.opinions) { o ->
            writeString(o.entityName)
            writeString(o.entityType)
            writeFloat(o.valence)
            writeFloat(o.confidence)
            writeString(o.basis)
            writeLong(o.formedAt)
            writeLong(o.updatedAt)
            writeStringList(o.tags)
        }
    }

    fun deserializeOpinionMap(data: ByteArray): MnxOpinionMap = mnxDeserialize(data) {
        val opinions = readList {
            MnxOpinion(
                entityName = readString(),
                entityType = readString(),
                valence = readFloat(),
                confidence = readFloat(),
                basis = readString(),
                formedAt = readLong(),
                updatedAt = readLong(),
                tags = readStringList()
            )
        }
        MnxOpinionMap(opinions)
    }

    /**
     * Serialize an [MnxAttachmentManifest] to bytes.
     */
    fun serializeAttachmentManifest(manifest: MnxAttachmentManifest): ByteArray = mnxSerialize {
        writeList(manifest.attachments) { a ->
            writeString(a.attachmentId)
            writeString(a.mimeType)
            writeString(a.filename)
            writeLong(a.sizeBytes)
            writeString(a.description)
            writeLong(a.createdAt)
            writeString(a.checksum)
            writeStringList(a.associatedEntities)
            writeStringList(a.associatedChunks)
            writeStringList(a.tags)
            writeStringMap(a.attributes)
        }
    }

    fun deserializeAttachmentManifest(data: ByteArray): MnxAttachmentManifest = mnxDeserialize(data) {
        val attachments = readList {
            MnxAttachment(
                attachmentId = readString(),
                mimeType = readString(),
                filename = readString(),
                sizeBytes = readLong(),
                description = readString(),
                createdAt = readLong(),
                checksum = readString(),
                associatedEntities = readStringList(),
                associatedChunks = readStringList(),
                tags = readStringList(),
                attributes = readStringMap()
            )
        }
        MnxAttachmentManifest(attachments)
    }

    /**
     * Serialize an [MnxAttachmentData] to bytes.
     *
     * Each blob is stored as: attachmentId (string) + length (int) + raw bytes.
     */
    fun serializeAttachmentData(attachmentData: MnxAttachmentData): ByteArray = mnxSerialize {
        writeList(attachmentData.blobs) { blob ->
            writeString(blob.attachmentId)
            writeInt(blob.data.size)
            writeBytes(blob.data)
        }
    }

    fun deserializeAttachmentData(data: ByteArray): MnxAttachmentData = mnxDeserialize(data) {
        val blobs = readList {
            val id = readString()
            val size = readInt()
            val blobData = readBytes(size)
            MnxAttachmentBlob(id, blobData)
        }
        MnxAttachmentData(blobs)
    }

    /**
     * Serialize an [MnxSensoryAssociations] to bytes.
     */
    fun serializeSensoryAssociations(assoc: MnxSensoryAssociations): ByteArray = mnxSerialize {
        writeList(assoc.links) { link ->
            writeString(link.entityId)
            writeString(link.modality)
            writeStringList(link.attachmentIds)
            writeString(link.description)
            writeFloat(link.intensity)
            writeFloat(link.valence)
        }
    }

    fun deserializeSensoryAssociations(data: ByteArray): MnxSensoryAssociations = mnxDeserialize(data) {
        val links = readList {
            MnxSensoryLink(
                entityId = readString(),
                modality = readString(),
                attachmentIds = readStringList(),
                description = readString(),
                intensity = readFloat(),
                valence = readFloat()
            )
        }
        MnxSensoryAssociations(links)
    }

    /**
     * Serialize an [MnxDimensionalRefs] to bytes.
     */
    fun serializeDimensionalRefs(refs: MnxDimensionalRefs): ByteArray = mnxSerialize {
        writeList(refs.refs) { ref ->
            writeString(ref.subject)
            writeString(ref.dimension)
            writeString(ref.target)
            writeString(ref.targetType)
            writeFloat(ref.confidence)
            writeStringMap(ref.metadata)
        }
    }

    fun deserializeDimensionalRefs(data: ByteArray): MnxDimensionalRefs = mnxDeserialize(data) {
        val refs = readList {
            MnxDimensionalRef(
                subject = readString(),
                dimension = readString(),
                target = readString(),
                targetType = readString(),
                confidence = readFloat(),
                metadata = readStringMap()
            )
        }
        MnxDimensionalRefs(refs)
    }

    /**
     * Serialize an [MnxMeta] to bytes.
     */
    fun serializeMeta(meta: MnxMeta): ByteArray = mnxSerialize {
        writeStringMap(meta.entries)
    }

    fun deserializeMeta(data: ByteArray): MnxMeta = mnxDeserialize(data) {
        MnxMeta(readStringMap())
    }

    // =========================================================================
    //  UTILITIES
    // =========================================================================

    private fun crc32(data: ByteArray): Int {
        val crc = CRC32()
        crc.update(data)
        return crc.value.toInt()
    }
}
