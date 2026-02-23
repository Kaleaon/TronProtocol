# Android SDK and Gradle Setup Guide

This guide documents the Android SDK and Gradle configuration for the TronProtocol project.

## Prerequisites

- Java JDK 17 or higher
- Android SDK (API levels 32, 33, 34)
- Android Build Tools (32.0.0, 33.0.2, 34.0.0)

## Installation

### Install Android SDK Command Line Tools

#### Linux/MacOS
```bash
# Download command-line tools
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-11076708_latest.zip
mkdir -p ~/Android/sdk/cmdline-tools/latest
mv cmdline-tools/* ~/Android/sdk/cmdline-tools/latest/

# Set environment variables
export ANDROID_HOME=~/Android/sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
```

#### Windows
Download and extract command-line tools from:
https://developer.android.com/studio#command-tools

### Install Required SDK Components

```bash
# Accept licenses
yes | sdkmanager --licenses

# Install required components
sdkmanager "platform-tools"
sdkmanager "platforms;android-34"
sdkmanager "platforms;android-33"
sdkmanager "platforms;android-32"
sdkmanager "build-tools;34.0.0"
sdkmanager "build-tools;33.0.2"
sdkmanager "build-tools;32.0.0"
```

### Install Java JDK

```bash
# On Debian/Ubuntu
sudo apt-get install openjdk-17-jdk

# Set JAVA_HOME
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
```

## Configuration

### local.properties

Copy `local.properties.example` to `local.properties` and set your SDK path:

```bash
cp local.properties.example local.properties
# Edit local.properties and uncomment the sdk.dir line
```

Example `local.properties`:
```properties
sdk.dir=/home/youruser/Android/Sdk
```

### gradle.properties

The project includes optimized Gradle settings in `gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.enableJetifier=true
android.nonTransitiveRClass=false
android.defaults.buildfeatures.buildconfig=true
android.nonFinalResIds=false
android.suppressUnsupportedCompileSdk=34
```

## Building the Project

### Debug Build
```bash
./gradlew assembleDebug
```

### Release Build
```bash
./gradlew assembleRelease
```

### Clean Build
```bash
./gradlew clean assembleDebug
```

## Output APKs

Build outputs are located in:
- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

## Troubleshooting

### SDK Not Found Error
Ensure `local.properties` exists and contains the correct `sdk.dir` path.

### License Acceptance Error
Run `yes | sdkmanager --licenses` to accept all SDK licenses.

### Build Tool Version Mismatch
Ensure you have the correct build-tools versions installed (34.0.0 for API 34).

### Java Version Error
Set `JAVA_HOME` to point to JDK 17 or higher:
```bash
export JAVA_HOME=/path/to/jdk-17
```

## CI/CD Configuration

For CI/CD environments, set the following environment variables instead of using `local.properties`:

```yaml
env:
  ANDROID_HOME: /path/to/android/sdk
  ANDROID_SDK_ROOT: /path/to/android/sdk
  JAVA_HOME: /path/to/jdk-17
```

## Gradle Wrapper

The project uses Gradle 8.4 with the Android Gradle Plugin 8.1.0. The wrapper is pre-configured and should not need modification.

## Additional Resources

- [Android Developer Documentation](https://developer.android.com/)
- [Gradle Documentation](https://docs.gradle.org/)
- [Kotlin for Android](https://developer.android.com/kotlin)