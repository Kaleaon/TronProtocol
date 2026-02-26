package com.tronprotocol.app.mindnexus

import org.junit.Assert.*
import org.junit.Test

class MnxFormatTest {

    // ---- MnxHeader creation with correct fields ----

    @Test
    fun headerCreation_hasCorrectDefaults() {
        val header = MnxHeader()
        assertEquals(MnxFormat.VERSION_MAJOR, header.versionMajor)
        assertEquals(MnxFormat.VERSION_MINOR, header.versionMinor)
        assertEquals(MnxFormat.VERSION_PATCH, header.versionPatch)
        assertEquals(0.toByte(), header.flags)
        assertEquals(0.toShort(), header.sectionCount)
        assertEquals(MnxFormat.HEADER_SIZE, header.sectionTableOffset)
        assertEquals(0L, header.totalUncompressedSize)
    }

    @Test
    fun headerCreation_withCustomFields() {
        val header = MnxHeader(
            versionMajor = 2,
            versionMinor = 3,
            versionPatch = 4,
            flags = MnxFormat.FLAG_COMPRESSED,
            sectionCount = 5,
            totalUncompressedSize = 1024L
        )
        assertEquals(2.toByte(), header.versionMajor)
        assertEquals(3.toByte(), header.versionMinor)
        assertEquals(4.toByte(), header.versionPatch)
        assertEquals(MnxFormat.FLAG_COMPRESSED, header.flags)
        assertEquals(5.toShort(), header.sectionCount)
        assertEquals(1024L, header.totalUncompressedSize)
    }

    @Test
    fun headerVersion_returnsFormattedString() {
        val header = MnxHeader()
        assertEquals("1.0.0", header.version)
    }

    @Test
    fun headerFlags_compressedFlagDetected() {
        val header = MnxHeader(flags = MnxFormat.FLAG_COMPRESSED)
        assertTrue(header.isCompressed)
        assertFalse(header.isEncrypted)
        assertFalse(header.isSigned)
    }

    @Test
    fun headerFlags_encryptedFlagDetected() {
        val header = MnxHeader(flags = MnxFormat.FLAG_ENCRYPTED)
        assertFalse(header.isCompressed)
        assertTrue(header.isEncrypted)
        assertFalse(header.isSigned)
    }

    @Test
    fun headerFlags_signedFlagDetected() {
        val header = MnxHeader(flags = MnxFormat.FLAG_SIGNED)
        assertFalse(header.isCompressed)
        assertFalse(header.isEncrypted)
        assertTrue(header.isSigned)
    }

    // ---- MnxHeader magic constant is "MNX!" (0x4D4E5821) ----

    @Test
    fun magic_isMNXExclamation() {
        // "MNX!" in ASCII: M=0x4D, N=0x4E, X=0x58, !=0x21
        assertEquals(0x4D4E5821, MnxFormat.MAGIC)
    }

    @Test
    fun magic_decodesAsExpectedString() {
        val bytes = byteArrayOf(
            ((MnxFormat.MAGIC shr 24) and 0xFF).toByte(),
            ((MnxFormat.MAGIC shr 16) and 0xFF).toByte(),
            ((MnxFormat.MAGIC shr 8) and 0xFF).toByte(),
            (MnxFormat.MAGIC and 0xFF).toByte()
        )
        val magicString = String(bytes, Charsets.US_ASCII)
        assertEquals("MNX!", magicString)
    }

    @Test
    fun footerMagic_isReversedMagic() {
        // "!XNM" = 0x21584E4D
        assertEquals(0x21584E4D, MnxFormat.FOOTER_MAGIC)
    }

    // ---- MnxFile creation with header, sections, footer ----

    @Test
    fun mnxFileCreation_withHeaderSectionsFooter() {
        val header = MnxHeader()
        val sections = mapOf(MnxSectionType.IDENTITY to byteArrayOf(1, 2, 3))
        val footer = MnxFooter(sha256 = ByteArray(32))

        val file = MnxFile(header = header, sections = sections, footer = footer)

        assertEquals(header, file.header)
        assertEquals(1, file.sections.size)
        assertTrue(file.hasSection(MnxSectionType.IDENTITY))
        assertFalse(file.hasSection(MnxSectionType.MEMORY_STORE))
        assertEquals(footer, file.footer)
    }

    @Test
    fun mnxFile_sectionCount_includesKnownAndRaw() {
        val header = MnxHeader()
        val sections = mapOf(
            MnxSectionType.IDENTITY to byteArrayOf(1),
            MnxSectionType.META to byteArrayOf(2)
        )
        val rawSections = mapOf(0x7FFF.toShort() to byteArrayOf(3))

        val file = MnxFile(header = header, sections = sections, rawSections = rawSections)

        assertEquals(3, file.sectionCount)
    }

