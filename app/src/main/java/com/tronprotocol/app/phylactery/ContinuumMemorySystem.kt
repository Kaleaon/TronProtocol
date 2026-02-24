package com.tronprotocol.app.phylactery

import android.content.Context
import android.util.Log
import com.tronprotocol.app.security.SecureStorage
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.sqrt

/**
 * Continuum Memory System (CMS) — the four-tier Phylactery implementation.
 *
 * Implements the memory architecture from the TronProtocol Pixel 10 spec:
 * - Working Memory: volatile, per-turn conversation context
 * - Episodic Memory: per-session summaries with embeddings
 * - Semantic Memory: daily-consolidated knowledge with cloud sync
 * - Core Identity: immutable ethical kernel with hardware-backed hash
 *
 * Uses simplified TF-IDF embeddings for on-device semantic search (to be
 * replaced with MediaPipe text embedder or sqlite-vec when available).
 */
class ContinuumMemorySystem(private val context: Context) {

    private val storage = SecureStorage(context)

    /** Working memory: volatile, current conversation context. */
    private val workingMemory = CopyOnWriteArrayList<PhylacteryEntry>()

    /** Episodic memory: persistent session summaries. */
    private val episodicMemory = CopyOnWriteArrayList<PhylacteryEntry>()

    /** Semantic memory: consolidated knowledge. */
    private val semanticMemory = CopyOnWriteArrayList<PhylacteryEntry>()

    /** Core identity: immutable axioms. */
    private val coreIdentity = CopyOnWriteArrayList<PhylacteryEntry>()

    /** Index of all entries by ID for fast lookup. */
    private val entryIndex = ConcurrentHashMap<String, PhylacteryEntry>()

    /** Current session ID. */
    @Volatile
    var currentSessionId: String = UUID.randomUUID().toString()
        private set

    /** Listeners for memory events. */
    private val listeners = CopyOnWriteArrayList<MemoryEventListener>()

    init {
        loadPersistentMemory()
    }

    // ---- Working Memory (Tier 1) -------------------------------------------

    /**
     * Add a turn to working memory. Called every inference turn.
     */
    fun addWorkingMemory(
        content: String,
        emotionalSnapshot: Map<String, Float>? = null
    ): PhylacteryEntry {
        val entry = PhylacteryEntry(
            id = "wm_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}",
            tier = MemoryTier.WORKING,
            content = content,
            embedding = generateEmbedding(content),
            sessionId = currentSessionId,
            emotionalSnapshot = emotionalSnapshot
        )
        workingMemory.add(entry)
        entryIndex[entry.id] = entry

        // Cap working memory to prevent unbounded growth.
        while (workingMemory.size > MAX_WORKING_MEMORY) {
            val removed = workingMemory.removeAt(0)
            entryIndex.remove(removed.id)
        }

        notifyListeners(MemoryEvent.ENTRY_ADDED, entry)
        return entry
    }

    /** Get recent working memory entries (conversation context). */
    fun getWorkingContext(limit: Int = 20): List<PhylacteryEntry> =
        workingMemory.takeLast(limit)

    /** Clear working memory (e.g., on session close after summarization). */
    fun clearWorkingMemory() {
        workingMemory.forEach { entryIndex.remove(it.id) }
        workingMemory.clear()
    }

    // ---- Episodic Memory (Tier 2) ------------------------------------------

    /**
     * Store an episodic memory (session summary, interaction embedding).
     * Called at session close or when significant events occur.
     */
    fun addEpisodicMemory(
        content: String,
        emotionalSnapshot: Map<String, Float>? = null,
        metadata: Map<String, Any> = emptyMap()
    ): PhylacteryEntry {
        val entry = PhylacteryEntry(
            id = "ep_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}",
            tier = MemoryTier.EPISODIC,
            content = content,
            embedding = generateEmbedding(content),
            sessionId = currentSessionId,
            emotionalSnapshot = emotionalSnapshot,
            metadata = metadata.toMutableMap()
        )
        episodicMemory.add(entry)
        entryIndex[entry.id] = entry
        persistTier(MemoryTier.EPISODIC)
        notifyListeners(MemoryEvent.ENTRY_ADDED, entry)
        return entry
    }

