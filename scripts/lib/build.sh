#!/bin/bash

# Build-specific utility functions
# This file provides functions for building VSCode extensions and IDEA plugins

# Source common utilities (common.sh should be in the same directory)
LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$LIB_DIR/common.sh"

# Build configuration
DEFAULT_BUILD_MODE="release"
readonly VSCODE_BRANCH="develop"
readonly IDEA_DIR="jetbrains_plugin"
readonly TEMP_PREFIX="build_temp_"

# Build modes
readonly BUILD_MODE_RELEASE="release"
readonly BUILD_MODE_DEBUG="debug"

# Global build variables
BUILD_MODE="$DEFAULT_BUILD_MODE"
VSIX_FILE=""
SKIP_VSCODE_BUILD=false
SKIP_BASE_BUILD=false
SKIP_IDEA_BUILD=false
SKIP_NODEJS_PREPARE=false
COSTRICT_VERSION=""
LITE_BUILD=false

# Node.js configuration
readonly NODEJS_VERSION="20.6.0"
BUILTIN_NODEJS_DIR=""  # Will be set in init_build_env()
readonly NODEJS_DOWNLOAD_BASE_URL="https://nodejs.org/dist/v${NODEJS_VERSION}"
# Node.js cache directory - shared across builds to avoid re-downloading
readonly NODEJS_CACHE_DIR="$HOME/.nodejs-cache/${NODEJS_VERSION}"

# Node.js platform mappings
# NOTE:
# Do NOT use bash array subscripts for keys like "windows-x64" under `set -u`:
# some bash contexts treat subscripts as arithmetic and parse it as `windows - x64`,
# which triggers "windows: unbound variable". Use an explicit mapping function instead.
get_nodejs_platform_archive_filename() {
    local platform="$1"
    case "$platform" in
        "windows-x64")
            echo "node-v${NODEJS_VERSION}-win-x64.zip"
            ;;
        "linux-x64")
            echo "node-v${NODEJS_VERSION}-linux-x64.tar.xz"
            ;;
        "macos-arm64")
            echo "node-v${NODEJS_VERSION}-darwin-arm64.tar.gz"
            ;;
        *)
            echo ""
            ;;
    esac
}

# Initialize build environment
init_build_env() {
    log_step "Initializing build environment..."
    
    # Set build paths
    export BUILD_TEMP_DIR="$(mktemp -d -t ${TEMP_PREFIX}XXXXXX)"
    export PLUGIN_BUILD_DIR="$PROJECT_ROOT/$PLUGIN_SUBMODULE_PATH"
    export BASE_BUILD_DIR="$PROJECT_ROOT/$EXTENSION_HOST_DIR"
    export IDEA_BUILD_DIR="$PROJECT_ROOT/$IDEA_DIR"
    export VSCODE_PLUGIN_NAME="${VSCODE_PLUGIN_NAME:-costrict}"
    export VSCODE_PLUGIN_TARGET_DIR="$IDEA_BUILD_DIR/plugins/${VSCODE_PLUGIN_NAME}"
    export BUILTIN_NODEJS_DIR="$IDEA_BUILD_DIR/src/main/resources/builtin-nodejs"
    
    # Validate build tools
    validate_build_tools
    
    log_debug "Build temp directory: $BUILD_TEMP_DIR"
    log_debug "Plugin build directory: $PLUGIN_BUILD_DIR"
    log_debug "Base build directory: $BASE_BUILD_DIR"
    log_debug "IDEA build directory: $IDEA_BUILD_DIR"
    
    log_success "Build environment initialized"
}

# Validate build tools
validate_build_tools() {
    log_step "Validating build tools..."
    
    local required_tools=("git" "node" "npm" "unzip")
    
    # Add platform-specific tools
    if command_exists "pnpm"; then
        log_debug "Found pnpm package manager"
    else
        log_warn "pnpm not found, will use npm"
    fi
    
    # Check for Gradle (for IDEA plugin)
    if command_exists "gradle" || [[ -f "$IDEA_BUILD_DIR/gradlew" ]]; then
        log_debug "Found Gradle build tool"
    else
        log_warn "Gradle not found, IDEA plugin build may fail"
    fi
    
    for tool in "${required_tools[@]}"; do
        if ! command_exists "$tool"; then
            die "Required build tool not found: $tool"
        fi
        log_debug "Found build tool: $tool"
    done
    
    log_success "Build tools validation passed"
}

# Initialize git submodules
init_submodules() {
    log_step "Initializing git submodules..."
    
    if [[ ! -d "$PLUGIN_BUILD_DIR" ]] || [[ ! "$(ls -A "$PLUGIN_BUILD_DIR" 2>/dev/null)" ]]; then
        log_info "VSCode submodule not found or empty, initializing..."
        
        cd "$PROJECT_ROOT"
        execute_cmd "git submodule init" "git submodule init"
        execute_cmd "git submodule update" "git submodule update"
        
        log_info "Switching to $VSCODE_BRANCH branch..."
        cd "$PLUGIN_BUILD_DIR"
        execute_cmd "git checkout $VSCODE_BRANCH" "git checkout $VSCODE_BRANCH"
        
        log_success "Git submodules initialized"
    else
        log_info "VSCode submodule already exists, skipping initialization"
    fi
}

# Apply patches to VSCode
apply_vscode_patches() {
    local patch_file="$1"
    
    if [[ -z "$patch_file" ]] || [[ ! -f "$patch_file" ]]; then
        log_warn "No patch file specified or file not found: $patch_file"
        return 0
    fi
    
    log_step "Applying VSCode patches..."
    
    cd "$PLUGIN_BUILD_DIR"
    
    # Check if patch is already applied
    if git apply --check "$patch_file" 2>/dev/null; then
        execute_cmd "git apply '$patch_file'" "patch application"
        log_success "Patch applied successfully"
    else
        log_warn "Patch cannot be applied (may already be applied or conflicts exist)"
    fi
}

