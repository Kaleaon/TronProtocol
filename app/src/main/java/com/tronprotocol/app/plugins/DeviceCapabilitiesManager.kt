package com.tronprotocol.app.plugins

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Comprehensive device hardware capability detection and scoring.
 *
 * Consolidates and expands on detection logic previously scattered across
 * PixelTpuTrainingManager, OnDeviceLLMManager.assessDevice(), and
 * ModelCatalog.DeviceSocInfo into a single, authoritative source of truth
 * for all hardware capability queries.
 *
 * Detects:
 * - Google Tensor TPU (G1 through G5, all Pixel generations)
 * - GPU family and tier (Adreno, Mali, Immortalis, PowerVR)
 * - NPU/DSP accelerators (Qualcomm Hexagon, Samsung Exynos NPU, MediaTek APU)
 * - CPU topology (architecture, core count, big.LITTLE, frequencies)
 * - NNAPI availability and estimated delegate support
 * - Vulkan and OpenCL runtime presence
 * - Memory capacity and availability
 * - Overall capability tier for inference workloads
 */
class DeviceCapabilitiesManager(private val context: Context) {

    companion object {
        private const val TAG = "DeviceCapabilities"
    }

    // ---- Public API ----

    /**
     * Run full device capability assessment. Safe to call from any thread.
     * Results are computed fresh each invocation (memory availability changes).
     */
    fun assess(): DeviceCapabilities {
        val cpu = detectCpu()
        val memory = detectMemory()
        val gpu = detectGpu(cpu)
        val tpu = detectTpu()
        val npu = detectNpu()
        val nnapi = detectNnapi()
        val acceleratorBackends = detectAcceleratorBackends()
        val soc = detectSoc()
        val tier = computeTier(cpu, memory, gpu, tpu, npu, nnapi)

        return DeviceCapabilities(
            cpu = cpu,
            memory = memory,
            gpu = gpu,
            tpu = tpu,
            npu = npu,
            nnapi = nnapi,
            acceleratorBackends = acceleratorBackends,
            soc = soc,
            tier = tier
        )
    }

    // ---- CPU Detection ----

    private fun detectCpu(): CpuCapability {
        val abis = Build.SUPPORTED_ABIS?.toList() ?: emptyList()
        val primaryArch = abis.firstOrNull() ?: "unknown"
        val supportsArm64 = abis.any { it == "arm64-v8a" }
        val supportsX86_64 = abis.any { it == "x86_64" }

        val processorCount = Runtime.getRuntime().availableProcessors()

        // FP16 NEON: available on ARMv8.2+ (practically all arm64 devices API 28+)
        val supportsFp16 = supportsArm64 && Build.VERSION.SDK_INT >= 28

        // Dot-product instructions (ARMv8.4+): API 30+ on arm64 is a reasonable proxy
        val supportsDotProd = supportsArm64 && Build.VERSION.SDK_INT >= 30

        // I8MM (Int8 matrix multiply, ARMv8.6+): API 31+ on arm64
        val supportsI8mm = supportsArm64 && Build.VERSION.SDK_INT >= 31

        // Read core frequencies from sysfs
        val coreFrequencies = readCoreFrequencies(processorCount)

        // Detect big.LITTLE topology from frequency variance
        val topology = detectTopology(coreFrequencies, processorCount)

        return CpuCapability(
            primaryArch = primaryArch,
            supportedAbis = abis,
            supportsArm64 = supportsArm64,
            supportsX86_64 = supportsX86_64,
            processorCount = processorCount,
            supportsFp16 = supportsFp16,
            supportsDotProd = supportsDotProd,
            supportsI8mm = supportsI8mm,
            coreFrequenciesMhz = coreFrequencies,
            topology = topology,
            recommendedThreads = computeRecommendedThreads(topology, processorCount)
        )
    }

    private fun readCoreFrequencies(processorCount: Int): List<Int> {
        val frequencies = mutableListOf<Int>()
        for (i in 0 until processorCount) {
            val maxFreq = readIntFromFile(
                "/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq"
            )
            // sysfs reports kHz, convert to MHz
            if (maxFreq > 0) {
                frequencies.add(maxFreq / 1000)
            }
        }
        return frequencies
    }

    private fun detectTopology(
        frequencies: List<Int>,
        processorCount: Int
    ): CpuTopology {
        if (frequencies.size < 2) {
            return CpuTopology(
                type = TopologyType.UNKNOWN,
                bigCores = processorCount,
                littleCores = 0,
                midCores = 0,
                maxFrequencyMhz = frequencies.firstOrNull() ?: 0
            )
        }

        val sorted = frequencies.sorted()
        val maxFreq = sorted.last()
        val minFreq = sorted.first()

        // If max/min differ by >30%, we have heterogeneous cores
        if (maxFreq <= 0 || (maxFreq - minFreq).toFloat() / maxFreq < 0.30f) {
            return CpuTopology(
                type = TopologyType.SYMMETRIC,
                bigCores = processorCount,
                littleCores = 0,
                midCores = 0,
                maxFrequencyMhz = maxFreq
            )
        }

        // Cluster by frequency: use k-means-style thresholds
        val midThreshold = minFreq + (maxFreq - minFreq) * 0.4
        val bigThreshold = minFreq + (maxFreq - minFreq) * 0.75

        var little = 0
        var mid = 0
        var big = 0
        for (freq in frequencies) {
            when {
                freq >= bigThreshold -> big++
                freq >= midThreshold -> mid++
                else -> little++
            }
        }

        // Tri-cluster (1+3+4) or bi-cluster (4+4)?
        val type = when {
            mid > 0 && little > 0 && big > 0 -> TopologyType.TRI_CLUSTER
            big > 0 && little > 0 -> TopologyType.BIG_LITTLE
            else -> TopologyType.SYMMETRIC
        }

        return CpuTopology(
            type = type,
            bigCores = big,
            midCores = mid,
            littleCores = little,
            maxFrequencyMhz = maxFreq
        )
    }

