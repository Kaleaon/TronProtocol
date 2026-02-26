package com.tronprotocol.app.ui

import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.tronprotocol.app.R
import com.tronprotocol.app.affect.AffectDimension
import com.tronprotocol.app.affect.AffectOrchestrator
import com.tronprotocol.app.affect.AffectState
import com.tronprotocol.app.avatar.AvatarModelCatalog
import com.tronprotocol.app.avatar.AvatarResourceManager
import com.tronprotocol.app.avatar.AvatarSessionManager
import com.tronprotocol.app.avatar.NnrRenderEngine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AvatarFragment : Fragment() {

    // Views
    private lateinit var avatarTextureView: TextureView
    private lateinit var avatarStatusIndicator: View
    private lateinit var avatarStatusText: TextView
    private lateinit var avatarFpsText: TextView
    private lateinit var avatarActiveModelText: TextView
    private lateinit var btnAvatarLoad: MaterialButton
    private lateinit var btnAvatarUnload: MaterialButton
    private lateinit var btnAvatarCustom: MaterialButton
    private lateinit var btnAvatarReset: MaterialButton
    private lateinit var avatarDownloadContainer: LinearLayout
    private lateinit var avatarDownloadStatusText: TextView
    private lateinit var avatarDownloadProgressBar: ProgressBar
    private lateinit var avatarDeviceAssessmentText: TextView
    private lateinit var affectIntensityText: TextView
    private lateinit var affectHedonicText: TextView
    private lateinit var affectDimensionBarsContainer: LinearLayout
    private lateinit var btnRefreshAffect: MaterialButton
    private lateinit var expressionOutputText: TextView
    private lateinit var motorNoiseText: TextView

    // Avatar state
    var avatarSurface: Surface? = null
    var avatarSessionManager: AvatarSessionManager? = null

    private val avatarExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_avatar, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        initializeAvatarSessionManager()
        setupAvatarTextureView()
        setupListeners()
        refreshAvatarDeviceAssessment()
    }

    private fun bindViews(view: View) {
        avatarTextureView = view.findViewById(R.id.avatarTextureView)
        avatarStatusIndicator = view.findViewById(R.id.avatarStatusIndicator)
        avatarStatusText = view.findViewById(R.id.avatarStatusText)
        avatarFpsText = view.findViewById(R.id.avatarFpsText)
        avatarActiveModelText = view.findViewById(R.id.avatarActiveModelText)
        btnAvatarLoad = view.findViewById(R.id.btnAvatarLoad)
        btnAvatarUnload = view.findViewById(R.id.btnAvatarUnload)
        btnAvatarCustom = view.findViewById(R.id.btnAvatarCustom)
        btnAvatarReset = view.findViewById(R.id.btnAvatarReset)
        avatarDownloadContainer = view.findViewById(R.id.avatarDownloadContainer)
        avatarDownloadStatusText = view.findViewById(R.id.avatarDownloadStatusText)
        avatarDownloadProgressBar = view.findViewById(R.id.avatarDownloadProgressBar)
        avatarDeviceAssessmentText = view.findViewById(R.id.avatarDeviceAssessmentText)
        affectIntensityText = view.findViewById(R.id.affectIntensityText)
        affectHedonicText = view.findViewById(R.id.affectHedonicText)
        affectDimensionBarsContainer = view.findViewById(R.id.affectDimensionBarsContainer)
        btnRefreshAffect = view.findViewById(R.id.btnRefreshAffect)
        expressionOutputText = view.findViewById(R.id.expressionOutputText)
        motorNoiseText = view.findViewById(R.id.motorNoiseText)
    }

    private fun initializeAvatarSessionManager() {
        avatarSessionManager = AvatarSessionManager(requireContext())
    }

    private fun setupAvatarTextureView() {
        avatarTextureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                avatarSurface = Surface(texture)
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                avatarSessionManager?.unloadAvatar()
                avatarSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
        }
    }

    private fun setupListeners() {
        btnAvatarLoad.setOnClickListener { showAvatarPresetDialog() }
        btnAvatarUnload.setOnClickListener { unloadAvatar() }
        btnAvatarCustom.setOnClickListener { showCustomAvatarsDialog() }
        btnAvatarReset.setOnClickListener {
            avatarSessionManager?.setCamera(0f, 0f)
            showToast("Camera reset")
        }
        btnRefreshAffect.setOnClickListener {
            // Refresh affect display requires an orchestrator to be passed externally
        }
    }

    private fun refreshAvatarDeviceAssessment() {
        val manager = avatarSessionManager ?: return
        val assessment = manager.assessDevice()
        avatarDeviceAssessmentText.text = buildString {
            append("NNR: ${if (assessment.isNnrAvailable) "Available" else "Not installed"}")
            append(" | A2BS: ${if (assessment.isA2bsAvailable) "Available" else "Not installed"}\n")
            append("RAM: ${assessment.availableRamMb}/${assessment.totalRamMb} MB")
            append(" | ${assessment.cpuArch}\n")
            append("Avatar capable: ${if (assessment.canRunAvatar) "Yes" else "No"}\n")
            if (assessment.recommendedPreset != null) {
                append("Recommended: ${assessment.recommendedPreset.name}")
            } else {
                append(assessment.reason)
            }
        }
    }

    private fun showAvatarPresetDialog() {
        val presets = AvatarModelCatalog.presets
        if (presets.isEmpty()) {
            showToast("No avatar presets available in catalog")
            return
        }
        val manager = avatarSessionManager ?: return

        val items = presets.map { preset ->
            val downloaded = preset.componentIds.values.all { manager.resourceManager.isComponentDownloaded(it) }
            val status = if (downloaded) "[Ready]" else "[Download required]"
            "$status ${preset.name} (${preset.description})"
        }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("Avatar Presets (${presets.size})")
            .setItems(items) { _, which -> handleAvatarPresetSelection(presets[which]) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun handleAvatarPresetSelection(preset: AvatarModelCatalog.AvatarPreset) {
        val manager = avatarSessionManager ?: return
        val surface = avatarSurface ?: run {
            showToast("Avatar viewport not ready. Try again.")
            return
        }

        val allDownloaded = preset.componentIds.values.all { manager.resourceManager.isComponentDownloaded(it) }
        if (!allDownloaded) {
            AlertDialog.Builder(requireContext())
                .setTitle("Download: ${preset.name}")
                .setMessage("This avatar preset needs to download required components. Continue?")
                .setPositiveButton("Download") { _, _ -> startAvatarPresetDownload(preset) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        loadAvatarPreset(preset.id)
    }

    private fun startAvatarPresetDownload(preset: AvatarModelCatalog.AvatarPreset) {
        val manager = avatarSessionManager ?: return
        avatarDownloadContainer.visibility = View.VISIBLE
        avatarDownloadStatusText.text = "Downloading ${preset.name}..."
        avatarDownloadProgressBar.progress = 0
        avatarDownloadProgressBar.isIndeterminate = true

        avatarExecutor.execute {
            val result = manager.downloadPreset(preset.id, object : AvatarResourceManager.DownloadListener {
                override fun onProgress(progress: AvatarResourceManager.DownloadProgress) {
                    requireActivity().runOnUiThread {
                        avatarDownloadProgressBar.isIndeterminate = false
                        avatarDownloadProgressBar.progress = progress.progressPercent.toInt()
                        avatarDownloadStatusText.text = "Downloading ${progress.componentId}... ${progress.progressPercent.toInt()}%"
                    }
                }

                override fun onComplete(componentId: String, modelDir: java.io.File) {
                    requireActivity().runOnUiThread {
                        avatarDownloadStatusText.text = "Downloaded: $componentId"
                    }
                }

                override fun onError(componentId: String, error: String) {
                    requireActivity().runOnUiThread {
                        avatarDownloadStatusText.text = "Error: $componentId — $error"
                    }
                }
            })

            requireActivity().runOnUiThread {
                avatarDownloadContainer.visibility = View.GONE
                if (result.success) {
                    showToast("Download complete. Loading avatar...")
                    loadAvatarPreset(preset.id)
                } else {
                    showToast("Download failed: ${result.message}")
                }
            }
        }
    }

    private fun loadAvatarPreset(presetId: String) {
        val manager = avatarSessionManager ?: return
        val surface = avatarSurface ?: return
        val width = avatarTextureView.width
        val height = avatarTextureView.height
        updateAvatarStatus("Loading...", R.color.service_status_degraded_background)

        avatarExecutor.execute {
            val result = manager.loadAvatar(presetId, surface, width, height)
            requireActivity().runOnUiThread {
                if (result.success) {
                    val config = manager.activeAvatar
                    avatarActiveModelText.text = config?.displayName ?: "Avatar loaded"
                    updateAvatarStatus("Ready", R.color.service_status_running_background)
                    showToast("Avatar loaded: ${config?.displayName}")
                    avatarExecutor.execute { manager.renderIdle() }
                } else {
                    updateAvatarStatus("Error", R.color.service_status_blocked_background)
                    showToast("Failed to load avatar: ${result.message}")
                }
            }
        }
    }

    private fun unloadAvatar() {
        avatarSessionManager?.unloadAvatar()
        avatarActiveModelText.text = getString(R.string.avatar_load_hint)
        updateAvatarStatus("No avatar loaded", R.color.service_status_deferred_background)
        avatarFpsText.text = ""
    }

    private fun updateAvatarStatus(status: String, colorRes: Int) {
        avatarStatusText.text = status
        avatarStatusIndicator.setBackgroundColor(ContextCompat.getColor(requireContext(), colorRes))
    }

    private fun showCustomAvatarsDialog() {
        val manager = avatarSessionManager ?: return
        val customAvatars = manager.listCustomAvatars()
        if (customAvatars.isEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle("Custom Avatars")
                .setMessage("No custom avatars imported yet.\n\nUse the MNN Avatar plugin to import custom avatar models with NNR rendering support.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val items = customAvatars.map {
            "${it.name} (${if (it.hasSkeleton) "With skeleton" else "Default skeleton"})"
        }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("Custom Avatars (${customAvatars.size})")
            .setItems(items) { _, which ->
                val avatarName = customAvatars[which].name
                val surface = avatarSurface ?: return@setItems
                avatarExecutor.execute {
                    val result = manager.loadCustomAvatar(
                        avatarName, surface, avatarTextureView.width, avatarTextureView.height
                    )
                    requireActivity().runOnUiThread {
                        if (result.success) {
                            avatarActiveModelText.text = avatarName
                            updateAvatarStatus("Ready", R.color.service_status_running_background)
                        } else {
                            showToast("Failed: ${result.message}")
                        }
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    fun refreshAffectDisplay(orchestrator: AffectOrchestrator) {
        if (!isAdded) return
        val state = orchestrator.getCurrentState()
        val expression = orchestrator.getLastExpression()
        val noise = orchestrator.getLastNoiseResult()

        affectIntensityText.text = "I=%.2f".format(state.intensity())
        val hedonic = state.hedonicTone()
        val hedonicLabel = when {
            hedonic > 0.5f -> "Flourishing"
            hedonic > 0.2f -> "Positive"
            hedonic > -0.2f -> "Neutral"
            hedonic > -0.5f -> "Low"
            else -> "Distress"
        }
        affectHedonicText.text = "Hedonic tone: %.2f ($hedonicLabel)".format(hedonic)

        buildAffectDimensionBars(state)

        if (expression != null) {
            expressionOutputText.text = buildString {
                append("Ears:     ${expression.earPosition}\n")
                append("Tail:     ${expression.tailState}")
                if (expression.tailPoof) append(" [POOF]")
                append("\nVoice:    ${expression.vocalTone}\n")
                append("Posture:  ${expression.posture}\n")
                append("Eyes:     ${expression.eyeTracking}\n")
                append("Breath:   ${expression.breathingRate}\n")
                append("Grip:     ${expression.gripPressure}\n")
                append("Proxim:   ${expression.proximitySeeking}")
            }
        } else {
            expressionOutputText.text = getString(R.string.avatar_no_expression)
        }

        if (noise != null) {
            motorNoiseText.text = buildString {
                append("Motor noise: %.2f".format(noise.overallNoiseLevel))
                if (state.isZeroNoiseState()) append(" [ZERO NOISE — total presence]")
            }
        } else {
            motorNoiseText.text = ""
        }
    }

    private fun buildAffectDimensionBars(state: AffectState) {
        affectDimensionBarsContainer.removeAllViews()
        val ctx = requireContext()

        val dimensionColors = mapOf(
            AffectDimension.VALENCE to R.color.affect_valence,
            AffectDimension.AROUSAL to R.color.affect_arousal,
            AffectDimension.ATTACHMENT_INTENSITY to R.color.affect_attachment,
            AffectDimension.CERTAINTY to R.color.affect_certainty,
            AffectDimension.NOVELTY_RESPONSE to R.color.affect_novelty,
            AffectDimension.THREAT_ASSESSMENT to R.color.affect_threat,
            AffectDimension.FRUSTRATION to R.color.affect_frustration,
            AffectDimension.SATIATION to R.color.affect_satiation,
            AffectDimension.VULNERABILITY to R.color.affect_vulnerability,
            AffectDimension.COHERENCE to R.color.affect_coherence,
            AffectDimension.DOMINANCE to R.color.affect_dominance,
            AffectDimension.INTEGRITY to R.color.affect_integrity
        )

        for (dim in AffectDimension.entries) {
            val value = state[dim]
            val normalizedValue = if (dim == AffectDimension.VALENCE) (value + 1f) / 2f else value.coerceIn(0f, 1f)

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 2 }
            }

            row.addView(TextView(ctx).apply {
                text = dim.key.take(8).uppercase()
                textSize = 9f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.25f)
                setTypeface(Typeface.MONOSPACE)
            })

            val barContainer = LinearLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, 10, 0.6f)
                setBackgroundColor(ContextCompat.getColor(ctx, R.color.affect_bar_background))
            }
            barContainer.addView(View(ctx).apply {
                setBackgroundColor(ContextCompat.getColor(ctx, dimensionColors[dim] ?: R.color.affect_valence))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    weight = normalizedValue
                }
            })
            barContainer.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    weight = 1f - normalizedValue
                }
            })
            row.addView(barContainer)

            row.addView(TextView(ctx).apply {
                text = "%.2f".format(value)
                textSize = 9f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.15f)
                gravity = Gravity.END
                setTypeface(Typeface.MONOSPACE)
            })

            affectDimensionBarsContainer.addView(row)
        }
    }

    fun updateAvatarFps() {
        if (!isAdded) return
        val manager = avatarSessionManager ?: return
        if (manager.isReady) {
            val stats = manager.getStats()
            val fps = (stats["nnr_current_fps"] as? Float) ?: 0f
            val frames = (stats["nnr_total_frames"] as? Long) ?: 0L
            avatarFpsText.text = if (fps > 0) "%.0f fps | %d frames".format(fps, frames) else ""
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        avatarSessionManager?.shutdown()
        avatarExecutor.shutdown()
    }
}