    @Test
    fun mnxFile_totalPayloadSize() {
        val header = MnxHeader()
        val sections = mapOf(
            MnxSectionType.IDENTITY to byteArrayOf(1, 2, 3),
            MnxSectionType.META to byteArrayOf(4, 5)
        )
        val file = MnxFile(header = header, sections = sections)

        assertEquals(5L, file.totalPayloadSize)
    }

    @Test
    fun mnxFile_hasRawSection() {
        val rawId: Short = 0x7FFF
        val file = MnxFile(
            header = MnxHeader(),
            sections = emptyMap(),
            rawSections = mapOf(rawId to byteArrayOf(1, 2))
        )
        assertTrue(file.hasRawSection(rawId))
        assertFalse(file.hasRawSection(0x0001))
    }

    // ---- MnxSectionType enum has all expected section types ----

    @Test
    fun sectionType_hasAllExpectedTypes() {
        val expectedNames = listOf(
            "IDENTITY", "MEMORY_STORE", "KNOWLEDGE_GRAPH",
            "AFFECT_STATE", "AFFECT_LOG", "EXPRESSION_MAP",
            "PERSONALITY", "BELIEF_STORE", "VALUE_ALIGNMENT",
            "RELATIONSHIP_WEB", "PREFERENCE_STORE", "TIMELINE",
            "EMBEDDING_INDEX", "OPINION_MAP",
            "ATTACHMENT_MANIFEST", "ATTACHMENT_DATA",
            "SENSORY_ASSOCIATIONS", "DIMENSIONAL_REFS",
            "META"
        )

        for (name in expectedNames) {
            assertNotNull(
                "MnxSectionType should contain $name",
                MnxSectionType.valueOf(name)
            )
        }
    }

    @Test
    fun sectionType_totalCount() {
        // 19 section types defined
        assertEquals(19, MnxSectionType.entries.size)
    }

    // ---- MnxSectionType values are unique ----

    @Test
    fun sectionType_typeIdsAreUnique() {
        val typeIds = MnxSectionType.entries.map { it.typeId }
        assertEquals(typeIds.size, typeIds.toSet().size)
    }

    @Test
    fun sectionType_fromTypeIdRoundTrip() {
        for (type in MnxSectionType.entries) {
            assertEquals(type, MnxSectionType.fromTypeId(type.typeId))
        }
    }

    @Test
    fun sectionType_fromTypeId_returnsNullForUnknown() {
        assertNull(MnxSectionType.fromTypeId(0x7FFE.toShort()))
    }

    // ---- MnxFooter stores checksum and hash ----

    @Test
    fun footer_storesChecksum() {
        val sha = ByteArray(32) { it.toByte() }
        val footer = MnxFooter(sha256 = sha)

        assertArrayEquals(sha, footer.sha256)
        assertEquals(MnxFormat.FOOTER_MAGIC, footer.footerMagic)
    }

    @Test
    fun footer_equalityChecksByContent() {
        val sha1 = ByteArray(32) { it.toByte() }
        val sha2 = ByteArray(32) { it.toByte() }
        val sha3 = ByteArray(32) { (it + 1).toByte() }

        val footer1 = MnxFooter(sha256 = sha1)
        val footer2 = MnxFooter(sha256 = sha2)
        val footer3 = MnxFooter(sha256 = sha3)

        assertEquals(footer1, footer2)
        assertNotEquals(footer1, footer3)
    }

    @Test
    fun footer_hashCodeConsistentWithEquals() {
        val sha1 = ByteArray(32) { it.toByte() }
        val sha2 = ByteArray(32) { it.toByte() }

        val footer1 = MnxFooter(sha256 = sha1)
        val footer2 = MnxFooter(sha256 = sha2)

        assertEquals(footer1.hashCode(), footer2.hashCode())
    }

    // ---- Format constants ----

    @Test
    fun format_headerSize() {
        assertEquals(64, MnxFormat.HEADER_SIZE)
    }

    @Test
    fun format_sectionEntrySize() {
        assertEquals(20, MnxFormat.SECTION_ENTRY_SIZE)
    }

    @Test
    fun format_footerSize() {
        assertEquals(36, MnxFormat.FOOTER_SIZE)
    }

    @Test
    fun format_fileExtension() {
        assertEquals(".mnx", MnxFormat.FILE_EXTENSION)
    }

    @Test
    fun format_mimeType() {
        assertEquals("application/x-mind-nexus", MnxFormat.MIME_TYPE)
    }
}
