// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.sina.weibo.agent.core

import com.google.gson.Gson
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo
import com.sina.weibo.agent.plugin.DEBUG_MODE
import com.sina.weibo.agent.plugin.EnvWhitelist
import com.sina.weibo.agent.plugin.WecoderPluginService
import com.sina.weibo.agent.util.PluginResourceUtil
import com.sina.weibo.agent.util.ProxyConfigUtil
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import com.sina.weibo.agent.util.ExtensionUtils
import com.sina.weibo.agent.util.PluginConstants
import com.sina.weibo.agent.util.NotificationUtil
import com.sina.weibo.agent.util.NodeVersionUtil
import com.sina.weibo.agent.util.NodeVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.io.path.exists

/**
 * Extension process manager
 * Responsible for starting and managing extension processes
 */
class ExtensionProcessManager : Disposable {
    companion object {
        // Node modules path
        private const val NODE_MODULES_PATH = PluginConstants.NODE_MODULES_PATH
        
        // Extension process entry file
        private const val EXTENSION_ENTRY_FILE = PluginConstants.EXTENSION_ENTRY_FILE
        
        // Runtime directory
        private const val RUNTIME_DIR = PluginConstants.RUNTIME_DIR
        
        // Plugin ID
        private const val PLUGIN_ID = PluginConstants.PLUGIN_ID
        
        // Minimum required Node.js version
        private val MIN_REQUIRED_NODE_VERSION = NodeVersion(20, 6, 0, "20.6.0")
    }
    
    private val LOG = Logger.getInstance(ExtensionProcessManager::class.java)
    
    // Extension process
    private var process: Process? = null
    
    // Process monitor thread
    private var monitorThread: Thread? = null
    
    // Whether running
    @Volatile
    private var isRunning = false
    
    enum class StartFailureReason {
        NONE,
        NODE_NOT_FOUND,
        NODE_SETUP_FAILED,
        NODE_VERSION_TOO_LOW,
        EXTENSION_ENTRY_MISSING,
        NODE_MODULES_MISSING,
        PROCESS_START_EXCEPTION
    }
    
    data class StartFailure(val reason: StartFailureReason, val message: String)
    
    @Volatile
    private var lastStartFailure: StartFailure? = null
    
    fun getLastFailure(): StartFailure? = lastStartFailure
    
    private fun recordFailure(reason: StartFailureReason, message: String) {
        lastStartFailure = StartFailure(reason, message)
    }
    
