package com.tronprotocol.app.mindnexus

import android.content.Context
import android.util.Log
import com.tronprotocol.app.affect.AffectDimension
import com.tronprotocol.app.affect.AffectOrchestrator
import com.tronprotocol.app.affect.AffectState
import com.tronprotocol.app.affect.MotorNoise
import com.tronprotocol.app.emotion.EmotionalStateManager
import com.tronprotocol.app.rag.BeliefVersioningManager
import com.tronprotocol.app.rag.AutobiographicalTimeline
import com.tronprotocol.app.rag.KnowledgeGraph
import com.tronprotocol.app.rag.RAGStore
import com.tronprotocol.app.rag.TextChunk
import java.io.File
import java.util.UUID

/**
 * High-level API for creating, reading, and applying Mind Nexus (.mnx) files.
 *
 * This manager bridges the live AI subsystems (RAG, Affect, Emotion, etc.)
 * and the portable .mnx binary format. It can:
 *
 * - **Snapshot**: Capture the complete psyche of the running AI into an .mnx file.
 * - **Restore**: Load an .mnx file and apply it back to the live subsystems.
 * - **Inspect**: Read an .mnx file and extract individual sections without
 *   applying them.
 *
 * ## Usage
 * ```kotlin
 * val manager = MindNexusManager(context)
 *
 * // Snapshot current state
 * val file = manager.snapshot(
 *     name = "Pyn",
 *     ragStore = ragStore,
 *     affectOrchestrator = orchestrator,
 *     emotionalStateManager = emotionManager,
 *     beliefManager = beliefManager,
 *     timeline = timeline
 * )
 * manager.saveToDisk(file, outputPath)
 *
 * // Restore from file
 * val loaded = manager.loadFromDisk(inputPath)
 * val identity = manager.extractIdentity(loaded)
 * ```
 */
class MindNexusManager(private val context: Context) {

    // =========================================================================
    //  SNAPSHOT — Capture live state into MnxFile
    // =========================================================================

