package com.tronprotocol.app.plugins

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device text-to-speech for spoken responses.
 *
 * Commands:
 *   speak|text             – Speak text aloud
 *   speak_wait|text        – Speak and wait for completion
 *   set_pitch|float        – Set pitch (0.5 - 2.0)
 *   set_rate|float         – Set speech rate (0.5 - 2.0)
 *   set_locale|language    – Set language (en, es, fr, de, etc.)
 *   stop                   – Stop current speech
 *   status                 – TTS engine status
 */
class VoiceSynthesisPlugin : Plugin {

    override val id: String = ID
    override val name: String = "Voice Synthesis"
    override val description: String =
        "Text-to-speech output. Commands: speak|text, speak_wait|text, set_pitch|float, set_rate|float, set_locale|lang, stop, status"
    override var isEnabled: Boolean = true

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pitch = 1.0f
    private var rate = 1.0f

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            val parts = input.split("\\|".toRegex(), 2)
            val command = parts[0].trim().lowercase()

            when (command) {
                "speak" -> {
                    if (!ttsReady) return PluginResult.error("TTS not ready", elapsed(start))
                    val text = parts.getOrNull(1)?.trim()
                        ?: return PluginResult.error("Usage: speak|text", elapsed(start))
                    tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "tron_${System.currentTimeMillis()}")
                    PluginResult.success("Speaking: ${text.take(100)}...", elapsed(start))
                }
                "speak_wait" -> {
                    if (!ttsReady) return PluginResult.error("TTS not ready", elapsed(start))
                    val text = parts.getOrNull(1)?.trim()
                        ?: return PluginResult.error("Usage: speak_wait|text", elapsed(start))
                    val latch = CountDownLatch(1)
                    val uttId = "tron_wait_${System.currentTimeMillis()}"
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) { if (utteranceId == uttId) latch.countDown() }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { if (utteranceId == uttId) latch.countDown() }
                    })
                    tts?.speak(text, TextToSpeech.QUEUE_ADD, null, uttId)
                    latch.await(30, TimeUnit.SECONDS)
                    PluginResult.success("Finished speaking: ${text.take(100)}", elapsed(start))
                }
                "set_pitch" -> {
                    val p = parts.getOrNull(1)?.trim()?.toFloatOrNull()
                        ?: return PluginResult.error("Usage: set_pitch|0.5-2.0", elapsed(start))
                    pitch = p.coerceIn(0.5f, 2.0f)
                    tts?.setPitch(pitch)
                    PluginResult.success("Pitch set to $pitch", elapsed(start))
                }
                "set_rate" -> {
                    val r = parts.getOrNull(1)?.trim()?.toFloatOrNull()
                        ?: return PluginResult.error("Usage: set_rate|0.5-2.0", elapsed(start))
                    rate = r.coerceIn(0.5f, 2.0f)
                    tts?.setSpeechRate(rate)
                    PluginResult.success("Rate set to $rate", elapsed(start))
                }
                "set_locale" -> {
                    val lang = parts.getOrNull(1)?.trim()
                        ?: return PluginResult.error("Usage: set_locale|en", elapsed(start))
                    val locale = Locale(lang)
                    val result = tts?.setLanguage(locale)
                    val supported = result != TextToSpeech.LANG_NOT_SUPPORTED && result != TextToSpeech.LANG_MISSING_DATA
                    PluginResult.success("Locale $lang: ${if (supported) "supported" else "not supported"}", elapsed(start))
                }
                "stop" -> {
                    tts?.stop()
                    PluginResult.success("Speech stopped", elapsed(start))
                }
                "status" -> {
                    PluginResult.success(JSONObject().apply {
                        put("ready", ttsReady)
                        put("pitch", pitch)
                        put("rate", rate)
                        put("engine", tts?.defaultEngine ?: "unknown")
                    }.toString(2), elapsed(start))
                }
                else -> PluginResult.error("Unknown command: $command", elapsed(start))
            }
        } catch (e: Exception) {
            PluginResult.error("TTS error: ${e.message}", elapsed(start))
        }
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {
        tts = TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.setPitch(pitch)
                tts?.setSpeechRate(rate)
                tts?.language = Locale.US
            }
            Log.d(TAG, "TTS initialized: ready=$ttsReady")
        }
    }

    override fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    companion object {
        const val ID = "voice_synthesis"
        private const val TAG = "VoiceSynthesis"
    }
}
