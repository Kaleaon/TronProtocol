package com.tronprotocol.app.mindnexus

/**
 * Portable data models for each section of a .mnx file.
 *
 * These classes are standalone — they do not depend on Android Context or
 * any live subsystem. They represent the complete, serializable state of
 * each personality domain.
 *
 * Naming convention: `Mnx<Domain>` for each section's data model.
 */

// =============================================================================
//  0x0001 — IDENTITY
// =============================================================================

/**
 * Core identity of the AI personality.
 *
 * @property name       The entity's chosen or assigned name.
 * @property createdAt  Timestamp (millis) when this identity was first created.
 * @property species    Self-identified species/type (e.g., "AI", "digital fox").
 * @property pronouns   Preferred pronoun set (e.g., "they/them").
 * @property coreTraits Fundamental, immutable personality descriptors.
 * @property biography  Free-text self-description / origin story.
 * @property attributes Extensible key-value identity fields.
 */
data class MnxIdentity(
    val name: String,
    val createdAt: Long,
    val species: String = "",
    val pronouns: String = "",
    val coreTraits: List<String> = emptyList(),
    val biography: String = "",
    val attributes: Map<String, String> = emptyMap()
)

// =============================================================================
//  0x0002 — MEMORY_STORE
// =============================================================================

/**
 * A single memory unit — the portable equivalent of [TextChunk].
 *
 * @property chunkId        Unique identifier.
 * @property content        The memory text.
 * @property source         Source document / conversation ID.
 * @property sourceType     Category: "document", "conversation", "memory", "knowledge".
 * @property timestamp      ISO-8601 creation timestamp.
 * @property tokenCount     Approximate token count.
 * @property qValue         MemRL utility score (0–1).
 * @property retrievalCount Times retrieved.
 * @property successCount   Times retrieval was successful.
 * @property memoryStage    Biological stage: SENSORY, WORKING, EPISODIC, SEMANTIC.
 * @property embedding      Optional semantic embedding vector.
 * @property metadata       Extensible key-value metadata.
 */
data class MnxMemoryChunk(
    val chunkId: String,
    val content: String,
    val source: String,
    val sourceType: String,
    val timestamp: String,
    val tokenCount: Int,
    val qValue: Float = 0.5f,
    val retrievalCount: Int = 0,
    val successCount: Int = 0,
    val memoryStage: String = "WORKING",
    val embedding: FloatArray? = null,
    val metadata: Map<String, Any> = emptyMap()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MnxMemoryChunk) return false
        return chunkId == other.chunkId
    }
    override fun hashCode(): Int = chunkId.hashCode()
}

/**
 * Complete memory store section.
 */
data class MnxMemoryStore(
    val chunks: List<MnxMemoryChunk>
)

// =============================================================================
//  0x0003 — KNOWLEDGE_GRAPH
// =============================================================================

/**
 * An entity node in the knowledge graph.
 */
data class MnxEntityNode(
    val entityId: String,
    val name: String,
    val entityType: String,
    val description: String,
    val mentionCount: Int = 1,
    val lastSeen: Long = 0L
)

/**
 * A chunk node linking a memory to its entities.
 */
data class MnxChunkNode(
    val chunkId: String,
    val summary: String,
    val entityIds: List<String> = emptyList()
)

/**
 * A directed relationship edge between two entities.
 */
data class MnxRelationshipEdge(
    val sourceEntityId: String,
    val targetEntityId: String,
    val relationship: String,
    val strength: Float = 1.0f,
    val keywords: List<String> = emptyList()
)

/**
 * Complete knowledge graph section.
 */
data class MnxKnowledgeGraph(
    val entities: List<MnxEntityNode>,
    val chunkNodes: List<MnxChunkNode>,
    val edges: List<MnxRelationshipEdge>
)

// =============================================================================
//  0x0004 — AFFECT_STATE
// =============================================================================

