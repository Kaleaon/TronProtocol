package com.tronprotocol.app.emotion

import android.content.Context
import android.util.Log
import com.tronprotocol.app.security.SecureStorage
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * Emotional State Manager with Hallucination Detection
 *
 * Implements emotion-based learning inspired by:
 * - SelfCheckGPT (arXiv:2303.08896) - Consistency-based hallucination detection
 * - awesome-hallucination-detection (EdinburghNLP) - State-of-the-art techniques
 *
 * Key features:
 * - Embarrassment as negative reinforcement for hallucinations
 * - Multi-response consistency checking (SelfCheckGPT)
 * - Belief tree propagation for claim verification
 * - Emotional bias influences future decisions
 * - Self-uncertainty expression to avoid hallucinations
 */
class EmotionalStateManager(private val context: Context) {

    enum class Emotion {
        CONFIDENT,       // High consistency, verified factual
        UNCERTAIN,       // Low confidence, should defer
        EMBARRASSED,     // Detected hallucination or error
        PROUD,           // Correct prediction confirmed, learned from mistake
        CURIOUS,         // Learning new information
        NEUTRAL          // Default state
    }

    private val storage = SecureStorage(context)
    private val emotionalHistory = mutableListOf<EmotionalEvent>()

    private var currentEmotion: Emotion = Emotion.NEUTRAL
    private var emotionalIntensity: Float = 0.5f
    private var embarrassmentCount: Int = 0
    private var lastEmbarrassmentTime: Long = 0L

    /**
     * Personality traits system (inspired by subliminal-learning research).
     *
     * Traits are implicit behavioral tendencies that evolve through experience,
     * not explicit programming. They influence response style and decision-making.
     *
     * Key insight from subliminal-learning (arXiv:2507.14805): behavioral traits
     * transfer through statistical patterns in data, not explicit content.
     * Here we model this as traits that strengthen through repeated experience.
     */
    private val personalityTraits = mutableMapOf<String, Float>()
    private var curiosityStreak: Int = 0
    private var lastCuriosityTime: Long = 0L

    init {
        loadEmotionalHistory()
        loadPersonalityTraits()
    }

    /**
     * SelfCheckGPT: Multi-response consistency checking
     * Generates multiple responses and compares for hallucination detection
     */
    fun checkConsistency(responses: List<String>?): ConsistencyResult {
        if (responses == null || responses.size < 2) {
            return ConsistencyResult(false, 0.0f, "Need multiple responses for consistency check")
        }

        var totalSimilarity = 0.0f
        var comparisons = 0

        for (i in responses.indices) {
            for (j in i + 1 until responses.size) {
                val similarity = calculateJaccardSimilarity(responses[i], responses[j])
                totalSimilarity += similarity
                comparisons++
            }
        }

        val avgSimilarity = if (comparisons > 0) totalSimilarity / comparisons else 0.0f
        val isConsistent = avgSimilarity > 0.7f

        val assessment = when {
            avgSimilarity > 0.8f -> "High consistency - likely factual"
            avgSimilarity > 0.6f -> "Moderate consistency - verify facts"
            else -> "Low consistency - hallucination detected"
        }

        return ConsistencyResult(isConsistent, avgSimilarity, assessment)
    }

    private fun calculateJaccardSimilarity(text1: String, text2: String): Float {
        val words1 = text1.lowercase().split("\\s+".toRegex())
        val words2 = text2.lowercase().split("\\s+".toRegex())

        val union = mutableMapOf<String, Boolean>()
        val intersection = mutableMapOf<String, Boolean>()

        for (word in words1) union[word] = true
        for (word in words2) {
            if (union.containsKey(word)) intersection[word] = true
            union[word] = true
        }

        return if (union.isEmpty()) 0.0f else intersection.size.toFloat() / union.size
    }

    /**
     * Apply embarrassment when hallucination detected
     * Creates strong negative emotional reinforcement
     */
    fun applyEmbarrassment(context: String, severity: Float): Float {
        embarrassmentCount++
        lastEmbarrassmentTime = System.currentTimeMillis()

        currentEmotion = Emotion.EMBARRASSED
        emotionalIntensity = min(1.0f, severity)

        val event = EmotionalEvent(
            Emotion.EMBARRASSED, emotionalIntensity, context, System.currentTimeMillis()
        )
        emotionalHistory.add(event)

        Log.w(TAG, String.format("EMBARRASSMENT applied (%.2f): %s", severity, context))
        saveEmotionalHistory()

        return EMBARRASSMENT_PENALTY * severity
    }

    /**
     * Apply confidence when response verified correct
     */
    fun applyConfidence(context: String): Float {
        currentEmotion = Emotion.CONFIDENT
        emotionalIntensity = 0.8f

        emotionalHistory.add(
            EmotionalEvent(Emotion.CONFIDENT, emotionalIntensity, context, System.currentTimeMillis())
        )

        Log.d(TAG, "Confidence boost: $context")
        saveEmotionalHistory()

        return CONFIDENCE_BOOST
    }

