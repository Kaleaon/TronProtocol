package com.tronprotocol.app.plugins

import android.content.Context
import android.util.Log
import com.tronprotocol.app.emotion.EmotionalStateManager
import com.tronprotocol.app.rag.RAGStore
import com.tronprotocol.app.rag.RetrievalStrategy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Personality Import Plugin — loads memories, personality traits, preferences,
 * and knowledge from stored documents, JSON files, and text files into the
 * AI's memory and personality systems.
 *
 * Supported input formats:
 * - Plain text (.txt): Ingested as knowledge chunks into the RAG memory system.
 * - JSON personality file (.json): Structured personality data with traits,
 *   memories, preferences, backstory, and knowledge entries.
 * - Markdown (.md): Parsed into sections and ingested as knowledge.
 * - Generic documents: Ingested as-is into the RAG store.
 *
 * JSON Personality Schema:
 * ```json
 * {
 *   "name": "AI Name",
 *   "backstory": "A narrative backstory for the AI persona...",
 *   "traits": {
 *     "curiosity": 0.8,
 *     "caution": 0.3,
 *     "empathy": 0.9
 *   },
 *   "preferences": {
 *     "communication_style": "warm and direct",
 *     "favorite_topics": "science, philosophy"
 *   },
 *   "memories": [
 *     "I once helped a user debug a complex threading issue.",
 *     "Users prefer concise answers with examples."
 *   ],
 *   "knowledge": [
 *     {
 *       "content": "The capital of France is Paris.",
 *       "category": "geography"
 *     }
 *   ],
 *   "directives": [
 *     "Always be honest about uncertainty.",
 *     "Prefer giving examples over abstract explanations."
 *   ]
 * }
 * ```
 *
 * Commands:
 * - import|<file_path>           Import a file (auto-detects format)
 * - import_json|<file_path>      Import a JSON personality file
 * - import_text|<file_path>      Import a plain text file as knowledge
 * - import_dir|<directory_path>  Import all supported files in a directory
 * - scan|<directory_path>        Preview files that would be imported (dry run)
 * - status                       Show import statistics
 * - clear_imports                Clear all imported data from the RAG store
 */
class PersonalityImportPlugin : Plugin {

    companion object {
        private const val TAG = "PersonalityImport"
        private const val ID = "personality_import"
        private const val AI_ID = "tronprotocol_ai"
        private const val MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024 // 10 MB
        private const val MAX_CHUNK_CHARS = 1500 // ~375 tokens per chunk
        private val SUPPORTED_EXTENSIONS = setOf("txt", "json", "md", "csv", "jsonl")
    }

    private lateinit var appContext: Context
    private var ragStore: RAGStore? = null
    private var emotionalStateManager: EmotionalStateManager? = null
    private var personalizationPlugin: PersonalizationPlugin? = null

    private var totalFilesImported: Int = 0
    private var totalChunksCreated: Int = 0
    private var totalTraitsImported: Int = 0
    private var totalPreferencesImported: Int = 0

    override val id: String = ID
    override val name: String = "Personality Import"
    override val description: String =
        "Import AI personality, memories, and knowledge from files. " +
        "Commands: import|path, import_json|path, import_text|path, import_dir|path, scan|path, status, clear_imports"
    override var isEnabled: Boolean = true

    override fun initialize(context: Context) {
        appContext = context.applicationContext
        try {
            ragStore = RAGStore(appContext, AI_ID)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize RAGStore", e)
        }
        try {
            emotionalStateManager = EmotionalStateManager(appContext)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize EmotionalStateManager", e)
        }
        try {
            personalizationPlugin = PersonalizationPlugin().also {
                it.initialize(appContext)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize PersonalizationPlugin", e)
        }
    }

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            if (input.isBlank()) {
                return PluginResult.error("No command provided. Use: import|path, import_json|path, import_text|path, import_dir|path, scan|path, status, clear_imports", elapsed(start))
            }

            val parts = input.split("\\|".toRegex(), 2)
            val command = parts[0].trim().lowercase()
            val arg = if (parts.size > 1) parts[1].trim() else ""

            when (command) {
                "import" -> {
                    if (arg.isBlank()) return PluginResult.error("Usage: import|<file_path>", elapsed(start))
                    importFile(File(arg))
                }
                "import_json" -> {
                    if (arg.isBlank()) return PluginResult.error("Usage: import_json|<file_path>", elapsed(start))
                    importJsonPersonality(File(arg))
                }
                "import_text" -> {
                    if (arg.isBlank()) return PluginResult.error("Usage: import_text|<file_path>", elapsed(start))
                    importTextFile(File(arg))
                }
                "import_dir" -> {
                    if (arg.isBlank()) return PluginResult.error("Usage: import_dir|<directory_path>", elapsed(start))
                    importDirectory(File(arg))
                }
                "scan" -> {
                    if (arg.isBlank()) return PluginResult.error("Usage: scan|<directory_path>", elapsed(start))
                    scanDirectory(File(arg))
                }
                "status" -> getStatus()
                "clear_imports" -> clearImports()
                else -> PluginResult.error("Unknown command: $command", elapsed(start))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Import failed: ${e.message}", e)
            PluginResult.error("Import failed: ${e.message}", elapsed(start))
        }
    }

