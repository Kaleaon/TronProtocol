package com.tronprotocol.app.llm;

/**
 * Configuration for an on-device LLM model loaded via MNN.
 *
 * Encapsulates all parameters needed to load and run inference:
 * - Model location and identity
 * - Hardware backend selection (CPU/GPU/NPU)
 * - Inference parameters (temperature, top-p, max tokens)
 * - Memory optimization settings (mmap, quantization)
 *
 * Models should be exported from HuggingFace using MNN's llmexport.py:
 *   python llmexport.py --path Qwen2.5-1.5B-Instruct --export mnn --quant_bit 4
 *
 * The model directory must contain:
 *   llm.mnn          - Compiled model graph
 *   llm.mnn.weight   - Quantized model weights
 *   config.json       - Runtime configuration
 *   llm_config.json   - Model architecture specs
 *   tokenizer.txt     - Token vocabulary
 *
 * @see OnDeviceLLMManager
 * @see <a href="https://github.com/alibaba/MNN">MNN GitHub</a>
 */
public class LLMModelConfig {

    private final String modelId;
    private final String modelName;
    private final String modelPath;
    private final String parameterCount;
    private final String quantization;
    private final int contextWindow;
    private final int maxTokens;
    private final int backend;
    private final int threadCount;
    private final float temperature;
    private final float topP;
    private final boolean useMmap;

    private LLMModelConfig(Builder builder) {
        this.modelId = builder.modelId;
        this.modelName = builder.modelName;
        this.modelPath = builder.modelPath;
        this.parameterCount = builder.parameterCount;
        this.quantization = builder.quantization;
        this.contextWindow = builder.contextWindow;
        this.maxTokens = builder.maxTokens;
        this.backend = builder.backend;
        this.threadCount = builder.threadCount;
        this.temperature = builder.temperature;
        this.topP = builder.topP;
        this.useMmap = builder.useMmap;
    }

    public String getModelId() { return modelId; }
    public String getModelName() { return modelName; }
    public String getModelPath() { return modelPath; }
    public String getParameterCount() { return parameterCount; }
    public String getQuantization() { return quantization; }
    public int getContextWindow() { return contextWindow; }
    public int getMaxTokens() { return maxTokens; }
    public int getBackend() { return backend; }
    public int getThreadCount() { return threadCount; }
    public float getTemperature() { return temperature; }
    public float getTopP() { return topP; }
    public boolean isUseMmap() { return useMmap; }

    /**
     * Human-readable backend name.
     */
    public String getBackendName() {
        switch (backend) {
            case OnDeviceLLMManager.BACKEND_OPENCL: return "opencl";
            case OnDeviceLLMManager.BACKEND_VULKAN: return "vulkan";
            default: return "cpu";
        }
    }

    @Override
    public String toString() {
        return "LLMModelConfig{" +
                "name='" + modelName + '\'' +
                ", params=" + parameterCount +
                ", quant=" + quantization +
                ", backend=" + getBackendName() +
                ", threads=" + threadCount +
                ", maxTokens=" + maxTokens +
                ", mmap=" + useMmap +
                '}';
    }

    /**
     * Builder for LLMModelConfig.
     */
    public static class Builder {
        private String modelId;
        private final String modelName;
        private final String modelPath;
        private String parameterCount = "unknown";
        private String quantization = "Q4";
        private int contextWindow = 4096;
        private int maxTokens = 512;
        private int backend = OnDeviceLLMManager.BACKEND_CPU;
        private int threadCount = 4;
        private float temperature = 0.7f;
        private float topP = 0.9f;
        private boolean useMmap = false;

        /**
         * @param modelName Display name (e.g., "Qwen2.5-1.5B-Instruct")
         * @param modelPath Absolute path to model directory on device
         */
        public Builder(String modelName, String modelPath) {
            this.modelName = modelName;
            this.modelPath = modelPath;
            this.modelId = "mnn_" + modelName.toLowerCase()
                    .replaceAll("[^a-z0-9]", "_")
                    .replaceAll("_+", "_");
        }

        public Builder setModelId(String modelId) {
            this.modelId = modelId;
            return this;
        }

