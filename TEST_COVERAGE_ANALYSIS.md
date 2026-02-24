# Test Coverage Analysis — TronProtocol

**Date**: 2026-02-24
**Source files**: 200 | **Test files**: 43 | **File-level coverage**: ~21.5%
**Estimated tests**: ~483 across all test files

---

## Current Coverage Summary

### What IS Tested

| Package | Files Tested | Notable Tests |
|---|---|---|
| `plugins/` | Calculator, DateTime, TextAnalysis, PluginResult, DeviceCapabilities, RuntimeAutonomyPolicy | 54 calculator tests, 24 text analysis tests |
| `plugins/security/` | FileManager path traversal, outbound guardrails, policy denial observability | Security regression tests with malicious payloads |
| `rag/` | TextChunk, RetrievalResult, ContinuitySnapshotCodec, NeuralTemporalScoringEngine, SleepCycleOptimizer, SleepCycleTakensTrainer, RAGStore telemetry | 31 TextChunk MemRL tests, 18 SleepCycleOptimizer tests |
| `llm/` | ModelCatalog, ModelDownloadManager, ModelIntegrityVerifier, ModelRepository, LLMModelConfig, OnDeviceLLMManager | 19 catalog tests, 8 download progress tests |
| `affect/` | AffectState | 12 state/serialization tests |
| `avatar/` | AvatarAffectBridge, AvatarConfig, AvatarModelCatalog, ExpressionRecognizer | 12 affect-to-avatar mapping tests |
| `frontier/` | AccessibilityResult, STLEEngine | 25 STLE ML tests, 14 accessibility tests |
| `emotion/` | HallucinationDetector | 3 tests (Mockito-based) |
| `selfmod/` | CodeModificationManager | 3 tests (canary/rollback) |
| `hedonic/` | BodyZone, ConsentGate | 9 zone tests, 12 consent gate tests |
| Others | DriftScore, InferenceResult, NCTAxis, MemoryTier, PhylacteryEntry, WisdomEntry, ConversationTranscriptFormatter, StartupDiagnostics | Data model and serialization coverage |

### What is NOT Tested

**200 source files − ~35 with direct tests = ~165 files with zero test coverage.**

---

## Priority 1 — Critical: Security & Encryption (0% tested)

The entire `security/` package has **no tests**:

| File | Risk | Recommendation |
|---|---|---|
| `EncryptionManager.kt` | AES-256-GCM encryption; incorrect implementation = data loss or exposure | Test encrypt/decrypt round-trip, key generation, IV uniqueness, tamper detection (GCM tag), error on corrupted ciphertext |
| `SecureStorage.kt` | Encrypted key-value storage; data integrity depends on it | Test put/get round-trip, overwrite behavior, missing key returns null, storage isolation |
| `AuditLogger.kt` | Security audit trail; untested = unverifiable compliance | Test log entry creation, log immutability, log retrieval/filtering, capacity limits |
| `ConstitutionalMemory.kt` | Core safety directives; untested = bypasses possible | Test directive loading, directive validation, tamper resistance |
| `EthicalKernelVerifier.kt` | Safety verification; failure here is a safety gap | Test verification pass/fail paths, invalid input handling |

**Why critical**: These components protect user data and enforce safety invariants. Bugs here could lead to data exposure or safety bypasses. Security code should have the highest test coverage in any project.

---

## Priority 2 — High: Core Service Layer (0% tested)

| File | Risk | Recommendation |
|---|---|---|
| `TronProtocolService.kt` | Foreground service: heartbeat loop, memory consolidation, START_STICKY | Test service lifecycle (create/start/stop/destroy), heartbeat interval enforcement, notification channel creation, restart behavior |
| `MainActivity.kt` | Permission management, plugin UI orchestration | Test permission request flow, plugin list rendering, service start/stop from UI |
| `BootReceiver.kt` | Auto-start on boot | Test intent filtering, WorkManager scheduling |
| `BootStartWorker.kt` | WorkManager boot task | Test doWork() success/failure paths |

**Why high priority**: These are the app's entry points. If the service doesn't start, nothing works. Robolectric tests can validate lifecycle and intent handling.

---

## Priority 3 — High: Plugin Manager & Registry (minimally tested)

The `PluginManager` and `PluginRegistry` are tested only indirectly via capability enforcement (2 tests) and policy denial (2 tests). They need direct tests for:

| Area | What to Test |
|---|---|
| Plugin registration | Duplicate ID rejection, priority ordering, factory lambda invocation |
| Plugin lifecycle | Initialize in priority order, destroy in reverse order, error isolation (one plugin crash shouldn't kill others) |
| Plugin execution | Input routing, disabled plugin rejection, error result propagation |
| PluginSafetyScanner | Scan results for safe/unsafe plugins, input sanitization |
| ToolPolicyEngine | Rule evaluation, rule precedence, default-deny behavior |

Additionally, **~60 plugins have zero tests**. The highest-risk untested plugins:

| Plugin | Risk Reason |
|---|---|
| `SandboxedCodeExecutionPlugin` | Executes code; incorrect sandboxing = arbitrary code execution |
| `FileManagerPlugin` | Only path-traversal tested; read/write/delete operations untested |
| `TelegramBridgePlugin` | External communication; failure = message loss or data leak |
| `WebSearchPlugin` | Only host-blocking tested; actual search logic untested |
| `SMSSendPlugin` | Sends SMS; incorrect behavior = privacy violation |
| `EmailPlugin` | Sends email; same concerns |
| `ContactManagerPlugin` | Accesses contacts; privacy-sensitive |
| `ClipboardMonitorPlugin` | Monitors clipboard; privacy-sensitive |
| `HttpClientPlugin` | Makes HTTP requests; SSRF risk |
| `KillSwitchPlugin` | Emergency shutdown; must work correctly |
| `PolicyGuardrailPlugin` | Safety guardrails; highest startup priority |
| `GranularConsentPlugin` | Consent management; compliance-critical |
| `PrivacyBudgetPlugin` | Privacy tracking; compliance-critical |

---

## Priority 4 — High: RAG Memory System (partially tested)

The RAG system has good coverage on data models but poor coverage on core logic:

| File | Status | Recommendation |
|---|---|---|
| `RAGStore.kt` | Only telemetry tested | Test all 7 retrieval strategies (semantic, keyword, hybrid, recency, MemRL, relevance-decay, graph), addMemory, deleteMemory, capacity limits, concurrent access |
| `KnowledgeGraph.kt` | Untested | Test entity addition, edge creation, graph traversal, entity-aware retrieval, cycle handling |
| `EntityExtractor.kt` | Untested | Test entity extraction from various text types, edge cases (empty text, special characters) |
| `EmbeddingQuantizer.kt` | Untested | Test quantization/dequantization round-trip, precision loss bounds, edge dimensions |
| `AutoCompactionManager.kt` | Untested | Test compaction triggers, compaction correctness, data preservation |
| `MemoryConsolidationManager.kt` | Untested | Test consolidation cycles, memory promotion/demotion, sleep-like optimization |
| `SessionKeyManager.kt` | Untested | Test key generation, key rotation, session isolation |
| `IntentionalForgettingManager.kt` | Untested | Test forgetting triggers, forgetting completeness, protected memory preservation |
| `BeliefVersioningManager.kt` | Untested | Test belief creation, version tracking, conflict resolution |
| `EpisodicReplayBuffer.kt` | Untested | Test buffer operations, replay sampling, capacity limits |
| `MemoryImportanceReassessor.kt` | Untested | Test reassessment logic, score changes over time |

---

## Priority 5 — Medium: Guidance & Ethics System (0% tested)

| File | Risk | Recommendation |
|---|---|---|
| `GuidanceOrchestrator.kt` | Central orchestrator for ethical decisions | Test decision routing, guidance generation, edge-case inputs |
| `EthicalKernelValidator.kt` | Validates actions against ethical framework | Test pass/fail validation, boundary actions |
| `DecisionRouter.kt` | Routes decisions to appropriate handler | Test routing logic, fallback behavior |
| `AnthropicApiClient.kt` | Claude API integration | Test request building, response parsing, error handling, timeout behavior, rate limiting |
| `ModelFailoverManager.kt` | API failover for resilience | Test failover trigger conditions, failover sequence, recovery |
| `ConstitutionalValuesEngine.kt` | Core values enforcement | Test value lookup, conflict resolution |

---

## Priority 6 — Medium: Affect Engine & Emotion (mostly untested)

Only `AffectState` (data model) is tested. The computational core is not:

| File | Recommendation |
|---|---|
| `AffectEngine.kt` | Test affect computation, input processing, state transitions, dimension updates |
| `AffectOrchestrator.kt` | Test coordination between engine, log, expression output |
| `ImmutableAffectLog.kt` | Test log append-only behavior, capacity, retrieval by time range |
| `MotorNoise.kt` | Test noise injection bounds, distribution properties, determinism with seed |
| `ExpressionDriver.kt` | Test expression output generation from affect state |
| `EmotionalStateManager.kt` | Test state tracking, state transitions, persistence |

---

## Priority 7 — Medium: Self-Modification System (minimally tested)

`CodeModificationManager` has 3 tests (canary/rollback) but the data models and detailed logic are untested:

| File | Recommendation |
|---|---|
| `CodeModification.kt` | Test data model creation, validation |
| `ModificationAuditRecord.kt` | Test audit record completeness, serialization |
| `ModificationStatus.kt` | Test status transitions |
| `ReflectionResult.kt` | Test result creation, scoring |
| `ValidationResult.kt` | Test validation pass/fail states |

---

## Priority 8 — Medium: MindNexus System (0% tested)

The entire `mindnexus/` package is untested:

| File | Recommendation |
|---|---|
| `MindNexusManager.kt` | Test initialization, memory operations |
| `MnxCodec.kt` | Test encode/decode round-trip, format compatibility |
| `MnxBinaryStream.kt` | Test binary read/write operations, endianness, boundary conditions |
| `MnxFormat.kt` | Test format validation, version checking |
| `MnxSections.kt` | Test section creation, section ordering |

---

## Priority 9 — Lower: Avatar System (partially tested)

4 of 16 avatar files are tested. Key gaps:

| File | Recommendation |
|---|---|
| `AvatarSessionManager.kt` | Test session lifecycle, concurrent sessions |
| `AvatarResourceManager.kt` | Test resource loading, caching, cleanup |
| `NnrRenderEngine.kt` | Test render pipeline initialization, frame generation |
| `FaceDetectionEngine.kt` | Test detection with mock images, no-face handling |
| `TTSEngine.kt` | Test synthesis requests, audio format, error paths |
| `DiffusionEngine.kt` | Test inference pipeline, input validation |

---

## Priority 10 — Lower: Remaining Untested Packages

| Package/File | Recommendation |
|---|---|
| `aimodel/ModelTrainingManager.kt` | Test training lifecycle, model creation, knowledge extraction |
| `aimodel/TakensEmbeddingTransformer.kt` | Test embedding transformation, dimension validation |
| `aimodel/ImplicitLearningManager.kt` | Test learning triggers, model updates |
| `inference/InferenceRouter.kt` | Test routing between local/cloud, tier selection |
| `drift/DriftDetector.kt` | Test drift detection algorithm, threshold calibration |
| `drift/ImmutableCommitLog.kt` | Test append-only behavior, log integrity |
| `wisdom/WisdomLog.kt` | Test log operations, capacity |
| `wisdom/ReflectionCycle.kt` | Test cycle execution, output generation |
| `phylactery/ContinuumMemorySystem.kt` | Test memory persistence, tier transitions |
| `sync/PhylacterySyncWorker.kt` | Test sync execution, conflict resolution |

---

## Structural Testing Gaps

### 1. No Integration Tests
All 43 test files are unit tests. There are no tests verifying that components work together:
- Plugin → RAGStore interaction
- Service → PluginManager lifecycle
- AffectEngine → ExpressionDriver pipeline
- Guidance → AnthropicApiClient → ModelFailover chain

### 2. No Instrumentation Tests
The `app/src/androidTest/` directory is empty. Android-specific behavior that Robolectric cannot fully simulate (permissions, foreground services, real sensor input) is untested.

### 3. No Error/Recovery Tests for Managers
Manager-level classes (`PluginManager`, `OnDeviceLLMManager`, `MemoryConsolidationManager`) lack tests for:
- Crash recovery
- Concurrent access
- Resource exhaustion (OOM, disk full)
- Timeout behavior

### 4. No Performance/Stress Tests
For a device-monitoring app running a persistent foreground service, there are no tests for:
- Memory leak detection under sustained operation
- Battery impact of heartbeat loops
- RAG retrieval latency under large memory stores
- Plugin execution time budgets

---

## Recommended Test Implementation Order

1. **Security package** — EncryptionManager and SecureStorage first (data protection)
2. **Safety-critical plugins** — SandboxedCodeExecutionPlugin, PolicyGuardrailPlugin, KillSwitchPlugin
3. **RAGStore core retrieval** — The 7 retrieval strategies are the memory backbone
4. **Service lifecycle** — TronProtocolService start/stop/restart
5. **PluginManager lifecycle** — Registration, priority init, error isolation
6. **Guidance system** — EthicalKernelValidator, GuidanceOrchestrator
7. **Privacy-sensitive plugins** — SMS, Email, Contacts, Clipboard
8. **AffectEngine computation** — Core emotional processing
9. **MindNexus codec** — Binary format correctness
10. **Integration tests** — Cross-module interactions
