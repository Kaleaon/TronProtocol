package com.tronprotocol.app.plugins

import com.tronprotocol.app.frontier.FrontierDynamicsPlugin

object PluginRegistry {
    data class PluginConfig(
        val id: String,
        val pluginClass: Class<out Plugin>,
        val defaultEnabled: Boolean,
        val startupPriority: Int,
        val factory: () -> Plugin,
    )

    val configs: List<PluginConfig> = listOf(
        PluginConfig("policy_guardrail", PolicyGuardrailPlugin::class.java, true, 10) { PolicyGuardrailPlugin() },
        PluginConfig("device_info", DeviceInfoPlugin::class.java, true, 20) { DeviceInfoPlugin() },
        PluginConfig("web_search", WebSearchPlugin::class.java, true, 30) { WebSearchPlugin() },
        PluginConfig("calculator", CalculatorPlugin::class.java, true, 40) { CalculatorPlugin() },
        PluginConfig("datetime", DateTimePlugin::class.java, true, 50) { DateTimePlugin() },
        PluginConfig("text_analysis", TextAnalysisPlugin::class.java, true, 60) { TextAnalysisPlugin() },
        PluginConfig("file_manager", FileManagerPlugin::class.java, true, 70) { FileManagerPlugin() },
        PluginConfig("notes", NotesPlugin::class.java, true, 80) { NotesPlugin() },
        PluginConfig("telegram_bridge", TelegramBridgePlugin::class.java, true, 90) { TelegramBridgePlugin() },
        PluginConfig("task_automation", TaskAutomationPlugin::class.java, true, 100) { TaskAutomationPlugin() },
        PluginConfig("sandbox_exec", SandboxedCodeExecutionPlugin::class.java, true, 110) { SandboxedCodeExecutionPlugin() },
        PluginConfig("personalization", PersonalizationPlugin::class.java, true, 120) { PersonalizationPlugin() },
        PluginConfig("communication_hub", CommunicationHubPlugin::class.java, true, 130) { CommunicationHubPlugin() },
        PluginConfig("on_device_llm", OnDeviceLLMPlugin::class.java, true, 15) { OnDeviceLLMPlugin() },
        PluginConfig("guidance_router", GuidanceRouterPlugin::class.java, true, 140) { GuidanceRouterPlugin() },
        PluginConfig("frontier_dynamics", FrontierDynamicsPlugin::class.java, true, 25) { FrontierDynamicsPlugin() },
        PluginConfig("takens_training", TakensTrainingPlugin::class.java, true, 145) { TakensTrainingPlugin() },
    )

    val sortedConfigs: List<PluginConfig>
        get() = configs.sortedBy { it.startupPriority }
}
