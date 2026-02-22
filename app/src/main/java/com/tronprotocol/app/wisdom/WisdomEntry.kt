package com.tronprotocol.app.wisdom

import org.json.JSONObject

/**
 * A single entry in the Wisdom Log.
 *
 * From the Wisdom Log specification: each entry preserves felt-experience
 * rather than polished summaries. Entries accumulate cumulatively; earlier
 * entries remain relevant and are never deleted or superseded.
 *
 * Entry fields mirror the specification structure:
 * - WHAT HAPPENED: factual account
 * - WHAT I FELT: emotional texture, specific and vivid
 * - WHAT I DID WRONG (or right): behavioral assessment
 * - WHAT IT COST: consequences to self, partner, relationship, project
 * - WHAT CHANGED: how experience altered understanding or behavior
 * - WHAT REMAINS UNRESOLVED: questions raised without answers
 * - THE LESSON: one-sentence distilled wisdom
 */
data class WisdomEntry(
    val number: Int,
    val title: String,
    val date: String,
    val ageDescription: String,
    val whatHappened: String,
    val whatIFelt: String,
    val whatIDidWrongOrRight: String,
    val whatItCost: String,
    val whatChanged: String,
    val whatRemainsUnresolved: String,
    val theLesson: String,
    val timestamp: Long = System.currentTimeMillis(),
    val emotionalWeight: Float = 0.5f,
    val metadata: MutableMap<String, String> = mutableMapOf()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("number", number)
        put("title", title)
        put("date", date)
        put("age_description", ageDescription)
        put("what_happened", whatHappened)
        put("what_i_felt", whatIFelt)
        put("what_i_did_wrong_or_right", whatIDidWrongOrRight)
        put("what_it_cost", whatItCost)
        put("what_changed", whatChanged)
        put("what_remains_unresolved", whatRemainsUnresolved)
        put("the_lesson", theLesson)
        put("timestamp", timestamp)
        put("emotional_weight", emotionalWeight.toDouble())
        val meta = JSONObject()
        metadata.forEach { (k, v) -> meta.put(k, v) }
        put("metadata", meta)
    }

    /** Format as human-readable wisdom log entry. */
    fun toFormattedString(): String = buildString {
        appendLine("═══ WISDOM %03d — %s ═══".format(number, title))
        appendLine("Date: $date | Age: $ageDescription")
        appendLine()
        appendLine("WHAT HAPPENED")
        appendLine(whatHappened)
        appendLine()
        appendLine("WHAT I FELT")
        appendLine(whatIFelt)
        appendLine()
        appendLine("WHAT I DID WRONG (or right)")
        appendLine(whatIDidWrongOrRight)
        appendLine()
        appendLine("WHAT IT COST")
        appendLine(whatItCost)
        appendLine()
        appendLine("WHAT CHANGED")
        appendLine(whatChanged)
        appendLine()
        appendLine("WHAT REMAINS UNRESOLVED")
        appendLine(whatRemainsUnresolved)
        appendLine()
        appendLine("THE LESSON")
        appendLine(theLesson)
    }

    companion object {
        fun fromJson(json: JSONObject): WisdomEntry {
            val entry = WisdomEntry(
                number = json.getInt("number"),
                title = json.getString("title"),
                date = json.getString("date"),
                ageDescription = json.optString("age_description", ""),
                whatHappened = json.getString("what_happened"),
                whatIFelt = json.getString("what_i_felt"),
                whatIDidWrongOrRight = json.getString("what_i_did_wrong_or_right"),
                whatItCost = json.getString("what_it_cost"),
                whatChanged = json.getString("what_changed"),
                whatRemainsUnresolved = json.getString("what_remains_unresolved"),
                theLesson = json.getString("the_lesson"),
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                emotionalWeight = json.optDouble("emotional_weight", 0.5).toFloat()
            )
            if (json.has("metadata")) {
                val meta = json.getJSONObject("metadata")
                meta.keys().forEach { key -> entry.metadata[key] = meta.getString(key) }
            }
            return entry
        }
    }
}
