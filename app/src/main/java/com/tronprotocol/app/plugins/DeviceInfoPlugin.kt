package com.tronprotocol.app.plugins

import android.content.Context
import android.util.Log

/**
 * Plugin that provides comprehensive device hardware capability information.
 *
 * Delegates to [DeviceCapabilitiesManager] for detection of CPU, GPU, TPU,
 * NPU/DSP, memory, NNAPI, Vulkan/OpenCL, and overall capability tier scoring.
 *
 * Commands:
 *   (empty/info)   - Full human-readable capability summary
 *   json           - Structured JSON output for programmatic consumption
 *   cpu            - CPU details (arch, cores, topology, ISA extensions)
 *   gpu            - GPU details (family, tier, Vulkan, OpenCL)
 *   tpu            - TPU / Google Tensor detection
 *   npu            - NPU / DSP accelerator detection
 *   memory         - RAM and heap details
 *   soc            - SoC identification
 *   tier           - Overall capability tier and scoring
 *   accelerators   - All ML accelerators summary (TPU + NPU + GPU + NNAPI)
 */
class DeviceInfoPlugin : Plugin {

    companion object {
        private const val TAG = "DeviceInfoPlugin"
        private const val ID = "device_info"
        private const val NAME = "Device Info"
        private const val DESCRIPTION = "Comprehensive device hardware capabilities. " +
                "Commands: info, json, cpu, gpu, tpu, npu, memory, soc, tier, accelerators"
    }

    private var context: Context? = null
    private var capabilitiesManager: DeviceCapabilitiesManager? = null

    override val id: String = ID

    override val name: String = NAME

    override val description: String = DESCRIPTION

    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val startTime = System.currentTimeMillis()

        val mgr = capabilitiesManager
        if (mgr == null) {
            return PluginResult.error(
                "DeviceCapabilitiesManager not initialized (no context)",
                System.currentTimeMillis() - startTime
            )
        }

        val caps = try {
            mgr.assess()
        } catch (e: Exception) {
            Log.e(TAG, "Capability assessment failed: ${e.message}", e)
            return PluginResult.error(
                "Assessment failed: ${e.message}",
                System.currentTimeMillis() - startTime
            )
        }

        val command = input.trim().lowercase()
        val output = when (command) {
            "", "info" -> caps.toSummary()
            "json" -> caps.toJson().toString(2)
            "cpu" -> formatCpu(caps)
            "gpu" -> formatGpu(caps)
            "tpu" -> formatTpu(caps)
            "npu" -> formatNpu(caps)
            "memory", "mem", "ram" -> formatMemory(caps)
            "soc", "chipset" -> formatSoc(caps)
            "tier", "score" -> formatTier(caps)
            "accelerators", "accel", "ml" -> formatAccelerators(caps)
            else -> "Unknown command: $command\n\n" +
                    "Available commands: info, json, cpu, gpu, tpu, npu, memory, soc, tier, accelerators"
        }

