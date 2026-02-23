package com.tronprotocol.app.avatar

import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Text-to-Speech (TTS) engine via MNN for the TaoAvatar pipeline.
 *
 * Converts text to speech audio using on-device MNN TTS models (BERT-VITS2 for Chinese,
 * SuperTonic for English). Output PCM audio can be fed directly into A2BSEngine for
 * synchronized lip-sync animation.
 *
 * Pipeline: Text -> Tokenize -> MNN TTS Model -> PCM Audio -> [optional] A2BS -> BlendShapes
 *
 * Native libraries required:
 * - libMNN.so (core framework)
 * - libmnn_tts.so (text-to-speech module)
 *
 * Models:
 * - BERT-VITS2-MNN: Chinese TTS with natural prosody
 * - SuperTonic-MNN: English TTS with natural prosody
 */
class TTSEngine {

    private val nativeHandle = AtomicLong(0)
    private val isInitialized = AtomicBoolean(false)
    private var modelType: TTSModelType = TTSModelType.SUPERTONIC_EN

    val isReady: Boolean get() = isInitialized.get() && nativeHandle.get() != 0L

    /** Supported TTS model types. */
    enum class TTSModelType(val language: String, val sampleRate: Int) {
        BERT_VITS2_ZH("zh", 22050),
        SUPERTONIC_EN("en", 22050),
        CUSTOM("custom", 22050)
    }

    /** TTS synthesis configuration. */
    data class SynthesisConfig(
        val speed: Float = 1.0f,
        val pitch: Float = 1.0f,
        val volume: Float = 1.0f,
        val speakerId: Int = 0
    )

