package com.tronprotocol.app.plugins

import android.content.Context
import android.util.Log
import com.tronprotocol.app.aimodel.PixelTpuTrainingManager
import com.tronprotocol.app.aimodel.TakensEmbeddingTransformer
import com.tronprotocol.app.rag.RAGStore

/**
 * Takens Training Plugin — train micro Takens Embedding Transformer models
 * on-device using Pixel 10 TPU (Tensor G5 Edge TPU via NNAPI).
 *
 * Ported from https://github.com/KevinHaylett/takens-embedding-transformer
 * The architecture replaces attention with delay-coordinate phase-space
 * reconstruction from Takens' theorem, enabling compact attention-free
 * sequence models suitable for on-device training.
 *
 * Commands:
 *   status                     - Show TPU capability and model state
 *   prepare|<category>         - Build vocabulary and training data from RAG
 *   train|<epochs>             - Train model (default 10 epochs)
 *   generate|<prompt>          - Generate text from trained model
 *   evaluate                   - Evaluate model perplexity
 *   metrics                    - Show training metrics
 *   config                     - Show model configuration
 *
 * @see PixelTpuTrainingManager
 * @see TakensEmbeddingTransformer
 */
class TakensTrainingPlugin : Plugin {

    private var trainingManager: PixelTpuTrainingManager? = null
    private var ragStore: RAGStore? = null
    private var preparedData: com.tronprotocol.app.aimodel.TrainingData? = null

    override val id: String = ID
    override val name: String = "Takens TPU Training"
    override val description: String =
        "Train micro Takens Embedding Transformer on Pixel 10 TPU. " +
                "Attention-free architecture using delay-coordinate phase-space " +
                "reconstruction (Takens' theorem). " +
                "Commands: status, prepare|category, train|epochs, generate|prompt, " +
                "evaluate, metrics, config"
    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()

        if (input.isBlank()) {
            return PluginResult.error(
                "No command. Use: status, prepare|category, train|epochs, " +
                        "generate|prompt, evaluate, metrics, config",
                elapsed(start)
            )
        }

        val parts = input.split("\\|".toRegex(), 2)
        val command = parts[0].trim().lowercase()

