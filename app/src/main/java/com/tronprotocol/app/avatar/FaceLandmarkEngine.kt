package com.tronprotocol.app.avatar

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log

/**
 * Face landmark detection via MNN using FaceMesh-style models.
 *
 * Detects 468 3D face landmarks from a cropped face region, enabling:
 * - Facial expression tracking for avatar animation
 * - Head pose estimation (yaw, pitch, roll)
 * - Iris tracking for eye gaze direction
 * - Lip-sync from visual speech
 *
 * Models: MediaPipe FaceMesh (192x192), PFLD (112x112)
 *
 * Pipeline: Cropped Face Bitmap -> Preprocess -> MNN Inference -> 468 Landmarks
 */
class FaceLandmarkEngine {

    private var visionEngine: MnnVisionEngine? = null
    private var modelType: ModelType = ModelType.FACEMESH_468

    val isReady: Boolean get() = visionEngine?.isReady == true

    /** Supported landmark model types. */
    enum class ModelType(
        val inputSize: Int,
        val landmarkCount: Int,
        val fileName: String,
        val has3D: Boolean
    ) {
        FACEMESH_468(192, 468, "face_landmark_468.mnn", true),
        PFLD_98(112, 98, "pfld_98.mnn", false),
        PFLD_68(112, 68, "pfld_68.mnn", false)
    }

    /** A 3D face landmark point. */
    data class Landmark(
        val index: Int,
        val x: Float,
        val y: Float,
        val z: Float = 0f
    )

    /** Head pose derived from landmarks (Euler angles in degrees). */
    data class HeadPose(
        val yaw: Float,
        val pitch: Float,
        val roll: Float
    )

    /** Complete face landmark result. */
    data class LandmarkResult(
        val landmarks: List<Landmark>,
        val headPose: HeadPose,
        val faceConfidence: Float,
        val inferenceTimeMs: Long
    ) {
        val landmarkCount: Int get() = landmarks.size

        /** Get landmark by semantic region name. */
        fun getLandmarksByRegion(region: FaceRegion): List<Landmark> =
            region.indices.mapNotNull { idx -> landmarks.getOrNull(idx) }

        /** Calculate the face bounding box from landmarks. */
        fun boundingBox(): RectF {
            if (landmarks.isEmpty()) return RectF()
            val xs = landmarks.map { it.x }
            val ys = landmarks.map { it.y }
            return RectF(
                xs.min(), ys.min(),
                xs.max(), ys.max()
            )
        }

        /** Get mouth openness ratio (0.0 = closed, 1.0 = wide open). */
        fun mouthOpenness(): Float {
            if (landmarks.size < 468) return 0f
            val upperLip = landmarks[13]
            val lowerLip = landmarks[14]
            val leftCorner = landmarks[61]
            val rightCorner = landmarks[291]

            val mouthHeight = kotlin.math.abs(lowerLip.y - upperLip.y)
            val mouthWidth = kotlin.math.abs(rightCorner.x - leftCorner.x)

            return if (mouthWidth > 0) (mouthHeight / mouthWidth).coerceIn(0f, 1f) else 0f
        }

        /** Get eye openness ratios [left, right] (0.0 = closed, 1.0 = wide open). */
        fun eyeOpenness(): Pair<Float, Float> {
            if (landmarks.size < 468) return Pair(0.5f, 0.5f)

            // Left eye: top=159, bottom=145, inner=133, outer=33
            val leftOpen = verticalRatio(landmarks[159], landmarks[145], landmarks[133], landmarks[33])
            // Right eye: top=386, bottom=374, inner=362, outer=263
            val rightOpen = verticalRatio(landmarks[386], landmarks[374], landmarks[362], landmarks[263])

            return Pair(leftOpen, rightOpen)
        }

        /** Get eyebrow raise values [left, right] (0.0 = neutral, 1.0 = raised). */
        fun eyebrowRaise(): Pair<Float, Float> {
            if (landmarks.size < 468) return Pair(0f, 0f)

            // Left: brow center=107 vs eye top=159
            val leftDist = kotlin.math.abs(landmarks[107].y - landmarks[159].y)
            val leftRef = kotlin.math.abs(landmarks[33].x - landmarks[133].x)
            val leftRaise = if (leftRef > 0) (leftDist / leftRef - 0.3f).coerceIn(0f, 1f) else 0f

            // Right: brow center=336 vs eye top=386
            val rightDist = kotlin.math.abs(landmarks[336].y - landmarks[386].y)
            val rightRef = kotlin.math.abs(landmarks[263].x - landmarks[362].x)
            val rightRaise = if (rightRef > 0) (rightDist / rightRef - 0.3f).coerceIn(0f, 1f) else 0f

            return Pair(leftRaise, rightRaise)
        }

        private fun verticalRatio(top: Landmark, bottom: Landmark, inner: Landmark, outer: Landmark): Float {
            val vDist = kotlin.math.abs(bottom.y - top.y)
            val hDist = kotlin.math.abs(outer.x - inner.x)
            return if (hDist > 0) (vDist / hDist).coerceIn(0f, 1f) else 0.5f
        }
    }