    /** TTS output containing synthesized audio. */
    data class TTSResult(
        val audioData: ShortArray,
        val sampleRate: Int,
        val durationMs: Long,
        val synthesisTimeMs: Long,
        val text: String
    ) {
        /** Real-time factor: synthesis time / audio duration. Lower is better. */
        val realTimeFactor: Float
            get() = if (durationMs > 0) synthesisTimeMs.toFloat() / durationMs else Float.MAX_VALUE

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TTSResult) return false
            return audioData.contentEquals(other.audioData) && sampleRate == other.sampleRate &&
                    durationMs == other.durationMs && text == other.text
        }

        override fun hashCode(): Int {
            var result = audioData.contentHashCode()
            result = 31 * result + sampleRate
            result = 31 * result + durationMs.hashCode()
            result = 31 * result + text.hashCode()
            return result
        }
    }

    /**
     * Initialize the TTS engine with a model directory.
     *
     * @param modelDir Directory containing TTS model files.
     * @param type TTS model type.
     * @return true if initialized successfully.
     */
    fun initialize(modelDir: String, type: TTSModelType = TTSModelType.SUPERTONIC_EN): Boolean {
        if (isInitialized.get()) {
            Log.w(TAG, "TTS engine already initialized")
            return true
        }

        if (!nativeAvailable.get()) {
            Log.e(TAG, "TTS native libraries not available")
            return false
        }

        val dir = File(modelDir)
        if (!dir.exists() || !dir.isDirectory) {
            Log.e(TAG, "TTS model directory not found: $modelDir")
            return false
        }

        return try {
            val handle = nativeCreateTTS()
            if (handle == 0L) {
                Log.e(TAG, "nativeCreateTTS() returned null handle")
                return false
            }
            nativeHandle.set(handle)

            val loadResult = nativeLoadModel(handle, modelDir, type.language)
            if (!loadResult) {
                Log.e(TAG, "Failed to load TTS model from $modelDir")
                nativeDestroyTTS(handle)
                nativeHandle.set(0)
                return false
            }

            modelType = type
            isInitialized.set(true)
            Log.d(TAG, "TTS engine initialized: ${type.name} (${type.sampleRate}Hz)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "TTS initialization failed: ${e.message}", e)
            false
        }
    }

    /**
     * Synthesize speech from text.
     *
     * @param text Input text to synthesize.
     * @param config Synthesis parameters (speed, pitch, volume).
     * @return TTSResult with PCM audio data, or null on failure.
     */
    fun synthesize(text: String, config: SynthesisConfig = SynthesisConfig()): TTSResult? {
        val handle = nativeHandle.get()
        if (handle == 0L || !isInitialized.get()) {
            Log.w(TAG, "TTS not initialized")
            return null
        }

        if (text.isBlank()) return null

        val startMs = System.currentTimeMillis()
        return try {
            val audioData = nativeSynthesize(
                handle, text,
                config.speed, config.pitch, config.volume,
                config.speakerId
            )

            if (audioData == null || audioData.isEmpty()) {
                Log.w(TAG, "TTS synthesis produced no audio")
                return null
            }

            val elapsed = System.currentTimeMillis() - startMs
            val durationMs = (audioData.size.toLong() * 1000L) / modelType.sampleRate

            Log.d(TAG, "Synthesized ${audioData.size} samples (${durationMs}ms) in ${elapsed}ms " +
                    "for text: \"${text.take(50)}...\"")

            TTSResult(
                audioData = audioData,
                sampleRate = modelType.sampleRate,
                durationMs = durationMs,
                synthesisTimeMs = elapsed,
                text = text
            )
        } catch (e: Exception) {
            Log.e(TAG, "TTS synthesis failed: ${e.message}", e)
            null
        }
    }

    /**
     * Synthesize and immediately generate blendshape animation frames.
     *
     * Convenience method that chains TTS -> A2BS for avatar lip-sync.
     *
     * @param text Input text.
     * @param a2bsEngine A2BS engine for blendshape generation.
     * @param config Synthesis parameters.
     * @return Pair of (TTSResult, BlendShapeFrames), or null on failure.
     */
    fun synthesizeWithAnimation(
        text: String,
        a2bsEngine: A2BSEngine,
        config: SynthesisConfig = SynthesisConfig()
    ): Pair<TTSResult, List<BlendShapeFrame>>? {
        val ttsResult = synthesize(text, config) ?: return null

        if (!a2bsEngine.isReady) {
            Log.w(TAG, "A2BS not ready, returning audio-only result")
            return Pair(ttsResult, emptyList())
        }

        val frames = a2bsEngine.processAudio(ttsResult.audioData, ttsResult.sampleRate)
        return Pair(ttsResult, frames)
    }

    /** Get the output sample rate for the current model. */
    fun getSampleRate(): Int = modelType.sampleRate

    /** Release all native resources. */
    fun destroy() {
        val handle = nativeHandle.getAndSet(0)
        if (handle != 0L && nativeAvailable.get()) {
            try {
                nativeDestroyTTS(handle)
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying TTS: ${e.message}", e)
            }
        }
        isInitialized.set(false)
        Log.d(TAG, "TTS engine destroyed")
    }

    companion object {
        private const val TAG = "TTSEngine"

        private val nativeAvailable = AtomicBoolean(false)
        private val nativeLoadAttempted = AtomicBoolean(false)

        init {
            tryLoadNativeLibraries()
        }

        @JvmStatic
        fun isNativeAvailable(): Boolean = nativeAvailable.get()

        private fun tryLoadNativeLibraries() {
            if (nativeLoadAttempted.getAndSet(true)) return

            try {
                System.loadLibrary("MNN")
            } catch (_: UnsatisfiedLinkError) { }

            try {
                System.loadLibrary("mnn_tts")
                nativeAvailable.set(true)
                Log.d(TAG, "Loaded libmnn_tts.so — TTS available")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "libmnn_tts.so not found — TTS unavailable. ${e.message}")
            }
        }

        // ---- JNI native methods ----
        @JvmStatic private external fun nativeCreateTTS(): Long
        @JvmStatic private external fun nativeLoadModel(handle: Long, modelDir: String, language: String): Boolean
        @JvmStatic private external fun nativeSynthesize(
            handle: Long, text: String,
            speed: Float, pitch: Float, volume: Float,
            speakerId: Int
        ): ShortArray?
        @JvmStatic private external fun nativeDestroyTTS(handle: Long)
    }
}
