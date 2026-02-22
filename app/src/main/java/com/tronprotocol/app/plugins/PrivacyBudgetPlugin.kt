package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Privacy Budget Plugin - Phase 8 Safety & Transparency.
 *
 * Tracks and limits privacy-sensitive data access with a budget system.
 * Each privacy category (location, contacts, messages, calls, browsing,
 * sensors) has a daily budget.  Every access "spends" from the budget;
 * once depleted the plugin blocks further access until the daily reset.
 *
 * Commands:
 *   set_budget|category|daily_limit          - Set the daily budget for a category
 *   spend|category|amount|reason             - Deduct from a category budget
 *   balance|category                         - Show remaining budget for a category
 *   report                                   - Show all category budgets and balances
 *   reset                                    - Reset all daily spending to zero
 *   history|category                         - Show spending history for a category
 *   alert_threshold|category|threshold_pct   - Set alert threshold percentage
 */
class PrivacyBudgetPlugin : Plugin {

    companion object {
        private const val ID = "privacy_budget"
        private const val PREFS_NAME = "tronprotocol_privacy_budget"
        private const val KEY_BUDGETS = "budgets"
        private const val KEY_SPENDING = "spending"
        private const val KEY_HISTORY = "spending_history"
        private const val KEY_THRESHOLDS = "alert_thresholds"
        private const val KEY_LAST_RESET = "last_reset_day"
        private val VALID_CATEGORIES = setOf(
            "location", "contacts", "messages", "calls", "browsing", "sensors"
        )
    }

    private lateinit var prefs: SharedPreferences
    private val budgets = mutableMapOf<String, Double>()
    private val spending = mutableMapOf<String, Double>()
    private val spendingHistory = mutableListOf<JSONObject>()
    private val alertThresholds = mutableMapOf<String, Int>()

    override val id: String = ID
    override val name: String = "Privacy Budget"
    override val description: String =
        "Privacy budget tracker. Commands: set_budget|category|daily_limit, " +
            "spend|category|amount|reason, balance|category, report, reset, " +
            "history|category, alert_threshold|category|threshold_pct"
    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            checkDailyReset()
            val parts = input.split("\\|".toRegex(), 4)
            val command = parts[0].trim().lowercase()

