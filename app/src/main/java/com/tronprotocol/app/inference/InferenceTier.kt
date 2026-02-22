package com.tronprotocol.app.inference

/**
 * The three-tier hybrid inference architecture.
 *
 * From the TronProtocol Pixel 10 spec:
 * - Tier 1 (Always-On Local): heartbeat, emotional state, drift detection. Sub-100ms.
 * - Tier 2 (On-Demand Local): SLM inference, RAG retrieval, narrative checks. 1–5s.
 * - Tier 3 (Cloud Fallback): Claude API for complex reasoning. Variable latency.
 */
enum class InferenceTier(val label: String, val priority: Int) {
    /** Lightweight local operations: affect ticks, drift checks, kernel verification. */
    LOCAL_ALWAYS_ON("local_always_on", 1),

    /** On-demand local SLM inference via llama.cpp/MNN or Gemini Nano via AICore. */
    LOCAL_ON_DEMAND("local_on_demand", 2),

    /** Cloud fallback via Anthropic Claude API for complex reasoning. */
    CLOUD_FALLBACK("cloud_fallback", 3)
}
