package com.tronprotocol.app.avatar

import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log

/**
 * Body pose estimation via MNN using MoveNet or BlazePose models.
 *
 * Detects body keypoints (17 or 33) from camera frames for driving avatar
 * body animations — shoulder rotations, arm positions, torso lean, etc.
 *
 * Models: MoveNet-Lightning (192x192), MoveNet-Thunder (256x256), BlazePose (256x256)
 *
 * Pipeline: Bitmap -> Preprocess -> MNN Inference -> Pose Keypoints
 */
class PoseEstimationEngine {

    private var visionEngine: MnnVisionEngine? = null
    private var modelType: ModelType = ModelType.MOVENET_LIGHTNING

    val isReady: Boolean get() = visionEngine?.isReady == true

    /** Supported pose estimation model types. */
    enum class ModelType(
        val inputSize: Int,
        val keypointCount: Int,
        val fileName: String
    ) {
        MOVENET_LIGHTNING(192, 17, "movenet_lightning.mnn"),
        MOVENET_THUNDER(256, 17, "movenet_thunder.mnn"),
        BLAZEPOSE_LITE(256, 33, "blazepose_lite.mnn"),
        BLAZEPOSE_FULL(256, 33, "blazepose_full.mnn")
    }

    /** Named body keypoint with position and confidence. */
    data class BodyKeypoint(
        val name: String,
        val index: Int,
        val position: PointF,
        val confidence: Float,
        val z: Float = 0f
    )

    /** Complete pose estimation result. */
    data class PoseResult(
        val keypoints: List<BodyKeypoint>,
        val imageWidth: Int,
        val imageHeight: Int,
        val inferenceTimeMs: Long
    ) {
        val keypointCount: Int get() = keypoints.size
        val isValid: Boolean get() = keypoints.count { it.confidence > 0.3f } >= MIN_VALID_KEYPOINTS

        /** Get keypoint by name. */
        fun getKeypoint(name: String): BodyKeypoint? = keypoints.find { it.name == name }

        /** Average confidence across all keypoints. */
        fun averageConfidence(): Float =
            if (keypoints.isEmpty()) 0f else keypoints.map { it.confidence }.average().toFloat()

        /** Calculate shoulder rotation in degrees. */
        fun shoulderAngle(): Float {
            val leftShoulder = getKeypoint("left_shoulder") ?: return 0f
            val rightShoulder = getKeypoint("right_shoulder") ?: return 0f
            if (leftShoulder.confidence < 0.3f || rightShoulder.confidence < 0.3f) return 0f

            return Math.toDegrees(
                kotlin.math.atan2(
                    (rightShoulder.position.y - leftShoulder.position.y).toDouble(),
                    (rightShoulder.position.x - leftShoulder.position.x).toDouble()
                )
            ).toFloat()
        }

        /** Calculate torso lean angle. */
        fun torsoLean(): Float {
            val leftShoulder = getKeypoint("left_shoulder") ?: return 0f
            val rightShoulder = getKeypoint("right_shoulder") ?: return 0f
            val leftHip = getKeypoint("left_hip") ?: return 0f
            val rightHip = getKeypoint("right_hip") ?: return 0f

            val shoulderCenter = PointF(
                (leftShoulder.position.x + rightShoulder.position.x) / 2f,
                (leftShoulder.position.y + rightShoulder.position.y) / 2f
            )
            val hipCenter = PointF(
                (leftHip.position.x + rightHip.position.x) / 2f,
                (leftHip.position.y + rightHip.position.y) / 2f
            )

            return Math.toDegrees(
                kotlin.math.atan2(
                    (shoulderCenter.x - hipCenter.x).toDouble(),
                    (hipCenter.y - shoulderCenter.y).toDouble()
                )
            ).toFloat().coerceIn(-45f, 45f)
        }

        /** Calculate elbow flexion angles [left, right] in degrees. */
        fun elbowAngles(): Pair<Float, Float> {
            val leftAngle = jointAngle("left_shoulder", "left_elbow", "left_wrist")
            val rightAngle = jointAngle("right_shoulder", "right_elbow", "right_wrist")
            return Pair(leftAngle, rightAngle)
        }

        private fun jointAngle(aName: String, bName: String, cName: String): Float {
            val a = getKeypoint(aName) ?: return 180f
            val b = getKeypoint(bName) ?: return 180f
            val c = getKeypoint(cName) ?: return 180f

            if (a.confidence < 0.2f || b.confidence < 0.2f || c.confidence < 0.2f) return 180f

            val ba = PointF(a.position.x - b.position.x, a.position.y - b.position.y)
            val bc = PointF(c.position.x - b.position.x, c.position.y - b.position.y)

            val dot = ba.x * bc.x + ba.y * bc.y
            val magBA = kotlin.math.sqrt((ba.x * ba.x + ba.y * ba.y).toDouble())
            val magBC = kotlin.math.sqrt((bc.x * bc.x + bc.y * bc.y).toDouble())

            if (magBA == 0.0 || magBC == 0.0) return 180f

            val cosAngle = (dot / (magBA * magBC)).coerceIn(-1.0, 1.0)
            return Math.toDegrees(kotlin.math.acos(cosAngle)).toFloat()
        }
    }