    private fun computeRecommendedThreads(
        topology: CpuTopology,
        processorCount: Int
    ): Int {
        // Use big + mid cores for inference, capped at 4 for battery
        val performanceCores = topology.bigCores + topology.midCores
        return when {
            performanceCores > 0 -> minOf(performanceCores, 4)
            else -> minOf(processorCount, 4)
        }
    }

    // ---- Memory Detection ----

    private fun detectMemory(): MemoryCapability {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)

        val totalMb = memInfo.totalMem / (1024 * 1024)
        val availableMb = memInfo.availMem / (1024 * 1024)
        val lowMemory = memInfo.lowMemory
        val threshold = memInfo.threshold / (1024 * 1024)

        // Java heap limits
        val runtimeMaxMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)

        // Large heap available?
        val largeHeap = try {
            am?.largeMemoryClass?.toLong() ?: 0L
        } catch (_: Exception) { 0L }

        return MemoryCapability(
            totalRamMb = totalMb,
            availableRamMb = availableMb,
            lowMemory = lowMemory,
            lowMemoryThresholdMb = threshold,
            javaHeapMaxMb = runtimeMaxMb,
            largeHeapMb = largeHeap
        )
    }

    // ---- GPU Detection ----

    private fun detectGpu(cpu: CpuCapability): GpuCapability {
        // OpenGL renderer string is the most reliable way to identify GPU
        // on Android, but requires an EGL context. We fall back to SoC-based
        // heuristics which are reliable for known chipsets.
        val socModel = getSocModel()
        val hardware = Build.HARDWARE.lowercase()
        val board = Build.BOARD.lowercase()

        val family = detectGpuFamily(socModel, hardware, board)
        val tier = estimateGpuTier(family, socModel)

        // Vulkan support: API 24+ on arm64 is the baseline, but actual
        // Vulkan 1.1+ is what we need for compute shaders
        val vulkanSupported = cpu.supportsArm64 && Build.VERSION.SDK_INT >= 24 &&
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)

        val vulkanVersion = detectVulkanVersion()

        // OpenCL: check for libOpenCL.so presence (used by MNN GPU backend)
        val openclAvailable = checkOpenClAvailable()

        return GpuCapability(
            family = family,
            tier = tier,
            vulkanSupported = vulkanSupported,
            vulkanVersion = vulkanVersion,
            openclAvailable = openclAvailable,
            estimatedGflops = estimateGpuGflops(family, tier)
        )
    }

    private fun detectGpuFamily(
        socModel: String,
        hardware: String,
        board: String
    ): GpuFamily {
        val soc = socModel.lowercase()

        return when {
            // Google Tensor uses Mali/Immortalis
            hardware.contains("tensor") || board.contains("tensor") ||
                    hardware.contains("gs") || soc.contains("tensor") ->
                detectMaliOrImmortalis(soc, hardware)

            // Qualcomm Snapdragon -> Adreno
            soc.startsWith("sm") || hardware.contains("qcom") ||
                    board.contains("msm") || board.contains("sdm") ||
                    soc.contains("snapdragon") ->
                GpuFamily.ADRENO

            // Samsung Exynos -> Mali/Immortalis (newer) or Mali (older)
            soc.contains("exynos") || hardware.contains("exynos") ||
                    soc.contains("s5e") ->
                detectMaliOrImmortalis(soc, hardware)

            // MediaTek Dimensity -> Mali/Immortalis
            soc.contains("mt") || hardware.contains("mt") ||
                    soc.contains("dimensity") ->
                detectMaliOrImmortalis(soc, hardware)

            // Apple (rare on Android, but Rosetta/emulation)
            soc.contains("apple") -> GpuFamily.APPLE_GPU

            // Intel/AMD (Chromebooks, emulators)
            soc.contains("intel") || hardware.contains("intel") -> GpuFamily.INTEL
            soc.contains("amd") -> GpuFamily.AMD

            // PowerVR (some older MediaTek devices)
            hardware.contains("powervr") || hardware.contains("img") ->
                GpuFamily.POWERVR

            else -> GpuFamily.UNKNOWN
        }
    }

    private fun detectMaliOrImmortalis(soc: String, hardware: String): GpuFamily {
        // Immortalis GPUs are high-end Mali successors (G715+, G720, G925)
        // Present in Tensor G3+, Exynos 2400+, Dimensity 9300+
        val combined = "$soc $hardware"
        return when {
            combined.contains("immortalis") -> GpuFamily.IMMORTALIS
            // Tensor G3+ and Exynos 2400+ use Immortalis
            combined.contains("g5") || combined.contains("g4") ||
                    combined.contains("g3") -> GpuFamily.IMMORTALIS
            else -> GpuFamily.MALI
        }
    }

    private fun estimateGpuTier(family: GpuFamily, socModel: String): GpuTier {
        val soc = socModel.lowercase()
        return when (family) {
            GpuFamily.ADRENO -> when {
                // Adreno 750/830 (8 Gen 3/4)
                soc.contains("sm8750") || soc.contains("sm8845") ||
                        soc.contains("sm8650") || soc.contains("sm8735") -> GpuTier.FLAGSHIP
                // Adreno 740 (8 Gen 2)
                soc.contains("sm8550") -> GpuTier.HIGH_END
                // Adreno 730 (8 Gen 1)
                soc.contains("sm8450") || soc.contains("sm8475") -> GpuTier.HIGH_END
                // Adreno 660/680 (888/8cx)
                soc.contains("sm8350") || soc.contains("sm8250") -> GpuTier.MID_RANGE
                else -> GpuTier.ENTRY
            }
            GpuFamily.IMMORTALIS -> GpuTier.FLAGSHIP
            GpuFamily.MALI -> GpuTier.MID_RANGE
            GpuFamily.POWERVR -> GpuTier.ENTRY
            GpuFamily.APPLE_GPU -> GpuTier.FLAGSHIP
            else -> GpuTier.UNKNOWN
        }
    }

    private fun detectVulkanVersion(): String {
        return try {
            if (Build.VERSION.SDK_INT >= 24) {
                val hasLevel1 = context.packageManager.hasSystemFeature(
                    PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL, 1
                )
                val hasCompute0 = context.packageManager.hasSystemFeature(
                    PackageManager.FEATURE_VULKAN_HARDWARE_COMPUTE, 0
                )
                val version = when {
                    Build.VERSION.SDK_INT >= 31 && hasLevel1 -> "1.1+"
                    hasLevel1 -> "1.1"
                    hasCompute0 -> "1.0 (compute)"
                    context.packageManager.hasSystemFeature(
                        PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL
                    ) -> "1.0"
                    else -> "none"
                }
                version
            } else "unsupported"
        } catch (e: Exception) {
            Log.d(TAG, "Vulkan version detection failed: ${e.message}")
            "unknown"
        }
    }

    private fun checkOpenClAvailable(): Boolean {
        // Check common library paths for libOpenCL.so
        val paths = listOf(
            "/system/lib64/libOpenCL.so",
            "/system/vendor/lib64/libOpenCL.so",
            "/vendor/lib64/libOpenCL.so",
            "/system/lib/libOpenCL.so",
            "/system/vendor/lib/libOpenCL.so",
            "/vendor/lib/libOpenCL.so"
        )
        return paths.any { File(it).exists() }
    }

    private fun estimateGpuGflops(family: GpuFamily, tier: GpuTier): Float {
        // Rough GFLOPS estimates for FP16 throughput — useful for inference sizing
        return when (tier) {
            GpuTier.FLAGSHIP -> when (family) {
                GpuFamily.ADRENO -> 4500f    // Adreno 750/830
                GpuFamily.IMMORTALIS -> 3500f // Immortalis G720/G925
                GpuFamily.APPLE_GPU -> 4000f
                else -> 3000f
            }
            GpuTier.HIGH_END -> when (family) {
                GpuFamily.ADRENO -> 2800f    // Adreno 730/740
                GpuFamily.IMMORTALIS -> 2500f
                else -> 2000f
            }
            GpuTier.MID_RANGE -> when (family) {
                GpuFamily.ADRENO -> 1200f    // Adreno 660
                GpuFamily.MALI -> 800f
                else -> 600f
            }
            GpuTier.ENTRY -> 300f
            GpuTier.UNKNOWN -> 0f
        }
    }

    // ---- TPU Detection (Google Tensor) ----

    private fun detectTpu(): TpuCapability {
        val socModel = getSocModel()
        val hardware = Build.HARDWARE.lowercase()
        val model = Build.MODEL.lowercase()
        val board = Build.BOARD.lowercase()

        val isPixel = model.contains("pixel") ||
                Build.MANUFACTURER.equals("Google", ignoreCase = true)

        // Detect Tensor SoC family
        val isTensor = socModel.contains("Tensor", ignoreCase = true) ||
                hardware.contains("tensor") ||
                board.contains("tensor") ||
                hardware.contains("gs")  // Google SoC codename prefix

        val generation = detectTpuGeneration(socModel, model, hardware, board, isTensor)

        return TpuCapability(
            present = generation != TpuGeneration.NONE,
            generation = generation,
            isPixel = isPixel,
            estimatedTops = generation.estimatedTops,
            recommendedBatchSize = generation.recommendedBatchSize,
            recommendedSeqLen = generation.recommendedSeqLen,
            nnapiDelegateName = if (generation != TpuGeneration.NONE) "google-edgetpu" else null,
            description = generation.description
        )
    }

    private fun detectTpuGeneration(
        socModel: String,
        model: String,
        hardware: String,
        board: String,
        isTensor: Boolean
    ): TpuGeneration {
        val soc = socModel.lowercase()

        // Tensor G5 — Pixel 10 (codename: caiman/komodo)
        if (soc.contains("g5") || model.contains("pixel 10") ||
            model.contains("caiman") || model.contains("komodo")) {
            return TpuGeneration.TENSOR_G5
        }

        // Tensor G4 Pro — Pixel 9 Pro Fold (codename: comet)
        if (soc.contains("g4 pro") || model.contains("comet")) {
            return TpuGeneration.TENSOR_G4_PRO
        }

        // Tensor G4 — Pixel 9 (codename: tokay/caiman)
        if (soc.contains("g4") || model.contains("pixel 9") ||
            model.contains("tokay")) {
            return TpuGeneration.TENSOR_G4
        }

        // Tensor G3 Pro — Pixel 8 Pro (codename: husky)
        if (soc.contains("g3 pro") || model.contains("husky")) {
            return TpuGeneration.TENSOR_G3_PRO
        }

        // Tensor G3 — Pixel 8 (codename: shiba/husky)
        if (soc.contains("g3") || model.contains("pixel 8") ||
            model.contains("shiba")) {
            return TpuGeneration.TENSOR_G3
        }

        // Tensor G2 — Pixel 7 (codename: cheetah/panther)
        if (soc.contains("g2") || model.contains("pixel 7") ||
            model.contains("cheetah") || model.contains("panther")) {
            return TpuGeneration.TENSOR_G2
        }

        // Tensor G1 — Pixel 6 (codename: oriole/raven)
        if (soc.contains("g1") || soc.contains("gs101") ||
            model.contains("pixel 6") ||
            model.contains("oriole") || model.contains("raven")) {
            return TpuGeneration.TENSOR_G1
        }

        // Generic Tensor detection
        if (isTensor) {
            return TpuGeneration.TENSOR_GENERIC
        }

        return TpuGeneration.NONE
    }

    // ---- NPU / DSP Detection (non-Google accelerators) ----

    private fun detectNpu(): NpuCapability {
        val socModel = getSocModel()
        val soc = socModel.lowercase()
        val hardware = Build.HARDWARE.lowercase()

        // Qualcomm Hexagon DSP / HTP (Hexagon Tensor Processor)
        if (soc.startsWith("sm") || hardware.contains("qcom")) {
            return detectQualcommNpu(soc)
        }

        // Samsung Exynos NPU
        if (soc.contains("exynos") || soc.contains("s5e") || hardware.contains("exynos")) {
            return detectExynosNpu(soc)
        }

        // MediaTek APU (AI Processing Unit)
        if (soc.startsWith("mt") || soc.contains("dimensity") || hardware.startsWith("mt")) {
            return detectMediatekNpu(soc, hardware)
        }

        return NpuCapability(
            present = false,
            type = NpuType.NONE,
            estimatedTops = 0f,
            description = "No dedicated NPU detected"
        )
    }

    private fun detectQualcommNpu(soc: String): NpuCapability {
        // Hexagon Tensor Processor (HTP) generations by SoC
        val (version, tops) = when {
            // Snapdragon 8 Gen 4 (SM8750)
            soc.contains("sm8750") || soc.contains("sm8845") ->
                "Hexagon HTP v79" to 75f
            // Snapdragon 8 Gen 3 (SM8650)
            soc.contains("sm8650") || soc.contains("sm8735") ->
                "Hexagon HTP v75" to 45f
            // Snapdragon 8 Gen 2 (SM8550)
            soc.contains("sm8550") ->
                "Hexagon HTP v73" to 35f
            // Snapdragon 8 Gen 1 (SM8450)
            soc.contains("sm8450") || soc.contains("sm8475") ->
                "Hexagon HTP v69" to 27f
            // Snapdragon 888 (SM8350)
            soc.contains("sm8350") ->
                "Hexagon DSP v68" to 15f
            // Snapdragon 865 (SM8250)
            soc.contains("sm8250") ->
                "Hexagon DSP v66" to 12f
            // Snapdragon 7 series
            soc.contains("sm7") ->
                "Hexagon DSP" to 8f
            // Snapdragon 6 series
            soc.contains("sm6") ->
                "Hexagon DSP (lite)" to 4f
            else ->
                "Hexagon DSP" to 5f
        }

        return NpuCapability(
            present = true,
            type = NpuType.QUALCOMM_HEXAGON,
            estimatedTops = tops,
            description = "$version (~${tops.toInt()} TOPS)"
        )
    }

    private fun detectExynosNpu(soc: String): NpuCapability {
        val (version, tops) = when {
            // Exynos 2500
            soc.contains("2500") || soc.contains("s5e9955") ->
                "Exynos NPU (dual-core)" to 40f
            // Exynos 2400
            soc.contains("2400") || soc.contains("s5e9945") ->
                "Exynos NPU (dual-core)" to 34f
            // Exynos 2200
            soc.contains("2200") || soc.contains("s5e9925") ->
                "Exynos NPU" to 20f
            // Exynos 2100
            soc.contains("2100") || soc.contains("s5e9840") ->
                "Exynos NPU" to 15f
            else ->
                "Exynos NPU" to 8f
        }

        return NpuCapability(
            present = true,
            type = NpuType.SAMSUNG_NPU,
            estimatedTops = tops,
            description = "$version (~${tops.toInt()} TOPS)"
        )
    }

    private fun detectMediatekNpu(soc: String, hardware: String): NpuCapability {
        val combined = "$soc $hardware"
        val (version, tops) = when {
            // Dimensity 9400
            combined.contains("9400") || combined.contains("mt6991") ->
                "MediaTek APU 8.0" to 46f
            // Dimensity 9300
            combined.contains("9300") || combined.contains("mt6989") ->
                "MediaTek APU 7.0" to 40f
            // Dimensity 9200
            combined.contains("9200") || combined.contains("mt6985") ->
                "MediaTek APU 6.0" to 30f
            // Dimensity 8000 series
            combined.contains("8300") || combined.contains("8200") ||
                    combined.contains("8100") ->
                "MediaTek APU 5.0" to 15f
            // Dimensity 7000 series
            combined.contains("7") ->
                "MediaTek APU" to 8f
            else ->
                "MediaTek APU" to 5f
        }

        return NpuCapability(
            present = true,
            type = NpuType.MEDIATEK_APU,
            estimatedTops = tops,
            description = "$version (~${tops.toInt()} TOPS)"
        )
    }

    // ---- NNAPI Detection ----

    private fun detectNnapi(): NnapiCapability {
        val available = Build.VERSION.SDK_INT >= 27
        val version = when {
            Build.VERSION.SDK_INT >= 34 -> "1.3+ (API 34)"
            Build.VERSION.SDK_INT >= 31 -> "1.3 (API 31)"
            Build.VERSION.SDK_INT >= 30 -> "1.2 (API 30)"
            Build.VERSION.SDK_INT >= 29 -> "1.2 (API 29)"
            Build.VERSION.SDK_INT >= 28 -> "1.1 (API 28)"
            Build.VERSION.SDK_INT >= 27 -> "1.0 (API 27)"
            else -> "unavailable"
        }

        // Estimate available delegates based on known SoC
        val delegates = mutableListOf<String>()
        if (available) {
            delegates.add("nnapi-reference") // always present
            val socModel = getSocModel().lowercase()
            val hardware = Build.HARDWARE.lowercase()

            when {
                socModel.contains("tensor") || hardware.contains("tensor") ||
                        hardware.contains("gs") -> {
                    delegates.add("google-edgetpu")
                    delegates.add("armnn")
                }
                socModel.startsWith("sm") || hardware.contains("qcom") -> {
                    delegates.add("qti-htp")
                    delegates.add("qti-dsp")
                    delegates.add("qti-gpu")
                }
                socModel.contains("exynos") || socModel.contains("s5e") -> {
                    delegates.add("samsung-eden")
                    delegates.add("armnn")
                }
                socModel.startsWith("mt") || hardware.startsWith("mt") -> {
                    delegates.add("mtk-neuron")
                    delegates.add("armnn")
                }
            }
        }

        return NnapiCapability(
            available = available,
            version = version,
            estimatedDelegates = delegates
        )
    }

    // ---- Accelerator Backend Detection ----

    private fun detectAcceleratorBackends(): AcceleratorBackends {
        // Check for TFLite GPU delegate library
        val tfliteGpu = try {
            Class.forName("org.tensorflow.lite.gpu.GpuDelegate")
            true
        } catch (_: ClassNotFoundException) {
            false
        }

        // Check for MNN native library
        val mnnAvailable = try {
            // Defer to OnDeviceLLMManager's detection if we're in the same process
            Class.forName("com.tronprotocol.app.llm.OnDeviceLLMManager")
                .getMethod("isNativeAvailable")
                .invoke(null) as? Boolean ?: false
        } catch (_: Exception) {
            false
        }

        return AcceleratorBackends(
            tfliteAvailable = checkTfliteAvailable(),
            tfliteGpuDelegateAvailable = tfliteGpu,
            mnnAvailable = mnnAvailable
        )
    }

    private fun checkTfliteAvailable(): Boolean {
        return try {
            Class.forName("org.tensorflow.lite.Interpreter")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }

    // ---- SoC Detection ----

    private fun detectSoc(): SocInfo {
        val socModel = getSocModel()
        val hardware = Build.HARDWARE
        val board = Build.BOARD
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL

        val vendor = detectSocVendor(socModel, hardware, board, manufacturer)
        val chipsetName = resolveChipsetName(vendor, socModel, hardware, model)

        return SocInfo(
            vendor = vendor,
            model = socModel,
            chipsetName = chipsetName,
            hardware = hardware,
            board = board,
            deviceManufacturer = manufacturer,
            deviceModel = model,
            androidVersion = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT
        )
    }

    private fun detectSocVendor(
        socModel: String,
        hardware: String,
        board: String,
        manufacturer: String
    ): SocVendor {
        val soc = socModel.lowercase()
        val hw = hardware.lowercase()
        val bd = board.lowercase()
        val mfr = manufacturer.lowercase()

        return when {
            soc.contains("tensor") || hw.contains("tensor") || hw.contains("gs") ||
                    (mfr == "google" && (bd.contains("oriole") || bd.contains("raven") ||
                            bd.contains("cheetah") || bd.contains("panther") ||
                            bd.contains("shiba") || bd.contains("husky") ||
                            bd.contains("tokay") || bd.contains("caiman"))) ->
                SocVendor.GOOGLE

            soc.startsWith("sm") || hw.contains("qcom") || bd.contains("msm") ||
                    bd.contains("sdm") ->
                SocVendor.QUALCOMM

            soc.contains("exynos") || soc.contains("s5e") || hw.contains("exynos") ->
                SocVendor.SAMSUNG

            soc.startsWith("mt") || hw.startsWith("mt") || soc.contains("dimensity") ->
                SocVendor.MEDIATEK

            soc.contains("kirin") || hw.contains("kirin") ->
                SocVendor.HISILICON

            soc.contains("unisoc") || soc.contains("spreadtrum") || hw.contains("ums") ->
                SocVendor.UNISOC

            else -> SocVendor.UNKNOWN
        }
    }

    private fun resolveChipsetName(
        vendor: SocVendor,
        socModel: String,
        hardware: String,
        deviceModel: String
    ): String {
        val soc = socModel.lowercase()
        return when (vendor) {
            SocVendor.GOOGLE -> resolveGoogleChipset(soc, deviceModel.lowercase())
            SocVendor.QUALCOMM -> resolveQualcommChipset(soc)
            SocVendor.SAMSUNG -> resolveExynosChipset(soc)
            SocVendor.MEDIATEK -> resolveMediatekChipset(soc, hardware.lowercase())
            else -> socModel.ifEmpty { hardware }
        }
    }

    private fun resolveGoogleChipset(soc: String, model: String): String = when {
        soc.contains("g5") || model.contains("pixel 10") -> "Google Tensor G5"
        soc.contains("g4") || model.contains("pixel 9") -> "Google Tensor G4"
        soc.contains("g3") || model.contains("pixel 8") -> "Google Tensor G3"
        soc.contains("g2") || model.contains("pixel 7") -> "Google Tensor G2"
        soc.contains("gs101") || soc.contains("g1") || model.contains("pixel 6") ->
            "Google Tensor G1"
        else -> "Google Tensor"
    }

    private fun resolveQualcommChipset(soc: String): String = when {
        soc.contains("sm8750") || soc.contains("sm8845") -> "Snapdragon 8 Gen 4"
        soc.contains("sm8650") || soc.contains("sm8735") -> "Snapdragon 8 Gen 3"
        soc.contains("sm8550") -> "Snapdragon 8 Gen 2"
        soc.contains("sm8450") || soc.contains("sm8475") -> "Snapdragon 8 Gen 1"
        soc.contains("sm8350") -> "Snapdragon 888"
        soc.contains("sm8250") -> "Snapdragon 865"
        soc.contains("sm7") -> "Snapdragon 7-series"
        soc.contains("sm6") -> "Snapdragon 6-series"
        soc.contains("sm4") -> "Snapdragon 4-series"
        else -> "Qualcomm $soc"
    }

    private fun resolveExynosChipset(soc: String): String = when {
        soc.contains("2500") || soc.contains("s5e9955") -> "Exynos 2500"
        soc.contains("2400") || soc.contains("s5e9945") -> "Exynos 2400"
        soc.contains("2200") || soc.contains("s5e9925") -> "Exynos 2200"
        soc.contains("2100") || soc.contains("s5e9840") -> "Exynos 2100"
        else -> "Samsung Exynos"
    }

    private fun resolveMediatekChipset(soc: String, hardware: String): String {
        val combined = "$soc $hardware"
        return when {
            combined.contains("9400") -> "Dimensity 9400"
            combined.contains("9300") -> "Dimensity 9300"
            combined.contains("9200") -> "Dimensity 9200"
            combined.contains("8300") -> "Dimensity 8300"
            combined.contains("8200") -> "Dimensity 8200"
            combined.contains("8100") -> "Dimensity 8100"
            combined.contains("7300") -> "Dimensity 7300"
            else -> "MediaTek $soc"
        }
    }

    // ---- Capability Tier Scoring ----

    private fun computeTier(
        cpu: CpuCapability,
        memory: MemoryCapability,
        gpu: GpuCapability,
        tpu: TpuCapability,
        npu: NpuCapability,
        nnapi: NnapiCapability
    ): DeviceTier {
        // Compute a weighted score (0-100)
        var score = 0

        // CPU (max 20 points)
        score += when {
            cpu.supportsI8mm -> 20
            cpu.supportsDotProd -> 16
            cpu.supportsFp16 -> 12
            cpu.supportsArm64 -> 8
            else -> 2
        }

        // Memory (max 20 points)
        score += when {
            memory.totalRamMb >= 12288 -> 20  // 12GB+
            memory.totalRamMb >= 8192 -> 16   // 8GB
            memory.totalRamMb >= 6144 -> 12   // 6GB
            memory.totalRamMb >= 4096 -> 8    // 4GB
            memory.totalRamMb >= 3072 -> 4    // 3GB
            else -> 1
        }

        // GPU (max 20 points)
        score += when (gpu.tier) {
            GpuTier.FLAGSHIP -> 20
            GpuTier.HIGH_END -> 15
            GpuTier.MID_RANGE -> 10
            GpuTier.ENTRY -> 5
            GpuTier.UNKNOWN -> 1
        }

        // TPU — significant bonus (max 25 points)
        score += when (tpu.generation) {
            TpuGeneration.TENSOR_G5 -> 25
            TpuGeneration.TENSOR_G4_PRO -> 23
            TpuGeneration.TENSOR_G4 -> 22
            TpuGeneration.TENSOR_G3_PRO -> 20
            TpuGeneration.TENSOR_G3 -> 18
            TpuGeneration.TENSOR_G2 -> 14
            TpuGeneration.TENSOR_G1 -> 10
            TpuGeneration.TENSOR_GENERIC -> 8
            TpuGeneration.NONE -> 0
        }

        // NPU (max 15 points) — only if no TPU (avoid double-counting)
        if (tpu.generation == TpuGeneration.NONE) {
            score += when {
                npu.estimatedTops >= 40f -> 15
                npu.estimatedTops >= 25f -> 12
                npu.estimatedTops >= 15f -> 9
                npu.estimatedTops >= 8f -> 6
                npu.present -> 3
                else -> 0
            }
        }

        // Determine tier
        val tier = when {
            score >= 75 -> CapabilityTier.FLAGSHIP
            score >= 55 -> CapabilityTier.HIGH_END
            score >= 35 -> CapabilityTier.MID_RANGE
            score >= 20 -> CapabilityTier.ENTRY
            else -> CapabilityTier.MINIMAL
        }

        // Inference recommendation
        val maxModelParams = when (tier) {
            CapabilityTier.FLAGSHIP -> "4B+"
            CapabilityTier.HIGH_END -> "3B"
            CapabilityTier.MID_RANGE -> "1.5B-1.7B"
            CapabilityTier.ENTRY -> "1B"
            CapabilityTier.MINIMAL -> "none"
        }

        // Best available accelerator for ML
        val bestAccelerator = when {
            tpu.present -> "TPU (${tpu.generation.shortName})"
            npu.present -> "${npu.type.displayName} (${npu.estimatedTops.toInt()} TOPS)"
            gpu.tier >= GpuTier.MID_RANGE && gpu.openclAvailable -> "GPU (${gpu.family.displayName})"
            else -> "CPU"
        }

        // Total estimated TOPS
        val totalTops = when {
            tpu.present -> tpu.estimatedTops
            npu.present -> npu.estimatedTops
            else -> 0f
        }

        return DeviceTier(
            tier = tier,
            score = score,
            maxRecommendedModelParams = maxModelParams,
            bestAccelerator = bestAccelerator,
            estimatedTotalTops = totalTops,
            summary = buildTierSummary(tier, score, bestAccelerator, maxModelParams)
        )
    }

    private fun buildTierSummary(
        tier: CapabilityTier,
        score: Int,
        bestAccelerator: String,
        maxParams: String
    ): String {
        return "${tier.displayName} device (score $score/100). " +
                "Best accelerator: $bestAccelerator. " +
                "Recommended max model: $maxParams parameters."
    }

    // ---- Utilities ----

    private fun getSocModel(): String {
        return try {
            if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else ""
        } catch (_: Exception) { "" }
    }

    private fun readIntFromFile(path: String): Int {
        return try {
            File(path).readText().trim().toIntOrNull() ?: 0
        } catch (_: Exception) {
            0
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// Data model
// ══════════════════════════════════════════════════════════════════════

/** Complete device capability assessment result. */
data class DeviceCapabilities(
    val cpu: CpuCapability,
    val memory: MemoryCapability,
    val gpu: GpuCapability,
    val tpu: TpuCapability,
    val npu: NpuCapability,
    val nnapi: NnapiCapability,
    val acceleratorBackends: AcceleratorBackends,
    val soc: SocInfo,
    val tier: DeviceTier
) {
    /** Structured JSON representation for storage or API responses. */
    fun toJson(): JSONObject = JSONObject().apply {
        put("cpu", cpu.toJson())
        put("memory", memory.toJson())
        put("gpu", gpu.toJson())
        put("tpu", tpu.toJson())
        put("npu", npu.toJson())
        put("nnapi", nnapi.toJson())
        put("backends", acceleratorBackends.toJson())
        put("soc", soc.toJson())
        put("tier", tier.toJson())
    }

    /** Human-readable multiline summary. */
    fun toSummary(): String = buildString {
        append("=== Device Capabilities ===\n\n")

        append("--- SoC ---\n")
        append("Chipset: ${soc.chipsetName}\n")
        append("Vendor: ${soc.vendor.displayName}\n")
        append("Device: ${soc.deviceManufacturer} ${soc.deviceModel}\n")
        append("Android: ${soc.androidVersion} (API ${soc.apiLevel})\n\n")

        append("--- CPU ---\n")
        append("Architecture: ${cpu.primaryArch}\n")
        append("Cores: ${cpu.processorCount}")
        if (cpu.topology.type != TopologyType.UNKNOWN) {
            append(" (${cpu.topology})")
        }
        append("\n")
        if (cpu.topology.maxFrequencyMhz > 0) {
            append("Max Frequency: ${cpu.topology.maxFrequencyMhz} MHz\n")
        }
        append("FP16: ${cpu.supportsFp16} | DotProd: ${cpu.supportsDotProd} | I8MM: ${cpu.supportsI8mm}\n")
        append("Recommended Threads: ${cpu.recommendedThreads}\n\n")

        append("--- Memory ---\n")
        append("Total RAM: ${memory.totalRamMb} MB\n")
        append("Available: ${memory.availableRamMb} MB")
        if (memory.lowMemory) append(" [LOW]")
        append("\n")
        append("Java Heap Max: ${memory.javaHeapMaxMb} MB\n\n")

        append("--- GPU ---\n")
        append("Family: ${gpu.family.displayName}\n")
        append("Tier: ${gpu.tier.displayName}\n")
        if (gpu.estimatedGflops > 0) {
            append("Estimated FP16: ~${gpu.estimatedGflops.toInt()} GFLOPS\n")
        }
        append("Vulkan: ${gpu.vulkanVersion}\n")
        append("OpenCL: ${if (gpu.openclAvailable) "available" else "not found"}\n\n")

        append("--- ML Accelerators ---\n")
        if (tpu.present) {
            append("TPU: ${tpu.generation.displayName}\n")
            append("  Estimated: ~${tpu.estimatedTops.toInt()} TOPS\n")
            append("  Recommended batch: ${tpu.recommendedBatchSize}, seq_len: ${tpu.recommendedSeqLen}\n")
            if (tpu.nnapiDelegateName != null) {
                append("  NNAPI delegate: ${tpu.nnapiDelegateName}\n")
            }
        } else {
            append("TPU: not present\n")
        }

        if (npu.present) {
            append("NPU: ${npu.description}\n")
        } else {
            append("NPU: not present\n")
        }

        append("NNAPI: ${nnapi.version}\n")
        if (nnapi.estimatedDelegates.isNotEmpty()) {
            append("  Delegates: ${nnapi.estimatedDelegates.joinToString(", ")}\n")
        }
        append("\n")

        append("--- Inference Backends ---\n")
        append("TFLite: ${if (acceleratorBackends.tfliteAvailable) "yes" else "no"}")
        if (acceleratorBackends.tfliteGpuDelegateAvailable) append(" (GPU delegate available)")
        append("\n")
        append("MNN: ${if (acceleratorBackends.mnnAvailable) "yes" else "no"}\n\n")

        append("--- Overall ---\n")
        append(tier.summary)
    }
}

// ---- CPU ----

data class CpuCapability(
    val primaryArch: String,
    val supportedAbis: List<String>,
    val supportsArm64: Boolean,
    val supportsX86_64: Boolean,
    val processorCount: Int,
    val supportsFp16: Boolean,
    val supportsDotProd: Boolean,
    val supportsI8mm: Boolean,
    val coreFrequenciesMhz: List<Int>,
    val topology: CpuTopology,
    val recommendedThreads: Int
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("primary_arch", primaryArch)
        put("supported_abis", JSONArray(supportedAbis))
        put("arm64", supportsArm64)
        put("x86_64", supportsX86_64)
        put("processor_count", processorCount)
        put("fp16", supportsFp16)
        put("dot_prod", supportsDotProd)
        put("i8mm", supportsI8mm)
        put("core_frequencies_mhz", JSONArray(coreFrequenciesMhz))
        put("topology", topology.toJson())
        put("recommended_threads", recommendedThreads)
    }
}

enum class TopologyType(val displayName: String) {
    SYMMETRIC("Symmetric"),
    BIG_LITTLE("big.LITTLE"),
    TRI_CLUSTER("Tri-cluster (1+3+4)"),
    UNKNOWN("Unknown")
}

data class CpuTopology(
    val type: TopologyType,
    val bigCores: Int,
    val midCores: Int = 0,
    val littleCores: Int,
    val maxFrequencyMhz: Int
) {
    override fun toString(): String = when (type) {
        TopologyType.TRI_CLUSTER -> "${bigCores}big+${midCores}mid+${littleCores}little"
        TopologyType.BIG_LITTLE -> "${bigCores}big+${littleCores}little"
        TopologyType.SYMMETRIC -> "${bigCores} cores"
        TopologyType.UNKNOWN -> "${bigCores + midCores + littleCores} cores"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.name)
        put("big_cores", bigCores)
        put("mid_cores", midCores)
        put("little_cores", littleCores)
        put("max_frequency_mhz", maxFrequencyMhz)
    }
}

// ---- Memory ----

data class MemoryCapability(
    val totalRamMb: Long,
    val availableRamMb: Long,
    val lowMemory: Boolean,
    val lowMemoryThresholdMb: Long,
    val javaHeapMaxMb: Long,
    val largeHeapMb: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("total_ram_mb", totalRamMb)
        put("available_ram_mb", availableRamMb)
        put("low_memory", lowMemory)
        put("low_memory_threshold_mb", lowMemoryThresholdMb)
        put("java_heap_max_mb", javaHeapMaxMb)
        put("large_heap_mb", largeHeapMb)
    }
}