    /**
     * Start extension process
     * @param portOrPath Socket server port (Int) or UDS path (String)
     * @return Whether started successfully
     */
    suspend fun start(portOrPath: Any?): Boolean {
        if (isRunning) {
            LOG.info("Extension process is already running")
            return true
        }
        val isUds = portOrPath is String
        if (!ExtensionUtils.isValidPortOrPath(portOrPath)) {
            LOG.error("Invalid socket info: $portOrPath")
            recordFailure(StartFailureReason.PROCESS_START_EXCEPTION, "Invalid socket info: $portOrPath")
            return false
        }
        lastStartFailure = null
        
        try {
            // Prepare Node.js executable path
            var nodePath = withContext(Dispatchers.IO) { findNodeExecutable() }
            if (nodePath == null) {
                LOG.warn("Node.js not found, attempting to setup...")
                
                // Check if online setup script exists (for lite plugin packages)
                val onlineScriptName = when {
                    SystemInfo.isWindows -> "setup-node-online.bat"
                    SystemInfo.isMac || SystemInfo.isLinux -> "setup-node-online.sh"
                    else -> null
                }
                
                val onlineScriptPath = if (onlineScriptName != null) {
                    PluginResourceUtil.getResourcePath(PLUGIN_ID, "scripts/$onlineScriptName")
                } else {
                    null
                }
                
                // Check if this is a lite package (has online script) or full package (has offline script)
                if (onlineScriptPath != null && File(onlineScriptPath).exists()) {
                    // Lite package: use online installation
                    LOG.info("Online setup script found, this is a lite package. Checking mirror connectivity...")
                    
                    // Show notification that we're checking network
                    NotificationUtil.showInfo(
                        "检查网络连接",
                        "正在检测 Node.js 镜像源连通性..."
                    )
                    
                    // Check mirror connectivity
                    if (withContext(Dispatchers.IO) { checkNodeMirrorConnectivity() }) {
                        LOG.info("Mirror is accessible, attempting online installation...")
                        
                        // Show notification that we're downloading
                        NotificationUtil.showInfo(
                            "下载 Node.js",
                            "正在从镜像源下载 Node.js ${MIN_REQUIRED_NODE_VERSION.original}..."
                        )
                        
                        // Try to run online setup script
                        if (withContext(Dispatchers.IO) { runNodeSetupOnlineScript() }) {
                            LOG.info("Online setup script completed, retrying Node.js detection...")
                            
                            // Successfully installed
                            NotificationUtil.showInfo(
                                "Node.js 安装成功",
                                "Node.js ${MIN_REQUIRED_NODE_VERSION.original} 已成功安装"
                            )
                            
                            // Retry finding Node.js after setup
                            nodePath = withContext(Dispatchers.IO) { findNodeExecutable() }
                            
                            // If still not found, show error
                            if (nodePath == null) {
                                LOG.error("Failed to find Node.js executable even after online setup")
                                
                                recordFailure(
                                    StartFailureReason.NODE_NOT_FOUND,
                                    "Node.js not found after online installation."
                                )
                                NotificationUtil.showError(
                                    "Node.js 安装后未找到",
                                    "Node.js 安装完成但无法找到可执行文件，请手动安装 Node.js ${MIN_REQUIRED_NODE_VERSION.original} 或更高版本。"
                                )
                                
                                return false
                            }
                        } else {
                            LOG.error("Online setup script failed")
                            
                            recordFailure(
                                StartFailureReason.NODE_SETUP_FAILED,
                                "Failed to download and install Node.js from mirror."
                            )
                            NotificationUtil.showError(
                                "Node.js 下载失败",
                                "无法从镜像源下载 Node.js，请手动安装 Node.js ${MIN_REQUIRED_NODE_VERSION.original} 或更高版本。"
                            )
                            
                            return false
                        }
                    } else {
                        LOG.warn("Mirror is not accessible, cannot perform online installation")
                        
                        recordFailure(
                            StartFailureReason.NODE_NOT_FOUND,
                            "Node.js mirror is not accessible and Node.js is not installed."
                        )
                        NotificationUtil.showError(
                            "网络不可用",
                            "无法连接到 Node.js 镜像源，且本地未安装 Node.js。请检查网络连接或手动安装 Node.js ${MIN_REQUIRED_NODE_VERSION.original} 或更高版本。"
                        )
                        
                        return false
                    }
                } else {
                    // Full package: use existing offline installation logic
                    LOG.warn("Online setup script not found, using offline setup (full package)...")
                    
                    // Show notification that we're attempting to install
                    NotificationUtil.showInfo(
                        "安装 Node.js",
                        "没有找到 NodeJS. 正在安装 Nodejs ${MIN_REQUIRED_NODE_VERSION.original}..."
                    )
                    
                    // Try to run offline setup script
                    if (withContext(Dispatchers.IO) { runNodeSetupScript() }) {
                        LOG.info("Setup script completed, retrying Node.js detection...")
                        // Successfully installed
                        NotificationUtil.showInfo(
                            "Node.js setup completed",
                            "NodeJS 安装成功"
                        )
                        // Retry finding Node.js after setup
                        nodePath = withContext(Dispatchers.IO) { findNodeExecutable() }
                    }
                    
                    // If still not found, show error
                    if (nodePath == null) {
                        LOG.error("Failed to find Node.js executable even after running setup script")
                        
                        recordFailure(
                            StartFailureReason.NODE_NOT_FOUND,
                            "Failed to locate Node.js. Please install version $MIN_REQUIRED_NODE_VERSION or higher."
                        )
                        NotificationUtil.showError(
                            "Node.js environment missing",
                            "Failed to setup Node.js automatically. Please install Node.js manually and try again. Recommended version: $MIN_REQUIRED_NODE_VERSION or higher."
                        )
                        
                        return false
                    }
                }
            }
            
            // Check Node.js version
            val nodeVersion = NodeVersionUtil.getNodeVersion(nodePath)
            if (!NodeVersionUtil.isVersionSupported(nodeVersion, MIN_REQUIRED_NODE_VERSION)) {
                LOG.error("Node.js version is not supported: $nodeVersion, required: $MIN_REQUIRED_NODE_VERSION")

                recordFailure(
                    StartFailureReason.NODE_VERSION_TOO_LOW,
                    "Detected Node.js $nodeVersion at $nodePath. Minimum required version is $MIN_REQUIRED_NODE_VERSION."
                )
                showBlockingErrorDialog(
                    "Node.js 版本过低",
                    "当前 Node.js ($nodePath) 版本为 $nodeVersion, CoStrict 支持的最低版本为 $MIN_REQUIRED_NODE_VERSION, 请升级到 $MIN_REQUIRED_NODE_VERSION 或更高版本以获得更好的兼容性."
                )
                
                return false
            }
            
            // Prepare extension process entry file path
            val extensionPath = findExtensionEntryFile()
            if (extensionPath == null) {
                LOG.error("Failed to find extension entry file")
                recordFailure(
                    StartFailureReason.EXTENSION_ENTRY_MISSING,
                    "Extension entry file ($EXTENSION_ENTRY_FILE) could not be located."
                )
                return false
            }

            val nodeModulesPath = findNodeModulesPath()
            if (nodeModulesPath == null) {
                LOG.error("Failed to find node_modules directory")
                recordFailure(
                    StartFailureReason.NODE_MODULES_MISSING,
                    "Node modules directory ($NODE_MODULES_PATH) is missing or unreadable."
                )
                return false
            }
            
            LOG.info("Starting extension process with node: $nodePath, entry: $extensionPath")

            val envVars = HashMap<String, String>(System.getenv())
            
            // Load and apply environment variables from idea-shell-env.json
            val ideaShellEnv = loadIdeaShellEnv()
            if (ideaShellEnv.isNotEmpty()) {
                // Filter environment variables using whitelist
                val filteredEnv = EnvWhitelist.filter(ideaShellEnv)
                LOG.info("Loaded ${filteredEnv.size} environment variables from idea-shell-env.json (after filtering)")
                
                // Add filtered environment variables to process environment
                filteredEnv.forEach { (key, value) ->
                    // Don't override PATH here, we'll handle it separately
                    if (!key.equals("PATH", ignoreCase = true)) {
                        envVars[key] = value
                        LOG.info("Added env var from idea-shell-env.json: $key")
                    }
                }
            } else {
                LOG.info("No environment variables loaded from idea-shell-env.json")
            }
            
            // Build complete PATH
            envVars["PATH"] = buildEnhancedPath(envVars, nodePath)
            LOG.info("Enhanced PATH for ${SystemInfo.getOsNameAndVersion()}: ${envVars["PATH"]}")
            
            // Add key environment variables
            if (isUds) {
                envVars["VSCODE_EXTHOST_IPC_HOOK"] = portOrPath.toString()
            }else{
                envVars["VSCODE_EXTHOST_WILL_SEND_SOCKET"] = "1"
                envVars["VSCODE_EXTHOST_SOCKET_HOST"] = "127.0.0.1"
                envVars["VSCODE_EXTHOST_SOCKET_PORT"] = portOrPath.toString()
            }

            // Build command line arguments
            val commandArgs = mutableListOf(
                nodePath,
                "--experimental-global-webcrypto",
                "--no-deprecation",
//                "--trace-uncaught",
                extensionPath,
                "--vscode-socket-port=${envVars["VSCODE_EXTHOST_SOCKET_PORT"]}",
                "--vscode-socket-host=${envVars["VSCODE_EXTHOST_SOCKET_HOST"]}",
                "--vscode-will-send-socket=${envVars["VSCODE_EXTHOST_WILL_SEND_SOCKET"]}"
            )
            
            // Get and set proxy configuration
            try {
                val proxyEnvVars = ProxyConfigUtil.getProxyEnvVarsForProcessStart()
                
                // Add proxy environment variables
                envVars.putAll(proxyEnvVars)
                
                // Log proxy configuration if used
                if (proxyEnvVars.isNotEmpty()) {
                    LOG.info("Applied proxy configuration for process startup")
                }
            } catch (e: Exception) {
                LOG.warn("Failed to configure proxy settings", e)
            }
            
            // Create process builder
            val builder = ProcessBuilder(commandArgs)

            // Print environment variables
            LOG.info("Environment variables:")
            envVars.forEach { (key, value) ->
                LOG.info("  $key = $value")
            }
            builder.environment().putAll(envVars)

            // Redirect error stream to standard output
            builder.redirectErrorStream(true)
            
            // Start process
            process = builder.start()
            
            // Start monitor thread
            monitorThread = Thread {
                monitorProcess()
            }.apply {
                name = "ExtensionProcessMonitor"
                isDaemon = true
                start()
            }
            
            isRunning = true
            LOG.info("Extension process started")
            lastStartFailure = null
            return true
        } catch (e: Exception) {
            LOG.error("Failed to start extension process", e)
            recordFailure(
                StartFailureReason.PROCESS_START_EXCEPTION,
                "Unexpected error starting extension process: ${e.message ?: "unknown"}"
            )
            stopInternal()
            return false
        }
    }
    