    /**
     * Initialize the pose estimation engine.
     *
     * @param modelDir Directory containing the pose .mnn model.
     * @param type Model type to use.
     * @param backend Inference backend.
     * @return true if initialized successfully.
     */
    fun initialize(
        modelDir: String,
        type: ModelType = ModelType.MOVENET_LIGHTNING,
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
            Log.e(TAG, "Failed to load pose model: $modelPath")
            return false
        }

        visionEngine = engine
        Log.d(TAG, "Pose estimation initialized: ${type.name} (${type.keypointCount} keypoints)")
        return true
    }

    /**
     * Estimate body pose from a bitmap.
     *
     * @param bitmap Input image from camera.
     * @return PoseResult with detected body keypoints.
     */
    fun estimate(bitmap: Bitmap): PoseResult {
        val engine = visionEngine
        if (engine == null || !engine.isReady) {
            return PoseResult(emptyList(), bitmap.width, bitmap.height, 0)
        }

        val config = MnnVisionEngine.PreprocessConfig(
            meanValues = floatArrayOf(127.5f, 127.5f, 127.5f),
            normValues = floatArrayOf(1f / 127.5f, 1f / 127.5f, 1f / 127.5f),
            inputFormat = MnnVisionEngine.PreprocessConfig.InputFormat.RGB
        )

        val output = engine.infer(bitmap, config)
            ?: return PoseResult(emptyList(), bitmap.width, bitmap.height, 0)

        val keypoints = postprocessKeypoints(
            output.data, output.shape,
            bitmap.width, bitmap.height
        )

        return PoseResult(
            keypoints = keypoints,
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
            inferenceTimeMs = output.inferenceTimeMs
        )
    }

    /** Release resources. */
    fun release() {
        visionEngine?.release()
        visionEngine = null
        Log.d(TAG, "Pose estimation engine released")
    }

    // ---- Postprocessing ----

    private fun postprocessKeypoints(
        rawOutput: FloatArray,
        shape: IntArray,
        imageWidth: Int,
        imageHeight: Int
    ): List<BodyKeypoint> {
        val names = if (modelType.keypointCount == 33) BLAZEPOSE_NAMES else MOVENET_NAMES
        val numKeypoints = modelType.keypointCount
        val keypoints = mutableListOf<BodyKeypoint>()

        // MoveNet format: [1, 1, 17, 3] → y, x, confidence per keypoint
        // BlazePose format: [1, 33, 5] → x, y, z, visibility, presence
        val isBlazepose = modelType.keypointCount == 33
        val stride = if (isBlazepose) 5 else 3

        for (i in 0 until numKeypoints) {
            val offset = i * stride
            if (offset + stride - 1 >= rawOutput.size) break

            val x: Float
            val y: Float
            val z: Float
            val conf: Float

            if (isBlazepose) {
                x = rawOutput[offset] * imageWidth
                y = rawOutput[offset + 1] * imageHeight
                z = rawOutput[offset + 2]
                conf = sigmoid(rawOutput[offset + 3])
            } else {
                // MoveNet: y, x, score
                y = rawOutput[offset] * imageHeight
                x = rawOutput[offset + 1] * imageWidth
                z = 0f
                conf = rawOutput[offset + 2]
            }

            keypoints.add(
                BodyKeypoint(
                    name = names.getOrElse(i) { "keypoint_$i" },
                    index = i,
                    position = PointF(x, y),
                    confidence = conf,
                    z = z
                )
            )
        }

        return keypoints
    }

    private fun sigmoid(x: Float): Float = 1f / (1f + Math.exp(-x.toDouble()).toFloat())

    companion object {
        private const val TAG = "PoseEstimationEngine"
        private const val MIN_VALID_KEYPOINTS = 5

        /** MoveNet 17 keypoint names (COCO format). */
        val MOVENET_NAMES = listOf(
            "nose", "left_eye", "right_eye", "left_ear", "right_ear",
            "left_shoulder", "right_shoulder", "left_elbow", "right_elbow",
            "left_wrist", "right_wrist", "left_hip", "right_hip",
            "left_knee", "right_knee", "left_ankle", "right_ankle"
        )

        /** BlazePose 33 keypoint names. */
        val BLAZEPOSE_NAMES = listOf(
            "nose", "left_eye_inner", "left_eye", "left_eye_outer",
            "right_eye_inner", "right_eye", "right_eye_outer",
            "left_ear", "right_ear", "mouth_left", "mouth_right",
            "left_shoulder", "right_shoulder", "left_elbow", "right_elbow",
            "left_wrist", "right_wrist", "left_pinky", "right_pinky",
            "left_index", "right_index", "left_thumb", "right_thumb",
            "left_hip", "right_hip", "left_knee", "right_knee",
            "left_ankle", "right_ankle", "left_heel", "right_heel",
            "left_foot_index", "right_foot_index"
        )
    }
}
