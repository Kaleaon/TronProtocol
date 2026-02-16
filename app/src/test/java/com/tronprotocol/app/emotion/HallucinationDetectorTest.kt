package com.tronprotocol.app.emotion

import com.tronprotocol.app.frontier.FrontierDynamicsManager
import com.tronprotocol.app.rag.RAGStore
import com.tronprotocol.app.rag.RetrievalStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HallucinationDetectorTest {

    @Test
    fun detectHallucination_handlesFrontierRetrievalExceptions() {
        val emotionalManager = mock(EmotionalStateManager::class.java)
        val ragStore = mock(RAGStore::class.java)
        val frontierDynamicsManager = mock(FrontierDynamicsManager::class.java)

        `when`(emotionalManager.getEmotionalBias()).thenReturn(0.0f)
        doThrow(RuntimeException("frontier retrieval failed"))
            .`when`(ragStore)
            .retrieve("Sample prompt", RetrievalStrategy.MEMRL, 5)

        val detector = HallucinationDetector(RuntimeEnvironment.getApplication(), emotionalManager).apply {
            this.ragStore = ragStore
            this.frontierDynamicsManager = frontierDynamicsManager
        }

        val result = detector.detectHallucination(
            response = "Sample response with possible unsupported claim.",
            alternativeResponses = null,
            prompt = "Sample prompt"
        )

        assertEquals("Sample response with possible unsupported claim.", result.response)
    }

    @Test
    fun detectHallucination_scoresFactThreadHigherForNumericVerifiableClaims() {
        val emotionalManager = mock(EmotionalStateManager::class.java)
        `when`(emotionalManager.getEmotionalBias()).thenReturn(0.0f)

        val detector = HallucinationDetector(RuntimeEnvironment.getApplication(), emotionalManager)
        val result = detector.detectHallucination(
            response = "The tower is 514 meters tall and was measured in 2024. It operates at 915 MHz.",
            alternativeResponses = null,
            prompt = "Give measured tower details"
        )

        assertTrue(result.factThreadScore > result.opinionBasinScore)
    }

    @Test
    fun detectHallucination_scoresOpinionBasinHigherForSubjectiveLanguage() {
        val emotionalManager = mock(EmotionalStateManager::class.java)
        `when`(emotionalManager.getEmotionalBias()).thenReturn(0.0f)

        val detector = HallucinationDetector(RuntimeEnvironment.getApplication(), emotionalManager)
        val result = detector.detectHallucination(
            response = "I think this might be the best style, in my view it feels better and perhaps more human.",
            alternativeResponses = null,
            prompt = "What is your style opinion?"
        )

        assertTrue(result.opinionBasinScore >= result.factThreadScore)
    }
}