/**
 * Snapshot of the 12-dimensional affect vector.
 * Keys correspond to [AffectDimension] key strings.
 */
data class MnxAffectState(
    val dimensions: Map<String, Float>,
    val timestamp: Long = System.currentTimeMillis()
)

// =============================================================================
//  0x0005 — AFFECT_LOG
// =============================================================================

/**
 * A single entry in the tamper-proof affect log.
 */
data class MnxAffectLogEntry(
    val timestamp: String,
    val affectVector: Map<String, Float>,
    val inputSources: List<String>,
    val expressionCommands: Map<String, String>,
    val motorNoiseLevel: Float,
    val noiseDistribution: Map<String, Float>,
    val hash: String
)

/**
 * Complete affect log section.
 */
data class MnxAffectLog(
    val entries: List<MnxAffectLogEntry>,
    val lastHash: String,
    val entryCount: Long
)

// =============================================================================
//  0x0006 — EXPRESSION_MAP
// =============================================================================

/**
 * Motor output channel configuration snapshot.
 * Maps channel names to their current expression values.
 */
data class MnxExpressionMap(
    val channels: Map<String, String>,
    val noiseChannels: Map<String, Float>,
    val noiseSensitivities: Map<String, Float>
)

// =============================================================================
//  0x0007 — PERSONALITY
// =============================================================================

/**
 * Personality profile: traits, emotional biases, and behavioral tendencies.
 *
 * @property traits           Named personality traits (e.g., "curiosity" → 0.8).
 * @property currentEmotion   Active emotion label (CONFIDENT, UNCERTAIN, etc.).
 * @property emotionalIntensity Overall emotional intensity (0–1).
 * @property emotionalBias    Emotional processing biases.
 * @property curiosityStreak  Consecutive curiosity events count.
 * @property embarrassmentCount Total embarrassment events.
 */
data class MnxPersonality(
    val traits: Map<String, Float>,
    val currentEmotion: String = "NEUTRAL",
    val emotionalIntensity: Float = 0.0f,
    val emotionalBias: Map<String, Float> = emptyMap(),
    val curiosityStreak: Int = 0,
    val embarrassmentCount: Int = 0
)

// =============================================================================
//  0x0008 — BELIEF_STORE
// =============================================================================

/**
 * A single version of a belief.
 */
data class MnxBeliefVersion(
    val version: Int,
    val belief: String,
    val confidence: Float,
    val reason: String,
    val timestamp: Long
)

/**
 * A belief topic with its version history.
 */
data class MnxBeliefTopic(
    val topic: String,
    val versions: List<MnxBeliefVersion>
)

/**
 * Complete belief store section.
 */
data class MnxBeliefStore(
    val beliefs: List<MnxBeliefTopic>
)

// =============================================================================
//  0x0009 — VALUE_ALIGNMENT
// =============================================================================

/**
 * A single weighted value.
 */
data class MnxValue(
    val name: String,
    val description: String,
    val weight: Int,
    val createdAt: Long
)

/**
 * Complete value alignment section.
 */
data class MnxValueAlignment(
    val values: List<MnxValue>
)

// =============================================================================
//  0x000A — RELATIONSHIP_WEB
// =============================================================================

/**
 * A single recorded interaction with an entity.
 */
data class MnxInteraction(
    val quality: Int,       // 1–5
    val context: String,
    val timestamp: Long
)

/**
 * A relationship with an entity (person, place, or thing).
 */
data class MnxRelationship(
    val entityName: String,
    val relationshipType: String,
    val createdAt: Long,
    val interactions: List<MnxInteraction> = emptyList()
)

/**
 * Complete relationship web section.
 */
data class MnxRelationshipWeb(
    val relationships: List<MnxRelationship>
)

// =============================================================================
//  0x000B — PREFERENCE_STORE
// =============================================================================

/**
 * A raw preference observation.
 */
data class MnxObservation(
    val text: String,
    val timestamp: Long
)