            when (command) {
                "set_budget" -> handleSetBudget(parts, start)
                "spend" -> handleSpend(parts, start)
                "balance" -> handleBalance(parts, start)
                "report" -> handleReport(start)
                "reset" -> handleReset(start)
                "history" -> handleHistory(parts, start)
                "alert_threshold" -> handleAlertThreshold(parts, start)
                else -> PluginResult.error(
                    "Unknown command '$command'. Use: set_budget, spend, balance, report, reset, history, alert_threshold",
                    elapsed(start)
                )
            }
        } catch (e: Exception) {
            PluginResult.error("Privacy budget error: ${e.message}", elapsed(start))
        }
    }

    private fun handleSetBudget(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 3) {
            return PluginResult.error("Usage: set_budget|category|daily_limit", elapsed(start))
        }
        val category = parts[1].trim().lowercase()
        if (category !in VALID_CATEGORIES) {
            return PluginResult.error(
                "Invalid category '$category'. Valid: ${VALID_CATEGORIES.joinToString(", ")}",
                elapsed(start)
            )
        }
        val limit = parts[2].trim().toDoubleOrNull()
            ?: return PluginResult.error("Daily limit must be a number.", elapsed(start))
        if (limit <= 0) {
            return PluginResult.error("Daily limit must be positive.", elapsed(start))
        }

        budgets[category] = limit
        if (!spending.containsKey(category)) {
            spending[category] = 0.0
        }
        save()
        return PluginResult.success(
            "Budget set: $category = $limit per day.",
            elapsed(start)
        )
    }

    private fun handleSpend(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 4) {
            return PluginResult.error("Usage: spend|category|amount|reason", elapsed(start))
        }
        val category = parts[1].trim().lowercase()
        if (category !in VALID_CATEGORIES) {
            return PluginResult.error(
                "Invalid category '$category'. Valid: ${VALID_CATEGORIES.joinToString(", ")}",
                elapsed(start)
            )
        }
        val amount = parts[2].trim().toDoubleOrNull()
            ?: return PluginResult.error("Amount must be a number.", elapsed(start))
        if (amount <= 0) {
            return PluginResult.error("Amount must be positive.", elapsed(start))
        }
        val reason = parts[3].trim()

        val budget = budgets[category]
            ?: return PluginResult.error(
                "No budget set for '$category'. Use set_budget first.",
                elapsed(start)
            )

        val currentSpending = spending.getOrDefault(category, 0.0)
        val remaining = budget - currentSpending

        if (amount > remaining) {
            addHistory(category, amount, reason, blocked = true)
            save()
            return PluginResult.error(
                "BLOCKED: Budget exceeded for '$category'. " +
                    "Remaining: ${"%.2f".format(remaining)}/${"%.2f".format(budget)}. " +
                    "Requested: ${"%.2f".format(amount)}.",
                elapsed(start)
            )
        }

        spending[category] = currentSpending + amount
        addHistory(category, amount, reason, blocked = false)
        save()

        val newRemaining = budget - spending[category]!!
        val thresholdPct = alertThresholds[category]
        val alertMsg = if (thresholdPct != null) {
            val usedPct = ((spending[category]!! / budget) * 100).toInt()
            if (usedPct >= thresholdPct) {
                " ALERT: ${usedPct}% of budget used (threshold: ${thresholdPct}%)."
            } else ""
        } else ""

        return PluginResult.success(
            "Spent ${"%.2f".format(amount)} from '$category' for: $reason. " +
                "Remaining: ${"%.2f".format(newRemaining)}/${"%.2f".format(budget)}.$alertMsg",
            elapsed(start)
        )
    }

    private fun handleBalance(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].trim().isEmpty()) {
            return PluginResult.error("Usage: balance|category", elapsed(start))
        }
        val category = parts[1].trim().lowercase()
        val budget = budgets[category]
            ?: return PluginResult.success(
                "No budget set for '$category'.",
                elapsed(start)
            )
        val spent = spending.getOrDefault(category, 0.0)
        val remaining = budget - spent
        val usedPct = if (budget > 0) ((spent / budget) * 100).toInt() else 0

        return PluginResult.success(
            "Budget for '$category': ${"%.2f".format(remaining)}/${"%.2f".format(budget)} remaining ($usedPct% used).",
            elapsed(start)
        )
    }

    private fun handleReport(start: Long): PluginResult {
        if (budgets.isEmpty()) {
            return PluginResult.success("No privacy budgets configured.", elapsed(start))
        }
        val sb = buildString {
            append("Privacy Budget Report:\n")
            VALID_CATEGORIES.forEach { category ->
                val budget = budgets[category]
                if (budget != null) {
                    val spent = spending.getOrDefault(category, 0.0)
                    val remaining = budget - spent
                    val usedPct = if (budget > 0) ((spent / budget) * 100).toInt() else 0
                    val threshold = alertThresholds[category]
                    val alert = if (threshold != null && usedPct >= threshold) " [ALERT]" else ""
                    append("  $category: ${"%.2f".format(remaining)}/${"%.2f".format(budget)} remaining ($usedPct% used)$alert\n")
                } else {
                    append("  $category: no budget set\n")
                }
            }
            append("Total spending history entries: ${spendingHistory.size}")
        }
        return PluginResult.success(sb, elapsed(start))
    }

    private fun handleReset(start: Long): PluginResult {
        val totalSpent = spending.values.sum()
        spending.keys.forEach { spending[it] = 0.0 }
        prefs.edit().putInt(KEY_LAST_RESET, currentDay()).apply()
        save()
        return PluginResult.success(
            "Daily budgets reset. Total spending cleared: ${"%.2f".format(totalSpent)}.",
            elapsed(start)
        )
    }

    private fun handleHistory(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].trim().isEmpty()) {
            return PluginResult.error("Usage: history|category", elapsed(start))
        }
        val category = parts[1].trim().lowercase()
        val filtered = spendingHistory.filter { it.optString("category") == category }
        if (filtered.isEmpty()) {
            return PluginResult.success("No spending history for '$category'.", elapsed(start))
        }
        val sb = buildString {
            append("Spending history for '$category' (${filtered.size} entries):\n")
            filtered.forEachIndexed { i, entry ->
                val blocked = if (entry.optBoolean("blocked")) " [BLOCKED]" else ""
                append("${i + 1}. ${"%.2f".format(entry.optDouble("amount"))} - ")
                append("${entry.optString("reason")}$blocked ")
                append("(${formatTimestamp(entry.optLong("timestamp"))})\n")
            }
        }
        return PluginResult.success(sb.trimEnd().toString(), elapsed(start))
    }

    private fun handleAlertThreshold(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 3) {
            return PluginResult.error(
                "Usage: alert_threshold|category|threshold_pct",
                elapsed(start)
            )
        }
        val category = parts[1].trim().lowercase()
        if (category !in VALID_CATEGORIES) {
            return PluginResult.error(
                "Invalid category '$category'. Valid: ${VALID_CATEGORIES.joinToString(", ")}",
                elapsed(start)
            )
        }
        val pct = parts[2].trim().toIntOrNull()
            ?: return PluginResult.error("Threshold must be an integer (1-100).", elapsed(start))
        if (pct < 1 || pct > 100) {
            return PluginResult.error("Threshold must be between 1 and 100.", elapsed(start))
        }
        alertThresholds[category] = pct
        save()
        return PluginResult.success(
            "Alert threshold for '$category' set to $pct%.",
            elapsed(start)
        )
    }

    private fun addHistory(category: String, amount: Double, reason: String, blocked: Boolean) {
        spendingHistory.add(JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("category", category)
            put("amount", amount)
            put("reason", reason)
            put("blocked", blocked)
        })
    }

    private fun checkDailyReset() {
        val lastReset = prefs.getInt(KEY_LAST_RESET, 0)
        val today = currentDay()
        if (lastReset != today) {
            spending.keys.forEach { spending[it] = 0.0 }
            prefs.edit().putInt(KEY_LAST_RESET, today).apply()
            save()
        }
    }

    private fun currentDay(): Int {
        val cal = java.util.Calendar.getInstance()
        return cal.get(java.util.Calendar.YEAR) * 1000 + cal.get(java.util.Calendar.DAY_OF_YEAR)
    }

    private fun formatTimestamp(ts: Long): String {
        if (ts == 0L) return "unknown"
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        return sdf.format(java.util.Date(ts))
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    private fun save() {
        val budgetsObj = JSONObject()
        budgets.forEach { (k, v) -> budgetsObj.put(k, v) }
        val spendingObj = JSONObject()
        spending.forEach { (k, v) -> spendingObj.put(k, v) }
        val historyArr = JSONArray()
        spendingHistory.forEach { historyArr.put(it) }
        val thresholdsObj = JSONObject()
        alertThresholds.forEach { (k, v) -> thresholdsObj.put(k, v) }

        prefs.edit()
            .putString(KEY_BUDGETS, budgetsObj.toString())
            .putString(KEY_SPENDING, spendingObj.toString())
            .putString(KEY_HISTORY, historyArr.toString())
            .putString(KEY_THRESHOLDS, thresholdsObj.toString())
            .apply()
    }

    private fun load() {
        val budgetsData = prefs.getString(KEY_BUDGETS, null)
        if (budgetsData != null) {
            try {
                val obj = JSONObject(budgetsData)
                budgets.clear()
                obj.keys().forEach { key -> budgets[key] = obj.getDouble(key) }
            } catch (_: Exception) { }
        }

        val spendingData = prefs.getString(KEY_SPENDING, null)
        if (spendingData != null) {
            try {
                val obj = JSONObject(spendingData)
                spending.clear()
                obj.keys().forEach { key -> spending[key] = obj.getDouble(key) }
            } catch (_: Exception) { }
        }

        val historyData = prefs.getString(KEY_HISTORY, null)
        if (historyData != null) {
            try {
                val arr = JSONArray(historyData)
                spendingHistory.clear()
                for (i in 0 until arr.length()) {
                    spendingHistory.add(arr.getJSONObject(i))
                }
            } catch (_: Exception) { }
        }

        val thresholdsData = prefs.getString(KEY_THRESHOLDS, null)
        if (thresholdsData != null) {
            try {
                val obj = JSONObject(thresholdsData)
                alertThresholds.clear()
                obj.keys().forEach { key -> alertThresholds[key] = obj.getInt(key) }
            } catch (_: Exception) { }
        }
    }

    override fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        load()
        checkDailyReset()
    }

    override fun destroy() {
        budgets.clear()
        spending.clear()
        spendingHistory.clear()
        alertThresholds.clear()
    }
}
