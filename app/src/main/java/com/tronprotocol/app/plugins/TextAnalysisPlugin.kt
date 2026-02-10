package com.tronprotocol.app.plugins

import android.content.Context
import java.util.regex.Pattern

/**
 * Text Analysis Plugin.
 *
 * Provides text processing and analysis utilities.
 *
 * Commands:
 *   count/stats|text      - Comprehensive text statistics
 *   uppercase|text        - Convert to uppercase
 *   lowercase|text        - Convert to lowercase
 *   reverse|text          - Reverse text
 *   trim|text             - Trim whitespace
 *   title|text            - Title case
 *   extract_urls|text     - Extract all URLs
 *   extract_emails|text   - Extract all email addresses
 *   word_count|text       - Count words
 *   char_count|text       - Count characters
 *   sentences|text        - Extract individual sentences
 *   readability|text      - Flesch-Kincaid readability score
 *   frequency|text        - Word frequency analysis
 */
class TextAnalysisPlugin : Plugin {

    companion object {
        private const val TAG = "TextAnalysisPlugin"
        private const val ID = "text_analysis"
    }

    private var context: Context? = null

    override val id: String = ID

    override val name: String = "Text Analysis"

    override val description: String =
        "Analyze text: stats, uppercase, lowercase, reverse, trim, title, " +
            "extract_urls, extract_emails, word_count, char_count, sentences, " +
            "readability, frequency. Format: command|text"

    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val startTime = System.currentTimeMillis()