// ---- GPU ----

enum class GpuFamily(val displayName: String) {
    ADRENO("Qualcomm Adreno"),
    MALI("Arm Mali"),
    IMMORTALIS("Arm Immortalis"),
    POWERVR("Imagination PowerVR"),
    APPLE_GPU("Apple GPU"),
    INTEL("Intel"),
    AMD("AMD"),
    UNKNOWN("Unknown")
}

enum class GpuTier(val displayName: String) {
    UNKNOWN("Unknown"),
    ENTRY("Entry-level"),
    MID_RANGE("Mid-range"),
    HIGH_END("High-end"),
    FLAGSHIP("Flagship")
}

data class GpuCapability(
    val family: GpuFamily,
    val tier: GpuTier,
    val vulkanSupported: Boolean,
    val vulkanVersion: String,
    val openclAvailable: Boolean,
    val estimatedGflops: Float
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("family", family.name)
        put("family_display", family.displayName)
        put("tier", tier.name)
        put("vulkan_supported", vulkanSupported)
        put("vulkan_version", vulkanVersion)
        put("opencl_available", openclAvailable)
        put("estimated_gflops_fp16", estimatedGflops)
    }
}

// ---- TPU (Google Tensor) ----

enum class TpuGeneration(
    val displayName: String,
    val shortName: String,
    val estimatedTops: Float,
    val recommendedBatchSize: Int,
    val recommendedSeqLen: Int,
    val description: String
) {
    TENSOR_G5(
        "Google Tensor G5", "G5", 46f, 16, 128,
        "Pixel 10 — 4nm Samsung, Immortalis G925, optimal on-device training"
    ),
    TENSOR_G4_PRO(
        "Google Tensor G4 Pro", "G4 Pro", 40f, 12, 112,
        "Pixel 9 Pro Fold — enhanced Tensor G4 with larger cache"
    ),
    TENSOR_G4(
        "Google Tensor G4", "G4", 37f, 8, 96,
        "Pixel 9 — 4nm TSMC, Immortalis G715, good on-device training"
    ),
    TENSOR_G3_PRO(
        "Google Tensor G3 Pro", "G3 Pro", 30f, 8, 80,
        "Pixel 8 Pro — enhanced G3 with temperature sensor, macro lens"
    ),
    TENSOR_G3(
        "Google Tensor G3", "G3", 28f, 6, 72,
        "Pixel 8 — 4nm Samsung, Mali-G715, on-device ML workloads"
    ),
    TENSOR_G2(
        "Google Tensor G2", "G2", 22f, 4, 64,
        "Pixel 7 — 5nm Samsung, Mali-G710, improved ML core over G1"
    ),
    TENSOR_G1(
        "Google Tensor G1", "G1", 15f, 4, 48,
        "Pixel 6 — 5nm Samsung, Mali-G78, first-gen Tensor TPU"
    ),
    TENSOR_GENERIC(
        "Google Tensor (unknown gen)", "Tensor", 20f, 4, 64,
        "Tensor SoC detected but generation unresolved"
    ),
    NONE(
        "None", "none", 0f, 2, 48,
        "No Google Tensor TPU detected"
    )
}

