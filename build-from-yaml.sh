#!/bin/bash
# TronProtocol YAML-based Build Automation Script
# Supports both ToolNeuron and Clever Ferret YAML configurations

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Print colored message
print_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Display usage information
usage() {
    cat << EOF
TronProtocol YAML Build Automation

Usage: $0 [OPTIONS]

OPTIONS:
    -c, --config FILE       Specify YAML config file (toolneuron.yaml or cleverferret.yaml)
    -t, --type TYPE         Build type: debug, release, or both (default: debug)
    -f, --format FORMAT     YAML format: toolneuron or cleverferret (auto-detect if not specified)
    -o, --output DIR        Output directory for built APKs (default: dist/)
    -h, --help              Show this help message

EXAMPLES:
    # Build debug APK using toolneuron.yaml
    $0 --config toolneuron.yaml --type debug

    # Build both debug and release using cleverferret.yaml
    $0 --config cleverferret.yaml --type both

    # Auto-detect YAML and build debug
    $0 --type debug

EOF
}

# Parse command line arguments
CONFIG_FILE=""
BUILD_TYPE="debug"
YAML_FORMAT=""
OUTPUT_DIR="dist"

while [[ $# -gt 0 ]]; do
    case $1 in
        -c|--config)
            CONFIG_FILE="$2"
            shift 2
            ;;
        -t|--type)
            BUILD_TYPE="$2"
            shift 2
            ;;
        -f|--format)
            YAML_FORMAT="$2"
            shift 2
            ;;
        -o|--output)
            OUTPUT_DIR="$2"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            print_error "Unknown option: $1"
            usage
            exit 1
            ;;
    esac
done

# Auto-detect YAML config file if not specified
if [ -z "$CONFIG_FILE" ]; then
    if [ -f "$PROJECT_ROOT/toolneuron.yaml" ]; then
        CONFIG_FILE="$PROJECT_ROOT/toolneuron.yaml"
        YAML_FORMAT="toolneuron"
        print_info "Auto-detected toolneuron.yaml"
    elif [ -f "$PROJECT_ROOT/cleverferret.yaml" ]; then
        CONFIG_FILE="$PROJECT_ROOT/cleverferret.yaml"
        YAML_FORMAT="cleverferret"
        print_info "Auto-detected cleverferret.yaml"
    else
        print_error "No YAML configuration file found (toolneuron.yaml or cleverferret.yaml)"
        exit 1
    fi
else
    # Make config file path absolute
    if [[ ! "$CONFIG_FILE" = /* ]]; then
        CONFIG_FILE="$PROJECT_ROOT/$CONFIG_FILE"
    fi
    
    if [ ! -f "$CONFIG_FILE" ]; then
        print_error "Configuration file not found: $CONFIG_FILE"
        exit 1
    fi
fi

# Auto-detect YAML format from filename if not specified
if [ -z "$YAML_FORMAT" ]; then
    if [[ "$CONFIG_FILE" == *"toolneuron"* ]]; then
        YAML_FORMAT="toolneuron"
    elif [[ "$CONFIG_FILE" == *"cleverferret"* ]]; then
        YAML_FORMAT="cleverferret"
    else
        print_warning "Could not auto-detect YAML format, defaulting to toolneuron"
        YAML_FORMAT="toolneuron"
    fi
fi

print_info "Using configuration: $CONFIG_FILE (format: $YAML_FORMAT)"
print_info "Build type: $BUILD_TYPE"
print_info "Output directory: $OUTPUT_DIR"

# Create output directory
mkdir -p "$OUTPUT_DIR"

# Clean previous builds
print_info "Cleaning previous builds..."
cd "$PROJECT_ROOT"
./gradlew clean > /dev/null 2>&1 || true

# Build based on type
build_debug() {
    print_info "Building debug APK..."
    ./gradlew assembleDebug
    
    # Copy debug APK to output directory
    DEBUG_APK=$(find app/build/outputs/apk/debug -name "*.apk" | head -n 1)
    if [ -f "$DEBUG_APK" ]; then
        APP_NAME=$(grep -A1 "app:" "$CONFIG_FILE" | grep "name:" | awk '{print $2}' | tr -d '"' || echo "TronProtocol")
        cp "$DEBUG_APK" "$OUTPUT_DIR/${APP_NAME}-debug.apk"
        print_success "Debug APK built: $OUTPUT_DIR/${APP_NAME}-debug.apk"
    else
        print_error "Debug APK not found"
        exit 1
    fi
}

build_release() {
    print_info "Building release APK..."
    ./gradlew assembleRelease
    
    # Copy release APK to output directory
    RELEASE_APK=$(find app/build/outputs/apk/release -name "*.apk" | head -n 1)
    if [ -f "$RELEASE_APK" ]; then
        APP_NAME=$(grep -A1 "app:" "$CONFIG_FILE" | grep "name:" | awk '{print $2}' | tr -d '"' || echo "TronProtocol")
        cp "$RELEASE_APK" "$OUTPUT_DIR/${APP_NAME}-release.apk"
        print_success "Release APK built: $OUTPUT_DIR/${APP_NAME}-release.apk"
    else
        print_error "Release APK not found"
        exit 1
    fi
}

case $BUILD_TYPE in
    debug)
        build_debug
        ;;
    release)
        build_release
        ;;
    both)
        build_debug
        build_release
        ;;
    *)
        print_error "Invalid build type: $BUILD_TYPE (must be: debug, release, or both)"
        exit 1
        ;;
esac

# Display summary
print_success "Build completed successfully!"
print_info "Build artifacts available in: $OUTPUT_DIR"
ls -lh "$OUTPUT_DIR"/*.apk 2>/dev/null || true

exit 0
