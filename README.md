# TronProtocol
A.I. heartbeat, and cellular device access.

## Overview
TronProtocol is an Android application designed for continuous AI monitoring and cellular device access with advanced background service capabilities.

## Features

### Background Services
- **Foreground Service**: Persistent background service that runs continuously
- **Wake Lock**: Prevents the device from sleeping during critical operations
- **Boot Receiver**: Automatically starts the service when the device boots
- **Battery Optimization Override**: Requests exemption from battery optimization to ensure uninterrupted operation

### AI/NPU Integration
The app includes dependencies for Neural Processing Unit (NPU) and AI Core functionality:
- TensorFlow Lite for on-device machine learning
- TensorFlow Lite GPU support for accelerated inference
- ML Kit for text recognition
- Support for neural network acceleration

### RAG with Self-Evolving Memory (Inspired by landseek)
- **MemRL System**: Self-evolving memory using Q-value learning (arXiv:2601.03192)
- **Multiple Retrieval Strategies**: Semantic, keyword, hybrid, recency, MemRL
- **Feedback-Driven Learning**: Memories improve through usage feedback
- **10M+ Token Context**: Scalable knowledge base per AI instance
- **Persistent Storage**: Encrypted memory chunks with embeddings

### Self-Modification System (Inspired by landseek free_will)
- **Self-Reflection**: AI analyzes its own behavior metrics
- **Code Modification**: Propose, validate, and apply code changes
- **Safety Features**: Validation, backups, rollback capability
- **Modification History**: Track all changes with statistics
- **Sandboxed Execution**: Safe testing before deployment

### Memory Consolidation (Brain-Inspired)
- **Sleep-Like Optimization**: Reorganizes memories during idle/rest periods (1-5 AM)
- **5-Phase Process**: Strengthen, weaken, forget, connect, optimize
- **Active Forgetting**: Removes low-value memories automatically
- **Semantic Connections**: Links related concepts for better retrieval
- **Performance Gain**: 10-20% faster retrieval after consolidation

### Essential AI Tools (6 Built-in Plugins)
- **WebSearchPlugin**: Privacy-focused web search (DuckDuckGo)
- **CalculatorPlugin**: Math expressions, scientific functions, unit conversions
- **DateTimePlugin**: Time operations, timezone conversion, date arithmetic
- **TextAnalysisPlugin**: Text stats, URL/email extraction, transformations
- **FileManagerPlugin**: Full filesystem access, document editing/creation
- **DeviceInfoPlugin**: System and hardware information

### Document & File Operations (New!)
- **Full Filesystem Access**: Read, write, create, delete files and directories
- **Document Editing**: Modify existing documents
- **Document Creation**: Create new documents from scratch
- **File Management**: List, move, copy, search files
- **Batch Operations**: Multiple file operations
- **Path Resolution**: Supports relative and absolute paths

### AI Model Training (New!)
- **Self-Training**: AI creates models from its own knowledge
- **Knowledge Extraction**: Uses MemRL to extract best concepts
- **Model Evolution**: Retrain models with new knowledge packs
- **Training Metrics**: Track accuracy, iterations, loss
- **Model Export**: Save trained models to files
- **Quality-Based**: Training considers Q-values for quality

### Security Features (Inspired by ToolNeuron)
- **Hardware-Backed Encryption**: AES-256-GCM encryption using Android KeyStore
- **Secure Storage**: Encrypted storage for sensitive data with automatic key management
- **Memory Vault**: Secure data persistence inspired by ToolNeuron's architecture
- **Zero Trust**: All sensitive data encrypted at rest

### Plugin System (Inspired by ToolNeuron)
Extensible plugin architecture for adding functionality:
- **Plugin Interface**: Standardized plugin API
- **Plugin Manager**: Central management for all plugins
- **Built-in Plugins**: Device info and system utilities
- **Execution Metrics**: Performance tracking for plugin operations

