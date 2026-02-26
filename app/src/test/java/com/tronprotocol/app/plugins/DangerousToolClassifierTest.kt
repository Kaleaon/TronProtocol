package com.tronprotocol.app.plugins

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DangerousToolClassifierTest {

    private lateinit var classifier: DangerousToolClassifier

    @Before
    fun setUp() {
        classifier = DangerousToolClassifier()
    }

    // --- Default classifications ---

    @Test
    fun testSafePluginsClassifiedAsSafe() {
        val safePlugins = listOf("calculator", "datetime", "text_analysis", "device_info", "notes")
        for (pluginId in safePlugins) {
            val classification = classifier.classify(pluginId)
            assertEquals(
                "$pluginId should be SAFE",
                DangerousToolClassifier.DangerTier.SAFE,
                classification.tier
            )
        }
    }

    @Test
    fun testOwnerOnlyPlugins() {
        val ownerOnly = listOf("telegram_bridge", "communication_hub", "sandbox_exec", "file_manager")
        for (pluginId in ownerOnly) {
            val classification = classifier.classify(pluginId)
            assertEquals(
                "$pluginId should be OWNER_ONLY",
                DangerousToolClassifier.DangerTier.OWNER_ONLY,
                classification.tier
            )
        }
    }

    @Test
    fun testApprovalRequiredPlugins() {
        val approvalRequired = listOf(
            "task_automation", "intent_automation", "sms_send",
            "email", "proactive_messaging"
        )
        for (pluginId in approvalRequired) {
            val classification = classifier.classify(pluginId)
            assertEquals(
                "$pluginId should be APPROVAL_REQUIRED",
                DangerousToolClassifier.DangerTier.APPROVAL_REQUIRED,
                classification.tier
            )
        }
    }

    @Test
    fun testUnknownPluginDefaultsToSafe() {
        val classification = classifier.classify("unknown_plugin_xyz")
        assertEquals(DangerousToolClassifier.DangerTier.SAFE, classification.tier)
    }

    // --- isDangerous convenience ---

    @Test
    fun testIsDangerousForOwnerOnly() {
        assertTrue(classifier.isDangerous("telegram_bridge"))
    }

    @Test
    fun testIsDangerousForApprovalRequired() {
        assertTrue(classifier.isDangerous("sms_send"))
    }

    @Test
    fun testIsDangerousReturnsFalseForSafe() {
        assertFalse(classifier.isDangerous("calculator"))
    }

    // --- Overrides ---

    @Test
    fun testOverrideChangesClassification() {
        // calculator is SAFE by default
        assertEquals(DangerousToolClassifier.DangerTier.SAFE, classifier.classify("calculator").tier)

        // Override to BLOCKED
        classifier.setOverride("calculator", DangerousToolClassifier.DangerTier.BLOCKED)
        assertEquals(DangerousToolClassifier.DangerTier.BLOCKED, classifier.classify("calculator").tier)
        assertTrue(classifier.isDangerous("calculator"))

        // Remove override — back to SAFE
        classifier.removeOverride("calculator")
        assertEquals(DangerousToolClassifier.DangerTier.SAFE, classifier.classify("calculator").tier)
    }

    @Test
    fun testOverrideCanRelaxClassification() {
        // telegram_bridge is OWNER_ONLY by default
        assertEquals(DangerousToolClassifier.DangerTier.OWNER_ONLY, classifier.classify("telegram_bridge").tier)

        // Override to SAFE
        classifier.setOverride("telegram_bridge", DangerousToolClassifier.DangerTier.SAFE)
        assertEquals(DangerousToolClassifier.DangerTier.SAFE, classifier.classify("telegram_bridge").tier)
        assertFalse(classifier.isDangerous("telegram_bridge"))
    }

    // --- Collection queries ---

    @Test
    fun testGetApprovalRequiredTools() {
        val tools = classifier.getApprovalRequiredTools()
        assertTrue(tools.contains("task_automation"))
        assertTrue(tools.contains("sms_send"))
        assertFalse(tools.contains("calculator"))
        assertFalse(tools.contains("telegram_bridge"))
    }

    @Test
    fun testGetOwnerOnlyTools() {
        val tools = classifier.getOwnerOnlyTools()
        assertTrue(tools.contains("telegram_bridge"))
        assertTrue(tools.contains("file_manager"))
        assertFalse(tools.contains("calculator"))
    }

    // --- Classification includes reason ---

    @Test
    fun testClassificationIncludesReason() {
        val classification = classifier.classify("telegram_bridge")
        assertTrue(classification.reason.isNotEmpty())
        assertTrue(classification.reason.contains("Telegram"))
    }

    // --- Summary ---

    @Test
    fun testSummaryContainsExpectedKeys() {
        val summary = classifier.getSummary()
        assertTrue(summary.containsKey("safe_count"))
        assertTrue(summary.containsKey("approval_required"))
        assertTrue(summary.containsKey("owner_only"))
        assertTrue(summary.containsKey("blocked"))
        assertTrue(summary.containsKey("overrides"))
    }
}
