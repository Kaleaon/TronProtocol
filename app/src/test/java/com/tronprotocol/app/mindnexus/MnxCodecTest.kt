package com.tronprotocol.app.mindnexus

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class MnxCodecTest {

    /**
     * Build a minimal but valid MnxFile for testing.
     */
    private fun buildMinimalMnxFile(): MnxFile {
        val identity = MnxIdentity(name = "TestAI", createdAt = System.currentTimeMillis())
        val identityBytes = MnxCodec.serializeIdentity(identity)

        val meta = MnxMeta(entries = mapOf("format" to "test"))
        val metaBytes = MnxCodec.serializeMeta(meta)

        val header = MnxHeader(
            fileUuid = UUID.randomUUID()
        )

        return MnxFile(
            header = header,
            sections = mapOf(
                MnxSectionType.IDENTITY to identityBytes,
                MnxSectionType.META to metaBytes
            )
        )
    }

    // ---- encode produces non-empty byte array ----

    @Test
    fun encode_producesNonEmptyByteArray() {
        val file = buildMinimalMnxFile()
        val bytes = MnxCodec.encodeToBytes(file)
        assertTrue("Encoded bytes should not be empty", bytes.isNotEmpty())
    }

    @Test
    fun encode_sizeAtLeastHeaderPlusFooter() {
        val file = buildMinimalMnxFile()
        val bytes = MnxCodec.encodeToBytes(file)
        val minSize = MnxFormat.HEADER_SIZE + MnxFormat.FOOTER_SIZE
        assertTrue(
            "Encoded size (${bytes.size}) should be at least header + footer ($minSize)",
            bytes.size >= minSize
        )
    }

    // ---- encode/decode round-trip preserves structure ----

    @Test
    fun encodeDecode_roundTrip_preservesSections() {
        val original = buildMinimalMnxFile()
        val bytes = MnxCodec.encodeToBytes(original)
        val decoded = MnxCodec.decodeFromBytes(bytes)

        assertEquals(original.sections.size, decoded.sections.size)
        assertTrue(decoded.hasSection(MnxSectionType.IDENTITY))
        assertTrue(decoded.hasSection(MnxSectionType.META))
    }

    @Test
    fun encodeDecode_roundTrip_preservesIdentityPayload() {
        val identity = MnxIdentity(
            name = "RoundTripAI",
            createdAt = 1000L,
            species = "digital",
            pronouns = "they/them",
            coreTraits = listOf("curious"),
            biography = "A test AI",
            attributes = mapOf("v" to "1")
        )
        val identityBytes = MnxCodec.serializeIdentity(identity)

        val file = MnxFile(
            header = MnxHeader(),
            sections = mapOf(MnxSectionType.IDENTITY to identityBytes)
        )

        val encoded = MnxCodec.encodeToBytes(file)
        val decoded = MnxCodec.decodeFromBytes(encoded)

        val decodedIdentity = MnxCodec.deserializeIdentity(
            decoded.sections[MnxSectionType.IDENTITY]!!
        )
        assertEquals("RoundTripAI", decodedIdentity.name)
        assertEquals(1000L, decodedIdentity.createdAt)
        assertEquals("digital", decodedIdentity.species)
        assertEquals("they/them", decodedIdentity.pronouns)
        assertEquals(listOf("curious"), decodedIdentity.coreTraits)
        assertEquals("A test AI", decodedIdentity.biography)
    }

    @Test
    fun encodeDecode_roundTrip_preservesHeaderVersion() {
        val original = buildMinimalMnxFile()
        val bytes = MnxCodec.encodeToBytes(original)
        val decoded = MnxCodec.decodeFromBytes(bytes)

        assertEquals(original.header.versionMajor, decoded.header.versionMajor)
        assertEquals(original.header.versionMinor, decoded.header.versionMinor)
        assertEquals(original.header.versionPatch, decoded.header.versionPatch)
    }

    @Test
    fun encodeDecode_roundTrip_preservesHeaderUuid() {
        val original = buildMinimalMnxFile()
        val bytes = MnxCodec.encodeToBytes(original)
        val decoded = MnxCodec.decodeFromBytes(bytes)

        assertEquals(original.header.fileUuid, decoded.header.fileUuid)
    }

    @Test
    fun encodeDecode_roundTrip_footerPresent() {
        val original = buildMinimalMnxFile()
        val bytes = MnxCodec.encodeToBytes(original)
        val decoded = MnxCodec.decodeFromBytes(bytes)

        assertNotNull(decoded.footer)
        assertEquals(MnxFormat.FOOTER_MAGIC, decoded.footer!!.footerMagic)
    }

    @Test
    fun encodeDecode_roundTrip_preservesMetaPayload() {
        val meta = MnxMeta(entries = mapOf("key1" to "val1", "key2" to "val2"))
        val metaBytes = MnxCodec.serializeMeta(meta)

        val file = MnxFile(
            header = MnxHeader(),
            sections = mapOf(MnxSectionType.META to metaBytes)
        )

        val encoded = MnxCodec.encodeToBytes(file)
        val decoded = MnxCodec.decodeFromBytes(encoded)
        val decodedMeta = MnxCodec.deserializeMeta(decoded.sections[MnxSectionType.META]!!)

        assertEquals("val1", decodedMeta.entries["key1"])
        assertEquals("val2", decodedMeta.entries["key2"])
    }

    // ---- decode rejects corrupted data ----

    @Test(expected = Exception::class)
    fun decode_rejectsCorruptedData() {
        val file = buildMinimalMnxFile()
        val bytes = MnxCodec.encodeToBytes(file)

        // Corrupt section data in the middle of the file
        val corrupted = bytes.copyOf()
        if (corrupted.size > MnxFormat.HEADER_SIZE + 50) {
            corrupted[MnxFormat.HEADER_SIZE + 50] =
                (corrupted[MnxFormat.HEADER_SIZE + 50].toInt() xor 0xFF).toByte()
        }

        MnxCodec.decodeFromBytes(corrupted)
    }

    // ---- decode rejects data with wrong magic bytes ----

    @Test(expected = MnxFormatException::class)
    fun decode_rejectsWrongMagicBytes() {
        val file = buildMinimalMnxFile()
        val bytes = MnxCodec.encodeToBytes(file)

        // Overwrite magic bytes at the beginning
        val corrupted = bytes.copyOf()
        corrupted[0] = 0x00
        corrupted[1] = 0x00
        corrupted[2] = 0x00
        corrupted[3] = 0x00

        MnxCodec.decodeFromBytes(corrupted)
    }

    @Test(expected = MnxFormatException::class)
    fun decode_rejectsPartialMagicCorruption() {
        val file = buildMinimalMnxFile()
        val bytes = MnxCodec.encodeToBytes(file)

        // Change only one byte of the magic
        val corrupted = bytes.copyOf()
        corrupted[0] = 0x00

        MnxCodec.decodeFromBytes(corrupted)
    }

    // ---- encoded data starts with magic bytes ----

    @Test
    fun encodedData_startsWithMagicBytes() {
        val file = buildMinimalMnxFile()
        val bytes = MnxCodec.encodeToBytes(file)

        // MnxFormat.MAGIC = 0x4D4E5821, big-endian
        assertEquals(0x4D.toByte(), bytes[0]) // 'M'
        assertEquals(0x4E.toByte(), bytes[1]) // 'N'
        assertEquals(0x58.toByte(), bytes[2]) // 'X'
        assertEquals(0x21.toByte(), bytes[3]) // '!'
    }

    // ---- Section serializers round-trip ----

    @Test
    fun serializeDeserialize_memoryStore() {
        val chunk = MnxMemoryChunk(
            chunkId = "c1", content = "Test memory",
            source = "test", sourceType = "conversation",
            timestamp = "2026-01-01", tokenCount = 3,
            qValue = 0.7f, retrievalCount = 5, successCount = 3,
            memoryStage = "EPISODIC"
        )
        val store = MnxMemoryStore(listOf(chunk))
        val bytes = MnxCodec.serializeMemoryStore(store)
        val result = MnxCodec.deserializeMemoryStore(bytes)

        assertEquals(1, result.chunks.size)
        assertEquals("c1", result.chunks[0].chunkId)
        assertEquals("Test memory", result.chunks[0].content)
        assertEquals(0.7f, result.chunks[0].qValue, 0.001f)
        assertEquals("EPISODIC", result.chunks[0].memoryStage)
    }

    @Test
    fun serializeDeserialize_knowledgeGraph() {
        val entities = listOf(
            MnxEntityNode("e1", "Alice", "person", "A user", 5, 1000L)
        )
        val chunkNodes = listOf(
            MnxChunkNode("c1", "summary", listOf("e1"))
        )
        val edges = listOf(
            MnxRelationshipEdge("e1", "e2", "knows", 0.9f, listOf("friend"))
        )
        val graph = MnxKnowledgeGraph(entities, chunkNodes, edges)
        val bytes = MnxCodec.serializeKnowledgeGraph(graph)
        val result = MnxCodec.deserializeKnowledgeGraph(bytes)

        assertEquals(1, result.entities.size)
        assertEquals("Alice", result.entities[0].name)
        assertEquals(1, result.edges.size)
        assertEquals("knows", result.edges[0].relationship)
    }

    @Test
    fun serializeDeserialize_personality() {
        val personality = MnxPersonality(
            traits = mapOf("curiosity" to 0.9f),
            currentEmotion = "HAPPY",
            emotionalIntensity = 0.6f,
            emotionalBias = mapOf("joy" to 0.1f),
            curiosityStreak = 3,
            embarrassmentCount = 0
        )
        val bytes = MnxCodec.serializePersonality(personality)
        val result = MnxCodec.deserializePersonality(bytes)

        assertEquals(0.9f, result.traits["curiosity"]!!, 0.001f)
        assertEquals("HAPPY", result.currentEmotion)
        assertEquals(0.6f, result.emotionalIntensity, 0.001f)
        assertEquals(3, result.curiosityStreak)
    }

    @Test
    fun serializeDeserialize_timeline() {
        val milestone = MnxMilestone(
            id = "m1", timestamp = 5000L, category = "first",
            description = "First test", significance = 1.0f,
            isFirst = true, metadata = mapOf("note" to "important")
        )
        val timeline = MnxTimeline(
            milestones = listOf(milestone),
            firsts = mapOf("first" to 5000L)
        )
        val bytes = MnxCodec.serializeTimeline(timeline)
        val result = MnxCodec.deserializeTimeline(bytes)

        assertEquals(1, result.milestones.size)
        assertEquals("m1", result.milestones[0].id)
        assertTrue(result.milestones[0].isFirst)
        assertEquals(5000L, result.firsts["first"])
    }

    @Test(expected = MnxFormatException::class)
    fun decode_rejectsTruncatedData() {
        // Very short data that cannot possibly be a valid mnx file
        val truncated = byteArrayOf(0x4D, 0x4E, 0x58, 0x21)
        MnxCodec.decodeFromBytes(truncated)
    }
}
