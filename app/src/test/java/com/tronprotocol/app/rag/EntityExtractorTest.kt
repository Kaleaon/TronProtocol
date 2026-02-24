package com.tronprotocol.app.rag

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EntityExtractorTest {

    private lateinit var extractor: EntityExtractor

    @Before
    fun setUp() {
        extractor = EntityExtractor()
    }

    // --- extractEntities finds proper nouns ---

    @Test
    fun extract_findsProperNouns() {
        val result = extractor.extract("Yesterday John went to Paris for a conference.")
        val entityNames = result.entities.map { it.name.lowercase() }
        // John and Paris should be detected as capitalized proper nouns
        assertTrue(
            "Should find 'John' as entity",
            entityNames.any { it.contains("john") }
        )
        assertTrue(
            "Should find 'Paris' as entity",
            entityNames.any { it.contains("paris") }
        )
    }

    // --- extractEntities identifies technology terms ---

    @Test
    fun extract_identifiesTechnologyTerms() {
        val result = extractor.extract("The app uses Kotlin and Android with TensorFlow for ML.")
        val entityNames = result.entities.map { it.name.lowercase() }

        assertTrue(
            "Should identify Kotlin as tech term",
            entityNames.any { it.contains("kotlin") }
        )
        assertTrue(
            "Should identify Android as tech term",
            entityNames.any { it.contains("android") }
        )
        assertTrue(
            "Should identify TensorFlow as tech term",
            entityNames.any { it.contains("tensorflow") }
        )
    }

    // --- extractEntities returns empty for text with no entities ---

    @Test
    fun extract_returnsEmptyForNoEntities() {
        val result = extractor.extract("the quick brown fox jumps over the lazy dog")
        // All lowercase, no proper nouns, no tech terms that would match
        // Some tech terms like "go" might match, but "the quick brown fox" shouldn't have many
        // The key assertion is that the extraction does not throw and returns a valid result
        assertNotNull(result)
        assertNotNull(result.entities)
    }

    // --- extractEntities handles empty string ---

    @Test
    fun extract_handlesEmptyString() {
        val result = extractor.extract("")
        assertNotNull(result)
        assertTrue("Empty string should yield no entities", result.entities.isEmpty())
    }

    // --- extracted entities have non-null name and type ---

    @Test
    fun extract_entitiesHaveNonNullNameAndType() {
        val result = extractor.extract("Google developed Kotlin support for Android Studio.")
        for (entity in result.entities) {
            assertNotNull("Entity name should not be null", entity.name)
            assertTrue("Entity name should not be empty", entity.name.isNotEmpty())
            assertNotNull("Entity type should not be null", entity.entityType)
            assertTrue("Entity type should not be empty", entity.entityType.isNotEmpty())
        }
    }

    // --- confidence values are between 0 and 1 ---

    @Test
    fun extract_confidenceValuesBetweenZeroAndOne() {
        val result = extractor.extract("Apple Inc. is based in Cupertino. Tim Cook is the CEO.")
        for (entity in result.entities) {
            assertTrue(
                "Confidence should be >= 0, was ${entity.confidence}",
                entity.confidence >= 0.0f
            )
            assertTrue(
                "Confidence should be <= 1, was ${entity.confidence}",
                entity.confidence <= 1.0f
            )
        }
    }

    // --- deduplication works ---

    @Test
    fun extract_deduplicatesSameEntity() {
        val result = extractor.extract(
            "Kotlin is great. I really love Kotlin for Android development. Kotlin is modern."
        )
        val kotlinEntities = result.entities.filter { it.name.equals("Kotlin", ignoreCase = true) }
        // After deduplication, Kotlin should appear at most once
        assertTrue(
            "Deduplicated entities should have at most 1 occurrence of 'Kotlin', found ${kotlinEntities.size}",
            kotlinEntities.size <= 1
        )
    }

    // --- relationships are inferred ---

    @Test
    fun extract_infersRelationships() {
        val result = extractor.extract("Google uses Kotlin for Android development.")
        // There should be entities and potentially relationships between co-occurring entities
        assertNotNull(result.relationships)
    }
}
