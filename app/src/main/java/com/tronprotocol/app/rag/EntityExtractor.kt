package com.tronprotocol.app.rag

/**
 * Entity Extractor for Knowledge Graph population
 *
 * Inspired by MiniRAG's entity extraction pipeline:
 * - Extracts named entities from text chunks
 * - Identifies entity types (PERSON, PLACE, ORG, CONCEPT, TECH, etc.)
 * - Detects relationships between co-occurring entities
 *
 * This is a lightweight on-device implementation using heuristics and
 * pattern matching. In production, this could be enhanced with:
 * - ML Kit's entity extraction API
 * - A TFLite NER model
 * - The Anthropic API for more sophisticated extraction
 */
class EntityExtractor {

    /**
     * Extracted entity with type and context
     */
    data class ExtractedEntity(
        val name: String,
        val entityType: String,
        val context: String,
        val confidence: Float
    )

    /**
     * Extracted relationship between two entities
     */
    data class ExtractedRelationship(
        val sourceEntity: String,
        val targetEntity: String,
        val relationship: String,
        val strength: Float
    )

    /**
     * Extraction result containing entities and relationships
     */
    data class ExtractionResult(
        val entities: List<ExtractedEntity>,
        val relationships: List<ExtractedRelationship>
    )

    /**
     * Extract entities and relationships from text.
     *
     * Uses multi-strategy extraction:
     * 1. Capitalized word sequences (proper nouns)
     * 2. Technology/programming term detection
     * 3. Pattern-based entity typing
     * 4. Co-occurrence relationship inference
     */
    fun extract(text: String): ExtractionResult {
        val entities = mutableListOf<ExtractedEntity>()
        val relationships = mutableListOf<ExtractedRelationship>()

        // Strategy 1: Extract capitalized proper nouns
        entities.addAll(extractProperNouns(text))

        // Strategy 2: Extract technology terms
        entities.addAll(extractTechTerms(text))

        // Strategy 3: Extract key concepts (noun phrases after certain patterns)
        entities.addAll(extractKeyConcepts(text))

        // Deduplicate by normalized name
        val deduped = deduplicateEntities(entities)

        // Infer relationships from co-occurrence in sentences
        relationships.addAll(inferRelationships(text, deduped))

        return ExtractionResult(deduped, relationships)
    }

    /**
     * Extract capitalized word sequences as potential named entities.
     * Filters out sentence starters and common words.
     */
    private fun extractProperNouns(text: String): List<ExtractedEntity> {
        val entities = mutableListOf<ExtractedEntity>()
        val sentences = text.split("[.!?]+".toRegex()).filter { it.isNotBlank() }

        for (sentence in sentences) {
            val words = sentence.trim().split("\\s+".toRegex())
            if (words.isEmpty()) continue

            var i = 1  // Skip first word (sentence starter)
            while (i < words.size) {
                val word = words[i].replace("[^a-zA-Z0-9'-]".toRegex(), "")

                if (word.isNotEmpty() && word[0].isUpperCase() && word.length > 1
                    && word !in COMMON_WORDS) {

                    // Collect consecutive capitalized words
                    val nameBuilder = StringBuilder(word)
                    var j = i + 1
                    while (j < words.size) {
                        val nextWord = words[j].replace("[^a-zA-Z0-9'-]".toRegex(), "")
                        if (nextWord.isNotEmpty() && nextWord[0].isUpperCase()) {
                            nameBuilder.append(" ").append(nextWord)
                            j++
                        } else {
                            break
                        }
                    }

                    val name = nameBuilder.toString()
                    if (name.length > 1) {
                        val entityType = classifyEntity(name)
                        val contextStart = maxOf(0, sentence.indexOf(name) - 30)
                        val contextEnd = minOf(sentence.length, sentence.indexOf(name) + name.length + 30)
                        val context = sentence.substring(contextStart, contextEnd).trim()

                        entities.add(ExtractedEntity(name, entityType, context, 0.7f))
                    }
                    i = j
                } else {
                    i++
                }
            }
        }

        return entities
    }

