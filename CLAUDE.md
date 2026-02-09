# CLAUDE.md - AI Assistant Guide for TronProtocol

## Project Overview

TronProtocol is an **Android application** for AI-driven cellular device monitoring with background service capabilities. It combines an extensible plugin architecture, a RAG (Retrieval-Augmented Generation) memory system, self-modification capabilities, and hardware-backed encryption. The app runs a persistent foreground service with heartbeat monitoring and memory consolidation.

**Package**: `com.tronprotocol.app`
**Min SDK**: 24 (Android 7.0) | **Target/Compile SDK**: 34 (Android 14)
**Languages**: Java (~85%), Kotlin (~15%)

## Repository Structure

```
TronProtocol/
├── app/
│   ├── build.gradle                    # App-level build config and dependencies
│   ├── proguard-rules.pro              # ProGuard obfuscation rules
│   └── src/main/
│       ├── AndroidManifest.xml         # Permissions, components, metadata
│       ├── java/com/tronprotocol/app/
│       │   ├── MainActivity.kt         # App entry point, UI, permission mgmt
│       │   ├── TronProtocolService.java # Foreground background service
│       │   ├── BootReceiver.kt         # Auto-start on device boot
│       │   ├── BootStartWorker.kt      # WorkManager boot task
│       │   ├── StartupDiagnostics.kt   # Service startup diagnostics
│       │   ├── plugins/                # 14 plugin implementations
│       │   ├── security/               # Encryption & secure storage
│       │   ├── rag/                    # RAG memory system (MemRL-inspired)
│       │   ├── selfmod/               # Self-modification system
│       │   ├── aimodel/               # AI model training
│       │   ├── emotion/               # Emotional state & hallucination detection
│       │   ├── guidance/              # Ethical guidance & Anthropic API client
│       │   └── models/                # Data model classes
│       └── res/                        # Android resources (layouts, strings, etc.)
├── .github/workflows/                  # CI/CD pipeline configs
│   ├── android-common.yml              # Reusable workflow (JDK 17, SDK 34)
│   ├── ci.yml                          # Compile, test, lint on push/PR
│   ├── build-apk.yml                   # APK builds (main/develop)
│   └── release.yml                     # GitHub Release on v* tags
├── build.gradle                        # Project-level Gradle config
├── settings.gradle                     # Module inclusion (`:app`)
├── gradle.properties                   # JVM args, AndroidX settings
├── gradlew / gradlew.bat              # Gradle wrapper executables
├── toolneuron.yaml                     # ToolNeuron build config
├── cleverferret.yaml                   # CleverFerret build config
├── build-from-yaml.sh                  # YAML-based build script
└── yaml-build-config.py                # Python YAML build automation
```

## Build System

**Build tool**: Gradle 8.1.0 with Android Gradle Plugin 8.1.0 and Kotlin 1.9.10.

### Essential Commands

```bash
# Build
./gradlew assembleDebug          # Build debug APK
./gradlew assembleRelease        # Build release APK
./gradlew clean                  # Clean build outputs

# Test & Lint
./gradlew testDebugUnitTest      # Run unit tests
./gradlew lintDebug              # Run Android lint

# Compile only (fast check)
./gradlew compileDebugSources    # Compile without packaging
```

### Build Output Paths

- Debug APK: `app/build/outputs/apk/debug/*.apk`
- Release APK: `app/build/outputs/apk/release/*.apk`

### Key Gradle Properties

- JVM heap: 2048m (`-Xmx2048m`)
- Java compatibility: 1.8
- Kotlin JVM target: 1.8
- AndroidX and Jetifier enabled

## Dependencies

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

## Architecture

### Entry Points

1. **MainActivity.kt** - Launcher activity. Manages permissions, plugin UI, and starts the service.
2. **TronProtocolService.java** - Foreground service (`dataSync` type). Runs heartbeat (30s interval) and memory consolidation loops. Returns `START_STICKY` for auto-restart.
3. **BootReceiver.kt** - `BOOT_COMPLETED` receiver. Schedules `BootStartWorker` via WorkManager for deferred service startup after device reboot.

### Module Responsibilities

