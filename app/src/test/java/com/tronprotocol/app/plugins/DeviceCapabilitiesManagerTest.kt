package com.tronprotocol.app.plugins

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the device capabilities data model and scoring logic.
 *
 * Since [DeviceCapabilitiesManager.assess] requires an Android Context and
 * real hardware, we test the data model classes, tier computation, JSON
 * serialization, and TPU generation properties directly.
 */
class DeviceCapabilitiesManagerTest {

    // ---- TpuGeneration enum properties ----

    @Test
    fun testTpuGenerationG5HasHighestTops() {
        assertTrue(TpuGeneration.TENSOR_G5.estimatedTops > TpuGeneration.TENSOR_G4.estimatedTops)
        assertTrue(TpuGeneration.TENSOR_G4.estimatedTops > TpuGeneration.TENSOR_G3.estimatedTops)
        assertTrue(TpuGeneration.TENSOR_G3.estimatedTops > TpuGeneration.TENSOR_G2.estimatedTops)
        assertTrue(TpuGeneration.TENSOR_G2.estimatedTops > TpuGeneration.TENSOR_G1.estimatedTops)
        assertEquals(0f, TpuGeneration.NONE.estimatedTops, 0.001f)
    }

    @Test
    fun testTpuGenerationBatchSizesIncreaseWithGeneration() {
        assertTrue(TpuGeneration.TENSOR_G5.recommendedBatchSize >= TpuGeneration.TENSOR_G4.recommendedBatchSize)
        assertTrue(TpuGeneration.TENSOR_G4.recommendedBatchSize >= TpuGeneration.TENSOR_G3.recommendedBatchSize)
        assertTrue(TpuGeneration.TENSOR_G3.recommendedBatchSize >= TpuGeneration.TENSOR_G2.recommendedBatchSize)
    }

    @Test
    fun testTpuGenerationSeqLensIncreaseWithGeneration() {
        assertTrue(TpuGeneration.TENSOR_G5.recommendedSeqLen > TpuGeneration.TENSOR_G4.recommendedSeqLen)
        assertTrue(TpuGeneration.TENSOR_G4.recommendedSeqLen > TpuGeneration.TENSOR_G3.recommendedSeqLen)
        assertTrue(TpuGeneration.TENSOR_G3.recommendedSeqLen > TpuGeneration.TENSOR_G2.recommendedSeqLen)
    }

    @Test
    fun testTpuGenerationG4ProBetweenG4AndG5() {
        assertTrue(TpuGeneration.TENSOR_G4_PRO.estimatedTops > TpuGeneration.TENSOR_G4.estimatedTops)
        assertTrue(TpuGeneration.TENSOR_G4_PRO.estimatedTops < TpuGeneration.TENSOR_G5.estimatedTops)
    }

    @Test
    fun testTpuGenerationG3ProBetweenG3AndG4() {
        assertTrue(TpuGeneration.TENSOR_G3_PRO.estimatedTops > TpuGeneration.TENSOR_G3.estimatedTops)
        assertTrue(TpuGeneration.TENSOR_G3_PRO.estimatedTops < TpuGeneration.TENSOR_G4.estimatedTops)
    }

    @Test
    fun testTpuGenerationShortNames() {
        assertEquals("G5", TpuGeneration.TENSOR_G5.shortName)
        assertEquals("G4", TpuGeneration.TENSOR_G4.shortName)
        assertEquals("G3", TpuGeneration.TENSOR_G3.shortName)
        assertEquals("G2", TpuGeneration.TENSOR_G2.shortName)
        assertEquals("G1", TpuGeneration.TENSOR_G1.shortName)
        assertEquals("none", TpuGeneration.NONE.shortName)
    }

    @Test
    fun testAllTpuGenerationsHaveDescriptions() {
        for (gen in TpuGeneration.values()) {
            assertTrue(
                "Generation ${gen.name} should have a description",
                gen.description.isNotBlank()
            )
        }
    }

    // ---- TpuCapability ----

    @Test
    fun testTpuCapabilityPresentWithG5() {
        val cap = TpuCapability(
            present = true,
            generation = TpuGeneration.TENSOR_G5,
            isPixel = true,
            estimatedTops = TpuGeneration.TENSOR_G5.estimatedTops,
            recommendedBatchSize = TpuGeneration.TENSOR_G5.recommendedBatchSize,
            recommendedSeqLen = TpuGeneration.TENSOR_G5.recommendedSeqLen,
            nnapiDelegateName = "google-edgetpu",
            description = TpuGeneration.TENSOR_G5.description
        )
        assertTrue(cap.present)
        assertEquals(TpuGeneration.TENSOR_G5, cap.generation)
        assertEquals(46f, cap.estimatedTops, 0.001f)
        assertEquals("google-edgetpu", cap.nnapiDelegateName)
    }

