# CLAUDE.md - AI Assistant Guide for TronProtocol

## Project Overview

TronProtocol is an **Android application** for AI-driven cellular device monitoring with background service capabilities. It combines an extensible plugin architecture (63 registered plugins across 8 phases), a RAG (Retrieval-Augmented Generation) memory system, self-modification capabilities, hardware-backed encryption, 3D avatar rendering, on-device LLM inference, and multi-tier inference routing. The app runs a persistent foreground service with heartbeat monitoring and memory consolidation.

**Package**: `com.tronprotocol.app`
**Min SDK**: 24 (Android 7.0) | **Target/Compile SDK**: 34 (Android 14)
**NDK ABI**: `arm64-v8a`
**Languages**: Kotlin (100%)

## Repository Structure

```
TronProtocol/
├── app/
│   ├── build.gradle                    # App-level build config, dependencies, security regression task
│   ├── proguard-rules.pro              # ProGuard obfuscation rules
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml     # 35 permissions, 4 components (activity, service x2, receiver)
│       │   ├── assets/themes/          # Theme JSON files (navy-gold.json)
│       │   ├── java/com/tronprotocol/app/
│       │   │   ├── MainActivity.kt         # App entry point, UI, permission management
│       │   │   ├── TronProtocolService.kt  # Foreground background service
│       │   │   ├── BootReceiver.kt         # Auto-start on device boot
│       │   │   ├── BootStartWorker.kt      # WorkManager boot task
│       │   │   ├── StartupDiagnostics.kt   # Service startup diagnostics
│       │   │   ├── ConversationTranscriptFormatter.kt  # Conversation formatting
│       │   │   ├── ConversationTurn.kt     # Conversation turn data model
│       │   │   ├── plugins/           # 63 registered plugins + safety/agent subsystems
│       │   │   ├── security/          # Encryption & secure storage (5 files)
│       │   │   ├── rag/               # RAG memory system (24 files)
│       │   │   ├── selfmod/           # Self-modification system (6 files)
│       │   │   ├── aimodel/           # AI model training (5 files)
│       │   │   ├── emotion/           # Emotional state & hallucination detection (2 files)
│       │   │   ├── guidance/          # Ethical guidance & Anthropic API client (6 files)
│       │   │   ├── affect/            # 3-layer emotional architecture (10 files)
│       │   │   ├── llm/               # On-device LLM integration (MNN framework)
│       │   │   ├── avatar/            # 3D avatar rendering & perception (16 files)
│       │   │   ├── frontier/          # Frontier dynamics & STLE engine (4 files)
│       │   │   ├── hedonic/           # Hedonic learning with consent gating (4 files)
│       │   │   ├── inference/         # Multi-tier inference routing (3 files)
│       │   │   ├── mindnexus/         # MNX binary format for mind state (5 files)
│       │   │   ├── nct/               # Narrative Continuity Test (3 files)
│       │   │   ├── phylactery/        # Tiered memory persistence (3 files)
│       │   │   ├── drift/             # Identity drift detection (3 files)
│       │   │   ├── wisdom/            # Wisdom logging & reflection (3 files)
│       │   │   ├── sync/              # Background sync workers (1 file)
│       │   │   └── models/            # Data model classes (1 file)
│       │   └── res/                   # Layouts, drawables, menus, mipmaps, values, xml
│       └── test/
│           ├── java/com/tronprotocol/app/  # 42 test files across all modules
│           └── resources/fixtures/rag/     # Test fixture JSON files
├── ktheme-kotlin/                     # Theme engine library module
│   ├── build.gradle                   # Java-library + Kotlin serialization
│   └── src/main/kotlin/com/ktheme/   # ThemeEngine, Theme model, ColorUtils
├── .github/workflows/                 # 7 CI/CD pipeline configs
│   ├── android-common.yml             # Reusable workflow (JDK 17, SDK 34, NDK r26d, MNN build)
│   ├── ci.yml                         # Lockfile integrity + CVE audit + Compile + Tests + Lint
│   ├── build-apk.yml                  # APK builds (main/develop)
│   ├── release.yml                    # GitHub Release on v* tags
│   ├── auto-build.yml                 # Automatic build workflow
│   ├── deploy-playstore.yml           # Google Play Store deployment (AAB)
│   └── dependency-governance.yml      # Weekly dependency update + CVE audit
├── scripts/
│   └── check_direct_vulns.py          # Direct dependency vulnerability checker (OSV)
├── docs/
│   ├── ANDROID_SDK_SETUP.md           # Android SDK setup guide
│   └── dependencies.md               # Dependency documentation
├── research/
│   └── optimize-anything-applicability.md
├── build.gradle                       # Project-level Gradle config (AGP 8.1.0, Kotlin 1.9.10)
├── settings.gradle                    # Modules: :app, :ktheme-kotlin
├── gradle.properties                  # JVM args, AndroidX settings
├── gradle/libs.versions.toml          # Version catalog for dependencies
├── gradlew / gradlew.bat             # Gradle wrapper executables
├── toolneuron.yaml                    # ToolNeuron build config
├── cleverferret.yaml                  # CleverFerret build config
├── build-from-yaml.sh                 # YAML-based build script
└── yaml-build-config.py               # Python YAML build automation
```