data class TpuCapability(
    val present: Boolean,
    val generation: TpuGeneration,
    val isPixel: Boolean,
    val estimatedTops: Float,
    val recommendedBatchSize: Int,
    val recommendedSeqLen: Int,
    val nnapiDelegateName: String?,
    val description: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("present", present)
        put("generation", generation.name)
        put("generation_display", generation.displayName)
        put("is_pixel", isPixel)
        put("estimated_tops", estimatedTops)
        put("recommended_batch_size", recommendedBatchSize)
        put("recommended_seq_len", recommendedSeqLen)
        if (nnapiDelegateName != null) put("nnapi_delegate", nnapiDelegateName)
        put("description", description)
    }
}

// ---- NPU / DSP ----

enum class NpuType(val displayName: String) {
    QUALCOMM_HEXAGON("Qualcomm Hexagon"),
    SAMSUNG_NPU("Samsung Exynos NPU"),
    MEDIATEK_APU("MediaTek APU"),
    NONE("None")
}

data class NpuCapability(
    val present: Boolean,
    val type: NpuType,
    val estimatedTops: Float,
    val description: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("present", present)
        put("type", type.name)
        put("type_display", type.displayName)
        put("estimated_tops", estimatedTops)
        put("description", description)
    }
}

// ---- NNAPI ----