    /**
     * Extract technology and programming terms.
     */
    private fun extractTechTerms(text: String): List<ExtractedEntity> {
        val entities = mutableListOf<ExtractedEntity>()
        val textLower = text.lowercase()

        for (term in TECH_TERMS) {
            if (textLower.contains(term.lowercase())) {
                entities.add(ExtractedEntity(term, "TECHNOLOGY", "", 0.8f))
            }
        }

        // Detect camelCase and PascalCase identifiers
        val codePattern = "\\b[A-Z][a-z]+(?:[A-Z][a-z]+)+\\b".toRegex()
        for (match in codePattern.findAll(text)) {
            val identifier = match.value
            if (identifier.length in 4..40 && identifier !in COMMON_WORDS) {
                entities.add(ExtractedEntity(identifier, "CODE_IDENTIFIER", "", 0.6f))
            }
        }

        return entities
    }

    /**
     * Extract key concepts from indicator patterns.
     * E.g., "X is a type of Y", "X uses Y"
     */
    private fun extractKeyConcepts(text: String): List<ExtractedEntity> {
        val entities = mutableListOf<ExtractedEntity>()

        val conceptPatterns = listOf(
            "(?:called|named|known as)\\s+([A-Z][a-zA-Z0-9 ]{2,30})" to "CONCEPT",
            "(?:using|via|through)\\s+([A-Z][a-zA-Z0-9 ]{2,30})" to "TECHNOLOGY",
            "(?:created by|developed by|built by)\\s+([A-Z][a-zA-Z0-9 ]{2,30})" to "ORGANIZATION",
            "(?:located in|based in|from)\\s+([A-Z][a-zA-Z0-9 ]{2,30})" to "PLACE"
        )

        for ((pattern, entityType) in conceptPatterns) {
            val regex = pattern.toRegex()
            for (match in regex.findAll(text)) {
                val name = match.groupValues[1].trim()
                if (name.length > 2 && name.split(" ").size <= 4) {
                    entities.add(ExtractedEntity(name, entityType, match.value, 0.6f))
                }
            }
        }

        return entities
    }

    /**
     * Classify an entity name into a type based on patterns.
     */
    private fun classifyEntity(name: String): String {
        val nameLower = name.lowercase()
        return when {
            PERSON_INDICATORS.any { nameLower.contains(it) } -> "PERSON"
            ORG_INDICATORS.any { nameLower.contains(it) } -> "ORGANIZATION"
            PLACE_INDICATORS.any { nameLower.contains(it) } -> "PLACE"
            name.contains("Exception") || name.contains("Error")
                    || name.contains("Manager") || name.contains("Service") -> "CODE_IDENTIFIER"
            else -> "ENTITY"
        }
    }

    /**
     * Deduplicate entities by normalized name, keeping highest confidence.
     */
    private fun deduplicateEntities(entities: List<ExtractedEntity>): List<ExtractedEntity> {
        val byName = mutableMapOf<String, ExtractedEntity>()
        for (entity in entities) {
            val key = entity.name.lowercase().trim()
            val existing = byName[key]
            if (existing == null || entity.confidence > existing.confidence) {
                byName[key] = entity
            }
        }
        return byName.values.toList()
    }

    /**
     * Infer relationships between entities that co-occur in the same sentence.
     * Uses MiniRAG's concept that co-occurrence implies semantic relationship.
     */
    private fun inferRelationships(
        text: String,
        entities: List<ExtractedEntity>
    ): List<ExtractedRelationship> {
        val relationships = mutableListOf<ExtractedRelationship>()
        val sentences = text.split("[.!?]+".toRegex()).filter { it.isNotBlank() }

        for (sentence in sentences) {
            val sentenceLower = sentence.lowercase()

            // Find all entities mentioned in this sentence
            val mentioned = entities.filter { entity ->
                sentenceLower.contains(entity.name.lowercase())
            }

            // Create relationships between co-occurring entities
            for (i in mentioned.indices) {
                for (j in i + 1 until mentioned.size) {
                    val source = mentioned[i]
                    val target = mentioned[j]

                    // Determine relationship type from context
                    val relType = inferRelationType(sentence, source.name, target.name)
                    val strength = calculateRelationshipStrength(sentence, source.name, target.name)

                    relationships.add(
                        ExtractedRelationship(
                            source.name, target.name, relType, strength
                        )
                    )
                }
            }
        }

        return relationships
    }

