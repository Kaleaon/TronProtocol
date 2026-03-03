package com.tronprotocol.app.data

import com.tronprotocol.app.data.entity.ChunkEntity
import com.tronprotocol.app.data.entity.TelemetryEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class TronDatabaseTest {

    private fun createDb(): TronDatabase =
        TronDatabase.createInMemory(RuntimeEnvironment.getApplication())

    // --- ChunkDao ---

    @Test
    fun `insert and retrieve chunk`() {
        val db = createDb()
        val dao = db.chunkDao()

        val chunk = ChunkEntity(
            chunkId = "test1",
            aiId = "ai1",
            content = "Kotlin is a modern programming language",
            source = "test",
            sourceType = "knowledge",
            timestamp = System.currentTimeMillis(),
            tokenCount = 8,
            qValue = 0.5f
        )

        dao.insert(chunk)
        val retrieved = dao.getById("test1")
        assertNotNull(retrieved)
        assertEquals("test1", retrieved!!.chunkId)
        assertEquals("Kotlin is a modern programming language", retrieved.content)
    }

    @Test
    fun `get chunks by aiId`() {
        val db = createDb()
        val dao = db.chunkDao()

        dao.insert(makeChunk("c1", "ai1", "Content 1"))
        dao.insert(makeChunk("c2", "ai1", "Content 2"))
        dao.insert(makeChunk("c3", "ai2", "Content 3"))

        val ai1Chunks = dao.getByAiId("ai1")
        assertEquals(2, ai1Chunks.size)

        val ai2Chunks = dao.getByAiId("ai2")
        assertEquals(1, ai2Chunks.size)
    }

    @Test
    fun `delete by id`() {
        val db = createDb()
        val dao = db.chunkDao()

        dao.insert(makeChunk("c1", "ai1", "Content"))
        dao.deleteById("c1")
        assertNull(dao.getById("c1"))
    }

    @Test
    fun `delete by aiId`() {
        val db = createDb()
        val dao = db.chunkDao()

        dao.insert(makeChunk("c1", "ai1", "Content 1"))
        dao.insert(makeChunk("c2", "ai1", "Content 2"))
        dao.deleteByAiId("ai1")
        assertEquals(0, dao.countByAiId("ai1"))
    }

    @Test
    fun `get lowest Q-value chunks`() {
        val db = createDb()
        val dao = db.chunkDao()

        dao.insert(makeChunk("c1", "ai1", "High Q", qValue = 0.9f))
        dao.insert(makeChunk("c2", "ai1", "Mid Q", qValue = 0.5f))
        dao.insert(makeChunk("c3", "ai1", "Low Q", qValue = 0.1f))

        val lowest = dao.getLowestQValue("ai1", 2)
        assertEquals(2, lowest.size)
        assertEquals("c3", lowest[0].chunkId) // lowest first
    }

    @Test
    fun `count by aiId`() {
        val db = createDb()
        val dao = db.chunkDao()

        dao.insert(makeChunk("c1", "ai1", "A"))
        dao.insert(makeChunk("c2", "ai1", "B"))
        assertEquals(2, dao.countByAiId("ai1"))
        assertEquals(0, dao.countByAiId("ai2"))
    }

    @Test
    fun `upsert replaces existing chunk`() {
        val db = createDb()
        val dao = db.chunkDao()

        dao.insert(makeChunk("c1", "ai1", "Original"))
        dao.insert(makeChunk("c1", "ai1", "Updated"))

        val chunk = dao.getById("c1")!!
        assertEquals("Updated", chunk.content)
        assertEquals(1, dao.countByAiId("ai1"))
    }

    // --- TelemetryDao ---

    @Test
    fun `insert and retrieve telemetry`() {
        val db = createDb()
        val dao = db.telemetryDao()

        dao.insert(
            TelemetryEntity(
                timestampMs = System.currentTimeMillis(),
                aiId = "ai1",
                strategy = "SEMANTIC",
                latencyMs = 50,
                resultCount = 5,
                topK = 10,
                topScore = 0.95f,
                avgScore = 0.75f
            )
        )

        val recent = dao.getRecent("ai1", 10)
        assertEquals(1, recent.size)
        assertEquals("SEMANTIC", recent[0].strategy)
    }

    @Test
    fun `average latency by strategy`() {
        val db = createDb()
        val dao = db.telemetryDao()

        dao.insert(makeTelemetry("ai1", "SEMANTIC", 40))
        dao.insert(makeTelemetry("ai1", "SEMANTIC", 60))
        dao.insert(makeTelemetry("ai1", "KEYWORD", 20))

        val avg = dao.getAvgLatency("ai1", "SEMANTIC")
        assertNotNull(avg)
        assertEquals(50f, avg!!, 1f)
    }

    @Test
    fun `delete older than cutoff`() {
        val db = createDb()
        val dao = db.telemetryDao()

        val now = System.currentTimeMillis()
        dao.insert(makeTelemetry("ai1", "S", 10, now - 100000))
        dao.insert(makeTelemetry("ai1", "S", 10, now))

        dao.deleteOlderThan(now - 50000)

        val remaining = dao.getRecent("ai1", 100)
        assertEquals(1, remaining.size)
    }

    // --- Helpers ---

    private fun makeChunk(
        id: String,
        aiId: String,
        content: String,
        qValue: Float = 0.5f
    ) = ChunkEntity(
        chunkId = id,
        aiId = aiId,
        content = content,
        source = "test",
        sourceType = "knowledge",
        timestamp = System.currentTimeMillis(),
        tokenCount = content.split(" ").size,
        qValue = qValue
    )

    private fun makeTelemetry(
        aiId: String,
        strategy: String,
        latencyMs: Long,
        timestampMs: Long = System.currentTimeMillis()
    ) = TelemetryEntity(
        timestampMs = timestampMs,
        aiId = aiId,
        strategy = strategy,
        latencyMs = latencyMs,
        resultCount = 3,
        topK = 10,
        topScore = 0.8f,
        avgScore = 0.6f
    )
}
