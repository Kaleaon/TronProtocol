package com.tronprotocol.app.phylactery

/**
 * The four tiers of the Continuum Memory System (CMS).
 *
 * Inspired by MemRL (arXiv:2601.03192) and the TronProtocol Pixel 10 spec.
 * Each tier has a distinct update frequency, storage strategy, and content type.
 */
enum class MemoryTier(val label: String) {
    /**
     * Every inference turn. In-memory (volatile).
     * Current conversation context, active emotional state, recent KV cache.
     */
    WORKING("working"),

    /**
     * Per session close. Persistent local storage.
     * Session summaries, interaction embeddings, emotional trajectory snapshots.
     */
    EPISODIC("episodic"),

    /**
     * Daily consolidation. Persistent local + cloud sync.
     * Extracted knowledge, relationship models, preference patterns, learned facts.
     */
    SEMANTIC("semantic"),

    /**
     * Immutable (verified at boot). Encrypted file + hash in Keystore.
     * Ethical kernel, base personality weights, identity axioms, consent gating rules.
     */
    CORE_IDENTITY("core_identity");

    companion object {
        fun fromLabel(label: String): MemoryTier? =
            entries.find { it.label == label }
    }
}
