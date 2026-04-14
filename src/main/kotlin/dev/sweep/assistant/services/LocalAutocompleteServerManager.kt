package dev.sweep.assistant.services

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import dev.sweep.assistant.agent.tools.TerminalApiWrapper
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.utils.SweepConstants
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import kotlinx.coroutines.*
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

@Service(Service.Level.APP)
class LocalAutocompleteServerManager : Disposable {
    companion object {
        private val logger = Logger.getInstance(LocalAutocompleteServerManager::class.java)
        private const val DEFAULT_PORT = 8081
        private const val HEALTH_CHECK_TIMEOUT_MS = 3000L
        private const val SERVER_START_TIMEOUT_MS = 30000L
        private const val SERVER_POLL_INTERVAL_MS = 500L
        private const val TERMINAL_TAB_NAME = "Sweep Autocomplete Server"

        fun getInstance(): LocalAutocompleteServerManager =
            ApplicationManager.getApplication().getService(LocalAutocompleteServerManager::class.java)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverProcess: Process? = null
    private var consecutiveFailures = 0
    private var lastRestartTime = 0L
    private val RESTART_THRESHOLD = 3
    private val RESTART_COOLDOWN_MS = 60_000L // Don't restart more than once per minute

    @Volatile
    private var isStarting = false

    private val httpClient =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofMillis(HEALTH_CHECK_TIMEOUT_MS))
            .build()

    private fun getPort(): Int =
        try {
            SweepSettings.getInstance().autocompleteLocalPort
        } catch (_: Exception) {
            DEFAULT_PORT
        }

    fun getServerUrl(): String = "http://localhost:${getPort()}"

    fun ensureServerRunning() {
        ensureServerRunning(null)
    }

    fun ensureServerRunning(onStatus: ((String) -> Unit)?) {
        onStatus?.invoke("Checking if server is already running...")
        if (isServerHealthy()) {
            onStatus?.invoke("Server is already running.")
            return
        }
        if (isStarting) {
            onStatus?.invoke("Server is already starting...")
            return
        }
        startServer(onStatus)
    }

