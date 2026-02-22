package com.tronprotocol.app.mindnexus

import java.util.UUID

/**
 * Mind Nexus File Format (.mnx) — Specification
 *
 * A dense binary file format designed to capture the complete psyche of an
 * emergent AI personality. Encodes memories, emotions, opinions, beliefs,
 * relationships, personality traits, and knowledge graphs into a single,
 * portable, section-based binary container.
 *
 * ## File Layout
 * ```
 * ┌────────────────────────────────────┐
 * │  HEADER  (64 bytes, fixed)         │  Magic, version, flags, timestamps
 * ├────────────────────────────────────┤
 * │  SECTION TABLE  (20 bytes/entry)   │  Type, offset, size, CRC32 per section
 * ├────────────────────────────────────┤
 * │  SECTION DATA  (variable)          │  Binary-encoded section payloads
 * │    ├─ IDENTITY                     │  Core identity & name
 * │    ├─ MEMORY_STORE                 │  TextChunks with MemRL Q-values
 * │    ├─ KNOWLEDGE_GRAPH              │  Entities, relationships, edges
 * │    ├─ AFFECT_STATE                 │  12-dim emotional vector
 * │    ├─ AFFECT_LOG                   │  Tamper-proof emotional history
 * │    ├─ EXPRESSION_MAP               │  Motor output configuration
 * │    ├─ PERSONALITY                  │  Traits, biases, curiosity profile
 * │    ├─ BELIEF_STORE                 │  Versioned beliefs with confidence
 * │    ├─ VALUE_ALIGNMENT              │  Weighted core values
 * │    ├─ RELATIONSHIP_WEB             │  Social bonds & interaction history
 * │    ├─ PREFERENCE_STORE             │  Observations + crystallized prefs
 * │    ├─ TIMELINE                     │  Autobiographical milestones
 * │    ├─ EMBEDDING_INDEX              │  Raw vector embeddings
 * │    ├─ OPINION_MAP                  │  Entity opinions with valence
 * │    ├─ ATTACHMENT_MANIFEST          │  Manifest of embedded binary objects
 * │    ├─ ATTACHMENT_DATA              │  Raw binary blobs (images, audio, 3D, etc.)
 * │    ├─ SENSORY_ASSOCIATIONS         │  Sensory links: entities ↔ sights/sounds/etc.
 * │    ├─ DIMENSIONAL_REFS             │  Abstract concept ↔ data type associations
 * │    ├─ META                         │  Extensible key-value metadata
 * │    └─ (0x8000+)                    │  User-defined / future section types
 * ├────────────────────────────────────┤
 * │  FOOTER  (36 bytes, fixed)         │  SHA-256 checksum + end marker
 * └────────────────────────────────────┘
 * ```
 *
 * ## Binary Encoding Conventions
 * - Strings:     4-byte length (big-endian) + UTF-8 bytes
 * - Ints:        4 bytes, big-endian
 * - Longs:       8 bytes, big-endian
 * - Floats:      4 bytes, IEEE 754
 * - Booleans:    1 byte (0x00 = false, 0x01 = true)
 * - FloatArrays: 4-byte count + N × 4-byte floats
 * - Lists:       4-byte count + serialized elements
 * - Maps:        4-byte count + serialized key-value pairs
 *
 * ## Extensibility — Dimensional Container
 * The .mnx format is designed as an open-ended **dimensional container**:
 * - **Attachments**: Binary blobs (images, audio, video, 3D models, or any file)
 *   are stored in ATTACHMENT_DATA sections and catalogued in the ATTACHMENT_MANIFEST.
 *   Each attachment has a MIME type, UUID, and optional associations to entities,
 *   memories, or opinions.
 * - **Sensory associations**: The SENSORY_ASSOCIATIONS section links entities to
 *   multi-modal data — what something looks like, sounds like, feels like.
 * - **Dimensional references**: The DIMENSIONAL_REFS section provides an abstract
 *   association layer that can link any concept to any data type, even types
 *   that don't exist yet. Each reference is a triple: (subject, dimension, target)
 *   where dimension is an open string ("visual", "auditory", "spatial", "temporal",
 *   "conceptual", or any future value).
 * - **User-defined sections**: Type IDs 0x8000–0xFFFE are reserved for custom
 *   section types. Unknown sections are preserved as raw byte arrays during
 *   decode, so older readers can carry them through without data loss.
 *
 * ## Design Principles
 * - Section-based for forward compatibility (unknown sections are skipped)
 * - CRC32 per section for integrity verification
 * - SHA-256 file checksum in footer
 * - Encryption-ready: flag bit reserved, sections can be individually encrypted
 * - Streaming-friendly: section table at front enables random access
 * - Open-ended: arbitrary binary attachments and user-defined section types
 */