### Permissions
The app requests comprehensive permissions for full device and filesystem access:
- **Phone**: Read phone state, call phone, manage call logs
- **SMS**: Send/receive SMS and MMS messages
- **Contacts**: Read and write contacts, access accounts
- **Location**: Fine and coarse location access
- **Network**: Internet and network state access
- **Storage**: Read/write external storage, full filesystem access
- **System**: Foreground service, wake lock, battery optimization exemption

## Project Structure

```
TronProtocol/
├── app/
│   ├── build.gradle              # App-level build configuration with NPU/AI dependencies
│   ├── proguard-rules.pro        # ProGuard rules for release builds
│   └── src/main/
│       ├── AndroidManifest.xml   # App manifest with permissions and components
│       ├── java/com/tronprotocol/app/
│       │   ├── MainActivity.java         # Main activity with permission handling
│       │   ├── TronProtocolService.java  # Background foreground service
│       │   ├── BootReceiver.java         # Boot broadcast receiver
│       │   ├── security/
│       │   │   ├── EncryptionManager.java    # Hardware-backed encryption (from ToolNeuron)
│       │   │   └── SecureStorage.java        # Encrypted storage system (from ToolNeuron)
│       │   ├── rag/
│       │   │   ├── RAGStore.java                    # Self-evolving RAG with MemRL (from landseek)
│       │   │   ├── TextChunk.java                   # Memory chunks with Q-values
│       │   │   ├── RetrievalStrategy.java           # 6 retrieval strategies
│       │   │   ├── RetrievalResult.java             # Query results
│       │   │   └── MemoryConsolidationManager.java  # Sleep-like memory optimization
│       │   ├── selfmod/
│       │   │   ├── CodeModificationManager.java  # AI self-modification (from landseek)
│       │   │   ├── CodeModification.java         # Modification proposals
│       │   │   ├── ReflectionResult.java         # Self-reflection insights
│       │   │   └── ValidationResult.java         # Modification validation
│       │   ├── models/
│       │   │   └── AIModel.java              # AI model representation (from ToolNeuron)
│       │   └── plugins/
│       │       ├── Plugin.java               # Plugin interface (from ToolNeuron)
│       │       ├── PluginResult.java         # Plugin execution results (from ToolNeuron)
│       │       ├── PluginManager.java        # Plugin management system (from ToolNeuron)
│       │       └── DeviceInfoPlugin.java     # Example plugin (from ToolNeuron)
│       └── res/
│           ├── layout/
│           │   └── activity_main.xml     # Main activity layout
│           ├── values/
│           │   └── strings.xml           # String resources
│           └── mipmap-*/                 # App icons
├── build.gradle                  # Project-level build configuration
├── settings.gradle               # Project settings
└── gradle.properties             # Gradle properties

```

## Building the App

### Prerequisites
- Android Studio Arctic Fox or later
- Android SDK with API Level 24 (Android 7.0) or higher
- Gradle 8.0
- Java 17 or Java 21 (recommended for Gradle/AGP compatibility)

### Java Compatibility Note
Some environments default to Java 25, which can fail during Gradle script compilation with `Unsupported class file major version 69`.

`./gradlew` now auto-detects Java 25 and falls back to Java 21 if it can find a compatible local JDK (for example via `JAVA21_HOME` or common install paths).

### Build Steps
1. Open the project in Android Studio
2. Sync Gradle files
3. Build the project: `./gradlew build`
4. Run on device/emulator: `./gradlew installDebug`

## How It Works

### Service Lifecycle
1. **App Launch**: MainActivity starts and requests all necessary permissions
2. **Permission Grant**: User grants phone, SMS, contacts, location permissions
3. **Battery Override**: App requests to be excluded from battery optimization
4. **Service Start**: TronProtocolService starts as a foreground service
5. **Heartbeat Loop**: Service runs a continuous heartbeat every 30 seconds
6. **AI Processing**: Placeholder for NPU/AI Core processing in the heartbeat loop

