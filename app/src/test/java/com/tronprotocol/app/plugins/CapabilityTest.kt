package com.tronprotocol.app.plugins

import org.junit.Assert.*
import org.junit.Test

class CapabilityTest {

    @Test
    fun allExpectedCapabilitiesExist() {
        // Verify key capabilities are present
        assertNotNull("FILESYSTEM_READ should exist", Capability.FILESYSTEM_READ)
        assertNotNull("FILESYSTEM_WRITE should exist", Capability.FILESYSTEM_WRITE)
        assertNotNull("NETWORK_OUTBOUND should exist", Capability.NETWORK_OUTBOUND)
        assertNotNull("CONTACTS_READ should exist", Capability.CONTACTS_READ)
        assertNotNull("CONTACTS_WRITE should exist", Capability.CONTACTS_WRITE)
        assertNotNull("SMS_SEND should exist", Capability.SMS_SEND)
        assertNotNull("SMS_READ should exist", Capability.SMS_READ)
        assertNotNull("MODEL_EXECUTION should exist", Capability.MODEL_EXECUTION)
        assertNotNull("DEVICE_INFO_READ should exist", Capability.DEVICE_INFO_READ)
        assertNotNull("MEMORY_READ should exist", Capability.MEMORY_READ)
        assertNotNull("MEMORY_WRITE should exist", Capability.MEMORY_WRITE)
        assertNotNull("TASK_AUTOMATION should exist", Capability.TASK_AUTOMATION)
        assertNotNull("CODE_EXECUTION should exist", Capability.CODE_EXECUTION)
        assertNotNull("NOTIFICATION_READ should exist", Capability.NOTIFICATION_READ)
        assertNotNull("SCREEN_READ should exist", Capability.SCREEN_READ)
        assertNotNull("SENSOR_READ should exist", Capability.SENSOR_READ)
        assertNotNull("CALENDAR_READ should exist", Capability.CALENDAR_READ)
        assertNotNull("APP_USAGE_READ should exist", Capability.APP_USAGE_READ)
        assertNotNull("CLIPBOARD_READ should exist", Capability.CLIPBOARD_READ)
        assertNotNull("BATTERY_READ should exist", Capability.BATTERY_READ)
        assertNotNull("EMAIL_SEND should exist", Capability.EMAIL_SEND)
        assertNotNull("VOICE_OUTPUT should exist", Capability.VOICE_OUTPUT)
        assertNotNull("HTTP_REQUEST should exist", Capability.HTTP_REQUEST)
        assertNotNull("INTENT_FIRE should exist", Capability.INTENT_FIRE)
        assertNotNull("IMAGE_GENERATION should exist", Capability.IMAGE_GENERATION)
    }

    @Test
    fun valuesAreUnique() {
        val names = Capability.values().map { it.name }
        assertEquals(
            "All capability names should be unique",
            names.size,
            names.toSet().size
        )
    }

    @Test
    fun hasAtLeast30Values() {
        val count = Capability.values().size
        assertTrue(
            "Capability enum should have at least 30 values, got $count",
            count >= 30
        )
    }

    @Test
    fun keyCapabilitiesPresent() {
        val capabilityNames = Capability.values().map { it.name }.toSet()

        val required = setOf(
            "FILESYSTEM_READ",
            "FILESYSTEM_WRITE",
            "NETWORK_OUTBOUND",
            "SMS_SEND",
            "CODE_EXECUTION",
            "CONTACTS_READ"
        )

        for (name in required) {
            assertTrue(
                "Required capability $name should be present",
                capabilityNames.contains(name)
            )
        }
    }
}
