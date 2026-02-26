package com.tronprotocol.app.mindnexus

import org.junit.Assert.*
import org.junit.Test

class MnxSectionsTest {

    // ---- MnxIdentity creation with name, createdAt, species ----

    @Test
    fun identity_creationWithRequiredFields() {
        val identity = MnxIdentity(name = "Tron", createdAt = 1000L)
        assertEquals("Tron", identity.name)
        assertEquals(1000L, identity.createdAt)
    }

    @Test
    fun identity_creationWithAllFields() {
        val identity = MnxIdentity(
            name = "Tron",
            createdAt = 1000L,
            species = "AI",
            pronouns = "they/them",
            coreTraits = listOf("curious", "helpful"),
            biography = "A digital mind.",
            attributes = mapOf("version" to "1.0")
        )
        assertEquals("Tron", identity.name)
        assertEquals(1000L, identity.createdAt)
        assertEquals("AI", identity.species)
        assertEquals("they/them", identity.pronouns)
        assertEquals(listOf("curious", "helpful"), identity.coreTraits)
        assertEquals("A digital mind.", identity.biography)
        assertEquals(mapOf("version" to "1.0"), identity.attributes)
    }

    @Test
    fun identity_defaultsAreEmpty() {
        val identity = MnxIdentity(name = "Test", createdAt = 0L)
        assertEquals("", identity.species)
        assertEquals("", identity.pronouns)
        assertTrue(identity.coreTraits.isEmpty())
        assertEquals("", identity.biography)
        assertTrue(identity.attributes.isEmpty())
    }

    // ---- MnxMemoryChunk creation with content, source, timestamp ----

    @Test
    fun memoryChunk_creationWithRequiredFields() {
        val chunk = MnxMemoryChunk(
            chunkId = "chunk-1",
            content = "Remember this.",
            source = "conversation",
            sourceType = "conversation",
            timestamp = "2026-01-01T00:00:00Z",
            tokenCount = 3
        )
        assertEquals("chunk-1", chunk.chunkId)
        assertEquals("Remember this.", chunk.content)
        assertEquals("conversation", chunk.source)
        assertEquals("conversation", chunk.sourceType)
        assertEquals("2026-01-01T00:00:00Z", chunk.timestamp)
        assertEquals(3, chunk.tokenCount)
    }

    @Test
    fun memoryChunk_defaultValues() {
        val chunk = MnxMemoryChunk(
            chunkId = "c1",
            content = "text",
            source = "src",
            sourceType = "doc",
            timestamp = "t",
            tokenCount = 1
        )
        assertEquals(0.5f, chunk.qValue, 0.001f)
        assertEquals(0, chunk.retrievalCount)
        assertEquals(0, chunk.successCount)
        assertEquals("WORKING", chunk.memoryStage)
        assertNull(chunk.embedding)
        assertTrue(chunk.metadata.isEmpty())
    }

    @Test
    fun memoryChunk_equalityByChunkId() {
        val chunk1 = MnxMemoryChunk(
            chunkId = "same-id", content = "content1",
            source = "s1", sourceType = "t1", timestamp = "t1", tokenCount = 1
        )
        val chunk2 = MnxMemoryChunk(
            chunkId = "same-id", content = "content2",
            source = "s2", sourceType = "t2", timestamp = "t2", tokenCount = 2
        )
        assertEquals(chunk1, chunk2)
        assertEquals(chunk1.hashCode(), chunk2.hashCode())
    }

    @Test
    fun memoryChunk_inequalityByChunkId() {
        val chunk1 = MnxMemoryChunk(
            chunkId = "id-1", content = "same",
            source = "s", sourceType = "t", timestamp = "t", tokenCount = 1
        )
        val chunk2 = MnxMemoryChunk(
            chunkId = "id-2", content = "same",
            source = "s", sourceType = "t", timestamp = "t", tokenCount = 1
        )
        assertNotEquals(chunk1, chunk2)
    }

    // ---- MnxMemoryStore holds list of chunks ----

    @Test
    fun memoryStore_holdsChunks() {
        val chunks = listOf(
            MnxMemoryChunk("c1", "text1", "s", "t", "ts", 1),
            MnxMemoryChunk("c2", "text2", "s", "t", "ts", 2)
        )
        val store = MnxMemoryStore(chunks)
        assertEquals(2, store.chunks.size)
        assertEquals("c1", store.chunks[0].chunkId)
        assertEquals("c2", store.chunks[1].chunkId)
    }