        val duration = System.currentTimeMillis() - startTime
        return PluginResult.success(output, duration)
    }

    override fun initialize(context: Context) {
        this.context = context
        this.capabilitiesManager = DeviceCapabilitiesManager(context)
    }

    override fun destroy() {
        context = null
        capabilitiesManager = null
    }

    /** Expose the manager so other components can query capabilities directly. */
    fun getCapabilitiesManager(): DeviceCapabilitiesManager? = capabilitiesManager

    // ---- Formatting helpers ----

    private fun formatCpu(caps: DeviceCapabilities): String = buildString {
        val cpu = caps.cpu
        append("=== CPU ===\n")
        append("Architecture: ${cpu.primaryArch}\n")
        append("ABIs: ${cpu.supportedAbis.joinToString(", ")}\n")
        append("Cores: ${cpu.processorCount}\n")
        append("Topology: ${cpu.topology}\n")
        if (cpu.topology.maxFrequencyMhz > 0) {
            append("Max Frequency: ${cpu.topology.maxFrequencyMhz} MHz\n")
        }
        if (cpu.coreFrequenciesMhz.isNotEmpty()) {
            append("Core Frequencies: ${cpu.coreFrequenciesMhz.joinToString(", ")} MHz\n")
        }
        append("\nISA Extensions:\n")
        append("  ARM64:    ${cpu.supportsArm64}\n")
        append("  FP16:     ${cpu.supportsFp16}\n")
        append("  DotProd:  ${cpu.supportsDotProd}\n")
        append("  I8MM:     ${cpu.supportsI8mm}\n")
        append("\nRecommended Threads: ${cpu.recommendedThreads}")
    }

    private fun formatGpu(caps: DeviceCapabilities): String = buildString {
        val gpu = caps.gpu
        append("=== GPU ===\n")
        append("Family: ${gpu.family.displayName}\n")
        append("Tier: ${gpu.tier.displayName}\n")
        if (gpu.estimatedGflops > 0) {
            append("Estimated FP16: ~${gpu.estimatedGflops.toInt()} GFLOPS\n")
        }
        append("\nGraphics APIs:\n")
        append("  Vulkan: ${gpu.vulkanVersion}\n")
        append("  OpenCL: ${if (gpu.openclAvailable) "available" else "not found"}\n")
    }

    private fun formatTpu(caps: DeviceCapabilities): String = buildString {
        val tpu = caps.tpu
        append("=== TPU (Google Tensor) ===\n")
        if (tpu.present) {
            append("Detected: ${tpu.generation.displayName}\n")
            append("Pixel Device: ${tpu.isPixel}\n")
            append("Estimated Performance: ~${tpu.estimatedTops.toInt()} TOPS\n")
            append("Recommended Batch Size: ${tpu.recommendedBatchSize}\n")
            append("Recommended Sequence Length: ${tpu.recommendedSeqLen}\n")
            if (tpu.nnapiDelegateName != null) {
                append("NNAPI Delegate: ${tpu.nnapiDelegateName}\n")
            }
            append("\n${tpu.description}")
        } else {
            append("No Google Tensor TPU detected.\n\n")
            append("TPU is available on Google Pixel devices:\n")
            append("  Pixel 6/6 Pro:  Tensor G1 (~15 TOPS)\n")
            append("  Pixel 7/7 Pro:  Tensor G2 (~22 TOPS)\n")
            append("  Pixel 8/8 Pro:  Tensor G3 (~28-30 TOPS)\n")
            append("  Pixel 9/9 Pro:  Tensor G4 (~37 TOPS)\n")
            append("  Pixel 10:       Tensor G5 (~46 TOPS)")
        }
    }

    private fun formatNpu(caps: DeviceCapabilities): String = buildString {
        val npu = caps.npu
        append("=== NPU / DSP ===\n")
        if (npu.present) {
            append("Type: ${npu.type.displayName}\n")
            append("Details: ${npu.description}\n")
        } else {
            append("No dedicated NPU/DSP detected.\n\n")
            append("NPU is available on:\n")
            append("  Qualcomm Snapdragon: Hexagon DSP/HTP\n")
            append("  Samsung Exynos: Exynos NPU\n")
            append("  MediaTek Dimensity: APU")
        }
    }

    private fun formatMemory(caps: DeviceCapabilities): String = buildString {
        val mem = caps.memory
        append("=== Memory ===\n")
        append("Total RAM: ${mem.totalRamMb} MB\n")
        append("Available RAM: ${mem.availableRamMb} MB\n")
        append("Low Memory: ${mem.lowMemory}\n")
        append("Low Threshold: ${mem.lowMemoryThresholdMb} MB\n")
        append("Java Heap Max: ${mem.javaHeapMaxMb} MB\n")
        append("Large Heap: ${mem.largeHeapMb} MB")
    }

    private fun formatSoc(caps: DeviceCapabilities): String = buildString {
        val soc = caps.soc
        append("=== SoC ===\n")
        append("Chipset: ${soc.chipsetName}\n")
        append("Vendor: ${soc.vendor.displayName}\n")
        append("SoC Model: ${soc.model}\n")
        append("Hardware: ${soc.hardware}\n")
        append("Board: ${soc.board}\n")
        append("Device: ${soc.deviceManufacturer} ${soc.deviceModel}\n")
        append("Android: ${soc.androidVersion} (API ${soc.apiLevel})")
    }

    private fun formatTier(caps: DeviceCapabilities): String = buildString {
        val tier = caps.tier
        append("=== Device Capability Tier ===\n")
        append("Tier: ${tier.tier.displayName}\n")
        append("Score: ${tier.score}/100\n")
        append("Best Accelerator: ${tier.bestAccelerator}\n")
        if (tier.estimatedTotalTops > 0) {
            append("Estimated TOPS: ~${tier.estimatedTotalTops.toInt()}\n")
        }
        append("Max Model Params: ${tier.maxRecommendedModelParams}\n\n")
        append(tier.summary)
    }

    private fun formatAccelerators(caps: DeviceCapabilities): String = buildString {
        append("=== ML Accelerators ===\n\n")

        append("--- TPU ---\n")
        if (caps.tpu.present) {
            append("${caps.tpu.generation.displayName} (~${caps.tpu.estimatedTops.toInt()} TOPS)\n")
        } else {
            append("Not present\n")
        }

        append("\n--- NPU/DSP ---\n")
        if (caps.npu.present) {
            append("${caps.npu.description}\n")
        } else {
            append("Not present\n")
        }

        append("\n--- GPU (compute) ---\n")
        append("${caps.gpu.family.displayName} (${caps.gpu.tier.displayName})")
        if (caps.gpu.estimatedGflops > 0) {
            append(" ~${caps.gpu.estimatedGflops.toInt()} GFLOPS")
        }
        append("\n")
        append("Vulkan: ${caps.gpu.vulkanVersion}\n")
        append("OpenCL: ${if (caps.gpu.openclAvailable) "available" else "not found"}\n")

        append("\n--- NNAPI ---\n")
        append("Version: ${caps.nnapi.version}\n")
        if (caps.nnapi.estimatedDelegates.isNotEmpty()) {
            append("Delegates: ${caps.nnapi.estimatedDelegates.joinToString(", ")}\n")
        }

        append("\n--- Inference Frameworks ---\n")
        append("TFLite: ${if (caps.acceleratorBackends.tfliteAvailable) "yes" else "no"}")
        if (caps.acceleratorBackends.tfliteGpuDelegateAvailable) append(" (GPU delegate)")
        append("\n")
        append("MNN: ${if (caps.acceleratorBackends.mnnAvailable) "yes" else "no"}\n")

        append("\n--- Overall ---\n")
        append("Best accelerator: ${caps.tier.bestAccelerator}\n")
        append("Capability tier: ${caps.tier.tier.displayName} (${caps.tier.score}/100)\n")
        append("Max recommended model: ${caps.tier.maxRecommendedModelParams} params")
    }
}
