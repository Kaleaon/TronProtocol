package com.tronprotocol.app.guidance

import android.text.TextUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque

/**
 * Anthropic API client with auth, rate limiting and robust error handling.
 */
class AnthropicApiClient(
    maxRequestsPerMinute: Int,
    minRequestIntervalMs: Long
) {

    private val rateLock = Any()
    private val requestTimes = ArrayDeque<Long>()
    private val maxRequestsPerMinute: Int = maxOf(1, maxRequestsPerMinute)
    private val minRequestIntervalMs: Long = maxOf(0L, minRequestIntervalMs)
    private var lastRequestMs: Long = 0L

    @Throws(AnthropicException::class)
    fun createGuidance(apiKey: String?, model: String, prompt: String?, maxTokens: Int): String {
        if (TextUtils.isEmpty(apiKey)) {
            throw AnthropicException("Anthropic API key is missing", 401, false)
        }
        if (TextUtils.isEmpty(prompt)) {
            throw AnthropicException("Prompt is empty", 400, false)
        }

        throttle()

        var connection: HttpURLConnection? = null
        try {
            val url = URL(API_URL)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 20000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("x-api-key", apiKey)
            connection.setRequestProperty("anthropic-version", API_VERSION)

            val body = JSONObject().apply {
                put("model", model)
                put("max_tokens", maxTokens)

                val messages = JSONArray()
                val userMessage = JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                }
                messages.put(userMessage)
                put("messages", messages)
            }

            val payload = body.toString().toByteArray(StandardCharsets.UTF_8)
            connection.outputStream.use { os ->
                os.write(payload)
            }

            val status = connection.responseCode
            val responseBody = readBody(
                if (status >= 400) connection.errorStream else connection.inputStream
            )

            if (status >= 400) {
                throw buildError(responseBody, status)
            }

            val responseJson = JSONObject(responseBody)
            val content = responseJson.optJSONArray("content")
            if (content == null || content.length() == 0) {
                throw AnthropicException("Anthropic response did not contain content", status, true)
            }

            val answer = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i)
                if (block != null && "text" == block.optString("type")) {
                    answer.append(block.optString("text", ""))
                }
            }

            if (answer.isEmpty()) {
                throw AnthropicException("Anthropic response text was empty", status, true)
            }

            return answer.toString().trim()
        } catch (e: AnthropicException) {
            throw e
        } catch (e: Exception) {
            throw AnthropicException("Failed to call Anthropic API: ${e.message}", 500, true)
        } finally {
            connection?.disconnect()
        }
    }

    @Throws(AnthropicException::class)
    private fun throttle() {
        synchronized(rateLock) {
            var now = System.currentTimeMillis()

            while (requestTimes.isNotEmpty()) {
                val oldest = requestTimes.peekFirst() ?: break
                if (now - oldest <= 60000) break
                requestTimes.pollFirst()
                now = System.currentTimeMillis()
            }

            if (requestTimes.size >= maxRequestsPerMinute) {
                throw AnthropicException("Rate limit hit in client guardrail", 429, true)
            }

            val sinceLast = now - lastRequestMs
            if (sinceLast < minRequestIntervalMs) {
                val wait = minRequestIntervalMs - sinceLast
                try {
                    Thread.sleep(wait)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw AnthropicException("Rate limiter interrupted", 429, true)
                }
            }

            val stamp = System.currentTimeMillis()
            requestTimes.addLast(stamp)
            lastRequestMs = stamp
        }
    }

    private fun buildError(body: String, status: Int): AnthropicException {
        var msg = "Anthropic API error"
        try {
            val json = JSONObject(body)
            val error = json.optJSONObject("error")
            if (error != null) {
                msg = error.optString("message", msg)
            }
        } catch (ignored: Exception) {
            if (!TextUtils.isEmpty(body)) {
                msg = body
            }
        }

        val retryable = status == 429 || status >= 500
        return AnthropicException(msg, status, retryable)
    }

    @Throws(Exception::class)
    private fun readBody(inputStream: InputStream?): String {
        if (inputStream == null) {
            return ""
        }
        BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line)
            }
            return sb.toString()
        }
    }

    class AnthropicException(
        message: String,
        val statusCode: Int,
        val isRetryable: Boolean
    ) : Exception(message)

    companion object {
        const val MODEL_SONNET = "claude-sonnet-4-5-20250929"
        const val MODEL_OPUS = "claude-opus-4-6"

        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val API_VERSION = "2023-06-01"
    }
}
