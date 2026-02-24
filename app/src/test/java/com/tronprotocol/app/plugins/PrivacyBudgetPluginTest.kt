package com.tronprotocol.app.plugins

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PrivacyBudgetPluginTest {

    private lateinit var plugin: PrivacyBudgetPlugin

    @Before
    fun setUp() {
        plugin = PrivacyBudgetPlugin()
        plugin.initialize(RuntimeEnvironment.getApplication())
    }

    // --- set_budget command ---

    @Test
    fun setBudget_setsDailyBudgetForValidCategory() {
        val result = plugin.execute("set_budget|location|100")
        assertTrue("set_budget should succeed for valid category", result.isSuccess)
        assertTrue(
            "Result should confirm budget was set",
            result.data?.contains("Budget set") == true
        )
    }

    @Test
    fun setBudget_setsForContacts() {
        val result = plugin.execute("set_budget|contacts|50")
        assertTrue(result.isSuccess)
    }

    @Test
    fun setBudget_setsForMessages() {
        val result = plugin.execute("set_budget|messages|75")
        assertTrue(result.isSuccess)
    }

    @Test
    fun setBudget_setsForCalls() {
        val result = plugin.execute("set_budget|calls|30")
        assertTrue(result.isSuccess)
    }

    @Test
    fun setBudget_setsForBrowsing() {
        val result = plugin.execute("set_budget|browsing|200")
        assertTrue(result.isSuccess)
    }

    @Test
    fun setBudget_setsForSensors() {
        val result = plugin.execute("set_budget|sensors|80")
        assertTrue(result.isSuccess)
    }

    @Test
    fun setBudget_rejectsInvalidCategory() {
        val result = plugin.execute("set_budget|invalid_category|100")
        assertFalse("set_budget should reject invalid category", result.isSuccess)
        assertTrue(
            "Error should mention invalid category",
            result.errorMessage?.contains("Invalid category") == true
        )
    }

    @Test
    fun setBudget_rejectsNonPositiveLimit() {
        val result = plugin.execute("set_budget|location|0")
        assertFalse("set_budget should reject zero limit", result.isSuccess)
        assertTrue(
            "Error should mention positive",
            result.errorMessage?.contains("positive") == true
        )
    }

    @Test
    fun setBudget_rejectsNegativeLimit() {
        val result = plugin.execute("set_budget|location|-10")
        assertFalse("set_budget should reject negative limit", result.isSuccess)
    }

    // --- spend command ---

    @Test
    fun spend_deductsFromBudget() {
        plugin.execute("set_budget|location|100")
        val result = plugin.execute("spend|location|25|GPS navigation")
        assertTrue("spend should succeed within budget", result.isSuccess)
        assertTrue(
            "Result should show spending details",
            result.data?.contains("Spent") == true
        )
        assertTrue(
            "Result should show remaining budget",
            result.data?.contains("Remaining") == true
        )
    }

    @Test
    fun spend_blocksWhenBudgetExceeded() {
        plugin.execute("set_budget|contacts|10")
        plugin.execute("spend|contacts|8|First access")

        val result = plugin.execute("spend|contacts|5|Second access")
        assertFalse("spend should be blocked when budget is exceeded", result.isSuccess)
        assertTrue(
            "Error should mention BLOCKED",
            result.errorMessage?.contains("BLOCKED") == true
        )
    }

    @Test
    fun spend_exactBudgetAllowed() {
        plugin.execute("set_budget|messages|50")
        val result = plugin.execute("spend|messages|50|Full spend")
        assertTrue("Spending exact budget amount should succeed", result.isSuccess)
    }

    @Test
    fun spend_multipleSpendingsAccumulate() {
        plugin.execute("set_budget|location|100")
        plugin.execute("spend|location|30|Trip 1")
        plugin.execute("spend|location|30|Trip 2")
        plugin.execute("spend|location|30|Trip 3")

        // Only 10 remaining, so 20 should fail
        val result = plugin.execute("spend|location|20|Trip 4")
        assertFalse("Should be blocked after cumulative spending exceeds budget", result.isSuccess)
    }

    // --- balance command ---

    @Test
    fun balance_showsRemainingBudget() {
        plugin.execute("set_budget|location|100")
        plugin.execute("spend|location|40|Test")

        val result = plugin.execute("balance|location")
        assertTrue(result.isSuccess)
        assertTrue(
            "Balance should show remaining amount",
            result.data?.contains("remaining") == true
        )
    }

    @Test
    fun balance_noBudgetSet() {
        val result = plugin.execute("balance|browsing")
        assertTrue(result.isSuccess)
        assertTrue(
            "Should indicate no budget set",
            result.data?.contains("No budget set") == true
        )
    }

    // --- report command ---

    @Test
    fun report_showsAllBudgets() {
        plugin.execute("set_budget|location|100")
        plugin.execute("set_budget|contacts|50")
        plugin.execute("spend|location|20|Test")

        val result = plugin.execute("report")
        assertTrue(result.isSuccess)
        assertTrue(
            "Report should include location",
            result.data?.contains("location") == true
        )
        assertTrue(
            "Report should include contacts",
            result.data?.contains("contacts") == true
        )
    }

    @Test
    fun report_showsNoBudgetsConfigured() {
        val result = plugin.execute("report")
        assertTrue(result.isSuccess)
        assertTrue(
            "Should indicate no budgets configured",
            result.data?.contains("No privacy budgets configured") == true
        )
    }

    // --- reset command ---

    @Test
    fun reset_clearsSpending() {
        plugin.execute("set_budget|location|100")
        plugin.execute("spend|location|80|Heavy usage")

        val resetResult = plugin.execute("reset")
        assertTrue(resetResult.isSuccess)
        assertTrue(
            "Reset should confirm clearing",
            resetResult.data?.contains("reset") == true
        )

        // After reset, spending should be 0 again
        val spendResult = plugin.execute("spend|location|80|After reset")
        assertTrue("Should be able to spend again after reset", spendResult.isSuccess)
    }

    // --- history command ---

    @Test
    fun history_showsSpendingEvents() {
        plugin.execute("set_budget|location|100")
        plugin.execute("spend|location|10|First check")
        plugin.execute("spend|location|20|Second check")

        val result = plugin.execute("history|location")
        assertTrue(result.isSuccess)
        assertTrue(
            "History should show spending entries",
            result.data?.contains("First check") == true
        )
        assertTrue(
            "History should show second entry",
            result.data?.contains("Second check") == true
        )
    }

    @Test
    fun history_emptyForCategoryWithNoSpending() {
        val result = plugin.execute("history|sensors")
        assertTrue(result.isSuccess)
        assertTrue(
            "Should indicate no spending history",
            result.data?.contains("No spending history") == true
        )
    }

    // --- alert_threshold command ---

    @Test
    fun alertThreshold_triggersAlertMessageWhenExceeded() {
        plugin.execute("set_budget|location|100")
        plugin.execute("alert_threshold|location|50")

        // Spend 60% of budget
        val result = plugin.execute("spend|location|60|Heavy tracking")
        assertTrue(result.isSuccess)
        assertTrue(
            "Alert should be triggered when threshold exceeded",
            result.data?.contains("ALERT") == true
        )
    }

    @Test
    fun alertThreshold_noAlertBelowThreshold() {
        plugin.execute("set_budget|contacts|100")
        plugin.execute("alert_threshold|contacts|80")

        // Spend 30% - well below 80% threshold
        val result = plugin.execute("spend|contacts|30|Small access")
        assertTrue(result.isSuccess)
        assertFalse(
            "No alert should be triggered below threshold",
            result.data?.contains("ALERT") == true
        )
    }

    @Test
    fun alertThreshold_rejectsInvalidCategory() {
        val result = plugin.execute("alert_threshold|invalid|50")
        assertFalse(result.isSuccess)
    }

    // --- spend without budget ---

    @Test
    fun spend_withoutBudgetReturnsError() {
        val result = plugin.execute("spend|location|10|Test")
        assertFalse("spend without budget should return error", result.isSuccess)
        assertTrue(
            "Error should mention no budget set",
            result.errorMessage?.contains("No budget set") == true
        )
    }

    // --- Plugin properties ---

    @Test
    fun pluginId_isPrivacyBudget() {
        assertEquals("privacy_budget", plugin.id)
    }

    @Test
    fun pluginName_isPrivacyBudget() {
        assertEquals("Privacy Budget", plugin.name)
    }

    @Test
    fun pluginIsEnabled_defaultTrue() {
        assertTrue(plugin.isEnabled)
    }

    @Test
    fun pluginDescription_isNotEmpty() {
        assertTrue(plugin.description.isNotEmpty())
    }
}