    /** Retrieve episodic memories by semantic similarity. */
    fun retrieveEpisodicMemories(query: String, topK: Int = 5): List<PhylacteryEntry> =
        semanticSearch(episodicMemory, query, topK)

    // ---- Semantic Memory (Tier 3) ------------------------------------------

    /**
     * Store semantic knowledge (extracted facts, preferences, relationship data).
     * Updated during daily consolidation cycle.
     */
    fun addSemanticKnowledge(
        content: String,
        category: String,
        metadata: Map<String, Any> = emptyMap()
    ): PhylacteryEntry {
        val meta = metadata.toMutableMap()
        meta["category"] = category
        val entry = PhylacteryEntry(
            id = "sem_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}",
            tier = MemoryTier.SEMANTIC,
            content = content,
            embedding = generateEmbedding(content),
            metadata = meta
        )
        semanticMemory.add(entry)
        entryIndex[entry.id] = entry
        persistTier(MemoryTier.SEMANTIC)
        notifyListeners(MemoryEvent.ENTRY_ADDED, entry)
        return entry
    }

    /** Retrieve semantic knowledge by similarity. */
    fun retrieveSemanticKnowledge(query: String, topK: Int = 5): List<PhylacteryEntry> =
        semanticSearch(semanticMemory, query, topK)

    /** Retrieve semantic knowledge filtered by category. */
    fun retrieveByCategory(category: String, limit: Int = 10): List<PhylacteryEntry> =
        semanticMemory.filter { it.metadata["category"] == category }
            .sortedByDescending { it.qValue }
            .take(limit)

    // ---- Core Identity (Tier 4) --------------------------------------------

    /**
     * Store a core identity axiom. These are immutable once written and
     * verified against a hardware-backed hash at each heartbeat.
     */
    fun addCoreIdentity(content: String, axiomType: String): PhylacteryEntry {
        val entry = PhylacteryEntry(
            id = "core_${axiomType}_${UUID.randomUUID().toString().take(8)}",
            tier = MemoryTier.CORE_IDENTITY,
            content = content,
            metadata = mutableMapOf("axiom_type" to axiomType)
        )
        coreIdentity.add(entry)
        entryIndex[entry.id] = entry
        persistTier(MemoryTier.CORE_IDENTITY)
        notifyListeners(MemoryEvent.ENTRY_ADDED, entry)
        return entry
    }

    /** Get all core identity axioms. */
    fun getCoreIdentityAxioms(): List<PhylacteryEntry> = coreIdentity.toList()

