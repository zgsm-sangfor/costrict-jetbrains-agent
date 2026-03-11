#!/bin/bash

# Build script for RunVSAgent project
# This script builds VSCode extension and IDEA plugin

set -euo pipefail

# ============================================================================
# Source common utilities
# ============================================================================

# Determine the absolute path of the directory containing this script
# This approach is more robust than nested command substitution and works
# correctly even when the script is sourced from another location
get_script_dir() {
    local source="${BASH_SOURCE[0]}"
    
    # Resolve $source until the file is no longer a symlink
    while [ -h "$source" ]; do
        local dir
        dir="$(cd -P "$(dirname "$source")" && pwd)"
        source="$(readlink "$source")"
        
        # If $source was a relative symlink, we need to resolve it relative
        # to the path where the symlink file was located
        [[ $source != /* ]] && source="$dir/$source"
    done
    
    # Get the absolute path of the directory
    printf '%s\n' "$(cd -P "$(dirname "$source")" && pwd)"
}

# Set script directory as a read-only global variable
readonly SCRIPT_DIR="$(get_script_dir)"

# Source utility functions with error handling
source_lib_script() {
    local script_name="$1"
    local script_path="${SCRIPT_DIR}/${script_name}"
    
    # Check if the script exists
    if [[ ! -f "$script_path" ]]; then
        printf 'ERROR: Required library script not found: %s\n' "$script_path" >&2
        printf 'Current working directory: %s\n' "$(pwd)" >&2
        printf 'Script directory: %s\n' "$SCRIPT_DIR" >&2
        return 1
    fi
    
    # Source the script and check for errors
    # shellcheck source=../lib/common.sh
    if source "$script_path"; then
        return 0
    else
        printf 'ERROR: Failed to source library script: %s\n' "$script_path" >&2
        return 1
    fi
}

# Load common utilities library
if ! source_lib_script "lib/common.sh"; then
    exit 1
fi

# Load build-specific utilities library
if ! source_lib_script "lib/build.sh"; then
    exit 1
fi

# Script configuration
readonly SCRIPT_NAME="build.sh"
readonly SCRIPT_VERSION="1.0.0"

# Build targets
readonly TARGET_ALL="all"
readonly TARGET_VSCODE="vscode"
readonly TARGET_BASE="base"
readonly TARGET_IDEA="idea"

# Build configuration
BUILD_TARGET="$TARGET_ALL"
CLEAN_BEFORE_BUILD=false
SKIP_TESTS=false
OUTPUT_DIR=""
BUILD_PLATFORM="all"
COSTRICT_VERSION=""
LITE_BUILD=false
FULL_BUILD=false

# Show help for this script
show_help() {
    cat << EOF
$SCRIPT_NAME - Build RunVSAgent project components

USAGE:
    $SCRIPT_NAME [OPTIONS] [TARGET]

DESCRIPTION:
    This script builds the RunVSAgent project components:
    - VSCode extension (from submodule)
    - Base extension runtime
    - IDEA plugin

TARGETS:
    all         Build all components (default)
    vscode      Build only VSCode extension
    base        Build only base extension
    idea        Build only IDEA plugin

OPTIONS:
    -m, --mode MODE       Build mode: release (default) or debug
    -c, --clean           Clean before building
    -o, --output DIR      Output directory for build artifacts
    -p, --platform PLATFORM   Build platform for full build: all (default), windows, linux, macos
                              Exact full platforms: windows-x64, linux-x64, macos-arm64
                              Note: --platform macos maps to macos-arm64 (Apple Silicon)
                              Use --lite for cross-platform support (including Intel macOS)
    -t, --skip-tests      Skip running tests
    --costrict-version VERSION   CoStrict version to build (branch, tag, or commit)
    --vsix FILE           Use existing VSIX file (skip VSCode build)
    --skip-vscode         Skip VSCode extension build
    --skip-base           Skip base extension build
    --skip-idea           Skip IDEA plugin build
    --skip-nodejs         Skip Node.js preparation
    --lite                Build lite plugin only (without builtin Node.js, cross-platform)
    --full                Build full plugins only (with builtin Node.js, including macOS Apple Silicon)
    -v, --verbose         Enable verbose output
    -n, --dry-run         Show what would be done without executing
    -h, --help            Show this help message

BUILD MODES:
    release     Production build with optimizations (default)
    debug       Development build with debug symbols and resources

EXAMPLES:
    $SCRIPT_NAME                           # Build all: full (Win+Linux+macOS Apple Silicon) + lite (cross-platform)
    $SCRIPT_NAME --platform windows        # Build full plugin for Windows only
    $SCRIPT_NAME --platform linux          # Build full plugin for Linux only
    $SCRIPT_NAME --platform macos          # Build full plugin for macOS Apple Silicon only
    $SCRIPT_NAME --platform macos-arm64    # Build full plugin for macOS Apple Silicon only
    $SCRIPT_NAME --lite                    # Build lite plugin only (cross-platform, no builtin Node.js)
    $SCRIPT_NAME --full                    # Build full plugins only (Windows + Linux + macOS Apple Silicon)
    $SCRIPT_NAME --mode debug              # Debug build
    $SCRIPT_NAME --clean vscode            # Clean build VSCode only
    $SCRIPT_NAME --vsix path/to/file.vsix  # Use existing VSIX
    $SCRIPT_NAME --output ./dist           # Custom output directory
    $SCRIPT_NAME --costrict-version v1.2.3 # Build with specific CoStrict version
    $SCRIPT_NAME --costrict-version develop    # Build with develop branch

ENVIRONMENT:
    BUILD_MODE           Override build mode (release/debug)
    VSIX_FILE            Path to existing VSIX file
    COSTRICT_VERSION    Override CoStrict version (branch, tag, or commit)
    SKIP_VSCODE_BUILD    Skip VSCode build if set to 'true'
    SKIP_BASE_BUILD      Skip base build if set to 'true'
    SKIP_IDEA_BUILD      Skip IDEA build if set to 'true'
    SKIP_NODEJS_PREPARE  Skip Node.js preparation if set to 'true'

EXIT CODES:
    0    Success
    1    General error
    2    Build failed
    3    Invalid arguments
    4    Missing dependencies

EOF
}

# Parse command line arguments
parse_build_args() {
    while [[ $# -gt 0 ]]; do
        case $1 in
            -m|--mode)
                if [[ -z "${2:-}" ]]; then
                    log_error "Build mode requires a value"
                    exit 3
                fi
                BUILD_MODE="$2"
                shift 2
                ;;
            --costrict-version)
                if [[ -z "${2:-}" ]]; then
                    log_error "CoStrict version requires a value"
                    exit 3
                fi
                COSTRICT_VERSION="$2"
                shift 2
                ;;
            -c|--clean)
                CLEAN_BEFORE_BUILD=true
                shift
                ;;
            -o|--output)
                if [[ -z "${2:-}" ]]; then
                    log_error "Output directory requires a value"
                    exit 3
                fi
                OUTPUT_DIR="$2"
                shift 2
                ;;
            -p|--platform)
                if [[ -z "${2:-}" ]]; then
                    log_error "Platform requires a value"
                    exit 3
                fi
                BUILD_PLATFORM="$2"
                shift 2
                ;;
            -t|--skip-tests)
                SKIP_TESTS=true
                shift
                ;;
            --vsix)
                if [[ -z "${2:-}" ]]; then
                    log_error "VSIX file path requires a value"
                    exit 3
                fi
                VSIX_FILE="$2"
                SKIP_VSCODE_BUILD=true
                shift 2
                ;;
            --skip-vscode)
                SKIP_VSCODE_BUILD=true
                shift
                ;;
            --skip-base)
                SKIP_BASE_BUILD=true
                shift
                ;;
            --skip-idea)
                SKIP_IDEA_BUILD=true
                shift
                ;;
            --skip-nodejs)
                SKIP_NODEJS_PREPARE=true
                shift
                ;;
            --lite)
                LITE_BUILD=true
                FULL_BUILD=false
                shift
                ;;
            --full)
                FULL_BUILD=true
                LITE_BUILD=false
                shift
                ;;
            -v|--verbose)
                VERBOSE=true
                shift
                ;;
            -n|--dry-run)
                DRY_RUN=true
                shift
                ;;
            -h|--help)
                show_help
                exit 0
                ;;
            -*)
                log_error "Unknown option: $1"
                log_info "Use --help for usage information"
                exit 3
                ;;
            *)
                # Positional argument (target)
                BUILD_TARGET="$1"
                shift
                ;;
        esac
    done
    
    # Validate build mode
    if [[ "$BUILD_MODE" != "$BUILD_MODE_RELEASE" && "$BUILD_MODE" != "$BUILD_MODE_DEBUG" ]]; then
        log_error "Invalid build mode: $BUILD_MODE"
        log_info "Valid modes: $BUILD_MODE_RELEASE, $BUILD_MODE_DEBUG"
        exit 3
    fi
    
    # Validate build target
    case "$BUILD_TARGET" in
        "$TARGET_ALL"|"$TARGET_VSCODE"|"$TARGET_BASE"|"$TARGET_IDEA")
            ;;
        *)
            log_error "Invalid build target: $BUILD_TARGET"
            log_info "Valid targets: $TARGET_ALL, $TARGET_VSCODE, $TARGET_BASE, $TARGET_IDEA"
            exit 3
            ;;
    esac

    # Validate build platform
    case "$BUILD_PLATFORM" in
        "all")
            # Will build for all supported platforms
            ;;
        "windows")
            BUILD_PLATFORM="windows-x64"
            ;;
        "linux")
            BUILD_PLATFORM="linux-x64"
            ;;
        "macos")
            BUILD_PLATFORM="macos-arm64"
            ;;
        "windows-x64"|"linux-x64"|"macos-x64"|"macos-arm64")
            # Already in correct format
            ;;
        *)
            log_error "Invalid build platform: $BUILD_PLATFORM"
            log_info "Valid platforms: all, windows, linux, macos, windows-x64, linux-x64, macos-x64, macos-arm64"
            exit 3
            ;;
    esac

    if [[ "$BUILD_TARGET" == "$TARGET_ALL" || "$BUILD_TARGET" == "$TARGET_IDEA" ]]; then
        if [[ "$LITE_BUILD" != "true" && "$BUILD_PLATFORM" != "all" ]] && ! validate_platform "$BUILD_PLATFORM"; then
            log_error "Full build does not support platform: $BUILD_PLATFORM"
            log_info "Supported full build platforms: $(get_supported_platforms)"
            log_info "For macOS full builds, use --platform macos or --platform macos-arm64"
            exit 3
        fi
    fi
    
    # Set skip flags based on target
    case "$BUILD_TARGET" in
        "$TARGET_VSCODE")
            SKIP_BASE_BUILD=true
            SKIP_IDEA_BUILD=true
            ;;
        "$TARGET_BASE")
            SKIP_VSCODE_BUILD=true
            SKIP_IDEA_BUILD=true
            ;;
        "$TARGET_IDEA")
            SKIP_VSCODE_BUILD=true
            SKIP_BASE_BUILD=true
            ;;
    esac
    
    # Override with environment variables
    [[ "${SKIP_VSCODE_BUILD:-false}" == "true" ]] && SKIP_VSCODE_BUILD=true
    [[ "${SKIP_BASE_BUILD:-false}" == "true" ]] && SKIP_BASE_BUILD=true
    [[ "${SKIP_IDEA_BUILD:-false}" == "true" ]] && SKIP_IDEA_BUILD=true
    [[ -n "${VSIX_FILE:-}" ]] && VSIX_FILE="$VSIX_FILE"
    [[ -n "${COSTRICT_VERSION:-}" ]] && COSTRICT_VERSION="$COSTRICT_VERSION"
    
    # Ensure the function returns 0 on success, preventing `set -e` from exiting the script.
    true
}

# Check JDK version
check_jdk_version() {
    log_step "Checking JDK version..."
    
    # Check if java command exists
    if ! command_exists "java"; then
        log_error "Java not found. Please install JDK 17 or higher."
        exit 4
    fi
    
    # Get Java version
    local java_version_output
    java_version_output=$(java -version 2>&1 | head -n 1)
    
    # Extract version number from output
    local java_version
    if [[ "$java_version_output" =~ \"([0-9]+)\.([0-9]+)\.([0-9]+) ]]; then
        # Java 8 and earlier format: "1.8.0_xxx"
        if [[ "${BASH_REMATCH[1]}" == "1" ]]; then
            java_version="${BASH_REMATCH[2]}"
        else
            java_version="${BASH_REMATCH[1]}"
        fi
    elif [[ "$java_version_output" =~ \"([0-9]+) ]]; then
        # Java 9+ format: "17.0.1" or just "17"
        java_version="${BASH_REMATCH[1]}"
    else
        log_error "Unable to parse Java version from: $java_version_output"
        exit 4
    fi
    
    log_debug "Detected Java version: $java_version"
    
    # Check if version is >= 17
    if [[ "$java_version" -lt 17 ]]; then
        log_error "JDK version $java_version is too old. Required: JDK 17 or higher."
        log_info "Current Java version output: $java_version_output"
        exit 4
    fi
    
    log_success "JDK version check passed (version: $java_version)"
}

# Validate build environment
validate_build_environment() {
    log_step "Validating build environment..."
    
    # Check JDK version
    check_jdk_version
    
    # Check if setup has been run
    local vscode_dir="$PROJECT_ROOT/$VSCODE_SUBMODULE_PATH"
    if [[ ! -d "$vscode_dir" ]] || [[ ! "$(ls -A "$vscode_dir" 2>/dev/null)" ]]; then
        log_error "VSCode submodule not initialized. Run './scripts/setup.sh' first."
        exit 4
    fi
    
    # Check for required build files
    if [[ "$SKIP_BASE_BUILD" != "true" ]]; then
        if [[ ! -f "$PROJECT_ROOT/$EXTENSION_HOST_DIR/package.json" ]]; then
            log_error "Base package.json not found. Run './scripts/setup.sh' first."
            exit 4
        fi
    fi
    
    if [[ "$SKIP_IDEA_BUILD" != "true" ]]; then
        if [[ ! -f "$PROJECT_ROOT/$IDEA_DIR/build.gradle" && ! -f "$PROJECT_ROOT/$IDEA_DIR/build.gradle.kts" ]]; then
            log_error "IDEA Gradle build file not found."
            exit 4
        fi
    fi
    
    # Validate VSIX file if provided
    if [[ -n "$VSIX_FILE" ]]; then
        if [[ ! -f "$VSIX_FILE" ]]; then
            log_error "VSIX file not found: $VSIX_FILE"
            exit 4
        fi
        log_info "Using existing VSIX file: $VSIX_FILE"
    fi
    
    log_success "Build environment validated"
}

# Setup build output directory
setup_output_directory() {
    if [[ -n "$OUTPUT_DIR" ]]; then
        log_step "Setting up output directory..."
        
        ensure_dir "$OUTPUT_DIR"
        
        # Make output directory absolute
        OUTPUT_DIR="$(cd "$OUTPUT_DIR" && pwd)"
        
        log_info "Build artifacts will be copied to: $OUTPUT_DIR"
    fi
}

# Clean build artifacts
clean_build_artifacts() {
    if [[ "$CLEAN_BEFORE_BUILD" != "true" ]]; then
        return 0
    fi
    
    log_step "Cleaning build artifacts..."
    clean_build
    log_success "Build artifacts cleaned"
}

# Build VSCode extension
build_vscode_plugin_component() {
    if [[ "$SKIP_VSCODE_BUILD" == "true" ]]; then
        log_info "Skipping VSCode extension build"
        return 0
    fi
    
    log_step "Building VSCode extension..."
    

    
    # Build extension
    build_vscode_extension
    
    # Copy extension files
    copy_vscode_extension
    
    # Copy debug resources if in debug mode
    copy_debug_resources
      
    
    log_success "VSCode extension built"
}

# Build base extension
build_vscode_extension_host_component() {
    if [[ "$SKIP_BASE_BUILD" == "true" ]]; then
        log_info "Skipping Extension host build"
        return 0
    fi
    
    log_step "Building Extension host..."
    
    build_extension_host
    copy_base_debug_resources
    
    log_success "Extension host built"
}

# Build IDEA plugin
build_idea_component() {
    if [[ "$SKIP_IDEA_BUILD" == "true" ]]; then
        log_info "Skipping IDEA plugin build"
        return 0
    fi

    # Handle lite/full build flags
    if [[ "$LITE_BUILD" == "true" ]]; then
        build_lite_plugin
        log_success "IDEA lite plugin build completed"
        return 0
    fi
    
    if [[ "$BUILD_PLATFORM" == "all" ]]; then
        log_step "Building IDEA plugin for all platforms..."
        local supported_platforms=$(get_supported_platforms)
        local built_files=()

        for platform in $supported_platforms; do
            log_info "Building IDEA plugin for platform: $platform"
            build_idea_plugin "$platform"
            if [[ -n "${IDEA_PLUGIN_FILE:-}" ]]; then
                built_files+=("$IDEA_PLUGIN_FILE")
            fi
            log_success "IDEA plugin built for platform: $platform"
        done

        # Export all built files for summary
        export MULTI_PLATFORM_BUILT_FILES="${built_files[*]}"
    else
        log_step "Building IDEA plugin for platform: $BUILD_PLATFORM..."
        build_idea_plugin "$BUILD_PLATFORM"
    fi

    log_success "IDEA plugin build completed"
}

# Run tests
run_tests() {
    if [[ "$SKIP_TESTS" == "true" ]]; then
        log_info "Skipping tests"
        return 0
    fi
    
    log_step "Running tests..."
    
    # Run base tests if available
    if [[ "$SKIP_BASE_BUILD" != "true" && -f "$PROJECT_ROOT/$EXTENSION_HOST_DIR/package.json" ]]; then
        cd "$PROJECT_ROOT/$EXTENSION_HOST_DIR"
        if npm run test --if-present >/dev/null 2>&1; then
            execute_cmd "npm test" "base extension tests"
        else
            log_debug "No tests found for base extension"
        fi
    fi
    
    # Run VSCode extension tests if available
    if [[ "$SKIP_VSCODE_BUILD" != "true" && -d "$PROJECT_ROOT/$VSCODE_SUBMODULE_PATH" ]]; then
        cd "$PROJECT_ROOT/$VSCODE_SUBMODULE_PATH"
        local pkg_manager="npm"
        if command_exists "pnpm" && [[ -f "pnpm-lock.yaml" ]]; then
            pkg_manager="pnpm"
        fi
        
        if $pkg_manager run test --if-present >/dev/null 2>&1; then
            execute_cmd "$pkg_manager test" "VSCode extension tests"
        else
            log_debug "No tests found for VSCode extension"
        fi
    fi
    
    log_success "Tests completed"
}

# Copy build artifacts to output directory
copy_build_artifacts() {
    if [[ -z "$OUTPUT_DIR" ]]; then
        return 0
    fi

    log_step "Copying build artifacts to output directory..."

    # Copy VSIX file
    if [[ -n "$VSIX_FILE" && -f "$VSIX_FILE" ]]; then
        copy_files "$VSIX_FILE" "$OUTPUT_DIR/" "VSIX file"
    fi

    # Copy IDEA full plugins
    if [[ "$BUILD_PLATFORM" == "all" && -n "${MULTI_PLATFORM_BUILT_FILES:-}" ]]; then
        for plugin_file in $MULTI_PLATFORM_BUILT_FILES; do
            if [[ -f "$plugin_file" ]]; then
                copy_files "$plugin_file" "$OUTPUT_DIR/" "IDEA plugin"
            fi
        done
    elif [[ -n "${IDEA_PLUGIN_FILE:-}" && -f "$IDEA_PLUGIN_FILE" ]]; then
        copy_files "$IDEA_PLUGIN_FILE" "$OUTPUT_DIR/" "IDEA plugin"
    fi

    # Copy IDEA lite plugin if present
    if [[ -n "${IDEA_LITE_PLUGIN_FILE:-}" && -f "$IDEA_LITE_PLUGIN_FILE" ]]; then
        copy_files "$IDEA_LITE_PLUGIN_FILE" "$OUTPUT_DIR/" "IDEA lite plugin"
    fi

    # Copy debug resources if in debug mode
    if [[ "$BUILD_MODE" == "$BUILD_MODE_DEBUG" && -d "$PROJECT_ROOT/debug-resources" ]]; then
        copy_files "$PROJECT_ROOT/debug-resources" "$OUTPUT_DIR/" "debug resources"
    fi

    log_success "Build artifacts copied to output directory"
}

# Show build summary
show_build_summary() {
    log_step "Build Summary"

    echo ""
    log_info "Build completed successfully!"
    log_info "Build mode: $BUILD_MODE"
    log_info "Build target: $BUILD_TARGET"
    log_info "Build platform(s): $BUILD_PLATFORM"
    log_info "Host platform: $(get_platform)"

    echo ""
    log_info "Generated artifacts:"

    # Show VSIX file
    if [[ -n "$VSIX_FILE" && -f "$VSIX_FILE" ]]; then
        log_info "  VSCode Extension: $VSIX_FILE"
    fi

    # Show IDEA plugins (single or multiple platforms)
    if [[ "$LITE_BUILD" == "true" ]]; then
        if [[ -n "${IDEA_LITE_PLUGIN_FILE:-}" && -f "$IDEA_LITE_PLUGIN_FILE" ]]; then
            log_info "  IDEA Lite Plugin: $IDEA_LITE_PLUGIN_FILE"
        fi
    elif [[ "$BUILD_PLATFORM" == "all" && -n "${MULTI_PLATFORM_BUILT_FILES:-}" ]]; then
        log_info "  IDEA Plugins (platform-specific):"
        for plugin_file in $MULTI_PLATFORM_BUILT_FILES; do
            if [[ -f "$plugin_file" ]]; then
                log_info "    - $plugin_file"
            fi
        done
    elif [[ -n "${IDEA_PLUGIN_FILE:-}" && -f "$IDEA_PLUGIN_FILE" ]]; then
        log_info "  IDEA Plugin: $IDEA_PLUGIN_FILE"
    fi
    
    # Show lite plugin if built alongside full plugins (default behavior)
    if [[ "$LITE_BUILD" == "false" && "$FULL_BUILD" == "false" && -n "${IDEA_LITE_PLUGIN_FILE:-}" && -f "$IDEA_LITE_PLUGIN_FILE" ]]; then
        log_info "  IDEA Lite Plugin: $IDEA_LITE_PLUGIN_FILE"
    fi

    # Show debug resources
    if [[ "$BUILD_MODE" == "$BUILD_MODE_DEBUG" && -d "$PROJECT_ROOT/debug-resources" ]]; then
        log_info "  Debug Resources: $PROJECT_ROOT/debug-resources"
    fi

    # Show output directory
    if [[ -n "$OUTPUT_DIR" ]]; then
        log_info "  Output Directory: $OUTPUT_DIR"
    fi

    echo ""
    log_info "Node.js cache location: $NODEJS_CACHE_DIR"

    echo ""
    log_info "Next steps:"
    if [[ "$BUILD_TARGET" == "$TARGET_ALL" || "$BUILD_TARGET" == "$TARGET_IDEA" ]]; then
        if [[ "$BUILD_PLATFORM" == "all" ]]; then
            log_info "  1. Install appropriate platform-specific IDEA plugin from: $IDEA_BUILD_DIR/build/distributions/"
            log_info "  2. Choose plugin based on target platform (windows-x64, linux-x64, or macos-arm64)"
        else
            log_info "  1. Install IDEA plugin from: ${IDEA_PLUGIN_FILE:-$IDEA_BUILD_DIR/build/distributions/}"
            log_info "  2. Configure plugin settings in IDEA"
        fi
    fi

    echo ""
}

# Main build function
main() {
    log_info "Starting RunVSAgent build process..."
    log_info "Script: $SCRIPT_NAME v$SCRIPT_VERSION"
    log_info "Platform: $(get_platform)"
    log_info "Project root: $PROJECT_ROOT"
    
    if [[ "$DRY_RUN" == "true" ]]; then
        log_warn "DRY RUN MODE - No changes will be made"
    fi
    
    # Parse arguments
    parse_build_args "$@"
    
    log_info "Build configuration:"
    log_info "  Mode: $BUILD_MODE"
    log_info "  Target: $BUILD_TARGET"
    log_info "  Platform(s): $BUILD_PLATFORM"
    log_info "  Clean: $CLEAN_BEFORE_BUILD"
    log_info "  Skip tests: $SKIP_TESTS"
    [[ -n "$OUTPUT_DIR" ]] && log_info "  Output: $OUTPUT_DIR"
    [[ -n "$COSTRICT_VERSION" ]] && log_info "  CoStrict version: $COSTRICT_VERSION"
    
    # Show build type
    if [[ "$LITE_BUILD" == "true" ]]; then
        log_info "  Build type: Lite only (without builtin Node.js)"
    elif [[ "$FULL_BUILD" == "true" ]]; then
        log_info "  Build type: Full only (with builtin Node.js)"
    else
        log_info "  Build type: Both full and lite (default)"
    fi
    
    # Initialize build environment
    init_build_env
    
    # Run build steps
    validate_build_environment
    setup_output_directory
    clean_build_artifacts
    
    # Build components
    build_vscode_plugin_component
    build_vscode_extension_host_component
    
    # Handle lite/full build flags
    if [[ "$LITE_BUILD" == "false" && "$FULL_BUILD" == "false" ]]; then
        # Default behavior: build both full and lite plugins
        log_info "Building both full and lite plugins (default behavior)..."
        
        # Build full plugins
        build_idea_component
        
        # Reset skip flags for lite build
        local original_skip_idea="$SKIP_IDEA_BUILD"
        SKIP_IDEA_BUILD=false
        
        # Build lite plugin
        LITE_BUILD=true
        build_idea_component
        LITE_BUILD=false
        
        # Restore original skip flag
        SKIP_IDEA_BUILD="$original_skip_idea"
    else
        # Build based on specific flag
        build_idea_component
    fi
    
    # Run tests and finalize
    #run_tests
    copy_build_artifacts
    show_build_summary
    
    log_success "Build process completed successfully!"
}

# Run main function with all arguments
main "$@"