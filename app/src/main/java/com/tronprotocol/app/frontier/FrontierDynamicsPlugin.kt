package com.tronprotocol.app.frontier

import android.content.Context
import android.util.Log
import com.tronprotocol.app.plugins.Plugin
import com.tronprotocol.app.plugins.PluginResult
import com.tronprotocol.app.rag.RAGStore

/**
 * Plugin that exposes the Frontier Dynamics STLE framework to TronProtocol's
 * plugin system.
 *
 * Commands:
 * - `status`                     — Show STLE engine status and stats
 * - `train`                      — Train STLE from RAG store embeddings
 * - `score|<comma-separated floats>` — Score a raw embedding for accessibility
 * - `frontier`                   — Analyse frontier distribution of RAG chunks
 * - `update|<mu_x>|<helpful>`   — Bayesian update of a mu_x score
 * - `auroc`                      — Compute AUROC on RAG reliable vs unreliable
 * - `help`                       — Show available commands
 *
 * @see <a href="https://github.com/strangehospital/Frontier-Dynamics-Project">Frontier Dynamics</a>
 */
class FrontierDynamicsPlugin : Plugin {

    override val id: String = "frontier_dynamics"
    override val name: String = "Frontier Dynamics"
    override val description: String =
        "STLE uncertainty framework: models data accessibility through complementary " +
        "fuzzy sets (mu_x + mu_y = 1) for OOD detection, learning frontier identification, " +
        "and uncertainty quantification."
    override var isEnabled: Boolean = true

    private var context: Context? = null
    var manager: FrontierDynamicsManager? = null
    var ragStore: RAGStore? = null

    override fun initialize(context: Context) {
        this.context = context
        if (manager == null) {
            manager = FrontierDynamicsManager(context)
        }
        Log.d(TAG, "FrontierDynamicsPlugin initialized")
    }

    override fun execute(input: String): PluginResult {
        val startTime = System.currentTimeMillis()

        return try {
            val parts = input.trim().split("|", limit = 3)
            val command = parts[0].trim().lowercase()

            val result = when (command) {
                "status" -> handleStatus()
                "train" -> handleTrain()
                "score" -> handleScore(parts.getOrNull(1))
                "frontier" -> handleFrontier()
                "update" -> handleUpdate(parts.getOrNull(1), parts.getOrNull(2))
                "auroc" -> handleAUROC()
                "help" -> handleHelp()
                else -> "Unknown command: $command. Use 'help' for available commands."
            }

            PluginResult.success(result, System.currentTimeMillis() - startTime)
        } catch (e: Exception) {
            Log.e(TAG, "Plugin execution error", e)
            PluginResult.error(e.message, System.currentTimeMillis() - startTime)
        }
    }

    override fun destroy() {
        manager = null
        ragStore = null
    }

    // ========================================================================
    // Command handlers
    // ========================================================================

    private fun handleStatus(): String {
        val mgr = manager ?: return "FrontierDynamicsManager not initialized"
        val stats = mgr.getStats()

        return buildString {
            appendLine("[Frontier Dynamics — STLE Status]")
            appendLine("  Ready: ${stats["is_ready"]}")
            appendLine("  Total inferences: ${stats["total_inferences"]}")
            appendLine("  Total trainings: ${stats["total_trainings"]}")
            if (stats["is_ready"] == true) {
                appendLine("  Input dimensions: ${stats["input_dim"]}")
                appendLine("  Classes: ${stats["num_classes"]}")
                appendLine("  Training samples: ${stats["training_size"]}")
            }
            appendLine("  Cache size: ${stats["cache_size"]}")

            val ts = stats["last_training_timestamp"] as? Long ?: 0
            if (ts > 0) {
                val ago = (System.currentTimeMillis() - ts) / 1000
                appendLine("  Last trained: ${ago}s ago")
            }
        }
    }

    private fun handleTrain(): String {
        val mgr = manager ?: return "FrontierDynamicsManager not initialized"
        val store = ragStore ?: return "RAG store not available"

        mgr.trainFromRAGStore(store)
        return if (mgr.isReady) {
            val stats = mgr.getStats()
            "STLE trained successfully on ${stats["training_size"]} samples (dim=${stats["input_dim"]})"
        } else {
            "Training skipped: insufficient embedded samples in RAG store (need >=10)"
        }
    }

    private fun handleScore(embeddingStr: String?): String {
        val mgr = manager ?: return "FrontierDynamicsManager not initialized"
        if (!mgr.isReady) return "STLE engine not trained. Run 'train' first."

        if (embeddingStr.isNullOrBlank()) {
            return "Usage: score|<comma-separated float values>"
        }

        val embedding = try {
            embeddingStr.split(",").map { it.trim().toFloat() }.toFloatArray()
        } catch (e: NumberFormatException) {
            return "Invalid embedding format. Expected comma-separated floats."
        }

        val result = mgr.scoreEmbedding(embedding)
            ?: return "Scoring failed. Check embedding dimensions."

        return buildString {
            appendLine("[STLE Accessibility Score]")
            appendLine("  mu_x (accessibility):    ${"%.4f".format(result.muX)}")
            appendLine("  mu_y (inaccessibility):  ${"%.4f".format(result.muY)}")
            appendLine("  State: ${result.frontierState}")
            appendLine("  Epistemic uncertainty:   ${"%.4f".format(result.epistemicUncertainty)}")
            appendLine("  Aleatoric uncertainty:   ${"%.4f".format(result.aleatoricUncertainty)}")
            appendLine("  Complementarity error:   ${"%.10f".format(result.complementarityError)}")
        }
    }

