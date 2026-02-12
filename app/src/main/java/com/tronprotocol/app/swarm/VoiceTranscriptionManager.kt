package com.tronprotocol.app.swarm

import android.util.Log
import com.tronprotocol.app.plugins.PicoClawBridgePlugin
import com.tronprotocol.app.swarm.SwarmNode.NodeCapability
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Voice Transcription Manager — routes audio transcription through the swarm.
 *
 * Transcription pipeline:
 * 1. If a PicoClaw edge node with VOICE_TRANSCRIPTION capability is available,
 *    route through it (PicoClaw uses Groq's free Whisper-based transcription)
 * 2. If no edge nodes are available, call Groq's Whisper API directly
 * 3. If direct API also fails, return an error
 *
 * Groq provides free Whisper-based voice transcription which PicoClaw
 * uses for Telegram voice message processing. This manager extends
 * that capability to TronProtocol.
 */
class VoiceTranscriptionManager(
    private val bridge: PicoClawBridgePlugin
) {
    companion object {
        private const val TAG = "VoiceTranscription"
        private const val GROQ_TRANSCRIPTION_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        private const val DEFAULT_MODEL = "whisper-large-v3"
    }

    @Volatile var groqApiKey: String? = null

    private val transcriptionCount = AtomicLong(0)
    private val swarmRouted = AtomicLong(0)
    private val directRouted = AtomicLong(0)
    private val errorCount = AtomicInteger(0)

    /**
     * Transcribe audio from a URL. Tries edge nodes first, then direct API.
     *
     * @param audioUrl URL of the audio file to transcribe
     * @param language ISO language code (e.g., "en", "zh", "ja")
     * @return TranscriptionResult with the transcribed text
     */
    fun transcribe(audioUrl: String, language: String = "en"): TranscriptionResult {
        val startTime = System.currentTimeMillis()

        // Strategy 1: Route through PicoClaw edge node
        val edgeResult = transcribeViaSwarm(audioUrl, language, startTime)
        if (edgeResult != null) {
            swarmRouted.incrementAndGet()
            transcriptionCount.incrementAndGet()
            return edgeResult
        }

        // Strategy 2: Direct Groq API call
        val directResult = transcribeDirectGroq(audioUrl, language, startTime)
        if (directResult.success) {
            directRouted.incrementAndGet()
            transcriptionCount.incrementAndGet()
            return directResult
        }

        errorCount.incrementAndGet()
        return TranscriptionResult(
            success = false,
            text = null,
            latencyMs = System.currentTimeMillis() - startTime,
            route = "none",
            error = "All transcription routes exhausted"
        )
    }

    /**
     * Transcribe by delegating to a PicoClaw edge node with voice capability.
     */
    private fun transcribeViaSwarm(audioUrl: String, language: String, startTime: Long): TranscriptionResult? {
        val node = bridge.getBestNodeForCapability(NodeCapability.VOICE_TRANSCRIPTION)
            ?: return null

        return try {
            val msg = SwarmProtocol.voiceTranscribeRequest(
                "tronprotocol_android",
                node.nodeId,
                audioUrl,
                language
            )

            val response = bridge.dispatchToNode(node, "/voice/transcribe", msg.toJson().toString())
            node.recordSuccess()

            val json = JSONObject(response)
            val text = json.optString("text",
                json.optJSONObject("payload")?.optString("text", null)
            )

            if (text != null) {
                TranscriptionResult(
                    success = true,
                    text = text,
                    latencyMs = System.currentTimeMillis() - startTime,
                    route = "swarm:${node.nodeId}",
                    model = json.optString("model", DEFAULT_MODEL)
                )
            } else {
                node.recordFailure()
                null
            }
        } catch (e: Exception) {
            node.recordFailure()
            Log.w(TAG, "Swarm transcription failed on ${node.nodeId}: ${e.message}")
            null
        }
    }

    /**
     * Transcribe directly via Groq's Whisper API.
     * Requires groqApiKey to be set.
     */
    private fun transcribeDirectGroq(audioUrl: String, language: String, startTime: Long): TranscriptionResult {
        val apiKey = groqApiKey
        if (apiKey == null) {
            return TranscriptionResult(
                success = false,
                text = null,
                latencyMs = System.currentTimeMillis() - startTime,
                route = "direct_groq",
                error = "Groq API key not configured"
            )
        }

        return try {
            // Send transcription request with audio URL
            val payload = JSONObject().apply {
                put("model", DEFAULT_MODEL)
                put("url", audioUrl)
                put("language", language)
                put("response_format", "json")
            }

            val response = postJson(GROQ_TRANSCRIPTION_URL, apiKey, payload.toString())
            val json = JSONObject(response)
            val text = json.optString("text", null)

            if (text != null) {
                TranscriptionResult(
                    success = true,
                    text = text,
                    latencyMs = System.currentTimeMillis() - startTime,
                    route = "direct_groq",
                    model = DEFAULT_MODEL
                )
            } else {
                TranscriptionResult(
                    success = false,
                    text = null,
                    latencyMs = System.currentTimeMillis() - startTime,
                    route = "direct_groq",
                    error = "No text in Groq response"
                )
            }
        } catch (e: Exception) {
            TranscriptionResult(
                success = false,
                text = null,
                latencyMs = System.currentTimeMillis() - startTime,
                route = "direct_groq",
                error = "Groq API failed: ${e.message}"
            )
        }
    }

    private fun postJson(endpoint: String, apiKey: String, body: String): String {
        val url = URL(endpoint)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $apiKey")

        BufferedWriter(OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8)).use { writer ->
            writer.write(body)
            writer.flush()
        }

        val code = connection.responseCode
        try {
            val stream = if (code in 200 until 400) connection.inputStream else connection.errorStream
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                val sb = StringBuilder()
                var line = reader.readLine()
                while (line != null) {
                    sb.append(line)
                    line = reader.readLine()
                }
                if (code >= 400) throw RuntimeException("Groq API error $code: ${sb.toString()}")
                return sb.toString()
            }
        } finally {
            connection.disconnect()
        }
    }

    fun getStats(): Map<String, Any> = mapOf(
        "total_transcriptions" to transcriptionCount.get(),
        "swarm_routed" to swarmRouted.get(),
        "direct_routed" to directRouted.get(),
        "errors" to errorCount.get(),
        "groq_key_configured" to (groqApiKey != null),
        "voice_capable_nodes" to bridge.getNodesByCapability(NodeCapability.VOICE_TRANSCRIPTION).size
    )

    data class TranscriptionResult(
        val success: Boolean,
        val text: String?,
        val latencyMs: Long,
        val route: String,
        val model: String? = null,
        val error: String? = null
    )
}
