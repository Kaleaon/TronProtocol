package com.tronprotocol.app.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaCardConverterTest {

    @Test
    fun exportToTavernV2ProducesValidJson() {
        val persona = Persona(
            id = "test",
            name = "TestChar",
            systemPrompt = "You are a test character.",
            greeting = "Hello!",
            description = "A test description.",
            personality = "Friendly.",
            scenario = "Testing scenario.",
            tags = listOf("test", "demo")
        )

        val json = PersonaCardConverter.exportToTavernV2(persona)
        assertTrue(json.contains("chara_card_v2"))
        assertTrue(json.contains("TestChar"))
        assertTrue(json.contains("A test description."))
    }

    @Test
    fun importV2RoundTrips() {
        val original = Persona(
            id = "test",
            name = "RoundTrip",
            systemPrompt = "System prompt here.",
            greeting = "Hi!",
            description = "Description here.",
            personality = "Personality here.",
            tags = listOf("tag1", "tag2")
        )

        val json = PersonaCardConverter.exportToTavernV2(original)
        val imported = PersonaCardConverter.importFromJson(json, "test_import")

        assertNotNull(imported)
        assertEquals("RoundTrip", imported!!.name)
        assertEquals("System prompt here.", imported.systemPrompt)
        assertEquals("Hi!", imported.greeting)
        assertEquals("Description here.", imported.description)
        assertEquals("Personality here.", imported.personality)
    }

    @Test
    fun importV1CardWorks() {
        val v1Json = """{"name": "V1Char", "char_persona": "I am V1.", "char_greeting": "Hello from V1!", "description": "V1 desc", "personality": "V1 personality"}"""
        val persona = PersonaCardConverter.importFromJson(v1Json)

        assertNotNull(persona)
        assertEquals("V1Char", persona!!.name)
        assertEquals("I am V1.", persona.systemPrompt)
        assertEquals("Hello from V1!", persona.greeting)
    }

    @Test
    fun importInvalidJsonReturnsNull() {
        assertNull(PersonaCardConverter.importFromJson("not json"))
        assertNull(PersonaCardConverter.importFromJson(""))
    }

    @Test
    fun extensionsPreserveSamplingAndControlVectors() {
        val original = Persona(
            id = "ext_test",
            name = "ExtTest",
            samplingProfileJson = """{"temperature": 0.8}""",
            controlVectorsJson = """{"warmth": 0.5}"""
        )

        val json = PersonaCardConverter.exportToTavernV2(original)
        val imported = PersonaCardConverter.importFromJson(json, "ext_test_import")

        assertNotNull(imported)
        assertTrue(imported!!.samplingProfileJson.contains("0.8"))
        assertTrue(imported.controlVectorsJson.contains("0.5"))
    }
}