    /** Semantic face regions with landmark index ranges. */
    enum class FaceRegion(val indices: List<Int>) {
        LEFT_EYE(listOf(33, 7, 163, 144, 145, 153, 154, 155, 133, 173, 157, 158, 159, 160, 161, 246)),
        RIGHT_EYE(listOf(263, 249, 390, 373, 374, 380, 381, 382, 362, 398, 384, 385, 386, 387, 388, 466)),
        LEFT_EYEBROW(listOf(70, 63, 105, 66, 107, 55, 65, 52, 53, 46)),
        RIGHT_EYEBROW(listOf(300, 293, 334, 296, 336, 285, 295, 282, 283, 276)),
        LIPS_OUTER(listOf(61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291, 409, 270, 269, 267, 0, 37, 39, 40, 185)),
        LIPS_INNER(listOf(78, 191, 80, 81, 82, 13, 312, 311, 310, 415, 308, 324, 318, 402, 317, 14, 87, 178, 88, 95)),
        NOSE(listOf(1, 2, 98, 327, 168, 5, 4, 195, 197)),
        JAW(listOf(10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400,
            377, 152, 148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109)),
        LEFT_IRIS(listOf(468, 469, 470, 471, 472)),
        RIGHT_IRIS(listOf(473, 474, 475, 476, 477))
    }

    /**
     * Initialize the face landmark engine.
     *
     * @param modelDir Directory containing the landmark .mnn model.
     * @param type Model type to use.
     * @param backend Inference backend.
     * @return true if initialized successfully.
     */
    fun initialize(
        modelDir: String,
        type: ModelType = ModelType.FACEMESH_468,
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
            Log.e(TAG, "Failed to load landmark model: $modelPath")
            return false
        }