# Revert VSCode changes
revert_vscode_changes() {
    log_step "Reverting VSCode changes..."
    
    cd "$PLUGIN_BUILD_DIR"
    execute_cmd "git reset --hard" "git reset"
    execute_cmd "git clean -fd" "git clean"
    
    log_success "VSCode changes reverted"
}

# Switch CoStrict to specified version
switch_costrict_version() {
    if [[ -z "${COSTRICT_VERSION:-}" ]]; then
        log_debug "No CoStrict version specified, using current version"
        return 0
    fi
    
    log_step "Switching CoStrict to version: $COSTRICT_VERSION..."
    
    # Check if submodule directory exists
    if [[ ! -d "$PLUGIN_BUILD_DIR" ]]; then
        die "CoStrict submodule directory not found: $PLUGIN_BUILD_DIR"
    fi
    
    cd "$PLUGIN_BUILD_DIR"
    
    # Clean any uncommitted changes and untracked files in the submodule
    log_debug "Cleaning CoStrict working directory..."
    if [[ -n $(git status --porcelain 2>/dev/null) ]]; then
        execute_cmd "git reset --hard HEAD" "reset CoStrict to clean state"
        execute_cmd "git clean -ffdx" "remove all untracked files and directories"
    fi
    
    # Fetch latest from remote to ensure we have the specified version
    log_debug "Fetching latest from CoStrict remote..."
    # Use || true to prevent fetch errors from stopping the script
    git fetch origin --tags --force --prune 2>&1 || {
        log_warn "Fetch had some warnings, continuing..."
    }
    log_success "Fetched latest from remote"
    
    # Check if the specified version exists (tag, branch, or commit)
    local target_commit=""
    local version_type=""
    
    # First try as a tag (most common for versions like v2.0.23)
    if git rev-parse --verify "refs/tags/$COSTRICT_VERSION" >/dev/null 2>&1; then
        target_commit="refs/tags/$COSTRICT_VERSION"
        version_type="tag"
        log_debug "Found tag: $COSTRICT_VERSION"
    # Try as a remote branch
    elif git rev-parse --verify "origin/$COSTRICT_VERSION" >/dev/null 2>&1; then
        target_commit="origin/$COSTRICT_VERSION"
        version_type="branch"
        log_debug "Found remote branch: $COSTRICT_VERSION"
    # Try as a local branch
    elif git rev-parse --verify "$COSTRICT_VERSION" >/dev/null 2>&1; then
        target_commit="$COSTRICT_VERSION"
        version_type="reference"
        log_debug "Found local reference: $COSTRICT_VERSION"
    else
        # List available tags and branches for debugging
        log_error "CoStrict version '$COSTRICT_VERSION' not found"
        log_info "Available tags:"
        git tag -l | head -10
        log_info "Available branches:"
        git branch -r | grep -v HEAD | head -10
        die "CoStrict version '$COSTRICT_VERSION' not found. Please check the version/tag/branch name."
    fi
    
    # Switch to the specified version
    log_info "Switching to $version_type: $COSTRICT_VERSION"
    execute_cmd "git checkout -f $target_commit" "switch to CoStrict version $COSTRICT_VERSION"
    
    # Show current commit info
    local current_commit=$(git rev-parse HEAD)
    local commit_message=$(git log -1 --pretty=format:"%s" "$current_commit")
    local current_tag=""
    
    # Try to find if current commit has a tag
    if current_tag=$(git describe --tags --exact-match "$current_commit" 2>/dev/null); then
        log_info "Switched to CoStrict version: $COSTRICT_VERSION (tag: $current_tag, commit: ${current_commit:0:8})"
    else
        log_info "Switched to CoStrict version: $COSTRICT_VERSION (commit: ${current_commit:0:8})"
    fi
    log_info "Commit message: $commit_message"
    
    log_success "CoStrict version switched successfully"
}

# Build VSCode extension
build_vscode_extension() {
    if [[ "$SKIP_VSCODE_BUILD" == "true" ]]; then
        log_info "Skipping VSCode extension build"
        return 0
    fi
    
    log_step "Building VSCode extension..."
    
    # Switch to specified CoStrict version if provided
    switch_costrict_version
    
    cd "$PLUGIN_BUILD_DIR"
    
    # Install dependencies
    local pkg_manager="npm"
    if command_exists "pnpm" && [[ -f "pnpm-lock.yaml" ]]; then
        pkg_manager="pnpm"
    fi
    
    log_info "Installing dependencies with $pkg_manager..."
    execute_cmd "$pkg_manager install" "dependency installation"
    
    # Apply Windows compatibility fix if needed
    apply_windows_compatibility_fix
    execute_cmd "$pkg_manager run clean" "VSIX clean"
    # Build based on mode
    if [[ "$BUILD_MODE" == "$BUILD_MODE_DEBUG" ]]; then
        log_info "Building in debug mode..."
        export USE_DEBUG_BUILD="true"
        execute_cmd "$pkg_manager run vsix" "VSIX build"
        execute_cmd "$pkg_manager run bundle" "bundle build"
    else
        log_info "Building in release mode..."
        execute_cmd "$pkg_manager run vsix" "VSIX build"
    fi
    
    # Find the generated VSIX file
    VSIX_FILE=$(get_latest_file "$PLUGIN_BUILD_DIR/bin" "*.vsix")
    if [[ -z "$VSIX_FILE" ]]; then
        die "VSIX file not found after build"
    fi
    
    log_success "VSCode extension built: $VSIX_FILE"
}