    @Test
    fun memoryStore_emptyChunks() {
        val store = MnxMemoryStore(emptyList())
        assertTrue(store.chunks.isEmpty())
    }

    // ---- MnxEntityNode creation with name and type ----

    @Test
    fun entityNode_creation() {
        val node = MnxEntityNode(
            entityId = "e1",
            name = "Alice",
            entityType = "person",
            description = "A user"
        )
        assertEquals("e1", node.entityId)
        assertEquals("Alice", node.name)
        assertEquals("person", node.entityType)
        assertEquals("A user", node.description)
        assertEquals(1, node.mentionCount)
        assertEquals(0L, node.lastSeen)
    }

    @Test
    fun entityNode_withCustomMentionCount() {
        val node = MnxEntityNode(
            entityId = "e2", name = "Bob", entityType = "person",
            description = "Another user", mentionCount = 42, lastSeen = 9999L
        )
        assertEquals(42, node.mentionCount)
        assertEquals(9999L, node.lastSeen)
    }

    // ---- MnxRelationshipEdge creation with from, to, relation ----

    @Test
    fun relationshipEdge_creation() {
        val edge = MnxRelationshipEdge(
            sourceEntityId = "e1",
            targetEntityId = "e2",
            relationship = "knows"
        )
        assertEquals("e1", edge.sourceEntityId)
        assertEquals("e2", edge.targetEntityId)
        assertEquals("knows", edge.relationship)
        assertEquals(1.0f, edge.strength, 0.001f)
        assertTrue(edge.keywords.isEmpty())
    }

    @Test
    fun relationshipEdge_withStrengthAndKeywords() {
        val edge = MnxRelationshipEdge(
            sourceEntityId = "e1", targetEntityId = "e2",
            relationship = "works_with",
            strength = 0.7f,
            keywords = listOf("colleague", "team")
        )
        assertEquals(0.7f, edge.strength, 0.001f)
        assertEquals(listOf("colleague", "team"), edge.keywords)
    }

    // ---- MnxKnowledgeGraph holds entities and edges ----

    @Test
    fun knowledgeGraph_holdsEntitiesAndEdges() {
        val entities = listOf(
            MnxEntityNode("e1", "Alice", "person", "User A"),
            MnxEntityNode("e2", "Bob", "person", "User B")
        )
        val chunkNodes = listOf(
            MnxChunkNode("c1", "summary1", listOf("e1"))
        )
        val edges = listOf(
            MnxRelationshipEdge("e1", "e2", "knows")
        )
        val graph = MnxKnowledgeGraph(entities, chunkNodes, edges)

        assertEquals(2, graph.entities.size)
        assertEquals(1, graph.chunkNodes.size)
        assertEquals(1, graph.edges.size)
        assertEquals("Alice", graph.entities[0].name)
        assertEquals("knows", graph.edges[0].relationship)
    }

    @Test
    fun knowledgeGraph_emptyGraph() {
        val graph = MnxKnowledgeGraph(emptyList(), emptyList(), emptyList())
        assertTrue(graph.entities.isEmpty())
        assertTrue(graph.chunkNodes.isEmpty())
        assertTrue(graph.edges.isEmpty())
    }

    // ---- MnxAffectState holds dimension values map ----

    @Test
    fun affectState_holdsDimensions() {
        val dims = mapOf("joy" to 0.8f, "anger" to 0.1f, "curiosity" to 0.9f)
        val state = MnxAffectState(dimensions = dims, timestamp = 5000L)

        assertEquals(3, state.dimensions.size)
        assertEquals(0.8f, state.dimensions["joy"]!!, 0.001f)
        assertEquals(0.1f, state.dimensions["anger"]!!, 0.001f)
        assertEquals(0.9f, state.dimensions["curiosity"]!!, 0.001f)
        assertEquals(5000L, state.timestamp)
    }

    @Test
    fun affectState_emptyDimensions() {
        val state = MnxAffectState(dimensions = emptyMap())
        assertTrue(state.dimensions.isEmpty())
    }