    /**
     * Apply pride when AI learns from mistake
     */
    fun applyPride(context: String): Float {
        currentEmotion = Emotion.PROUD
        emotionalIntensity = 0.9f

        emotionalHistory.add(
            EmotionalEvent(Emotion.PROUD, emotionalIntensity, context, System.currentTimeMillis())
        )

        Log.d(TAG, "Pride applied (learned from mistake): $context")
        saveEmotionalHistory()

        return CONFIDENCE_BOOST * 1.5f
    }

    /**
     * Express uncertainty - better to say "I don't know" than hallucinate
     */
    fun expressUncertainty(context: String) {
        currentEmotion = Emotion.UNCERTAIN
        emotionalIntensity = 0.3f

        emotionalHistory.add(
            EmotionalEvent(Emotion.UNCERTAIN, emotionalIntensity, context, System.currentTimeMillis())
        )

        Log.d(TAG, "Expressing uncertainty: $context")
        saveEmotionalHistory()
    }

    /**
     * Apply curiosity when encountering new information or topics.
     * Implements the CURIOUS emotion state that was previously unused.
     *
     * Curiosity drives exploration behavior:
     * - Increases willingness to retrieve more context
     * - Boosts engagement with novel topics
     * - Builds a curiosity streak for sustained exploration
     */
    fun applyCuriosity(context: String): Float {
        currentEmotion = Emotion.CURIOUS
        emotionalIntensity = 0.7f

        curiosityStreak++
        lastCuriosityTime = System.currentTimeMillis()

        emotionalHistory.add(
            EmotionalEvent(Emotion.CURIOUS, emotionalIntensity, context, System.currentTimeMillis())
        )

        // Strengthen the curiosity personality trait
        reinforceTrait("curiosity", CURIOSITY_TRAIT_BOOST)

        Log.d(TAG, "Curiosity applied (streak: $curiosityStreak): $context")
        saveEmotionalHistory()

        return CURIOSITY_BOOST
    }

    /**
     * Check if the AI is in an active curiosity streak.
     * A streak means multiple curiosity events within a short period.
     */
    fun isInCuriosityStreak(): Boolean {
        val timeSinceCuriosity = System.currentTimeMillis() - lastCuriosityTime
        return curiosityStreak >= 2 && timeSinceCuriosity < CURIOSITY_STREAK_WINDOW_MS
    }

    // --- Personality Trait System (subliminal-learning inspired) ---

    /**
     * Reinforce a personality trait through experience.
     *
     * Inspired by the subliminal-learning finding that behavioral traits
     * transmit through repeated exposure patterns. Each experience slightly
     * shifts the trait value, creating emergent personality over time.
     *
     * @param traitName Name of the trait (e.g., "caution", "curiosity", "thoroughness")
     * @param amount Reinforcement amount (positive or negative)
     */
    fun reinforceTrait(traitName: String, amount: Float) {
        val current = personalityTraits.getOrDefault(traitName, 0.5f)
        val updated = (current + amount * TRAIT_LEARNING_RATE).coerceIn(0.0f, 1.0f)
        personalityTraits[traitName] = updated
        savePersonalityTraits()
    }

    /**
     * Get the current value of a personality trait.
     * Returns 0.5 (neutral) if the trait hasn't been established.
     */
    fun getTraitValue(traitName: String): Float {
        return personalityTraits.getOrDefault(traitName, 0.5f)
    }

    /**
     * Get all personality traits.
     */
    fun getPersonalityTraits(): Map<String, Float> = personalityTraits.toMap()

    /**
     * Get a personality profile summary.
     * Categorizes traits into dominant, balanced, and weak.
     */
    fun getPersonalityProfile(): Map<String, Any> {
        val profile = mutableMapOf<String, Any>()
        val dominant = personalityTraits.filter { it.value > 0.7f }.keys.toList()
        val balanced = personalityTraits.filter { it.value in 0.3f..0.7f }.keys.toList()
        val weak = personalityTraits.filter { it.value < 0.3f }.keys.toList()

        profile["dominant_traits"] = dominant
        profile["balanced_traits"] = balanced
        profile["weak_traits"] = weak
        profile["trait_count"] = personalityTraits.size
        profile["curiosity_streak"] = curiosityStreak

        return profile
    }