## Build System

**Build tool**: Gradle 8.4 with Android Gradle Plugin 8.1.0, Kotlin 1.9.10, and a Gradle version catalog (`gradle/libs.versions.toml`).

**Modules**: `:app` (Android application) and `:ktheme-kotlin` (pure Kotlin/JVM theme library with kotlinx-serialization).

### Essential Commands

```bash
# Build
./gradlew assembleDebug          # Build debug APK
./gradlew assembleRelease        # Build release APK (uses env-based signing or debug keystore fallback)
./gradlew bundleRelease          # Build release AAB (for Play Store)
./gradlew clean                  # Clean build outputs

# Test & Lint
./gradlew testDebugUnitTest      # Run unit tests
./gradlew lintDebug              # Run Android lint
./gradlew securityRegressionTest # Run security regression suite (plugin guardrails)
./gradlew check                  # Full check (includes securityRegressionTest)

# Compile only (fast check)
./gradlew compileDebugSources    # Compile without packaging
```

### Build Output Paths

- Debug APK: `app/build/outputs/apk/debug/*.apk`
- Release APK: `app/build/outputs/apk/release/*.apk`
- Release AAB: `app/build/outputs/bundle/release/*.aab`

### Key Gradle Properties

- JVM heap: 2048m (`-Xmx2048m`)
- Java compatibility: 1.8
- Kotlin JVM target: 1.8
- AndroidX and Jetifier enabled
- Non-transitive R class: disabled
- BuildConfig generation: enabled

### Native Libraries (MNN)

On-device LLM and avatar inference requires native `.so` files in `app/src/main/jniLibs/arm64-v8a/`:
- `libMNN.so`, `libMNN_Express.so`, `libmnnllm.so` - Core LLM inference
- `libmnn_nnr.so`, `libmnn_a2bs.so` - Avatar rendering (Neural Network Rendering, Audio-to-BlendShape)

These are built from MNN source with CMake flags `-DMNN_BUILD_LLM=ON` and optionally `-DMNN_BUILD_NNR=ON`. CI builds them automatically (cached by NDK/MNN version).

### Signing Configuration