    override fun destroy() {
        // No-op
    }

    // ---- Import Routing ----

    private fun importFile(file: File): PluginResult {
        val start = System.currentTimeMillis()
        val validation = validateFile(file)
        if (validation != null) return PluginResult.error(validation, elapsed(start))

        return when (file.extension.lowercase()) {
            "json" -> importJsonPersonality(file)
            "jsonl" -> importJsonlFile(file)
            "txt", "md", "csv" -> importTextFile(file)
            else -> importTextFile(file) // treat unknown as text
        }
    }

    // ---- JSON Personality Import ----

    private fun importJsonPersonality(file: File): PluginResult {
        val start = System.currentTimeMillis()
        val validation = validateFile(file)
        if (validation != null) return PluginResult.error(validation, elapsed(start))

        val content = file.readText(Charsets.UTF_8)
        val json = try {
            JSONObject(content)
        } catch (e: Exception) {
            // Might be a JSON array of memories
            return try {
                val array = JSONArray(content)
                importJsonArray(array, file.name)
            } catch (e2: Exception) {
                PluginResult.error("Invalid JSON: ${e.message}", elapsed(start))
            }
        }

        val results = StringBuilder()
        var chunksCreated = 0
        var traitsSet = 0
        var prefsSet = 0

        // Import personality name
        json.optString("name", "").takeIf { it.isNotBlank() }?.let { name ->
            personalizationPlugin?.execute("set|ai_name|$name")
            prefsSet++
            results.appendLine("Name: $name")
        }

        // Import backstory as high-importance knowledge
        json.optString("backstory", "").takeIf { it.isNotBlank() }?.let { backstory ->
            val chunks = chunkText(backstory, "backstory")
            for (chunk in chunks) {
                ragStore?.addChunk(chunk, "personality_import", "backstory",
                    mapOf("importance" to 0.9f, "source_file" to file.name))
                chunksCreated++
            }
            results.appendLine("Backstory: ${chunks.size} chunk(s) imported")
        }

        // Import traits into EmotionalStateManager
        json.optJSONObject("traits")?.let { traitsObj ->
            val keys = traitsObj.keys()
            while (keys.hasNext()) {
                val traitName = keys.next()
                val value = traitsObj.optDouble(traitName, 0.5).toFloat().coerceIn(0f, 1f)
                emotionalStateManager?.let { esm ->
                    // Set the trait to the target value by computing the needed reinforcement.
                    // Current trait system uses reinforceTrait() with learning rate 0.05.
                    // We call it in a loop to converge toward the target value.
                    val current = esm.getTraitValue(traitName)
                    val delta = value - current
                    // Apply large reinforcement to set it approximately to the target
                    esm.reinforceTrait(traitName, delta / 0.05f)
                    traitsSet++
                }
            }
            results.appendLine("Traits: $traitsSet trait(s) imported")
        }

        // Import preferences into PersonalizationPlugin
        json.optJSONObject("preferences")?.let { prefsObj ->
            val keys = prefsObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = prefsObj.optString(key, "")
                if (value.isNotBlank()) {
                    personalizationPlugin?.execute("set|$key|$value")
                    prefsSet++
                }
            }
            results.appendLine("Preferences: $prefsSet preference(s) imported")
        }

        // Import memories into RAG store
        json.optJSONArray("memories")?.let { memoriesArray ->
            for (i in 0 until memoriesArray.length()) {
                val memory = memoriesArray.optString(i, "").takeIf { it.isNotBlank() } ?: continue
                ragStore?.addMemory(memory, 0.7f)
                chunksCreated++
            }
            results.appendLine("Memories: ${memoriesArray.length()} memory(s) imported")
        }

