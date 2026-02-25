package com.tronprotocol.app.ui

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.tronprotocol.app.R
import com.tronprotocol.app.llm.ModelCatalog
import com.tronprotocol.app.llm.ModelDownloadManager
import com.tronprotocol.app.llm.ModelRepository
import com.tronprotocol.app.llm.OnDeviceLLMManager
import java.util.concurrent.Executors
import java.util.concurrent.Future

class ModelHubFragment : Fragment() {

    // Views
    private lateinit var modelHubStatusText: TextView
    private lateinit var modelSelectedDetailsText: TextView
    private lateinit var modelDownloadProgressContainer: MaterialCardView
    private lateinit var modelDownloadStatusText: TextView
    private lateinit var modelDownloadProgressBar: ProgressBar
    private lateinit var modelRunCard: MaterialCardView
    private lateinit var modelPromptInput: TextInputEditText
    private lateinit var btnRunModel: MaterialButton
    private lateinit var btnStopGeneration: MaterialButton
    private lateinit var modelRunStatusText: TextView
    private lateinit var modelOutputScrollView: ScrollView
    private lateinit var modelOutputText: TextView
    private lateinit var btnModelBrowse: MaterialButton
    private lateinit var btnModelSelect: MaterialButton
    private lateinit var btnModelRecommend: MaterialButton
    private lateinit var btnModelStatus: MaterialButton
    private lateinit var btnDownloadInitModel: MaterialButton
    private lateinit var deviceCapText: TextView

    // Managers
    private lateinit var llmManager: OnDeviceLLMManager
    private lateinit var modelRepository: ModelRepository
    private lateinit var downloadManager: ModelDownloadManager
    private var modelCatalog: ModelCatalog = ModelCatalog

