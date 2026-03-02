package com.tronprotocol.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SensorFusionPluginTest {

    private lateinit var plugin: SensorFusionPlugin

    @Before
    fun setUp() {
        plugin = SensorFusionPlugin()
    }

    @Test
    fun testActivity_freefall() {
        // accelMag < 1.0f -> "freefall"
        plugin.accelerometer = floatArrayOf(0f, 0.5f, 0f) // mag = 0.5
        plugin.gyroscope = floatArrayOf(0f, 0f, 0f)

        val result = plugin.inferActivity()
        assertEquals("freefall", result.getString("activity"))
    }

    @Test
    fun testActivity_still() {
        // accelMag in 8.0f..11.0f && gyroMag < 0.5f -> "still"
        plugin.accelerometer = floatArrayOf(0f, 0f, 9.81f) // mag = 9.81
        plugin.gyroscope = floatArrayOf(0.1f, 0.1f, 0.1f) // mag = ~0.17

        val result = plugin.inferActivity()
        assertEquals("still", result.getString("activity"))
    }

    @Test
    fun testActivity_walking() {
        // accelMag in 8.0f..13.0f && gyroMag in 0.5f..3.0f -> "walking"
        plugin.accelerometer = floatArrayOf(0f, 10f, 0f) // mag = 10.0
        plugin.gyroscope = floatArrayOf(0f, 1.0f, 0f) // mag = 1.0

        val result = plugin.inferActivity()
        assertEquals("walking", result.getString("activity"))
    }

    @Test
    fun testActivity_activeMotion_highAccel() {
        // accelMag > 13.0f || gyroMag > 3.0f -> "active_motion"
        plugin.accelerometer = floatArrayOf(10f, 10f, 0f) // mag = 14.14
        plugin.gyroscope = floatArrayOf(0f, 0f, 0f) // mag = 0.0

        val result = plugin.inferActivity()
        assertEquals("active_motion", result.getString("activity"))
    }

    @Test
    fun testActivity_activeMotion_highGyro() {
        // accelMag > 13.0f || gyroMag > 3.0f -> "active_motion"
        plugin.accelerometer = floatArrayOf(0f, 9.8f, 0f) // mag = 9.8
        plugin.gyroscope = floatArrayOf(0f, 0f, 4f) // mag = 4.0

        val result = plugin.inferActivity()
        assertEquals("active_motion", result.getString("activity"))
    }

    @Test
    fun testActivity_unknown() {
        // else -> "unknown"
        // Let's create a situation that hits else:
        // Not freefall (>= 1.0)
        // Not still (e.g., accelMag = 5.0f, gyroMag = 0.0f)
        // Not walking (accelMag = 5.0f < 8.0f)
        // Not active_motion (accelMag = 5.0f <= 13.0f and gyroMag = 0.0f <= 3.0f)
        plugin.accelerometer = floatArrayOf(5f, 0f, 0f) // mag = 5.0
        plugin.gyroscope = floatArrayOf(0f, 0f, 0f) // mag = 0.0

        val result = plugin.inferActivity()
        assertEquals("unknown", result.getString("activity"))
    }

    @Test
    fun testEnvironment_dark() {
        // light < 10f -> "dark"
        plugin.light = 5f
        val result = plugin.inferActivity()
        assertEquals("dark", result.getString("environment"))
    }

    @Test
    fun testEnvironment_indoor() {
        // light < 200f -> "indoor"
        plugin.light = 150f
        val result = plugin.inferActivity()
        assertEquals("indoor", result.getString("environment"))
    }

    @Test
    fun testEnvironment_outdoorShade() {
        // light < 10000f -> "outdoor_shade"
        plugin.light = 5000f
        val result = plugin.inferActivity()
        assertEquals("outdoor_shade", result.getString("environment"))
    }

    @Test
    fun testEnvironment_outdoorBright() {
        // else -> "outdoor_bright"
        plugin.light = 15000f
        val result = plugin.inferActivity()
        assertEquals("outdoor_bright", result.getString("environment"))
    }

    @Test
    fun testPhonePosition_nearFaceOrPocket() {
        // proximity < 1f -> "near_face_or_pocket"
        plugin.proximity = 0.5f
        val result = plugin.inferActivity()
        assertEquals("near_face_or_pocket", result.getString("phone_position"))
    }

    @Test
    fun testPhonePosition_open() {
        // else -> "open"
        plugin.proximity = 5.0f
        val result = plugin.inferActivity()
        assertEquals("open", result.getString("phone_position"))
    }

    @Test
    fun testActivity_edgeCase_boundaryStill() {
        // Testing the boundaries for "still"
        // accelMag in 8.0f..11.0f && gyroMag < 0.5f -> "still"
        plugin.accelerometer = floatArrayOf(8.0f, 0f, 0f) // exactly 8.0
        plugin.gyroscope = floatArrayOf(0.49f, 0f, 0f) // strictly < 0.5f
        val result = plugin.inferActivity()
        assertEquals("still", result.getString("activity"))
    }

    @Test
    fun testActivity_edgeCase_boundaryWalking() {
        // accelMag in 8.0f..13.0f && gyroMag in 0.5f..3.0f -> "walking"
        plugin.accelerometer = floatArrayOf(13.0f, 0f, 0f) // exactly 13.0
        plugin.gyroscope = floatArrayOf(3.0f, 0f, 0f) // exactly 3.0
        val result = plugin.inferActivity()
        assertEquals("walking", result.getString("activity"))
    }
}