object MnxFormat {

    // ---- Magic & Markers ----

    /** File magic bytes: "MNX!" */
    const val MAGIC: Int = 0x4D4E5821

    /** Footer end marker: "!XNM" (magic reversed) */
    const val FOOTER_MAGIC: Int = 0x21584E4D

    /** File extension */
    const val FILE_EXTENSION = ".mnx"

    /** MIME type */
    const val MIME_TYPE = "application/x-mind-nexus"

    // ---- Version ----

    const val VERSION_MAJOR: Byte = 1
    const val VERSION_MINOR: Byte = 0
    const val VERSION_PATCH: Byte = 0

    // ---- Sizes ----

    /** Fixed header size in bytes */
    const val HEADER_SIZE = 64

    /** Bytes per section table entry */
    const val SECTION_ENTRY_SIZE = 20

    /** Fixed footer size in bytes */
    const val FOOTER_SIZE = 36

    // ---- Header Flags (bitmask) ----

    /** Section data is DEFLATE-compressed */
    const val FLAG_COMPRESSED: Byte = 0x01

    /** Section data is encrypted (reserved for future use) */
    const val FLAG_ENCRYPTED: Byte = 0x02

    /** File includes a digital signature (reserved for future use) */
    const val FLAG_SIGNED: Byte = 0x04
}

/**
 * Identifies the type and purpose of each binary section within an .mnx file.
 *
 * Each section type maps to a specific domain of AI personality data.
 * Unknown section types are skipped during deserialization, enabling
 * forward compatibility as the format evolves.
 */
enum class MnxSectionType(val typeId: Short) {

    /** Core identity: name, creation date, base personality parameters */
    IDENTITY(0x0001),

    /** Memory store: TextChunks with content, embeddings, MemRL Q-values, stages */
    MEMORY_STORE(0x0002),

    /** Knowledge graph: entity nodes, chunk nodes, relationship edges */
    KNOWLEDGE_GRAPH(0x0003),

    /** Current affect state: 12-dimensional emotional vector */
    AFFECT_STATE(0x0004),

    /** Affect history log: chain-linked emotional snapshots */
    AFFECT_LOG(0x0005),

    /** Expression map: motor output channel configurations */
    EXPRESSION_MAP(0x0006),

    /** Personality profile: traits, emotional biases, curiosity/caution scores */
    PERSONALITY(0x0007),

    /** Belief store: versioned beliefs with confidence and rationale */
    BELIEF_STORE(0x0008),

    /** Core values: weighted ethical/personal values */
    VALUE_ALIGNMENT(0x0009),

    /** Relationship web: social bonds, interaction quality histories */
    RELATIONSHIP_WEB(0x000A),

    /** Preferences: raw observations + crystallized summaries */
    PREFERENCE_STORE(0x000B),

    /** Autobiographical timeline: milestones, firsts, significance scores */
    TIMELINE(0x000C),

    /** Embedding index: raw float vectors for semantic search */
    EMBEDDING_INDEX(0x000D),

    /** Opinion map: entity opinions with valence, confidence, reasoning */
    OPINION_MAP(0x000E),

    /**
     * Attachment manifest: catalogue of embedded binary objects.
     * Each entry describes a blob stored in ATTACHMENT_DATA with its MIME type,
     * UUID, size, and entity associations.
     */
    ATTACHMENT_MANIFEST(0x0010),

    /**
     * Raw attachment data: binary blobs for images, audio, video, 3D models,
     * or any file type — including types that don't exist yet.
     * Blobs are identified by UUID and described in the ATTACHMENT_MANIFEST.
     */
    ATTACHMENT_DATA(0x0011),

    /**
     * Sensory associations: links entities to multi-modal sensory data.
     * Maps (entity, modality) → attachment UUIDs. Modalities include:
     * visual, auditory, tactile, olfactory, spatial, temporal, and any
     * future sensory dimension.
     */
    SENSORY_ASSOCIATIONS(0x0012),

    /**
     * Dimensional references: abstract concept-to-data associations.
     * Each reference is a triple (subject, dimension, target) where
     * dimension is an open-ended string — "visual", "auditory", "spatial",
     * "conceptual", "3d_model", or any name. This allows the format to
     * associate any concept with any kind of data, even data types that
     * don't have names yet.
     */
    DIMENSIONAL_REFS(0x0013),

    /** Extensible key-value metadata */
    META(0x00FF);