    // Background work
    private val llmSetupExecutor = Executors.newSingleThreadExecutor()
    private var activeGenerationFuture: Future<*>? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_models, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        initializeManagers()
        setupListeners()
        refreshModelHubCard()
        refreshDeviceCapabilities()
    }

    private fun bindViews(view: View) {
        modelHubStatusText = view.findViewById(R.id.modelHubStatusText)
        modelSelectedDetailsText = view.findViewById(R.id.modelSelectedDetailsText)
        modelDownloadProgressContainer = view.findViewById(R.id.modelDownloadProgressContainer)
        modelDownloadStatusText = view.findViewById(R.id.modelDownloadStatusText)
        modelDownloadProgressBar = view.findViewById(R.id.modelDownloadProgressBar)
        modelRunCard = view.findViewById(R.id.modelRunCard)
        modelPromptInput = view.findViewById(R.id.modelPromptInput)
        btnRunModel = view.findViewById(R.id.btnRunModel)
        btnStopGeneration = view.findViewById(R.id.btnStopGeneration)
        modelRunStatusText = view.findViewById(R.id.modelRunStatusText)
        modelOutputScrollView = view.findViewById(R.id.modelOutputScrollView)
        modelOutputText = view.findViewById(R.id.modelOutputText)
        btnModelBrowse = view.findViewById(R.id.btnModelBrowse)
        btnModelSelect = view.findViewById(R.id.btnModelSelect)
        btnModelRecommend = view.findViewById(R.id.btnModelRecommend)
        btnModelStatus = view.findViewById(R.id.btnModelStatus)
        btnDownloadInitModel = view.findViewById(R.id.btnDownloadInitModel)
        deviceCapText = view.findViewById(R.id.deviceCapText)
    }

    private fun initializeManagers() {
        llmManager = OnDeviceLLMManager(requireContext())
        modelRepository = ModelRepository(requireContext())
        downloadManager = ModelDownloadManager(requireContext())
        modelCatalog = ModelCatalog
    }

    private fun setupListeners() {
        btnModelBrowse.setOnClickListener { showModelCatalog() }
        btnModelSelect.setOnClickListener { showLocalModels() }
        btnModelRecommend.setOnClickListener { showRecommendation() }
        btnModelStatus.setOnClickListener { showModelStatus() }
        btnDownloadInitModel.setOnClickListener { promptModelDownloadAndInit() }
        btnRunModel.setOnClickListener { runModelGeneration() }
        btnStopGeneration.setOnClickListener { cancelModelGeneration() }
    }

    fun refreshModelHubCard() {
        val selected = modelRepository.getSelectedModel()
        if (selected != null) {
            modelHubStatusText.text = selected.name
            modelSelectedDetailsText.visibility = View.VISIBLE
            modelSelectedDetailsText.text = buildString {
                append("${selected.quantization} | ${selected.parameterCount}")
                if (selected.diskUsageMb > 0) append(" | ${selected.diskUsageMb} MB")
            }
            modelRunCard.visibility = View.VISIBLE
        } else {
            modelHubStatusText.text = "No model selected"
            modelSelectedDetailsText.visibility = View.GONE
            modelRunCard.visibility = View.GONE
        }
    }

    fun refreshDeviceCapabilities() {
        val capability = llmManager.assessDevice()
        deviceCapText.text = buildString {
            append("RAM: ${capability.availableRamMb} MB available\n")
            append("Can run LLM: ${if (capability.canRunLLM) "Yes" else "No"}\n")
            append("Recommended: ${capability.recommendedModel}\n")
            append("MNN native: ${if (OnDeviceLLMManager.isNativeAvailable()) "Loaded" else "Not available"}")
        }
    }

    private fun showModelCatalog() {
        val catalog = modelCatalog.entries
        if (catalog.isEmpty()) {
            Toast.makeText(requireContext(), "No models available in catalog", Toast.LENGTH_SHORT).show()
            return
        }

        val items = catalog.map { "${it.name} (${it.sizeMb} MB) — ${it.description}" }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Model Catalog (${catalog.size})")
            .setItems(items) { _, which -> startModelDownload(catalog[which]) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showLocalModels() {
        val models = modelRepository.getImportedModels()
        if (models.isEmpty()) {
            Toast.makeText(requireContext(), "No local models found. Download one first.", Toast.LENGTH_SHORT).show()
            return
        }

        val items = models.map { model ->
            val selected = if (model.id == modelRepository.getSelectedModelId()) " [ACTIVE]" else ""
            "${model.name}$selected (${model.quantization})"
        }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("My Models (${models.size})")
            .setItems(items) { _, which -> selectAndLoadModel(models[which]) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showRecommendation() {
        val capability = llmManager.assessDevice()
        val message = buildString {
            append("Recommended model: ${capability.recommendedModel}\n\n")
            append("Device: ${capability.cpuArch}\n")
            append("Total RAM: ${capability.totalRamMb} MB\n")
            append("Available RAM: ${capability.availableRamMb} MB\n")
            append("Max model size: ${capability.maxModelSizeMb} MB\n")
            append("ARM64: ${if (capability.supportsArm64) "Yes" else "No"}\n")
            append("FP16: ${if (capability.supportsFp16) "Yes" else "No"}\n")
            append("GPU: ${if (capability.hasGpu) "Yes" else "No"}\n")
            append("Threads: ${capability.recommendedThreads}\n\n")
            append(capability.reason)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Device Recommendation")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showModelStatus() {
        val stats = llmManager.getStats()
        val message = if (stats.isEmpty()) {
            "No model loaded"
        } else {
            stats.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        }

        AlertDialog.Builder(requireContext())
            .setTitle("LLM Status")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun startModelDownload(entry: ModelCatalog.CatalogEntry) {
        modelDownloadProgressContainer.visibility = View.VISIBLE
        modelDownloadStatusText.text = "Starting download: ${entry.name}..."
        modelDownloadProgressBar.progress = 0
        modelDownloadProgressBar.isIndeterminate = false
        Toast.makeText(requireContext(), "Downloading ${entry.name} (${entry.sizeMb} MB)...", Toast.LENGTH_SHORT).show()

        val started = downloadManager.downloadModel(entry) { progress ->
            requireActivity().runOnUiThread {
                when (progress.state) {
                    ModelDownloadManager.DownloadState.DOWNLOADING -> {
                        modelDownloadProgressBar.progress = progress.progressPercent
                        val speedMb = progress.speedBytesPerSec / (1024f * 1024f)
                        val downloadedMb = progress.downloadedBytes / (1024f * 1024f)
                        val totalMb = progress.totalBytes / (1024f * 1024f)
                        modelDownloadStatusText.text = String.format(
                            "%.1f / %.1f MB (%.1f MB/s)", downloadedMb, totalMb, speedMb
                        )
                    }
                    ModelDownloadManager.DownloadState.EXTRACTING -> {
                        modelDownloadStatusText.text = "Extracting ${entry.name}..."
                        modelDownloadProgressBar.isIndeterminate = true
                    }
                    ModelDownloadManager.DownloadState.COMPLETED -> {
                        modelDownloadProgressContainer.visibility = View.GONE
                        modelDownloadProgressBar.isIndeterminate = false
                        Toast.makeText(requireContext(), "Download complete: ${entry.name}", Toast.LENGTH_SHORT).show()
                        refreshModelHubCard()
                    }
                    ModelDownloadManager.DownloadState.ERROR -> {
                        modelDownloadProgressContainer.visibility = View.GONE
                        modelDownloadProgressBar.isIndeterminate = false
                        Toast.makeText(
                            requireContext(),
                            "Download failed: ${progress.errorMessage ?: "Unknown error"}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    ModelDownloadManager.DownloadState.CANCELLED -> {
                        modelDownloadProgressContainer.visibility = View.GONE
                        modelDownloadProgressBar.isIndeterminate = false
                    }
                    else -> {}
                }
            }
        }

        if (!started) {
            modelDownloadProgressContainer.visibility = View.GONE
            Toast.makeText(requireContext(), "Download already in progress for ${entry.name}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun selectAndLoadModel(model: ModelRepository.ImportedModelEntry) {
        modelRepository.setSelectedModelId(model.id)
        refreshModelHubCard()
        Toast.makeText(requireContext(), "Selected: ${model.name}", Toast.LENGTH_SHORT).show()

        if (OnDeviceLLMManager.isNativeAvailable()) {
            llmSetupExecutor.execute {
                try {
                    val modelDir = java.io.File(model.directory)
                    val config = llmManager.createConfigFromDirectory(modelDir)
                    val loaded = llmManager.loadModel(config)
                    requireActivity().runOnUiThread {
                        if (loaded) {
                            Toast.makeText(requireContext(), "Loaded: ${model.name} (${config.backendName})", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(requireContext(), "Selected ${model.name} but failed to load model", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    requireActivity().runOnUiThread {
                        Toast.makeText(requireContext(), "Selected ${model.name} but error loading: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun runModelGeneration() {
        val prompt = modelPromptInput.text?.toString()?.trim().orEmpty()
        if (prompt.isBlank()) {
            Toast.makeText(requireContext(), "Enter a prompt before generating.", Toast.LENGTH_SHORT).show()
            return
        }

        val selectedModel = modelRepository.getSelectedModel()
        if (selectedModel == null) {
            Toast.makeText(requireContext(), "No model selected. Select a model first.", Toast.LENGTH_SHORT).show()
            return
        }

        btnRunModel.isEnabled = false
        btnRunModel.text = "Generating..."
        btnStopGeneration.visibility = View.VISIBLE
        modelRunStatusText.visibility = View.VISIBLE
        modelRunStatusText.text = "Running inference on ${selectedModel.name}..."
        modelOutputScrollView.visibility = View.GONE

        activeGenerationFuture = llmSetupExecutor.submit {
            if (!llmManager.isReady) {
                try {
                    val config = llmManager.createConfigFromDirectory(selectedModel.directory)
                    if (!llmManager.loadModel(config)) {
                        requireActivity().runOnUiThread {
                            resetGenerationUi()
                            modelRunStatusText.visibility = View.VISIBLE
                            modelRunStatusText.text = "Failed to load model. Try selecting it again."
                        }
                        return@submit
                    }
                } catch (e: Exception) {
                    requireActivity().runOnUiThread {
                        resetGenerationUi()
                        modelRunStatusText.visibility = View.VISIBLE
                        modelRunStatusText.text = "Error loading model: ${e.message}"
                    }
                    return@submit
                }
            }

            val result = llmManager.generate(prompt)
            requireActivity().runOnUiThread {
                if (activity?.isFinishing == true || activity?.isDestroyed == true) return@runOnUiThread
                resetGenerationUi()
                if (result.success && result.text != null) {
                    modelOutputScrollView.visibility = View.VISIBLE
                    modelOutputText.text = result.text
                    modelRunStatusText.visibility = View.VISIBLE
                    modelRunStatusText.text = "${result.tokensGenerated} tokens | ${result.latencyMs} ms | ${"%.1f".format(result.tokensPerSecond)} tok/s"
                } else {
                    modelRunStatusText.visibility = View.VISIBLE
                    modelRunStatusText.text = "Generation failed: ${result.error ?: "Unknown error"}"
                }
            }
        }
    }

    private fun cancelModelGeneration() {
        activeGenerationFuture?.cancel(true)
        activeGenerationFuture = null
        resetGenerationUi()
        modelRunStatusText.visibility = View.VISIBLE
        modelRunStatusText.text = "Generation cancelled."
    }

    private fun resetGenerationUi() {
        btnRunModel.isEnabled = true
        btnRunModel.text = "Generate"
        btnStopGeneration.visibility = View.GONE
        activeGenerationFuture = null
    }

    private fun promptModelDownloadAndInit() {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val modelNameInput = EditText(requireContext()).apply {
            hint = "Qwen2.5-1.5B-Instruct"
            setText("Qwen2.5-1.5B-Instruct")
        }
        val modelUrlInput = EditText(requireContext()).apply {
            hint = "https://example.com/model.zip"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        layout.addView(TextView(requireContext()).apply { text = "Model name:" })
        layout.addView(modelNameInput)
        layout.addView(TextView(requireContext()).apply { text = "Download URL:" })
        layout.addView(modelUrlInput)

        AlertDialog.Builder(requireContext())
            .setTitle("Download & Initialize LLM")
            .setView(layout)
            .setPositiveButton("Start") { _, _ ->
                val modelName = modelNameInput.text?.toString()?.trim().orEmpty()
                val modelUrl = modelUrlInput.text?.toString()?.trim().orEmpty()
                if (modelName.isBlank() || modelUrl.isBlank()) {
                    Toast.makeText(requireContext(), "Provide both model name and URL.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val uri = android.net.Uri.parse(modelUrl)
                val scheme = uri.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") {
                    Toast.makeText(requireContext(), "Invalid URL scheme: only http and https are allowed.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                Toast.makeText(requireContext(), "Downloading model package...", Toast.LENGTH_SHORT).show()
                llmSetupExecutor.execute {
                    val result = llmManager.downloadAndInitializeModel(modelName, modelUrl)
                    requireActivity().runOnUiThread {
                        if (activity?.isFinishing == true || activity?.isDestroyed == true) return@runOnUiThread
                        if (result.success) {
                            val sizeMb = result.downloadedBytes / (1024f * 1024f)
                            Toast.makeText(
                                requireContext(),
                                "Model ready: ${result.config?.modelName} (${"%.1f".format(sizeMb)} MB)",
                                Toast.LENGTH_SHORT
                            ).show()
                            refreshModelHubCard()
                        } else {
                            Toast.makeText(requireContext(), "Model setup failed: ${result.error}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        activeGenerationFuture?.cancel(true)
        llmSetupExecutor.shutdown()
    }
}