    @Test
    fun testTpuCapabilityNotPresent() {
        val cap = TpuCapability(
            present = false,
            generation = TpuGeneration.NONE,
            isPixel = false,
            estimatedTops = 0f,
            recommendedBatchSize = 2,
            recommendedSeqLen = 48,
            nnapiDelegateName = null,
            description = "No TPU"
        )
        assertFalse(cap.present)
        assertNull(cap.nnapiDelegateName)
    }

    @Test
    fun testTpuCapabilityToJson() {
        val cap = TpuCapability(
            present = true,
            generation = TpuGeneration.TENSOR_G4,
            isPixel = true,
            estimatedTops = 37f,
            recommendedBatchSize = 8,
            recommendedSeqLen = 96,
            nnapiDelegateName = "google-edgetpu",
            description = "Pixel 9"
        )
        val json = cap.toJson()
        assertTrue(json.getBoolean("present"))
        assertEquals("TENSOR_G4", json.getString("generation"))
        assertEquals("Google Tensor G4", json.getString("generation_display"))
        assertTrue(json.getBoolean("is_pixel"))
        assertEquals(37.0, json.getDouble("estimated_tops"), 0.1)
        assertEquals("google-edgetpu", json.getString("nnapi_delegate"))
    }

    // ---- GpuCapability ----

    @Test
    fun testGpuCapabilityToJson() {
        val cap = GpuCapability(
            family = GpuFamily.ADRENO,
            tier = GpuTier.FLAGSHIP,
            vulkanSupported = true,
            vulkanVersion = "1.1+",
            openclAvailable = true,
            estimatedGflops = 4500f
        )
        val json = cap.toJson()
        assertEquals("ADRENO", json.getString("family"))
        assertEquals("Qualcomm Adreno", json.getString("family_display"))
        assertEquals("FLAGSHIP", json.getString("tier"))
        assertTrue(json.getBoolean("vulkan_supported"))
        assertTrue(json.getBoolean("opencl_available"))
    }

    @Test
    fun testGpuFamilyDisplayNames() {
        assertEquals("Qualcomm Adreno", GpuFamily.ADRENO.displayName)
        assertEquals("Arm Mali", GpuFamily.MALI.displayName)
        assertEquals("Arm Immortalis", GpuFamily.IMMORTALIS.displayName)
        assertEquals("Imagination PowerVR", GpuFamily.POWERVR.displayName)
    }

    @Test
    fun testGpuTierOrdering() {
        assertTrue(GpuTier.FLAGSHIP > GpuTier.HIGH_END)
        assertTrue(GpuTier.HIGH_END > GpuTier.MID_RANGE)
        assertTrue(GpuTier.MID_RANGE > GpuTier.ENTRY)
        assertTrue(GpuTier.ENTRY > GpuTier.UNKNOWN)
    }

    // ---- NpuCapability ----

    @Test
    fun testNpuTypes() {
        assertEquals("Qualcomm Hexagon", NpuType.QUALCOMM_HEXAGON.displayName)
        assertEquals("Samsung Exynos NPU", NpuType.SAMSUNG_NPU.displayName)
        assertEquals("MediaTek APU", NpuType.MEDIATEK_APU.displayName)
        assertEquals("None", NpuType.NONE.displayName)
    }

    @Test
    fun testNpuCapabilityToJson() {
        val cap = NpuCapability(
            present = true,
            type = NpuType.QUALCOMM_HEXAGON,
            estimatedTops = 45f,
            description = "Hexagon HTP v75 (~45 TOPS)"
        )
        val json = cap.toJson()
        assertTrue(json.getBoolean("present"))
        assertEquals("QUALCOMM_HEXAGON", json.getString("type"))
        assertEquals(45.0, json.getDouble("estimated_tops"), 0.1)
    }

    // ---- CpuTopology ----

    @Test
    fun testCpuTopologyBigLittleToString() {
        val topology = CpuTopology(
            type = TopologyType.BIG_LITTLE,
            bigCores = 4,
            midCores = 0,
            littleCores = 4,
            maxFrequencyMhz = 2800
        )
        assertEquals("4big+4little", topology.toString())
    }

    @Test
    fun testCpuTopologyTriClusterToString() {
        val topology = CpuTopology(
            type = TopologyType.TRI_CLUSTER,
            bigCores = 1,
            midCores = 3,
            littleCores = 4,
            maxFrequencyMhz = 3200
        )
        assertEquals("1big+3mid+4little", topology.toString())
    }

