package com.tronprotocol.app.affect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AffectLogEntryTest {

    private fun createSampleEntry(
        timestamp: String = "2026-02-24T12:00:00.000Z",
        hash: String = "abc123def456",
        immutable: Boolean = true
    ): AffectLogEntry = AffectLogEntry(
        timestamp = timestamp,
        affectVector = mapOf(
            "valence" to 0.3f,
            "arousal" to 0.5f,
            "coherence" to 0.7f
        ),
        inputSources = listOf("conversation:sentiment", "memrl:attachment_cluster"),
        expressionCommands = mapOf(
            "ears" to "forward_alert",
            "tail" to "wagging",
            "voice" to "warm_higher_faster_stable"
        ),
        motorNoiseLevel = 0.15f,
        noiseDistribution = mapOf(
            "voice_pitch" to 0.12f,
            "ear_servos" to 0.18f
        ),
        hash = hash,
        immutable = immutable
    )

    @Test
    fun creation_withAllFields() {
        val entry = createSampleEntry()

        assertEquals("2026-02-24T12:00:00.000Z", entry.timestamp)
        assertEquals(3, entry.affectVector.size)
        assertEquals(0.3f, entry.affectVector["valence"]!!, 0.0001f)
        assertEquals(0.5f, entry.affectVector["arousal"]!!, 0.0001f)
        assertEquals(0.7f, entry.affectVector["coherence"]!!, 0.0001f)
        assertEquals(2, entry.inputSources.size)
        assertEquals("conversation:sentiment", entry.inputSources[0])
        assertEquals("memrl:attachment_cluster", entry.inputSources[1])
        assertEquals(3, entry.expressionCommands.size)
        assertEquals("forward_alert", entry.expressionCommands["ears"])
        assertEquals(0.15f, entry.motorNoiseLevel, 0.0001f)
        assertEquals(2, entry.noiseDistribution.size)
        assertEquals("abc123def456", entry.hash)
        assertTrue(entry.immutable)
    }

    @Test
    fun toJson_serialization() {
        val entry = createSampleEntry()
        val json = entry.toJson()

        assertNotNull(json)
        assertEquals("2026-02-24T12:00:00.000Z", json.getString("timestamp"))
        assertEquals("abc123def456", json.getString("hash"))
        assertTrue(json.getBoolean("immutable"))

        // Check affect_vector
        val vectorObj = json.getJSONObject("affect_vector")
        assertEquals(0.3, vectorObj.getDouble("valence"), 0.001)
        assertEquals(0.5, vectorObj.getDouble("arousal"), 0.001)
        assertEquals(0.7, vectorObj.getDouble("coherence"), 0.001)

        // Check input_sources
        val sourcesArray = json.getJSONArray("input_sources")
        assertEquals(2, sourcesArray.length())
        assertEquals("conversation:sentiment", sourcesArray.getString(0))

        // Check expression_commands
        val cmdsObj = json.getJSONObject("expression_commands")
        assertEquals("forward_alert", cmdsObj.getString("ears"))
        assertEquals("wagging", cmdsObj.getString("tail"))

        // Check motor_noise_level
        assertEquals(0.15, json.getDouble("motor_noise_level"), 0.001)

        // Check noise_distribution
        val noiseObj = json.getJSONObject("noise_distribution")
        assertEquals(0.12, noiseObj.getDouble("voice_pitch"), 0.001)
    }

    @Test
    fun fromJson_deserialization_roundTrip() {
        val original = createSampleEntry()
        val json = original.toJson()

        val deserialized = AffectLogEntry.fromJson(json)

        assertEquals(original.timestamp, deserialized.timestamp)
        assertEquals(original.hash, deserialized.hash)
        assertEquals(original.immutable, deserialized.immutable)
        assertEquals(original.motorNoiseLevel, deserialized.motorNoiseLevel, 0.001f)

        // Verify affect vector round-trip
        for ((key, value) in original.affectVector) {
            assertEquals(
                "Affect vector key $key should match",
                value, deserialized.affectVector[key]!!, 0.001f
            )
        }

        // Verify input sources round-trip
        assertEquals(original.inputSources.size, deserialized.inputSources.size)
        for (i in original.inputSources.indices) {
            assertEquals(original.inputSources[i], deserialized.inputSources[i])
        }

        // Verify expression commands round-trip
        for ((key, value) in original.expressionCommands) {
            assertEquals(
                "Expression command $key should match",
                value, deserialized.expressionCommands[key]
            )
        }

        // Verify noise distribution round-trip
        for ((key, value) in original.noiseDistribution) {
            assertEquals(
                "Noise distribution key $key should match",
                value, deserialized.noiseDistribution[key]!!, 0.001f
            )
        }
    }

    @Test
    fun immutable_flagDefaultsToTrue() {
        val entry = AffectLogEntry(
            timestamp = "2026-01-01T00:00:00.000Z",
            affectVector = emptyMap(),
            inputSources = emptyList(),
            expressionCommands = emptyMap(),
            motorNoiseLevel = 0.0f,
            noiseDistribution = emptyMap(),
            hash = "somehash"
            // immutable defaults to true
        )

        assertTrue("Immutable flag should default to true", entry.immutable)
    }

    @Test
    fun previousHash_forChainLinkage() {
        // Create two entries with different hashes to simulate chain linkage
        val entry1 = createSampleEntry(hash = "hash_of_genesis_plus_entry1")
        val entry2 = createSampleEntry(
            timestamp = "2026-02-24T12:00:01.000Z",
            hash = "hash_of_entry1_plus_entry2"
        )

        // Hashes should be different
        assertTrue(
            "Different entries should have different hashes for chain linkage",
            entry1.hash != entry2.hash
        )

        // Both hashes should be non-empty
        assertTrue("Entry 1 hash should be non-empty", entry1.hash.isNotEmpty())
        assertTrue("Entry 2 hash should be non-empty", entry2.hash.isNotEmpty())
    }

    @Test
    fun fromJson_handlesEmptyOptionalFields() {
        // Create a minimal JSON with only required fields
        val json = org.json.JSONObject().apply {
            put("timestamp", "2026-02-24T00:00:00.000Z")
            put("affect_vector", org.json.JSONObject())
            put("hash", "minimal_hash")
        }

        val entry = AffectLogEntry.fromJson(json)
        assertEquals("2026-02-24T00:00:00.000Z", entry.timestamp)
        assertEquals("minimal_hash", entry.hash)
        assertTrue(entry.inputSources.isEmpty())
        assertTrue(entry.expressionCommands.isEmpty())
        assertTrue(entry.noiseDistribution.isEmpty())
        assertEquals(0.0f, entry.motorNoiseLevel, 0.0001f)
        assertTrue(entry.immutable)
    }

    @Test
    fun dataClass_equality() {
        val entry1 = createSampleEntry()
        val entry2 = createSampleEntry()

        assertEquals("Identical entries should be equal", entry1, entry2)
        assertEquals("Identical entries should have same hashCode", entry1.hashCode(), entry2.hashCode())
    }

    @Test
    fun dataClass_copy() {
        val original = createSampleEntry()
        val modified = original.copy(timestamp = "2026-03-01T00:00:00.000Z")

        assertEquals("2026-03-01T00:00:00.000Z", modified.timestamp)
        assertEquals(original.hash, modified.hash)
        assertEquals(original.affectVector, modified.affectVector)
    }
}