    /**
     * Compute SHA-256 hash of the entire Core Identity tier for verification.
     * This hash is compared against the hardware-backed Keystore value at each heartbeat.
     */
    fun computeCoreIdentityHash(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val sorted = coreIdentity.sortedBy { it.id }
        for (entry in sorted) {
            digest.update(entry.content.toByteArray(Charsets.UTF_8))
            digest.update(entry.id.toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ---- Session Management ------------------------------------------------

    /**
     * Close the current session. Summarizes working memory into an episodic
     * entry and clears the working buffer.
     */
    fun closeSession(sessionSummary: String? = null) {
        val summary = sessionSummary ?: summarizeWorkingMemory()
        if (summary.isNotBlank()) {
            val emotionalTrajectory = workingMemory.mapNotNull { it.emotionalSnapshot }
                .lastOrNull()
            addEpisodicMemory(
                content = summary,
                emotionalSnapshot = emotionalTrajectory,
                metadata = mapOf(
                    "type" to "session_summary",
                    "turn_count" to workingMemory.size,
                    "session_id" to currentSessionId
                )
            )
        }
        clearWorkingMemory()
        currentSessionId = UUID.randomUUID().toString()
        Log.d(TAG, "Session closed, new session: $currentSessionId")
    }

    /** Generate a brief summary of the current working memory. */
    private fun summarizeWorkingMemory(): String {
        if (workingMemory.isEmpty()) return ""
        val turns = workingMemory.takeLast(10)
        return buildString {
            append("Session summary (${workingMemory.size} turns): ")
            turns.forEach { append(it.content.take(100)).append(" | ") }
        }.trimEnd(' ', '|')
    }

    // ---- Consolidation (Tier 2 → Tier 3) -----------------------------------

    /**
     * Daily consolidation: merge frequently-accessed episodic memories into
     * semantic knowledge. Prune low-resonance entries.
     */
    fun runConsolidation() {
        Log.d(TAG, "Starting memory consolidation cycle")

        // Promote high-Q episodic entries to semantic knowledge.
        val candidates = episodicMemory.filter {
            it.qValue > CONSOLIDATION_Q_THRESHOLD && it.retrievalCount >= CONSOLIDATION_RETRIEVAL_THRESHOLD
        }
        for (entry in candidates) {
            addSemanticKnowledge(
                content = entry.content,
                category = "consolidated_episodic",
                metadata = mapOf(
                    "source_episodic_id" to entry.id,
                    "original_q_value" to entry.qValue,
                    "retrieval_count" to entry.retrievalCount
                )
            )
        }

        // Decay Q-values of unretrieved episodic entries.
        val now = System.currentTimeMillis()
        for (entry in episodicMemory) {
            val age = now - entry.timestamp
            if (age > DECAY_AGE_MS && entry.retrievalCount == 0) {
                entry.qValue *= Q_DECAY_FACTOR
            }
        }

        // Prune episodic entries below threshold.
        val pruned = episodicMemory.filter { it.qValue < PRUNE_Q_THRESHOLD }
        episodicMemory.removeAll(pruned.toSet())
        pruned.forEach { entryIndex.remove(it.id) }

        persistTier(MemoryTier.EPISODIC)
        persistTier(MemoryTier.SEMANTIC)
        Log.d(TAG, "Consolidation complete: promoted=${candidates.size}, pruned=${pruned.size}")
    }

    // ---- Cross-tier Semantic Search ----------------------------------------

    /**
     * Search across all persistent tiers (episodic + semantic) for relevant memories.
     */
    fun searchAllMemory(query: String, topK: Int = 10): List<PhylacteryEntry> {
        val allEntries = episodicMemory + semanticMemory
        return semanticSearch(allEntries, query, topK)
    }

    /** Record a retrieval event (updates Q-value via MemRL-style learning). */
    fun recordRetrieval(entryId: String, feedbackReward: Float = 0.1f) {
        val entry = entryIndex[entryId] ?: return
        entry.retrievalCount++
        entry.lastAccessTime = System.currentTimeMillis()
        // MemRL Q-value update: Q ← Q + α(reward - Q)
        entry.qValue += Q_LEARNING_RATE * (feedbackReward - entry.qValue)
    }

    // ---- Stats -------------------------------------------------------------

    fun getStats(): Map<String, Any> = mapOf(
        "working_memory_size" to workingMemory.size,
        "episodic_memory_size" to episodicMemory.size,
        "semantic_memory_size" to semanticMemory.size,
        "core_identity_size" to coreIdentity.size,
        "total_entries" to entryIndex.size,
        "current_session_id" to currentSessionId
    )

    // ---- Listeners ---------------------------------------------------------

    fun addListener(listener: MemoryEventListener) { listeners.add(listener) }
    fun removeListener(listener: MemoryEventListener) { listeners.remove(listener) }

    private fun notifyListeners(event: MemoryEvent, entry: PhylacteryEntry) {
        for (listener in listeners) {
            try {
                listener.onMemoryEvent(event, entry)
            } catch (e: Exception) {
                Log.w(TAG, "Listener error", e)
            }
        }
    }

    // ---- Embedding & Search ------------------------------------------------

    /**
     * Generate a simplified TF-IDF-like embedding for on-device semantic search.
     * In production, this would use MediaPipe's all-MiniLM-L6-v2 (~384 dims, ~5ms).
     */
    private fun generateEmbedding(text: String): FloatArray {
        val tokens = text.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
        val embedding = FloatArray(EMBEDDING_DIM)
        for (token in tokens) {
            val h = token.hashCode()
            // Map each token to 4 distinct positions using different mixing seeds,
            // producing sparse vectors so different vocabularies remain distinguishable.
            for (k in 0 until 4) {
                val mixed = h xor (h ushr (k * 7 + 3)) xor (k * 1000003)
                val idx = mixed.and(Int.MAX_VALUE) % EMBEDDING_DIM
                embedding[idx] += 1.0f
            }
        }
        // L2 normalize.
        val norm = sqrt(embedding.map { it * it }.sum())
        if (norm > 0) {
            for (i in embedding.indices) embedding[i] /= norm
        }
        return embedding
    }

    /** KNN semantic search using cosine similarity. */
    private fun semanticSearch(
        entries: List<PhylacteryEntry>,
        query: String,
        topK: Int
    ): List<PhylacteryEntry> {
        val queryEmb = generateEmbedding(query)
        return entries.filter { it.embedding != null }
            .map { entry ->
                val sim = cosineSimilarity(queryEmb, entry.embedding!!)
                entry to sim
            }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0.0f
        var dot = 0.0f; var normA = 0.0f; var normB = 0.0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 0) dot / denom else 0.0f
    }

    // ---- Persistence -------------------------------------------------------

    private fun persistTier(tier: MemoryTier) {
        try {
            val entries = when (tier) {
                MemoryTier.WORKING -> return // volatile, not persisted
                MemoryTier.EPISODIC -> episodicMemory
                MemoryTier.SEMANTIC -> semanticMemory
                MemoryTier.CORE_IDENTITY -> coreIdentity
            }
            val array = JSONArray()
            entries.forEach { array.put(it.toJson()) }
            storage.store("phylactery_${tier.label}", array.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist ${tier.label}", e)
        }
    }

    private fun loadPersistentMemory() {
        for (tier in listOf(MemoryTier.EPISODIC, MemoryTier.SEMANTIC, MemoryTier.CORE_IDENTITY)) {
            try {
                val data = storage.retrieve("phylactery_${tier.label}") ?: continue
                val array = JSONArray(data)
                val target = when (tier) {
                    MemoryTier.EPISODIC -> episodicMemory
                    MemoryTier.SEMANTIC -> semanticMemory
                    MemoryTier.CORE_IDENTITY -> coreIdentity
                    else -> continue
                }
                for (i in 0 until array.length()) {
                    val entry = PhylacteryEntry.fromJson(array.getJSONObject(i))
                    target.add(entry)
                    entryIndex[entry.id] = entry
                }
                Log.d(TAG, "Loaded ${array.length()} ${tier.label} entries")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load ${tier.label}", e)
            }
        }
    }

    /** Export all persistent memory as JSON for Google Drive sync. */
    fun exportForSync(): JSONObject = JSONObject().apply {
        put("export_time", System.currentTimeMillis())
        put("session_id", currentSessionId)
        for (tier in listOf(MemoryTier.EPISODIC, MemoryTier.SEMANTIC, MemoryTier.CORE_IDENTITY)) {
            val entries = when (tier) {
                MemoryTier.EPISODIC -> episodicMemory
                MemoryTier.SEMANTIC -> semanticMemory
                MemoryTier.CORE_IDENTITY -> coreIdentity
                else -> continue
            }
            val array = JSONArray()
            entries.forEach { array.put(it.toJson()) }
            put(tier.label, array)
        }
    }

    // ---- Events ------------------------------------------------------------

    enum class MemoryEvent { ENTRY_ADDED, ENTRY_UPDATED, ENTRY_PRUNED, CONSOLIDATION_COMPLETE }

    interface MemoryEventListener {
        fun onMemoryEvent(event: MemoryEvent, entry: PhylacteryEntry)
    }

    companion object {
        private const val TAG = "ContinuumMemorySystem"
        private const val MAX_WORKING_MEMORY = 100
        private const val EMBEDDING_DIM = 128
        private const val CONSOLIDATION_Q_THRESHOLD = 0.7f
        private const val CONSOLIDATION_RETRIEVAL_THRESHOLD = 3
        private const val DECAY_AGE_MS = 7 * 24 * 3600 * 1000L // 7 days
        private const val Q_DECAY_FACTOR = 0.95f
        private const val PRUNE_Q_THRESHOLD = 0.1f
        private const val Q_LEARNING_RATE = 0.1f
    }
}
