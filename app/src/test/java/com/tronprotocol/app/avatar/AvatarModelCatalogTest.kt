package com.tronprotocol.app.avatar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AvatarModelCatalogTest {

    @Test
    fun testCatalogHasComponents() {
        assertTrue("Catalog should have components", AvatarModelCatalog.components.isNotEmpty())
    }

    @Test
    fun testAllComponentTypes() {
        // Verify we have at least one component per type
        for (type in AvatarModelCatalog.AvatarComponent.entries) {
            val components = AvatarModelCatalog.getComponentsByType(type)
            assertTrue(
                "Should have at least one component of type ${type.name}",
                components.isNotEmpty()
            )
        }
    }

    @Test
    fun testGetComponentById() {
        val nnr = AvatarModelCatalog.getComponent("taoavatar-nnr-mnn")
        assertNotNull(nnr)
        assertEquals("TaoAvatar NNR", nnr!!.name)
        assertEquals(AvatarModelCatalog.AvatarComponent.NNR, nnr.component)

        val a2bs = AvatarModelCatalog.getComponent("unitalker-mnn")
        assertNotNull(a2bs)
        assertEquals(AvatarModelCatalog.AvatarComponent.A2BS, a2bs!!.component)
    }

    @Test
    fun testGetComponentNotFound() {
        assertNull(AvatarModelCatalog.getComponent("nonexistent"))
    }

    @Test
    fun testPresets() {
        assertTrue("Should have presets", AvatarModelCatalog.presets.isNotEmpty())

        val enPreset = AvatarModelCatalog.getPreset("taoavatar-full-en")
        assertNotNull(enPreset)
        assertEquals("TaoAvatar (English)", enPreset!!.name)
        assertTrue(enPreset.componentIds.containsKey(AvatarModelCatalog.AvatarComponent.NNR))
        assertTrue(enPreset.componentIds.containsKey(AvatarModelCatalog.AvatarComponent.A2BS))
        assertTrue(enPreset.componentIds.containsKey(AvatarModelCatalog.AvatarComponent.TTS))
        assertTrue(enPreset.componentIds.containsKey(AvatarModelCatalog.AvatarComponent.ASR))
        assertTrue(enPreset.componentIds.containsKey(AvatarModelCatalog.AvatarComponent.LLM))
    }

    @Test
    fun testRenderOnlyPreset() {
        val renderOnly = AvatarModelCatalog.getPreset("taoavatar-render-only")
        assertNotNull(renderOnly)
        assertEquals(2, renderOnly!!.componentIds.size)
        assertTrue(renderOnly.componentIds.containsKey(AvatarModelCatalog.AvatarComponent.NNR))
        assertTrue(renderOnly.componentIds.containsKey(AvatarModelCatalog.AvatarComponent.A2BS))
        assertFalse(renderOnly.componentIds.containsKey(AvatarModelCatalog.AvatarComponent.TTS))
    }

    @Test
    fun testMinRamRequirement() {
        assertEquals(8192L, AvatarModelCatalog.MIN_RAM_MB)
        assertEquals(12288L, AvatarModelCatalog.RECOMMENDED_RAM_MB)
    }

    @Test
    fun testRecommendPresetHighRam() {
        // Device with 16GB should get full preset
        val recommended = AvatarModelCatalog.recommendPreset(16384)
        assertNotNull(recommended)
        assertEquals("taoavatar-full-en", recommended!!.id)
    }

    @Test
    fun testRecommendPresetLowRam() {
        // Device with 8GB should get render-only
        val recommended = AvatarModelCatalog.recommendPreset(8192)
        assertNotNull(recommended)
        assertEquals("taoavatar-render-only", recommended!!.id)
    }

    @Test
    fun testRecommendPresetInsufficientRam() {
        // Device with 4GB should get null
        val recommended = AvatarModelCatalog.recommendPreset(4096)
        assertNull(recommended)
    }

    @Test
    fun testAllComponentsHaveValidIds() {
        AvatarModelCatalog.components.forEach { component ->
            assertTrue("ID should not be blank: ${component.name}", component.id.isNotBlank())
            assertTrue("Name should not be blank: ${component.id}", component.name.isNotBlank())
            assertTrue("Download URL should not be blank: ${component.id}", component.downloadUrl.isNotBlank())
            assertTrue("Size should be positive: ${component.id}", component.sizeEstimateMb > 0)
        }
    }

    @Test
    fun testPresetComponentIdsReferenceValidComponents() {
        AvatarModelCatalog.presets.forEach { preset ->
            preset.componentIds.forEach { (_, componentId) ->
                assertNotNull(
                    "Preset '${preset.id}' references unknown component: $componentId",
                    AvatarModelCatalog.getComponent(componentId)
                )
            }
        }
    }

    @Test
    fun testNnrComponentHasRequiredFiles() {
        val nnr = AvatarModelCatalog.getComponent("taoavatar-nnr-mnn")
        assertNotNull(nnr)
        assertTrue(nnr!!.requiredFiles.isNotEmpty())
        assertTrue(nnr.requiredFiles.contains("compute.nnr"))
        assertTrue(nnr.requiredFiles.contains("render_full.nnr"))
        assertTrue(nnr.requiredFiles.contains("background.nnr"))
        assertTrue(nnr.requiredFiles.contains("input_nnr.json"))
    }
}