        return try {
            when (command) {
                "status" -> executeStatus(start)
                "prepare" -> {
                    val category = parts.getOrNull(1)?.trim() ?: ""
                    executePrepare(category, start)
                }
                "train" -> {
                    val epochs = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 10
                    executeTrain(epochs, start)
                }
                "generate" -> {
                    val prompt = parts.getOrNull(1)?.trim()
                    if (prompt.isNullOrBlank()) {
                        PluginResult.error("Usage: generate|<prompt text>", elapsed(start))
                    } else {
                        executeGenerate(prompt, start)
                    }
                }
                "evaluate" -> executeEvaluate(start)
                "metrics" -> executeMetrics(start)
                "config" -> executeConfig(start)
                else -> PluginResult.error(
                    "Unknown command: $command. Use: status, prepare|category, " +
                            "train|epochs, generate|prompt, evaluate, metrics, config",
                    elapsed(start)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command '$command' failed: ${e.message}", e)
            PluginResult.error("Command failed: ${e.message}", elapsed(start))
        }
    }

    override fun initialize(context: Context) {
        try {
            trainingManager = PixelTpuTrainingManager(context)
            ragStore = RAGStore(context, "takens_training")

            // Try to load a previously trained model
            val loaded = trainingManager?.loadModel() ?: false
            Log.d(TAG, "TakensTrainingPlugin initialized. " +
                    "TPU: ${trainingManager?.getTpuCapability()?.tpuGeneration}, " +
                    "model_loaded=$loaded")
        } catch (e: Exception) {
            Log.w(TAG, "TakensTrainingPlugin init: ${e.message}")
        }
    }

    override fun destroy() {
        trainingManager?.shutdown()
        trainingManager = null
        ragStore = null
        preparedData = null
    }

    // ---- Command Implementations ----

    private fun executeStatus(start: Long): PluginResult {
        val manager = trainingManager
            ?: return PluginResult.error("Training manager not initialized", elapsed(start))

        val cap = manager.getTpuCapability()
        val sb = StringBuilder().apply {
            append("=== Takens Embedding Transformer — TPU Training ===\n")
            append("Architecture: Attention-free delay-coordinate phase-space model\n")
            append("Reference: github.com/KevinHaylett/takens-embedding-transformer\n\n")

            append("--- Hardware ---\n")
            append("Device: ${cap.deviceModel}\n")
            append("SoC: ${cap.socModel.ifEmpty { "Unknown" }}\n")
            append("TPU: ${cap.tpuGeneration}")
            if (cap.tpuTops > 0) append(" (~${cap.tpuTops} TOPS)")
            append("\n")
            append("NNAPI: ${if (cap.hasNnapi) "Available" else "Not available"}\n")
            append("RAM: ${cap.availableRamMb}MB / ${cap.totalRamMb}MB\n")
            append("Can Train: ${cap.canTrain}\n")
            append("Assessment: ${cap.reason}\n\n")

            append("--- Training State ---\n")
            append("State: ${manager.getTrainingState()}\n")
            append("Model Loaded: ${manager.isModelLoaded()}\n")

            if (manager.isModelLoaded()) {
                val config = manager.getConfig()
                val metrics = manager.getMetrics()
                append("Vocab Size: ${manager.getVocabSize()}\n")
                if (config != null) {
                    append("Parameters: ${estimateParamCount(config)}\n")
                    append("Config: embed=${config.embedDim}, hidden=${config.hiddenDim}, " +
                            "layers=${config.numLayers}, delays=${config.delays}\n")
                }
                if (metrics.epochsTrained > 0) {
                    append("Epochs Trained: ${metrics.epochsTrained}\n")
                    append("Final Loss: ${"%.4f".format(metrics.finalLoss)}\n")
                    append("Final Perplexity: ${"%.2f".format(metrics.finalPerplexity)}\n")
                }
            }

            val data = preparedData
            if (data != null) {
                append("\n--- Prepared Data ---\n")
                append("Sequences: ${data.sequences.size}\n")
                append("Vocab Size: ${data.vocabulary.size}\n")
                append("Corpus Size: ${data.corpusSize} chars\n")
            }

            append("\n--- Recommended Batch Size ---\n")
            append("Batch: ${cap.recommendedBatchSize} (seq_len=${cap.recommendedSeqLen()})")
        }

        return PluginResult.success(sb.toString(), elapsed(start))
    }

    private fun executePrepare(category: String, start: Long): PluginResult {
        val manager = trainingManager
            ?: return PluginResult.error("Training manager not initialized", elapsed(start))
        val rag = ragStore
            ?: return PluginResult.error("RAG store not initialized", elapsed(start))

        val data = manager.prepareTrainingData(rag, category)
        preparedData = data

        if (data.sequences.isEmpty()) {
            return PluginResult.error(
                "No training data found in RAG store" +
                        (if (category.isNotEmpty()) " for category '$category'" else "") +
                        ". Add knowledge to RAG first.",
                elapsed(start)
            )
        }

        val sb = StringBuilder().apply {
            append("=== Training Data Prepared ===\n")
            append("Category: ${category.ifEmpty { "(all)" }}\n")
            append("Sequences: ${data.sequences.size}\n")
            append("Vocabulary Size: ${data.vocabulary.size}\n")
            append("Corpus Size: ${data.corpusSize} characters\n")
            append("Avg Sequence Length: ${"%.1f".format(data.sequences.map { it.size }.average())}\n\n")
            append("Ready to train. Use: train|<epochs> (default: 10)")
        }

        return PluginResult.success(sb.toString(), elapsed(start))
    }

    private fun executeTrain(epochs: Int, start: Long): PluginResult {
        val manager = trainingManager
            ?: return PluginResult.error("Training manager not initialized", elapsed(start))

        val data = preparedData
        if (data == null || data.sequences.isEmpty()) {
            return PluginResult.error(
                "No training data prepared. Use: prepare|<category> first",
                elapsed(start)
            )
        }

        val cap = manager.getTpuCapability()
        if (!cap.canTrain) {
            return PluginResult.error(
                "Cannot train on this device: ${cap.reason}",
                elapsed(start)
            )
        }

        val metrics = manager.train(data, epochs = epochs)

        val sb = StringBuilder().apply {
            append("=== Training Complete ===\n")
            append("Architecture: Takens Embedding Transformer (attention-free)\n")
            append("TPU: ${metrics.tpuGeneration}\n")
            append("Config: ${metrics.configDescription}\n")
            append("Parameters: ${metrics.totalParams}\n")
            append("Vocab Size: ${metrics.vocabSize}\n")
            append("Sequences: ${metrics.sequenceCount}\n\n")

            append("--- Results ---\n")
            append("Epochs Trained: ${metrics.epochsTrained}\n")
            append("Final Loss: ${"%.4f".format(metrics.finalLoss)}\n")
            append("Final Perplexity: ${"%.2f".format(metrics.finalPerplexity)}\n")
            append("Training Time: ${metrics.trainingTimeMs}ms\n")

            if (metrics.epochLosses.size > 1) {
                append("\n--- Loss Curve ---\n")
                for ((i, loss) in metrics.epochLosses.withIndex()) {
                    val ppl = metrics.epochPerplexities[i]
                    append("  Epoch ${i + 1}: loss=${"%.4f".format(loss)}, ppl=${"%.2f".format(ppl)}\n")
                }
            }

            if (metrics.error != null) {
                append("\nWarning: ${metrics.error}\n")
            }

            append("\nUse: generate|<prompt> to test the model")
        }

        return PluginResult.success(sb.toString(), elapsed(start))
    }

    private fun executeGenerate(prompt: String, start: Long): PluginResult {
        val manager = trainingManager
            ?: return PluginResult.error("Training manager not initialized", elapsed(start))

        if (!manager.isModelLoaded()) {
            return PluginResult.error(
                "No model loaded. Use: prepare|category then train|epochs",
                elapsed(start)
            )
        }

        val result = manager.generate(prompt, maxTokens = 128, temperature = 0.7f)

        return if (result.success) {
            val output = "${result.text}\n\n" +
                    "[${result.tokensGenerated} tokens, ${result.latencyMs}ms, " +
                    "${"%.1f".format(result.tokensPerSecond)} tok/s]"
            PluginResult.success(output, elapsed(start))
        } else {
            PluginResult.error("Generation failed: ${result.error}", elapsed(start))
        }
    }

    private fun executeEvaluate(start: Long): PluginResult {
        val manager = trainingManager
            ?: return PluginResult.error("Training manager not initialized", elapsed(start))

        if (!manager.isModelLoaded()) {
            return PluginResult.error("No model loaded", elapsed(start))
        }

        val data = preparedData
        if (data == null || data.sequences.isEmpty()) {
            return PluginResult.error("No evaluation data. Use: prepare|category first", elapsed(start))
        }

        // Use last 20% of sequences as test set
        val testStart = (data.sequences.size * 0.8).toInt()
        val testSeqs = data.sequences.subList(testStart, data.sequences.size)

        val perplexity = manager.evaluate(testSeqs)

        val sb = StringBuilder().apply {
            append("=== Evaluation ===\n")
            append("Test Sequences: ${testSeqs.size}\n")
            append("Perplexity: ${"%.2f".format(perplexity)}\n")
            append("Assessment: ")
            append(when {
                perplexity.isNaN() -> "Unable to evaluate"
                perplexity < 10 -> "Excellent — strong pattern capture"
                perplexity < 50 -> "Good — meaningful structure learned"
                perplexity < 200 -> "Moderate — basic patterns captured"
                else -> "High — model needs more training data or epochs"
            })
        }

        return PluginResult.success(sb.toString(), elapsed(start))
    }

    private fun executeMetrics(start: Long): PluginResult {
        val manager = trainingManager
            ?: return PluginResult.error("Training manager not initialized", elapsed(start))

        val metrics = manager.getMetrics()
        if (metrics.epochsTrained == 0) {
            return PluginResult.error("No training metrics. Train a model first.", elapsed(start))
        }

        val sb = StringBuilder().apply {
            append("=== Training Metrics ===\n")
            append("Architecture: Takens Embedding Transformer\n")
            append("TPU: ${metrics.tpuGeneration}\n")
            append("Config: ${metrics.configDescription}\n")
            append("Parameters: ${metrics.totalParams}\n")
            append("Vocab Size: ${metrics.vocabSize}\n")
            append("Training Sequences: ${metrics.sequenceCount}\n")
            append("Epochs Trained: ${metrics.epochsTrained}\n")
            append("Final Loss: ${"%.4f".format(metrics.finalLoss)}\n")
            append("Final Perplexity: ${"%.2f".format(metrics.finalPerplexity)}\n")
            append("Training Time: ${metrics.trainingTimeMs}ms\n")
            if (metrics.error != null) {
                append("Error: ${metrics.error}\n")
            }
        }

        return PluginResult.success(sb.toString(), elapsed(start))
    }

    private fun executeConfig(start: Long): PluginResult {
        val manager = trainingManager
            ?: return PluginResult.error("Training manager not initialized", elapsed(start))

        val cap = manager.getTpuCapability()

        // Show micro config that would be used
        val exampleConfig = TakensEmbeddingTransformer.microConfig(256)

        val sb = StringBuilder().apply {
            append("=== Takens Model Configuration ===\n\n")
            append("--- Micro Config (Pixel TPU optimized) ---\n")
            append("Vocab Size: dynamic (from training data)\n")
            append("Embedding Dim: ${exampleConfig.embedDim}\n")
            append("Hidden Dim: ${exampleConfig.hiddenDim}\n")
            append("Num Layers: ${exampleConfig.numLayers}\n")
            append("Delays: ${exampleConfig.delays}\n")
            append("  (Exponential spacing captures multi-scale temporal structure)\n")
            append("Max Seq Len: ${exampleConfig.maxSeqLen}\n")
            append("Learning Rate: ${exampleConfig.learningRate}\n")
            append("Weight Decay: ${exampleConfig.weightDecay}\n\n")

            append("--- Architecture ---\n")
            append("Token Embedding → Takens Delay Embedding → [TBTLayer x N] → LayerNorm → Output\n\n")
            append("Key: NO attention mechanism. Instead uses Takens' theorem to\n")
            append("reconstruct semantic phase space from delay coordinates:\n")
            append("  [e(t), e(t-1), e(t-2), e(t-4), e(t-8)]\n")
            append("This geometric reconstruction captures sequential dependencies\n")
            append("without O(n²) attention cost.\n\n")

            append("--- Hardware Tuning ---\n")
            append("Recommended Batch: ${cap.recommendedBatchSize}\n")
            append("Recommended Seq Len: ${cap.recommendedSeqLen()}\n")
            append("TPU: ${cap.tpuGeneration}\n")
        }

        return PluginResult.success(sb.toString(), elapsed(start))
    }

    private fun estimateParamCount(config: com.tronprotocol.app.aimodel.TakensConfig): String {
        val transformer = TakensEmbeddingTransformer(config)
        val count = transformer.parameterCount()
        return when {
            count > 1_000_000 -> "${"%.1f".format(count / 1_000_000.0)}M"
            count > 1_000 -> "${"%.1f".format(count / 1_000.0)}K"
            else -> "$count"
        }
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    companion object {
        private const val TAG = "TakensTrainingPlugin"
        const val ID = "takens_training"
    }
}
