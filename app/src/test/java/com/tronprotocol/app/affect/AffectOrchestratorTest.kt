package com.tronprotocol.app.affect

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AffectOrchestratorTest {

    private lateinit var orchestrator: AffectOrchestrator

    @Before
    fun setUp() {
        orchestrator = AffectOrchestrator(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        if (orchestrator.isRunning()) {
            orchestrator.stop()
        }
    }

    @Test
    fun start_doesNotThrow() {
        orchestrator.start()
        assertTrue("Orchestrator should be running after start", orchestrator.isRunning())
    }

    @Test
    fun stop_doesNotThrow() {
        orchestrator.start()
        orchestrator.stop()
        assertFalse("Orchestrator should not be running after stop", orchestrator.isRunning())
    }

    @Test
    fun stopWithoutStart_doesNotThrow() {
        // Stopping without starting should not throw
        orchestrator.stop()
        assertFalse(orchestrator.isRunning())
    }

    @Test
    fun processConversationSentiment_acceptsMessage() {
        // processConversationSentiment is the conversation context ingestion method
        // It should not throw when called with a valid sentiment
        orchestrator.processConversationSentiment(0.5f, isPartnerMessage = false)
    }

    @Test
    fun processConversationSentiment_handlesPartnerMessage() {
        orchestrator.processConversationSentiment(0.8f, isPartnerMessage = true)
    }

    @Test
    fun processConversationSentiment_handlesNegativeSentiment() {
        orchestrator.processConversationSentiment(-0.7f, isPartnerMessage = false)
    }

    @Test
    fun getAffectSnapshot_returnsNonNullResult() {
        val snapshot = orchestrator.getAffectSnapshot()
        assertNotNull("getAffectSnapshot should return a non-null map", snapshot)
        assertTrue("Snapshot should contain affect_vector", snapshot.containsKey("affect_vector"))
        assertTrue("Snapshot should contain intensity", snapshot.containsKey("intensity"))
        assertTrue("Snapshot should contain zero_noise_state", snapshot.containsKey("zero_noise_state"))
    }

    @Test
    fun getCurrentState_returnsNonNullAffectState() {
        val state = orchestrator.getCurrentState()
        assertNotNull("getCurrentState should return a non-null AffectState", state)
    }

    @Test
    fun submitInput_acceptsAffectInput() {
        val input = AffectInput.builder("test:orchestrator")
            .valence(0.4f)
            .arousal(0.2f)
            .build()

        orchestrator.submitInput(input)
    }

    @Test
    fun processMemRLRetrieval_acceptsClusterTypes() {
        // Should not throw for any valid cluster type
        orchestrator.processMemRLRetrieval("attachment", 0.5f)
        orchestrator.processMemRLRetrieval("threat", 0.7f)
        orchestrator.processMemRLRetrieval("novelty", 0.3f)
        orchestrator.processMemRLRetrieval("achievement", 0.6f)
        orchestrator.processMemRLRetrieval("unknown_cluster", 0.1f)
    }

    @Test
    fun processGoalState_handlesBlockedAndAchieved() {
        orchestrator.processGoalState(blocked = true)
        orchestrator.processGoalState(blocked = false)
    }

    @Test
    fun processSelfModProposal_doesNotThrow() {
        orchestrator.processSelfModProposal()
    }

    @Test
    fun getStats_returnsNonEmptyMap() {
        val stats = orchestrator.getStats()
        assertNotNull(stats)
        assertTrue("Stats should contain 'engine' key", stats.containsKey("engine"))
        assertTrue("Stats should contain 'log' key", stats.containsKey("log"))
    }

    @Test
    fun recordPartnerInput_doesNotThrow() {
        orchestrator.recordPartnerInput()
    }
}