Release builds use environment variables for keystore configuration:
- `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
- Falls back to debug keystore when env vars are not set

## Dependencies

Managed via `gradle/libs.versions.toml` version catalog:

| Dependency | Version | Purpose |
|---|---|---|
| `androidx.appcompat` | 1.6.1 | AppCompat support |
| `com.google.android.material` | 1.9.0 | Material Design components |
| `androidx.constraintlayout` | 2.1.4 | Layout system |
| `androidx.work:work-runtime-ktx` | 2.9.1 | WorkManager for background tasks |
| `org.tensorflow:tensorflow-lite` | 2.13.0 | On-device ML inference |
| `org.tensorflow:tensorflow-lite-gpu` | 2.13.0 | GPU-accelerated inference |
| `org.tensorflow:tensorflow-lite-support` | 0.4.4 | TFLite utilities |
| `play-services-mlkit-text-recognition` | 19.0.0 | ML Kit OCR |
| `play-services-base` | 18.2.0 | GMS for neural network acceleration |
| `com.alibaba.android:mnn` | 0.0.8 | On-device LLM/avatar inference (MNN framework) |
| `:ktheme-kotlin` (project) | — | Theme engine with kotlinx-serialization |

Test dependencies: JUnit 4.13.2, Mockito 5.5.0, Robolectric 4.10.3, org.json 20230227, androidx.test:core 1.5.0.

## Architecture

### Entry Points

1. **MainActivity.kt** - Launcher activity. Manages permissions, plugin UI, and starts the service.
2. **TronProtocolService.kt** - Foreground service (`dataSync` type). Runs heartbeat (30s interval) and memory consolidation loops. Returns `START_STICKY` for auto-restart.
3. **BootReceiver.kt** - `BOOT_COMPLETED` receiver. Schedules `BootStartWorker` via WorkManager for deferred service startup after device reboot.

### Android Components (Manifest)

| Component | Type | Purpose |
|---|---|---|
| `MainActivity` | Activity | Main launcher, permission management, plugin UI |
| `TronProtocolService` | Service | Foreground service (dataSync), heartbeat + memory loops |
| `TronNotificationListenerService` | Service | Ambient notification reading (requires `BIND_NOTIFICATION_LISTENER_SERVICE`) |
| `TronAccessibilityService` | Service | Screen content reading (requires `BIND_ACCESSIBILITY_SERVICE`) |
| `BootReceiver` | Receiver | Auto-start on `BOOT_COMPLETED` |

### Module Responsibilities

**plugins/** - Extensible plugin system with **63 registered plugins** across 8 development phases plus core safety/agent subsystems. All plugins implement the `Plugin` interface. Plugins are registered in `PluginRegistry.kt` with startup priority ordering and **capability-based access control** via the `Capability` enum (31 scoped capabilities). `PluginManager` is a singleton orchestrator with OpenClaw-inspired safety layers (`PluginSafetyScanner`, `ToolPolicyEngine`, `RuntimeAutonomyPolicy`, `LaneQueueExecutor`, `SubAgentManager`, `DeviceCapabilitiesManager`).

Plugin phases and registered plugins by startup priority:

| Priority | Plugin | ID | Phase |
|---|---|---|---|
| 10 | PolicyGuardrailPlugin | `policy_guardrail` | Core |
| 12 | PhylacteryPlugin | `phylactery` | Pixel 10 Spec |
| 15 | OnDeviceLLMPlugin | `on_device_llm` | Core |
| 16 | InferenceRouterPlugin | `inference_router` | Pixel 10 Spec |
| 17 | MnnAvatarPlugin | `mnn_avatar` | Core |
| 18 | AvatarPerceptionPlugin | `avatar_perception` | Core |
| 19 | HereticModelPlugin | `heretic_model` | Core |
| 20 | DeviceInfoPlugin | `device_info` | Core |
| 25 | FrontierDynamicsPlugin | `frontier_dynamics` | Core |
| 30 | WebSearchPlugin | `web_search` | Core |
| 40 | CalculatorPlugin | `calculator` | Core |
| 50 | DateTimePlugin | `datetime` | Core |
| 60 | TextAnalysisPlugin | `text_analysis` | Core |
| 70 | FileManagerPlugin | `file_manager` | Core |
| 80 | NotesPlugin | `notes` | Core |
| 90 | TelegramBridgePlugin | `telegram_bridge` | Core |
| 100 | TaskAutomationPlugin | `task_automation` | Core |
| 110 | SandboxedCodeExecutionPlugin | `sandbox_exec` | Core |
| 120 | PersonalizationPlugin | `personalization` | Core |
| 121 | PersonalityImportPlugin | `personality_import` | Core |
| 130 | CommunicationHubPlugin | `communication_hub` | Core |
| 140 | GuidanceRouterPlugin | `guidance_router` | Core |
| 145 | TakensTrainingPlugin | `takens_training` | Core |
| 146 | ContinuityBridgePlugin | `continuity_bridge` | Core |
| 147 | DriftDetectorPlugin | `drift_detector` | Pixel 10 Spec |
| 148 | WisdomLogPlugin | `wisdom_log` | Pixel 10 Spec |
| 149 | HedonicPlugin | `hedonic` | Pixel 10 Spec |
| 150 | NCTPlugin | `nct` | Pixel 10 Spec |
| 160 | NotificationListenerPlugin | `notification_listener` | Phase 2: Perception |
| 165 | ScreenReaderPlugin | `screen_reader` | Phase 2: Perception |
| 170 | SensorFusionPlugin | `sensor_fusion` | Phase 2: Perception |
| 175 | CalendarPlugin | `calendar` | Phase 2: Perception |
| 180 | AppUsagePlugin | `app_usage` | Phase 2: Perception |
| 185 | ClipboardMonitorPlugin | `clipboard_monitor` | Phase 2: Perception |
| 190 | BatteryConnectivityPlugin | `battery_connectivity` | Phase 2: Perception |
| 195 | ContentProviderQueryPlugin | `content_provider_query` | Phase 2: Perception |
| 200 | ProactiveMessagingPlugin | `proactive_messaging` | Phase 4: Communication |
| 205 | SMSSendPlugin | `sms_send` | Phase 4: Communication |
| 210 | EmailPlugin | `email` | Phase 4: Communication |
| 215 | VoiceSynthesisPlugin | `voice_synthesis` | Phase 4: Communication |
| 220 | CommunicationSchedulerPlugin | `communication_scheduler` | Phase 4: Communication |
| 225 | ConversationSummaryPlugin | `conversation_summary` | Phase 4: Communication |
| 230 | ToneAdaptationPlugin | `tone_adaptation` | Phase 4: Communication |
| 235 | MultiPartyConversationPlugin | `multi_party_conversation` | Phase 4: Communication |
| 240 | HttpClientPlugin | `http_client` | Phase 5: Action & Orchestration |
| 245 | IntentAutomationPlugin | `intent_automation` | Phase 5: Action & Orchestration |
| 250 | ContactManagerPlugin | `contact_manager` | Phase 5: Action & Orchestration |
| 255 | ReActPlanningExecutor | `react_planner` | Phase 5: Action & Orchestration |
| 260 | ScheduledActionsPlugin | `scheduled_actions` | Phase 5: Action & Orchestration |
| 265 | WorkflowPersistencePlugin | `workflow_persistence` | Phase 5: Action & Orchestration |
| 270 | APIKeyVaultPlugin | `api_key_vault` | Phase 5: Action & Orchestration |
| 275 | ScriptingRuntimePlugin | `scripting_runtime` | Phase 5: Action & Orchestration |
| 280 | UserFeedbackPlugin | `user_feedback` | Phase 6: Self-Improvement |
| 285 | BenchmarkSuitePlugin | `benchmark_suite` | Phase 6: Self-Improvement |
| 290 | CapabilityDiscoveryPlugin | `capability_discovery` | Phase 6: Self-Improvement |
| 295 | ErrorPatternLearningPlugin | `error_pattern_learning` | Phase 6: Self-Improvement |
| 300 | PromptStrategyEvolutionPlugin | `prompt_strategy_evolution` | Phase 6: Self-Improvement |
| 305 | ABTestingPlugin | `ab_testing` | Phase 6: Self-Improvement |
| 310 | GoalHierarchyPlugin | `goal_hierarchy` | Phase 7: Identity & Coherence |
| 315 | ValueAlignmentPlugin | `value_alignment` | Phase 7: Identity & Coherence |
| 320 | PreferenceCrystallizationPlugin | `preference_crystallization` | Phase 7: Identity & Coherence |
| 325 | IdentityNarrativePlugin | `identity_narrative` | Phase 7: Identity & Coherence |
| 330 | RelationshipModelPlugin | `relationship_model` | Phase 7: Identity & Coherence |
| 335 | MoodDecisionRouterPlugin | `mood_decision_router` | Phase 7: Identity & Coherence |
| 340 | AuditDashboardPlugin | `audit_dashboard` | Phase 8: Safety & Transparency |
| 345 | GranularConsentPlugin | `granular_consent` | Phase 8: Safety & Transparency |
| 350 | ActionExplanationPlugin | `action_explanation` | Phase 8: Safety & Transparency |
| 355 | KillSwitchPlugin | `kill_switch` | Phase 8: Safety & Transparency |
| 360 | PrivacyBudgetPlugin | `privacy_budget` | Phase 8: Safety & Transparency |
| 365 | UserOverrideHistoryPlugin | `user_override_history` | Phase 8: Safety & Transparency |

**security/** - Hardware-backed AES-256-GCM encryption via Android KeyStore (`EncryptionManager`), encrypted key-value storage (`SecureStorage`), comprehensive audit logging (`AuditLogger`), constitutional security directives (`ConstitutionalMemory`), and ethical kernel verification (`EthicalKernelVerifier`).

**rag/** - Retrieval-Augmented Generation memory system inspired by MemRL (arXiv:2601.03192) and MiniRAG (arXiv:2501.06713). 24 files including: `RAGStore` with 7 retrieval strategies (semantic, keyword, hybrid, recency, MemRL, relevance-decay, graph), `TextChunk` with Q-values, `KnowledgeGraph` for entity-aware retrieval, `EntityExtractor`, `EmbeddingQuantizer`, `AutoCompactionManager`, `SessionKeyManager`, `MemoryConsolidationManager` for sleep-like memory optimization, `AutobiographicalTimeline`, `BeliefVersioningManager`, `ContinuitySnapshotCodec`, `EncryptedCloudSyncManager`, `EpisodicReplayBuffer`, `IntentionalForgettingManager`, `MemoryImportanceReassessor`, `NeuralTemporalScoringEngine`, `SleepCycleOptimizer`, `SleepCycleTakensTrainer`, and retrieval telemetry (`RetrievalTelemetryAnalytics`, `RetrievalTelemetrySink`, `LocalJsonlRetrievalMetricsSink`).

**selfmod/** - Self-modification system with 6 files. `CodeModificationManager` handles self-reflection, code modification proposals, sandboxed validation, and rollback. Supporting models: `CodeModification`, `ModificationAuditRecord`, `ModificationStatus`, `ReflectionResult`, `ValidationResult`.

**aimodel/** - AI model training with 5 files. `ModelTrainingManager` for AI model creation and evolution, `ImplicitLearningManager` for implicit learning, `PixelTpuTrainingManager` for Pixel TPU-accelerated training, `TakensEmbeddingTransformer` for Takens embedding transformations, and `TrainedModel` data class.

**emotion/** - `EmotionalStateManager` for emotional state tracking and `HallucinationDetector` for hallucination detection.

**guidance/** - Ethical guidance with 6 files: `GuidanceOrchestrator` (coordination), `EthicalKernelValidator`, `DecisionRouter`, `AnthropicApiClient` for Claude API integration, `ModelFailoverManager` for resilient API access, and `ConstitutionalValuesEngine`.

**affect/** - 3-layer emotional architecture with 10 files: `AffectEngine` (core computation), `AffectOrchestrator` (coordination), `AffectState`/`AffectDimension` (dimensional model), `AffectInput`, `AffectLogEntry`, `ImmutableAffectLog` (emotion history), `MotorNoise` (output noise injection), `ExpressionDriver`/`ExpressionOutput` (expression output).

**llm/** - On-device LLM integration via Alibaba's MNN framework. `OnDeviceLLMManager` handles model loading, inference, and benchmarking. `LLMModelConfig` for model configuration, `ModelRepository`/`ModelCatalog` for model management, `ModelDownloadManager` for downloading models, `ModelIntegrityVerifier` for integrity checks, `HereticModelManager` for alternative model support. Supports Qwen, Gemma, DeepSeek models up to ~4B parameters with Q4 quantization.

**avatar/** - 3D avatar rendering and perception system with 16 files. Built on MNN TaoAvatar (arXiv:2503.17032). Includes `NnrRenderEngine` (Neural Network Rendering / 3D Gaussian Splatting), `A2BSEngine` (Audio-to-BlendShape animation), `ASREngine` (speech recognition), `TTSEngine` (text-to-speech), `DiffusionEngine` (image diffusion), `MnnVisionEngine` (vision inference), `FaceDetectionEngine`, `FaceLandmarkEngine`, `PoseEstimationEngine`, `ExpressionRecognizer`, `AvatarAffectBridge` (emotion-to-avatar bridge), `AvatarSessionManager`, `AvatarResourceManager`, `AvatarModelCatalog`, `AvatarConfig`, `CustomAvatarManager`.

**frontier/** - Frontier dynamics system with 4 files: `FrontierDynamicsManager`, `FrontierDynamicsPlugin`, `STLEEngine` (Spatio-Temporal Latent Exploration), and `AccessibilityResult`.

**hedonic/** - Hedonic learning system with consent gating. 4 files: `HedonicLearning`, `HedonicProcessor`, `ConsentGate`, `BodyZone`.

**inference/** - Multi-tier inference routing with 3 files: `InferenceRouter` (routes between on-device and cloud), `InferenceResult`, `InferenceTier`.

**mindnexus/** - Binary format for mind state serialization. 5 files: `MindNexusManager`, `MnxCodec`, `MnxBinaryStream`, `MnxFormat`, `MnxSections`.

**nct/** - Narrative Continuity Test system with 3 files: `NarrativeContinuityTest`, `NCTAxis`, `NCTResult`.

**phylactery/** - Tiered memory persistence system with 3 files: `ContinuumMemorySystem`, `MemoryTier`, `PhylacteryEntry`.

**drift/** - Identity drift detection with 3 files: `DriftDetector`, `DriftScore`, `ImmutableCommitLog`.

**wisdom/** - Wisdom logging and reflection with 3 files: `WisdomLog`, `WisdomEntry`, `ReflectionCycle`.

**sync/** - Background synchronization: `PhylacterySyncWorker`.

**models/** - Shared data models: `AIModel.kt`.

### Capability System

Plugins declare required capabilities via the `Capability` enum (35 values). The `PluginRegistry` maps default capabilities per plugin, and `RuntimeAutonomyPolicy` + `ToolPolicyEngine` enforce access control at runtime.

Capability categories:
- **Filesystem**: `FILESYSTEM_READ`, `FILESYSTEM_WRITE`
- **Network**: `NETWORK_OUTBOUND`, `HTTP_REQUEST`
- **Contacts/SMS**: `CONTACTS_READ`, `CONTACTS_WRITE`, `SMS_SEND`, `SMS_READ`
- **Memory**: `MEMORY_READ`, `MEMORY_WRITE`
- **Model**: `MODEL_EXECUTION`
- **Device**: `DEVICE_INFO_READ`, `SENSOR_READ`, `BATTERY_READ`
- **Perception**: `NOTIFICATION_READ`, `SCREEN_READ`, `CLIPBOARD_READ`, `APP_USAGE_READ`, `CONTENT_PROVIDER_READ`, `CALENDAR_READ`
- **Communication**: `EMAIL_SEND`, `VOICE_OUTPUT`, `PROACTIVE_MESSAGE`
- **Automation**: `TASK_AUTOMATION`, `CODE_EXECUTION`, `SCRIPT_EXECUTE`, `SCHEDULED_ACTION`, `INTENT_FIRE`, `ALARM_MANAGE`, `GOAL_MANAGE`
- **Avatar**: `AVATAR_RENDER`, `AVATAR_ANIMATE`, `CAMERA_ACCESS`, `AUDIO_CAPTURE`, `IMAGE_GENERATION`

### Design Patterns

- **Singleton**: PluginManager, EncryptionManager
- **Interface-based plugins**: All plugins implement the `Plugin` interface with capability declarations
- **Factory pattern**: `PluginRegistry` uses factory lambdas for plugin instantiation
- **Priority-ordered initialization**: Plugins start in priority order (lower number = higher priority)
- **Capability-based security**: Plugins must declare capabilities; `ToolPolicyEngine` enforces at runtime
- **Observer/Diagnostics**: `StartupDiagnostics` records startup milestones
- **Phase-based development**: Plugins organized into 8 development phases

## CI/CD Pipeline

All workflows use a shared **android-common.yml** reusable workflow that sets up JDK 17 (Temurin), Android SDK 34, build-tools 34.0.0, NDK r26d, MNN native library build (cached), and Gradle caching.

| Workflow | Trigger | Jobs |
|---|---|---|
| `ci.yml` | Push, PR | Lockfile Integrity + CVE Audit -> Compile -> Unit Tests + Lint |
| `build-apk.yml` | Push to main/develop, manual | Debug APK + Release APK |
| `release.yml` | Push `v*` tag | Build release APK -> GitHub Release |
| `auto-build.yml` | Automatic | Automated build pipeline |
| `deploy-playstore.yml` | Manual (workflow_dispatch) | Build signed AAB -> Deploy to Google Play (internal/alpha/beta/production tracks) |
| `dependency-governance.yml` | Weekly (Monday 06:00 UTC), manual | Outdated dependency report + CVE audit via OSV |

The CI pipeline runs: lockfile integrity check and CVE vulnerability gate first, then compile (depends on both), then unit tests and lint in parallel (both depend on compile).

### Security in CI

- **Lockfile integrity**: Regenerates lockfiles and verifies they match committed versions
- **CVE audit**: Runs `scripts/check_direct_vulns.py` against OSV database for known vulnerabilities
- **Security regression tests**: Dedicated Gradle task (`securityRegressionTest`) runs plugin guardrail tests, included in `check` task

## Code Conventions

### Style

- **Kotlin**: Idiomatic Kotlin throughout. CamelCase for classes/methods, UPPER_SNAKE_CASE for constants. KDoc on public APIs. Uses data classes, object singletons, sealed classes, enum classes, and lambda factories.

### Package Structure

All source lives under `com.tronprotocol.app` with feature-based subpackages:
```
com.tronprotocol.app
├── plugins/       # Plugin interface + 63 implementations + manager + registry + safety/agent
├── security/      # Encryption, secure storage, audit logging, constitutional memory
├── rag/           # RAG memory system (24 files)
├── selfmod/       # Self-modification (6 files)
├── aimodel/       # AI model training (5 files)
├── emotion/       # Emotional state management
├── guidance/      # Ethical guidance system (6 files)
├── affect/        # 3-layer emotional architecture (10 files)
├── llm/           # On-device LLM integration (MNN)
├── avatar/        # 3D avatar rendering & perception (16 files)
├── frontier/      # Frontier dynamics & STLE engine
├── hedonic/       # Hedonic learning with consent gating
├── inference/     # Multi-tier inference routing
├── mindnexus/     # MNX binary format for mind state
├── nct/           # Narrative Continuity Test
├── phylactery/    # Tiered memory persistence
├── drift/         # Identity drift detection
├── wisdom/        # Wisdom logging & reflection
├── sync/          # Background sync workers
└── models/        # Data model classes
```

### Key Interfaces

New plugins must implement `com.tronprotocol.app.plugins.Plugin`:
```kotlin
interface Plugin {
    val id: String
    val name: String
    val description: String
    var isEnabled: Boolean
    fun requiredCapabilities(): Set<Capability> = emptySet()
    fun execute(input: String): PluginResult
    fun initialize(context: Context)
    fun destroy()
}
```

Then register in `PluginRegistry.kt` with an ID, class, default enabled state, priority, default capabilities set, and factory lambda.

## Testing

42 test files in `app/src/test/java/com/tronprotocol/app/` organized by module:

**Core tests:**
- `StartupDiagnosticsTest.kt` - Startup diagnostics milestones
- `ConversationTranscriptFormatterTest.kt` - Conversation formatting

**Plugin tests:**
- `plugins/CalculatorPluginTest.kt` - Calculator operations
- `plugins/TextAnalysisPluginTest.kt` - Text analysis
- `plugins/DateTimePluginTest.kt` - DateTime operations
- `plugins/PluginResultTest.kt` - PluginResult model
- `plugins/PluginCapabilityEnforcementTest.kt` - Capability enforcement
- `plugins/RuntimeAutonomyPolicyTest.kt` - Autonomy policy
- `plugins/DeviceCapabilitiesManagerTest.kt` - Device capabilities

**Security regression tests** (`plugins/security/`):
- `FileManagerSecurityRegressionTest.kt` - Path traversal prevention
- `OutboundGuardrailsSecurityTest.kt` - Outbound data guardrails
- `PolicyDenialObservabilityTest.kt` - Policy denial logging
- `SecurityMaliciousPayloadFixtures.kt` - Malicious input test fixtures

**RAG tests:**
- `rag/TextChunkTest.kt` - TextChunk and MemRL Q-value learning
- `rag/RetrievalResultTest.kt` - RetrievalResult model
- `rag/ContinuitySnapshotCodecTest.kt` - Snapshot serialization
- `rag/NeuralTemporalScoringEngineTest.kt` - Temporal scoring
- `rag/RAGStoreTelemetryTest.kt` - Telemetry metrics
- `rag/SleepCycleOptimizerTest.kt` - Sleep cycle optimization
- `rag/SleepCycleTakensTrainerTest.kt` - Takens training

**Module tests:**
- `affect/AffectStateTest.kt` - Affect state model
- `avatar/AvatarConfigTest.kt`, `AvatarModelCatalogTest.kt`, `AvatarAffectBridgeTest.kt`, `ExpressionRecognizerTest.kt` - Avatar system
- `drift/DriftScoreTest.kt` - Drift scoring
- `emotion/HallucinationDetectorTest.kt` - Hallucination detection
- `frontier/AccessibilityResultTest.kt`, `STLEEngineTest.kt` - Frontier dynamics
- `hedonic/BodyZoneTest.kt`, `ConsentGateTest.kt` - Hedonic system
- `inference/InferenceResultTest.kt` - Inference routing
- `llm/LLMModelConfigTest.kt`, `ModelCatalogTest.kt`, `ModelDownloadManagerTest.kt`, `ModelIntegrityVerifierTest.kt`, `ModelRepositoryTest.kt`, `OnDeviceLLMManagerTest.kt` - LLM system
- `nct/NCTAxisTest.kt` - Narrative Continuity Test
- `phylactery/MemoryTierTest.kt`, `PhylacteryEntryTest.kt` - Phylactery system
- `selfmod/CodeModificationManagerTest.kt` - Self-modification
- `wisdom/WisdomEntryTest.kt` - Wisdom logging

**Test resources:**
- `app/src/test/resources/fixtures/rag/continuity_snapshot_v1.json`
- `app/src/test/resources/fixtures/rag/continuity_snapshot_corrupted.json`

Run tests:
- Unit tests: `./gradlew testDebugUnitTest`
- Security regression: `./gradlew securityRegressionTest`
- Lint: `./gradlew lintDebug`
- Full check: `./gradlew check`
- Instrumentation tests go in `app/src/androidTest/java/com/tronprotocol/app/` (none yet)

## Android Permissions

The app declares **35 permissions** across several categories:

| Category | Permissions |
|---|---|
| Phone | `READ_PHONE_STATE`, `CALL_PHONE`, `READ_CALL_LOG`, `WRITE_CALL_LOG`, `ANSWER_PHONE_CALLS` |
| SMS | `SEND_SMS`, `RECEIVE_SMS`, `READ_SMS`, `RECEIVE_MMS` |
| Contacts | `READ_CONTACTS`, `WRITE_CONTACTS`, `GET_ACCOUNTS` |
| Location | `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` |
| Network | `INTERNET`, `ACCESS_NETWORK_STATE` |
| Storage (legacy) | `READ_EXTERNAL_STORAGE` (max SDK 32), `WRITE_EXTERNAL_STORAGE` (max SDK 32), `MANAGE_EXTERNAL_STORAGE` |
| Media (Android 13+) | `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO` |
| Calendar | `READ_CALENDAR` |
| Alarms | `SET_ALARM`, `SCHEDULE_EXACT_ALARM` |
| Camera | `CAMERA` (optional hardware feature) |
| Sensors/Audio | `BODY_SENSORS`, `RECORD_AUDIO` |
| System | `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `WAKE_LOCK`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `RECEIVE_BOOT_COMPLETED`, `POST_NOTIFICATIONS` |
| Special | `PACKAGE_USAGE_STATS` (protected) |

