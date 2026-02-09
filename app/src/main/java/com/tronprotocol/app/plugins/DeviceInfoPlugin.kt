package com.tronprotocol.app.plugins

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Plugin that provides device information.
 *
 * Example plugin inspired by ToolNeuron's dev utilities.
 */
class DeviceInfoPlugin : Plugin {

    companion object {
        private const val ID = "device_info"
        private const val NAME = "Device Info"
        private const val DESCRIPTION = "Provides device and system information"
    }

    private var context: Context? = null

    override val id: String = ID

    override val name: String = NAME

    override val description: String = DESCRIPTION

    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val startTime = System.currentTimeMillis()

        val info = buildString {
            append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("Brand: ${Build.BRAND}\n")
            append("Device: ${Build.DEVICE}\n")
            append("Hardware: ${Build.HARDWARE}\n")
            append("Product: ${Build.PRODUCT}\n")

            // Add memory info if context is available
            context?.let { ctx ->
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                am?.let {
                    val memInfo = ActivityManager.MemoryInfo()
                    it.getMemoryInfo(memInfo)

                    val totalMB = memInfo.totalMem / (1024 * 1024)
                    val availMB = memInfo.availMem / (1024 * 1024)

                    append("Total Memory: $totalMB MB\n")
                    append("Available Memory: $availMB MB\n")
                }
            }
        }

        val duration = System.currentTimeMillis() - startTime
        return PluginResult.success(info, duration)
    }

    override fun initialize(context: Context) {
        this.context = context
    }

    override fun destroy() {
        context = null
    }
}
