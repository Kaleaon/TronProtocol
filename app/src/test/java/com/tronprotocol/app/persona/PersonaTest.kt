package com.tronprotocol.app.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaTest {

    @Test
    fun defaultPersonaHasCorrectFields() {
        val default = Persona.DEFAULT
        assertEquals("default_assistant", default.id)
        assertEquals("Assistant", default.name)
        assertTrue(default.isDefault)
        assertTrue(default.systemPrompt.isNotBlank())
        assertTrue(default.greeting.isNotBlank())
    }

    @Test
    fun defaultPersonasHaveNineEntries() {
        assertEquals(9, Persona.DEFAULTS.size)
    }

    @Test
    fun allDefaultPersonasHaveUniqueIds() {
        val ids = Persona.DEFAULTS.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun buildEffectiveSystemPromptCombinesFields() {
        val persona = Persona(
            id = "test",
            name = "Test",
            systemPrompt = "You are {{char}}.",
            description = "A test persona.",
            personality = "Test personality."
        )

        val prompt = persona.buildEffectiveSystemPrompt()
        assertTrue(prompt.contains("test persona"))
        assertTrue(prompt.contains("Test personality"))
    }

    @Test
    fun templateVariableSubstitution() {
        val result = Persona.applyTemplateVars(
            "Hello {{user}}, I'm {{char}}.",
            "Alice",
            "Bob"
        )
        assertEquals("Hello Alice, I'm Bob.", result)
    }

    @Test
    fun getSamplingProfileParseJson() {
        val persona = Persona(
            id = "test",
            name = "Test",
            samplingProfileJson = """{"temperature": 0.7, "top_p": 0.9}"""
        )

        val profile = persona.getSamplingProfile()
        assertNotNull(profile["temperature"])
        assertNotNull(profile["top_p"])
    }

    @Test
    fun getControlVectorsParseJson() {
        val persona = Persona(
            id = "test",
            name = "Test",
            controlVectorsJson = """{"warmth": 0.5, "energy": -0.3}"""
        )

        val vectors = persona.getControlVectors()
        assertEquals(0.5f, vectors["warmth"]!!, 0.01f)
        assertEquals(-0.3f, vectors["energy"]!!, 0.01f)
    }

    @Test
    fun emptySamplingProfileReturnsEmptyMap() {
        val persona = Persona(id = "test", name = "Test")
        assertTrue(persona.getSamplingProfile().isEmpty())
    }

    @Test
    fun invalidJsonReturnsSafeDefaults() {
        val persona = Persona(
            id = "test",
            name = "Test",
            samplingProfileJson = "not json",
            controlVectorsJson = "not json"
        )
        assertTrue(persona.getSamplingProfile().isEmpty())
        assertTrue(persona.getControlVectors().isEmpty())
    }
}