**plugins/** - Extensible plugin system with 14 built-in plugins. All plugins implement the `Plugin` interface (`getId`, `getName`, `getDescription`, `isEnabled`, `setEnabled`, `execute`, `initialize`, `destroy`). Plugins are registered in `PluginRegistry.kt` with startup priority ordering. `PluginManager` is a singleton orchestrator.

Plugin startup priority order:
| Priority | Plugin | ID |
|---|---|---|
| 10 | PolicyGuardrailPlugin | `policy_guardrail` |
| 20 | DeviceInfoPlugin | `device_info` |
| 30 | WebSearchPlugin | `web_search` |
| 40 | CalculatorPlugin | `calculator` |
| 50 | DateTimePlugin | `datetime` |
| 60 | TextAnalysisPlugin | `text_analysis` |
| 70 | FileManagerPlugin | `file_manager` |
| 80 | NotesPlugin | `notes` |
| 90 | TelegramBridgePlugin | `telegram_bridge` |
| 100 | TaskAutomationPlugin | `task_automation` |
| 110 | SandboxedCodeExecutionPlugin | `sandbox_exec` |
| 120 | PersonalizationPlugin | `personalization` |
| 130 | CommunicationHubPlugin | `communication_hub` |
| 140 | GuidanceRouterPlugin | `guidance_router` |

**security/** - Hardware-backed AES-256-GCM encryption via Android KeyStore (`EncryptionManager`) and encrypted key-value storage (`SecureStorage`).

**rag/** - Retrieval-Augmented Generation memory system inspired by MemRL (arXiv:2601.03192). Includes `RAGStore` with multiple retrieval strategies (semantic, keyword, hybrid, recency, MemRL), `TextChunk` with Q-values, and `MemoryConsolidationManager` for sleep-like memory optimization.

**selfmod/** - Self-modification system. `CodeModificationManager` handles self-reflection, code modification proposals, sandboxed validation, and rollback.

**aimodel/** - `ModelTrainingManager` for AI model creation, knowledge extraction, and model evolution.

**emotion/** - `EmotionalStateManager` for emotional state tracking and `HallucinationDetector` for hallucination detection.

**guidance/** - Ethical guidance with `GuidanceOrchestrator`, `EthicalKernelValidator`, `DecisionRouter`, and `AnthropicApiClient` for Claude API integration.

### Design Patterns

- **Singleton**: PluginManager, EncryptionManager
- **Interface-based plugins**: All plugins implement the `Plugin` interface
- **Factory pattern**: `PluginRegistry` uses factory lambdas for plugin instantiation
- **Priority-ordered initialization**: Plugins start in priority order (lower number = higher priority)
- **Observer/Diagnostics**: `StartupDiagnostics` records startup milestones

## CI/CD Pipeline

All workflows use a shared **android-common.yml** reusable workflow that sets up JDK 17 (Temurin), Android SDK 34, build-tools 33.0.2, and Gradle caching.

| Workflow | Trigger | Jobs |
|---|---|---|
| `ci.yml` | Push, PR | Compile -> Unit Tests + Lint |
| `build-apk.yml` | Push to main/develop, manual | Debug APK + Release APK |
| `release.yml` | Push `v*` tag | Build release APK -> GitHub Release |

The CI pipeline runs sequentially: compile first, then unit tests and lint in parallel (both depend on compile).

## Code Conventions

### Style

- **Java**: Standard Android Java conventions. CamelCase for classes/methods, UPPER_SNAKE_CASE for constants. JavaDoc on public methods.
- **Kotlin**: Idiomatic Kotlin with data classes, object singletons, and lambda factories.
- **Mixed language**: Java for core services and plugins, Kotlin for Android components and registry.

### Package Structure

All source lives under `com.tronprotocol.app` with feature-based subpackages:
```
com.tronprotocol.app
├── plugins/       # Plugin interface + 14 implementations + manager + registry
├── security/      # Encryption and secure storage
├── rag/           # RAG memory system
├── selfmod/       # Self-modification
├── aimodel/       # AI model training
├── emotion/       # Emotional state management
├── guidance/      # Ethical guidance system
└── models/        # Data model classes
```

### Key Interfaces

New plugins must implement `com.tronprotocol.app.plugins.Plugin`:
```java
public interface Plugin {
    String getId();
    String getName();
    String getDescription();
    boolean isEnabled();
    void setEnabled(boolean enabled);
    PluginResult execute(String input) throws Exception;
    void initialize(android.content.Context context);
    void destroy();
}
```

Then register in `PluginRegistry.kt` with an ID, class, default enabled state, priority, and factory lambda.

## Testing

There are currently no unit tests or instrumentation tests in the repository. The CI workflow runs `testDebugUnitTest` and `lintDebug`, but the test directories (`src/test/`, `src/androidTest/`) do not exist yet.

When adding tests:
- Unit tests go in `app/src/test/java/com/tronprotocol/app/`
- Instrumentation tests go in `app/src/androidTest/java/com/tronprotocol/app/`
- Run with `./gradlew testDebugUnitTest` (unit) or `./gradlew connectedDebugAndroidTest` (instrumentation)

## Android Permissions

The app declares 23 permissions across several categories: phone, SMS, contacts, location, network, storage, and system (foreground service, wake lock, boot completed, notifications). See `AndroidManifest.xml` for the full list. Any changes to permissions should be reflected in `MainActivity.kt` which handles runtime permission requests.

## Files to Avoid Modifying

- `gradle/wrapper/` - Gradle wrapper (change version in `gradle-wrapper.properties` only)
- `*.jks`, `*.keystore` - Signing keys (never commit)
- `local.properties` - Local SDK paths (gitignored)
- `google-services.json` - API keys (gitignored)

## Common Development Tasks

### Adding a New Plugin

1. Create a Java/Kotlin class implementing `Plugin` in `app/src/main/java/com/tronprotocol/app/plugins/`
2. Implement all interface methods (`getId`, `getName`, `getDescription`, `isEnabled`, `setEnabled`, `execute`, `initialize`, `destroy`)
3. Return a `PluginResult` from `execute()`
4. Register the plugin in `PluginRegistry.kt` with a unique ID and appropriate startup priority

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