/**
 * A crystallized (learned) preference derived from observations.
 */
data class MnxCrystal(
    val category: String,
    val summary: String,
    val confidence: Double,
    val observationCount: Int,
    val topKeywords: List<String>,
    val crystallizedAt: Long
)

/**
 * Complete preference store section.
 */
data class MnxPreferenceStore(
    val observations: Map<String, List<MnxObservation>>,
    val crystals: Map<String, MnxCrystal>
)

// =============================================================================
//  0x000C — TIMELINE
// =============================================================================

/**
 * An autobiographical milestone.
 */
data class MnxMilestone(
    val id: String,
    val timestamp: Long,
    val category: String,
    val description: String,
    val significance: Float,
    val isFirst: Boolean,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Complete autobiographical timeline section.
 */
data class MnxTimeline(
    val milestones: List<MnxMilestone>,
    val firsts: Map<String, Long>
)

// =============================================================================
//  0x000D — EMBEDDING_INDEX
// =============================================================================

/**
 * A named embedding vector, linking a chunk ID to its float array.
 */
data class MnxEmbeddingEntry(
    val chunkId: String,
    val vector: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MnxEmbeddingEntry) return false
        return chunkId == other.chunkId && vector.contentEquals(other.vector)
    }
    override fun hashCode(): Int = chunkId.hashCode() * 31 + vector.contentHashCode()
}

/**
 * Complete embedding index section.
 *
 * @property dimensionality The vector dimensionality (e.g., 128).
 * @property entries        Chunk ID → embedding vector pairs.
 */
data class MnxEmbeddingIndex(
    val dimensionality: Int,
    val entries: List<MnxEmbeddingEntry>
)

// =============================================================================
//  0x000E — OPINION_MAP
// =============================================================================

/**
 * An opinion about a specific entity (person, place, thing, concept).
 *
 * @property entityName  The subject of the opinion.
 * @property entityType  Category: "person", "place", "thing", "concept", "event".
 * @property valence     Overall sentiment: -1.0 (strongly negative) to +1.0 (strongly positive).
 * @property confidence  How certain this opinion is: 0.0 to 1.0.
 * @property basis       Free-text reasoning for this opinion.
 * @property formedAt    When the opinion was first formed.
 * @property updatedAt   When the opinion was last updated.
 * @property tags        Free-form tags for categorization.
 */
data class MnxOpinion(
    val entityName: String,
    val entityType: String,
    val valence: Float,
    val confidence: Float,
    val basis: String,
    val formedAt: Long,
    val updatedAt: Long,
    val tags: List<String> = emptyList()
)

/**
 * Complete opinion map section.
 */
data class MnxOpinionMap(
    val opinions: List<MnxOpinion>
)

// =============================================================================
//  0x0010 — ATTACHMENT_MANIFEST
// =============================================================================

/**
 * Describes a single binary attachment embedded in the .mnx file.
 *
 * Attachments are the core extensibility mechanism — they let the format
 * hold any kind of data: images, audio, video, 3D models, raw sensor data,
 * or file types that haven't been invented yet.
 *
 * @property attachmentId   UUID identifying this blob within the file.
 * @property mimeType       MIME type (e.g., "image/png", "audio/wav", "model/gltf+json",
 *                          "application/octet-stream" for unknown types).
 * @property filename       Optional original filename.
 * @property sizeBytes      Size of the raw blob in bytes.
 * @property description    Human-readable description of what this attachment is.
 * @property createdAt      When the attachment was added.
 * @property checksum       SHA-256 hex digest of the raw blob data.
 * @property associatedEntities  Entity IDs this attachment is linked to.
 * @property associatedChunks    Memory chunk IDs this attachment is linked to.
 * @property tags           Free-form tags for categorization.
 * @property attributes     Extensible key-value metadata about this attachment.
 */
