package com.tronprotocol.app.frontier

import android.content.Context
import android.util.Log
import com.tronprotocol.app.rag.RAGStore
import com.tronprotocol.app.rag.RetrievalResult
import com.tronprotocol.app.security.SecureStorage
import org.json.JSONObject

/**
 * FrontierDynamicsManager — singleton coordinator for the STLE framework
 * integrated into TronProtocol.
 *
 * Responsibilities:
 * 1. Manage STLE engine lifecycle (training, inference, persistence)
 * 2. Provide accessibility scoring for RAG retrieval results
 * 3. Supply uncertainty metrics to HallucinationDetector
 * 4. Inform DecisionRouter with frontier-aware routing signals
 * 5. Bayesian update of accessibility scores based on feedback
 *
 * The manager maintains an embedding-based STLE model that learns from
 * the RAG store's TF-IDF embeddings. When new data arrives, the model
 * can be incrementally retrained or updated via Bayesian posterior revision.
 *
 * @see <a href="https://github.com/strangehospital/Frontier-Dynamics-Project">Frontier Dynamics</a>
 */
class FrontierDynamicsManager(
    private val context: Context
) {
    private val storage: SecureStorage = SecureStorage(context)

    /** The core STLE engine, lazily initialized when first trained. */
    private var engine: STLEEngine? = null

    /** Cache of recent accessibility computations to avoid redundant inference. */
    private val accessibilityCache = LinkedHashMap<Int, AccessibilityResult>(
        CACHE_CAPACITY, 0.75f, true
    )

    /** Running statistics for monitoring. */
    private var totalInferences = 0L
    private var totalTrainings = 0L
    private var lastTrainingTimestamp = 0L

    val isReady: Boolean
        get() = engine?.isTrained == true

    // ========================================================================
    // Training
    // ========================================================================

    /**
     * Train the STLE engine from a RAG store's embeddings.
     *
     * Extracts embedding vectors from all chunks in the store, assigns
     * pseudo-labels based on Q-value thresholds (high Q → class 0 "reliable",
     * low Q → class 1 "unreliable"), and fits the STLE model.
     *
     * @param ragStore The RAG store to train from
     * @param embeddingDim Embedding dimensionality (default 128 matching RAGStore)
     */
    fun trainFromRAGStore(ragStore: RAGStore, embeddingDim: Int = DEFAULT_EMBEDDING_DIM) {
        val chunks = ragStore.getChunks()
        if (chunks.size < MIN_TRAINING_SAMPLES) {
            Log.d(TAG, "Insufficient samples for STLE training: ${chunks.size} < $MIN_TRAINING_SAMPLES")
            return
        }

        val embeddings = mutableListOf<FloatArray>()
        val labels = mutableListOf<Int>()

        for (chunk in chunks) {
            val emb = chunk.embedding ?: continue
            if (emb.size != embeddingDim) continue

            embeddings.add(emb)
            // Pseudo-label from Q-value: high Q = reliable (0), low Q = unreliable (1)
            labels.add(if (chunk.qValue >= Q_VALUE_THRESHOLD) 0 else 1)
        }

        if (embeddings.size < MIN_TRAINING_SAMPLES) {
            Log.d(TAG, "Insufficient embedded samples: ${embeddings.size}")
            return
        }

        val X = embeddings.toTypedArray()
        val y = labels.toIntArray()

        engine = STLEEngine(inputDim = embeddingDim, numClasses = 2).also {
            it.fit(X, y, epochs = TRAINING_EPOCHS, learningRate = TRAINING_LR)
        }

        totalTrainings++
        lastTrainingTimestamp = System.currentTimeMillis()
        accessibilityCache.clear()

        // Persist training state
        saveState()

        Log.d(TAG, "STLE trained from RAG store: ${embeddings.size} samples, " +
                "dim=$embeddingDim, classes=2")
    }

    /**
     * Train the STLE engine from explicit feature vectors and labels.
     */
    fun train(X: Array<FloatArray>, y: IntArray, numClasses: Int = 2) {
        if (X.isEmpty()) return

        engine = STLEEngine(inputDim = X[0].size, numClasses = numClasses).also {
            it.fit(X, y)
        }
        totalTrainings++
        lastTrainingTimestamp = System.currentTimeMillis()
        accessibilityCache.clear()
        saveState()
    }

    // ========================================================================
    // Accessibility Scoring
    // ========================================================================

    /**
     * Score a single embedding for accessibility.
     *
     * @param embedding Feature vector to evaluate
     * @return AccessibilityResult or null if engine not trained
     */
    fun scoreEmbedding(embedding: FloatArray): AccessibilityResult? {
        val eng = engine ?: return null
        if (!eng.isTrained) return null

        val cacheKey = embedding.contentHashCode()
        accessibilityCache[cacheKey]?.let { return it }

        val result = eng.predictSingle(embedding)
        totalInferences++

        // Manage cache size
        if (accessibilityCache.size >= CACHE_CAPACITY) {
            val it = accessibilityCache.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
        accessibilityCache[cacheKey] = result

        return result
    }

    /**
     * Score RAG retrieval results with accessibility, augmenting the retrieval
     * scores with STLE uncertainty information.
     *
     * Retrieval results whose chunks have low mu_x (frontier/inaccessible)
     * are flagged to indicate the response may require additional verification.
     *
     * @return List of ScoredRetrieval with accessibility metadata
     */
    fun scoreRetrievalResults(results: List<RetrievalResult>): List<ScoredRetrieval> {
        val eng = engine
        if (eng == null || !eng.isTrained) {
            // No STLE model: return results with default accessibility
            return results.map { ScoredRetrieval(it, null) }
        }

        return results.map { result ->
            val embedding = result.chunk.embedding
            val accessibility = if (embedding != null && embedding.size == eng.inputDim) {
                scoreEmbedding(embedding)
            } else {
                null
            }
            ScoredRetrieval(result, accessibility)
        }
    }

    /**
     * Compute aggregate accessibility for a set of retrieval results.
     * Useful for determining overall confidence in a RAG-augmented response.
     */
    fun aggregateAccessibility(results: List<RetrievalResult>): AggregateAccessibility {
        val scored = scoreRetrievalResults(results)
        val accessible = scored.filter { it.accessibility != null }

        if (accessible.isEmpty()) {
            return AggregateAccessibility(
                meanMuX = 0.5f, minMuX = 0.5f, maxMuX = 0.5f,
                frontierCount = 0, inaccessibleCount = 0,
                totalScored = 0, confidenceLevel = ConfidenceLevel.UNKNOWN
            )
        }

        val muXValues = accessible.map { it.accessibility!!.muX }
        val meanMuX = muXValues.average().toFloat()
        val minMuX = muXValues.min()
        val maxMuX = muXValues.max()

        val frontierCount = accessible.count { it.accessibility!!.frontierState == FrontierState.FRONTIER }
        val inaccessibleCount = accessible.count { it.accessibility!!.frontierState == FrontierState.INACCESSIBLE }

        val confidence = when {
            meanMuX > 0.8f && inaccessibleCount == 0 -> ConfidenceLevel.HIGH
            meanMuX > 0.5f && inaccessibleCount <= 1 -> ConfidenceLevel.MODERATE
            meanMuX > 0.3f -> ConfidenceLevel.LOW
            else -> ConfidenceLevel.VERY_LOW
        }

        return AggregateAccessibility(
            meanMuX = meanMuX,
            minMuX = minMuX,
            maxMuX = maxMuX,
            frontierCount = frontierCount,
            inaccessibleCount = inaccessibleCount,
            totalScored = accessible.size,
            confidenceLevel = confidence
        )
    }

    // ========================================================================
    // Bayesian Updates
    // ========================================================================

    /**
     * Update accessibility score for a chunk based on feedback evidence.
     *
     * @param currentMuX Current accessibility score
     * @param wasHelpful Whether the retrieval was helpful (positive evidence)
     * @return Updated mu_x value
     */
    fun updateAccessibility(currentMuX: Float, wasHelpful: Boolean): Float {
        val eng = engine ?: return currentMuX
        val (lAccess, lInaccess) = if (wasHelpful) {
            POSITIVE_LIKELIHOOD_ACCESSIBLE to POSITIVE_LIKELIHOOD_INACCESSIBLE
        } else {
            NEGATIVE_LIKELIHOOD_ACCESSIBLE to NEGATIVE_LIKELIHOOD_INACCESSIBLE
        }
        return eng.bayesianUpdate(currentMuX, lAccess, lInaccess)
    }

    // ========================================================================
    // Uncertainty-aware Hallucination Support
    // ========================================================================

    /**
     * Compute an STLE-based uncertainty score for text content.
     * Used by HallucinationDetector as an additional signal.
     *
     * @param embedding The text chunk embedding to evaluate
     * @return Uncertainty score in [0, 1]: higher = more uncertain
     */
    fun computeUncertaintyScore(embedding: FloatArray): Float {
        val result = scoreEmbedding(embedding) ?: return 0.5f
        // mu_y directly represents inaccessibility / uncertainty
        return result.muY
    }

    // ========================================================================
    // Decision Routing Support
    // ========================================================================

    /**
     * Determine whether a prompt's embedding suggests it should be escalated
     * to a higher-capability tier (cloud) based on frontier analysis.
     *
     * A prompt in the FRONTIER or INACCESSIBLE state has high epistemic
     * uncertainty, meaning a small on-device model is likely to be unreliable.
     *
     * @param embedding Feature vector of the prompt
     * @return true if the prompt should be escalated to cloud
     */
    fun shouldEscalateToCloud(embedding: FloatArray): Boolean {
        val result = scoreEmbedding(embedding) ?: return false
        return result.frontierState != FrontierState.ACCESSIBLE
    }

    // ========================================================================
    // Statistics & Persistence
    // ========================================================================

    /**
     * Get comprehensive statistics about the STLE system.
     */
    fun getStats(): Map<String, Any> {
        val stats = mutableMapOf<String, Any>()
        stats["is_ready"] = isReady
        stats["total_inferences"] = totalInferences
        stats["total_trainings"] = totalTrainings
        stats["last_training_timestamp"] = lastTrainingTimestamp
        stats["cache_size"] = accessibilityCache.size

        engine?.let { eng ->
            stats["input_dim"] = eng.inputDim
            stats["num_classes"] = eng.numClasses
            stats["training_size"] = eng.trainingSize
        }

        return stats
    }

    private fun saveState() {
        try {
            val json = JSONObject().apply {
                put("total_inferences", totalInferences)
                put("total_trainings", totalTrainings)
                put("last_training_timestamp", lastTrainingTimestamp)
                put("is_ready", isReady)
                engine?.let {
                    put("input_dim", it.inputDim)
                    put("num_classes", it.numClasses)
                    put("training_size", it.trainingSize)
                }
            }
            storage.store(STORAGE_KEY, json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist STLE state", e)
        }
    }

    private fun loadState() {
        try {
            val data = storage.retrieve(STORAGE_KEY) ?: return
            val json = JSONObject(data)
            totalInferences = json.optLong("total_inferences", 0)
            totalTrainings = json.optLong("total_trainings", 0)
            lastTrainingTimestamp = json.optLong("last_training_timestamp", 0)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load STLE state", e)
        }
    }

    init {
        loadState()
    }

    companion object {
        private const val TAG = "FrontierDynamicsManager"
        private const val STORAGE_KEY = "frontier_dynamics_state"

        const val DEFAULT_EMBEDDING_DIM = 128
        private const val MIN_TRAINING_SAMPLES = 10
        private const val TRAINING_EPOCHS = 50
        private const val TRAINING_LR = 0.05f
        private const val Q_VALUE_THRESHOLD = 0.5f
        private const val CACHE_CAPACITY = 256

        // Bayesian update likelihoods
        private const val POSITIVE_LIKELIHOOD_ACCESSIBLE = 0.9f
        private const val POSITIVE_LIKELIHOOD_INACCESSIBLE = 0.1f
        private const val NEGATIVE_LIKELIHOOD_ACCESSIBLE = 0.1f
        private const val NEGATIVE_LIKELIHOOD_INACCESSIBLE = 0.9f
    }
}

/**
 * A retrieval result augmented with STLE accessibility scoring.
 */
data class ScoredRetrieval(
    val retrievalResult: RetrievalResult,
    val accessibility: AccessibilityResult?
) {
    /** Whether this result is in the learning frontier (partial knowledge). */
    val isFrontier: Boolean
        get() = accessibility?.frontierState == FrontierState.FRONTIER

    /** Whether this result is out-of-distribution (no knowledge). */
    val isOOD: Boolean
        get() = accessibility?.frontierState == FrontierState.INACCESSIBLE
}

/**
 * Aggregate accessibility summary for a batch of retrieval results.
 */
data class AggregateAccessibility(
    val meanMuX: Float,
    val minMuX: Float,
    val maxMuX: Float,
    val frontierCount: Int,
    val inaccessibleCount: Int,
    val totalScored: Int,
    val confidenceLevel: ConfidenceLevel
)

/**
 * Confidence levels derived from STLE accessibility analysis.
 */
enum class ConfidenceLevel {
    /** Mean mu_x > 0.8, no OOD samples. Safe to proceed. */
    HIGH,
    /** Mean mu_x > 0.5, at most 1 OOD sample. Proceed with caution. */
    MODERATE,
    /** Mean mu_x > 0.3. Consider additional verification. */
    LOW,
    /** Mean mu_x <= 0.3. Defer to human or cloud. */
    VERY_LOW,
    /** STLE not available; no accessibility data. */
    UNKNOWN
}