    companion object {
        private val byId = entries.associateBy { it.typeId }

        /**
         * The start of the user-defined section type range.
         * IDs 0x8000–0xFFFE are available for custom section types
         * that the core format does not define.
         */
        const val USER_DEFINED_RANGE_START: Short = 0x7FFF // 0x8000 as signed short

        /**
         * Look up section type by its 2-byte ID. Returns null for unknown types.
         * Unknown types (including user-defined 0x8000+) are preserved as raw
         * byte arrays during decode — they won't be lost.
         */
        fun fromTypeId(id: Short): MnxSectionType? = byId[id]

        /** Check if a type ID falls in the user-defined range. */
        fun isUserDefined(id: Short): Boolean = id < 0 // Signed short: 0x8000+ wraps negative
    }
}

/**
 * File header (64 bytes, fixed layout).
 */
data class MnxHeader(
    val versionMajor: Byte = MnxFormat.VERSION_MAJOR,
    val versionMinor: Byte = MnxFormat.VERSION_MINOR,
    val versionPatch: Byte = MnxFormat.VERSION_PATCH,
    val flags: Byte = 0,
    val createdTimestamp: Long = System.currentTimeMillis(),
    val modifiedTimestamp: Long = System.currentTimeMillis(),
    val sectionCount: Short = 0,
    val sectionTableOffset: Int = MnxFormat.HEADER_SIZE,
    val fileUuid: UUID = UUID.randomUUID(),
    val totalUncompressedSize: Long = 0L,
    val headerCrc32: Int = 0
) {
    /** Human-readable version string */
    val version: String
        get() = "$versionMajor.$versionMinor.$versionPatch"

    val isCompressed: Boolean get() = (flags.toInt() and MnxFormat.FLAG_COMPRESSED.toInt()) != 0
    val isEncrypted: Boolean get() = (flags.toInt() and MnxFormat.FLAG_ENCRYPTED.toInt()) != 0
    val isSigned: Boolean get() = (flags.toInt() and MnxFormat.FLAG_SIGNED.toInt()) != 0
}

/**
 * Section table entry (20 bytes per entry).
 */
data class MnxSectionEntry(
    val sectionType: MnxSectionType,
    val offset: Long,
    val size: Int,
    val crc32: Int
)

/**
 * File footer (36 bytes, fixed layout).
 */
data class MnxFooter(
    val sha256: ByteArray,  // 32 bytes
    val footerMagic: Int = MnxFormat.FOOTER_MAGIC
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MnxFooter) return false
        return sha256.contentEquals(other.sha256) && footerMagic == other.footerMagic
    }

    override fun hashCode(): Int = sha256.contentHashCode() * 31 + footerMagic
}

/**
 * Complete in-memory representation of a .mnx file.
 *
 * Holds the header, section table, raw section payloads, and footer.
 * Use [MnxCodec] to serialize/deserialize, and [MindNexusManager] to
 * snapshot/restore from live AI subsystems.
 *
 * ## Extensibility
 * Known section types are stored in [sections]. User-defined or unknown
 * section types (0x8000+) are preserved in [rawSections] as (typeId → bytes)
 * pairs. This allows older readers to carry forward data produced by newer
 * writers without losing it.
 */
data class MnxFile(
    val header: MnxHeader,
    val sections: Map<MnxSectionType, ByteArray>,
    val rawSections: Map<Short, ByteArray> = emptyMap(),
    val footer: MnxFooter? = null
) {
    /** Quick check: does this file contain a given section? */
    fun hasSection(type: MnxSectionType): Boolean = sections.containsKey(type)

    /** Check for a raw/user-defined section by type ID. */
    fun hasRawSection(typeId: Short): Boolean = rawSections.containsKey(typeId)

    /** Number of sections in this file (known + raw). */
    val sectionCount: Int get() = sections.size + rawSections.size

    /** Total payload size across all sections (uncompressed). */
    val totalPayloadSize: Long
        get() = sections.values.sumOf { it.size.toLong() } +
                rawSections.values.sumOf { it.size.toLong() }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MnxFile) return false
        if (header != other.header) return false
        if (sections.size != other.sections.size) return false
        for ((type, data) in sections) {
            if (!data.contentEquals(other.sections[type] ?: return false)) return false
        }
        if (rawSections.size != other.rawSections.size) return false
        for ((typeId, data) in rawSections) {
            if (!data.contentEquals(other.rawSections[typeId] ?: return false)) return false
        }
        return footer == other.footer
    }

    override fun hashCode(): Int {
        var result = header.hashCode()
        for ((type, data) in sections) {
            result = result * 31 + type.hashCode()
            result = result * 31 + data.contentHashCode()
        }
        for ((typeId, data) in rawSections) {
            result = result * 31 + typeId.hashCode()
            result = result * 31 + data.contentHashCode()
        }
        result = result * 31 + (footer?.hashCode() ?: 0)
        return result
    }
}
