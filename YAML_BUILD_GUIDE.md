# YAML Build Automation Guide

## Overview

TronProtocol supports automatic packaging and debug building from YAML configuration files in two formats:
- **ToolNeuron YAML** (`toolneuron.yaml`)
- **Clever Ferret YAML** (`cleverferret.yaml`)

## Quick Start

### 1. Using the Shell Script

The simplest way to build from YAML:

```bash
# Build debug APK (auto-detects YAML file)
./build-from-yaml.sh --type debug

# Build release APK from specific YAML
./build-from-yaml.sh --config toolneuron.yaml --type release

# Build both debug and release
./build-from-yaml.sh --type both --output dist/
```

### 2. Using the Python Configurator

For more advanced configuration management:

```bash
# Show configuration summary
python3 yaml-build-config.py -c toolneuron.yaml -s

# Generate Gradle build file
python3 yaml-build-config.py -c toolneuron.yaml -g

# Update actual build.gradle from YAML
python3 yaml-build-config.py -c cleverferret.yaml -u
```

## YAML Configuration Formats

### ToolNeuron Format (`toolneuron.yaml`)

```yaml
app:
  name: "TronProtocol"
  package: "com.tronprotocol.app"
  
version:
  code: 1
  name: "1.0.0"
  
build:
  compile_sdk: 34
  min_sdk: 24
  target_sdk: 34
  
build_types:
  debug:
    enabled: true
    debuggable: true
    minify_enabled: false
    
  release:
    enabled: true
    minify_enabled: true
    proguard_files:
      - "proguard-rules.pro"
```

### Clever Ferret Format (`cleverferret.yaml`)

```yaml
project:
  name: TronProtocol
  id: com.tronprotocol.app

versioning:
  version_code: 1
  version_name: 1.0.0

android:
  compileSdk: 34
  minSdk: 24
  targetSdk: 34

variants:
  - name: debug
    type: debug
    config:
      debuggable: true
      minifyEnabled: false
```

## Build Script Options

### Shell Script (`build-from-yaml.sh`)

```
OPTIONS:
    -c, --config FILE       YAML config file (toolneuron.yaml or cleverferret.yaml)
    -t, --type TYPE         Build type: debug, release, or both (default: debug)
    -f, --format FORMAT     YAML format: toolneuron or cleverferret (auto-detect)
    -o, --output DIR        Output directory (default: dist/)
    -h, --help              Show help
```

### Python Configurator (`yaml-build-config.py`)

```
OPTIONS:
    -c, --config FILE       YAML configuration file (required)
    -f, --format FORMAT     YAML format: auto, toolneuron, or cleverferret
    -g, --generate          Generate Gradle build file
    -u, --update            Update actual build.gradle
    -s, --summary           Print configuration summary
```

## Common Tasks

### Build Debug APK

```bash
./build-from-yaml.sh --type debug
# Output: dist/TronProtocol-debug.apk
```

### Build Release APK

```bash
./build-from-yaml.sh --type release
# Output: dist/TronProtocol-release.apk
```

### Build Both Variants

```bash
./build-from-yaml.sh --type both
# Output: dist/TronProtocol-debug.apk
#         dist/TronProtocol-release.apk
```

### Update build.gradle from YAML

```bash
# This updates app/build.gradle based on YAML config
python3 yaml-build-config.py -c toolneuron.yaml -u
```

### Preview Changes

```bash
# Generate without updating
python3 yaml-build-config.py -c toolneuron.yaml -g
# Check app/build.gradle.generated
```

## Configuration Examples

### Adding Dependencies

**ToolNeuron format:**
```yaml
dependencies:
  - "androidx.appcompat:appcompat:1.6.1"
  - "org.tensorflow:tensorflow-lite:2.13.0"
```

**Clever Ferret format:**
```yaml
libraries:
  androidx:
    - appcompat:1.6.1
  tensorflow:
    - tensorflow-lite:2.13.0
```

### Configuring Build Types

**ToolNeuron format:**
```yaml
build_types:
  debug:
    enabled: true
    debuggable: true
    minify_enabled: false
    
  release:
    enabled: true
    debuggable: false
    minify_enabled: true
    shrink_resources: true
```

**Clever Ferret format:**
```yaml
variants:
  - name: debug
    type: debug
    config:
      debuggable: true
      minifyEnabled: false
      
  - name: release
    type: release
    config:
      minifyEnabled: true
      shrinkResources: true
```

### Setting Version

**ToolNeuron format:**
```yaml
version:
  code: 2
  name: "1.1.0"
```

**Clever Ferret format:**
```yaml
versioning:
  version_code: 2
  version_name: 1.1.0
  auto_increment: true
```

## CI/CD Integration

### GitHub Actions Example

```yaml
name: Build APK from YAML

on: [push]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - name: Set up JDK
        uses: actions/setup-java@v2
        with:
          java-version: '11'
      - name: Build from YAML
        run: |
          chmod +x build-from-yaml.sh
          ./build-from-yaml.sh --type both
      - name: Upload APKs
        uses: actions/upload-artifact@v2
        with:
          name: apk-files
          path: dist/*.apk
```

### Azure Pipelines Example

```yaml
trigger:
  - main

pool:
  vmImage: 'ubuntu-latest'

steps:
  - script: |
      chmod +x build-from-yaml.sh
      ./build-from-yaml.sh --type release
    displayName: 'Build Release APK from YAML'
    
  - task: PublishBuildArtifacts@1
    inputs:
      pathToPublish: 'dist'
      artifactName: 'apk'
```

## Troubleshooting

### YAML Not Found

```bash
# Ensure you're in the project root
cd /path/to/TronProtocol

# Check for YAML files
ls -la *.yaml
```

### Build Fails

```bash
# Check Gradle wrapper
./gradlew --version

# Clean and retry
./gradlew clean
./build-from-yaml.sh --type debug
```

### Python Dependencies

```bash
# Install PyYAML if missing
pip install pyyaml
```

## Advanced Usage

### Multiple Configurations

Create environment-specific YAML files:
- `toolneuron.dev.yaml`
- `toolneuron.staging.yaml`
- `toolneuron.prod.yaml`

Build specific environment:
```bash
./build-from-yaml.sh --config toolneuron.prod.yaml --type release
```

### Custom Output Naming

Edit YAML:
```yaml
build_types:
  release:
    output_name: "TronProtocol-v${version_name}-release"
```

### Signing Configuration

**ToolNeuron format:**
```yaml
signing:
  release:
    store_file: "release.keystore"
    store_password: "${KEYSTORE_PASSWORD}"
    key_alias: "release"
    key_password: "${KEY_PASSWORD}"
```

Use environment variables:
```bash
export KEYSTORE_PASSWORD="your_password"
export KEY_PASSWORD="your_key_password"
./build-from-yaml.sh --type release
```

## Best Practices

1. **Version Control**: Commit YAML configs but not keystores
2. **Secrets**: Use environment variables for passwords
3. **CI/CD**: Auto-detect YAML in pipelines
4. **Testing**: Build debug locally, release in CI
5. **Backup**: Keep original build.gradle as .backup

## Reference

- `toolneuron.yaml` - Full ToolNeuron format example
- `cleverferret.yaml` - Full Clever Ferret format example
- `build-from-yaml.sh` - Build automation script
- `yaml-build-config.py` - Configuration parser/generator