    private fun handleFrontier(): String {
        val mgr = manager ?: return "FrontierDynamicsManager not initialized"
        if (!mgr.isReady) return "STLE engine not trained. Run 'train' first."

        val store = ragStore ?: return "RAG store not available"
        val chunks = store.getChunks()

        val results = chunks.mapNotNull { chunk ->
            chunk.embedding?.let { emb ->
                mgr.scoreEmbedding(emb)
            }
        }

        if (results.isEmpty()) return "No embeddings to analyse."

        var accessible = 0
        var frontier = 0
        var inaccessible = 0
        var sumMuX = 0f
        var sumEpistemic = 0f

        for (r in results) {
            sumMuX += r.muX
            sumEpistemic += r.epistemicUncertainty
            when (r.frontierState) {
                FrontierState.ACCESSIBLE -> accessible++
                FrontierState.FRONTIER -> frontier++
                FrontierState.INACCESSIBLE -> inaccessible++
            }
        }

        val total = results.size

        return buildString {
            appendLine("[Frontier Distribution — ${total} chunks analysed]")
            appendLine("  Accessible   (mu_x > 0.8): $accessible (${"%.1f".format(accessible.toFloat() / total * 100)}%)")
            appendLine("  Frontier (0.2 <= mu_x <= 0.8): $frontier (${"%.1f".format(frontier.toFloat() / total * 100)}%)")
            appendLine("  Inaccessible (mu_x < 0.2): $inaccessible (${"%.1f".format(inaccessible.toFloat() / total * 100)}%)")
            appendLine()
            appendLine("  Mean mu_x: ${"%.4f".format(sumMuX / total)}")
            appendLine("  Mean epistemic: ${"%.4f".format(sumEpistemic / total)}")

            if (frontier > 0) {
                appendLine()
                appendLine("  -> $frontier chunks in learning frontier (active learning candidates)")
            }
            if (inaccessible > 0) {
                appendLine("  -> $inaccessible chunks flagged as out-of-distribution")
            }
        }
    }

    private fun handleUpdate(muXStr: String?, helpfulStr: String?): String {
        val mgr = manager ?: return "FrontierDynamicsManager not initialized"

        if (muXStr.isNullOrBlank() || helpfulStr.isNullOrBlank()) {
            return "Usage: update|<mu_x>|<true/false>"
        }

        val muX = muXStr.trim().toFloatOrNull()
            ?: return "Invalid mu_x value: $muXStr"
        val helpful = helpfulStr.trim().lowercase() == "true"

        val updated = mgr.updateAccessibility(muX, helpful)
        val muYUpdated = 1.0f - updated

        return buildString {
            appendLine("[Bayesian Update]")
            appendLine("  Before: mu_x=${"%.4f".format(muX)}, mu_y=${"%.4f".format(1 - muX)}")
            appendLine("  Evidence: ${if (helpful) "positive (helpful)" else "negative (unhelpful)"}")
            appendLine("  After:  mu_x=${"%.4f".format(updated)}, mu_y=${"%.4f".format(muYUpdated)}")
            appendLine("  Delta:  ${"%.4f".format(updated - muX)}")
            appendLine("  Complementarity: |mu_x + mu_y - 1| = ${"%.10f".format(kotlin.math.abs(updated + muYUpdated - 1.0f))}")
        }
    }

    private fun handleAUROC(): String {
        val mgr = manager ?: return "FrontierDynamicsManager not initialized"
        if (!mgr.isReady) return "STLE engine not trained. Run 'train' first."

        val store = ragStore ?: return "RAG store not available"
        val chunks = store.getChunks()

        val idScores = mutableListOf<Float>()
        val oodScores = mutableListOf<Float>()

        for (chunk in chunks) {
            val emb = chunk.embedding ?: continue
            val result = mgr.scoreEmbedding(emb) ?: continue

            if (chunk.qValue >= 0.5f) {
                idScores.add(result.muX)
            } else {
                oodScores.add(result.muX)
            }
        }

        if (idScores.isEmpty() || oodScores.isEmpty()) {
            return "Insufficient data for AUROC: need both high-Q and low-Q chunks. " +
                    "High-Q: ${idScores.size}, Low-Q: ${oodScores.size}"
        }

        val auroc = STLEEngine.computeAUROCStatic(idScores.toFloatArray(), oodScores.toFloatArray())

        return buildString {
            appendLine("[OOD Detection — AUROC]")
            appendLine("  In-distribution (high Q): ${idScores.size} chunks, mean mu_x=${"%.4f".format(idScores.average())}")
            appendLine("  Out-of-distribution (low Q): ${oodScores.size} chunks, mean mu_x=${"%.4f".format(oodScores.average())}")
            appendLine("  AUROC: ${"%.4f".format(auroc)}")
            appendLine("  ${if (auroc > 0.75f) "GOOD" else if (auroc > 0.6f) "MODERATE" else "NEEDS IMPROVEMENT"} OOD detection")
        }
    }

    private fun handleHelp(): String = buildString {
        appendLine("[Frontier Dynamics — STLE Commands]")
        appendLine("  status                         — Engine status and stats")
        appendLine("  train                          — Train STLE from RAG embeddings")
        appendLine("  score|<floats>                 — Score embedding accessibility")
        appendLine("  frontier                       — Analyse chunk frontier distribution")
        appendLine("  update|<mu_x>|<true/false>     — Bayesian update with evidence")
        appendLine("  auroc                          — OOD detection AUROC metric")
        appendLine("  help                           — This message")
    }

    companion object {
        private const val TAG = "FrontierDynamicsPlugin"
    }
}
