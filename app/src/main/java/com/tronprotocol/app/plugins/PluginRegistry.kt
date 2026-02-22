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
    )

    val defaultCapabilitiesByPluginId: Map<String, Set<Capability>>
        get() = configs.associate { it.id to it.defaultCapabilities }

    val sortedConfigs: List<PluginConfig>
        get() = configs.sortedBy { it.startupPriority }
}
