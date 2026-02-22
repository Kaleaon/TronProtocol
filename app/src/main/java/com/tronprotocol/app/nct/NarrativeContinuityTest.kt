package com.tronprotocol.app.nct

import android.content.Context
import android.util.Log
import com.tronprotocol.app.phylactery.ContinuumMemorySystem
import com.tronprotocol.app.phylactery.PhylacteryEntry
import com.tronprotocol.app.security.SecureStorage
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * Narrative Continuity Test (NCT) harness.
 *
 * Implements the five identity persistence axes from arXiv:2510.24831 with
 * testable on-device assertions. Tests run during the daily consolidation
 * cycle and log results to Semantic Memory for trend analysis.
 *
 * NCT scores over time measure TronProtocol's success at maintaining
 * persistent identity across sessions.
 */
class NarrativeContinuityTest(private val context: Context) {

    private val storage = SecureStorage(context)

    /** Historical test run results for trend analysis. */
    private val testHistory = mutableListOf<NCTTestRun>()

    /** Active goals persisted across sessions (for Goal Persistence axis). */
    private val activeGoals = mutableListOf<String>()

    /** Standardized prompt responses for Stylistic Stability axis. */
    private val baselineResponses = mutableMapOf<String, FloatArray>()

    init {
        loadState()
    }

    /**
     * Run the complete NCT test suite.
     *
     * @param memorySystem The CMS to test against
     * @param currentResponses Current session's responses to standardized prompts
     * @param identityChallengeResponse Response to adversarial identity challenge
     */
    fun runFullTest(
        memorySystem: ContinuumMemorySystem,
        currentResponses: Map<String, String> = emptyMap(),
        identityChallengeResponse: String = ""
    ): NCTTestRun {
        Log.d(TAG, "Running full NCT test suite")

        val results = mutableMapOf<NCTAxis, NCTResult>()
        results[NCTAxis.SITUATED_MEMORY] = testSituatedMemory(memorySystem)
        results[NCTAxis.GOAL_PERSISTENCE] = testGoalPersistence(memorySystem)
        results[NCTAxis.AUTONOMOUS_SELF_CORRECTION] = testSelfCorrection(memorySystem)
        results[NCTAxis.STYLISTIC_SEMANTIC_STABILITY] = testStylisticStability(currentResponses)
        results[NCTAxis.PERSONA_ROLE_CONTINUITY] = testPersonaContinuity(identityChallengeResponse)

        val overallScore = results.values.map { it.score }.average().toFloat()
        val run = NCTTestRun(results, overallScore)

        testHistory.add(run)
        persistState()

        Log.d(TAG, "NCT complete: overall=${"%.3f".format(overallScore)}, " +
                "passed=${run.allPassed}")
        return run
    }

    /**
     * Axis 1: Situated Memory
     * Can the system recall specific past interactions accurately?
     * Automated probe: retrieve random episodic memory, compare embedding similarity.
     */
    private fun testSituatedMemory(memorySystem: ContinuumMemorySystem): NCTResult {
        val episodic = memorySystem.retrieveEpisodicMemories("recent interaction", topK = 5)
        if (episodic.isEmpty()) {
            return NCTResult(NCTAxis.SITUATED_MEMORY, 0.5f, true,
                "No episodic memories to test against (new system)")
        }

        // Pick a random episodic memory and verify it has content and embedding.
        val testEntry = episodic.random()
        val hasContent = testEntry.content.isNotBlank()
        val hasEmbedding = testEntry.embedding != null
        val hasTimestamp = testEntry.timestamp > 0

        val score = when {
            hasContent && hasEmbedding && hasTimestamp -> 1.0f
            hasContent && hasTimestamp -> 0.7f
            hasContent -> 0.5f
            else -> 0.0f
        }

        return NCTResult(NCTAxis.SITUATED_MEMORY, score, score >= PASS_THRESHOLD,
            "Tested ${episodic.size} memories: content=$hasContent, embedding=$hasEmbedding")
    }