# Apply Windows compatibility fix
apply_windows_compatibility_fix() {
    local windows_release_file="$PLUGIN_BUILD_DIR/node_modules/.pnpm/windows-release@6.1.0/node_modules/windows-release/index.js"
    
    if [[ -f "$windows_release_file" ]]; then
        log_debug "Applying Windows compatibility fix..."
        
        # Use perl for cross-platform compatibility
        if command_exists "perl"; then
            perl -i -pe "s/execaSync\\('wmic', \\['os', 'get', 'Caption'\\]\\)\\.stdout \\|\\| ''/''/g" "$windows_release_file"
            perl -i -pe "s/execaSync\\('powershell', \\['\\(Get-CimInstance -ClassName Win32_OperatingSystem\\)\\.caption'\\]\\)\\.stdout \\|\\| ''/''/g" "$windows_release_file"
            log_debug "Windows compatibility fix applied"
        else
            log_warn "perl not found, skipping Windows compatibility fix"
        fi
    fi
}

# Extract VSIX file
extract_vsix() {
    local vsix_file="$1"
    local extract_dir="$2"
    
    if [[ -z "$vsix_file" ]] || [[ ! -f "$vsix_file" ]]; then
        die "VSIX file not found: $vsix_file"
    fi
    
    log_step "Extracting VSIX file..."
    
    ensure_dir "$extract_dir"
    execute_cmd "unzip -q '$vsix_file' -d '$extract_dir'" "VSIX extraction"
    
    log_success "VSIX extracted to: $extract_dir"
}

# Copy VSIX contents to target directory
copy_vscode_extension() {
    local vsix_file="${1:-$VSIX_FILE}"
    local target_dir="${2:-$VSCODE_PLUGIN_TARGET_DIR}"
    
    if [[ -z "$vsix_file" ]]; then
        die "No VSIX file specified"
    fi
    
    log_step "Copying VSCode extension files..."
    
    # Clean target directory
    remove_dir "$target_dir"
    ensure_dir "$target_dir"
    
    # Extract VSIX to temp directory
    local temp_extract_dir="$BUILD_TEMP_DIR/vsix_extract"
    extract_vsix "$vsix_file" "$temp_extract_dir"
    
    # Copy extension files
    copy_files "$temp_extract_dir/extension" "$target_dir/" "VSCode extension files"
    
    log_success "VSCode extension files copied"
}