        public Builder setParameterCount(String parameterCount) {
            this.parameterCount = parameterCount;
            return this;
        }

        public Builder setQuantization(String quantization) {
            this.quantization = quantization;
            return this;
        }

        public Builder setContextWindow(int contextWindow) {
            this.contextWindow = contextWindow;
            return this;
        }

        public Builder setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        /**
         * Set the MNN backend.
         * @param backend OnDeviceLLMManager.BACKEND_CPU, BACKEND_OPENCL, or BACKEND_VULKAN
         */
        public Builder setBackend(int backend) {
            this.backend = backend;
            return this;
        }

        public Builder setThreadCount(int threadCount) {
            this.threadCount = Math.max(1, Math.min(threadCount, 8));
            return this;
        }

        public Builder setTemperature(float temperature) {
            this.temperature = Math.max(0.0f, Math.min(temperature, 2.0f));
            return this;
        }

        public Builder setTopP(float topP) {
            this.topP = Math.max(0.0f, Math.min(topP, 1.0f));
            return this;
        }

        /**
         * Enable memory-mapped weights to reduce DRAM usage.
         * Recommended for devices with limited RAM or models > 2GB.
         */
        public Builder setUseMmap(boolean useMmap) {
            this.useMmap = useMmap;
            return this;
        }

        public LLMModelConfig build() {
            return new LLMModelConfig(this);
        }
    }

    // ---- Preset configurations for recommended models ----

    /**
     * Preset for Qwen2.5-1.5B-Instruct with Q4 quantization.
     * Smallest recommended model — runs on 3GB+ RAM devices.
     * Model size: ~1GB on disk, ~1.5GB DRAM.
     */
    public static LLMModelConfig qwen25_1_5b(String modelPath) {
        return new Builder("Qwen2.5-1.5B-Instruct", modelPath)
                .setParameterCount("1.5B")
                .setQuantization("Q4_K_M")
                .setContextWindow(4096)
                .setMaxTokens(512)
                .setThreadCount(4)
                .build();
    }

    /**
     * Preset for Qwen3-1.7B with Q4 quantization.
     * Good balance of quality and speed.
     * Model size: ~1.2GB on disk, ~1.7GB DRAM.
     */
    public static LLMModelConfig qwen3_1_7b(String modelPath) {
        return new Builder("Qwen3-1.7B", modelPath)
                .setParameterCount("1.7B")
                .setQuantization("Q4_K_M")
                .setContextWindow(4096)
                .setMaxTokens(512)
                .setThreadCount(4)
                .build();
    }

    /**
     * Preset for Qwen2.5-3B-Instruct with Q4 quantization.
     * Higher quality, requires 6GB+ RAM.
     * Model size: ~2GB on disk, ~2.5GB DRAM.
     */
    public static LLMModelConfig qwen25_3b(String modelPath) {
        return new Builder("Qwen2.5-3B-Instruct", modelPath)
                .setParameterCount("3B")
                .setQuantization("Q4_K_M")
                .setContextWindow(4096)
                .setMaxTokens(512)
                .setThreadCount(4)
                .setUseMmap(true)
                .build();
    }

    /**
     * Preset for Gemma-2B with Q4 quantization.
     * Google's lightweight model, good for general tasks.
     * Model size: ~1.5GB on disk, ~2GB DRAM.
     */
    public static LLMModelConfig gemma_2b(String modelPath) {
        return new Builder("Gemma-2B", modelPath)
                .setParameterCount("2B")
                .setQuantization("Q4_K_M")
                .setContextWindow(2048)
                .setMaxTokens(256)
                .setThreadCount(4)
                .build();
    }

    /**
     * Preset for DeepSeek-R1-1.5B with Q4 quantization.
     * Reasoning-focused model for analytical tasks.
     * Model size: ~1GB on disk, ~1.5GB DRAM.
     */
    public static LLMModelConfig deepseek_r1_1_5b(String modelPath) {
        return new Builder("DeepSeek-R1-1.5B", modelPath)
                .setParameterCount("1.5B")
                .setQuantization("Q4_K_M")
                .setContextWindow(4096)
                .setMaxTokens(512)
                .setThreadCount(4)
                .build();
    }
}