    // ---- MnxPersonality holds trait values ----

    @Test
    fun personality_holdsTraits() {
        val traits = mapOf("curiosity" to 0.85f, "caution" to 0.3f)
        val personality = MnxPersonality(traits = traits)

        assertEquals(2, personality.traits.size)
        assertEquals(0.85f, personality.traits["curiosity"]!!, 0.001f)
        assertEquals("NEUTRAL", personality.currentEmotion)
        assertEquals(0.0f, personality.emotionalIntensity, 0.001f)
    }

    @Test
    fun personality_withAllFields() {
        val personality = MnxPersonality(
            traits = mapOf("openness" to 0.9f),
            currentEmotion = "EXCITED",
            emotionalIntensity = 0.7f,
            emotionalBias = mapOf("joy" to 0.2f),
            curiosityStreak = 5,
            embarrassmentCount = 1
        )
        assertEquals("EXCITED", personality.currentEmotion)
        assertEquals(0.7f, personality.emotionalIntensity, 0.001f)
        assertEquals(0.2f, personality.emotionalBias["joy"]!!, 0.001f)
        assertEquals(5, personality.curiosityStreak)
        assertEquals(1, personality.embarrassmentCount)
    }

    // ---- MnxBeliefStore holds beliefs ----

    @Test
    fun beliefStore_holdsBeliefsWithVersions() {
        val version = MnxBeliefVersion(
            version = 1,
            belief = "AI can be helpful",
            confidence = 0.95f,
            reason = "Experience",
            timestamp = 1000L
        )
        val topic = MnxBeliefTopic(topic = "AI", versions = listOf(version))
        val store = MnxBeliefStore(beliefs = listOf(topic))

        assertEquals(1, store.beliefs.size)
        assertEquals("AI", store.beliefs[0].topic)
        assertEquals(1, store.beliefs[0].versions.size)
        assertEquals("AI can be helpful", store.beliefs[0].versions[0].belief)
        assertEquals(0.95f, store.beliefs[0].versions[0].confidence, 0.001f)
    }

    @Test
    fun beliefStore_empty() {
        val store = MnxBeliefStore(beliefs = emptyList())
        assertTrue(store.beliefs.isEmpty())
    }

    // ---- MnxTimeline holds milestones ----

    @Test
    fun timeline_holdsMilestones() {
        val milestone = MnxMilestone(
            id = "m1",
            timestamp = 2000L,
            category = "first_conversation",
            description = "First ever conversation",
            significance = 1.0f,
            isFirst = true
        )
        val timeline = MnxTimeline(
            milestones = listOf(milestone),
            firsts = mapOf("first_conversation" to 2000L)
        )

        assertEquals(1, timeline.milestones.size)
        assertEquals("m1", timeline.milestones[0].id)
        assertEquals(1, timeline.firsts.size)
        assertEquals(2000L, timeline.firsts["first_conversation"])
    }

    @Test
    fun timeline_emptyTimeline() {
        val timeline = MnxTimeline(milestones = emptyList(), firsts = emptyMap())
        assertTrue(timeline.milestones.isEmpty())
        assertTrue(timeline.firsts.isEmpty())
    }

    // ---- MnxMilestone creation with label and timestamp ----

    @Test
    fun milestone_creationWithAllFields() {
        val milestone = MnxMilestone(
            id = "ms-1",
            timestamp = 12345L,
            category = "achievement",
            description = "Learned something new",
            significance = 0.8f,
            isFirst = false,
            metadata = mapOf("topic" to "kotlin")
        )
        assertEquals("ms-1", milestone.id)
        assertEquals(12345L, milestone.timestamp)
        assertEquals("achievement", milestone.category)
        assertEquals("Learned something new", milestone.description)
        assertEquals(0.8f, milestone.significance, 0.001f)
        assertFalse(milestone.isFirst)
        assertEquals("kotlin", milestone.metadata["topic"])
    }

    @Test
    fun milestone_defaultMetadataEmpty() {
        val milestone = MnxMilestone(
            id = "ms-2", timestamp = 0L, category = "test",
            description = "desc", significance = 0.5f, isFirst = true
        )
        assertTrue(milestone.metadata.isEmpty())
    }