    /**
     * Infer the type of relationship between two entities from sentence context.
     */
    private fun inferRelationType(sentence: String, source: String, target: String): String {
        val sentenceLower = sentence.lowercase()
        val sourceIdx = sentenceLower.indexOf(source.lowercase())
        val targetIdx = sentenceLower.indexOf(target.lowercase())

        if (sourceIdx < 0 || targetIdx < 0) return "co_occurs_with"

        val between = if (sourceIdx < targetIdx) {
            sentenceLower.substring(sourceIdx + source.length, targetIdx).trim()
        } else {
            sentenceLower.substring(targetIdx + target.length, sourceIdx).trim()
        }

        return when {
            between.contains("uses") || between.contains("using") -> "uses"
            between.contains("is a") || between.contains("is an") -> "is_a"
            between.contains("has") || between.contains("have") -> "has"
            between.contains("created") || between.contains("built") -> "created_by"
            between.contains("part of") || between.contains("belongs to") -> "part_of"
            between.contains("depends on") || between.contains("requires") -> "depends_on"
            between.contains("similar") || between.contains("like") -> "similar_to"
            between.contains("and") || between.contains(",") -> "associated_with"
            else -> "related_to"
        }
    }

    /**
     * Calculate relationship strength based on proximity and frequency.
     */
    private fun calculateRelationshipStrength(sentence: String, source: String, target: String): Float {
        val sentenceLower = sentence.lowercase()
        val sourceIdx = sentenceLower.indexOf(source.lowercase())
        val targetIdx = sentenceLower.indexOf(target.lowercase())

        if (sourceIdx < 0 || targetIdx < 0) return 0.5f

        // Closer entities have stronger relationships
        val distance = Math.abs(sourceIdx - targetIdx).toFloat()
        val maxDistance = sentence.length.toFloat()
        val proximityScore = 1.0f - (distance / maxDistance)

        return (0.5f + proximityScore * 0.5f).coerceIn(0.0f, 1.0f)
    }

    companion object {
        private val COMMON_WORDS = setOf(
            "The", "This", "That", "These", "Those", "There", "Their", "They",
            "What", "Which", "Where", "When", "Why", "How", "Who",
            "But", "And", "For", "Not", "You", "All", "Can", "Her",
            "Was", "One", "Our", "Out", "Are", "Has", "His", "Its",
            "Let", "May", "New", "Now", "Old", "See", "Way", "Had",
            "Has", "Him", "How", "Man", "Its", "Say", "She", "Too",
            "Use", "Also", "Each", "Just", "More", "Most", "Only",
            "Some", "Such", "Than", "Very", "Will", "With", "From",
            "Here", "Into", "Then", "Them", "Been", "Have", "Many",
            "Well", "Back", "Much", "Make", "Over", "Take", "Upon",
            "Could", "Would", "Should", "About", "After", "Before",
            "Being", "Below", "Between", "However", "Because",
            "While", "During", "Since", "Until", "Although", "Though"
        )

        private val TECH_TERMS = listOf(
            "Android", "Kotlin", "Java", "TensorFlow", "TFLite",
            "API", "REST", "HTTP", "HTTPS", "JSON", "XML",
            "Machine Learning", "Neural Network", "Deep Learning",
            "NLP", "RAG", "GPT", "LLM", "Transformer",
            "SQL", "NoSQL", "Firebase", "Cloud",
            "Gradle", "Maven", "Docker", "Kubernetes",
            "React", "Vue", "Angular", "Node.js",
            "Python", "JavaScript", "TypeScript", "Rust", "Go",
            "Encryption", "AES", "RSA", "SHA", "KeyStore",
            "MQTT", "WebSocket", "gRPC", "GraphQL"
        )

        private val PERSON_INDICATORS = listOf("dr.", "mr.", "mrs.", "ms.", "prof.")
        private val ORG_INDICATORS = listOf("inc.", "corp.", "ltd.", "llc", "company", "organization", "institute", "university")
        private val PLACE_INDICATORS = listOf("city", "state", "country", "island", "mountain", "river", "ocean")
    }
}