### Background Persistence
- The service uses `START_STICKY` to restart if killed by the system
- Wake lock keeps the device partially awake for processing
- Foreground notification prevents the service from being killed
- Boot receiver restarts the service after device reboot

## Startup Compatibility Matrix (Notification Prerequisites)

`TronProtocolService` now performs a startup preflight before entering long-running loops. If notification prerequisites are missing, the service publishes a startup diagnostic and does **not** start heartbeat/consolidation loops.

- **API 33+ (Android 13+)**
  - Requires `POST_NOTIFICATIONS` runtime permission for startup.
  - If missing, startup state is `blocked-by-permission`, loops are skipped, and logs direct the user to grant notification permission.
- **API 26+ (Android 8–12L)**
  - Foreground notification channel must exist and `NotificationManager` must be available.
  - If channel/manager is unavailable, startup state is `deferred`, loops are skipped, and logs direct user to reopen the app.
- **API 24+ notification app-toggle edge case**
  - If app-level notifications are disabled, startup state is `degraded`.
  - Service may still run loops, but user-visible alerts are suppressed.
- **Below API 24**
  - Notification app-toggle check is not available; startup proceeds if foreground start succeeds.

MainActivity displays a startup badge sourced from diagnostics persisted by the service:
- `running`
- `deferred`
- `blocked-by-permission`
- `degraded`

## NPU/AI Dependencies

The following dependencies are included for AI/NPU functionality:

```gradle
implementation 'org.tensorflow:tensorflow-lite:2.13.0'
implementation 'org.tensorflow:tensorflow-lite-gpu:2.13.0'
implementation 'org.tensorflow:tensorflow-lite-support:0.4.4'
implementation 'com.google.android.gms:play-services-mlkit-text-recognition:19.0.0'
```

These libraries enable:
- On-device machine learning inference
- GPU acceleration for neural networks
- ML model support utilities
- Text recognition via ML Kit

## Security Considerations

⚠️ **Important**: This app requests sensitive permissions including:
- Access to phone calls and SMS messages
- Access to contacts
- Ability to run continuously in the background
- Battery optimization exemption

Ensure these permissions are used responsibly and in compliance with applicable laws and regulations.

## ToolNeuron Integration

This project integrates several architectural concepts from [ToolNeuron](https://github.com/Siddhesh2377/ToolNeuron), a privacy-first AI assistant:

### Integrated Features

1. **Hardware-Backed Encryption**
   - AES-256-GCM encryption using Android KeyStore
   - Secure key generation and storage
   - Based on ToolNeuron's Memory Vault encryption architecture

2. **Secure Storage System**
   - Encrypted file-based storage for sensitive data
   - Automatic encryption/decryption on read/write
   - Key-value store with secure persistence

3. **Plugin Architecture**
   - Extensible plugin system for adding functionality
   - Plugin lifecycle management (initialize, execute, destroy)
   - Execution metrics and error handling
   - Similar to ToolNeuron's plugin system for web search, calculator, etc.

4. **Model Management**
   - AI model representation and metadata
   - Model loading status tracking
   - Category-based organization
   - Inspired by ToolNeuron's model management system

### Usage Examples

**Secure Storage:**
```java
SecureStorage storage = new SecureStorage(context);
storage.store("api_key", "sensitive_data");
String data = storage.retrieve("api_key");
```

**Plugin System:**
```java
PluginManager manager = PluginManager.getInstance();
manager.initialize(context);
manager.registerPlugin(new DeviceInfoPlugin());
PluginResult result = manager.executePlugin("device_info", "");
```

**Encryption:**
```java
EncryptionManager encryption = new EncryptionManager();
byte[] encrypted = encryption.encryptString("secret message");
String decrypted = encryption.decryptString(encrypted);
```

## License

This project is provided as-is for development purposes.