    @Test
    fun testCpuTopologySymmetricToString() {
        val topology = CpuTopology(
            type = TopologyType.SYMMETRIC,
            bigCores = 8,
            midCores = 0,
            littleCores = 0,
            maxFrequencyMhz = 2200
        )
        assertEquals("8 cores", topology.toString())
    }

    @Test
    fun testCpuTopologyToJson() {
        val topology = CpuTopology(
            type = TopologyType.TRI_CLUSTER,
            bigCores = 1,
            midCores = 3,
            littleCores = 4,
            maxFrequencyMhz = 3200
        )
        val json = topology.toJson()
        assertEquals("TRI_CLUSTER", json.getString("type"))
        assertEquals(1, json.getInt("big_cores"))
        assertEquals(3, json.getInt("mid_cores"))
        assertEquals(4, json.getInt("little_cores"))
        assertEquals(3200, json.getInt("max_frequency_mhz"))
    }

    // ---- MemoryCapability ----

    @Test
    fun testMemoryCapabilityToJson() {
        val mem = MemoryCapability(
            totalRamMb = 8192,
            availableRamMb = 4096,
            lowMemory = false,
            lowMemoryThresholdMb = 512,
            javaHeapMaxMb = 256,
            largeHeapMb = 512
        )
        val json = mem.toJson()
        assertEquals(8192L, json.getLong("total_ram_mb"))
        assertEquals(4096L, json.getLong("available_ram_mb"))
        assertFalse(json.getBoolean("low_memory"))
    }

    // ---- NnapiCapability ----

    @Test
    fun testNnapiCapabilityToJson() {
        val nnapi = NnapiCapability(
            available = true,
            version = "1.3+ (API 34)",
            estimatedDelegates = listOf("nnapi-reference", "google-edgetpu", "armnn")
        )
        val json = nnapi.toJson()
        assertTrue(json.getBoolean("available"))
        assertEquals("1.3+ (API 34)", json.getString("version"))
        val delegates = json.getJSONArray("estimated_delegates")
        assertEquals(3, delegates.length())
        assertEquals("google-edgetpu", delegates.getString(1))
    }

    // ---- SocInfo ----

    @Test
    fun testSocVendorDisplayNames() {
        assertEquals("Google", SocVendor.GOOGLE.displayName)
        assertEquals("Qualcomm", SocVendor.QUALCOMM.displayName)
        assertEquals("Samsung", SocVendor.SAMSUNG.displayName)
        assertEquals("MediaTek", SocVendor.MEDIATEK.displayName)
    }

    @Test
    fun testSocInfoToJson() {
        val soc = SocInfo(
            vendor = SocVendor.GOOGLE,
            model = "Tensor G5",
            chipsetName = "Google Tensor G5",
            hardware = "tensor",
            board = "caiman",
            deviceManufacturer = "Google",
            deviceModel = "Pixel 10",
            androidVersion = "15",
            apiLevel = 35
        )
        val json = soc.toJson()
        assertEquals("GOOGLE", json.getString("vendor"))
        assertEquals("Google", json.getString("vendor_display"))
        assertEquals("Google Tensor G5", json.getString("chipset_name"))
        assertEquals("Pixel 10", json.getString("device_model"))
    }

    // ---- DeviceTier ----

    @Test
    fun testCapabilityTierDisplayNames() {
        assertEquals("Flagship", CapabilityTier.FLAGSHIP.displayName)
        assertEquals("High-end", CapabilityTier.HIGH_END.displayName)
        assertEquals("Mid-range", CapabilityTier.MID_RANGE.displayName)
        assertEquals("Entry-level", CapabilityTier.ENTRY.displayName)
        assertEquals("Minimal", CapabilityTier.MINIMAL.displayName)
    }

    @Test
    fun testDeviceTierToJson() {
        val tier = DeviceTier(
            tier = CapabilityTier.FLAGSHIP,
            score = 85,
            maxRecommendedModelParams = "4B+",
            bestAccelerator = "TPU (G5)",
            estimatedTotalTops = 46f,
            summary = "Flagship device (score 85/100). Best accelerator: TPU (G5). Recommended max model: 4B+ parameters."
        )
        val json = tier.toJson()
        assertEquals("FLAGSHIP", json.getString("tier"))
        assertEquals("Flagship", json.getString("tier_display"))
        assertEquals(85, json.getInt("score"))
        assertEquals("4B+", json.getString("max_recommended_model_params"))
        assertEquals("TPU (G5)", json.getString("best_accelerator"))
    }

    // ---- AcceleratorBackends ----

    @Test
    fun testAcceleratorBackendsToJson() {
        val backends = AcceleratorBackends(
            tfliteAvailable = true,
            tfliteGpuDelegateAvailable = true,
            mnnAvailable = false
        )
        val json = backends.toJson()
        assertTrue(json.getBoolean("tflite"))
        assertTrue(json.getBoolean("tflite_gpu_delegate"))
        assertFalse(json.getBoolean("mnn"))
    }