# Copy debug resources (for debug builds)
copy_debug_resources() {
    if [[ "$BUILD_MODE" != "$BUILD_MODE_DEBUG" ]]; then
        return 0
    fi
    
    log_step "Copying debug resources..."
    
    local debug_res_dir="$PROJECT_ROOT/debug-resources"
    local vscode_plugin_debug_dir="$debug_res_dir/${VSCODE_PLUGIN_NAME}"
    
    # Clean debug resources
    remove_dir "$debug_res_dir"
    ensure_dir "$vscode_plugin_debug_dir"
    
    cd "$PLUGIN_BUILD_DIR"
    
    # Copy various debug resources
    copy_files "src/dist/i18n" "$vscode_plugin_debug_dir/dist/" "i18n files"
    copy_files "src/dist/extension.js" "$vscode_plugin_debug_dir/dist/" "extension.js"
    copy_files "src/dist/extension.js.map" "$vscode_plugin_debug_dir/dist/" "extension.js.map"
    
    # Copy WASM files
    find "$PLUGIN_BUILD_DIR/src/dist" -maxdepth 1 -name "*.wasm" -exec cp {} "$vscode_plugin_debug_dir/dist/" \;
    
    # Copy assets and audio
    copy_files "src/assets" "$vscode_plugin_debug_dir/" "assets"
    copy_files "src/webview-ui/audio" "$vscode_plugin_debug_dir/" "audio files"
    
    # Copy webview build
    copy_files "src/webview-ui/build" "$vscode_plugin_debug_dir/webview-ui/" "webview build"
    
    # Copy theme files
    ensure_dir "$vscode_plugin_debug_dir/src/integrations/theme/default-themes"
    copy_files "src/integrations/theme/default-themes" "$vscode_plugin_debug_dir/src/integrations/theme/" "default themes"
    
    # Copy IDEA themes if they exist
    local idea_themes_dir="$IDEA_BUILD_DIR/src/main/resources/themes"
    if [[ -d "$idea_themes_dir" ]]; then
        copy_files "$idea_themes_dir/*" "$vscode_plugin_debug_dir/src/integrations/theme/default-themes/" "IDEA themes"
    fi
    
    # Copy JSON files (excluding specific ones)
    for json_file in "$PLUGIN_BUILD_DIR"/*.json; do
        local filename=$(basename "$json_file")
        if [[ "$filename" != "package-lock.json" && "$filename" != "tsconfig.json" ]]; then
            copy_files "$json_file" "$vscode_plugin_debug_dir/" "$filename"
        fi
    done
    
    # Remove type field from package.json for CommonJS compatibility
    local debug_package_json="$vscode_plugin_debug_dir/package.json"
    if [[ -f "$debug_package_json" ]]; then
        node -e "
            const fs = require('fs');
            const pkgPath = process.argv[1];
            const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));
            delete pkg.type;
            fs.writeFileSync(pkgPath, JSON.stringify(pkg, null, 2));
            console.log('Removed type field from debug package.json for CommonJS compatibility');
        " "$debug_package_json"
    fi
    
    log_success "Debug resources copied"
}

# Build base extension
build_extension_host() {
    if [[ "$SKIP_BASE_BUILD" == "true" ]]; then
        log_info "Skipping Extension host build"
        return 0
    fi
    
    log_step "Building Extension host..."
    
    cd "$BASE_BUILD_DIR"
    
    # Clean previous build
    remove_dir "dist"
    
    # Build extension
    if [[ "$BUILD_MODE" == "$BUILD_MODE_DEBUG" ]]; then
        execute_cmd "npm run build" "Extension host build (debug)"
    else
        execute_cmd "npm run build:extension" "Extension host build (release)"
    fi
    
    # Generate production dependencies list
    execute_cmd "npm ls --prod --depth=10 --parseable > '$IDEA_BUILD_DIR/prodDep.txt'" "production dependencies list"
    
    log_success "Base extension built"
}

# Copy base extension for debug
copy_base_debug_resources() {
    if [[ "$BUILD_MODE" != "$BUILD_MODE_DEBUG" ]]; then
        return 0
    fi
    
    log_step "Copying base debug resources..."
    
    local debug_res_dir="$PROJECT_ROOT/debug-resources"
    local runtime_dir="$debug_res_dir/runtime"
    local node_modules_dir="$debug_res_dir/node_modules"
    
    ensure_dir "$runtime_dir"
    ensure_dir "$node_modules_dir"
    
    # Copy node_modules
    copy_files "$BASE_BUILD_DIR/node_modules/*" "$node_modules_dir/" "base node_modules"
    
    # Copy package.json and dist
    copy_files "$BASE_BUILD_DIR/package.json" "$runtime_dir/" "base package.json"
    copy_files "$BASE_BUILD_DIR/dist/*" "$runtime_dir/" "base dist files"
    
    log_success "Base debug resources copied"
}

# Download Node.js binary for a specific platform
download_nodejs_binary() {
    # Note: This function can be called while the parent script has `set -u` enabled.
    # Use safe parameter expansion to avoid "unbound variable" crashes and show a clear error.
    local platform="${1:-}"
    local supported_platforms
    supported_platforms="$(get_supported_platforms)"
    if [[ -z "$platform" ]]; then
        # Auto-detect current platform when not provided (works with the rest of this script's platform naming)
        platform="$(get_current_platform)"
    fi
    if [[ -z "$platform" ]]; then
        die "Platform is required (argument missing and auto-detection failed). Supported platforms: ${supported_platforms}"
    fi

    local filename
    filename="$(get_nodejs_platform_archive_filename "$platform")"
    
    # Validate platform is supported for pre-built Node.js
    if [[ -z "$filename" ]]; then
        die "Platform '$platform' is not supported for full plugin build. Supported platforms: ${supported_platforms}. Use --lite flag to build lite plugin which supports more platforms via online download."
    fi
    
    local url="$NODEJS_DOWNLOAD_BASE_URL/$filename"
    local cache_file="$NODEJS_CACHE_DIR/$filename"
    local target_dir="$BUILTIN_NODEJS_DIR/$platform"
    local target_file="$target_dir/$filename"

    # First check if file exists in cache
    if [[ -f "$cache_file" ]]; then
        local file_size=$(stat -f%z "$cache_file" 2>/dev/null || stat -c%s "$cache_file" 2>/dev/null || echo "0")
        local min_size=25000000  # 25MB (Windows zip is typically ~28MB)

        if [[ "$file_size" -gt "$min_size" ]]; then
            log_info "Using cached Node.js binary for $platform from $cache_file"
            ensure_dir "$target_dir"
            execute_cmd "cp '$cache_file' '$target_file'" "copy Node.js binary from cache"
            log_success "Copied Node.js binary for $platform from cache ($(( file_size / 1024 / 1024 ))MB)"
            return 0
        else
            log_warn "Cached file is too small, removing and re-downloading..."
            rm -f "$cache_file"
        fi
    fi

    # If not in cache or cache file is invalid, download it
    log_info "Downloading Node.js $NODEJS_VERSION for $platform (will cache for future builds)..."

    # Ensure cache directory exists
    ensure_dir "$NODEJS_CACHE_DIR"

    # Download to cache first
    if command_exists "curl"; then
        execute_cmd "curl -L --progress-bar -o '$cache_file' '$url'" "download Node.js $platform to cache"
    elif command_exists "wget"; then
        execute_cmd "wget --show-progress -O '$cache_file' '$url'" "download Node.js $platform to cache"
    else
        die "Neither curl nor wget found for downloading Node.js"
    fi

    # Verify download in cache
    local file_size=$(stat -f%z "$cache_file" 2>/dev/null || stat -c%s "$cache_file" 2>/dev/null || echo "0")
    local min_size=25000000  # 25MB (Windows zip is typically ~28MB)

    if [[ "$file_size" -lt "$min_size" ]]; then
        remove_file "$cache_file"
        die "Downloaded Node.js binary appears corrupted for $platform (size: $file_size bytes)"
    fi

    # Copy from cache to target directory
    ensure_dir "$target_dir"
    execute_cmd "cp '$cache_file' '$target_file'" "copy Node.js binary from cache to target"

    log_success "Downloaded and cached Node.js for $platform ($(( file_size / 1024 / 1024 ))MB)"
}

# Download all Node.js binaries
download_all_nodejs_binaries() {
    log_step "Downloading Node.js binaries for all platforms..."

    local platforms
    platforms="$(get_supported_platforms)"
    for platform in $platforms; do
        download_nodejs_binary "$platform"
    done

    log_success "All Node.js binaries downloaded"
}

# Generate Node.js configuration file
generate_nodejs_config() {
    log_step "Generating Node.js configuration..."

    local config_file="$BUILTIN_NODEJS_DIR/config.json"

    # Create JSON config using heredoc
    cat > "$config_file" << EOF
{
  "version": "$NODEJS_VERSION",
  "platforms": {
    "windows-x64": {
      "file": "node-v$NODEJS_VERSION-win-x64.zip",
      "extractPath": "node-v$NODEJS_VERSION-win-x64/",
      "executable": "node.exe"
    },
    "linux-x64": {
      "file": "node-v$NODEJS_VERSION-linux-x64.tar.xz",
      "extractPath": "node-v$NODEJS_VERSION-linux-x64/bin/",
      "executable": "node"
    },
    "macos-arm64": {
      "file": "node-v$NODEJS_VERSION-darwin-arm64.tar.gz",
      "extractPath": "node-v$NODEJS_VERSION-darwin-arm64/bin/",
      "executable": "node"
    }
  }
}
EOF

    if [[ ! -f "$config_file" ]]; then
        die "Failed to generate Node.js configuration file"
    fi

    log_success "Node.js configuration generated: $config_file"
}

# Generate Node.js configuration file for specific platform
generate_nodejs_config_for_platform() {
    local platform="$1"
    log_step "Generating Node.js configuration for platform: $platform..."

    local config_file="$BUILTIN_NODEJS_DIR/config.json"
    local platform_config=""

    # Generate platform-specific configuration
    case "$platform" in
        "windows-x64")
            platform_config='
    "windows-x64": {
      "file": "node-v'"$NODEJS_VERSION"'-win-x64.zip",
      "extractPath": "node-v'"$NODEJS_VERSION"'-win-x64/",
      "executable": "node.exe"
    }'
            ;;
        "linux-x64")
            platform_config='
    "linux-x64": {
      "file": "node-v'"$NODEJS_VERSION"'-linux-x64.tar.xz",
      "extractPath": "node-v'"$NODEJS_VERSION"'-linux-x64/bin/",
      "executable": "node"
    }'
            ;;
        "macos-arm64")
            platform_config='
    "macos-arm64": {
      "file": "node-v'"$NODEJS_VERSION"'-darwin-arm64.tar.gz",
      "extractPath": "node-v'"$NODEJS_VERSION"'-darwin-arm64/bin/",
      "executable": "node"
    }'
            ;;
        *)
            die "Unsupported platform for Node.js configuration: $platform"
            ;;
    esac

    # Create JSON config using heredoc
    cat > "$config_file" << EOF
{
  "version": "$NODEJS_VERSION",
  "platforms": {
$platform_config
  }
}
EOF

    if [[ ! -f "$config_file" ]]; then
        die "Failed to generate Node.js configuration file for platform: $platform"
    fi

    log_success "Node.js configuration generated for $platform: $config_file"
}

# Prepare builtin Node.js before build (places files in src/main/resources)
prepare_builtin_nodejs_prebuild() {
    if [[ "$SKIP_NODEJS_PREPARE" == "true" ]]; then
        log_info "Skipping Node.js pre-build preparation (SKIP_NODEJS_PREPARE=true)"
        return 0
    fi

    log_step "Preparing builtin Node.js $NODEJS_VERSION for plugin resources..."

    # Use the standard BUILTIN_NODEJS_DIR (src/main/resources/builtin-nodejs)
    ensure_dir "$BUILTIN_NODEJS_DIR"

    # Download binaries
    download_all_nodejs_binaries

    # Generate config
    generate_nodejs_config

    log_success "Builtin Node.js prepared in resources directory: $BUILTIN_NODEJS_DIR"

    # Copy asset files (setup scripts) to resources directory
    local asset_target_dir="$IDEA_BUILD_DIR/src/main/resources/scripts"
    copy_asset_files "$asset_target_dir" "all"
}

# Prepare builtin Node.js for specific platform
prepare_builtin_nodejs_prebuild_for_platform() {
    local target_platform="$1"

    if [[ "$SKIP_NODEJS_PREPARE" == "true" ]]; then
        log_info "Skipping Node.js pre-build preparation (SKIP_NODEJS_PREPARE=true)"
        return 0
    fi

    # For lite build, we prepare online setup scripts instead of builtin Node.js
    if [[ "$LITE_BUILD" == "true" ]]; then
        log_step "Preparing online setup scripts for lite build..."
        
        # Copy online asset files (setup scripts) to resources directory
        local asset_target_dir="$IDEA_BUILD_DIR/src/main/resources/scripts"
        copy_online_asset_files "$asset_target_dir" "$target_platform"
        
        log_success "Online setup scripts prepared for lite build"
        return 0
    fi

    log_step "Preparing builtin Node.js $NODEJS_VERSION for platform: $target_platform..."

    # Use the standard BUILTIN_NODEJS_DIR (src/main/resources/builtin-nodejs)
    ensure_dir "$BUILTIN_NODEJS_DIR"

    if [[ "$target_platform" == "all" ]]; then
        # Download all platforms for universal build
        download_all_nodejs_binaries
        generate_nodejs_config
    else
        # Download only specific platform
        download_nodejs_binary "$target_platform"
        # Generate config for single platform
        generate_nodejs_config_for_platform "$target_platform"
    fi

    log_success "Builtin Node.js prepared for platform: $target_platform"

    # Copy asset files (setup scripts) to resources directory
    local asset_target_dir="$IDEA_BUILD_DIR/src/main/resources/scripts"
    copy_asset_files "$asset_target_dir" "$target_platform"
}

# Copy asset files (setup scripts) to target directory
# Used by prepare_builtin_nodejs_prebuild to copy to resources directory
# Only copies builtin version scripts (setup-node.sh/bat), not online versions
# Args: target_dir, platform (windows-x64, linux-x64, macos-x64, macos-arm64, all)
copy_asset_files() {
    local target_dir="$1"
    local platform="${2:-all}"
    local asset_dir="$PROJECT_ROOT/scripts/asset"
    
    log_step "Copying asset files (setup scripts for builtin Node.js, platform: $platform)..."
    log_debug "Asset directory: $asset_dir"
    log_debug "Target directory: $target_dir"
    
    if [[ ! -d "$asset_dir" ]]; then
        log_warn "Asset directory not found: $asset_dir"
        return 0
    fi
    
    # Ensure target directory exists
    ensure_dir "$target_dir"
    
    # Copy platform-specific setup scripts
    case "$platform" in
        windows-*)
            # Windows platform: only copy .bat script
            if [[ -f "$asset_dir/setup-node.bat" ]]; then
                copy_files "$asset_dir/setup-node.bat" "$target_dir/setup-node.bat" "setup-node.bat"
                chmod +x "$target_dir/setup-node.bat"
                log_debug "Copied setup-node.bat for Windows"
            fi
            ;;
        linux-*|macos-*)
            # Linux/macOS platform: only copy .sh script
            if [[ -f "$asset_dir/setup-node.sh" ]]; then
                copy_files "$asset_dir/setup-node.sh" "$target_dir/setup-node.sh" "setup-node.sh"
                chmod +x "$target_dir/setup-node.sh"
                log_debug "Copied setup-node.sh for Linux/macOS"
            fi
            ;;
        all)
            # Universal build: copy both scripts
            if [[ -f "$asset_dir/setup-node.sh" ]]; then
                copy_files "$asset_dir/setup-node.sh" "$target_dir/setup-node.sh" "setup-node.sh"
                chmod +x "$target_dir/setup-node.sh"
                log_debug "Copied setup-node.sh"
            fi
            if [[ -f "$asset_dir/setup-node.bat" ]]; then
                copy_files "$asset_dir/setup-node.bat" "$target_dir/setup-node.bat" "setup-node.bat"
                chmod +x "$target_dir/setup-node.bat"
                log_debug "Copied setup-node.bat"
            fi
            ;;
        *)
            log_warn "Unknown platform: $platform, skipping asset files"
            return 0
            ;;
    esac
    
    log_success "Asset files copied to: $target_dir"
}

# Copy online asset files (setup scripts) for lite build
# Used by build_lite_plugin to copy online setup scripts
# Args: target_dir, platform (windows-x64, linux-x64, macos-x64, macos-arm64, all, lite)
copy_online_asset_files() {
    local target_dir="$1"
    local platform="${2:-all}"
    local asset_dir="$PROJECT_ROOT/scripts/asset"
    
    log_step "Copying online asset files (setup scripts for lite build, platform: $platform)..."
    log_debug "Asset directory: $asset_dir"
    log_debug "Target directory: $target_dir"
    
    if [[ ! -d "$asset_dir" ]]; then
        log_warn "Asset directory not found: $asset_dir"
        return 0
    fi
    
    # Ensure target directory exists
    ensure_dir "$target_dir"
    
    # Copy platform-specific online setup scripts
    case "$platform" in
        windows-*)
            # Windows platform: only copy .bat script
            if [[ -f "$asset_dir/setup-node-online.bat" ]]; then
                copy_files "$asset_dir/setup-node-online.bat" "$target_dir/setup-node-online.bat" "setup-node-online.bat"
                chmod +x "$target_dir/setup-node-online.bat"
                log_debug "Copied setup-node-online.bat for Windows"
            fi
            ;;
        linux-*|macos-*)
            # Linux/macOS platform: only copy .sh script
            if [[ -f "$asset_dir/setup-node-online.sh" ]]; then
                copy_files "$asset_dir/setup-node-online.sh" "$target_dir/setup-node-online.sh" "setup-node-online.sh"
                chmod +x "$target_dir/setup-node-online.sh"
                log_debug "Copied setup-node-online.sh for Linux/macOS"
            fi
            ;;
        all|lite)
            # Universal build or lite build without specific platform: copy both scripts
            if [[ -f "$asset_dir/setup-node-online.sh" ]]; then
                copy_files "$asset_dir/setup-node-online.sh" "$target_dir/setup-node-online.sh" "setup-node-online.sh"
                chmod +x "$target_dir/setup-node-online.sh"
                log_debug "Copied setup-node-online.sh"
            fi
            if [[ -f "$asset_dir/setup-node-online.bat" ]]; then
                copy_files "$asset_dir/setup-node-online.bat" "$target_dir/setup-node-online.bat" "setup-node-online.bat"
                chmod +x "$target_dir/setup-node-online.bat"
                log_debug "Copied setup-node-online.bat"
            fi
            ;;
        *)
            log_warn "Unknown platform: $platform, skipping online asset files"
            return 0
            ;;
    esac
    
    log_success "Online asset files copied to: $target_dir"
}

# Clean IDEA resources after build
# This removes temporary build artifacts from resources directory
clean_idea_resources_postbuild() {
    log_step "Cleaning IDEA temporary resources after build..."
    
    local builtin_nodejs_dir="$IDEA_BUILD_DIR/src/main/resources/builtin-nodejs"
    local scripts_dir="$IDEA_BUILD_DIR/src/main/resources/scripts"
    
    # Remove builtin-nodejs directory
    if [[ -d "$builtin_nodejs_dir" ]]; then
        log_info "Removing builtin-nodejs directory..."
        remove_dir "$builtin_nodejs_dir"
        log_debug "Removed: $builtin_nodejs_dir"
    fi
    
    # Remove scripts directory
    if [[ -d "$scripts_dir" ]]; then
        log_info "Removing scripts directory..."
        remove_dir "$scripts_dir"
        log_debug "Removed: $scripts_dir"
    fi
    
    log_success "IDEA temporary resources cleaned"
}

# Build IDEA plugin
build_idea_plugin() {
    local target_platform="${1:-all}"

    if [[ "$SKIP_IDEA_BUILD" == "true" ]]; then
        log_info "Skipping IDEA plugin build"
        return 0
    fi

    log_step "Building IDEA plugin for platform: $target_platform..."

    cd "$IDEA_BUILD_DIR"

    # Check for Gradle build files
    if [[ ! -f "build.gradle" && ! -f "build.gradle.kts" ]]; then
        die "No Gradle build file found in IDEA directory"
    fi

    # Prepare builtin Node.js before building (so it gets packaged into the plugin)
    prepare_builtin_nodejs_prebuild_for_platform "$target_platform"

    # Use gradlew if available, otherwise use system gradle
    local gradle_cmd="gradle"
    if [[ -f "./gradlew" ]]; then
        gradle_cmd="./gradlew"
        chmod +x "./gradlew"
    fi

    # Set debugMode based on BUILD_MODE
    local debug_mode="none"
    if [[ "$BUILD_MODE" == "$BUILD_MODE_RELEASE" ]]; then
        debug_mode="release"
        log_info "Building IDEA plugin in release mode (debugMode=release)"
    elif [[ "$BUILD_MODE" == "$BUILD_MODE_DEBUG" ]]; then
        debug_mode="idea"
        log_info "Building IDEA plugin in debug mode (debugMode=idea)"
    fi

    # Set platform-specific Gradle properties
    local gradle_props="-PdebugMode=$debug_mode"
    if [[ "$target_platform" != "all" ]]; then
        gradle_props="$gradle_props -PtargetPlatform=$target_platform"
        local platform_identifier=$(get_platform_identifier "$target_platform")
        gradle_props="$gradle_props -PplatformIdentifier=$platform_identifier"
        log_info "Building with platform identifier: $platform_identifier"
    else
        gradle_props="$gradle_props -PtargetPlatform=all"
    fi

    local expected_plugin_file
    expected_plugin_file="$(get_expected_idea_plugin_archive_path "$target_platform")"
    ensure_dir "$(dirname "$expected_plugin_file")"
    if [[ -f "$expected_plugin_file" ]]; then
        remove_file "$expected_plugin_file"
    fi

    # Build plugin with platform-specific properties
    execute_cmd "$gradle_cmd $gradle_props buildPlugin --info" "IDEA plugin build"

    if [[ -f "$expected_plugin_file" ]]; then
        log_success "IDEA plugin built: $expected_plugin_file"
        export IDEA_PLUGIN_FILE="$expected_plugin_file"
    else
        die "Expected IDEA plugin archive was not generated: $expected_plugin_file"
    fi

    # Clean temporary resources after successful build
    clean_idea_resources_postbuild
}

# Build lite plugin (without builtin Node.js)
build_lite_plugin() {
    if [[ "$SKIP_IDEA_BUILD" == "true" ]]; then
        log_info "Skipping IDEA lite plugin build"
        return 0
    fi

    log_step "Building IDEA lite plugin (without builtin Node.js)..."

    cd "$IDEA_BUILD_DIR"

    # Check for Gradle build files
    if [[ ! -f "build.gradle" && ! -f "build.gradle.kts" ]]; then
        die "No Gradle build file found in IDEA directory"
    fi

    # Mark as lite build for other functions (must be set before calling prepare function)
    LITE_BUILD=true

    # Prepare online setup scripts for lite build
    # This will detect LITE_BUILD=true and copy setup-node-online.sh/bat instead of downloading Node.js
    prepare_builtin_nodejs_prebuild_for_platform "lite"

    # Use gradlew if available, otherwise use system gradle
    local gradle_cmd="gradle"
    if [[ -f "./gradlew" ]]; then
        gradle_cmd="./gradlew"
        chmod +x "./gradlew"
    fi

    # Set debugMode based on BUILD_MODE
    local debug_mode="none"
    if [[ "$BUILD_MODE" == "$BUILD_MODE_RELEASE" ]]; then
        debug_mode="release"
        log_info "Building IDEA lite plugin in release mode (debugMode=release)"
    elif [[ "$BUILD_MODE" == "$BUILD_MODE_DEBUG" ]]; then
        debug_mode="idea"
        log_info "Building IDEA lite plugin in debug mode (debugMode=idea)"
    fi

    # Set gradle properties for lite build
    # Note: lite build doesn't use targetPlatform parameter since it's platform-agnostic
    local gradle_props="-PdebugMode=$debug_mode -PtargetPlatform=lite"
    local expected_plugin_file
    expected_plugin_file="$(get_expected_idea_plugin_archive_path "lite")"
    ensure_dir "$(dirname "$expected_plugin_file")"
    if [[ -f "$expected_plugin_file" ]]; then
        remove_file "$expected_plugin_file"
    fi

    # Build plugin with lite-specific properties
    execute_cmd "$gradle_cmd $gradle_props buildPlugin --info" "IDEA lite plugin build"

    if [[ -f "$expected_plugin_file" ]]; then
        log_success "IDEA lite plugin built: $expected_plugin_file"
        export IDEA_LITE_PLUGIN_FILE="$expected_plugin_file"
    else
        die "Expected IDEA lite plugin archive was not generated: $expected_plugin_file"
    fi

    # Clean temporary resources after successful build
    clean_idea_resources_postbuild

    # Reset lite build flag
    LITE_BUILD=false
}

# Clean build artifacts
clean_build() {
    log_step "Cleaning build artifacts..."
    
    # Clean VSCode build
    if [[ -d "$PLUGIN_BUILD_DIR" ]]; then
        cd "$PLUGIN_BUILD_DIR"
        [[ -d "bin" ]] && remove_dir "bin"
        [[ -d "src/dist" ]] && remove_dir "src/dist"
        [[ -d "node_modules" ]] && remove_dir "node_modules"
    fi
    
    # Clean base build
    if [[ -d "$BASE_BUILD_DIR" ]]; then
        cd "$BASE_BUILD_DIR"
        [[ -d "dist" ]] && remove_dir "dist"
        [[ -d "node_modules" ]] && remove_dir "node_modules"
    fi
    
    # Clean IDEA build
    if [[ -d "$IDEA_BUILD_DIR" ]]; then
        cd "$IDEA_BUILD_DIR"
        [[ -d "build" ]] && remove_dir "build"
        [[ -d "$VSCODE_PLUGIN_TARGET_DIR" ]] && remove_dir "$VSCODE_PLUGIN_TARGET_DIR"
        # Clean builtin Node.js from resources directory
        [[ -d "$BUILTIN_NODEJS_DIR" ]] && remove_dir "$BUILTIN_NODEJS_DIR"
        # Clean asset files (setup scripts) from resources directory
        # This includes both local and online setup scripts
        local scripts_resource_dir="$IDEA_BUILD_DIR/src/main/resources/scripts"
        if [[ -d "$scripts_resource_dir" ]]; then
            [[ -f "$scripts_resource_dir/setup-node.sh" ]] && remove_file "$scripts_resource_dir/setup-node.sh"
            [[ -f "$scripts_resource_dir/setup-node.bat" ]] && remove_file "$scripts_resource_dir/setup-node.bat"
            [[ -f "$scripts_resource_dir/setup-node-online.sh" ]] && remove_file "$scripts_resource_dir/setup-node-online.sh"
            [[ -f "$scripts_resource_dir/setup-node-online.bat" ]] && remove_file "$scripts_resource_dir/setup-node-online.bat"
            [[ -f "$scripts_resource_dir/NODE_SETUP_README.md" ]] && remove_file "$scripts_resource_dir/NODE_SETUP_README.md"
            # Remove directory if empty
            if [[ -z "$(ls -A "$scripts_resource_dir" 2>/dev/null)" ]]; then
                remove_dir "$scripts_resource_dir"
            fi
        fi
    fi
    
    # Clean debug resources
    [[ -d "$PROJECT_ROOT/debug-resources" ]] && remove_dir "$PROJECT_ROOT/debug-resources"
    
    # Clean temp directories
    find /tmp -name "${TEMP_PREFIX}*" -type d -exec rm -rf {} + 2>/dev/null || true
    
    log_success "Build artifacts cleaned"
}

# Platform utility functions
get_current_platform() {
    local platform=""
    local os_name=$(uname -s | tr '[:upper:]' '[:lower:]')
    local arch_name=$(uname -m | tr '[:upper:]' '[:lower:]')

    case "$os_name" in
        mingw*|msys*|cygwin*)
            platform="windows-x64"
            ;;
        linux*)
            if [[ "$arch_name" == "x86_64" ]]; then
                platform="linux-x64"
            fi
            ;;
        darwin*)
            if [[ "$arch_name" == "arm64" ]]; then
                platform="macos-arm64"
            elif [[ "$arch_name" == "x86_64" ]]; then
                platform="macos-x64"
            fi
            ;;
    esac

    echo "$platform"
}

get_platform_identifier() {
    local platform="$1"
    case "$platform" in
        "windows-x64")
            echo "windows-x64"
            ;;
        "linux-x64")
            echo "linux-x64"
            ;;
        "macos-x64")
            echo "macos-x64"
            ;;
        "macos-arm64")
            echo "macos-arm64"
            ;;
        *)
            echo "unknown"
            ;;
    esac
}

get_plugin_name_with_platform() {
    local base_name="${1:-costrict}"
    local version="$2"
    local platform="$3"
    local platform_identifier=$(get_platform_identifier "$platform")

    if [[ -z "$version" ]]; then
        echo "${base_name}"
    elif [[ -n "$platform_identifier" && "$platform_identifier" != "unknown" ]]; then
        echo "${base_name}-${version}-${platform_identifier}"
    else
        echo "${base_name}-${version}"
    fi
}

get_idea_plugin_project_name() {
    local settings_file=""
    local project_name=""

    if [[ -f "$IDEA_BUILD_DIR/settings.gradle.kts" ]]; then
        settings_file="$IDEA_BUILD_DIR/settings.gradle.kts"
    elif [[ -f "$IDEA_BUILD_DIR/settings.gradle" ]]; then
        settings_file="$IDEA_BUILD_DIR/settings.gradle"
    fi

    if [[ -n "$settings_file" ]]; then
        project_name="$(sed -n 's/^[[:space:]]*rootProject\.name[[:space:]]*=[[:space:]]*"\([^"]*\)".*$/\1/p' "$settings_file" | head -n 1 | tr -d '\r')"
    fi

    if [[ -z "$project_name" ]]; then
        die "Unable to determine IDEA plugin project name from settings.gradle(.kts)"
    fi

    echo "$project_name"
}

get_idea_plugin_version() {
    local gradle_properties_file="$IDEA_BUILD_DIR/gradle.properties"
    local package_json_file="$PROJECT_ROOT/deps/costrict/src/package.json"
    local plugin_version=""

    if [[ -f "$gradle_properties_file" ]]; then
        plugin_version="$(sed -n 's/^[[:space:]]*pluginVersion[[:space:]]*=[[:space:]]*\(.*\)$/\1/p' "$gradle_properties_file" | head -n 1 | tr -d '\r')"
    fi

    if [[ -z "$plugin_version" && -f "$package_json_file" ]]; then
        plugin_version="$(sed -n 's/^[[:space:]]*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*$/\1/p' "$package_json_file" | head -n 1 | tr -d '\r')"
    fi

    if [[ -z "$plugin_version" ]]; then
        die "Unable to determine IDEA plugin version from gradle.properties or package.json"
    fi

    echo "$plugin_version"
}

get_expected_idea_plugin_archive_path() {
    local target_platform="${1:-all}"
    local project_name
    project_name="$(get_idea_plugin_project_name)"
    local plugin_version
    plugin_version="$(get_idea_plugin_version)"
    local archive_name
    archive_name="$(get_plugin_name_with_platform "$project_name" "$plugin_version" "$target_platform")"

    echo "$IDEA_BUILD_DIR/build/distributions/${archive_name}.zip"
}


get_supported_platforms() {
    # Only platforms with pre-built Node.js binaries for full plugin
    # Lite plugin can support more platforms via online download
    echo "windows-x64 linux-x64 macos-arm64"
}

validate_platform() {
    local platform="$1"
    local supported_platforms=$(get_supported_platforms)

    for supported in $supported_platforms; do
        if [[ "$platform" == "$supported" ]]; then
            return 0
        fi
    done

    return 1
}

# Cleanup build environment
cleanup_build() {
    if [[ -n "${BUILD_TEMP_DIR:-}" && -d "${BUILD_TEMP_DIR:-}" ]]; then
        remove_dir "$BUILD_TEMP_DIR"
    fi
}

# Set up cleanup trap
trap cleanup_build EXIT