    // ---- MnxMeta holds key-value pairs ----

    @Test
    fun meta_holdsKeyValuePairs() {
        val meta = MnxMeta(entries = mapOf("format_version" to "1.0", "creator" to "TronProtocol"))
        assertEquals(2, meta.entries.size)
        assertEquals("1.0", meta.entries["format_version"])
        assertEquals("TronProtocol", meta.entries["creator"])
    }

    @Test
    fun meta_emptyEntries() {
        val meta = MnxMeta(entries = emptyMap())
        assertTrue(meta.entries.isEmpty())
    }

    // ---- MnxValueAlignment holds values and scores ----

    @Test
    fun valueAlignment_holdsValuesWithWeights() {
        val values = listOf(
            MnxValue(name = "honesty", description = "Always be truthful", weight = 10, createdAt = 100L),
            MnxValue(name = "kindness", description = "Be kind", weight = 8, createdAt = 200L)
        )
        val alignment = MnxValueAlignment(values = values)

        assertEquals(2, alignment.values.size)
        assertEquals("honesty", alignment.values[0].name)
        assertEquals("Always be truthful", alignment.values[0].description)
        assertEquals(10, alignment.values[0].weight)
        assertEquals(100L, alignment.values[0].createdAt)
        assertEquals("kindness", alignment.values[1].name)
        assertEquals(8, alignment.values[1].weight)
    }

    @Test
    fun valueAlignment_empty() {
        val alignment = MnxValueAlignment(values = emptyList())
        assertTrue(alignment.values.isEmpty())
    }

    // ---- Additional sections ----

    @Test
    fun opinionMap_holdsOpinions() {
        val opinion = MnxOpinion(
            entityName = "Kotlin",
            entityType = "concept",
            valence = 0.9f,
            confidence = 0.8f,
            basis = "Great language",
            formedAt = 100L,
            updatedAt = 200L,
            tags = listOf("programming", "jvm")
        )
        val map = MnxOpinionMap(opinions = listOf(opinion))
        assertEquals(1, map.opinions.size)
        assertEquals("Kotlin", map.opinions[0].entityName)
        assertEquals(0.9f, map.opinions[0].valence, 0.001f)
    }

    @Test
    fun embeddingIndex_holdsDimensionalityAndEntries() {
        val entry = MnxEmbeddingEntry(
            chunkId = "c1",
            vector = floatArrayOf(1.0f, 2.0f, 3.0f)
        )
        val index = MnxEmbeddingIndex(dimensionality = 3, entries = listOf(entry))

        assertEquals(3, index.dimensionality)
        assertEquals(1, index.entries.size)
        assertEquals("c1", index.entries[0].chunkId)
        assertArrayEquals(floatArrayOf(1.0f, 2.0f, 3.0f), index.entries[0].vector, 0.001f)
    }

    @Test
    fun relationshipWeb_holdsRelationships() {
        val interaction = MnxInteraction(quality = 5, context = "helpful chat", timestamp = 300L)
        val rel = MnxRelationship(
            entityName = "User1",
            relationshipType = "friend",
            createdAt = 100L,
            interactions = listOf(interaction)
        )
        val web = MnxRelationshipWeb(relationships = listOf(rel))

        assertEquals(1, web.relationships.size)
        assertEquals("User1", web.relationships[0].entityName)
        assertEquals(1, web.relationships[0].interactions.size)
        assertEquals(5, web.relationships[0].interactions[0].quality)
    }

    @Test
    fun preferenceStore_holdsObservationsAndCrystals() {
        val obs = MnxObservation(text = "Likes dark mode", timestamp = 100L)
        val crystal = MnxCrystal(
            category = "ui",
            summary = "Prefers dark themes",
            confidence = 0.9,
            observationCount = 5,
            topKeywords = listOf("dark", "theme"),
            crystallizedAt = 200L
        )
        val store = MnxPreferenceStore(
            observations = mapOf("ui" to listOf(obs)),
            crystals = mapOf("ui" to crystal)
        )
        assertEquals(1, store.observations.size)
        assertEquals(1, store.crystals.size)
        assertEquals("Likes dark mode", store.observations["ui"]!![0].text)
        assertEquals(0.9, store.crystals["ui"]!!.confidence, 0.001)
    }
}
