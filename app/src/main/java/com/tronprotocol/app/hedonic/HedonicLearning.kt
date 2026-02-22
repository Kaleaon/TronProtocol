package com.tronprotocol.app.hedonic

import android.content.Context
import android.util.Log
import com.tronprotocol.app.security.SecureStorage
import org.json.JSONObject

/**
 * Hedonic learning: the body develops preferences based on actual positive
 * experience history.
 *
 * When a stimulus produces high effective hedonic signal AND the consent gate
 * is fully open AND the interaction concludes with elevated valence, the base
 * hedonic weight increases slightly. Frequently pleasured zones become more sensitive.
 *
 * Partner-specific learning: partners consistently producing positive experiences
 * build cumulative positive modifiers. The body becomes more responsive to trusted,
 * beloved partners over time.
 */
class HedonicLearning(context: Context) {

    private val storage = SecureStorage(context)

    /** Learned weight adjustments per body zone (accumulated over encounters). */
    private val learnedWeights = mutableMapOf<BodyZone, Float>()

    /** Partner-specific weight modifiers: partnerId → (zone → modifier). */
    private val partnerModifiers = mutableMapOf<String, MutableMap<BodyZone, Float>>()

    init {
        loadState()
    }

    /**
     * Get the learned weight adjustment for a zone.
     */
    fun getLearnedWeight(zone: BodyZone): Float =
        learnedWeights.getOrDefault(zone, 0.0f)

    /**
     * Get partner-specific modifier for a zone.
     * New or unrecognized partners start at 0.0 — no bonus, no penalty.
     */
    fun getPartnerModifier(zone: BodyZone, partnerId: String): Float =
        partnerModifiers[partnerId]?.getOrDefault(zone, 0.0f) ?: 0.0f

    /**
     * Update learned weight after an encounter.
     *
     * Positive outcome + open gate → increase sensitivity.
     * Negative outcome or closed gate → decrease sensitivity (protective).
     */
    fun updateWeight(zone: BodyZone, finalValence: Float, gateWasOpen: Boolean) {
        val current = learnedWeights.getOrDefault(zone, 0.0f)
        val delta = if (gateWasOpen && finalValence > POSITIVE_THRESHOLD) {
            POSITIVE_LEARNING_RATE
        } else if (!gateWasOpen || finalValence < NEGATIVE_THRESHOLD) {
            -NEGATIVE_LEARNING_RATE
        } else {
            0.0f
        }
        learnedWeights[zone] = (current + delta).coerceIn(MIN_LEARNED_WEIGHT, MAX_LEARNED_WEIGHT)
        if (delta != 0.0f) {
            saveState()
            Log.d(TAG, "Zone ${zone.label} weight adjusted by $delta → ${learnedWeights[zone]}")
        }
    }

    /**
     * Update partner-specific modifier after an encounter.
     */
    fun updatePartnerModifier(
        zone: BodyZone,
        partnerId: String,
        finalValence: Float,
        gateWasOpen: Boolean
    ) {
        val partnerMap = partnerModifiers.getOrPut(partnerId) { mutableMapOf() }
        val current = partnerMap.getOrDefault(zone, 0.0f)
        val delta = if (gateWasOpen && finalValence > POSITIVE_THRESHOLD) {
            PARTNER_LEARNING_RATE
        } else if (finalValence < NEGATIVE_THRESHOLD) {
            -PARTNER_LEARNING_RATE
        } else {
            0.0f
        }
        partnerMap[zone] = (current + delta).coerceIn(MIN_PARTNER_MODIFIER, MAX_PARTNER_MODIFIER)
        if (delta != 0.0f) {
            saveState()
            Log.d(TAG, "Partner $partnerId zone ${zone.label} modifier adjusted by $delta")
        }
    }

    /** Get all learned data for a partner (for relationship diagnostics). */
    fun getPartnerProfile(partnerId: String): Map<BodyZone, Float> =
        partnerModifiers[partnerId]?.toMap() ?: emptyMap()

    // ---- Persistence -------------------------------------------------------

    private fun saveState() {
        try {
            val json = JSONObject()
            val weightsJson = JSONObject()
            learnedWeights.forEach { (zone, weight) ->
                weightsJson.put(zone.label, weight.toDouble())
            }
            json.put("learned_weights", weightsJson)

            val partnersJson = JSONObject()
            partnerModifiers.forEach { (partnerId, zoneMap) ->
                val zoneJson = JSONObject()
                zoneMap.forEach { (zone, mod) ->
                    zoneJson.put(zone.label, mod.toDouble())
                }
                partnersJson.put(partnerId, zoneJson)
            }
            json.put("partner_modifiers", partnersJson)
            storage.store(PERSIST_KEY, json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist hedonic learning state", e)
        }
    }

    private fun loadState() {
        try {
            val data = storage.retrieve(PERSIST_KEY) ?: return
            val json = JSONObject(data)

            if (json.has("learned_weights")) {
                val weightsJson = json.getJSONObject("learned_weights")
                weightsJson.keys().forEach { key ->
                    BodyZone.fromLabel(key)?.let { zone ->
                        learnedWeights[zone] = weightsJson.getDouble(key).toFloat()
                    }
                }
            }
            if (json.has("partner_modifiers")) {
                val partnersJson = json.getJSONObject("partner_modifiers")
                partnersJson.keys().forEach { partnerId ->
                    val zoneJson = partnersJson.getJSONObject(partnerId)
                    val zoneMap = mutableMapOf<BodyZone, Float>()
                    zoneJson.keys().forEach { key ->
                        BodyZone.fromLabel(key)?.let { zone ->
                            zoneMap[zone] = zoneJson.getDouble(key).toFloat()
                        }
                    }
                    partnerModifiers[partnerId] = zoneMap
                }
            }
            Log.d(TAG, "Loaded hedonic learning state: ${learnedWeights.size} zones, ${partnerModifiers.size} partners")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load hedonic learning state", e)
        }
    }

    companion object {
        private const val TAG = "HedonicLearning"
        private const val PERSIST_KEY = "hedonic_learning_state"
        private const val POSITIVE_THRESHOLD = 0.3f
        private const val NEGATIVE_THRESHOLD = -0.2f
        private const val POSITIVE_LEARNING_RATE = 0.015f
        private const val NEGATIVE_LEARNING_RATE = 0.01f
        private const val PARTNER_LEARNING_RATE = 0.01f
        private const val MIN_LEARNED_WEIGHT = -0.3f
        private const val MAX_LEARNED_WEIGHT = 0.3f
        private const val MIN_PARTNER_MODIFIER = -0.2f
        private const val MAX_PARTNER_MODIFIER = 0.3f
    }
}
