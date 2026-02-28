package com.tronprotocol.app.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.tronprotocol.app.ConversationTranscriptFormatter
import com.tronprotocol.app.ConversationTurn
import com.tronprotocol.app.R
import com.tronprotocol.app.inference.AIContextManager
import com.tronprotocol.app.inference.InferenceTelemetry
import com.tronprotocol.app.inference.InferenceTier
import com.tronprotocol.app.inference.PromptTemplateEngine
import com.tronprotocol.app.inference.ResponseQualityScorer
import com.tronprotocol.app.plugins.PluginManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Fragment for the Chat tab, extracted from MainActivity.
 *
 * Handles conversation UI, inference execution via the guidance_router plugin,
 * thinking indicators, inference telemetry tracking, and emotion strip updates.
 */
class ChatFragment : Fragment() {

    // --- Views ---
    private lateinit var chatScrollView: ScrollView
    private lateinit var conversationTranscriptText: TextView
    private lateinit var conversationInput: TextInputEditText
    private lateinit var btnSendConversation: MaterialButton
    private lateinit var chatInferenceStrip: LinearLayout
    private lateinit var inferenceTierIndicator: ImageView
    private lateinit var inferenceTierText: TextView
    private lateinit var inferenceLatencyText: TextView
    private lateinit var inferenceQualityIndicator: ImageView
    private lateinit var inferenceQualityText: TextView
    private lateinit var inferenceContextText: TextView
    private lateinit var chatThinkingIndicator: LinearLayout
    private lateinit var chatThinkingText: TextView
    private lateinit var chatThinkingCategoryText: TextView
    private lateinit var chatEmotionStrip: LinearLayout
    private lateinit var chatHedonicToneText: TextView
    private lateinit var chatExpressionSummaryText: TextView
    private lateinit var chatCoherenceIndicator: ImageView
    private lateinit var chatCoherenceText: TextView

    // --- Executor and handler ---
    private val chatExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val uiHandler = Handler(Looper.getMainLooper())

    // --- Conversation state ---
    private val conversationTurns = mutableListOf<ConversationTurn>()

    // --- AI inference subsystems ---
    private lateinit var aiContextManager: AIContextManager
    private lateinit var promptTemplateEngine: PromptTemplateEngine
    private lateinit var responseQualityScorer: ResponseQualityScorer

    /**
     * The InferenceTelemetry instance, expected to be set by the hosting Activity
     * after fragment attachment. This allows the fragment to record inference events
     * into the same telemetry store the Activity uses.
     */
    var inferenceTelemetry: InferenceTelemetry? = null

    // ========================================================================
    // Lifecycle
    // ========================================================================

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_chat, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        aiContextManager = AIContextManager()
        promptTemplateEngine = PromptTemplateEngine()
        responseQualityScorer = ResponseQualityScorer()

