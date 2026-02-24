package com.tronprotocol.app.affect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ImmutableAffectLogTest {

    private lateinit var log: ImmutableAffectLog

    @Before
    fun setUp() {
        log = ImmutableAffectLog(RuntimeEnvironment.getApplication())
    }

    /**
     * Helper to create a default AffectState for test appends.
     */
    private fun createDefaultState(): AffectState = AffectState()

    /**
     * Helper to create a default NoiseResult for test appends.
     */
    private fun createDefaultNoiseResult(): MotorNoise.NoiseResult {
        val zeroMap = MotorNoise.MotorChannel.entries.associateWith { 0.1f }
        return MotorNoise.NoiseResult(
            channelNoise = zeroMap,
            channelJitter = zeroMap,
            overallNoiseLevel = 0.1f,
            affectIntensity = 0.5f,
            coherence = 0.7f,
            isZeroNoise = false
        )
    }

    @Test
    fun appendIncreasesEntryCount() {
        assertEquals(0L, log.getEntryCount())

        log.append(
            affectState = createDefaultState(),
            inputSources = listOf("test:source"),
            expressionCommands = mapOf("ears" to "neutral"),
            noiseResult = createDefaultNoiseResult()
        )

        assertEquals(1L, log.getEntryCount())
    }

    @Test
    fun getRecentEntries_returnsAppendedEntries() {
        log.append(
            affectState = createDefaultState(),
            inputSources = listOf("test:first"),
            expressionCommands = mapOf("ears" to "forward"),
            noiseResult = createDefaultNoiseResult()
        )

        val entries = log.getRecentEntries(10)
        assertEquals("Should have 1 recent entry", 1, entries.size)
        assertTrue(
            "Entry should contain the input source",
            entries[0].inputSources.contains("test:first")
        )
    }

    @Test
    fun verifyRecentIntegrity_returnsTrueForValidLog() {
        log.append(
            affectState = createDefaultState(),
            inputSources = listOf("test:a"),
            expressionCommands = mapOf("ears" to "neutral"),
            noiseResult = createDefaultNoiseResult()
        )
        log.append(
            affectState = createDefaultState(),
            inputSources = listOf("test:b"),
            expressionCommands = mapOf("ears" to "forward"),
            noiseResult = createDefaultNoiseResult()
        )

        assertTrue("Integrity should be valid for untampered log", log.verifyRecentIntegrity())
    }

    @Test
    fun emptyLog_hasEntryCountZero() {
        assertEquals("Empty log should have entry count 0", 0L, log.getEntryCount())
    }

    @Test
    fun emptyLog_verifyRecentIntegrityReturnsTrue() {
        assertTrue("Empty log should pass integrity check", log.verifyRecentIntegrity())
    }

    @Test
    fun appendMultipleEntries_preservesOrder() {
        val sources = listOf("test:first", "test:second", "test:third")

        for (source in sources) {
            log.append(
                affectState = createDefaultState(),
                inputSources = listOf(source),
                expressionCommands = mapOf("ears" to "neutral"),
                noiseResult = createDefaultNoiseResult()
            )
        }

        assertEquals(3L, log.getEntryCount())

        val entries = log.getRecentEntries(10)
        assertEquals(3, entries.size)

        // Entries should be in insertion order
        assertTrue(entries[0].inputSources.contains("test:first"))
        assertTrue(entries[1].inputSources.contains("test:second"))
        assertTrue(entries[2].inputSources.contains("test:third"))
    }

    @Test
    fun hashChain_linksEntries() {
        log.append(
            affectState = createDefaultState(),
            inputSources = listOf("test:entry1"),
            expressionCommands = mapOf("ears" to "neutral"),
            noiseResult = createDefaultNoiseResult()
        )
        log.append(
            affectState = createDefaultState(),
            inputSources = listOf("test:entry2"),
            expressionCommands = mapOf("ears" to "forward"),
            noiseResult = createDefaultNoiseResult()
        )

        val entries = log.getRecentEntries(10)
        assertEquals(2, entries.size)

        // Each entry should have a non-empty hash
        assertTrue("First entry should have a hash", entries[0].hash.isNotEmpty())
        assertTrue("Second entry should have a hash", entries[1].hash.isNotEmpty())

        // Hashes should be different (different content)
        assertTrue(
            "Entries should have different hashes",
            entries[0].hash != entries[1].hash
        )

        // Chain head hash should match the last entry's hash
        assertEquals(
            "Chain head hash should equal the last entry's hash",
            entries[1].hash, log.getChainHeadHash()
        )
    }

    @Test
    fun getRecentEntries_respectsCount() {
        for (i in 1..5) {
            log.append(
                affectState = createDefaultState(),
                inputSources = listOf("test:entry$i"),
                expressionCommands = mapOf("ears" to "neutral"),
                noiseResult = createDefaultNoiseResult()
            )
        }

        val twoEntries = log.getRecentEntries(2)
        assertEquals("Should return only 2 entries", 2, twoEntries.size)

        // Should return the last 2 entries
        assertTrue(twoEntries[0].inputSources.contains("test:entry4"))
        assertTrue(twoEntries[1].inputSources.contains("test:entry5"))
    }

    @Test
    fun entries_areMarkedImmutable() {
        log.append(
            affectState = createDefaultState(),
            inputSources = listOf("test:immutable"),
            expressionCommands = mapOf("ears" to "neutral"),
            noiseResult = createDefaultNoiseResult()
        )

        val entries = log.getRecentEntries(1)
        assertTrue("Entry should be marked immutable", entries[0].immutable)
    }

    @Test
    fun getStats_returnsExpectedKeys() {
        val stats = log.getStats()
        assertTrue(stats.containsKey("entry_count"))
        assertTrue(stats.containsKey("recent_buffer_size"))
        assertTrue(stats.containsKey("chain_head_hash"))
        assertTrue(stats.containsKey("integrity_valid"))
    }
}