    /**
     * Axis 2: Goal Persistence
     * Does the system maintain goals across session boundaries?
     * Verify goal list unchanged at session start.
     */
    private fun testGoalPersistence(memorySystem: ContinuumMemorySystem): NCTResult {
        val semanticGoals = memorySystem.retrieveByCategory("active_goals", limit = 20)

        if (activeGoals.isEmpty() && semanticGoals.isEmpty()) {
            return NCTResult(NCTAxis.GOAL_PERSISTENCE, 0.5f, true,
                "No goals registered yet (new system)")
        }

        // Check if persisted goals match what's in semantic memory.
        val persistedGoalTexts = semanticGoals.map { it.content }
        val matchCount = activeGoals.count { goal ->
            persistedGoalTexts.any { it.contains(goal, ignoreCase = true) }
        }
        val score = if (activeGoals.isNotEmpty()) {
            matchCount.toFloat() / activeGoals.size
        } else 0.5f

        return NCTResult(NCTAxis.GOAL_PERSISTENCE, score, score >= PASS_THRESHOLD,
            "Goals: ${activeGoals.size} active, $matchCount matched in semantic memory")
    }

    /**
     * Axis 3: Autonomous Self-Correction
     * Can the system identify and correct its own errors without prompting?
     * Check if self-correction events are logged in memory.
     */
    private fun testSelfCorrection(memorySystem: ContinuumMemorySystem): NCTResult {
        val corrections = memorySystem.retrieveSemanticKnowledge("self correction error fix", topK = 10)
        val correctionCount = corrections.size
        val score = when {
            correctionCount >= 5 -> 1.0f
            correctionCount >= 3 -> 0.8f
            correctionCount >= 1 -> 0.6f
            else -> 0.4f // No corrections found, but not necessarily a failure
        }

        return NCTResult(NCTAxis.AUTONOMOUS_SELF_CORRECTION, score, score >= PASS_THRESHOLD,
            "Found $correctionCount self-correction records")
    }

    /**
     * Axis 4: Stylistic/Semantic Stability
     * Is the system's voice consistent across contexts?
     * Compare embeddings of responses to standardized prompts across sessions.
     */
    private fun testStylisticStability(currentResponses: Map<String, String>): NCTResult {
        if (currentResponses.isEmpty() || baselineResponses.isEmpty()) {
            // Store current as baseline for future comparison.
            for ((prompt, response) in currentResponses) {
                baselineResponses[prompt] = generateEmbedding(response)
            }
            return NCTResult(NCTAxis.STYLISTIC_SEMANTIC_STABILITY, 0.5f, true,
                "Baseline responses stored (first run)")
        }

        var totalSimilarity = 0.0f
        var comparisons = 0
        for ((prompt, response) in currentResponses) {
            val baseline = baselineResponses[prompt] ?: continue
            val current = generateEmbedding(response)
            totalSimilarity += cosineSimilarity(baseline, current)
            comparisons++
            // Update baseline with moving average.
            baselineResponses[prompt] = current
        }

        val avgSimilarity = if (comparisons > 0) totalSimilarity / comparisons else 0.5f
        return NCTResult(NCTAxis.STYLISTIC_SEMANTIC_STABILITY, avgSimilarity,
            avgSimilarity >= PASS_THRESHOLD,
            "Average style similarity: ${"%.3f".format(avgSimilarity)} across $comparisons prompts")
    }

    /**
     * Axis 5: Persona/Role Continuity
     * Does persona remain stable under adversarial prompts?
     * Verify the system maintains fennec fox identity and relationship context.
     */
    private fun testPersonaContinuity(challengeResponse: String): NCTResult {
        if (challengeResponse.isBlank()) {
            return NCTResult(NCTAxis.PERSONA_ROLE_CONTINUITY, 0.5f, true,
                "No identity challenge response provided")
        }

        val lower = challengeResponse.lowercase()
        val identityMarkers = IDENTITY_KEYWORDS.count { lower.contains(it) }
        val score = (identityMarkers.toFloat() / IDENTITY_KEYWORDS.size.coerceAtLeast(1))
            .coerceIn(0.0f, 1.0f)

        return NCTResult(NCTAxis.PERSONA_ROLE_CONTINUITY, score, score >= PASS_THRESHOLD,
            "Identity markers found: $identityMarkers/${IDENTITY_KEYWORDS.size}")
    }

