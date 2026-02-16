package com.tronprotocol.app.rag

/**
 * NTS memory stages inspired by the biological cascade architecture.
 */
enum class MemoryStage(
    val durabilityWeight: Float,
    val defaultTtlMinutes: Long
) {
    SENSORY(durabilityWeight = 0.30f, defaultTtlMinutes = 2L),
    WORKING(durabilityWeight = 0.55f, defaultTtlMinutes = 120L),
    EPISODIC(durabilityWeight = 0.80f, defaultTtlMinutes = 60L * 24L * 14L),
    SEMANTIC(durabilityWeight = 1.00f, defaultTtlMinutes = 60L * 24L * 365L * 3L);

    companion object {
        private const val STAGE_KEY = "nts_stage"

        fun fromChunk(chunk: TextChunk): MemoryStage {
            val value = chunk.metadata[STAGE_KEY]?.toString()?.trim()?.uppercase() ?: return WORKING
            return values().firstOrNull { it.name == value } ?: WORKING
        }

        fun assign(chunk: TextChunk, stage: MemoryStage) {
            chunk.addMetadata(STAGE_KEY, stage.name)
        }

        fun parse(value: String?): MemoryStage? {
            if (value.isNullOrBlank()) {
                return null
            }
            val normalized = value.trim().uppercase()
            return values().firstOrNull { it.name == normalized }
        }
    }
}
