package com.tronprotocol.app.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeuralTemporalScoringEngineTest {

    private val engine = NeuralTemporalScoringEngine()

    @Test
    fun assignStageTreatsKnowledgeAsSemantic() {
        val stage = engine.assignStage(
            content = "Tensor G5 TPU includes advanced NNAPI acceleration",
            sourceType = "knowledge"
        )
        assertEquals(MemoryStage.SEMANTIC, stage)
    }

    @Test
    fun assignStagePromotesHighImportanceToEpisodic() {
        val stage = engine.assignStage(
            content = "urgent emergency family safety alert critical action",
            sourceType = "memory",
            baseImportance = 0.95f
        )
        assertEquals(MemoryStage.EPISODIC, stage)
    }

    @Test
    fun scoreForRetrievalReturnsNormalizedAggregate() {
        val chunk = TextChunk(
            chunkId = "nts-1",
            content = "important family meeting notes",
            source = "test",
            sourceType = "memory",
            timestamp = System.currentTimeMillis().toString(),
            tokenCount = 4
        )
        MemoryStage.assign(chunk, MemoryStage.WORKING)
        chunk.restoreMemRLState(0.8f, 8, 6)

        val scores = engine.scoreForRetrieval(chunk, semanticSimilarity = 0.9f, nowMs = System.currentTimeMillis())
        assertTrue(scores.aggregate in 0.0f..1.0f)
        assertTrue(scores.utility >= 0.79f)
        assertTrue(scores.recency >= 0.95f)
    }

    @Test
    fun noveltyPrefersDiverseTokenSequences() {
        val low = engine.estimateNovelty("ping ping ping ping")
        val high = engine.estimateNovelty("ping alpha beta gamma")
        assertTrue(high > low)
    }
}
