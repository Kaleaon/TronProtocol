package com.tronprotocol.app.ui

import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.tronprotocol.app.R
import com.tronprotocol.app.plugins.Plugin
import com.tronprotocol.app.plugins.PluginManager
import java.util.concurrent.Executors

/**
 * Fragment for the Plugins tab. Displays all registered plugins organized
 * by category with toggle switches to enable/disable each plugin.
 */
class PluginManagementFragment : Fragment() {

    // Views
    private lateinit var pluginSummaryText: TextView
    private lateinit var btnRuntimeSelfCheck: MaterialButton
    private lateinit var pluginCoreContainer: LinearLayout
    private lateinit var pluginAiContainer: LinearLayout
    private lateinit var pluginCommContainer: LinearLayout
    private lateinit var pluginToolsContainer: LinearLayout
    private lateinit var pluginSafetyContainer: LinearLayout

    private val selfCheckExecutor = Executors.newSingleThreadExecutor()
    private val uiHandler = Handler(Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_plugins, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupListeners()
        refreshPluginDisplay()
    }

    private fun bindViews(view: View) {
        pluginSummaryText = view.findViewById(R.id.pluginSummaryText)
        btnRuntimeSelfCheck = view.findViewById(R.id.btnRuntimeSelfCheck)
        pluginCoreContainer = view.findViewById(R.id.pluginCoreContainer)
        pluginAiContainer = view.findViewById(R.id.pluginAiContainer)
        pluginCommContainer = view.findViewById(R.id.pluginCommContainer)
        pluginToolsContainer = view.findViewById(R.id.pluginToolsContainer)
        pluginSafetyContainer = view.findViewById(R.id.pluginSafetyContainer)
    }

    private fun setupListeners() {
        btnRuntimeSelfCheck.setOnClickListener {
            runSelfCheck()
        }
    }

    /**
     * Refresh the plugin display by querying the PluginManager for all registered
     * plugins, categorizing them, and populating the appropriate containers.
     */
    fun refreshPluginDisplay() {
        val pluginManager = PluginManager.getInstance()
        val allPlugins = pluginManager.getAllPlugins()
        val enabledCount = allPlugins.count { it.isEnabled }

        pluginSummaryText.text = "${allPlugins.size} plugins loaded ($enabledCount enabled)"

        // Clear all containers
        pluginCoreContainer.removeAllViews()
        pluginAiContainer.removeAllViews()
        pluginCommContainer.removeAllViews()
        pluginToolsContainer.removeAllViews()
        pluginSafetyContainer.removeAllViews()

        // Categorize and add plugin rows
        for (plugin in allPlugins) {
            val row = createPluginRow(plugin)
            when {
                plugin.id in CORE_PLUGINS -> pluginCoreContainer.addView(row)
                plugin.id in AI_PLUGINS -> pluginAiContainer.addView(row)
                plugin.id in COMM_PLUGINS -> pluginCommContainer.addView(row)
                plugin.id in SAFETY_PLUGINS -> pluginSafetyContainer.addView(row)
                else -> pluginToolsContainer.addView(row)
            }
        }
    }

    /**
     * Create a horizontal row view for a single plugin with name, description,
     * and an enable/disable toggle switch.
     */
    private fun createPluginRow(plugin: Plugin): LinearLayout {
        val context = requireContext()
        val density = resources.displayMetrics.density

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            val horizontalPadding = (12 * density).toInt()
            val verticalPadding = (8 * density).toInt()
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
        }

        // Plugin name (bold, 14sp)
        val nameText = TextView(context).apply {
            text = plugin.name
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
        }

        // Separator and description (12sp)
        val descText = TextView(context).apply {
            text = " - ${plugin.description}"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        // Toggle switch reflecting enabled state
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val toggle = SwitchCompat(context).apply {
            isChecked = plugin.isEnabled
            setOnCheckedChangeListener { _, isChecked ->
                plugin.isEnabled = isChecked
                prefs.edit()
                    .putBoolean("plugin_enabled_${plugin.id}", isChecked)
                    .apply()
                // Update summary text after toggle change
                val pluginManager = PluginManager.getInstance()
                val allPlugins = pluginManager.getAllPlugins()
                val enabledCount = allPlugins.count { it.isEnabled }
                pluginSummaryText.text = "${allPlugins.size} plugins loaded ($enabledCount enabled)"
            }
        }

        row.addView(nameText)
        row.addView(descText)
        row.addView(toggle)

        return row
    }

    /**
     * Run a self-check on all enabled plugins in a background thread,
     * then display the results via a toast on the UI thread.
     */
    private fun runSelfCheck() {
        Toast.makeText(requireContext(), "Running self-check...", Toast.LENGTH_SHORT).show()

        selfCheckExecutor.execute {
            val pluginManager = PluginManager.getInstance()
            val resultSummary: String = try {
                pluginManager.runRuntimeSelfCheck()
            } catch (_: Exception) {
                // Fallback: iterate all enabled plugins calling execute("self_check")
                val enabledPlugins = pluginManager.getEnabledPlugins()
                var successCount = 0
                var failCount = 0
                for (plugin in enabledPlugins) {
                    try {
                        val result = plugin.execute("self_check")
                        if (result.isSuccess) successCount++ else failCount++
                    } catch (_: Exception) {
                        failCount++
                    }
                }
                "Self-check complete: $successCount passed, $failCount failed"
            }

            uiHandler.post {
                if (isAdded) {
                    Toast.makeText(requireContext(), resultSummary, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    companion object {
        /** Core system plugins */
        val CORE_PLUGINS = setOf(
            "policy_guardrail",
            "phylactery",
            "on_device_llm",
            "inference_router",
            "device_info",
            "api_key_vault"
        )

        /** AI and intelligence plugins */
        val AI_PLUGINS = setOf(
            "guidance_router",
            "frontier_dynamics",
            "personalization",
            "personality_import",
            "takens_training",
            "continuity_bridge",
            "drift_detector",
            "wisdom_log",
            "hedonic",
            "nct",
            "react_planner",
            "prompt_strategy_evolution",
            "ab_testing",
            "goal_hierarchy",
            "preference_crystallization",
            "identity_narrative",
            "relationship_model",
            "mood_decision_router",
            "error_pattern_learning",
            "conversation_summary",
            "tone_adaptation"
        )

        /** Communication plugins */
        val COMM_PLUGINS = setOf(
            "telegram_bridge",
            "communication_hub",
            "proactive_messaging",
            "sms_send",
            "email",
            "voice_synthesis",
            "communication_scheduler",
            "multi_party_conversation",
            "contact_manager"
        )

        /** Safety and governance plugins */
        val SAFETY_PLUGINS = setOf(
            "kill_switch",
            "privacy_budget",
            "user_override_history",
            "audit_dashboard",
            "granular_consent",
            "action_explanation",
            "value_alignment"
        )
    }
}