    /**
     * Capture a complete snapshot of the AI's current psyche.
     *
     * Each parameter is optional — only provided subsystems are serialized.
     * Missing subsystems result in missing sections (which is valid).
     *
     * @param name               The AI's identity name.
     * @param species            Self-identified species/type.
     * @param pronouns           Preferred pronouns.
     * @param coreTraits         Immutable personality descriptors.
     * @param biography          Free-text origin story.
     * @param ragStore           Live RAG memory store (memories + knowledge graph).
     * @param affectOrchestrator Live affect system (state + log + expression + noise).
     * @param emotionalStateManager Live emotional profile (traits, biases).
     * @param beliefManager      Versioned belief system.
     * @param timeline           Autobiographical milestones.
     * @param opinions           Pre-built opinion map (if available).
     * @param preferences        Pre-built preference store (if available).
     * @param values             Pre-built value alignment (if available).
     * @param relationships      Pre-built relationship web (if available).
     * @param metadata           Arbitrary metadata entries.
     * @return                   A complete [MnxFile] ready for serialization.
     */
    fun snapshot(
        name: String,
        species: String = "",
        pronouns: String = "",
        coreTraits: List<String> = emptyList(),
        biography: String = "",
        ragStore: RAGStore? = null,
        affectOrchestrator: AffectOrchestrator? = null,
        emotionalStateManager: EmotionalStateManager? = null,
        beliefManager: BeliefVersioningManager? = null,
        timeline: AutobiographicalTimeline? = null,
        opinions: MnxOpinionMap? = null,
        preferences: MnxPreferenceStore? = null,
        values: MnxValueAlignment? = null,
        relationships: MnxRelationshipWeb? = null,
        attachments: MnxAttachmentManifest? = null,
        attachmentData: MnxAttachmentData? = null,
        sensoryAssociations: MnxSensoryAssociations? = null,
        dimensionalRefs: MnxDimensionalRefs? = null,
        metadata: Map<String, String> = emptyMap()
    ): MnxFile {
        val sections = LinkedHashMap<MnxSectionType, ByteArray>()

        // ---- IDENTITY -------------------------------------------------------
        val identity = MnxIdentity(
            name = name,
            createdAt = System.currentTimeMillis(),
            species = species,
            pronouns = pronouns,
            coreTraits = coreTraits,
            biography = biography
        )
        sections[MnxSectionType.IDENTITY] = MnxCodec.serializeIdentity(identity)
        Log.d(TAG, "Snapshot: IDENTITY for '$name'")

        // ---- MEMORY_STORE ---------------------------------------------------
        if (ragStore != null) {
            try {
                val chunks = ragStore.getChunks()
                val mnxChunks = chunks.map { chunk -> chunkToMnx(chunk) }
                val store = MnxMemoryStore(mnxChunks)
                sections[MnxSectionType.MEMORY_STORE] = MnxCodec.serializeMemoryStore(store)
                Log.d(TAG, "Snapshot: MEMORY_STORE with ${mnxChunks.size} chunks")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to snapshot MEMORY_STORE", e)
            }
        }

        // ---- KNOWLEDGE_GRAPH ------------------------------------------------
        if (ragStore != null) {
            try {
                val graph = snapshotKnowledgeGraph(ragStore.knowledgeGraph)
                sections[MnxSectionType.KNOWLEDGE_GRAPH] = MnxCodec.serializeKnowledgeGraph(graph)
                Log.d(TAG, "Snapshot: KNOWLEDGE_GRAPH with ${graph.entities.size} entities, ${graph.edges.size} edges")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to snapshot KNOWLEDGE_GRAPH", e)
            }
        }

        // ---- AFFECT_STATE ---------------------------------------------------
        if (affectOrchestrator != null) {
            try {
                val state = snapshotAffectState(affectOrchestrator)
                sections[MnxSectionType.AFFECT_STATE] = MnxCodec.serializeAffectState(state)
                Log.d(TAG, "Snapshot: AFFECT_STATE (${state.dimensions.size} dimensions)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to snapshot AFFECT_STATE", e)
            }
        }

        // ---- AFFECT_LOG -----------------------------------------------------
        if (affectOrchestrator != null) {
            try {
                val log = snapshotAffectLog(affectOrchestrator)
                sections[MnxSectionType.AFFECT_LOG] = MnxCodec.serializeAffectLog(log)
                Log.d(TAG, "Snapshot: AFFECT_LOG with ${log.entries.size} entries")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to snapshot AFFECT_LOG", e)
            }
        }

        // ---- EXPRESSION_MAP -------------------------------------------------
        if (affectOrchestrator != null) {
            try {
                val expr = snapshotExpressionMap(affectOrchestrator)
                sections[MnxSectionType.EXPRESSION_MAP] = MnxCodec.serializeExpressionMap(expr)
                Log.d(TAG, "Snapshot: EXPRESSION_MAP")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to snapshot EXPRESSION_MAP", e)
            }
        }

        // ---- PERSONALITY ----------------------------------------------------
        if (emotionalStateManager != null) {
            try {
                val personality = snapshotPersonality(emotionalStateManager)
                sections[MnxSectionType.PERSONALITY] = MnxCodec.serializePersonality(personality)
                Log.d(TAG, "Snapshot: PERSONALITY (${personality.traits.size} traits)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to snapshot PERSONALITY", e)
            }
        }

        // ---- BELIEF_STORE ---------------------------------------------------
        if (beliefManager != null) {
            try {
                val store = snapshotBeliefStore(beliefManager)
                sections[MnxSectionType.BELIEF_STORE] = MnxCodec.serializeBeliefStore(store)
                Log.d(TAG, "Snapshot: BELIEF_STORE with ${store.beliefs.size} topics")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to snapshot BELIEF_STORE", e)
            }
        }

        // ---- VALUE_ALIGNMENT ------------------------------------------------
        if (values != null) {
            sections[MnxSectionType.VALUE_ALIGNMENT] = MnxCodec.serializeValueAlignment(values)
            Log.d(TAG, "Snapshot: VALUE_ALIGNMENT with ${values.values.size} values")
        }

        // ---- RELATIONSHIP_WEB -----------------------------------------------
        if (relationships != null) {
            sections[MnxSectionType.RELATIONSHIP_WEB] = MnxCodec.serializeRelationshipWeb(relationships)
            Log.d(TAG, "Snapshot: RELATIONSHIP_WEB with ${relationships.relationships.size} relationships")
        }

        // ---- PREFERENCE_STORE -----------------------------------------------
        if (preferences != null) {
            sections[MnxSectionType.PREFERENCE_STORE] = MnxCodec.serializePreferenceStore(preferences)
            Log.d(TAG, "Snapshot: PREFERENCE_STORE")
        }

        // ---- TIMELINE -------------------------------------------------------
        if (timeline != null) {
            try {
                val tl = snapshotTimeline(timeline)
                sections[MnxSectionType.TIMELINE] = MnxCodec.serializeTimeline(tl)
                Log.d(TAG, "Snapshot: TIMELINE with ${tl.milestones.size} milestones")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to snapshot TIMELINE", e)
            }
        }

        // ---- EMBEDDING_INDEX ------------------------------------------------
        if (ragStore != null) {
            try {
                val index = snapshotEmbeddings(ragStore)
                if (index.entries.isNotEmpty()) {
                    sections[MnxSectionType.EMBEDDING_INDEX] = MnxCodec.serializeEmbeddingIndex(index)
                    Log.d(TAG, "Snapshot: EMBEDDING_INDEX with ${index.entries.size} vectors (dim=${index.dimensionality})")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to snapshot EMBEDDING_INDEX", e)
            }
        }

        // ---- OPINION_MAP ----------------------------------------------------
        if (opinions != null) {
            sections[MnxSectionType.OPINION_MAP] = MnxCodec.serializeOpinionMap(opinions)
            Log.d(TAG, "Snapshot: OPINION_MAP with ${opinions.opinions.size} opinions")
        }

        // ---- ATTACHMENT_MANIFEST --------------------------------------------
        if (attachments != null) {
            sections[MnxSectionType.ATTACHMENT_MANIFEST] = MnxCodec.serializeAttachmentManifest(attachments)
            Log.d(TAG, "Snapshot: ATTACHMENT_MANIFEST with ${attachments.attachments.size} attachments")
        }

        // ---- ATTACHMENT_DATA ------------------------------------------------
        if (attachmentData != null) {
            sections[MnxSectionType.ATTACHMENT_DATA] = MnxCodec.serializeAttachmentData(attachmentData)
            Log.d(TAG, "Snapshot: ATTACHMENT_DATA with ${attachmentData.blobs.size} blobs")
        }

        // ---- SENSORY_ASSOCIATIONS -------------------------------------------
        if (sensoryAssociations != null) {
            sections[MnxSectionType.SENSORY_ASSOCIATIONS] = MnxCodec.serializeSensoryAssociations(sensoryAssociations)
            Log.d(TAG, "Snapshot: SENSORY_ASSOCIATIONS with ${sensoryAssociations.links.size} links")
        }

        // ---- DIMENSIONAL_REFS -----------------------------------------------
        if (dimensionalRefs != null) {
            sections[MnxSectionType.DIMENSIONAL_REFS] = MnxCodec.serializeDimensionalRefs(dimensionalRefs)
            Log.d(TAG, "Snapshot: DIMENSIONAL_REFS with ${dimensionalRefs.refs.size} refs")
        }

        // ---- META -----------------------------------------------------------
        val allMeta = mutableMapOf(
            "format_version" to MnxFormat.VERSION_MAJOR.toString() + "." +
                    MnxFormat.VERSION_MINOR + "." + MnxFormat.VERSION_PATCH,
            "snapshot_time" to System.currentTimeMillis().toString(),
            "app_package" to "com.tronprotocol.app"
        )
        allMeta.putAll(metadata)
        sections[MnxSectionType.META] = MnxCodec.serializeMeta(MnxMeta(allMeta))

        // ---- Build MnxFile --------------------------------------------------
        val header = MnxHeader(
            fileUuid = UUID.randomUUID(),
            createdTimestamp = System.currentTimeMillis(),
            sectionCount = sections.size.toShort()
        )

        val mnxFile = MnxFile(header, sections)
        Log.d(TAG, "Snapshot complete: ${sections.size} sections, " +
                "${mnxFile.totalPayloadSize} bytes payload")
        return mnxFile
    }

    // =========================================================================
    //  SAVE / LOAD
    // =========================================================================

    /**
     * Write an [MnxFile] to disk.
     */
    fun saveToDisk(file: MnxFile, path: String) {
        val outputFile = File(path)
        outputFile.parentFile?.mkdirs()
        MnxCodec.encodeToFile(file, outputFile)
        Log.d(TAG, "Saved .mnx file to $path (${outputFile.length()} bytes)")
    }

    /**
     * Load an [MnxFile] from disk.
     */
    fun loadFromDisk(path: String): MnxFile {
        val file = File(path)
        if (!file.exists()) {
            throw MnxFormatException("File not found: $path")
        }
        val mnxFile = MnxCodec.decodeFromFile(file)
        Log.d(TAG, "Loaded .mnx file from $path: ${mnxFile.sectionCount} sections, " +
                "version ${mnxFile.header.version}, UUID ${mnxFile.header.fileUuid}")
        return mnxFile
    }

    /**
     * Get the default directory for .mnx files on this device.
     */
    fun getDefaultDirectory(): File {
        val dir = File(context.filesDir, "mindnexus")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * List all .mnx files in the default directory.
     */
    fun listFiles(): List<File> {
        return getDefaultDirectory().listFiles { _, name ->
            name.endsWith(MnxFormat.FILE_EXTENSION)
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    // =========================================================================
    //  SECTION EXTRACTION — Read individual sections from a loaded MnxFile
    // =========================================================================

    fun extractIdentity(file: MnxFile): MnxIdentity? {
        val data = file.sections[MnxSectionType.IDENTITY] ?: return null
        return MnxCodec.deserializeIdentity(data)
    }

    fun extractMemoryStore(file: MnxFile): MnxMemoryStore? {
        val data = file.sections[MnxSectionType.MEMORY_STORE] ?: return null
        return MnxCodec.deserializeMemoryStore(data)
    }

    fun extractKnowledgeGraph(file: MnxFile): MnxKnowledgeGraph? {
        val data = file.sections[MnxSectionType.KNOWLEDGE_GRAPH] ?: return null
        return MnxCodec.deserializeKnowledgeGraph(data)
    }

    fun extractAffectState(file: MnxFile): MnxAffectState? {
        val data = file.sections[MnxSectionType.AFFECT_STATE] ?: return null
        return MnxCodec.deserializeAffectState(data)
    }

    fun extractAffectLog(file: MnxFile): MnxAffectLog? {
        val data = file.sections[MnxSectionType.AFFECT_LOG] ?: return null
        return MnxCodec.deserializeAffectLog(data)
    }

    fun extractExpressionMap(file: MnxFile): MnxExpressionMap? {
        val data = file.sections[MnxSectionType.EXPRESSION_MAP] ?: return null
        return MnxCodec.deserializeExpressionMap(data)
    }

    fun extractPersonality(file: MnxFile): MnxPersonality? {
        val data = file.sections[MnxSectionType.PERSONALITY] ?: return null
        return MnxCodec.deserializePersonality(data)
    }

    fun extractBeliefStore(file: MnxFile): MnxBeliefStore? {
        val data = file.sections[MnxSectionType.BELIEF_STORE] ?: return null
        return MnxCodec.deserializeBeliefStore(data)
    }

    fun extractValueAlignment(file: MnxFile): MnxValueAlignment? {
        val data = file.sections[MnxSectionType.VALUE_ALIGNMENT] ?: return null
        return MnxCodec.deserializeValueAlignment(data)
    }

    fun extractRelationshipWeb(file: MnxFile): MnxRelationshipWeb? {
        val data = file.sections[MnxSectionType.RELATIONSHIP_WEB] ?: return null
        return MnxCodec.deserializeRelationshipWeb(data)
    }

    fun extractPreferenceStore(file: MnxFile): MnxPreferenceStore? {
        val data = file.sections[MnxSectionType.PREFERENCE_STORE] ?: return null
        return MnxCodec.deserializePreferenceStore(data)
    }

    fun extractTimeline(file: MnxFile): MnxTimeline? {
        val data = file.sections[MnxSectionType.TIMELINE] ?: return null
        return MnxCodec.deserializeTimeline(data)
    }

    fun extractEmbeddingIndex(file: MnxFile): MnxEmbeddingIndex? {
        val data = file.sections[MnxSectionType.EMBEDDING_INDEX] ?: return null
        return MnxCodec.deserializeEmbeddingIndex(data)
    }

    fun extractOpinionMap(file: MnxFile): MnxOpinionMap? {
        val data = file.sections[MnxSectionType.OPINION_MAP] ?: return null
        return MnxCodec.deserializeOpinionMap(data)
    }

    fun extractAttachmentManifest(file: MnxFile): MnxAttachmentManifest? {
        val data = file.sections[MnxSectionType.ATTACHMENT_MANIFEST] ?: return null
        return MnxCodec.deserializeAttachmentManifest(data)
    }

    fun extractAttachmentData(file: MnxFile): MnxAttachmentData? {
        val data = file.sections[MnxSectionType.ATTACHMENT_DATA] ?: return null
        return MnxCodec.deserializeAttachmentData(data)
    }

    fun extractSensoryAssociations(file: MnxFile): MnxSensoryAssociations? {
        val data = file.sections[MnxSectionType.SENSORY_ASSOCIATIONS] ?: return null
        return MnxCodec.deserializeSensoryAssociations(data)
    }

    fun extractDimensionalRefs(file: MnxFile): MnxDimensionalRefs? {
        val data = file.sections[MnxSectionType.DIMENSIONAL_REFS] ?: return null
        return MnxCodec.deserializeDimensionalRefs(data)
    }

    /**
     * Extract a raw (user-defined/unknown) section by its type ID.
     * Returns the raw byte array, or null if the section doesn't exist.
     */
    fun extractRawSection(file: MnxFile, typeId: Short): ByteArray? {
        return file.rawSections[typeId]
    }

    fun extractMeta(file: MnxFile): MnxMeta? {
        val data = file.sections[MnxSectionType.META] ?: return null
        return MnxCodec.deserializeMeta(data)
    }

    /**
     * Get a human-readable summary of a .mnx file's contents.
     */
    fun summarize(file: MnxFile): String {
        val sb = StringBuilder()
        sb.appendLine("=== Mind Nexus File ===")
        sb.appendLine("UUID:     ${file.header.fileUuid}")
        sb.appendLine("Version:  ${file.header.version}")
        sb.appendLine("Created:  ${file.header.createdTimestamp}")
        sb.appendLine("Modified: ${file.header.modifiedTimestamp}")
        sb.appendLine("Sections: ${file.sectionCount}")
        sb.appendLine("Payload:  ${file.totalPayloadSize} bytes")
        sb.appendLine()

        for ((type, data) in file.sections) {
            sb.appendLine("  [${type.name}] ${data.size} bytes")
            try {
                when (type) {
                    MnxSectionType.IDENTITY -> {
                        val id = extractIdentity(file)!!
                        sb.appendLine("    Name: ${id.name}")
                        sb.appendLine("    Species: ${id.species}")
                        sb.appendLine("    Traits: ${id.coreTraits.joinToString(", ")}")
                    }
                    MnxSectionType.MEMORY_STORE -> {
                        val store = extractMemoryStore(file)!!
                        sb.appendLine("    Chunks: ${store.chunks.size}")
                        val avgQ = store.chunks.map { it.qValue }.average()
                        sb.appendLine("    Avg Q-value: ${"%.3f".format(avgQ)}")
                    }
                    MnxSectionType.KNOWLEDGE_GRAPH -> {
                        val graph = extractKnowledgeGraph(file)!!
                        sb.appendLine("    Entities: ${graph.entities.size}")
                        sb.appendLine("    Chunk nodes: ${graph.chunkNodes.size}")
                        sb.appendLine("    Edges: ${graph.edges.size}")
                    }
                    MnxSectionType.AFFECT_STATE -> {
                        val state = extractAffectState(file)!!
                        for ((dim, value) in state.dimensions) {
                            sb.appendLine("    $dim: ${"%.3f".format(value)}")
                        }
                    }
                    MnxSectionType.AFFECT_LOG -> {
                        val log = extractAffectLog(file)!!
                        sb.appendLine("    Entries: ${log.entries.size}")
                        sb.appendLine("    Total recorded: ${log.entryCount}")
                    }
                    MnxSectionType.PERSONALITY -> {
                        val p = extractPersonality(file)!!
                        sb.appendLine("    Current emotion: ${p.currentEmotion}")
                        sb.appendLine("    Intensity: ${"%.3f".format(p.emotionalIntensity)}")
                        sb.appendLine("    Traits: ${p.traits.size}")
                    }
                    MnxSectionType.BELIEF_STORE -> {
                        val store = extractBeliefStore(file)!!
                        sb.appendLine("    Topics: ${store.beliefs.size}")
                        val totalVersions = store.beliefs.sumOf { it.versions.size }
                        sb.appendLine("    Total versions: $totalVersions")
                    }
                    MnxSectionType.OPINION_MAP -> {
                        val opinions = extractOpinionMap(file)!!
                        sb.appendLine("    Opinions: ${opinions.opinions.size}")
                    }
                    MnxSectionType.TIMELINE -> {
                        val tl = extractTimeline(file)!!
                        sb.appendLine("    Milestones: ${tl.milestones.size}")
                        sb.appendLine("    Firsts: ${tl.firsts.size}")
                    }
                    MnxSectionType.EMBEDDING_INDEX -> {
                        val idx = extractEmbeddingIndex(file)!!
                        sb.appendLine("    Vectors: ${idx.entries.size}")
                        sb.appendLine("    Dimensionality: ${idx.dimensionality}")
                    }
                    MnxSectionType.ATTACHMENT_MANIFEST -> {
                        val manifest = extractAttachmentManifest(file)!!
                        sb.appendLine("    Attachments: ${manifest.attachments.size}")
                        for (a in manifest.attachments.take(5)) {
                            sb.appendLine("      ${a.attachmentId}: ${a.mimeType} (${a.sizeBytes} bytes)")
                        }
                        if (manifest.attachments.size > 5) {
                            sb.appendLine("      ... and ${manifest.attachments.size - 5} more")
                        }
                    }
                    MnxSectionType.ATTACHMENT_DATA -> {
                        val ad = extractAttachmentData(file)!!
                        sb.appendLine("    Blobs: ${ad.blobs.size}")
                        sb.appendLine("    Total data: ${ad.blobs.sumOf { it.data.size.toLong() }} bytes")
                    }
                    MnxSectionType.SENSORY_ASSOCIATIONS -> {
                        val sa = extractSensoryAssociations(file)!!
                        sb.appendLine("    Sensory links: ${sa.links.size}")
                        val modalities = sa.links.map { it.modality }.distinct()
                        sb.appendLine("    Modalities: ${modalities.joinToString(", ")}")
                    }
                    MnxSectionType.DIMENSIONAL_REFS -> {
                        val dr = extractDimensionalRefs(file)!!
                        sb.appendLine("    References: ${dr.refs.size}")
                        val dimensions = dr.refs.map { it.dimension }.distinct()
                        sb.appendLine("    Dimensions: ${dimensions.joinToString(", ")}")
                    }
                    MnxSectionType.META -> {
                        val meta = extractMeta(file)!!
                        for ((k, v) in meta.entries) {
                            sb.appendLine("    $k: $v")
                        }
                    }
                    else -> {} // Other sections summarized by size only
                }
            } catch (e: Exception) {
                sb.appendLine("    (failed to parse: ${e.message})")
            }
        }

        // Report any user-defined / unknown raw sections
        if (file.rawSections.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("  User-defined sections:")
            for ((typeId, data) in file.rawSections) {
                sb.appendLine("    [0x${typeId.toUInt().toString(16).padStart(4, '0')}] ${data.size} bytes")
            }
        }

        return sb.toString()
    }

    // =========================================================================
    //  RESTORE — Apply MnxFile sections back to live subsystems
    // =========================================================================

    /**
     * Restore memories from an .mnx file into a live [RAGStore].
     *
     * @param file      The loaded .mnx file.
     * @param ragStore  The target RAG store.
     * @param merge     If true, adds to existing memories. If false, clears first.
     * @return          Number of chunks restored.
     */
    fun restoreMemories(file: MnxFile, ragStore: RAGStore, merge: Boolean = true): Int {
        val store = extractMemoryStore(file) ?: return 0

        if (!merge) {
            ragStore.clear()
        }

        var restored = 0
        for (chunk in store.chunks) {
            try {
                val chunkId = ragStore.addChunk(
                    content = chunk.content,
                    source = chunk.source,
                    sourceType = chunk.sourceType,
                    metadata = chunk.metadata.mapValues { (_, v) -> v }
                )

                // Restore MemRL state
                val liveChunks = ragStore.getChunks()
                val liveChunk = liveChunks.find { it.chunkId == chunkId }
                liveChunk?.restoreMemRLState(
                    savedQValue = chunk.qValue,
                    savedRetrievalCount = chunk.retrievalCount,
                    savedSuccessCount = chunk.successCount
                )

                restored++
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore chunk ${chunk.chunkId}", e)
            }
        }

        Log.d(TAG, "Restored $restored/${store.chunks.size} memory chunks")
        return restored
    }

    /**
     * Restore the affect state from an .mnx file into a live [AffectOrchestrator].
     */
    fun restoreAffectState(file: MnxFile, orchestrator: AffectOrchestrator): Boolean {
        val mnxState = extractAffectState(file) ?: return false

        val currentState = orchestrator.getCurrentState()
        for ((key, value) in mnxState.dimensions) {
            val dim = AffectDimension.fromKey(key) ?: continue
            currentState[dim] = value
        }

        Log.d(TAG, "Restored affect state (${mnxState.dimensions.size} dimensions)")
        return true
    }

    /**
     * Restore beliefs from an .mnx file into a live [BeliefVersioningManager].
     */
    fun restoreBeliefs(file: MnxFile, beliefManager: BeliefVersioningManager): Int {
        val store = extractBeliefStore(file) ?: return 0

        var restored = 0
        for (topic in store.beliefs) {
            // Apply the latest version of each belief
            val latest = topic.versions.maxByOrNull { it.version } ?: continue
            try {
                beliefManager.setBelief(
                    topic = topic.topic,
                    belief = latest.belief,
                    confidence = latest.confidence,
                    reason = latest.reason
                )
                restored++
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore belief '${topic.topic}'", e)
            }
        }

        Log.d(TAG, "Restored $restored/${store.beliefs.size} beliefs")
        return restored
    }

    // =========================================================================
    //  PRIVATE — Snapshot helpers (live system → Mnx models)
    // =========================================================================

    private fun chunkToMnx(chunk: TextChunk): MnxMemoryChunk {
        // Convert metadata values to serializable types
        val safeMetadata = mutableMapOf<String, Any>()
        for ((k, v) in chunk.metadata) {
            when (v) {
                is String, is Int, is Long, is Float, is Double, is Boolean -> safeMetadata[k] = v
                else -> safeMetadata[k] = v.toString()
            }
        }

        return MnxMemoryChunk(
            chunkId = chunk.chunkId,
            content = chunk.content,
            source = chunk.source,
            sourceType = chunk.sourceType,
            timestamp = chunk.timestamp,
            tokenCount = chunk.tokenCount,
            qValue = chunk.qValue,
            retrievalCount = chunk.retrievalCount,
            successCount = chunk.successCount,
            memoryStage = safeMetadata["memory_stage"]?.toString() ?: "WORKING",
            embedding = chunk.embedding,
            metadata = safeMetadata
        )
    }

    private fun snapshotKnowledgeGraph(graph: KnowledgeGraph): MnxKnowledgeGraph {
        val entities = graph.getEntities().map { e ->
            MnxEntityNode(
                entityId = e.entityId,
                name = e.name,
                entityType = e.entityType,
                description = e.description,
                mentionCount = e.mentionCount,
                lastSeen = e.lastSeen
            )
        }

        // The KnowledgeGraph doesn't expose chunk nodes or edges directly
        // in a list form, so we provide what we can from getEntities().
        // The full graph is captured at the binary level via the RAGStore's
        // KnowledgeGraph persistence. Here we capture the entity-level view.
        return MnxKnowledgeGraph(
            entities = entities,
            chunkNodes = emptyList(), // Populated from RAGStore's chunks
            edges = emptyList()       // Relationship edges require graph traversal
        )
    }

    private fun snapshotAffectState(orchestrator: AffectOrchestrator): MnxAffectState {
        val state = orchestrator.getCurrentState()
        return MnxAffectState(
            dimensions = state.toMap(),
            timestamp = System.currentTimeMillis()
        )
    }

    private fun snapshotAffectLog(orchestrator: AffectOrchestrator): MnxAffectLog {
        val snapshot = orchestrator.getAffectSnapshot()
        val recentEntries = snapshot["recent_log_entries"]

        // The log entries come through the snapshot as a list
        // Convert from AffectLogEntry to MnxAffectLogEntry
        val entries = mutableListOf<MnxAffectLogEntry>()

        // Fallback: create an entry from current state if we can't get log entries
        val currentState = orchestrator.getCurrentState()
        entries.add(
            MnxAffectLogEntry(
                timestamp = java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                    java.util.Locale.US
                ).format(java.util.Date()),
                affectVector = currentState.toMap(),
                inputSources = emptyList(),
                expressionCommands = emptyMap(),
                motorNoiseLevel = 0.0f,
                noiseDistribution = emptyMap(),
                hash = ""
            )
        )

        return MnxAffectLog(
            entries = entries,
            lastHash = "",
            entryCount = entries.size.toLong()
        )
    }

    private fun snapshotExpressionMap(orchestrator: AffectOrchestrator): MnxExpressionMap {
        val snapshot = orchestrator.getAffectSnapshot()

        // Extract expression commands from the snapshot
        @Suppress("UNCHECKED_CAST")
        val expressionMap = (snapshot["expression"] as? Map<String, String>) ?: emptyMap()

        // Extract noise info
        @Suppress("UNCHECKED_CAST")
        val noiseMap = (snapshot["motor_noise"] as? Map<String, Any>) ?: emptyMap()

        val noiseChannels = mutableMapOf<String, Float>()
        val noiseSensitivities = mutableMapOf<String, Float>()

        // Map motor channel sensitivities
        for (channel in MotorNoise.MotorChannel.entries) {
            noiseSensitivities[channel.key] = channel.sensitivity
        }

        return MnxExpressionMap(
            channels = expressionMap,
            noiseChannels = noiseChannels,
            noiseSensitivities = noiseSensitivities
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun snapshotPersonality(manager: EmotionalStateManager): MnxPersonality {
        val emotionalState = manager.getEmotionalState()

        val traits = (emotionalState["personality_traits"] as? Map<String, Float>) ?: emptyMap()
        val currentEmotion = emotionalState["current_emotion"]?.toString() ?: "NEUTRAL"
        val intensity = (emotionalState["intensity"] as? Float) ?: 0.0f
        val bias = emotionalState["emotional_bias"]
        val emotionalBias = if (bias is Float) mapOf("overall" to bias) else emptyMap()
        val curiosityStreak = (emotionalState["curiosity_streak"] as? Int) ?: 0
        val embarrassmentCount = (emotionalState["embarrassment_count"] as? Int) ?: 0

        return MnxPersonality(
            traits = traits,
            currentEmotion = currentEmotion,
            emotionalIntensity = intensity,
            emotionalBias = emotionalBias,
            curiosityStreak = curiosityStreak,
            embarrassmentCount = embarrassmentCount
        )
    }

    private fun snapshotBeliefStore(manager: BeliefVersioningManager): MnxBeliefStore {
        val topics = manager.getAllTopics()
        val beliefs = topics.mapNotNull { topic ->
            val history = manager.getBeliefHistory(topic)
            if (history.isEmpty()) return@mapNotNull null

            MnxBeliefTopic(
                topic = topic,
                versions = history.map { v ->
                    MnxBeliefVersion(
                        version = v.version,
                        belief = v.belief,
                        confidence = v.confidence,
                        reason = v.reason,
                        timestamp = v.timestamp
                    )
                }
            )
        }
        return MnxBeliefStore(beliefs)
    }

    private fun snapshotTimeline(timeline: AutobiographicalTimeline): MnxTimeline {
        val milestones = timeline.getTimeline(limit = Int.MAX_VALUE).map { m ->
            MnxMilestone(
                id = m.id,
                timestamp = m.timestamp,
                category = m.category,
                description = m.description,
                significance = m.significance,
                isFirst = m.isFirst,
                metadata = m.metadata.mapValues { (_, v) -> v.toString() }
            )
        }

        return MnxTimeline(
            milestones = milestones,
            firsts = timeline.getFirsts()
        )
    }

    private fun snapshotEmbeddings(ragStore: RAGStore): MnxEmbeddingIndex {
        val chunks = ragStore.getChunks()
        val entries = chunks.filter { it.embedding != null }.map { chunk ->
            MnxEmbeddingEntry(
                chunkId = chunk.chunkId,
                vector = chunk.embedding!!
            )
        }

        // Determine dimensionality from the first embedding
        val dim = entries.firstOrNull()?.vector?.size ?: 0

        return MnxEmbeddingIndex(
            dimensionality = dim,
            entries = entries
        )
    }

    companion object {
        private const val TAG = "MindNexusManager"
    }
}