data class MnxAttachment(
    val attachmentId: String,
    val mimeType: String,
    val filename: String = "",
    val sizeBytes: Long,
    val description: String = "",
    val createdAt: Long,
    val checksum: String = "",
    val associatedEntities: List<String> = emptyList(),
    val associatedChunks: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val attributes: Map<String, String> = emptyMap()
)

/**
 * Complete attachment manifest section.
 */
data class MnxAttachmentManifest(
    val attachments: List<MnxAttachment>
)

// =============================================================================
//  0x0011 — ATTACHMENT_DATA
// =============================================================================

/**
 * A single binary blob stored in the attachment data section.
 */
data class MnxAttachmentBlob(
    val attachmentId: String,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MnxAttachmentBlob) return false
        return attachmentId == other.attachmentId && data.contentEquals(other.data)
    }
    override fun hashCode(): Int = attachmentId.hashCode() * 31 + data.contentHashCode()
}

/**
 * Complete attachment data section — all binary blobs.
 */
data class MnxAttachmentData(
    val blobs: List<MnxAttachmentBlob>
)

// =============================================================================
//  0x0012 — SENSORY_ASSOCIATIONS
// =============================================================================

/**
 * A sensory link between an entity and multi-modal data.
 *
 * This captures what something looks like, sounds like, feels like, etc.
 * The modality is an open-ended string — "visual", "auditory", "tactile",
 * "olfactory", "gustatory", "spatial", "thermal", "kinesthetic", or any
 * future sensory dimension.
 *
 * @property entityId       The entity this sensory data is associated with.
 * @property modality       The sensory dimension (open-ended string).
 * @property attachmentIds  UUIDs of attachments that provide this sensory data.
 * @property description    Free-text description of the sensory experience.
 * @property intensity      How strong this sensory association is (0.0–1.0).
 * @property valence        Positive/negative quality of the sensation (-1.0 to 1.0).
 */
data class MnxSensoryLink(
    val entityId: String,
    val modality: String,
    val attachmentIds: List<String> = emptyList(),
    val description: String = "",
    val intensity: Float = 0.5f,
    val valence: Float = 0.0f
)

/**
 * Complete sensory associations section.
 */
data class MnxSensoryAssociations(
    val links: List<MnxSensoryLink>
)

// =============================================================================
//  0x0013 — DIMENSIONAL_REFS
// =============================================================================

/**
 * A dimensional reference: an abstract association between any subject
 * and any target through a named dimension.
 *
 * This is the most open-ended structure in the format. It can represent:
 * - A person associated with a 3D avatar model: ("person_bob", "3d_model", "attach_uuid")
 * - A memory linked to a sound: ("chunk_123", "auditory", "attach_uuid")
 * - A concept linked to a color: ("concept_freedom", "visual_color", "#4488FF")
 * - A place linked to a spatial coordinate: ("place_home", "spatial", "47.6,-122.3,0")
 * - Any future association: ("subject", "dimension_name", "target_value")
 *
 * @property subject        The source entity/concept ID.
 * @property dimension      The named dimension (open-ended: "visual", "auditory",
 *                          "spatial", "temporal", "conceptual", "3d_model",
 *                          "color", "texture", "weight", or anything).
 * @property target         The target value — an attachment UUID, a literal value,
 *                          another entity ID, or any string-encoded reference.
 * @property targetType     What kind of target this is: "attachment", "literal",
 *                          "entity", "chunk", "url", "coordinate", or custom.
 * @property confidence     How strong/certain this association is (0.0–1.0).
 * @property metadata       Additional key-value context for this reference.
 */
data class MnxDimensionalRef(
    val subject: String,
    val dimension: String,
    val target: String,
    val targetType: String = "literal",
    val confidence: Float = 1.0f,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Complete dimensional references section.
 */
data class MnxDimensionalRefs(
    val refs: List<MnxDimensionalRef>
)

// =============================================================================
//  0x00FF — META
// =============================================================================

/**
 * Extensible metadata section.
 */
data class MnxMeta(
    val entries: Map<String, String>
)