    fun isServerHealthy(): Boolean {
        // Use HttpURLConnection — java.net.http.HttpClient has known issues with localhost on macOS
        return try {
            val url = java.net.URL(getServerUrl())
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = HEALTH_CHECK_TIMEOUT_MS.toInt()
                readTimeout = HEALTH_CHECK_TIMEOUT_MS.toInt()
            }
            val status = conn.responseCode
            conn.disconnect()
            status in 200..499
        } catch (e: Exception) {
            false
        }
    }

    @Synchronized
    private fun startServer(onStatus: ((String) -> Unit)? = null) {
        if (isStarting) return
        if (isServerHealthy()) return
        isStarting = true

        try {
            onStatus?.invoke("Looking for uvx on PATH...")
            val uvxPath = resolveUvx()
            if (uvxPath == null) {
                logger.info("uvx not found, attempting to install uv")
                onStatus?.invoke("uvx not found. Installing uv...")
                installUv()
                onStatus?.invoke("Checking for uvx after install...")
                val uvxAfterInstall = resolveUvx()
                if (uvxAfterInstall == null) {
                    val msg = "Failed to find uvx after installing uv. Please install uv manually."
                    onStatus?.invoke(msg)
                    showNotification(
                        "Failed to find uvx after installing uv. Please install uv manually: https://docs.astral.sh/uv/",
                        NotificationType.ERROR,
                    )
                    return
                }
                onStatus?.invoke("Found uvx at $uvxAfterInstall. Starting server...")
                startServerProcess(uvxAfterInstall, onStatus)
            } else {
                onStatus?.invoke("Found uvx at $uvxPath. Starting server...")
                startServerProcess(uvxPath, onStatus)
            }
        } finally {
            isStarting = false
        }
    }

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    /**
     * Resolves the path to a bundled Python package inside the plugin directory.
     * Returns the path if found, null otherwise.
     */
    private fun getBundledPackagePath(packageDirName: String): String? {
        val pluginId = PluginId.getId(SweepConstants.PLUGIN_ID)
        val plugin = PluginManagerCore.getPlugin(pluginId) ?: return null
        val pluginPath = plugin.pluginPath ?: return null
        val pkgPath = pluginPath.resolve(packageDirName)
        return if (pkgPath.toFile().exists()) pkgPath.toString() else null
    }

    private fun buildUvxCommand(uvxPath: String, port: Int): List<String> {
        val useMlx = SweepSettings.getInstance().autocompleteLocalMlx

        if (useMlx) {
            val mlxPath = getBundledPackagePath("sweep-autocomplete-mlx")
            if (mlxPath != null) {
                return listOf(
                    uvxPath,
                    "--from", mlxPath,
                    "sweep-autocomplete-mlx",
                    "--port", port.toString(),
                )
            }
            logger.warn("MLX package not found at plugin path, falling back to default GGUF")
        }

        return if (isWindows) {
            listOf(
                uvxPath,
                "--python", "3.12",
                "--extra-index-url", "https://abetlen.github.io/llama-cpp-python/whl/cpu",
                "sweep-autocomplete",
                "--port", port.toString(),
            )
        } else {
            listOf(uvxPath, "sweep-autocomplete", "--port", port.toString())
        }
    }

    private fun startServerProcess(uvxPath: String, onStatus: ((String) -> Unit)? = null) {
        val port = getPort()
        val command = buildUvxCommand(uvxPath, port)
        val pb = ProcessBuilder(command)

        // Load environment using EnvironmentUtil pattern
        try {
            val env = com.intellij.util.EnvironmentUtil.getEnvironmentMap()
            if (env.isNotEmpty()) {
                pb.environment().apply {
                    clear()
                    putAll(env)
                }
            }
        } catch (_: Throwable) {
            // Fall back to default environment
        }

        // Redirect stdout to /dev/null — the server communicates via HTTP, not stdout.
        // llama_cpp calls print() during generation which causes BrokenPipeError if stdout is a pipe.
        pb.redirectOutput(ProcessBuilder.Redirect.to(File(if (isWindows) "NUL" else "/dev/null")))

        try {
            serverProcess = pb.start()
            logger.info("Started local autocomplete server with: ${command.joinToString(" ")}")

            // Consume stderr in background for logging
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    serverProcess?.errorStream?.bufferedReader()?.useLines { lines ->
                        lines.forEach { line ->
                            logger.info("Local autocomplete server: $line")
                        }
                    }
                } catch (_: Exception) {
                    // Process may have been closed
                }
            }

            // Poll for health check up to 30 seconds
            onStatus?.invoke("Waiting for server to become healthy...")
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < SERVER_START_TIMEOUT_MS) {
                if (isServerHealthy()) {
                    logger.info("Local autocomplete server is healthy")
                    onStatus?.invoke("Server is running on localhost:$port")
                    showNotification("Local autocomplete server started successfully.", NotificationType.INFORMATION)
                    return
                }
                // Check if process died
                serverProcess?.let { proc ->
                    if (!proc.isAlive) {
                        logger.warn("Local autocomplete server process exited with code: ${proc.exitValue()}")
                        val msg = "Server process exited with code ${proc.exitValue()}"
                        onStatus?.invoke(msg)
                        showNotification(
                            "Local autocomplete server failed to start (exit code ${proc.exitValue()}).",
                            NotificationType.ERROR,
                        )
                        serverProcess = null
                        return
                    }
                }
                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                onStatus?.invoke("Waiting for server to become healthy... (${elapsed}s)")
                Thread.sleep(SERVER_POLL_INTERVAL_MS)
            }

            logger.warn("Local autocomplete server did not become healthy within ${SERVER_START_TIMEOUT_MS}ms")
            onStatus?.invoke("Server did not start within 30 seconds.")
            showNotification(
                "Local autocomplete server did not start within 30 seconds.",
                NotificationType.WARNING,
            )
        } catch (e: Exception) {
            logger.warn("Failed to start local autocomplete server: ${e.message}")
            onStatus?.invoke("Failed to start server: ${e.message}")
            showNotification(
                "Failed to start local autocomplete server: ${e.message}",
                NotificationType.ERROR,
            )
        }
    }

    private fun resolveUvx(): String? {
        // Load environment for PATH resolution
        val envPath =
            try {
                val env = com.intellij.util.EnvironmentUtil.getEnvironmentMap()
                if (env.isNotEmpty()) env["PATH"] else System.getenv("PATH")
            } catch (_: Throwable) {
                System.getenv("PATH")
            }

        val exeName = if (isWindows) "uvx.exe" else "uvx"

        // Search PATH
        if (!envPath.isNullOrEmpty()) {
            for (dir in envPath.split(File.pathSeparatorChar)) {
                if (dir.isEmpty()) continue
                val cand = File(dir, exeName)
                if (cand.isFile && cand.canExecute()) {
                    return cand.absolutePath
                }
            }
        }

        // Check common locations
        val home = System.getProperty("user.home")
        val commonLocations =
            listOf(
                "$home/.local/bin/$exeName",
                "$home/.cargo/bin/$exeName",
            )
        for (loc in commonLocations) {
            val f = File(loc)
            if (f.isFile && f.canExecute()) {
                return f.absolutePath
            }
        }

        return null
    }

    private fun installUv() {

        try {
            val process =
                if (isWindows) {
                    ProcessBuilder(
                        "powershell",
                        "-ExecutionPolicy",
                        "ByPass",
                        "-c",
                        "irm https://astral.sh/uv/install.ps1 | iex",
                    ).redirectErrorStream(true).start()
                } else {
                    ProcessBuilder(
                        "/bin/sh",
                        "-c",
                        "curl -LsSf https://astral.sh/uv/install.sh | sh",
                    ).redirectErrorStream(true).start()
                }

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                logger.info("Successfully installed uv")
                showNotification("Successfully installed uv for local autocomplete.", NotificationType.INFORMATION)
            } else {
                logger.warn("Failed to install uv (exit code $exitCode): $output")
                showNotification("Failed to install uv (exit code $exitCode).", NotificationType.ERROR)
            }
        } catch (e: Exception) {
            logger.warn("Error installing uv: ${e.message}")
            showNotification("Error installing uv: ${e.message}", NotificationType.ERROR)
        }
    }

    fun reportSuccess() {
        consecutiveFailures = 0
    }

    fun reportFailure() {
        consecutiveFailures++
        if (consecutiveFailures >= RESTART_THRESHOLD) {
            val timeSinceLastRestart = System.currentTimeMillis() - lastRestartTime
            if (timeSinceLastRestart < RESTART_COOLDOWN_MS) {
                logger.info(
                    "$consecutiveFailures consecutive failures, but last restart was ${timeSinceLastRestart / 1000}s ago " +
                        "(cooldown: ${RESTART_COOLDOWN_MS / 1000}s). Skipping restart.",
                )
                // Reset counter so we don't log this every single request
                consecutiveFailures = 0
                return
            }
            logger.info("$consecutiveFailures consecutive failures, restarting local autocomplete server")
            consecutiveFailures = 0
            restartServer()
        }
    }

    fun restartServer() {
        logger.info("Restarting local autocomplete server")
        lastRestartTime = System.currentTimeMillis()
        stopServer()
        startServer()
    }

    // --- llama-server support for native engine ---

    private fun getSelectedModel(): dev.sweep.assistant.autocomplete.edit.engine.NesModel {
        val modelId = SweepSettings.getInstance().autocompleteLocalModel
        return dev.sweep.assistant.autocomplete.edit.engine.NesModelConfig.getModel(modelId)
    }

    /**
     * Resolve llama-server binary on PATH.
     */
    private fun resolveLlamaServer(): String? {
        val envPath = try {
            val env = com.intellij.util.EnvironmentUtil.getEnvironmentMap()
            if (env.isNotEmpty()) env["PATH"] else System.getenv("PATH")
        } catch (_: Throwable) {
            System.getenv("PATH")
        }

        val exeName = if (isWindows) "llama-server.exe" else "llama-server"
        if (!envPath.isNullOrEmpty()) {
            for (dir in envPath.split(File.pathSeparatorChar)) {
                if (dir.isEmpty()) continue
                val cand = File(dir, exeName)
                if (cand.isFile && cand.canExecute()) return cand.absolutePath
            }
        }

        // Check common locations
        val commonPaths = listOf(
            "/opt/homebrew/bin/llama-server",
            "/usr/local/bin/llama-server",
            System.getProperty("user.home") + "/.local/bin/llama-server",
        )
        for (path in commonPaths) {
            val f = File(path)
            if (f.isFile && f.canExecute()) return f.absolutePath
        }

        return null
    }

    /**
     * Resolve the GGUF model path.
     * Checks: 1) HuggingFace cache, 2) Sweep models cache (~/.cache/sweep/models/).
     * Returns the path to the .gguf file, or null if not cached yet.
     */
    private fun resolveModelPath(): String? {
        val model = getSelectedModel()

        // Check HuggingFace cache
        val hfCacheBase = File(System.getProperty("user.home"), ".cache/huggingface/hub")
        val modelDir = File(hfCacheBase, "models--${model.repo.replace("/", "--")}")
        if (modelDir.isDirectory) {
            val snapshotsDir = File(modelDir, "snapshots")
            if (snapshotsDir.isDirectory) {
                val snapshots = snapshotsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
                for (snapshot in snapshots) {
                    val gguf = File(snapshot, model.filename)
                    if (gguf.isFile) return gguf.absolutePath
                }
            }
        }

        // Check Sweep models cache
        val sweepCache = File(System.getProperty("user.home"), ".cache/sweep/models/${model.filename}")
        if (sweepCache.isFile) return sweepCache.absolutePath

        return null
    }

    private val SWEEP_MODELS_DIR = System.getProperty("user.home") + "/.cache/sweep/models"

    /**
     * Build a shell command to download the model.
     * Tries hf first, falls back to curl.
     */
    private fun buildModelDownloadCommand(): String {
        val model = getSelectedModel()
        val hfCliCmd = "hf download ${model.repo} ${model.filename}"
        val url = "https://huggingface.co/${model.repo}/resolve/main/${model.filename}"
        val destDir = SWEEP_MODELS_DIR
        val destFile = "$destDir/${model.filename}"
        val curlCmd = "mkdir -p $destDir && curl -L -o \"$destFile\" \"$url\""

        // Shell one-liner: try hf, fall back to curl
        return "if command -v hf >/dev/null 2>&1; then $hfCliCmd; else echo 'hf not found, downloading with curl...' && $curlCmd; fi"
    }

    /**
     * Build the llama-server command with speculative decoding flags.
     */
    private fun buildLlamaServerCommand(llamaServerPath: String, modelPath: String, port: Int): List<String> {
        return listOf(
            llamaServerPath,
            "-m", modelPath,
            "--port", port.toString(),
            "-ngl", "-1",
            "--flash-attn", "auto",
            "--spec-type", "ngram-mod",
            "--spec-ngram-size-n", "24",
            "--draft-min", "48",
            "--draft-max", "64",
        )
    }

    /**
     * Builds the full command string for starting the server.
     * Uses llama-server when native engine is enabled, otherwise uvx.
     */
    fun getServerCommand(): String? {
        val useNativeEngine = SweepSettings.getInstance().autocompleteLocalNativeEngine
        val port = getPort()

        if (useNativeEngine) {
            val llamaPath = resolveLlamaServer()
            if (llamaPath == null) {
                showNotification(
                    "llama-server not found. Install it with: brew install llama.cpp",
                    NotificationType.ERROR,
                )
                return null
            }

            val modelPath = resolveModelPath()
            if (modelPath != null) {
                return buildLlamaServerCommand(llamaPath, modelPath, port).joinToString(" ") { arg ->
                    if (arg.contains(" ")) "\"$arg\"" else arg
                }
            }

            // Model not downloaded yet — return a command that downloads first, then starts
            val model = getSelectedModel()
            val downloadCmd = buildModelDownloadCommand()
            val repoDirName = model.repo.replace("/", "--")
            val sweepCachePath = "$SWEEP_MODELS_DIR/${model.filename}"
            val serverCmd = buildLlamaServerCommand(llamaPath, "\$MODEL_PATH", port)
                .joinToString(" ") { if (it.contains(" ")) "\"$it\"" else it }

            // After download, find the model in either HF cache or Sweep cache
            val findModel = "MODEL_PATH=\$(find ~/.cache/huggingface/hub/models--$repoDirName -name '${model.filename}' 2>/dev/null | head -1); " +
                "[ -z \"\$MODEL_PATH\" ] && MODEL_PATH=\"$sweepCachePath\""

            return "$downloadCmd && $findModel && $serverCmd"
        }

        // Fall back to uvx path
        var uvxPath = resolveUvx()
        if (uvxPath == null) {
            logger.info("uvx not found, attempting to install uv")
            installUv()
            uvxPath = resolveUvx()
            if (uvxPath == null) {
                showNotification(
                    "Failed to find uvx after installing uv. Please install uv manually: https://docs.astral.sh/uv/",
                    NotificationType.ERROR,
                )
                return null
            }
        }
        return buildUvxCommand(uvxPath, port).joinToString(" ") { arg ->
            if (arg.contains(" ")) "\"$arg\"" else arg
        }
    }

    /**
     * Starts the local autocomplete server in a visible IDE terminal tab.
     * If the server is already healthy, does nothing.
     */
    @Volatile
    private var terminalStartInProgress = false

    fun startServerInTerminal(project: Project) {
        if (isServerHealthy()) {
            logger.info("Local autocomplete server is already running")
            return
        }
        // Prevent multiple concurrent terminal starts (e.g. when multiple projects open at once)
        if (terminalStartInProgress) {
            logger.info("Local autocomplete server terminal start already in progress, skipping")
            return
        }
        terminalStartInProgress = true

        val command = getServerCommand() ?: run {
            terminalStartInProgress = false
            return
        }

        ApplicationManager.getApplication().invokeLater {
            try {
                val toolWindow = ToolWindowManager.getInstance(project)
                    .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID) ?: return@invokeLater

                // Reuse existing terminal tab if one exists, otherwise create a new one
                val existingContent = toolWindow.contentManager.contents.firstOrNull {
                    it.displayName == TERMINAL_TAB_NAME
                }
                val widget = if (existingContent != null) {
                    TerminalToolWindowManager.findWidgetByContent(existingContent)
                } else {
                    null
                }

                val targetWidget = widget ?: run {
                    val workDir = project.basePath ?: System.getProperty("user.home")
                    TerminalToolWindowManager
                        .getInstance(project)
                        .createShellWidget(workDir, TERMINAL_TAB_NAME, true, true)
                }

                // Small delay so the shell is ready to accept input, then send command
                ApplicationManager.getApplication().executeOnPooledThread {
                    Thread.sleep(1000)
                    // Compute isPowerShell on BGT to avoid EDT threading violation
                    val isPowerShell = TerminalApiWrapper.isPowerShell(project)
                    ApplicationManager.getApplication().invokeLater {
                        TerminalApiWrapper.sendCommand(targetWidget, command, project, isPowerShell)
                    }
                    // Clear the in-progress flag after the server has had time to start
                    Thread.sleep(30_000)
                    terminalStartInProgress = false
                }
                logger.info("Started local autocomplete server in terminal: $command")
            } catch (e: Exception) {
                logger.warn("Failed to start local autocomplete server in terminal: ${e.message}")
                terminalStartInProgress = false
                showNotification(
                    "Failed to open terminal for local autocomplete server: ${e.message}",
                    NotificationType.ERROR,
                )
            }
        }
    }

    /**
     * Restarts the server in the terminal by sending Ctrl+C to the existing tab,
     * then launching the new command. Used when the model is changed.
     */
    fun restartServerInTerminal(project: Project) {
        val command = getServerCommand() ?: return

        ApplicationManager.getApplication().invokeLater {
            try {
                val toolWindow = ToolWindowManager.getInstance(project)
                    .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID) ?: return@invokeLater

                val existingContent = toolWindow.contentManager.contents.firstOrNull {
                    it.displayName == TERMINAL_TAB_NAME
                }
                val widget = if (existingContent != null) {
                    TerminalToolWindowManager.findWidgetByContent(existingContent)
                } else {
                    null
                }

                if (widget != null) {
                    // Send Ctrl+C to stop the running server, then start new one
                    ApplicationManager.getApplication().executeOnPooledThread {
                        val isPowerShell = TerminalApiWrapper.isPowerShell(project)
                        ApplicationManager.getApplication().invokeLater {
                            // Send Ctrl+C
                            TerminalApiWrapper.sendCommand(widget, "\u0003", project, isPowerShell)
                        }
                        // Wait for the server to stop
                        Thread.sleep(1500)
                        ApplicationManager.getApplication().invokeLater {
                            TerminalApiWrapper.sendCommand(widget, command, project, isPowerShell)
                        }
                    }
                    logger.info("Restarting local autocomplete server with: $command")
                } else {
                    // No existing terminal — just start fresh
                    startServerInTerminal(project)
                }
            } catch (e: Exception) {
                logger.warn("Failed to restart local autocomplete server: ${e.message}")
            }
        }
    }

    private fun stopServer() {
        serverProcess?.let { process ->
            try {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
                logger.info("Stopped local autocomplete server")
            } catch (e: Exception) {
                logger.warn("Error stopping local autocomplete server: ${e.message}")
            }
            serverProcess = null
        }
    }

    private fun showNotification(
        content: String,
        type: NotificationType,
    ) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val notificationGroup =
                    NotificationGroupManager
                        .getInstance()
                        .getNotificationGroup("Sweep Autocomplete")

                notificationGroup?.createNotification("Sweep Autocomplete", content, type)?.notify(null)
            } catch (e: Exception) {
                logger.warn("Failed to show notification: ${e.message}")
            }
        }
    }

    override fun dispose() {
        stopServer()
        scope.cancel()
    }
}
