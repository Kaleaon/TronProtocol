# Implementation Options

Based on the improvements identified in `IMPROVEMENTS.md`, here are three actionable options for immediate implementation.

Please select one option to proceed, or suggest a different combination.

## Option A: "The AI Foundation" (Real Embeddings)
**Goal:** Make the RAG system actually "intelligent" by replacing the hash-based placeholder with real semantic embeddings.
**Tasks:**
1.  Add `tensorflow-lite-support` and `tensorflow-lite` dependencies (if missing/outdated).
2.  Create an `EmbeddingProvider` interface in `com.tronprotocol.app.rag`.
3.  Implement `TFLiteEmbeddingProvider` using a small, efficient model (e.g., a quantized sentence encoder).
4.  Update `RAGStore` to use this provider for `generateEmbedding`.
**Impact:** `RetrievalStrategy.SEMANTIC`, `HYBRID`, and `FRONTIER_AWARE` will start working correctly.

## Option B: "Service Architecture" (Refactoring)
**Goal:** Clean up the monolithic `TronProtocolService` to improve maintainability and testability.
**Tasks:**
1.  Extract heartbeat logic into `com.tronprotocol.app.service.HeartbeatManager`.
2.  Extract initialization logic into `com.tronprotocol.app.service.ServiceInitializer`.
3.  Refactor `TronProtocolService` to delegate to these new components.
**Impact:** The code becomes much easier to read, test, and extend.

## Option C: "Developer Experience" (MNN & Tooling)
**Goal:** Make the project easier to build and run for new developers (and AI agents).
**Tasks:**
1.  Create a `scripts/download_mnn_libs.sh` script to fetch pre-built MNN libraries.
2.  Create a `scripts/download_models.sh` script to fetch a default quantized LLM (e.g., Qwen-1.5B-Int4).
3.  Update `README.md` with clear instructions on how to use these scripts.
**Impact:** Removes the biggest friction point ("missing native libs") and allows the on-device LLM to work out-of-the-box.

---

**To proceed:**
Tell me which option you prefer (e.g., "Go with Option A" or "Implement Option C").
