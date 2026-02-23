package com.tronprotocol.app.avatar

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log

/**
 * Real-time face detection via MNN using BlazeFace or similar lightweight models.
 *
 * Detects face bounding boxes and basic keypoints (eyes, nose, mouth) from camera
 * frames or bitmap inputs. Designed for 30+ FPS on modern ARM64 devices.
 *
 * Models: BlazeFace (128x128 or 256x256), SCRFD, RetinaFace
 *
 * Pipeline: Bitmap -> Preprocess -> MNN Inference -> NMS -> FaceDetection results
 */
class FaceDetectionEngine {

    private var visionEngine: MnnVisionEngine? = null
    private var modelType: ModelType = ModelType.BLAZEFACE_SHORT
    private var confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD
    private var nmsThreshold: Float = DEFAULT_NMS_THRESHOLD

    val isReady: Boolean get() = visionEngine?.isReady == true

    /** Supported face detection model types. */
    enum class ModelType(val inputSize: Int, val fileName: String) {
        BLAZEFACE_SHORT(128, "blazeface_short.mnn"),
        BLAZEFACE_FULL(256, "blazeface_full.mnn"),
        SCRFD_500M(160, "scrfd_500m.mnn"),
        SCRFD_2_5G(320, "scrfd_2.5g.mnn")
    }

    /** Detected face with bounding box, confidence, and keypoints. */
    data class FaceDetection(
        val boundingBox: RectF,
        val confidence: Float,
        val keypoints: List<FaceKeypoint>,
        val inferenceTimeMs: Long
    )

    /** A named 2D keypoint on the face. */
    data class FaceKeypoint(
        val name: String,
        val x: Float,
        val y: Float
    )

