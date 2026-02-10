package com.tronprotocol.app.plugins

import android.content.Context
import java.util.regex.Pattern

/**
 * Text Analysis Plugin.
 *
 * Provides text processing utilities.
 * Inspired by ToolNeuron's DevUtilsPlugin and landseek's tools.
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
        "Analyze text: word count, character count, extract URLs/emails, " +
            "find patterns, transform text (uppercase, lowercase, reverse)."

    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val startTime = System.currentTimeMillis()

        return try {
            // Parse command: "command|text"
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
                "extract_urls" -> extractUrls(text)
                "extract_emails" -> extractEmails(text)
                "word_count" -> countWords(text).toString()
                "char_count" -> text.length.toString()
                else -> throw Exception("Unknown command: $command")
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
        val wordCount = countWords(text)
        val lineCount = text.split("\n").size
        val sentenceCount = text.split("[.!?]+".toRegex()).size

        return "Characters: $charCount\n" +
            "Words: $wordCount\n" +
            "Lines: $lineCount\n" +
            "Sentences: $sentenceCount"
    }

    /**
     * Count words in text.
     */
    private fun countWords(text: String?): Int {
        if (text.isNullOrBlank()) {
            return 0
        }
        return text.trim().split("\\s+".toRegex()).size
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
        val urls = StringBuilder()
        var count = 0

        while (matcher.find()) {
            urls.append(matcher.group()).append("\n")
            count++
        }

        if (count == 0) {
            return "No URLs found"
        }

        return "Found $count URL(s):\n$urls"
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
        val emails = StringBuilder()
        var count = 0

        while (matcher.find()) {
            emails.append(matcher.group()).append("\n")
            count++
        }

        if (count == 0) {
            return "No email addresses found"
        }

        return "Found $count email(s):\n$emails"
    }

    override fun initialize(context: Context) {
        this.context = context
    }

    override fun destroy() {
        context = null
    }
}