    /**
     * Monitor extension process
     */
    private fun monitorProcess() {
        val proc = process ?: return
        
        try {
            // Start log reading thread
            val logThread = Thread {
                proc.inputStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        LOG.info("Extension process: $line")
                    }
                }
            }
            logThread.name = "ExtensionProcessLogger"
            logThread.isDaemon = true
            logThread.start()
            
            // Wait for process to end
            try {
                val exitCode = proc.waitFor()
                LOG.info("Extension process exited with code: $exitCode")
            } catch (e: InterruptedException) {
                LOG.info("Process monitor interrupted")
            }
            
            // Ensure log thread ends
            logThread.interrupt()
            try {
                logThread.join(1000)
            } catch (e: InterruptedException) {
                // Ignore
            }
        } catch (e: Exception) {
            LOG.error("Error monitoring extension process", e)
        } finally {
            synchronized(this) {
                if (process === proc) {
                    isRunning = false
                    process = null
                }
            }
        }
    }
    
    /**
     * Stop extension process
     */
    fun stop() {
        if (!isRunning) {
            return
        }
        
        stopInternal()
    }
    
    /**
     * Internal stop logic
     */
    private fun stopInternal() {
        LOG.info("Stopping extension process")
        
        val proc = process
        if (proc != null) {
            try {
                // Try to close normally
                if (proc.isAlive) {
                    proc.destroy()
                    
                    // Wait for process to end
                    if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                        // Force terminate
                        proc.destroyForcibly()
                        proc.waitFor(2, TimeUnit.SECONDS)
                    }
                }
            } catch (e: Exception) {
                LOG.error("Error stopping extension process", e)
            }
        }
        
        // Interrupt monitor thread
        monitorThread?.interrupt()
        try {
            monitorThread?.join(1000)
        } catch (e: InterruptedException) {
            // Ignore
        }
        
        process = null
        monitorThread = null
        isRunning = false
        
        LOG.info("Extension process stopped")
    }
    
    private fun showBlockingErrorDialog(title: String, message: String) {
        val application = ApplicationManager.getApplication()
        if (application == null || application.isDisposed || application.isHeadlessEnvironment) {
            LOG.warn("Application context unavailable, fallback to notification: $title")
            NotificationUtil.showError(title, message)
            return
        }

        val dialogTask = Runnable {
            Messages.showMessageDialog(
                /* project = */ null,
                message,
                title,
                Messages.getErrorIcon()
            )
        }

        if (application.isDispatchThread) {
            dialogTask.run()
        } else {
            application.invokeAndWait(dialogTask, ModalityState.any())
        }
    }
    
    /**
     * Find Node.js executable
     * This function only searches for Node.js, does not install it
     */
    private fun findNodeExecutable(): String? {
        LOG.info("Starting Node.js detection...")
        
        // First check built-in Node.js in plugin resources
        val resourcesPath = PluginResourceUtil.getResourcePath(PLUGIN_ID, NODE_MODULES_PATH)
        if (resourcesPath != null) {
            val resourceDir = File(resourcesPath)
            LOG.info("Checking plugin resources directory: ${resourceDir.absolutePath}")
            if (resourceDir.exists() && resourceDir.isDirectory) {
                val nodeBin = if (SystemInfo.isWindows) {
                    File(resourceDir, "node.exe")
                } else {
                    File(resourceDir, ".bin/node")
                }
                
                LOG.info("Checking for built-in Node.js at: ${nodeBin.absolutePath}, exists: ${nodeBin.exists()}, canExecute: ${nodeBin.canExecute()}")
                if (nodeBin.exists() && nodeBin.canExecute()) {
                    LOG.info("Found built-in Node.js: ${nodeBin.absolutePath}")
                    return nodeBin.absolutePath
                }
            }
        }
        
        // Check Node.js installed by setup script in user's local directory
        val localNodePath = if (SystemInfo.isWindows) {
            // Windows: %LOCALAPPDATA%\nodejs\node.exe
            val localAppData = System.getenv("LOCALAPPDATA") ?: "${System.getProperty("user.home")}\\AppData\\Local"
            LOG.info("Windows detected. LOCALAPPDATA: $localAppData")
            File(localAppData, "nodejs\\node.exe")
        } else {
            // Linux/Mac: $HOME/.local/share/nodejs/bin/node
            val userHome = System.getProperty("user.home")
            LOG.info("Unix-like system detected. HOME: $userHome")
            File(userHome, ".local/share/nodejs/bin/node")
        }
        
        LOG.info("Checking for setup-installed Node.js at: ${localNodePath.absolutePath}")
        LOG.info("File exists: ${localNodePath.exists()}, isFile: ${localNodePath.isFile}, canExecute: ${localNodePath.canExecute()}")
        
        // On Windows, .exe files are executable if they exist; on Unix, check canExecute()
        val isExecutable = if (SystemInfo.isWindows) {
            localNodePath.exists() && localNodePath.isFile
        } else {
            localNodePath.exists() && localNodePath.canExecute()
        }
        
        if (isExecutable) {
            LOG.info("Found setup-installed Node.js: ${localNodePath.absolutePath}")
            return localNodePath.absolutePath
        }
        
        // Then check system path
        LOG.info("Checking system PATH for Node.js...")
        val systemNode = findExecutableInPath("node")
        if (systemNode != null) {
            LOG.info("Found system Node.js: $systemNode")
            return systemNode
        }
        
        LOG.warn("Node.js not found in built-in, user-local, or system path")
        return null
    }
    
    /**
     * Run Node.js setup script to install builtin Node.js
     * @return true if script executed successfully, false otherwise
     */
    private fun runNodeSetupScript(): Boolean {
        try {
            // Determine script name based on OS
            val scriptName = when {
                SystemInfo.isWindows -> "setup-node.bat"
                SystemInfo.isMac || SystemInfo.isLinux -> "setup-node.sh"
                else -> {
                    LOG.error("Unsupported operating system for Node.js setup")
                    return false
                }
            }
            
            // Get script path from plugin resources
            val scriptPath = PluginResourceUtil.getResourcePath(PLUGIN_ID, "scripts/$scriptName")
            if (scriptPath == null) {
                LOG.error("Setup script not found in plugin resources: scripts/$scriptName")
                return false
            }
            
            val scriptFile = File(scriptPath)
            if (!scriptFile.exists()) {
                LOG.error("Setup script file does not exist: $scriptPath")
                return false
            }
            
            // Get builtin-nodejs directory path
            val builtinNodejsPath = PluginResourceUtil.getResourcePath(PLUGIN_ID, "builtin-nodejs")
            if (builtinNodejsPath == null) {
                LOG.error("builtin-nodejs directory not found in plugin resources")
                return false
            }
            
            // Get the parent directory (resources root) as working directory
            val resourcesRoot = scriptFile.parentFile?.parentFile
            if (resourcesRoot == null || !resourcesRoot.exists()) {
                LOG.error("Cannot determine plugin resources root directory")
                return false
            }
            
            // Make script executable on Unix-like systems
            if (!SystemInfo.isWindows) {
                scriptFile.setExecutable(true, false)
            }
            
            LOG.info("Running Node.js setup script: $scriptPath")
            LOG.info("Working directory: ${resourcesRoot.absolutePath}")
            LOG.info("Builtin Node.js directory: $builtinNodejsPath")
            
            // Build command based on OS
            val command = if (SystemInfo.isWindows) {
                listOf("cmd.exe", "/c", scriptPath)
            } else {
                listOf("/bin/bash", scriptPath)
            }
            
            // Execute script with working directory set to resources root
            val processBuilder = ProcessBuilder(command)
            processBuilder.directory(scriptFile.parentFile) // Set working directory to scripts/
            processBuilder.redirectErrorStream(true)
            
            val process = processBuilder.start()
            
            // Read and log output
            val reader = process.inputStream.bufferedReader()
            reader.useLines { lines ->
                lines.forEach { line ->
                    LOG.info("[Setup Script] $line")
                }
            }
            
            // Wait for script to complete
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                LOG.info("Node.js setup script completed successfully")
                return true
            } else {
                LOG.error("Node.js setup script failed with exit code: $exitCode")
                return false
            }
        } catch (e: Exception) {
            LOG.error("Failed to run Node.js setup script", e)
            return false
        }
    }
    
    /**
     * Check if Node.js mirror is accessible via HTTP HEAD request
     * @return true if mirror is accessible, false otherwise
     */
    private fun checkNodeMirrorConnectivity(): Boolean {
        val mirrorUrl = "https://registry.npmmirror.com/-/binary/node/v${MIN_REQUIRED_NODE_VERSION.original}/"
        
        try {
            LOG.info("Checking Node.js mirror connectivity: $mirrorUrl")
            
            val url = java.net.URL(mirrorUrl)
            val connection = url.openConnection() as java.net.HttpURLConnection
            
            // Apply proxy configuration
            try {
                val proxyConfig = ProxyConfigUtil.getProxyEnvVarsForProcessStart()
                if (proxyConfig.containsKey("http_proxy") || proxyConfig.containsKey("https_proxy")) {
                    LOG.info("Using proxy configuration for connectivity check")
                }
            } catch (e: Exception) {
                LOG.warn("Failed to apply proxy configuration", e)
            }
            
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 3000 // 3 seconds
            connection.readTimeout = 5000    // 5 seconds
            connection.instanceFollowRedirects = true
            
            val responseCode = connection.responseCode
            connection.disconnect()
            
            val isAccessible = responseCode in 200..399
            LOG.info("Mirror connectivity check result: $responseCode, accessible: $isAccessible")
            
            return isAccessible
        } catch (e: java.net.SocketTimeoutException) {
            LOG.warn("Mirror connectivity check timeout", e)
            return false
        } catch (e: Exception) {
            LOG.warn("Mirror connectivity check failed", e)
            return false
        }
    }
    
    /**
     * Run Node.js online setup script to download and install Node.js
     * @return true if script executed successfully, false otherwise
     */
    private fun runNodeSetupOnlineScript(): Boolean {
        try {
            // Determine script name based on OS
            val scriptName = when {
                SystemInfo.isWindows -> "setup-node-online.bat"
                SystemInfo.isMac || SystemInfo.isLinux -> "setup-node-online.sh"
                else -> {
                    LOG.error("Unsupported operating system for Node.js online setup")
                    return false
                }
            }
            
            // Get script path from plugin resources
            val scriptPath = PluginResourceUtil.getResourcePath(PLUGIN_ID, "scripts/$scriptName")
            if (scriptPath == null) {
                LOG.error("Online setup script not found in plugin resources: scripts/$scriptName")
                return false
            }
            
            val scriptFile = File(scriptPath)
            if (!scriptFile.exists()) {
                LOG.error("Online setup script file does not exist: $scriptPath")
                return false
            }
            
            // Make script executable on Unix-like systems
            if (!SystemInfo.isWindows) {
                scriptFile.setExecutable(true, false)
            }
            
            LOG.info("Running Node.js online setup script: $scriptPath")
            
            // Build command based on OS
            val command = if (SystemInfo.isWindows) {
                listOf("cmd.exe", "/c", scriptPath)
            } else {
                listOf("/bin/bash", scriptPath)
            }
            
            // Execute script
            val processBuilder = ProcessBuilder(command)
            processBuilder.directory(scriptFile.parentFile)
            processBuilder.redirectErrorStream(true)
            
            val process = processBuilder.start()
            
            // Read and log output
            val reader = process.inputStream.bufferedReader()
            reader.useLines { lines ->
                lines.forEach { line ->
                    LOG.info("[Online Setup Script] $line")
                }
            }
            
            // Wait for script to complete
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                LOG.info("Node.js online setup script completed successfully")
                return true
            } else {
                LOG.error("Node.js online setup script failed with exit code: $exitCode")
                return false
            }
        } catch (e: Exception) {
            LOG.error("Failed to run Node.js online setup script", e)
            return false
        }
    }
    
    /**
     * Find executable in system path
     */
    private fun findExecutableInPath(name: String): String? {
        val nodePath = PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS("node")?.absolutePath
        LOG.info("System Node path: $nodePath")
        return nodePath
    }
    
    /**
     * Find extension process entry file
     * @param projectBasePath Current project root path
     */
    fun findExtensionEntryFile(): String? {
        // In debug mode, directly return debug-resources path
        if (WecoderPluginService.getDebugMode() != DEBUG_MODE.NONE) {
            val debugEntry = java.nio.file.Paths.get(WecoderPluginService.getDebugResource(), RUNTIME_DIR, EXTENSION_ENTRY_FILE).normalize().toFile()
            if (debugEntry.exists() && debugEntry.isFile) {
                LOG.info("[DebugMode] Using debug entry file: ${debugEntry.absolutePath}")
                return debugEntry.absolutePath
            } else {
                LOG.warn("[DebugMode] Debug entry file not found: ${debugEntry.absolutePath}")
            }
        }
        // Normal mode
        val resourcesPath = com.sina.weibo.agent.util.PluginResourceUtil.getResourcePath(PLUGIN_ID, "$RUNTIME_DIR/$EXTENSION_ENTRY_FILE")
        if (resourcesPath != null) {
            val resource = java.io.File(resourcesPath)
            if (resource.exists() && resource.isFile) {
                return resourcesPath
            }
        }
        return null
    }
    

    
    /**
     * Find node_modules path
     */
    private fun findNodeModulesPath(): String? {
        val nodePath = PluginResourceUtil.getResourcePath(PLUGIN_ID, NODE_MODULES_PATH)
        if (nodePath != null) {
            val nodeDir = File(nodePath)
            if (nodeDir.exists() && nodeDir.isDirectory) {
                return nodeDir.absolutePath
            }
        }
        return null
    }
    
    /**
     * Build enhanced PATH environment variable
     * @param envVars Environment variable map
     * @param nodePath Node.js executable path
     * @return Enhanced PATH
     */
    private fun buildEnhancedPath(envVars: MutableMap<String, String>, nodePath: String): String {
        // Find current PATH value (Path on Windows)
        val currentPath = envVars.filterKeys { it.equals("PATH", ignoreCase = true) }
            .values.firstOrNull() ?: ""
        
        val pathBuilder = mutableListOf<String>()

        // Simplify: add Node directory to PATH head (npx usually in same dir as node)
        val nodeDir = File(nodePath).parentFile?.absolutePath
        if (nodeDir != null && !currentPath.contains(nodeDir)) {
            pathBuilder.add(nodeDir)
        }

        // Add common paths according to OS
        val commonDevPaths = when {
            SystemInfo.isMac -> listOf(
                "/opt/homebrew/bin",
                "/opt/homebrew/sbin",
                "/usr/local/bin",
                "/usr/local/sbin",
                "${System.getProperty("user.home")}/.local/bin"
            )
            SystemInfo.isWindows -> listOf(
                "C:\\Windows\\System32",
                "C:\\Windows\\SysWOW64",
                "C:\\Windows",
                "C:\\Windows\\System32\\WindowsPowerShell\\v1.0",
                "C:\\Program Files\\PowerShell\\7",
                "C:\\Program Files (x86)\\PowerShell\\7"
            )
            else -> emptyList()
        }

        // Add existing paths
        commonDevPaths.forEach { path ->
            if (File(path).exists() && !currentPath.contains(path)) {
                pathBuilder.add(path)
                LOG.info("Add path to PATH: $path")
            } else if (!File(path).exists()) {
                LOG.warn("Path does not exist, skip: $path")
            }
        }

        // Keep original PATH
        if (currentPath.isNotEmpty()) {
            pathBuilder.add(currentPath)
        }

        return pathBuilder.joinToString(File.pathSeparator)
    }
    
    /**
     * Load environment variables from idea-shell-env.json
     * @return Map of environment variables
     */
    private fun loadIdeaShellEnv(): Map<String, String> {
        try {
            val envFilePath = resolveIdeaShellEnvPath()
            if (envFilePath == null || !envFilePath.exists()) {
                LOG.info("idea-shell-env.json not found at: $envFilePath")
                return emptyMap()
            }
            
            LOG.info("Loading environment variables from: $envFilePath")
            val jsonContent = Files.readString(envFilePath, StandardCharsets.UTF_8)
            val gson = Gson()
            @Suppress("UNCHECKED_CAST")
            val envMap = gson.fromJson(jsonContent, Map::class.java) as? Map<String, String> ?: emptyMap()
            
            LOG.info("Loaded ${envMap.size} environment variables from idea-shell-env.json")
            return envMap
        } catch (e: Exception) {
            LOG.warn("Failed to load idea-shell-env.json", e)
            return emptyMap()
        }
    }
    
    /**
     * Resolve the path to idea-shell-env.json based on platform
     * @return Path to idea-shell-env.json or null if cannot be determined
     */
    private fun resolveIdeaShellEnvPath(): Path? {
        val filename = "idea-shell-env.json"
        val osName = System.getProperty("os.name").lowercase()
        
        return when {
            osName.contains("win") -> {
                // Windows: %LOCALAPPDATA%/idea-shell-env.json
                val localAppData = System.getenv("LOCALAPPDATA")
                if (localAppData != null) Paths.get(localAppData, filename) else null
            }
            osName.contains("mac") || osName.contains("darwin") -> {
                // macOS: ~/Library/Caches/idea-shell-env.json
                val home = System.getProperty("user.home")
                Paths.get(home, "Library", "Caches", filename)
            }
            else -> {
                // Linux: ~/.cache/idea-shell-env.json
                val home = System.getProperty("user.home")
                Paths.get(home, ".cache", filename)
            }
        }
    }
    
    /**
     * Whether running
     */
    fun isRunning(): Boolean {
        return isRunning && process?.isAlive == true
    }
    
    override fun dispose() {
        stop()
    }
}