data class NnapiCapability(
    val available: Boolean,
    val version: String,
    val estimatedDelegates: List<String>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("available", available)
        put("version", version)
        put("estimated_delegates", JSONArray(estimatedDelegates))
    }
}

// ---- Accelerator Backends ----

data class AcceleratorBackends(
    val tfliteAvailable: Boolean,
    val tfliteGpuDelegateAvailable: Boolean,
    val mnnAvailable: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("tflite", tfliteAvailable)
        put("tflite_gpu_delegate", tfliteGpuDelegateAvailable)
        put("mnn", mnnAvailable)
    }
}

// ---- SoC Info ----

enum class SocVendor(val displayName: String) {
    GOOGLE("Google"),
    QUALCOMM("Qualcomm"),
    SAMSUNG("Samsung"),
    MEDIATEK("MediaTek"),
    HISILICON("HiSilicon"),
    UNISOC("Unisoc"),
    UNKNOWN("Unknown")
}

data class SocInfo(
    val vendor: SocVendor,
    val model: String,
    val chipsetName: String,
    val hardware: String,
    val board: String,
    val deviceManufacturer: String,
    val deviceModel: String,
    val androidVersion: String,
    val apiLevel: Int
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("vendor", vendor.name)
        put("vendor_display", vendor.displayName)
        put("model", model)
        put("chipset_name", chipsetName)
        put("hardware", hardware)
        put("board", board)
        put("device_manufacturer", deviceManufacturer)
        put("device_model", deviceModel)
        put("android_version", androidVersion)
        put("api_level", apiLevel)
    }
}

// ---- Overall Tier ----

enum class CapabilityTier(val displayName: String) {
    FLAGSHIP("Flagship"),
    HIGH_END("High-end"),
    MID_RANGE("Mid-range"),
    ENTRY("Entry-level"),
    MINIMAL("Minimal")
}

data class DeviceTier(
    val tier: CapabilityTier,
    val score: Int,
    val maxRecommendedModelParams: String,
    val bestAccelerator: String,
    val estimatedTotalTops: Float,
    val summary: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("tier", tier.name)
        put("tier_display", tier.displayName)
        put("score", score)
        put("max_recommended_model_params", maxRecommendedModelParams)
        put("best_accelerator", bestAccelerator)
        put("estimated_total_tops", estimatedTotalTops)
        put("summary", summary)
    }
}