        // Import structured knowledge
        json.optJSONArray("knowledge")?.let { knowledgeArray ->
            for (i in 0 until knowledgeArray.length()) {
                val entry = knowledgeArray.optJSONObject(i) ?: continue
                val entryContent = entry.optString("content", "").takeIf { it.isNotBlank() } ?: continue
                val category = entry.optString("category", "imported")
                val importance = entry.optDouble("importance", 0.6).toFloat()

                val chunks = chunkText(entryContent, category)
                for (chunk in chunks) {
                    ragStore?.addChunk(chunk, "personality_import", "knowledge",
                        mapOf("importance" to importance, "category" to category,
                              "source_file" to file.name))
                    chunksCreated++
                }
            }
            results.appendLine("Knowledge: ${knowledgeArray.length()} entries imported ($chunksCreated total chunks)")
        }

        // Import directives as high-priority knowledge
        json.optJSONArray("directives")?.let { directivesArray ->
            for (i in 0 until directivesArray.length()) {
                val directive = directivesArray.optString(i, "").takeIf { it.isNotBlank() } ?: continue
                ragStore?.addChunk(directive, "personality_import", "directive",
                    mapOf("importance" to 0.95f, "source_file" to file.name))
                chunksCreated++
            }
            results.appendLine("Directives: ${directivesArray.length()} directive(s) imported")
        }

        // Import conversation history if present
        json.optJSONArray("conversations")?.let { convoArray ->
            for (i in 0 until convoArray.length()) {
                val turn = convoArray.optJSONObject(i) ?: continue
                val role = turn.optString("role", "user")
                val msg = turn.optString("content", "").takeIf { it.isNotBlank() } ?: continue
                ragStore?.addChunk("[$role] $msg", "personality_import", "conversation",
                    mapOf("importance" to 0.5f, "role" to role, "source_file" to file.name))
                chunksCreated++
            }
            results.appendLine("Conversations: ${convoArray.length()} turn(s) imported")
        }

        totalFilesImported++
        totalChunksCreated += chunksCreated
        totalTraitsImported += traitsSet
        totalPreferencesImported += prefsSet

        val summary = "Imported personality from ${file.name}:\n$results" +
                "Total: $chunksCreated chunks, $traitsSet traits, $prefsSet preferences"

