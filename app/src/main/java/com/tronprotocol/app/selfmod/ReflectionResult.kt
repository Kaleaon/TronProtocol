package com.tronprotocol.app.selfmod

/**
 * Result of self-reflection on behavior
 */
class ReflectionResult {
    private val insights = mutableListOf<String>()
    private val suggestions = mutableListOf<String>()

    fun addInsight(insight: String) {
        insights.add(insight)
    }

    fun addSuggestion(suggestion: String) {
        suggestions.add(suggestion)
    }

    fun getInsights(): List<String> = ArrayList(insights)

    fun getSuggestions(): List<String> = ArrayList(suggestions)

    fun hasInsights(): Boolean = insights.isNotEmpty()

    fun hasSuggestions(): Boolean = suggestions.isNotEmpty()

    override fun toString(): String {
        return "ReflectionResult{" +
                "insights=${insights.size}" +
                ", suggestions=${suggestions.size}" +
                "}"
    }
}