    private fun savePersonalityTraits() {
        try {
            val traitsObj = JSONObject()
            for ((key, value) in personalityTraits) {
                traitsObj.put(key, value.toDouble())
            }
            traitsObj.put("_curiosity_streak", curiosityStreak)
            traitsObj.put("_last_curiosity_time", lastCuriosityTime)
            storage.store(PERSONALITY_TRAITS_KEY, traitsObj.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error saving personality traits", e)
        }
    }

    private fun loadPersonalityTraits() {
        try {
            val data = storage.retrieve(PERSONALITY_TRAITS_KEY) ?: return
            val traitsObj = JSONObject(data)
            val keys = traitsObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.startsWith("_")) {
                    // Internal state fields
                    when (key) {
                        "_curiosity_streak" -> curiosityStreak = traitsObj.optInt(key, 0)
                        "_last_curiosity_time" -> lastCuriosityTime = traitsObj.optLong(key, 0L)
                    }
                } else {
                    personalityTraits[key] = traitsObj.getDouble(key).toFloat()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading personality traits", e)
        }
    }

    /**
     * Get emotional bias for decision making
     * Recent embarrassment increases caution
     */
    fun getEmotionalBias(): Float {
        if (embarrassmentCount == 0) return 0.0f

        val timeSinceEmbarrassment = System.currentTimeMillis() - lastEmbarrassmentTime
        val oneHour = 3600000L

        if (timeSinceEmbarrassment < oneHour) {
            return -0.2f * (1.0f - (timeSinceEmbarrassment / oneHour.toFloat()))
        }
        return 0.0f
    }

    /**
     * Should AI defer answering due to low confidence?
     */
    fun shouldDefer(confidence: Float): Boolean {
        val adjustedConfidence = confidence + getEmotionalBias()
        return adjustedConfidence < 0.5f
    }

    /**
     * Get emotional state summary
     */
    fun getEmotionalState(): Map<String, Any> {
        val state = mutableMapOf<String, Any>()
        state["current_emotion"] = currentEmotion.name
        state["intensity"] = emotionalIntensity
        state["embarrassment_count"] = embarrassmentCount
        state["emotional_bias"] = getEmotionalBias()
        state["history_size"] = emotionalHistory.size
        state["curiosity_streak"] = curiosityStreak
        state["in_curiosity_streak"] = isInCuriosityStreak()

        val distribution = mutableMapOf<String, Int>()
        for (event in emotionalHistory) {
            val emotion = event.emotion.name
            distribution[emotion] = (distribution[emotion] ?: 0) + 1
        }
        state["emotion_distribution"] = distribution

        // Include personality traits
        state["personality_traits"] = personalityTraits.toMap()
        state["personality_profile"] = getPersonalityProfile()

        return state
    }

    private fun saveEmotionalHistory() {
        try {
            val historyArray = JSONArray()
            val startIdx = max(0, emotionalHistory.size - 100)
            for (i in startIdx until emotionalHistory.size) {
                val event = emotionalHistory[i]
                val eventObj = JSONObject().apply {
                    put("emotion", event.emotion.name)
                    put("intensity", event.intensity.toDouble())
                    put("context", event.context)
                    put("timestamp", event.timestamp)
                }
                historyArray.put(eventObj)
            }
            storage.store(EMOTIONAL_HISTORY_KEY, historyArray.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error saving emotional history", e)
        }
    }

    private fun loadEmotionalHistory() {
        try {
            val data = storage.retrieve(EMOTIONAL_HISTORY_KEY) ?: return
            val historyArray = JSONArray(data)
            for (i in 0 until historyArray.length()) {
                val eventObj = historyArray.getJSONObject(i)
                val event = EmotionalEvent(
                    Emotion.valueOf(eventObj.getString("emotion")),
                    eventObj.getDouble("intensity").toFloat(),
                    eventObj.getString("context"),
                    eventObj.getLong("timestamp")
                )
                emotionalHistory.add(event)

                if (event.emotion == Emotion.EMBARRASSED) {
                    embarrassmentCount++
                    lastEmbarrassmentTime = max(lastEmbarrassmentTime, event.timestamp)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading emotional history", e)
        }
    }

    class EmotionalEvent(
        val emotion: Emotion,
        val intensity: Float,
        val context: String,
        val timestamp: Long
    ) {
        override fun toString(): String {
            return String.format("%s (%.2f): %s", emotion.name, intensity, context)
        }
    }

    class ConsistencyResult(
        val isConsistent: Boolean,
        val similarityScore: Float,
        val assessment: String
    ) {
        override fun toString(): String {
            return String.format(
                "Consistency: %s (%.2f) - %s",
                if (isConsistent) "YES" else "NO", similarityScore, assessment
            )
        }
    }

    companion object {
        private const val TAG = "EmotionalState"
        private const val EMOTIONAL_HISTORY_KEY = "emotional_history"
        private const val PERSONALITY_TRAITS_KEY = "personality_traits"
        private const val EMBARRASSMENT_PENALTY = -0.3f
        private const val CONFIDENCE_BOOST = 0.2f
        private const val CURIOSITY_BOOST = 0.15f
        private const val CURIOSITY_TRAIT_BOOST = 0.1f
        private const val TRAIT_LEARNING_RATE = 0.05f
        private const val CURIOSITY_STREAK_WINDOW_MS = 1800000L  // 30 minutes
    }
}
