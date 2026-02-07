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

### Permissions
The app requests comprehensive permissions for full device access:
- **Phone**: Read phone state, call phone, manage call logs
- **SMS**: Send/receive SMS and MMS messages
- **Contacts**: Read and write contacts, access accounts
- **Location**: Fine and coarse location access
- **Network**: Internet and network state access
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
│       │   └── BootReceiver.java         # Boot broadcast receiver
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

## License

This project is provided as-is for development purposes.
