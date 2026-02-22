package com.tronprotocol.app.plugins

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import org.json.JSONObject

/**
 * Aggregates device sensor data (accelerometer, gyroscope, light, proximity, barometer)
 * into a unified context signal. Detects user activity state.
 *
 * Commands:
 *   read          – Current sensor readings
 *   activity      – Inferred activity state (still, walking, driving, etc.)
 *   status        – Available sensors and registration state
 *   start         – Begin sensor listening
 *   stop          – Stop sensor listening
 */
class SensorFusionPlugin : Plugin, SensorEventListener {

    override val id: String = ID
    override val name: String = "Sensor Fusion"
    override val description: String =
        "Aggregate device sensors. Commands: read, activity, status, start, stop"
    override var isEnabled: Boolean = true

    private var sensorManager: SensorManager? = null
    private var listening = false

    // Latest readings
    @Volatile private var accelerometer = floatArrayOf(0f, 0f, 0f)
    @Volatile private var gyroscope = floatArrayOf(0f, 0f, 0f)
    @Volatile private var light = 0f
    @Volatile private var proximity = 0f
    @Volatile private var pressure = 0f
    @Volatile private var lastUpdateMs = 0L

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            val command = input.split("\\|".toRegex())[0].trim().lowercase()

            when (command) {
                "read" -> {
                    val json = JSONObject().apply {
                        put("accelerometer_x", accelerometer[0])
                        put("accelerometer_y", accelerometer[1])
                        put("accelerometer_z", accelerometer[2])
                        put("gyroscope_x", gyroscope[0])
                        put("gyroscope_y", gyroscope[1])
                        put("gyroscope_z", gyroscope[2])
                        put("light_lux", light)
                        put("proximity_cm", proximity)
                        put("pressure_hPa", pressure)
                        put("last_update_ms", lastUpdateMs)
                        put("listening", listening)
                    }
                    PluginResult.success(json.toString(2), elapsed(start))
                }
                "activity" -> {
                    val activity = inferActivity()
                    PluginResult.success(activity.toString(2), elapsed(start))
                }
                "status" -> {
                    val mgr = sensorManager
                    val available = JSONObject().apply {
                        put("accelerometer", mgr?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null)
                        put("gyroscope", mgr?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null)
                        put("light", mgr?.getDefaultSensor(Sensor.TYPE_LIGHT) != null)
                        put("proximity", mgr?.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null)
                        put("pressure", mgr?.getDefaultSensor(Sensor.TYPE_PRESSURE) != null)
                        put("listening", listening)
                    }
                    PluginResult.success(available.toString(2), elapsed(start))
                }
                "start" -> {
                    startListening()
                    PluginResult.success("Sensor listening started", elapsed(start))
                }
                "stop" -> {
                    stopListening()
                    PluginResult.success("Sensor listening stopped", elapsed(start))
                }
                else -> PluginResult.error("Unknown command: $command", elapsed(start))
            }
        } catch (e: Exception) {
            PluginResult.error("Sensor fusion error: ${e.message}", elapsed(start))
        }
    }

    private fun inferActivity(): JSONObject {
        val accelMag = Math.sqrt(
            (accelerometer[0] * accelerometer[0] +
                    accelerometer[1] * accelerometer[1] +
                    accelerometer[2] * accelerometer[2]).toDouble()
        ).toFloat()

        val gyroMag = Math.sqrt(
            (gyroscope[0] * gyroscope[0] +
                    gyroscope[1] * gyroscope[1] +
                    gyroscope[2] * gyroscope[2]).toDouble()
        ).toFloat()

        val activity = when {
            accelMag < 1.0f -> "freefall"
            accelMag in 8.0f..11.0f && gyroMag < 0.5f -> "still"
            accelMag in 8.0f..13.0f && gyroMag in 0.5f..3.0f -> "walking"
            accelMag > 13.0f || gyroMag > 3.0f -> "active_motion"
            else -> "unknown"
        }

        val environment = when {
            light < 10f -> "dark"
            light < 200f -> "indoor"
            light < 10000f -> "outdoor_shade"
            else -> "outdoor_bright"
        }

        val phonePosition = when {
            proximity < 1f -> "near_face_or_pocket"
            else -> "open"
        }

        return JSONObject().apply {
            put("activity", activity)
            put("environment", environment)
            put("phone_position", phonePosition)
            put("accel_magnitude", accelMag)
            put("gyro_magnitude", gyroMag)
            put("light_lux", light)
            put("proximity_cm", proximity)
            put("pressure_hPa", pressure)
        }
    }

    private fun startListening() {
        if (listening) return
        val mgr = sensorManager ?: return
        val rate = SensorManager.SENSOR_DELAY_NORMAL

        mgr.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { mgr.registerListener(this, it, rate) }
        mgr.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { mgr.registerListener(this, it, rate) }
        mgr.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { mgr.registerListener(this, it, rate) }
        mgr.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { mgr.registerListener(this, it, rate) }
        mgr.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { mgr.registerListener(this, it, rate) }

        listening = true
        Log.d(TAG, "Sensor listening started")
    }

    private fun stopListening() {
        sensorManager?.unregisterListener(this)
        listening = false
        Log.d(TAG, "Sensor listening stopped")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        lastUpdateMs = System.currentTimeMillis()
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> accelerometer = event.values.copyOf()
            Sensor.TYPE_GYROSCOPE -> gyroscope = event.values.copyOf()
            Sensor.TYPE_LIGHT -> light = event.values[0]
            Sensor.TYPE_PROXIMITY -> proximity = event.values[0]
            Sensor.TYPE_PRESSURE -> pressure = event.values[0]
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun initialize(context: Context) {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    }

    override fun destroy() {
        stopListening()
        sensorManager = null
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    companion object {
        const val ID = "sensor_fusion"
        private const val TAG = "SensorFusionPlugin"
    }
}