        visionEngine = engine
        Log.d(TAG, "Face landmark engine initialized: ${type.name} (${type.landmarkCount} points)")
        return true
    }

    /**
     * Detect face landmarks from a cropped face bitmap.
     *
     * @param faceBitmap Cropped face image (from FaceDetectionEngine bounding box).
     * @return LandmarkResult with all detected landmarks and derived metrics.
     */
    fun detectLandmarks(faceBitmap: Bitmap): LandmarkResult? {
        val engine = visionEngine ?: return null

        val config = MnnVisionEngine.PreprocessConfig(
            meanValues = floatArrayOf(127.5f, 127.5f, 127.5f),
            normValues = floatArrayOf(1f / 127.5f, 1f / 127.5f, 1f / 127.5f),
            inputFormat = MnnVisionEngine.PreprocessConfig.InputFormat.RGB
        )

        val output = engine.infer(faceBitmap, config) ?: return null

        return postprocessLandmarks(
            output.data, output.shape,
            faceBitmap.width, faceBitmap.height,
            output.inferenceTimeMs
        )
    }

    /**
     * Detect landmarks from a full image given a face bounding box.
     *
     * @param fullImage Complete camera frame.
     * @param faceBox Bounding box from FaceDetectionEngine.
     * @return LandmarkResult with landmarks in full-image coordinate space.
     */
    fun detectLandmarks(fullImage: Bitmap, faceBox: RectF): LandmarkResult? {
        // Crop face region with padding
        val pad = 0.1f
        val cropLeft = ((faceBox.left - faceBox.width() * pad).toInt()).coerceIn(0, fullImage.width - 1)
        val cropTop = ((faceBox.top - faceBox.height() * pad).toInt()).coerceIn(0, fullImage.height - 1)
        val cropWidth = ((faceBox.width() * (1 + 2 * pad)).toInt()).coerceIn(1, fullImage.width - cropLeft)
        val cropHeight = ((faceBox.height() * (1 + 2 * pad)).toInt()).coerceIn(1, fullImage.height - cropTop)

        val cropped = Bitmap.createBitmap(fullImage, cropLeft, cropTop, cropWidth, cropHeight)
        val result = detectLandmarks(cropped) ?: return null

        // Transform landmarks back to full image coordinates
        val transformedLandmarks = result.landmarks.map { lm ->
            Landmark(
                index = lm.index,
                x = lm.x * cropWidth / modelType.inputSize + cropLeft,
                y = lm.y * cropHeight / modelType.inputSize + cropTop,
                z = lm.z
            )
        }

        return result.copy(landmarks = transformedLandmarks)
    }

    /** Release resources. */
    fun release() {
        visionEngine?.release()
        visionEngine = null
        Log.d(TAG, "Face landmark engine released")
    }

    // ---- Postprocessing ----

    private fun postprocessLandmarks(
        rawOutput: FloatArray,
        shape: IntArray,
        imageWidth: Int,
        imageHeight: Int,
        inferenceTimeMs: Long
    ): LandmarkResult {
        val numLandmarks = modelType.landmarkCount
        val dimensions = if (modelType.has3D) 3 else 2
        val landmarks = mutableListOf<Landmark>()

        for (i in 0 until numLandmarks) {
            val offset = i * dimensions
            if (offset + dimensions - 1 >= rawOutput.size) break

            landmarks.add(
                Landmark(
                    index = i,
                    x = rawOutput[offset] * imageWidth,
                    y = rawOutput[offset + 1] * imageHeight,
                    z = if (dimensions >= 3) rawOutput[offset + 2] else 0f
                )
            )
        }

        // Estimate confidence from the output (last element after landmarks if present)
        val confOffset = numLandmarks * dimensions
        val confidence = if (confOffset < rawOutput.size) {
            sigmoid(rawOutput[confOffset])
        } else {
            0.9f // default high confidence if not provided
        }

        val headPose = estimateHeadPose(landmarks)

        return LandmarkResult(
            landmarks = landmarks,
            headPose = headPose,
            faceConfidence = confidence,
            inferenceTimeMs = inferenceTimeMs
        )
    }

    /**
     * Estimate head pose from face landmarks using a simplified model.
     * Uses key points: nose tip, chin, eye corners, mouth corners.
     */
    private fun estimateHeadPose(landmarks: List<Landmark>): HeadPose {
        if (landmarks.size < 68) return HeadPose(0f, 0f, 0f)

        // Use nose tip as center reference
        val noseTip = landmarks.getOrNull(1) ?: return HeadPose(0f, 0f, 0f)

        // Approximate yaw from nose position relative to face center
        val leftEye = landmarks.getOrNull(33)
        val rightEye = landmarks.getOrNull(263)

        if (leftEye == null || rightEye == null) return HeadPose(0f, 0f, 0f)

        val faceCenterX = (leftEye.x + rightEye.x) / 2f
        val faceWidth = kotlin.math.abs(rightEye.x - leftEye.x)

        val yaw = if (faceWidth > 0) {
            ((noseTip.x - faceCenterX) / faceWidth * 90f).coerceIn(-90f, 90f)
        } else 0f

        // Approximate pitch from nose-to-eye vertical ratio
        val eyeCenterY = (leftEye.y + rightEye.y) / 2f
        val chin = landmarks.getOrNull(152)
        val faceHeight = if (chin != null) kotlin.math.abs(chin.y - eyeCenterY) else faceWidth

        val pitch = if (faceHeight > 0) {
            ((noseTip.y - eyeCenterY) / faceHeight * 90f - 20f).coerceIn(-90f, 90f)
        } else 0f

        // Approximate roll from eye angle
        val eyeAngle = kotlin.math.atan2(
            (rightEye.y - leftEye.y).toDouble(),
            (rightEye.x - leftEye.x).toDouble()
        )
        val roll = Math.toDegrees(eyeAngle).toFloat().coerceIn(-90f, 90f)

        return HeadPose(yaw, pitch, roll)
    }

    private fun sigmoid(x: Float): Float = 1f / (1f + Math.exp(-x.toDouble()).toFloat())

    companion object {
        private const val TAG = "FaceLandmarkEngine"
    }
}
