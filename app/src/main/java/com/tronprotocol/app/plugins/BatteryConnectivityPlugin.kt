package com.tronprotocol.app.plugins

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import org.json.JSONObject

/**
 * Monitors battery level, charging state, and network connectivity.
 *
 * Commands:
 *   battery     – Battery level, charging state, temperature
 *   network     – Network type, connectivity status
 *   all         – Combined battery + network snapshot
 *   can_heavy   – Whether conditions allow heavy operations (good battery + wifi)
 */
class BatteryConnectivityPlugin : Plugin {

    override val id: String = ID
    override val name: String = "Battery & Connectivity"
    override val description: String =
        "Battery and network state. Commands: battery, network, all, can_heavy"
    override var isEnabled: Boolean = true

    private var appContext: Context? = null

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        val ctx = appContext ?: return PluginResult.error("Context not available", 0)

        return try {
            val command = input.split("\\|".toRegex())[0].trim().lowercase()

            when (command) {
                "battery" -> PluginResult.success(getBatteryInfo(ctx).toString(2), elapsed(start))
                "network" -> PluginResult.success(getNetworkInfo(ctx).toString(2), elapsed(start))
                "all" -> {
                    val combined = JSONObject()
                    combined.put("battery", getBatteryInfo(ctx))
                    combined.put("network", getNetworkInfo(ctx))
                    PluginResult.success(combined.toString(2), elapsed(start))
                }
                "can_heavy" -> {
                    val battery = getBatteryInfo(ctx)
                    val network = getNetworkInfo(ctx)
                    val level = battery.optInt("level", 0)
                    val charging = battery.optBoolean("charging", false)
                    val wifi = network.optBoolean("wifi", false)
                    val connected = network.optBoolean("connected", false)

                    val canHeavy = (level > 20 || charging) && connected
                    val canDownload = canHeavy && wifi
                    val recommendation = when {
                        !connected -> "No network. Defer all network operations."
                        level <= 15 && !charging -> "Critical battery. Only essential operations."
                        level <= 30 && !charging -> "Low battery. Avoid heavy computation."
                        !wifi -> "On cellular. Avoid large downloads."
                        else -> "Good conditions for all operations."
                    }
                    PluginResult.success(JSONObject().apply {
                        put("can_heavy_compute", canHeavy)
                        put("can_large_download", canDownload)
                        put("recommendation", recommendation)
                        put("battery_level", level)
                        put("charging", charging)
                        put("wifi", wifi)
                    }.toString(2), elapsed(start))
                }
                else -> PluginResult.error("Unknown command: $command", elapsed(start))
            }
        } catch (e: Exception) {
            PluginResult.error("Battery/connectivity error: ${e.message}", elapsed(start))
        }
    }

    private fun getBatteryInfo(ctx: Context): JSONObject {
        val batteryStatus = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (scale > 0) (level * 100) / scale else -1
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val plugged = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val temp = (batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val health = batteryStatus?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1

        return JSONObject().apply {
            put("level", pct)
            put("charging", charging)
            put("plugged", when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                else -> "none"
            })
            put("temperature_c", temp)
            put("health", when (health) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
                BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
                else -> "unknown"
            })
            put("status", when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                BatteryManager.BATTERY_STATUS_FULL -> "full"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
                else -> "unknown"
            })
        }
    }

    private fun getNetworkInfo(ctx: Context): JSONObject {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val json = JSONObject()

        if (cm == null) {
            json.put("connected", false)
            json.put("error", "ConnectivityManager unavailable")
            return json
        }

        val network = cm.activeNetwork
        val caps = if (network != null) cm.getNetworkCapabilities(network) else null

        json.put("connected", caps != null)
        json.put("wifi", caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false)
        json.put("cellular", caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ?: false)
        json.put("ethernet", caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ?: false)
        json.put("vpn", caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ?: false)
        json.put("metered", cm.isActiveNetworkMetered)

        if (caps != null) {
            json.put("download_bandwidth_kbps", caps.linkDownstreamBandwidthKbps)
            json.put("upload_bandwidth_kbps", caps.linkUpstreamBandwidthKbps)
        }

        return json
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    override fun destroy() {
        appContext = null
    }

    companion object {
        const val ID = "battery_connectivity"
    }
}