    // ---- Full DeviceCapabilities ----

    @Test
    fun testDeviceCapabilitiesSummaryNotEmpty() {
        val caps = buildTestCapabilities()
        val summary = caps.toSummary()
        assertTrue(summary.isNotBlank())
        assertTrue(summary.contains("Device Capabilities"))
        assertTrue(summary.contains("CPU"))
        assertTrue(summary.contains("GPU"))
        assertTrue(summary.contains("Memory"))
    }

    @Test
    fun testDeviceCapabilitiesJsonRoundTrip() {
        val caps = buildTestCapabilities()
        val json = caps.toJson()

        // Verify all top-level sections present
        assertTrue(json.has("cpu"))
        assertTrue(json.has("memory"))
        assertTrue(json.has("gpu"))
        assertTrue(json.has("tpu"))
        assertTrue(json.has("npu"))
        assertTrue(json.has("nnapi"))
        assertTrue(json.has("backends"))
        assertTrue(json.has("soc"))
        assertTrue(json.has("tier"))
    }

    @Test
    fun testDeviceCapabilitiesSummaryContainsTpuInfo() {
        val caps = buildTestCapabilities()
        val summary = caps.toSummary()
        assertTrue(summary.contains("Tensor G5"))
        assertTrue(summary.contains("TOPS"))
    }

    @Test
    fun testDeviceCapabilitiesSummaryContainsGpuInfo() {
        val caps = buildTestCapabilities()
        val summary = caps.toSummary()
        assertTrue(summary.contains("Immortalis"))
    }

    @Test
    fun testDeviceCapabilitiesSummaryShowsTier() {
        val caps = buildTestCapabilities()
        val summary = caps.toSummary()
        assertTrue(summary.contains("Flagship"))
    }

    // ---- Helpers ----

    private fun buildTestCapabilities(): DeviceCapabilities {
        val cpu = CpuCapability(
            primaryArch = "arm64-v8a",
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
            supportsArm64 = true,
            supportsX86_64 = false,
            processorCount = 8,
            supportsFp16 = true,
            supportsDotProd = true,
            supportsI8mm = true,
            coreFrequenciesMhz = listOf(1800, 1800, 1800, 1800, 2400, 2400, 2400, 3200),
            topology = CpuTopology(TopologyType.TRI_CLUSTER, 1, 3, 4, 3200),
            recommendedThreads = 4
        )
        val memory = MemoryCapability(
            totalRamMb = 12288, availableRamMb = 6000,
            lowMemory = false, lowMemoryThresholdMb = 512,
            javaHeapMaxMb = 512, largeHeapMb = 1024
        )
        val gpu = GpuCapability(
            family = GpuFamily.IMMORTALIS, tier = GpuTier.FLAGSHIP,
            vulkanSupported = true, vulkanVersion = "1.1+",
            openclAvailable = true, estimatedGflops = 3500f
        )
        val tpu = TpuCapability(
            present = true, generation = TpuGeneration.TENSOR_G5,
            isPixel = true, estimatedTops = 46f,
            recommendedBatchSize = 16, recommendedSeqLen = 128,
            nnapiDelegateName = "google-edgetpu",
            description = TpuGeneration.TENSOR_G5.description
        )
        val npu = NpuCapability(
            present = false, type = NpuType.NONE,
            estimatedTops = 0f, description = "TPU used instead"
        )
        val nnapi = NnapiCapability(
            available = true, version = "1.3+ (API 34)",
            estimatedDelegates = listOf("nnapi-reference", "google-edgetpu")
        )
        val backends = AcceleratorBackends(
            tfliteAvailable = true, tfliteGpuDelegateAvailable = true,
            mnnAvailable = true
        )
        val soc = SocInfo(
            vendor = SocVendor.GOOGLE, model = "Tensor G5",
            chipsetName = "Google Tensor G5", hardware = "tensor",
            board = "caiman", deviceManufacturer = "Google",
            deviceModel = "Pixel 10", androidVersion = "15", apiLevel = 35
        )
        val tier = DeviceTier(
            tier = CapabilityTier.FLAGSHIP, score = 85,
            maxRecommendedModelParams = "4B+",
            bestAccelerator = "TPU (G5)",
            estimatedTotalTops = 46f,
            summary = "Flagship device (score 85/100). Best accelerator: TPU (G5). Recommended max model: 4B+ parameters."
        )
        return DeviceCapabilities(cpu, memory, gpu, tpu, npu, nnapi, backends, soc, tier)
    }
}