    /** Aggregated detection result for a frame. */
    data class DetectionResult(
        val faces: List<FaceDetection>,
        val imageWidth: Int,
        val imageHeight: Int,
        val inferenceTimeMs: Long
    ) {
        val faceCount: Int get() = faces.size
        val hasFaces: Boolean get() = faces.isNotEmpty()

        /** Get the primary (largest / most confident) face. */
        val primaryFace: FaceDetection?
            get() = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() * it.confidence }
    }

    /**
     * Initialize the face detection engine.
     *
     * @param modelDir Directory containing the face detection .mnn model.
     * @param type Model type to use.
     * @param backend Inference backend.
     * @return true if initialized successfully.
     */
    fun initialize(
        modelDir: String,
        type: ModelType = ModelType.BLAZEFACE_SHORT,
        backend: MnnVisionEngine.BackendType = MnnVisionEngine.BackendType.CPU,
        threads: Int = 2
    ): Boolean {
        if (!MnnVisionEngine.isNativeAvailable()) {
            Log.e(TAG, "MNN native libraries not available")
            return false
        }

        modelType = type
        val modelPath = "$modelDir/${type.fileName}"

        val engine = MnnVisionEngine()
        val loaded = engine.loadModel(
            mnnModelPath = modelPath,
            width = type.inputSize,
            height = type.inputSize,
            channels = 3,
            backend = backend,
            numThreads = threads
        )

        if (!loaded) {
            Log.e(TAG, "Failed to load face detection model: $modelPath")
            return false
        }

        visionEngine = engine
        Log.d(TAG, "Face detection initialized: ${type.name} (${type.inputSize}x${type.inputSize})")
        return true
    }

    /**
     * Detect faces in a bitmap.
     *
     * @param bitmap Input image from camera or file.
     * @return Detection result with all detected faces.
     */
    fun detect(bitmap: Bitmap): DetectionResult {
        val engine = visionEngine
        if (engine == null || !engine.isReady) {
            return DetectionResult(emptyList(), bitmap.width, bitmap.height, 0)
        }

        val config = MnnVisionEngine.PreprocessConfig(
            meanValues = floatArrayOf(127.5f, 127.5f, 127.5f),
            normValues = floatArrayOf(1f / 127.5f, 1f / 127.5f, 1f / 127.5f),
            inputFormat = MnnVisionEngine.PreprocessConfig.InputFormat.RGB
        )

        val output = engine.infer(bitmap, config)
            ?: return DetectionResult(emptyList(), bitmap.width, bitmap.height, 0)

        val faces = postprocessDetections(
            output.data, output.shape,
            bitmap.width, bitmap.height
        )

        return DetectionResult(
            faces = faces,
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
            inferenceTimeMs = output.inferenceTimeMs
        )
    }

    /** Update detection thresholds at runtime. */
    fun setThresholds(confidence: Float = confidenceThreshold, nms: Float = nmsThreshold) {
        confidenceThreshold = confidence.coerceIn(0.1f, 0.99f)
        nmsThreshold = nms.coerceIn(0.1f, 0.9f)
    }

    /** Release resources. */
    fun release() {
        visionEngine?.release()
        visionEngine = null
        Log.d(TAG, "Face detection engine released")
    }

    // ---- Postprocessing ----

    private fun postprocessDetections(
        rawOutput: FloatArray,
        shape: IntArray,
        imageWidth: Int,
        imageHeight: Int
    ): List<FaceDetection> {
        // BlazeFace output format: [num_detections, 17]
        // [x_center, y_center, width, height, confidence, kp0_x, kp0_y, ..., kp5_x, kp5_y]
        val numDetections = if (shape.size >= 2) shape[shape.size - 2] else rawOutput.size / DETECTION_STRIDE
        val stride = if (shape.size >= 2) shape[shape.size - 1] else DETECTION_STRIDE

        val detections = mutableListOf<FaceDetection>()

        for (i in 0 until numDetections) {
            val offset = i * stride
            if (offset + 4 >= rawOutput.size) break

            val confidence = sigmoid(rawOutput[offset + 4])
            if (confidence < confidenceThreshold) continue

            val cx = rawOutput[offset] * imageWidth
            val cy = rawOutput[offset + 1] * imageHeight
            val w = rawOutput[offset + 2] * imageWidth
            val h = rawOutput[offset + 3] * imageHeight

            val box = RectF(
                (cx - w / 2f).coerceIn(0f, imageWidth.toFloat()),
                (cy - h / 2f).coerceIn(0f, imageHeight.toFloat()),
                (cx + w / 2f).coerceIn(0f, imageWidth.toFloat()),
                (cy + h / 2f).coerceIn(0f, imageHeight.toFloat())
            )

            // Extract face keypoints (6 points for BlazeFace)
            val keypoints = mutableListOf<FaceKeypoint>()
            val keypointNames = listOf(
                "right_eye", "left_eye", "nose_tip",
                "mouth_center", "right_ear", "left_ear"
            )

            for (k in keypointNames.indices) {
                val kpOffset = offset + 5 + k * 2
                if (kpOffset + 1 < rawOutput.size) {
                    keypoints.add(
                        FaceKeypoint(
                            name = keypointNames[k],
                            x = rawOutput[kpOffset] * imageWidth,
                            y = rawOutput[kpOffset + 1] * imageHeight
                        )
                    )
                }
            }

            detections.add(FaceDetection(box, confidence, keypoints, 0))
        }

        return applyNms(detections)
    }

    private fun applyNms(detections: List<FaceDetection>): List<FaceDetection> {
        if (detections.size <= 1) return detections

        val sorted = detections.sortedByDescending { it.confidence }.toMutableList()
        val kept = mutableListOf<FaceDetection>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            kept.add(best)

            sorted.removeAll { other ->
                computeIoU(best.boundingBox, other.boundingBox) > nmsThreshold
            }
        }
        return kept
    }

    private fun computeIoU(a: RectF, b: RectF): Float {
        val intersectLeft = maxOf(a.left, b.left)
        val intersectTop = maxOf(a.top, b.top)
        val intersectRight = minOf(a.right, b.right)
        val intersectBottom = minOf(a.bottom, b.bottom)

        if (intersectRight <= intersectLeft || intersectBottom <= intersectTop) return 0f

        val intersectArea = (intersectRight - intersectLeft) * (intersectBottom - intersectTop)
        val aArea = a.width() * a.height()
        val bArea = b.width() * b.height()
        val unionArea = aArea + bArea - intersectArea

        return if (unionArea > 0) intersectArea / unionArea else 0f
    }

    private fun sigmoid(x: Float): Float = 1f / (1f + Math.exp(-x.toDouble()).toFloat())

    companion object {
        private const val TAG = "FaceDetectionEngine"
        private const val DETECTION_STRIDE = 17 // BlazeFace: 4 box + 1 conf + 6*2 keypoints
        private const val DEFAULT_CONFIDENCE_THRESHOLD = 0.5f
        private const val DEFAULT_NMS_THRESHOLD = 0.3f
    }
}
