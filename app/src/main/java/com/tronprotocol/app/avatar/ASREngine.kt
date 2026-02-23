package com.tronprotocol.app.avatar

import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Automatic Speech Recognition (ASR) engine via MNN using Sherpa-MNN.
 *
 * Provides streaming on-device speech-to-text for the TaoAvatar conversational pipeline.
 * Supports bilingual Chinese/English recognition with low latency suitable for
 * real-time avatar interaction.
 *
 * Pipeline: Microphone PCM -> ASR Model -> Partial/Final Transcripts
 *
 * Native libraries required:
 * - libMNN.so (core framework)
 * - libmnn_asr.so (Sherpa-MNN speech recognition module)
 *
 * Model: sherpa-mnn-streaming-zipformer-bilingual-zh-en
 */
class ASREngine {

    private val nativeHandle = AtomicLong(0)
    private val isInitialized = AtomicBoolean(false)
    private var accumulatedTranscript = StringBuilder()

    val isReady: Boolean get() = isInitialized.get() && nativeHandle.get() != 0L

    /** ASR recognition result. */
    data class RecognitionResult(
        val text: String,
        val isFinal: Boolean,
        val confidence: Float,
        val language: String,
        val latencyMs: Long
    )

    /** Callback for streaming recognition results. */
    interface RecognitionListener {
        fun onPartialResult(result: RecognitionResult)
        fun onFinalResult(result: RecognitionResult)
        fun onError(error: String)
    }

    /**
     * Initialize the ASR engine.
     *
     * @param modelDir Directory containing Sherpa-MNN model files.
     * @param sampleRate Expected audio sample rate (typically 16000).
     * @return true if initialized successfully.
     */
    fun initialize(modelDir: String, sampleRate: Int = DEFAULT_SAMPLE_RATE): Boolean {
        if (isInitialized.get()) {
            Log.w(TAG, "ASR engine already initialized")
            return true
        }

        if (!nativeAvailable.get()) {
            Log.e(TAG, "ASR native libraries not available")
            return false
        }

        val dir = File(modelDir)
        if (!dir.exists() || !dir.isDirectory) {
            Log.e(TAG, "ASR model directory not found: $modelDir")
            return false
        }

        return try {
            val handle = nativeCreateASR()
            if (handle == 0L) {
                Log.e(TAG, "nativeCreateASR() returned null handle")
                return false
            }
            nativeHandle.set(handle)

            val loadResult = nativeLoadModel(handle, modelDir, sampleRate)
            if (!loadResult) {
                Log.e(TAG, "Failed to load ASR model from $modelDir")
                nativeDestroyASR(handle)
                nativeHandle.set(0)
                return false
            }

            isInitialized.set(true)
            Log.d(TAG, "ASR engine initialized: Sherpa-MNN (${sampleRate}Hz)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "ASR initialization failed: ${e.message}", e)
            false
        }
    }

    /**
     * Process an audio chunk for streaming recognition.
     *
     * Call this repeatedly with audio chunks from the microphone.
     * Results are delivered through the listener callback.
     *
     * @param audioData PCM audio samples (16-bit signed, mono).
     * @param sampleRate Audio sample rate.
     * @param listener Callback for recognition results.
     */
    fun processAudioChunk(
        audioData: ShortArray,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        listener: RecognitionListener
    ) {
        val handle = nativeHandle.get()
        if (handle == 0L || !isInitialized.get()) {
            listener.onError("ASR not initialized")
            return
        }

        val startMs = System.currentTimeMillis()

        try {
            val result = nativeProcessChunk(handle, audioData, audioData.size, sampleRate)
            if (result == null) return

            val elapsed = System.currentTimeMillis() - startMs

            // Parse native result: "P:partial text" or "F:final text" or "C:confidence"
            val isFinal = result.startsWith("F:")
            val text = result.removePrefix("P:").removePrefix("F:")
            val confidence = nativeGetConfidence(handle)
            val language = nativeGetDetectedLanguage(handle) ?: "unknown"

            val recognition = RecognitionResult(
                text = text,
                isFinal = isFinal,
                confidence = confidence,
                language = language,
                latencyMs = elapsed
            )

            if (isFinal) {
                accumulatedTranscript.append(text).append(" ")
                listener.onFinalResult(recognition)
            } else if (text.isNotBlank()) {
                listener.onPartialResult(recognition)
            }
        } catch (e: Exception) {
            Log.e(TAG, "ASR processing failed: ${e.message}", e)
            listener.onError("Processing failed: ${e.message}")
        }
    }

    /**
     * Perform offline (non-streaming) recognition on a complete audio buffer.
     *
     * @param audioData Complete PCM audio samples.
     * @param sampleRate Audio sample rate.
     * @return RecognitionResult with final transcript.
     */
    fun recognizeOffline(audioData: ShortArray, sampleRate: Int = DEFAULT_SAMPLE_RATE): RecognitionResult? {
        val handle = nativeHandle.get()
        if (handle == 0L || !isInitialized.get()) return null

        val startMs = System.currentTimeMillis()

        return try {
            val text = nativeRecognizeOffline(handle, audioData, audioData.size, sampleRate) ?: return null
            val elapsed = System.currentTimeMillis() - startMs
            val confidence = nativeGetConfidence(handle)

            RecognitionResult(
                text = text,
                isFinal = true,
                confidence = confidence,
                language = nativeGetDetectedLanguage(handle) ?: "unknown",
                latencyMs = elapsed
            )
        } catch (e: Exception) {
            Log.e(TAG, "Offline recognition failed: ${e.message}", e)
            null
        }
    }

    /** Get the full accumulated transcript from the current session. */
    fun getAccumulatedTranscript(): String = accumulatedTranscript.toString().trim()

    /** Reset the recognition state for a new utterance. */
    fun reset() {
        val handle = nativeHandle.get()
        if (handle != 0L && isInitialized.get()) {
            nativeReset(handle)
        }
        accumulatedTranscript.clear()
    }

    /** Release all native resources. */
    fun destroy() {
        val handle = nativeHandle.getAndSet(0)
        if (handle != 0L && nativeAvailable.get()) {
            try {
                nativeDestroyASR(handle)
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying ASR: ${e.message}", e)
            }
        }
        isInitialized.set(false)
        accumulatedTranscript.clear()
        Log.d(TAG, "ASR engine destroyed")
    }

    companion object {
        private const val TAG = "ASREngine"
        const val DEFAULT_SAMPLE_RATE = 16000

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
                System.loadLibrary("mnn_asr")
                nativeAvailable.set(true)
                Log.d(TAG, "Loaded libmnn_asr.so — ASR available")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "libmnn_asr.so not found — ASR unavailable. ${e.message}")
            }
        }

        // ---- JNI native methods ----
        @JvmStatic private external fun nativeCreateASR(): Long
        @JvmStatic private external fun nativeLoadModel(handle: Long, modelDir: String, sampleRate: Int): Boolean
        @JvmStatic private external fun nativeProcessChunk(
            handle: Long, audioData: ShortArray, length: Int, sampleRate: Int
        ): String?
        @JvmStatic private external fun nativeRecognizeOffline(
            handle: Long, audioData: ShortArray, length: Int, sampleRate: Int
        ): String?
        @JvmStatic private external fun nativeGetConfidence(handle: Long): Float
        @JvmStatic private external fun nativeGetDetectedLanguage(handle: Long): String?
        @JvmStatic private external fun nativeReset(handle: Long)
        @JvmStatic private external fun nativeDestroyASR(handle: Long)
    }
}
