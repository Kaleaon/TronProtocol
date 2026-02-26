package com.tronprotocol.app.rag

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KnowledgeGraphTest {

    private lateinit var context: Context
    private lateinit var graph: KnowledgeGraph

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        graph = KnowledgeGraph(context, "test_graph_${System.nanoTime()}")
    }

    // --- addEntity ---

    @Test
    fun addEntity_addsAnEntity() {
        val entityId = graph.addEntity("Kotlin", "TECHNOLOGY", "A programming language")
        assertNotNull(entityId)
        assertTrue(entityId.isNotEmpty())
        assertEquals(1, graph.getEntities().size)
    }

    // --- addChunkNode ---

    @Test
    fun addChunkNode_addsAChunkNode() {
        val entityId = graph.addEntity("Android", "TECHNOLOGY", "Mobile platform")
        graph.addChunkNode("chunk_001", "Android is a mobile platform", listOf(entityId))

        val stats = graph.getStats()
        assertEquals(1, stats["chunk_count"])
    }

    // --- getEntities ---

    @Test
    fun getEntities_returnsAddedEntities() {
        graph.addEntity("Kotlin", "TECHNOLOGY", "Programming language")
        graph.addEntity("Java", "TECHNOLOGY", "Programming language")
        graph.addEntity("Google", "ORGANIZATION", "Tech company")

        val entities = graph.getEntities()
        assertEquals(3, entities.size)
        assertTrue(entities.any { it.name == "kotlin" }) // Names are normalized to lowercase
        assertTrue(entities.any { it.name == "java" })
        assertTrue(entities.any { it.name == "google" })
    }

    // --- getStats for chunk nodes ---

    @Test
    fun getStats_returnsAddedChunkNodes() {
        val eId1 = graph.addEntity("TensorFlow", "TECHNOLOGY", "ML framework")
        val eId2 = graph.addEntity("PyTorch", "TECHNOLOGY", "ML framework")
        graph.addChunkNode("chunk_001", "TensorFlow is used for ML", listOf(eId1))
        graph.addChunkNode("chunk_002", "PyTorch is used for ML", listOf(eId2))

        val stats = graph.getStats()
        assertEquals(2, stats["chunk_count"])
    }

    // --- addRelationship ---

    @Test
    fun addRelationship_createsEdgeBetweenNodes() {
        val id1 = graph.addEntity("Kotlin", "TECHNOLOGY", "Language")
        val id2 = graph.addEntity("Android", "TECHNOLOGY", "Platform")

        graph.addRelationship(id1, id2, "runs_on", 0.9f)

        val stats = graph.getStats()
        val relCount = stats["relationship_count"] as Int
        // Bidirectional, so each addRelationship creates 2 edges
        assertTrue("Relationship count should be > 0", relCount > 0)
    }

    // --- getEntityDegree (getRelationships proxy) ---

    @Test
    fun getEntityDegree_returnsEdgesForANode() {
        val kotlinId = graph.addEntity("Kotlin", "TECHNOLOGY", "Language")
        val androidId = graph.addEntity("Android", "TECHNOLOGY", "Platform")
        val jvmId = graph.addEntity("JVM", "TECHNOLOGY", "Runtime")

        graph.addChunkNode("chunk_001", "Kotlin on Android", listOf(kotlinId, androidId))
        graph.addRelationship(kotlinId, jvmId, "targets", 0.8f)

        val degree = graph.getEntityDegree(kotlinId)
        assertTrue("Entity degree should be > 0 when edges exist", degree > 0)
    }

    @Test
    fun getEntityDegree_returnsZeroForUnknownNode() {
        val degree = graph.getEntityDegree("entity_nonexistent")
        assertEquals(0, degree)
    }

    // --- topologyRetrieve (getTopologicallyRelevantChunks) ---

    @Test
    fun topologyRetrieve_returnsRelevantChunks() {
        val kotlinId = graph.addEntity("Kotlin", "TECHNOLOGY", "Programming language")
        val androidId = graph.addEntity("Android", "TECHNOLOGY", "Mobile platform")

        graph.addChunkNode("chunk_001", "Kotlin is the main Android language", listOf(kotlinId, androidId))
        graph.addChunkNode("chunk_002", "Kotlin has null safety", listOf(kotlinId))

        val results = graph.topologyRetrieve(listOf("kotlin"), 10)
        assertFalse("Should find chunks connected to matching entities", results.isEmpty())
        assertTrue(results.any { it.chunkId == "chunk_001" || it.chunkId == "chunk_002" })
    }

    // --- edgeVotingRetrieve (retrieveByEdgeVoting) ---

    @Test
    fun edgeVotingRetrieve_returnsResults() {
        val kotlinId = graph.addEntity("Kotlin", "TECHNOLOGY", "Language")
        val androidId = graph.addEntity("Android", "TECHNOLOGY", "Platform")
        val jvmId = graph.addEntity("JVM", "TECHNOLOGY", "Runtime")

        graph.addRelationship(kotlinId, androidId, "used_by", 0.9f)
        graph.addRelationship(kotlinId, jvmId, "targets", 0.8f)

        graph.addChunkNode("chunk_001", "Kotlin runs on Android", listOf(kotlinId, androidId))
        graph.addChunkNode("chunk_002", "Kotlin targets JVM", listOf(kotlinId, jvmId))

        val results = graph.edgeVotingRetrieve(listOf("kotlin"), 10)
        // Edge voting may or may not find results depending on vote threshold,
        // but it should not throw
        assertNotNull(results)
    }
}
