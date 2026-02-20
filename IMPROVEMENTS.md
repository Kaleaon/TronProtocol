# Suggested Improvements for TronProtocol

This document outlines suggested improvements for the TronProtocol codebase based on a comprehensive review.

## 1. Critical Architecture & Stability

### **Refactor `TronProtocolService` (High Priority)**
The `TronProtocolService` is currently a monolithic class responsible for:
- Service lifecycle management
- Wake lock acquisition/release
- Initialization of heavy dependencies (RAG, LLM, Plugins)
- Heartbeat loop logic
- Memory consolidation loop logic
- Notification management

**Recommendation:** Decompose this service into smaller, focused components:
- **`ServiceInitializer`**: Handles dependency injection and initialization sequencing.
- **`HeartbeatManager`**: Encapsulates the heartbeat loop logic and state.
- **`ConsolidationScheduler`**: Manages memory consolidation timing and execution (consider `WorkManager`).
- **`NotificationManagerWrapper`**: Handles notification channels and updates.

### **Native Library Dependency Management (Critical)**
The `OnDeviceLLMManager` relies on MNN native libraries (`libmnnllm.so`, etc.) which must be manually built and placed in `jniLibs`. This creates significant friction for new developers and CI pipelines.

**Recommendation:**
- Provide a `download_libs.sh` script or a Gradle task that fetches pre-built binaries for supported architectures (arm64-v8a).
- Alternatively, include a Dockerfile or build script that compiles MNN from source for the target ABI.

## 2. AI & Machine Learning Capabilities

### **Implement Real Embeddings (Critical)**
Currently, `RAGStore` uses a `generateEmbedding` function that produces a "hash-based" embedding (TF-IDF approximation). This is insufficient for semantic search.

**Recommendation:**
- Integrate a real on-device embedding model (e.g., **TensorFlow Lite Universal Sentence Encoder** or a quantized BERT model).
- Create an `EmbeddingProvider` interface to abstract the implementation, allowing for easy swapping of models.

### **Enhance Entity Extraction (High Priority)**
The `EntityExtractor` currently uses regex and heuristic patterns. While fast, it misses many context-dependent entities.

**Recommendation:**
- Integrate **MediaPipe Tasks** or **TFLite NER** (Named Entity Recognition) models for more accurate extraction.
- Support custom entity types relevant to the user's domain.

### **LLM Model Management (Medium Priority)**
`OnDeviceLLMManager` supports model loading but lacks a structured way to manage multiple models.

**Recommendation:**
- Create a `ModelRegistry` or `ModelStore` to track available local models, their metadata (quantization, size), and compatibility.
- Implement a UI or CLI command to download popular compatible models (e.g., Qwen, Gemma) directly.

## 3. Performance & Scalability

### **RAGStore Scalability (High Priority)**
`RAGStore` currently loads all `TextChunk` objects into memory (`chunks` list) on startup. This will cause `OutOfMemoryError` as the knowledge base grows.

**Recommendation:**
- Migrate to **SQLite (Room)** or a dedicated vector database (like **ObjectBox** or **ChromaDB** if compatible with Android).
- Implement pagination for retrieval and lazy loading for content.
- Use memory mapping for the vector index if staying with a custom implementation.

### **Battery Optimization (Medium Priority)**
The service uses a partial wake lock (`PowerManager.PARTIAL_WAKE_LOCK`) to keep the CPU running. This drains battery significantly.

**Recommendation:**
- Evaluate if the heartbeat *must* be continuous. If 30-second intervals are acceptable, use **`WorkManager`** with `PeriodicWorkRequest` (min 15 mins) or `AlarmManager` for exact timing, allowing the device to doze in between.
- If continuous monitoring is required, implement a "low power mode" that reduces heartbeat frequency when the device is stationary or battery is low.

## 4. Security

### **Key Management (High Priority)**
API keys and sensitive configuration are often found in environment variables or properties files.

**Recommendation:**
- Ensure all sensitive keys (if any external APIs are used) are stored in the **Android Keystore System** via `EncryptedSharedPreferences`.
- Implement a "Secret Rotation" mechanism if connecting to cloud services.

### **Plugin Sandboxing (Medium Priority)**
`PluginManager` executes plugins in the same process. A malicious or buggy plugin could crash the entire service.

**Recommendation:**
- For high-risk plugins (e.g., `SandboxedCodeExecutionPlugin`), consider running them in a separate **process** or using a JavaScript engine (like Rhino/Duktape) with strict context limits instead of native code/direct Java calls where possible.

## 5. Code Quality & Tooling

### **Linting & Formatting**
- Enforce **Ktlint** or **Detekt** in the CI pipeline to maintain code style consistency.
- Add unit tests for `RAGStore` retrieval strategies (mocking the embedding generation for deterministic results).

### **Documentation**
- Add KDoc to all public public methods in `Plugin` and `RAGStore`.
- Create a `CONTRIBUTING.md` guide specifically for adding new plugins.
