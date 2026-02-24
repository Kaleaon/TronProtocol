package com.tronprotocol.app.selfmod

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ReflectionResultTest {

    private lateinit var result: ReflectionResult

    @Before
    fun setUp() {
        result = ReflectionResult()
    }

    // ---- initially empty insights and suggestions ----

    @Test
    fun initialState_insightsEmpty() {
        assertTrue(result.getInsights().isEmpty())
    }

    @Test
    fun initialState_suggestionsEmpty() {
        assertTrue(result.getSuggestions().isEmpty())
    }

    @Test
    fun initialState_hasInsightsIsFalse() {
        assertFalse(result.hasInsights())
    }

    @Test
    fun initialState_hasSuggestionsIsFalse() {
        assertFalse(result.hasSuggestions())
    }

    // ---- addInsight adds to list ----

    @Test
    fun addInsight_addsToList() {
        result.addInsight("Performance could be improved")
        assertEquals(1, result.getInsights().size)
        assertEquals("Performance could be improved", result.getInsights()[0])
    }

    @Test
    fun addInsight_hasInsightsBecomesTrue() {
        result.addInsight("test")
        assertTrue(result.hasInsights())
    }

    // ---- addSuggestion adds to list ----

    @Test
    fun addSuggestion_addsToList() {
        result.addSuggestion("Use caching for repeated queries")
        assertEquals(1, result.getSuggestions().size)
        assertEquals("Use caching for repeated queries", result.getSuggestions()[0])
    }

    @Test
    fun addSuggestion_hasSuggestionsBecomesTrue() {
        result.addSuggestion("test")
        assertTrue(result.hasSuggestions())
    }

    // ---- multiple insights preserved in order ----

    @Test
    fun multipleInsights_preservedInOrder() {
        result.addInsight("First insight")
        result.addInsight("Second insight")
        result.addInsight("Third insight")

        val insights = result.getInsights()
        assertEquals(3, insights.size)
        assertEquals("First insight", insights[0])
        assertEquals("Second insight", insights[1])
        assertEquals("Third insight", insights[2])
    }

    @Test
    fun multipleSuggestions_preservedInOrder() {
        result.addSuggestion("First suggestion")
        result.addSuggestion("Second suggestion")
        result.addSuggestion("Third suggestion")

        val suggestions = result.getSuggestions()
        assertEquals(3, suggestions.size)
        assertEquals("First suggestion", suggestions[0])
        assertEquals("Second suggestion", suggestions[1])
        assertEquals("Third suggestion", suggestions[2])
    }

    // ---- getInsights returns copy (modifying returned list doesn't affect internal) ----

    @Test
    fun getInsights_returnsCopy_modifyingDoesNotAffectInternal() {
        result.addInsight("Original insight")

        val returned = result.getInsights()
        // The returned list is an ArrayList copy, so modifications should not
        // affect the internal state.
        (returned as MutableList).add("Injected insight")

        // Internal state should still have only 1 insight
        assertEquals(1, result.getInsights().size)
        assertEquals("Original insight", result.getInsights()[0])
    }

    @Test
    fun getSuggestions_returnsCopy_modifyingDoesNotAffectInternal() {
        result.addSuggestion("Original suggestion")

        val returned = result.getSuggestions()
        (returned as MutableList).add("Injected suggestion")

        assertEquals(1, result.getSuggestions().size)
        assertEquals("Original suggestion", result.getSuggestions()[0])
    }

    @Test
    fun getInsights_returnsFreshCopyEachTime() {
        result.addInsight("insight A")
        val copy1 = result.getInsights()
        val copy2 = result.getInsights()
        assertNotSame(copy1, copy2)
        assertEquals(copy1, copy2)
    }

    // ---- Mixed insights and suggestions ----

    @Test
    fun insightsAndSuggestions_areIndependent() {
        result.addInsight("insight 1")
        result.addInsight("insight 2")
        result.addSuggestion("suggestion 1")

        assertEquals(2, result.getInsights().size)
        assertEquals(1, result.getSuggestions().size)
    }

    // ---- toString ----

    @Test
    fun toString_containsCounts() {
        result.addInsight("i1")
        result.addInsight("i2")
        result.addSuggestion("s1")

        val str = result.toString()
        assertTrue(str.contains("insights=2"))
        assertTrue(str.contains("suggestions=1"))
    }
}