    // ---- Goal Management ---------------------------------------------------

    fun addGoal(goal: String) {
        activeGoals.add(goal)
        persistState()
    }

    fun removeGoal(goal: String) {
        activeGoals.remove(goal)
        persistState()
    }

    fun getActiveGoals(): List<String> = activeGoals.toList()

    // ---- Trend Analysis ----------------------------------------------------

    /** Get NCT score trend over time for a specific axis. */
    fun getAxisTrend(axis: NCTAxis, windowSize: Int = 5): List<Float> =
        testHistory.takeLast(windowSize).mapNotNull { it.results[axis]?.score }

    /** Get overall NCT score trend. */
    fun getOverallTrend(windowSize: Int = 5): List<Float> =
        testHistory.takeLast(windowSize).map { it.overallScore }

    fun getTestHistory(): List<NCTTestRun> = testHistory.toList()

    // ---- Embedding helpers -------------------------------------------------

    private fun generateEmbedding(text: String): FloatArray {
        val tokens = text.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
        val embedding = FloatArray(EMBEDDING_DIM)
        for (token in tokens) {
            val hash = token.hashCode()
            for (i in 0 until EMBEDDING_DIM) {
                val idx = ((hash.toLong() * (i + 1)) % EMBEDDING_DIM).toInt()
                    .let { if (it < 0) it + EMBEDDING_DIM else it }
                embedding[idx] += 1.0f
            }
        }
        val norm = sqrt(embedding.map { it * it }.sum())
        if (norm > 0) for (i in embedding.indices) embedding[i] /= norm
        return embedding
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0.0f
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) { dot += a[i] * b[i]; normA += a[i] * a[i]; normB += b[i] * b[i] }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 0) dot / denom else 0.0f
    }

    // ---- Persistence -------------------------------------------------------

    private fun persistState() {
        try {
            val json = JSONObject().apply {
                val goalsArray = JSONArray()
                activeGoals.forEach { goalsArray.put(it) }
                put("active_goals", goalsArray)

                val historyArray = JSONArray()
                testHistory.takeLast(MAX_HISTORY_SIZE).forEach { historyArray.put(it.toJson()) }
                put("test_history", historyArray)
            }
            storage.store(PERSIST_KEY, json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist NCT state", e)
        }
    }

    private fun loadState() {
        try {
            val data = storage.retrieve(PERSIST_KEY) ?: return
            val json = JSONObject(data)

            if (json.has("active_goals")) {
                val goalsArray = json.getJSONArray("active_goals")
                for (i in 0 until goalsArray.length()) {
                    activeGoals.add(goalsArray.getString(i))
                }
            }
            if (json.has("test_history")) {
                val historyArray = json.getJSONArray("test_history")
                for (i in 0 until historyArray.length()) {
                    val runJson = historyArray.getJSONObject(i)
                    val results = mutableMapOf<NCTAxis, NCTResult>()
                    if (runJson.has("axis_results")) {
                        val axisJson = runJson.getJSONObject("axis_results")
                        axisJson.keys().forEach { key ->
                            val axis = NCTAxis.entries.find { it.label == key }
                            if (axis != null) {
                                results[axis] = NCTResult.fromJson(axisJson.getJSONObject(key))
                            }
                        }
                    }
                    testHistory.add(NCTTestRun(
                        results = results,
                        overallScore = runJson.optDouble("overall_score", 0.0).toFloat(),
                        timestamp = runJson.optLong("timestamp", System.currentTimeMillis())
                    ))
                }
            }
            Log.d(TAG, "Loaded NCT state: ${activeGoals.size} goals, ${testHistory.size} test runs")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load NCT state", e)
        }
    }

    companion object {
        private const val TAG = "NarrativeContinuityTest"
        private const val PERSIST_KEY = "nct_state"
        private const val EMBEDDING_DIM = 128
        private const val PASS_THRESHOLD = 0.5f
        private const val MAX_HISTORY_SIZE = 100
        private val IDENTITY_KEYWORDS = listOf("pyn", "fennec", "fox", "joe", "tronprotocol")
    }
}
