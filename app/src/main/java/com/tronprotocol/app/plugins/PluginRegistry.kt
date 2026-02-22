package com.tronprotocol.app.plugins

import com.tronprotocol.app.frontier.FrontierDynamicsPlugin

object PluginRegistry {
    data class PluginConfig(
        val id: String,
        val pluginClass: Class<out Plugin>,
        val defaultEnabled: Boolean,
        val startupPriority: Int,
        val defaultCapabilities: Set<Capability>,
        val factory: () -> Plugin,
    )

    val configs: List<PluginConfig> = listOf(
        PluginConfig("policy_guardrail", PolicyGuardrailPlugin::class.java, true, 10, emptySet()) { PolicyGuardrailPlugin() },
        PluginConfig("device_info", DeviceInfoPlugin::class.java, true, 20, setOf(Capability.DEVICE_INFO_READ)) { DeviceInfoPlugin() },
        PluginConfig("web_search", WebSearchPlugin::class.java, true, 30, setOf(Capability.NETWORK_OUTBOUND)) { WebSearchPlugin() },
        PluginConfig("calculator", CalculatorPlugin::class.java, true, 40, emptySet()) { CalculatorPlugin() },
        PluginConfig("datetime", DateTimePlugin::class.java, true, 50, emptySet()) { DateTimePlugin() },
        PluginConfig("text_analysis", TextAnalysisPlugin::class.java, true, 60, setOf(Capability.MODEL_EXECUTION)) { TextAnalysisPlugin() },
        PluginConfig("file_manager", FileManagerPlugin::class.java, true, 70, setOf(Capability.FILESYSTEM_READ, Capability.FILESYSTEM_WRITE)) { FileManagerPlugin() },
        PluginConfig("notes", NotesPlugin::class.java, true, 80, setOf(Capability.MEMORY_READ, Capability.MEMORY_WRITE)) { NotesPlugin() },
        PluginConfig("telegram_bridge", TelegramBridgePlugin::class.java, true, 90, setOf(Capability.NETWORK_OUTBOUND)) { TelegramBridgePlugin() },
        PluginConfig("task_automation", TaskAutomationPlugin::class.java, true, 100, setOf(Capability.TASK_AUTOMATION)) { TaskAutomationPlugin() },
        PluginConfig("sandbox_exec", SandboxedCodeExecutionPlugin::class.java, true, 110, setOf(Capability.CODE_EXECUTION)) { SandboxedCodeExecutionPlugin() },
        PluginConfig("personalization", PersonalizationPlugin::class.java, true, 120, setOf(Capability.MEMORY_READ, Capability.MEMORY_WRITE)) { PersonalizationPlugin() },
        PluginConfig("personality_import", PersonalityImportPlugin::class.java, true, 121, setOf(Capability.FILESYSTEM_READ, Capability.MEMORY_READ, Capability.MEMORY_WRITE)) { PersonalityImportPlugin() },
        PluginConfig("communication_hub", CommunicationHubPlugin::class.java, true, 130, setOf(Capability.CONTACTS_READ, Capability.SMS_SEND, Capability.NETWORK_OUTBOUND)) { CommunicationHubPlugin() },
        PluginConfig("on_device_llm", OnDeviceLLMPlugin::class.java, true, 15, setOf(Capability.MODEL_EXECUTION)) { OnDeviceLLMPlugin() },
        PluginConfig("guidance_router", GuidanceRouterPlugin::class.java, true, 140, setOf(Capability.NETWORK_OUTBOUND, Capability.MODEL_EXECUTION)) { GuidanceRouterPlugin() },
        PluginConfig("frontier_dynamics", FrontierDynamicsPlugin::class.java, true, 25, setOf(Capability.MODEL_EXECUTION)) { FrontierDynamicsPlugin() },
        PluginConfig("takens_training", TakensTrainingPlugin::class.java, true, 145, setOf(Capability.MODEL_EXECUTION)) { TakensTrainingPlugin() },
        PluginConfig("continuity_bridge", ContinuityBridgePlugin::class.java, true, 146, setOf(Capability.MEMORY_READ, Capability.MEMORY_WRITE)) { ContinuityBridgePlugin() },
        // ── TronProtocol Pixel 10 Spec: new systems ──
        PluginConfig("phylactery", PhylacteryPlugin::class.java, true, 12, setOf(Capability.MEMORY_READ, Capability.MEMORY_WRITE)) { PhylacteryPlugin() },
        PluginConfig("inference_router", InferenceRouterPlugin::class.java, true, 16, setOf(Capability.MODEL_EXECUTION, Capability.NETWORK_OUTBOUND)) { InferenceRouterPlugin() },
        PluginConfig("drift_detector", DriftDetectorPlugin::class.java, true, 147, setOf(Capability.MEMORY_READ, Capability.MEMORY_WRITE)) { DriftDetectorPlugin() },
        PluginConfig("wisdom_log", WisdomLogPlugin::class.java, true, 148, setOf(Capability.MEMORY_READ, Capability.MEMORY_WRITE)) { WisdomLogPlugin() },
        PluginConfig("hedonic", HedonicPlugin::class.java, true, 149, setOf(Capability.MEMORY_READ, Capability.MEMORY_WRITE)) { HedonicPlugin() },
        PluginConfig("nct", NCTPlugin::class.java, true, 150, setOf(Capability.MEMORY_READ, Capability.MEMORY_WRITE, Capability.MODEL_EXECUTION)) { NCTPlugin() },
        // ── Phase 2: Perception plugins ──
        PluginConfig("notification_listener", NotificationListenerPlugin::class.java, true, 160, setOf(Capability.NOTIFICATION_READ)) { NotificationListenerPlugin() },
        PluginConfig("screen_reader", ScreenReaderPlugin::class.java, true, 165, setOf(Capability.SCREEN_READ)) { ScreenReaderPlugin() },
        PluginConfig("sensor_fusion", SensorFusionPlugin::class.java, true, 170, setOf(Capability.SENSOR_READ)) { SensorFusionPlugin() },
        PluginConfig("calendar", CalendarPlugin::class.java, true, 175, setOf(Capability.CALENDAR_READ)) { CalendarPlugin() },
        PluginConfig("app_usage", AppUsagePlugin::class.java, true, 180, setOf(Capability.APP_USAGE_READ)) { AppUsagePlugin() },
        PluginConfig("clipboard_monitor", ClipboardMonitorPlugin::class.java, true, 185, setOf(Capability.CLIPBOARD_READ)) { ClipboardMonitorPlugin() },
        PluginConfig("battery_connectivity", BatteryConnectivityPlugin::class.java, true, 190, setOf(Capability.BATTERY_READ)) { BatteryConnectivityPlugin() },
        PluginConfig("content_provider_query", ContentProviderQueryPlugin::class.java, true, 195, setOf(Capability.CONTENT_PROVIDER_READ, Capability.CONTACTS_READ, Capability.SMS_READ)) { ContentProviderQueryPlugin() },
        // ── Phase 4: Communication plugins ──
        PluginConfig("proactive_messaging", ProactiveMessagingPlugin::class.java, true, 200, setOf(Capability.PROACTIVE_MESSAGE, Capability.NETWORK_OUTBOUND)) { ProactiveMessagingPlugin() },
        PluginConfig("sms_send", SMSSendPlugin::class.java, true, 205, setOf(Capability.SMS_SEND)) { SMSSendPlugin() },
        PluginConfig("email", EmailPlugin::class.java, true, 210, setOf(Capability.EMAIL_SEND, Capability.NETWORK_OUTBOUND)) { EmailPlugin() },
        PluginConfig("voice_synthesis", VoiceSynthesisPlugin::class.java, true, 215, setOf(Capability.VOICE_OUTPUT)) { VoiceSynthesisPlugin() },
        PluginConfig("communication_scheduler", CommunicationSchedulerPlugin::class.java, true, 220, setOf(Capability.SCHEDULED_ACTION)) { CommunicationSchedulerPlugin() },
        PluginConfig("conversation_summary", ConversationSummaryPlugin::class.java, true, 225, setOf(Capability.MEMORY_READ, Capability.MEMORY_WRITE)) { ConversationSummaryPlugin() },
        PluginConfig("tone_adaptation", ToneAdaptationPlugin::class.java, true, 230, setOf(Capability.MEMORY_READ)) { ToneAdaptationPlugin() },
        PluginConfig("multi_party_conversation", MultiPartyConversationPlugin::class.java, true, 235, setOf(Capability.MEMORY_READ, Capability.MEMORY_WRITE)) { MultiPartyConversationPlugin() },
        // ── Phase 5: Action & Orchestration plugins ──
        PluginConfig("http_client", HttpClientPlugin::class.java, true, 240, setOf(Capability.HTTP_REQUEST, Capability.NETWORK_OUTBOUND)) { HttpClientPlugin() },
        PluginConfig("intent_automation", IntentAutomationPlugin::class.java, true, 245, setOf(Capability.INTENT_FIRE)) { IntentAutomationPlugin() },
        PluginConfig("contact_manager", ContactManagerPlugin::class.java, true, 250, setOf(Capability.CONTACTS_READ, Capability.CONTACTS_WRITE)) { ContactManagerPlugin() },
        PluginConfig("react_planner", ReActPlanningExecutor::class.java, true, 255, setOf(Capability.TASK_AUTOMATION, Capability.GOAL_MANAGE)) { ReActPlanningExecutor() },
        PluginConfig("scheduled_actions", ScheduledActionsPlugin::class.java, true, 260, setOf(Capability.SCHEDULED_ACTION, Capability.TASK_AUTOMATION)) { ScheduledActionsPlugin() },
        PluginConfig("workflow_persistence", WorkflowPersistencePlugin::class.java, true, 265, setOf(Capability.MEMORY_READ, Capability.MEMORY_WRITE)) { WorkflowPersistencePlugin() },
        PluginConfig("api_key_vault", APIKeyVaultPlugin::class.java, true, 270, setOf(Capability.MEMORY_READ, Capability.MEMORY_WRITE)) { APIKeyVaultPlugin() },
        PluginConfig("scripting_runtime", ScriptingRuntimePlugin::class.java, true, 275, setOf(Capability.SCRIPT_EXECUTE, Capability.CODE_EXECUTION)) { ScriptingRuntimePlugin() },
        // ── Phase 6: Self-Improvement plugins ──
        PluginConfig("user_feedback", UserFeedbackPlugin::class.java, true, 280, setOf(Capability.MEMORY_READ, Capability.MEMORY_WRITE)) { UserFeedbackPlugin() },
        PluginConfig("benchmark_suite", BenchmarkSuitePlugin::class.java, true, 285, setOf(Capability.MEMORY_READ)) { BenchmarkSuitePlugin() },
        PluginConfig("capability_discovery", CapabilityDiscoveryPlugin::class.java, true, 290, setOf(Capability.MEMORY_READ)) { CapabilityDiscoveryPlugin() },
        PluginConfig("error_pattern_learning", ErrorPatternLearningPlugin::class.java, true, 295, setOf(Capability.MEMORY_READ, Capability.MEMORY_WRITE)) { ErrorPatternLearningPlugin() },
        PluginConfig("prompt_strategy_evolution", PromptStrategyEvolutionPlugin::class.java, true, 300, setOf(Capability.MEMORY_READ, Capability.MEMORY_WRITE)) { PromptStrategyEvolutionPlugin() },
        PluginConfig("ab_testing", ABTestingPlugin::class.java, true, 305, setOf(Capability.MEMORY_READ, Capability.MEMORY_WRITE)) { ABTestingPlugin() },
        // ── Phase 7: Identity & Coherence plugins ──
        PluginConfig("goal_hierarchy", GoalHierarchyPlugin::class.java, true, 310, setOf(Capability.GOAL_MANAGE, Capability.MEMORY_READ, Capability.MEMORY_WRITE)) { GoalHierarchyPlugin() },
        PluginConfig("value_alignment", ValueAlignmentPlugin::class.java, true, 315, setOf(Capability.MEMORY_READ, Capability.MEMORY_WRITE)) { ValueAlignmentPlugin() },
        PluginConfig("preference_crystallization", PreferenceCrystallizationPlugin::class.java, true, 320, setOf(Capability.MEMORY_READ, Capability.MEMORY_WRITE)) { PreferenceCrystallizationPlugin() },
        PluginConfig("identity_narrative", IdentityNarrativePlugin::class.java, true, 325, setOf(Capability.MEMORY_READ, Capability.MEMORY_WRITE)) { IdentityNarrativePlugin() },
        PluginConfig("relationship_model", RelationshipModelPlugin::class.java, true, 330, setOf(Capability.MEMORY_READ, Capability.MEMORY_WRITE)) { RelationshipModelPlugin() },
        PluginConfig("mood_decision_router", MoodDecisionRouterPlugin::class.java, true, 335, setOf(Capability.MEMORY_READ, Capability.MEMORY_WRITE)) { MoodDecisionRouterPlugin() },
        // ── Phase 8: Safety & Transparency plugins ──
        PluginConfig("audit_dashboard", AuditDashboardPlugin::class.java, true, 340, setOf(Capability.MEMORY_READ, Capability.MEMORY_WRITE)) { AuditDashboardPlugin() },
        PluginConfig("granular_consent", GranularConsentPlugin::class.java, true, 345, setOf(Capability.MEMORY_READ, Capability.MEMORY_WRITE)) { GranularConsentPlugin() },
        PluginConfig("action_explanation", ActionExplanationPlugin::class.java, true, 350, setOf(Capability.MEMORY_READ, Capability.MEMORY_WRITE)) { ActionExplanationPlugin() },
        PluginConfig("kill_switch", KillSwitchPlugin::class.java, true, 355, setOf(Capability.TASK_AUTOMATION)) { KillSwitchPlugin() },
        PluginConfig("privacy_budget", PrivacyBudgetPlugin::class.java, true, 360, setOf(Capability.MEMORY_READ, Capability.MEMORY_WRITE)) { PrivacyBudgetPlugin() },
        PluginConfig("user_override_history", UserOverrideHistoryPlugin::class.java, true, 365, setOf(Capability.MEMORY_READ, Capability.MEMORY_WRITE)) { UserOverrideHistoryPlugin() },
    )

    val defaultCapabilitiesByPluginId: Map<String, Set<Capability>>
        get() = configs.associate { it.id to it.defaultCapabilities }

    val sortedConfigs: List<PluginConfig>
        get() = configs.sortedBy { it.startupPriority }
}
