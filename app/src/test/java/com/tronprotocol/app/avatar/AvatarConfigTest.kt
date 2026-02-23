package com.tronprotocol.app.avatar

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarConfigTest {

    @Test
    fun testAvatarConfigCreation() {
        val config = AvatarConfig(
            avatarId = "test-avatar",
            displayName = "Test Avatar",
            rootPath = "/data/avatars",
            nnrModelDir = "/data/avatars/nnr",
            a2bsModelDir = "/data/avatars/a2bs",
            ttsModelDir = "/data/avatars/tts",
            asrModelDir = "/data/avatars/asr",
            llmModelDir = "/data/avatars/llm"
        )

        assertEquals("test-avatar", config.avatarId)
        assertEquals("Test Avatar", config.displayName)
        assertEquals("/data/avatars", config.rootPath)
        assertFalse(config.isCustom)
        assertEquals(512, config.renderWidth)
        assertEquals(512, config.renderHeight)
    }

    @Test
    fun testNnrPaths() {
        val config = AvatarConfig(
            avatarId = "test",
            displayName = "Test",
            rootPath = "/root",
            nnrModelDir = "/root/nnr",
            a2bsModelDir = "",
            ttsModelDir = "",
            asrModelDir = "",
            llmModelDir = ""
        )

        assertEquals("/root/nnr/compute.nnr", config.nnrComputePath)
        assertEquals("/root/nnr/render_full.nnr", config.nnrRenderPath)
        assertEquals("/root/nnr/background.nnr", config.nnrBackgroundPath)
        assertEquals("/root/nnr/input_nnr.json", config.nnrInputConfigPath)
    }

    @Test
    fun testJsonSerialization() {
        val original = AvatarConfig(
            avatarId = "json-test",
            displayName = "JSON Test Avatar",
            rootPath = "/data/test",
            nnrModelDir = "/data/test/nnr",
            a2bsModelDir = "/data/test/a2bs",
            ttsModelDir = "/data/test/tts",
            asrModelDir = "/data/test/asr",
            llmModelDir = "/data/test/llm",
            isCustom = true,
            skeletonPath = "/data/test/skeleton.json",
            renderWidth = 256,
            renderHeight = 256
        )

        val json = original.toJson()
        val restored = AvatarConfig.fromJson(json)

        assertEquals(original.avatarId, restored.avatarId)
        assertEquals(original.displayName, restored.displayName)
        assertEquals(original.rootPath, restored.rootPath)
        assertEquals(original.nnrModelDir, restored.nnrModelDir)
        assertEquals(original.a2bsModelDir, restored.a2bsModelDir)
        assertEquals(original.ttsModelDir, restored.ttsModelDir)
        assertEquals(original.asrModelDir, restored.asrModelDir)
        assertEquals(original.llmModelDir, restored.llmModelDir)
        assertEquals(original.isCustom, restored.isCustom)
        assertEquals(original.skeletonPath, restored.skeletonPath)
        assertEquals(original.renderWidth, restored.renderWidth)
        assertEquals(original.renderHeight, restored.renderHeight)
    }

    @Test
    fun testBlendShapeFrame() {
        val weights = FloatArray(68) { it * 0.01f }
        val frame = BlendShapeFrame(
            index = 0,
            timestamp = 12345L,
            weights = weights,
            isLast = false
        )

        assertEquals(0, frame.index)
        assertEquals(12345L, frame.timestamp)
        assertEquals(68, frame.weights.size)
        assertFalse(frame.isLast)
    }

    @Test
    fun testBlendShapeFrameEquality() {
        val weights1 = FloatArray(68) { 0.5f }
        val weights2 = FloatArray(68) { 0.5f }
        val weights3 = FloatArray(68) { 0.3f }

        val frame1 = BlendShapeFrame(0, 100L, weights1)
        val frame2 = BlendShapeFrame(0, 100L, weights2)
        val frame3 = BlendShapeFrame(0, 100L, weights3)

        assertEquals(frame1, frame2)
        assertEquals(frame1.hashCode(), frame2.hashCode())
        assertFalse(frame1 == frame3)
    }

    @Test
    fun testSkeletonData() {
        val bones = listOf(
            SkeletonData.Bone(0, "root", -1, FloatArray(16) { if (it % 5 == 0) 1f else 0f }),
            SkeletonData.Bone(1, "spine", 0, FloatArray(16) { if (it % 5 == 0) 1f else 0f })
        )
        val skeleton = SkeletonData("test_skeleton", bones, FloatArray(16))

        assertEquals("test_skeleton", skeleton.name)
        assertEquals(2, skeleton.bones.size)
        assertEquals("root", skeleton.bones[0].name)
        assertEquals(-1, skeleton.bones[0].parentId)
        assertEquals(0, skeleton.bones[1].parentId)
    }

    @Test
    fun testAvatarState() {
        assertEquals("UNINITIALIZED", AvatarState.UNINITIALIZED.name)
        assertEquals("READY", AvatarState.READY.name)
        assertEquals("RENDERING", AvatarState.RENDERING.name)
        assertEquals("ERROR", AvatarState.ERROR.name)
    }

    @Test
    fun testAvatarOperationResult() {
        val success = AvatarOperationResult.ok("Operation succeeded", mapOf("key" to "value"))
        assertTrue(success.success)
        assertEquals("Operation succeeded", success.message)
        assertEquals("value", success.data["key"])

        val failure = AvatarOperationResult.fail("Operation failed")
        assertFalse(failure.success)
        assertEquals("Operation failed", failure.message)
        assertTrue(failure.data.isEmpty())
    }
}