        return try {
            val parts = input.split("\\|".toRegex(), 2)
            if (parts.size < 2) {
                throw Exception("Invalid format. Use: command|text")
            }

            val command = parts[0].trim().lowercase()
            val text = parts[1]

            val result = when (command) {
                "count", "stats" -> getTextStats(text)
                "uppercase" -> text.uppercase()
                "lowercase" -> text.lowercase()
                "reverse" -> text.reversed()
                "trim" -> text.trim()
                "title" -> toTitleCase(text)
                "extract_urls" -> extractUrls(text)
                "extract_emails" -> extractEmails(text)
                "word_count" -> countWords(text).toString()
                "char_count" -> text.length.toString()
                "sentences" -> extractSentences(text)
                "readability" -> calculateReadability(text)
                "frequency" -> wordFrequency(text)
                else -> throw Exception(
                    "Unknown command: $command. " +
                        "Available: stats, uppercase, lowercase, reverse, trim, title, " +
                        "extract_urls, extract_emails, word_count, char_count, " +
                        "sentences, readability, frequency"
                )
            }

            val duration = System.currentTimeMillis() - startTime
            PluginResult.success(result, duration)
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            PluginResult.error("Text analysis failed: ${e.message}", duration)
        }
    }

    /**
     * Get comprehensive text statistics.
     */
    private fun getTextStats(text: String): String {
        val charCount = text.length
        val charCountNoSpaces = text.count { !it.isWhitespace() }
        val wordCount = countWords(text)
        val lineCount = if (text.isEmpty()) 0 else text.split("\n").size
        val sentences = splitSentences(text)
        val sentenceCount = sentences.size
        val avgWordLength = if (wordCount > 0) {
            val words = text.trim().split("\\s+".toRegex())
            words.sumOf { it.length }.toDouble() / words.size
        } else 0.0
        val avgSentenceLength = if (sentenceCount > 0) {
            wordCount.toDouble() / sentenceCount
        } else 0.0
        val paragraphCount = if (text.isEmpty()) 0 else text.split("\n\\s*\n".toRegex()).size

        return buildString {
            append("Characters: $charCount\n")
            append("Characters (no spaces): $charCountNoSpaces\n")
            append("Words: $wordCount\n")
            append("Sentences: $sentenceCount\n")
            append("Lines: $lineCount\n")
            append("Paragraphs: $paragraphCount\n")
            append("Avg word length: ${"%.1f".format(avgWordLength)} chars\n")
            append("Avg sentence length: ${"%.1f".format(avgSentenceLength)} words")
        }
    }

    /**
     * Count words in text.
     */
    private fun countWords(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        return text.trim().split("\\s+".toRegex()).size
    }

    /**
     * Convert text to title case.
     */
    private fun toTitleCase(text: String): String {
        return text.split("\\s+".toRegex()).joinToString(" ") { word ->
            if (word.isNotEmpty()) {
                word[0].uppercase() + word.substring(1).lowercase()
            } else word
        }
    }

    /**
     * Extract all URLs from text.
     */
    private fun extractUrls(text: String): String {
        val urlPattern = Pattern.compile(
            "(https?://[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}(/[^\\s]*)?)",
            Pattern.CASE_INSENSITIVE
        )

        val matcher = urlPattern.matcher(text)
        val urls = mutableListOf<String>()

        while (matcher.find()) {
            urls.add(matcher.group())
        }

        if (urls.isEmpty()) return "No URLs found."

        return buildString {
            append("Found ${urls.size} URL(s):\n")
            urls.forEachIndexed { i, url ->
                append("${i + 1}. $url\n")
            }
        }.trimEnd()
    }

    /**
     * Extract all email addresses from text.
     */
    private fun extractEmails(text: String): String {
        val emailPattern = Pattern.compile(
            "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}",
            Pattern.CASE_INSENSITIVE
        )

        val matcher = emailPattern.matcher(text)
        val emails = mutableListOf<String>()

        while (matcher.find()) {
            emails.add(matcher.group())
        }

        if (emails.isEmpty()) return "No email addresses found."

        return buildString {
            append("Found ${emails.size} email(s):\n")
            emails.forEachIndexed { i, email ->
                append("${i + 1}. $email\n")
            }
        }.trimEnd()
    }

    /**
     * Extract individual sentences from text.
     */
    private fun extractSentences(text: String): String {
        val sentences = splitSentences(text)
        if (sentences.isEmpty()) return "No sentences found."

        return buildString {
            append("Found ${sentences.size} sentence(s):\n")
            sentences.forEachIndexed { i, sentence ->
                append("${i + 1}. $sentence\n")
            }
        }.trimEnd()
    }

    private fun splitSentences(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return text.split("(?<=[.!?])\\s+".toRegex())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Calculate Flesch-Kincaid readability score.
     *
     * Score interpretation:
     *   90-100: Very easy (5th grade)
     *   80-89:  Easy (6th grade)
     *   70-79:  Fairly easy (7th grade)
     *   60-69:  Standard (8th-9th grade)
     *   50-59:  Fairly difficult (10th-12th grade)
     *   30-49:  Difficult (college)
     *   0-29:   Very difficult (graduate)
     */
    private fun calculateReadability(text: String): String {
        val words = if (text.isBlank()) emptyList()
        else text.trim().split("\\s+".toRegex())
        val wordCount = words.size

        if (wordCount == 0) return "Cannot calculate readability: no words."

        val sentences = splitSentences(text)
        val sentenceCount = sentences.size.coerceAtLeast(1)

        var syllableCount = 0
        for (word in words) {
            syllableCount += countSyllables(word)
        }

        val score = 206.835 -
            1.015 * (wordCount.toDouble() / sentenceCount) -
            84.6 * (syllableCount.toDouble() / wordCount)

        val level = when {
            score >= 90 -> "Very Easy (5th grade)"
            score >= 80 -> "Easy (6th grade)"
            score >= 70 -> "Fairly Easy (7th grade)"
            score >= 60 -> "Standard (8th-9th grade)"
            score >= 50 -> "Fairly Difficult (10th-12th grade)"
            score >= 30 -> "Difficult (College)"
            else -> "Very Difficult (Graduate)"
        }

        return buildString {
            append("Flesch-Kincaid Readability Score: ${"%.1f".format(score)}\n")
            append("Level: $level\n")
            append("Words: $wordCount, Sentences: $sentenceCount, Syllables: $syllableCount")
        }
    }

    /**
     * Estimate syllable count for a word.
     */
    private fun countSyllables(word: String): Int {
        val w = word.lowercase().replace("[^a-z]".toRegex(), "")
        if (w.isEmpty()) return 0
        if (w.length <= 3) return 1

        var count = 0
        var prevVowel = false
        val vowels = "aeiouy"

        for (c in w) {
            val isVowel = c in vowels
            if (isVowel && !prevVowel) count++
            prevVowel = isVowel
        }

        // Adjust for silent 'e' at end
        if (w.endsWith("e") && count > 1) count--
        // Words like "le" at end
        if (w.endsWith("le") && w.length > 2 && w[w.length - 3] !in vowels) count++

        return count.coerceAtLeast(1)
    }

    /**
     * Word frequency analysis - returns top words sorted by frequency.
     */
    private fun wordFrequency(text: String): String {
        if (text.isBlank()) return "No words to analyze."

        val words = text.lowercase()
            .replace("[^a-z0-9\\s]".toRegex(), "")
            .split("\\s+".toRegex())
            .filter { it.isNotEmpty() }

        if (words.isEmpty()) return "No words to analyze."

        val freq = mutableMapOf<String, Int>()
        for (word in words) {
            freq[word] = (freq[word] ?: 0) + 1
        }

        val sorted = freq.entries.sortedByDescending { it.value }.take(20)
        val total = words.size

        return buildString {
            append("Word Frequency (top ${sorted.size} of ${freq.size} unique words):\n")
            sorted.forEach { (word, count) ->
                val pct = "%.1f".format(count.toDouble() / total * 100)
                append("  $word: $count ($pct%)\n")
            }
        }.trimEnd()
    }

    override fun initialize(context: Context) {
        this.context = context
    }

    override fun destroy() {
        context = null
    }
}
