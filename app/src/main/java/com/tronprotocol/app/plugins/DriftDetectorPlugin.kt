package com.tronprotocol.app.plugins

import android.content.Context
import android.util.Log
import com.tronprotocol.app.drift.DriftDetector
import org.json.JSONObject

/**
 * Plugin exposing the DriftDetector for confabulation monitoring.
 *
 * Provides CMI (Continuous Memory Integrity) resonance scoring,
 * immutable commit log management, and drift pattern analysis.
 *
 * Commands:
 * - record:<content> — record ground truth interaction
 * - measure:<entryId>|<recalled>|<truth> — measure drift between recalled and truth
 * - verify_chain — verify immutable commit log integrity
 * - flagged — get entries flagged for partner review
 * - stats — get drift detection statistics
 */
class DriftDetectorPlugin : Plugin {
    override val id = "drift_detector"
    override val name = "Drift Detector"
    override val description = "CMI resonance scoring for confabulation detection and memory integrity"
    override var isEnabled = true

    private var detector: DriftDetector? = null

    override fun initialize(context: Context) {
        detector = DriftDetector(context)
        Log.d(TAG, "DriftDetectorPlugin initialized")
    }

    override fun execute(input: String): PluginResult {
        val startTime = System.currentTimeMillis()
        val drift = detector ?: return PluginResult.error("DriftDetector not initialized", elapsed(startTime))

        return try {
            when {
                input.startsWith("record:") -> {
                    val content = input.removePrefix("record:")
                    val hash = drift.recordGroundTruth(content)
                    PluginResult.success("Recorded ground truth: hash=${hash.take(16)}...", elapsed(startTime))
                }
                input.startsWith("measure:") -> {
                    val parts = input.removePrefix("measure:").split("|", limit = 3)
                    if (parts.size < 3) return PluginResult.error("Format: measure:entryId|recalled|truth", elapsed(startTime))
                    val score = drift.measureDrift(parts[0], parts[1], parts[2])
                    PluginResult.success(score.toJson().toString(), elapsed(startTime))
                }
                input == "verify_chain" -> {
                    val valid = drift.verifyCommitLogIntegrity()
                    PluginResult.success(if (valid) "Chain valid" else "CHAIN BROKEN — tampering detected", elapsed(startTime))
                }
                input == "flagged" -> {
                    val flagged = drift.getFlaggedEntries()
                    val json = JSONObject().apply {
                        put("count", flagged.size)
                        put("average_drift", drift.averageDrift.toDouble())
                    }
                    PluginResult.success(json.toString(), elapsed(startTime))
                }
                input == "stats" -> {
                    PluginResult.success(JSONObject(drift.getStats()).toString(), elapsed(startTime))
                }
                else -> PluginResult.error("Unknown command: $input", elapsed(startTime))
            }
        } catch (e: Exception) {
            Log.e(TAG, "DriftDetectorPlugin error", e)
            PluginResult.error("Drift detector error: ${e.message}", elapsed(startTime))
        }
    }

    fun getDetector(): DriftDetector? = detector

    override fun destroy() {
        detector = null
    }

    private fun elapsed(startTime: Long): Long = System.currentTimeMillis() - startTime

    companion object {
        private const val TAG = "DriftDetectorPlugin"
    }
}
