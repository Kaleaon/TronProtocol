package com.tronprotocol.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
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
    private lateinit var chatRecyclerView: RecyclerView
    private lateinit var conversationAdapter: ConversationTurnAdapter
    private lateinit var conversationInput: TextInputEditText
    private lateinit var btnSendConversation: MaterialButton
    private lateinit var btnRetryConversation: MaterialButton
    private lateinit var chatInferenceStrip: LinearLayout
    private lateinit var inferenceTierIndicator: View
    private lateinit var inferenceTierText: TextView
    private lateinit var inferenceLatencyText: TextView
    private lateinit var inferenceQualityText: TextView
    private lateinit var inferenceContextText: TextView
    private lateinit var chatThinkingIndicator: LinearLayout
    private lateinit var chatThinkingText: TextView
    private lateinit var chatThinkingCategoryText: TextView
    private lateinit var chatEmotionStrip: LinearLayout
    private lateinit var chatHedonicToneText: TextView
    private lateinit var chatExpressionSummaryText: TextView
    private lateinit var chatCoherenceIndicator: View

    // --- Executor and handler ---
    private val chatExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val uiHandler = Handler(Looper.getMainLooper())

    // --- Conversation state ---
    private val conversationTurns = mutableListOf<ConversationTurn>()
    private var isSending = false
    private var lastFailedUserMessage: String? = null

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
        chatRecyclerView = view.findViewById(R.id.chatRecyclerView)
        conversationInput = view.findViewById(R.id.conversationInput)
        btnSendConversation = view.findViewById(R.id.btnSendConversation)
        btnRetryConversation = view.findViewById(R.id.btnRetryConversation)
        chatInferenceStrip = view.findViewById(R.id.chatInferenceStrip)
        inferenceTierIndicator = view.findViewById(R.id.inferenceTierIndicator)
        inferenceTierText = view.findViewById(R.id.inferenceTierText)
        inferenceLatencyText = view.findViewById(R.id.inferenceLatencyText)
        inferenceQualityText = view.findViewById(R.id.inferenceQualityText)
        inferenceContextText = view.findViewById(R.id.inferenceContextText)
        chatThinkingIndicator = view.findViewById(R.id.chatThinkingIndicator)
        chatThinkingText = view.findViewById(R.id.chatThinkingText)
        chatThinkingCategoryText = view.findViewById(R.id.chatThinkingCategoryText)
        chatEmotionStrip = view.findViewById(R.id.chatEmotionStrip)
        chatHedonicToneText = view.findViewById(R.id.chatHedonicToneText)
        chatExpressionSummaryText = view.findViewById(R.id.chatExpressionSummaryText)
        chatCoherenceIndicator = view.findViewById(R.id.chatCoherenceIndicator)

        setupConversationList()
    }

    private fun setupConversationList() {
        conversationAdapter = ConversationTurnAdapter { turn -> copyMessageToClipboard(turn) }
        chatRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        chatRecyclerView.adapter = conversationAdapter
    }

    // ========================================================================
    // Listeners
    // ========================================================================

    private fun setupListeners() {
        btnSendConversation.setOnClickListener { sendConversationMessage() }
        btnRetryConversation.setOnClickListener { retryLastFailedMessage() }
    }

    // ========================================================================
    // Conversation send logic
    // ========================================================================

    private fun sendConversationMessage() {
        if (isSending) return

        val userText = conversationInput.text?.toString()?.trim().orEmpty()
        if (userText.isBlank()) {
            Toast.makeText(requireContext(), getString(R.string.chat_toast_type_message), Toast.LENGTH_SHORT).show()
            return
        }

        sendConversationMessage(userText, isRetry = false)
    }

    private fun retryLastFailedMessage() {
        if (isSending) return

        val retryMessage = lastFailedUserMessage ?: return
        sendConversationMessage(retryMessage, isRetry = true)
    }

    private fun sendConversationMessage(userText: String, isRetry: Boolean) {
        isSending = true
        setInputEnabled(false)
        btnRetryConversation.visibility = View.GONE

        // Append user turn immediately
        val userRole = if (isRetry) "You (retry)" else "You"
        conversationTurns.add(ConversationTurn(userRole, userText))
        conversationTranscriptText.text = ConversationTranscriptFormatter.format(conversationTurns)
        if (!isRetry) {
            conversationInput.setText("")
        }
        conversationTurns.add(ConversationTurn("You", userText))
        submitConversationTurns()
        conversationInput.setText("")

        // Track in context manager
        aiContextManager.addTurn("You", userText)

        // Classify query and show thinking indicator
        val category = promptTemplateEngine.classifyQuery(userText)
        showThinkingIndicator(category)

        // Auto-scroll to bottom
        maybeAutoScrollToBottom()

        // Execute inference asynchronously
        val startTime = System.currentTimeMillis()
        chatExecutor.execute {
            val contextWindow = aiContextManager.buildContextWindow()
            var fallbackError: String? = null

            try {
                val pluginManager = PluginManager.getInstance()

                // Construct enhanced prompt with context
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
                    val inlineError = guidanceResult.errorMessage ?: "Unknown error"
                    aiMessage = "⚠️ Unable to respond: $inlineError"
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

                runOnUiIfActive {
                    conversationTurns.add(
                        ConversationTurn(
                            if (success) "Tron AI" else "Tron AI (error)",
                            aiMessage
                        )
                    )
                    conversationTranscriptText.text = ConversationTranscriptFormatter.format(conversationTurns)
                    updateInferenceStrip(
                        tier.label,
                        latencyMs,
                        qualityScore.overall,
                        "${contextWindow.turnCount}T ${"%.0f".format(contextWindow.utilizationPercent)}%%ctx"
                    )

                    if (!success) {
                        lastFailedUserMessage = userText
                        btnRetryConversation.visibility = View.VISIBLE
                    } else {
                        lastFailedUserMessage = null
                        btnRetryConversation.visibility = View.GONE
                    }

                    chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
                }
            } catch (e: Exception) {
                fallbackError = e.message ?: e.javaClass.simpleName

                runOnUiIfActive {
                    conversationTurns.add(
                        ConversationTurn(
                            "Tron AI (error)",
                            "⚠️ Unable to respond: $fallbackError"
                        )
                    )
                    conversationTranscriptText.text = ConversationTranscriptFormatter.format(conversationTurns)
                    lastFailedUserMessage = userText
                    btnRetryConversation.visibility = View.VISIBLE
                    chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
                }
            } finally {
                runOnUiIfActive {
                    hideThinkingIndicator()
                    isSending = false
                    setInputEnabled(true)
                }
            }
        }
    }

    private fun setInputEnabled(enabled: Boolean) {
        btnSendConversation.isEnabled = enabled
        conversationInput.isEnabled = enabled
    }
                hideThinkingIndicator()
                conversationTurns.add(ConversationTurn("Tron AI", aiMessage))
                submitConversationTurns()

    private fun runOnUiIfActive(action: () -> Unit) {
        val hostActivity = activity ?: return
        if (!isAdded || view == null) return

        hostActivity.runOnUiThread {
            if (!isAdded || view == null) return@runOnUiThread
            action()
                // Auto-scroll to bottom
                maybeAutoScrollToBottom()
            }
        }
    }

    private fun submitConversationTurns() {
        val shouldAutoScroll = isNearBottom()
        conversationAdapter.submitList(conversationTurns.toList()) {
            if (shouldAutoScroll) {
                chatRecyclerView.scrollToPosition((conversationAdapter.itemCount - 1).coerceAtLeast(0))
            }
        }
    }

    private fun maybeAutoScrollToBottom() {
        if (!isNearBottom()) return
        chatRecyclerView.post {
            chatRecyclerView.scrollToPosition((conversationAdapter.itemCount - 1).coerceAtLeast(0))
        }
    }

    private fun isNearBottom(thresholdItems: Int = 2): Boolean {
        val layoutManager = chatRecyclerView.layoutManager as? LinearLayoutManager ?: return true
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        if (lastVisible == RecyclerView.NO_POSITION) return true
        return lastVisible >= (conversationAdapter.itemCount - 1 - thresholdItems)
    }

    private fun copyMessageToClipboard(turn: ConversationTurn) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(turn.role, turn.message)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), getString(R.string.chat_copy_success), Toast.LENGTH_SHORT).show()
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

        val coherenceColor = when {
            coherence > 0.8f -> R.color.affect_coherence
            coherence > 0.5f -> R.color.service_status_degraded_background
            else -> R.color.service_status_blocked_background
        }
        chatCoherenceIndicator.setBackgroundColor(
            ContextCompat.getColor(requireContext(), coherenceColor)
        )
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

        // Tier indicator color and label
        val tierColorRes = when (tier) {
            InferenceTier.LOCAL_ALWAYS_ON.label,
            InferenceTier.LOCAL_ON_DEMAND.label -> R.color.tier_local
            InferenceTier.CLOUD_FALLBACK.label -> R.color.tier_cloud
            else -> R.color.tier_local
        }
        inferenceTierIndicator.setBackgroundColor(
            ContextCompat.getColor(requireContext(), tierColorRes)
        )
        inferenceTierText.text = tier

        // Latency
        inferenceLatencyText.text = "${latency}ms"

        // Quality indicator
        val qualityColorRes = when {
            quality >= 0.7f -> R.color.quality_good
            quality >= 0.4f -> R.color.quality_acceptable
            else -> R.color.quality_poor
        }
        inferenceQualityText.text = "Q=${"%.0f".format(quality * 100)}%%"
        inferenceQualityText.setTextColor(
            ContextCompat.getColor(requireContext(), qualityColorRes)
        )

        // Context utilization
        inferenceContextText.text = contextInfo
    }

    companion object {
        private const val GUIDANCE_ROUTER_PLUGIN_ID = "guidance_router"
        private const val GUIDANCE_ROUTER_GUIDE_COMMAND_PREFIX = "guide|"
    }
}