        Log.d(TAG, summary)
        return PluginResult.success(summary, elapsed(start))
    }

    // ---- JSON Array Import ----

    private fun importJsonArray(array: JSONArray, sourceName: String): PluginResult {
        val start = System.currentTimeMillis()
        var chunksCreated = 0

        for (i in 0 until array.length()) {
            val item = array.opt(i)
            val content = when (item) {
                is JSONObject -> {
                    // Try common content keys
                    item.optString("content",
                        item.optString("text",
                            item.optString("memory",
                                item.optString("message", item.toString()))))
                }
                is String -> item
                else -> item.toString()
            }

            if (content.isNotBlank()) {
                ragStore?.addChunk(content, "personality_import", "memory",
                    mapOf("importance" to 0.6f, "source_file" to sourceName))
                chunksCreated++
            }
        }

        totalFilesImported++
        totalChunksCreated += chunksCreated

        return PluginResult.success(
            "Imported $chunksCreated entries from JSON array in $sourceName",
            elapsed(start)
        )
    }

    // ---- JSONL Import (one JSON object per line) ----

    private fun importJsonlFile(file: File): PluginResult {
        val start = System.currentTimeMillis()
        val validation = validateFile(file)
        if (validation != null) return PluginResult.error(validation, elapsed(start))

        var chunksCreated = 0
        var errors = 0

        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isBlank()) continue
                try {
                    val obj = JSONObject(trimmed)
                    val content = obj.optString("content",
                        obj.optString("text",
                            obj.optString("memory",
                                obj.optString("message", ""))))

                    if (content.isNotBlank()) {
                        val category = obj.optString("category", "imported")
                        val importance = obj.optDouble("importance", 0.6).toFloat()
                        ragStore?.addChunk(content, "personality_import", category,
                            mapOf("importance" to importance, "source_file" to file.name))
                        chunksCreated++
                    }
                } catch (e: Exception) {
                    errors++
                }
            }
        }

        totalFilesImported++
        totalChunksCreated += chunksCreated

        val errMsg = if (errors > 0) " ($errors lines skipped due to parse errors)" else ""
        return PluginResult.success(
            "Imported $chunksCreated entries from ${file.name}$errMsg",
            elapsed(start)
        )
    }

    // ---- Text/Markdown Import ----

    private fun importTextFile(file: File): PluginResult {
        val start = System.currentTimeMillis()
        val validation = validateFile(file)
        if (validation != null) return PluginResult.error(validation, elapsed(start))

        val content = file.readText(Charsets.UTF_8)
        if (content.isBlank()) {
            return PluginResult.error("File is empty: ${file.name}", elapsed(start))
        }

        val sourceType = when (file.extension.lowercase()) {
            "md" -> "markdown"
            "csv" -> "data"
            else -> "document"
        }

        val chunks = if (file.extension.lowercase() == "md") {
            chunkMarkdown(content)
        } else {
            chunkText(content, sourceType)
        }

        var chunksCreated = 0
        for (chunk in chunks) {
            ragStore?.addChunk(chunk, "personality_import", sourceType,
                mapOf("importance" to 0.6f, "source_file" to file.name))
            chunksCreated++
        }

        totalFilesImported++
        totalChunksCreated += chunksCreated

        return PluginResult.success(
            "Imported ${file.name}: $chunksCreated chunk(s) created from ${content.length} characters",
            elapsed(start)
        )
    }

    // ---- Directory Import ----

    private fun importDirectory(dir: File): PluginResult {
        val start = System.currentTimeMillis()

        if (!dir.exists()) return PluginResult.error("Directory not found: ${dir.absolutePath}", elapsed(start))
        if (!dir.isDirectory) return PluginResult.error("Not a directory: ${dir.absolutePath}", elapsed(start))

        val files = findImportableFiles(dir)
        if (files.isEmpty()) {
            return PluginResult.error("No importable files found in ${dir.absolutePath}", elapsed(start))
        }

        val results = StringBuilder()
        var successCount = 0
        var failCount = 0

        for (file in files) {
            val result = importFile(file)
            if (result.isSuccess) {
                successCount++
                results.appendLine("OK: ${file.name}")
            } else {
                failCount++
                results.appendLine("FAIL: ${file.name} - ${result.errorMessage}")
            }
        }

        val summary = "Directory import complete: $successCount succeeded, $failCount failed\n$results"
        return PluginResult.success(summary, elapsed(start))
    }

    // ---- Directory Scan (dry run) ----

    private fun scanDirectory(dir: File): PluginResult {
        val start = System.currentTimeMillis()

        if (!dir.exists()) return PluginResult.error("Directory not found: ${dir.absolutePath}", elapsed(start))
        if (!dir.isDirectory) return PluginResult.error("Not a directory: ${dir.absolutePath}", elapsed(start))

        val files = findImportableFiles(dir)
        if (files.isEmpty()) {
            return PluginResult.success("No importable files found in ${dir.absolutePath}", elapsed(start))
        }

        val summary = buildString {
            appendLine("Found ${files.size} importable file(s) in ${dir.absolutePath}:")
            for (file in files) {
                val sizeMb = file.length() / (1024f * 1024f)
                appendLine("  ${file.name} (${file.extension}, ${String.format("%.2f", sizeMb)} MB)")
            }
            appendLine("\nUse import_dir|${dir.absolutePath} to import all files.")
        }

        return PluginResult.success(summary, elapsed(start))
    }

    // ---- Status ----

    private fun getStatus(): PluginResult {
        val start = System.currentTimeMillis()
        val ragChunks = ragStore?.getChunks()?.size ?: 0
        val ragStats = ragStore?.getMemRLStats() ?: emptyMap()
        val traits = emotionalStateManager?.getPersonalityTraits() ?: emptyMap()
        val profile = emotionalStateManager?.getPersonalityProfile() ?: emptyMap()

        val status = buildString {
            appendLine("=== Personality Import Status ===")
            appendLine("Files imported this session: $totalFilesImported")
            appendLine("Chunks created this session: $totalChunksCreated")
            appendLine("Traits imported this session: $totalTraitsImported")
            appendLine("Preferences imported this session: $totalPreferencesImported")
            appendLine()
            appendLine("=== RAG Memory ===")
            appendLine("Total chunks: $ragChunks")
            appendLine("Avg Q-value: ${ragStats["avg_q_value"] ?: "N/A"}")
            appendLine("Total retrievals: ${ragStats["total_retrievals"] ?: 0}")
            appendLine()
            appendLine("=== Personality Traits ===")
            if (traits.isEmpty()) {
                appendLine("No traits defined yet.")
            } else {
                for ((trait, value) in traits) {
                    val bar = "█".repeat((value * 10).toInt()) + "░".repeat(10 - (value * 10).toInt())
                    appendLine("  $trait: $bar ${String.format("%.2f", value)}")
                }
            }
            appendLine()
            appendLine("=== Profile ===")
            appendLine("Dominant traits: ${profile["dominant_traits"] ?: "none"}")
            appendLine("Balanced traits: ${profile["balanced_traits"] ?: "none"}")
        }

        return PluginResult.success(status, elapsed(start))
    }

    // ---- Clear ----

    private fun clearImports(): PluginResult {
        val start = System.currentTimeMillis()
        try {
            // Remove only personality_import sourced chunks
            val store = ragStore
            if (store != null) {
                val importedChunks = store.getChunks()
                    .filter { it.source == "personality_import" }
                    .map { it.chunkId }
                for (chunkId in importedChunks) {
                    store.removeChunk(chunkId)
                }
                totalChunksCreated = 0
                totalFilesImported = 0
                totalTraitsImported = 0
                totalPreferencesImported = 0
                return PluginResult.success(
                    "Cleared ${importedChunks.size} imported chunks from RAG store",
                    elapsed(start)
                )
            }
            return PluginResult.error("RAG store not available", elapsed(start))
        } catch (e: Exception) {
            return PluginResult.error("Clear failed: ${e.message}", elapsed(start))
        }
    }

    // ---- Helpers ----

    private fun validateFile(file: File): String? {
        if (!file.exists()) return "File not found: ${file.absolutePath}"
        if (!file.isFile) return "Not a file: ${file.absolutePath}"
        if (!file.canRead()) return "Cannot read file: ${file.absolutePath}"
        if (file.length() > MAX_FILE_SIZE_BYTES) {
            return "File too large (${file.length() / (1024 * 1024)} MB). Maximum: ${MAX_FILE_SIZE_BYTES / (1024 * 1024)} MB"
        }
        return null
    }

    private fun findImportableFiles(dir: File): List<File> {
        return dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in SUPPORTED_EXTENSIONS }
            ?.sortedBy { it.name }
            .orEmpty()
    }

    /**
     * Split long text into chunks of approximately [MAX_CHUNK_CHARS] characters,
     * splitting on paragraph or sentence boundaries where possible.
     */
    private fun chunkText(text: String, label: String): List<String> {
        if (text.length <= MAX_CHUNK_CHARS) return listOf(text)

        val chunks = mutableListOf<String>()
        val paragraphs = text.split("\n\n")

        val buffer = StringBuilder()
        for (paragraph in paragraphs) {
            if (buffer.length + paragraph.length + 2 > MAX_CHUNK_CHARS && buffer.isNotEmpty()) {
                chunks.add(buffer.toString().trim())
                buffer.clear()
            }

            if (paragraph.length > MAX_CHUNK_CHARS) {
                // Paragraph itself is too long — split on sentences
                if (buffer.isNotEmpty()) {
                    chunks.add(buffer.toString().trim())
                    buffer.clear()
                }
                chunks.addAll(chunkBySentence(paragraph))
            } else {
                if (buffer.isNotEmpty()) buffer.append("\n\n")
                buffer.append(paragraph)
            }
        }

        if (buffer.isNotEmpty()) {
            chunks.add(buffer.toString().trim())
        }

        return chunks.filter { it.isNotBlank() }
    }

    private fun chunkBySentence(text: String): List<String> {
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
        val chunks = mutableListOf<String>()
        val buffer = StringBuilder()

        for (sentence in sentences) {
            if (buffer.length + sentence.length + 1 > MAX_CHUNK_CHARS && buffer.isNotEmpty()) {
                chunks.add(buffer.toString().trim())
                buffer.clear()
            }
            if (buffer.isNotEmpty()) buffer.append(" ")
            buffer.append(sentence)
        }

        if (buffer.isNotEmpty()) {
            chunks.add(buffer.toString().trim())
        }

        return chunks
    }

    /**
     * Parse markdown into chunks by heading sections.
     */
    private fun chunkMarkdown(text: String): List<String> {
        val sections = mutableListOf<String>()
        val currentSection = StringBuilder()

        for (line in text.lines()) {
            if (line.startsWith("#") && currentSection.isNotEmpty()) {
                sections.add(currentSection.toString().trim())
                currentSection.clear()
            }
            currentSection.appendLine(line)
        }

        if (currentSection.isNotEmpty()) {
            sections.add(currentSection.toString().trim())
        }

        // Further chunk sections that are still too long
        return sections.flatMap { section ->
            if (section.length > MAX_CHUNK_CHARS) {
                chunkText(section, "markdown")
            } else {
                listOf(section)
            }
        }.filter { it.isNotBlank() }
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start
}