Any changes to permissions should be reflected in `MainActivity.kt` which handles runtime permission requests.

## Files to Avoid Modifying

- `gradle/wrapper/` - Gradle wrapper (change version in `gradle-wrapper.properties` only)
- `gradle/libs.versions.toml` - Version catalog (modify carefully, affects all modules)
- `*.jks`, `*.keystore` - Signing keys (never commit)
- `local.properties` - Local SDK paths (gitignored)
- `google-services.json` - API keys (gitignored)

## Common Development Tasks

### Adding a New Plugin

1. Create a Kotlin class implementing `Plugin` in `app/src/main/java/com/tronprotocol/app/plugins/`
2. Implement all interface members (`id`, `name`, `description`, `isEnabled`, `execute`, `initialize`, `destroy`)
3. Optionally override `requiredCapabilities()` for dynamic capability sets
4. Return a `PluginResult` from `execute()` using `PluginResult.success()` or `PluginResult.error()`
5. Register in `PluginRegistry.kt` with: unique ID, class, default enabled state, startup priority, default `Capability` set, and factory lambda
6. Choose an appropriate phase and priority number (see plugin table above for ranges)
7. Add unit tests in `app/src/test/java/com/tronprotocol/app/plugins/`

### Adding a New Module

1. Create a new subpackage under `app/src/main/java/com/tronprotocol/app/`
2. If it needs a plugin facade, create a plugin class in `plugins/` and register it
3. Add unit tests in `app/src/test/java/com/tronprotocol/app/<module>/`

### Building from YAML Config

```bash
./build-from-yaml.sh                                    # Interactive
python3 yaml-build-config.py --yaml toolneuron.yaml --action build  # Direct
```

### Release Process

1. Ensure all CI checks pass on `main`
2. Tag the commit: `git tag v1.x.x`
3. Push the tag: `git push origin v1.x.x`
4. The `release.yml` workflow builds a release APK and publishes a GitHub Release automatically

### Play Store Deployment

1. Ensure required secrets are configured: `PLAY_CONFIG_JSON`, `PLAY_PACKAGE_NAME`, `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`
2. Trigger `deploy-playstore.yml` manually via workflow_dispatch
3. Select target track: internal, alpha, beta, or production
4. Optionally provide a release notes directory