        bindViews(view)
        setupListeners()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        chatExecutor.shutdown()
    }

    // ========================================================================
    // View binding
    // ========================================================================

    private fun bindViews(view: View) {
        chatScrollView = view.findViewById(R.id.chatScrollView)
        conversationTranscriptText = view.findViewById(R.id.conversationTranscriptText)
        conversationInput = view.findViewById(R.id.conversationInput)
        btnSendConversation = view.findViewById(R.id.btnSendConversation)
        chatInferenceStrip = view.findViewById(R.id.chatInferenceStrip)
        inferenceTierIndicator = view.findViewById(R.id.inferenceTierIndicator)
        inferenceTierText = view.findViewById(R.id.inferenceTierText)
        inferenceLatencyText = view.findViewById(R.id.inferenceLatencyText)
        inferenceQualityIndicator = view.findViewById(R.id.inferenceQualityIndicator)
        inferenceQualityText = view.findViewById(R.id.inferenceQualityText)
        inferenceContextText = view.findViewById(R.id.inferenceContextText)
        chatThinkingIndicator = view.findViewById(R.id.chatThinkingIndicator)
        chatThinkingText = view.findViewById(R.id.chatThinkingText)
        chatThinkingCategoryText = view.findViewById(R.id.chatThinkingCategoryText)
        chatEmotionStrip = view.findViewById(R.id.chatEmotionStrip)
        chatHedonicToneText = view.findViewById(R.id.chatHedonicToneText)
        chatExpressionSummaryText = view.findViewById(R.id.chatExpressionSummaryText)
        chatCoherenceIndicator = view.findViewById(R.id.chatCoherenceIndicator)
        chatCoherenceText = view.findViewById(R.id.chatCoherenceText)
    }

    // ========================================================================
    // Listeners
    // ========================================================================

    private fun setupListeners() {
        btnSendConversation.setOnClickListener { sendConversationMessage() }
    }

    // ========================================================================
    // Conversation send logic
    // ========================================================================

    private fun sendConversationMessage() {
        val userText = conversationInput.text?.toString()?.trim().orEmpty()
        if (userText.isBlank()) {
            Toast.makeText(requireContext(), "Type a message before sending.", Toast.LENGTH_SHORT).show()
            return
        }

        // Append user turn immediately
        conversationTurns.add(ConversationTurn("You", userText))
        conversationTranscriptText.text = ConversationTranscriptFormatter.format(conversationTurns)
        conversationInput.setText("")

        // Track in context manager
        aiContextManager.addTurn("You", userText)

        // Classify query and show thinking indicator
        val category = promptTemplateEngine.classifyQuery(userText)
        showThinkingIndicator(category)

        // Auto-scroll to bottom
        chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }

        // Execute inference asynchronously
        val startTime = System.currentTimeMillis()
        chatExecutor.execute {
            val pluginManager = PluginManager.getInstance()

            // Construct enhanced prompt with context
            val contextWindow = aiContextManager.buildContextWindow()
            promptTemplateEngine.constructPrompt(
                userQuery = userText,
                conversationContext = aiContextManager.formatForInference()
            )

            val guidanceResult = pluginManager.executePlugin(
                GUIDANCE_ROUTER_PLUGIN_ID,
                "$GUIDANCE_ROUTER_GUIDE_COMMAND_PREFIX$userText"
            )

            val latencyMs = System.currentTimeMillis() - startTime
            val aiMessage: String
            val tier: InferenceTier
            val success: Boolean

            if (guidanceResult.isSuccess) {
                aiMessage = guidanceResult.data ?: "No response received from AI. Please try again."
                // Determine tier from guidance result metadata
                tier = if (guidanceResult.data?.startsWith("[cloud]") == true) {
                    InferenceTier.CLOUD_FALLBACK
                } else {
                    InferenceTier.LOCAL_ON_DEMAND
                }
                success = true
            } else {
                aiMessage = "I'm having trouble responding right now. Please try again."
                tier = InferenceTier.LOCAL_ON_DEMAND
                success = false
            }

            // Score response quality
            val qualityScore = responseQualityScorer.score(
                query = userText,
                response = aiMessage,
                category = category,
                tier = tier
            )

            // Record telemetry
            val tokenCount = AIContextManager.estimateTokens(aiMessage)
            inferenceTelemetry?.recordInference(
                InferenceTelemetry.InferenceEvent(
                    tier = tier,
                    category = category,
                    latencyMs = latencyMs,
                    tokenCount = tokenCount,
                    qualityScore = qualityScore.overall,
                    wasFallback = tier == InferenceTier.CLOUD_FALLBACK,
                    wasRegenerated = false,
                    success = success,
                    contextTokens = contextWindow.totalTokens,
                    errorMessage = if (!success) guidanceResult.errorMessage else null
                )
            )

            // Track AI response in context manager
            aiContextManager.addTurn("Tron AI", aiMessage)

            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread

                hideThinkingIndicator()
                conversationTurns.add(ConversationTurn("Tron AI", aiMessage))
                conversationTranscriptText.text = ConversationTranscriptFormatter.format(conversationTurns)

                // Update inference status strip
                updateInferenceStrip(
                    tier.label,
                    latencyMs,
                    qualityScore.overall,
                    "${contextWindow.turnCount}T ${"%.0f".format(contextWindow.utilizationPercent)}%%ctx"
                )

                // Auto-scroll to bottom
                chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    // ========================================================================
    // Thinking indicator
    // ========================================================================

    private fun showThinkingIndicator(category: PromptTemplateEngine.QueryCategory) {
        chatThinkingIndicator.visibility = View.VISIBLE
        chatThinkingText.text = getString(R.string.chat_thinking)
        chatThinkingCategoryText.text = category.name
    }

    private fun hideThinkingIndicator() {
        chatThinkingIndicator.visibility = View.GONE
    }

    // ========================================================================
    // Public methods callable by Activity
    // ========================================================================

    /**
     * Update the emotion strip displayed below the chat transcript.
     *
     * @param hedonic the hedonic tone value (typically -1.0 to 1.0)
     * @param expressionSummary a short description of the current expression, or null
     * @param coherence the emotional coherence value (0.0 to 1.0)
     */
    fun updateEmotionStrip(hedonic: Float, expressionSummary: String?, coherence: Float) {
        if (!isAdded) return

        chatEmotionStrip.visibility = View.VISIBLE
        chatHedonicToneText.text = "%.2f".format(hedonic)

        if (expressionSummary != null) {
            chatExpressionSummaryText.text = expressionSummary
        }

        val (coherenceColor, coherenceLabel, coherenceIconRes) = when {
            coherence > 0.8f -> Triple(R.color.affect_coherence, "Coherent", R.drawable.ic_quality_good)
            coherence > 0.5f -> Triple(R.color.service_status_degraded_background, "Mixed", R.drawable.ic_quality_degraded)
            else -> Triple(R.color.service_status_blocked_background, "Drift", R.drawable.ic_quality_degraded)
        }
        chatCoherenceIndicator.setImageResource(coherenceIconRes)
        chatCoherenceIndicator.setColorFilter(ContextCompat.getColor(requireContext(), coherenceColor))
        chatCoherenceIndicator.contentDescription = getString(
            R.string.emotion_coherence_indicator_dynamic_description,
            coherenceLabel
        )
        chatCoherenceText.text = coherenceLabel
    }

    /**
     * Update the inference telemetry strip displayed above the chat input.
     *
     * @param tier the inference tier label (e.g. "local_on_demand", "cloud_fallback")
     * @param latency the inference latency in milliseconds
     * @param quality the quality score (0.0 to 1.0)
     * @param contextInfo a formatted context utilization string
     */
    fun updateInferenceStrip(tier: String, latency: Long, quality: Float, contextInfo: String) {
        if (!isAdded) return

        chatInferenceStrip.visibility = View.VISIBLE

        // Tier indicator color, icon, and short label
        val (tierColorRes, tierLabel, tierIconRes) = when (tier) {
            InferenceTier.LOCAL_ALWAYS_ON.label,
            InferenceTier.LOCAL_ON_DEMAND.label -> Triple(R.color.tier_local, "Local", R.drawable.ic_status_local)
            InferenceTier.CLOUD_FALLBACK.label -> Triple(R.color.tier_cloud, "Cloud", R.drawable.ic_status_cloud)
            else -> Triple(R.color.tier_local, "Local", R.drawable.ic_status_local)
        }
        inferenceTierIndicator.setImageResource(tierIconRes)
        inferenceTierIndicator.setColorFilter(ContextCompat.getColor(requireContext(), tierColorRes))
        inferenceTierIndicator.contentDescription = getString(
            R.string.inference_tier_indicator_dynamic_description,
            tierLabel
        )
        inferenceTierText.text = tierLabel

        // Latency
        inferenceLatencyText.text = "${latency}ms"

        // Quality indicator icon + short label + score
        val (qualityColorRes, qualityLabel, qualityIconRes) = when {
            quality >= 0.7f -> Triple(R.color.quality_good, "Good", R.drawable.ic_quality_good)
            quality >= 0.4f -> Triple(R.color.quality_acceptable, "Degraded", R.drawable.ic_quality_degraded)
            else -> Triple(R.color.quality_poor, "Poor", R.drawable.ic_quality_degraded)
        }
        inferenceQualityIndicator.setImageResource(qualityIconRes)
        inferenceQualityIndicator.setColorFilter(ContextCompat.getColor(requireContext(), qualityColorRes))
        inferenceQualityIndicator.contentDescription = getString(
            R.string.inference_quality_indicator_dynamic_description,
            qualityLabel
        )
        inferenceQualityText.text = "$qualityLabel ${"%.0f".format(quality * 100)}%"

        // Context utilization
        inferenceContextText.text = contextInfo
    }

    companion object {
        private const val GUIDANCE_ROUTER_PLUGIN_ID = "guidance_router"
        private const val GUIDANCE_ROUTER_GUIDE_COMMAND_PREFIX = "guide|"
    }
}
