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
            modelHubStatusText.text = getString(R.string.models_no_model_selected)
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
            showToast(getString(R.string.models_toast_catalog_empty))
            return
        }

        val items = catalog.map { "${it.name} (${it.sizeMb} MB) — ${it.description}" }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.models_dialog_catalog_title, catalog.size))
            .setItems(items) { _, which -> startModelDownload(catalog[which]) }
            .setNegativeButton(R.string.common_close, null)
            .show()
    }

    private fun showLocalModels() {
        val models = modelRepository.getImportedModels()
        if (models.isEmpty()) {
            showToast(getString(R.string.models_toast_local_empty))
            return
        }

        val items = models.map { model ->
            val selected = if (model.id == modelRepository.getSelectedModelId()) " [ACTIVE]" else ""
            "${model.name}$selected (${model.quantization})"
        }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.models_dialog_my_models_title, models.size))
            .setItems(items) { _, which -> selectAndLoadModel(models[which]) }
            .setNegativeButton(R.string.common_close, null)
            .show()
    }

    private fun showRecommendation() {
        val capability = llmManager.assessDevice()
        val message = getString(
            R.string.models_recommendation_message,
            capability.recommendedModel,
            capability.cpuArch,
            capability.totalRamMb,
            capability.availableRamMb,
            capability.maxModelSizeMb,
            if (capability.supportsArm64) getString(R.string.common_yes) else getString(R.string.common_no),
            if (capability.supportsFp16) getString(R.string.common_yes) else getString(R.string.common_no),
            if (capability.hasGpu) getString(R.string.common_yes) else getString(R.string.common_no),
            capability.recommendedThreads,
            capability.reason
        )

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.models_dialog_device_recommendation_title)
            .setMessage(message)
            .setPositiveButton(R.string.common_ok, null)
            .show()
    }

    private fun showModelStatus() {
        val stats = llmManager.getStats()
        val message = if (stats.isEmpty()) {
            getString(R.string.models_no_model_loaded)
        } else {
            stats.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.models_dialog_llm_status_title)
            .setMessage(message)
            .setPositiveButton(R.string.common_ok, null)
            .show()
    }

    private fun startModelDownload(entry: ModelCatalog.CatalogEntry) {
        modelDownloadProgressContainer.visibility = View.VISIBLE
        modelDownloadStatusText.text = getString(R.string.models_download_starting, entry.name)
        modelDownloadProgressBar.progress = 0
        modelDownloadProgressBar.isIndeterminate = false
        showToast(getString(R.string.models_toast_downloading, entry.name, entry.sizeMb))

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
                        modelDownloadStatusText.text = getString(R.string.models_download_extracting, entry.name)
                        modelDownloadProgressBar.isIndeterminate = true
                    }
                    ModelDownloadManager.DownloadState.COMPLETED -> {
                        modelDownloadProgressContainer.visibility = View.GONE
                        modelDownloadProgressBar.isIndeterminate = false
                        showToast(getString(R.string.models_toast_download_complete, entry.name))
                        refreshModelHubCard()
                    }
                    ModelDownloadManager.DownloadState.ERROR -> {
                        modelDownloadProgressContainer.visibility = View.GONE
                        modelDownloadProgressBar.isIndeterminate = false
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.models_toast_download_failed, progress.errorMessage ?: getString(R.string.common_unknown_error)),
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
            showToast(getString(R.string.models_toast_download_already_in_progress, entry.name))
        }
    }

    private fun selectAndLoadModel(model: ModelRepository.ImportedModelEntry) {
        modelRepository.setSelectedModelId(model.id)
        refreshModelHubCard()
        showToast(getString(R.string.models_toast_selected, model.name))

        if (OnDeviceLLMManager.isNativeAvailable()) {
            llmSetupExecutor.execute {
                try {
                    val modelDir = java.io.File(model.directory)
                    val config = llmManager.createConfigFromDirectory(modelDir)
                    val loaded = llmManager.loadModel(config)
                    requireActivity().runOnUiThread {
                        if (loaded) {
                            showToast(getString(R.string.models_toast_loaded, model.name, config.backendName))
                        } else {
                            showToast(getString(R.string.models_toast_selected_failed_to_load, model.name))
                        }
                    }
                } catch (e: Exception) {
                    requireActivity().runOnUiThread {
                        showToast(getString(R.string.models_toast_selected_error_loading, model.name, e.message ?: getString(R.string.common_unknown_error)))
                    }
                }
            }
        }
    }

    private fun runModelGeneration() {
        val prompt = modelPromptInput.text?.toString()?.trim().orEmpty()
        if (prompt.isBlank()) {
            showToast(getString(R.string.models_toast_prompt_required))
            return
        }

        val selectedModel = modelRepository.getSelectedModel()
        if (selectedModel == null) {
            showToast(getString(R.string.models_toast_select_model_first))
            return
        }

        btnRunModel.isEnabled = false
        btnRunModel.text = getString(R.string.models_generating)
        btnStopGeneration.visibility = View.VISIBLE
        modelRunStatusText.visibility = View.VISIBLE
        modelRunStatusText.text = getString(R.string.models_running_inference, selectedModel.name)
        modelOutputScrollView.visibility = View.GONE

        activeGenerationFuture = llmSetupExecutor.submit {
            if (!llmManager.isReady) {
                try {
                    val config = llmManager.createConfigFromDirectory(selectedModel.directory)
                    if (!llmManager.loadModel(config)) {
                        requireActivity().runOnUiThread {
                            resetGenerationUi()
                            modelRunStatusText.visibility = View.VISIBLE
                            modelRunStatusText.text = getString(R.string.models_status_failed_to_load)
                        }
                        return@submit
                    }
                } catch (e: Exception) {
                    requireActivity().runOnUiThread {
                        resetGenerationUi()
                        modelRunStatusText.visibility = View.VISIBLE
                        modelRunStatusText.text = getString(R.string.models_status_error_loading, e.message ?: getString(R.string.common_unknown_error))
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
                    modelRunStatusText.text = getString(R.string.models_status_generation_failed, result.error ?: getString(R.string.common_unknown_error))
                }
            }
        }
    }

    private fun cancelModelGeneration() {
        activeGenerationFuture?.cancel(true)
        activeGenerationFuture = null
        resetGenerationUi()
        modelRunStatusText.visibility = View.VISIBLE
        modelRunStatusText.text = getString(R.string.models_status_generation_cancelled)
    }

    private fun resetGenerationUi() {
        btnRunModel.isEnabled = true
        btnRunModel.text = getString(R.string.models_generate)
        btnStopGeneration.visibility = View.GONE
        activeGenerationFuture = null
    }

    private fun promptModelDownloadAndInit() {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val modelNameInput = EditText(requireContext()).apply {
            hint = getString(R.string.models_hint_name)
            setText(R.string.models_default_name)
        }
        val modelUrlInput = EditText(requireContext()).apply {
            hint = getString(R.string.models_hint_url)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        layout.addView(TextView(requireContext()).apply { text = getString(R.string.models_label_name) })
        layout.addView(modelNameInput)
        layout.addView(TextView(requireContext()).apply { text = getString(R.string.models_label_download_url) })
        layout.addView(modelUrlInput)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.models_dialog_download_initialize_title)
            .setView(layout)
            .setPositiveButton(R.string.common_start) { _, _ ->
                val modelName = modelNameInput.text?.toString()?.trim().orEmpty()
                val modelUrl = modelUrlInput.text?.toString()?.trim().orEmpty()
                if (modelName.isBlank() || modelUrl.isBlank()) {
                    showToast(getString(R.string.models_toast_provide_name_and_url))
                    return@setPositiveButton
                }
                val uri = android.net.Uri.parse(modelUrl)
                val scheme = uri.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") {
                    showToast(getString(R.string.models_toast_invalid_url_scheme))
                    return@setPositiveButton
                }

                showToast(getString(R.string.models_toast_downloading_package))
                llmSetupExecutor.execute {
                    val result = llmManager.downloadAndInitializeModel(modelName, modelUrl)
                    requireActivity().runOnUiThread {
                        if (activity?.isFinishing == true || activity?.isDestroyed == true) return@runOnUiThread
                        if (result.success) {
                            val sizeMb = result.downloadedBytes / (1024f * 1024f)
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.models_toast_model_ready, result.config?.modelName ?: getString(R.string.models_unknown_model), "%.1f".format(sizeMb)),
                                Toast.LENGTH_SHORT
                            ).show()
                            refreshModelHubCard()
                        } else {
                            showToast(getString(R.string.models_toast_setup_failed, result.error ?: getString(R.string.common_unknown_error)))
                        }
                    }
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        activeGenerationFuture?.cancel(true)
        llmSetupExecutor.shutdown()
    }
}
