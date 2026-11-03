package com.ai.assistance.operit.terminal

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.launch
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import com.ai.assistance.operit.terminal.data.TerminalState
import com.ai.assistance.operit.terminal.data.CommandHistoryItem
import com.ai.assistance.operit.terminal.data.QueuedCommand
import com.ai.assistance.operit.terminal.view.domain.OutputProcessor
import java.util.UUID
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import com.ai.assistance.operit.terminal.data.PackageManagerType
import com.ai.assistance.operit.terminal.data.SessionInitState
import com.ai.assistance.operit.terminal.utils.SourceManager
import com.ai.assistance.operit.terminal.utils.SSHConfigManager
import com.ai.assistance.operit.terminal.utils.SSHDServerManager
import com.ai.assistance.operit.terminal.provider.filesystem.FileSystemProvider
import com.ai.assistance.operit.terminal.provider.filesystem.LocalFileSystemProvider
import com.ai.assistance.operit.terminal.provider.filesystem.PRootBindMount
import com.ai.assistance.operit.terminal.provider.filesystem.PRootMountMapping
import com.ai.assistance.operit.terminal.provider.type.HiddenExecResult
import com.ai.assistance.operit.terminal.provider.type.TerminalProvider
import com.ai.assistance.operit.terminal.provider.type.TerminalType
import com.ai.assistance.operit.terminal.provider.type.LocalTerminalProvider
import com.ai.assistance.operit.terminal.provider.type.SSHTerminalProvider
import com.ai.assistance.operit.terminal.data.TerminalSessionData
import com.ai.assistance.operit.terminal.view.domain.ansi.AnsiTerminalEmulator

class TerminalManager private constructor(
    private val context: Context
) {
    internal val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val envInitMutex = Mutex()
    private var isEnvInitialized = false

    private val filesDir: File = context.filesDir
    private val usrDir: File = File(filesDir, "usr")
    private val binDir: File = File(usrDir, "bin")
    private val nativeLibDir: String = context.applicationInfo.nativeLibraryDir
    private val activeSessions = ConcurrentHashMap<String, TerminalSession>()
    private val closingSessions = ConcurrentHashMap.newKeySet<String>()
    
    // SharedPreferences for reading settings
    private val prefs = context.getSharedPreferences("terminal_settings", Context.MODE_PRIVATE)

    /**
     * root 启动路径的状态。
     * 背景：proot 在 app 进程身份（untrusted_app + seccomp）下 execve 直系子进程会返回 ENOSYS，
     * 只有以 root 身份启动 proot 才能正常工作，因此本地终端优先经 su -c 进入 rootfs。
     * 这里只在同一进程内探测一次并缓存，避免反复触发授权弹窗、也避免未授权时被系统直接杀进程。
     */
    private object RootLaunchState {
        const val UNKNOWN = 0
        const val GRANTED = 1
        const val DENIED = 2
    }

    @Volatile
    private var rootLaunchState: Int = RootLaunchState.UNKNOWN

    /** 面向 UI 的提示：null 表示没有阻塞性问题 */
    private val _environmentBlocker = MutableSharedFlow<String>(extraBufferCapacity = 8, replay = 1)
    val environmentBlocker: SharedFlow<String> = _environmentBlocker.asSharedFlow()

    private fun publishEnvironmentBlocker(message: String?) {
        _environmentBlocker.tryEmit(message.orEmpty())
    }

    // 核心组件
    private val sessionManager = SessionManager(this)
    private val outputProcessor = OutputProcessor(
        onCommandExecutionEvent = { event ->
            coroutineScope.launch {
                _commandExecutionEvents.emit(event)
            }
        },
        onDirectoryChangeEvent = { event ->
            coroutineScope.launch {
                _directoryChangeEvents.emit(event)
            }
        },
        onCommandCompleted = { sessionId ->
            coroutineScope.launch {
                processNextQueuedCommand(sessionId)
            }
        }
    )
    private val sourceManager = SourceManager(context)
    private val sshConfigManager = SSHConfigManager(context)
    private val sshdServerManager = SSHDServerManager.getInstance(context)
    
    // 单例的 TerminalProvider
    private var terminalProvider: TerminalProvider? = null
    private val providerMutex = Mutex()

    // 状态和事件流
    private val _commandExecutionEvents = MutableSharedFlow<CommandExecutionEvent>()
    val commandExecutionEvents: SharedFlow<CommandExecutionEvent> = _commandExecutionEvents.asSharedFlow()

    private val _directoryChangeEvents = MutableSharedFlow<SessionDirectoryEvent>()
    val directoryChangeEvents: SharedFlow<SessionDirectoryEvent> = _directoryChangeEvents.asSharedFlow()

    // 暴露会话管理器的状态
    val terminalState: StateFlow<TerminalState> = sessionManager.state

    // 为了向后兼容，提供单独的状态流
    val sessions = terminalState.map { it.sessions }
    val currentSessionId = terminalState.map { it.currentSessionId }
    val currentDirectory = terminalState.map { it.currentSession?.currentDirectory ?: "$ " }
    val isInteractiveMode = terminalState.map { it.currentSession?.isInteractiveMode ?: false }
    val interactivePrompt = terminalState.map { it.currentSession?.interactivePrompt ?: "" }
    val isFullscreen = terminalState.map { it.currentSession?.isFullscreen ?: false }
    val terminalEmulator = terminalState.map { it.currentSession?.ansiParser ?: AnsiTerminalEmulator() }

    companion object {
        @Volatile
        private var INSTANCE: TerminalManager? = null

        fun getInstance(context: Context): TerminalManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TerminalManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        private const val TAG = "TerminalManager"
        // 多个环境包：rootfs-packs/<env>.tar.xz
        private const val ROOTFS_PACKS_DIR = "rootfs-packs"
        private const val MAX_HISTORY_ITEMS = 500
        private const val MAX_OUTPUT_LINES_PER_ITEM = 1000
        private const val TERMINAL_ENTER = "\r"
    }

    init {
        // 在初始化时异步创建默认session
        coroutineScope.launch {
            try {
                Log.d(TAG, "Creating default session...")
                // 自动检测：如果有SSH配置则创建SSH会话，否则创建本地会话
                val sshConfig = sshConfigManager.getConfig()
                val isEnabled = sshConfigManager.isEnabled()
                if (sshConfig != null && isEnabled) {
                    Log.d(TAG, "Found SSH config, creating SSH session")
                    createNewSession("SSH")
                } else {
                    Log.d(TAG, "No SSH config, creating local session")
                    createNewSession("Local")
                }
                Log.d(TAG, "Default session created successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create default session", e)
            }
        }
    }

    /**
     * 创建新会话 - 同步等待初始化完成
     * 自动检测终端类型：如果配置了SSH则使用SSH，否则使用本地终端
     * 
     * @param title 会话标题
     */
    suspend fun createNewSession(
        title: String? = null
    ): TerminalSessionData {
        // 自动检测终端类型
        val terminalType = if (sshConfigManager.getConfig() != null && sshConfigManager.isEnabled()) {
            TerminalType.SSH
        } else {
            TerminalType.LOCAL
        }
        
        val newSession = sessionManager.createNewSession(title, terminalType)

        // 异步初始化会话
        coroutineScope.launch {
            initializeSession(newSession.id)
        }

        // 等待会话初始化完成
        val success = withTimeoutOrNull(30000) { // 30秒超时
            terminalState.first { state ->
                val session = state.sessions.find { it.id == newSession.id }
                session?.initState == com.ai.assistance.operit.terminal.data.SessionInitState.READY
            }
        }

        if (success == null) {
            Log.e(TAG, "Session initialization timeout for session: ${newSession.id}")
            // 初始化失败，移除会话
            sessionManager.closeSession(newSession.id)
            // 优先给出可读的阻塞原因（无 root 授权 / 环境包损坏），排队中的会话也能拿到
            val blocker = environmentBlocker.replayCache.firstOrNull().orEmpty()
            throw Exception(blocker.ifEmpty { describeInitFailure() })
        }

        Log.d(TAG, "Session ${newSession.id} initialized successfully")
        return sessionManager.getSession(newSession.id) ?: newSession
    }

    /**
     * 切换到会话
     */
    fun switchToSession(sessionId: String) {
        sessionManager.switchToSession(sessionId)
    }

    /**
     * 关闭会话
     */
    fun closeSession(sessionId: String) {
        sessionManager.closeSession(sessionId)
    }

    /**
     * 会话关闭后的运行态清理（非持久化状态）。
     */
    fun onSessionClosed(sessionId: String) {
        outputProcessor.clearSessionState(sessionId)
    }
    
    /**
     * 保存会话的滚动位置
     */
    fun saveScrollOffset(sessionId: String, scrollOffset: Float) {
        sessionManager.saveScrollOffset(sessionId, scrollOffset)
    }
    
    /**
     * 获取会话的滚动位置
     */
    fun getScrollOffset(sessionId: String): Float {
        return sessionManager.getScrollOffset(sessionId)
    }

    private fun createBusyboxSymlinks() {
        val links = listOf(
            "awk", "ash", "basename", "bzip2", "curl", "cp", "chmod", "cut", "cat", "du", "dd",
            "find", "grep", "gzip", "hexdump", "head", "id", "lscpu", "mkdir", "realpath", "rm",
            "sed", "stat", "sh", "tr", "tar", "uname", "xargs", "xz", "xxd"
        )
        val busybox = File(binDir, "busybox")
        for (linkName in links) {
            try {
                createSymbolicLink(busybox, linkName, binDir, true)
                Log.d(TAG, "Created busybox link for '$linkName'")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create link for '$linkName'", e)
            }
        }
        try {
            val fileLink = File(binDir, "file")
            if (!fileLink.exists()) {
                Files.createSymbolicLink(fileLink.toPath(), File("/system/bin/file").toPath())
                Log.d(TAG, "Created symlink for 'file'")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create symlink for 'file'", e)
        }
    }

    private fun writeInputToKernel(session: TerminalSessionData, input: String, source: String) {
        val writer = session.sessionWriter ?: return
        writer.write(input)
        writer.flush()
        Log.d(
            TAG,
            "Sent terminal input to kernel [source=$source, sessionId=${session.id}]: '${escapeInputForLog(input)}'"
        )
    }

    private fun escapeInputForLog(input: String): String {
        return buildString(input.length) {
            input.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (char.isISOControl()) {
                            append("\\u")
                            append(char.code.toString(16).padStart(4, '0'))
                        } else {
                            append(char)
                        }
                    }
                }
            }
        }
    }

    /**
     * 发送命令
     */
    suspend fun sendCommand(command: String, commandId: String? = null): String {
        val actualCommandId = commandId ?: UUID.randomUUID().toString()
        val session = sessionManager.getCurrentSession() ?: return actualCommandId

        // Allow input during initialization (e.g. password prompt) or interactive mode
        val isInitializing = session.initState != SessionInitState.READY
        
        if (session.isInteractiveMode || isInitializing) {
            Log.d(TAG, "Session in interactive mode or initializing, sending as input: $command")
            sendInput(command + TERMINAL_ENTER)
            return actualCommandId
        }

        session.commandMutex.withLock {
            if (session.currentExecutingCommand?.isExecuting == true) {
                // 有命令正在执行，将新命令加入队列
                session.commandQueue.add(QueuedCommand(actualCommandId, command))
                Log.d(TAG, "Command queued: $command (id: $actualCommandId). Queue size: ${session.commandQueue.size}")
            } else {
                // 没有命令在执行，直接执行
                executeCommandInternal(command, session, actualCommandId)
            }
        }
        return actualCommandId
    }

    /**
     * 向指定会话发送命令（不切换当前会话）
     */
    suspend fun sendCommandToSession(sessionId: String, command: String, commandId: String? = null): String {
        val actualCommandId = commandId ?: UUID.randomUUID().toString()
        val session = sessionManager.getSession(sessionId) ?: return actualCommandId

        // 如果会话在交互模式，直接发送输入（不创建命令历史）
        if (session.isInteractiveMode) {
            Log.d(TAG, "Session $sessionId in interactive mode, sending as input: $command")
            try {
                writeInputToKernel(session, command + TERMINAL_ENTER, "interactive-session-command")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending input to session $sessionId", e)
            }
            return actualCommandId
        }

        session.commandMutex.withLock {
            if (session.currentExecutingCommand?.isExecuting == true) {
                // 有命令正在执行，将新命令加入队列
                session.commandQueue.add(QueuedCommand(actualCommandId, command))
                Log.d(TAG, "Command queued for session $sessionId: $command (id: $actualCommandId). Queue size: ${session.commandQueue.size}")
            } else {
                // 没有命令在执行，直接执行
                executeCommandInternal(command, session, actualCommandId)
            }
        }
        return actualCommandId
    }

    /**
     * 处理队列中的下一个命令
     */
    private suspend fun processNextQueuedCommand(sessionId: String) {
        val session = sessionManager.getSession(sessionId) ?: return

        session.commandMutex.withLock {
            if (session.currentExecutingCommand?.isExecuting == true) {
                Log.w(TAG, "processNextQueuedCommand called, but a command is still executing. This should not happen.")
                return@withLock
            }

            if (session.commandQueue.isNotEmpty()) {
                val nextCommand = session.commandQueue.removeAt(0)
                Log.d(TAG, "Processing next queued command: ${nextCommand.command} (id: ${nextCommand.id}). Queue size: ${session.commandQueue.size}")
                executeCommandInternal(nextCommand.command, session, nextCommand.id)
            }
        }
    }

    /**
     * 内部执行命令的函数, 必须在 commandMutex 锁内部调用
     */
    private suspend fun executeCommandInternal(command: String, session: TerminalSessionData, commandId: String) {
        if (command.trim() == "clear") {
            try {
                writeInputToKernel(session, "clear$TERMINAL_ENTER", "command-clear")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending 'clear' command", e)
            }
        } else {
            handleRegularCommand(command, session, commandId)
            try {
                val fullInput = "$command$TERMINAL_ENTER"
                writeInputToKernel(session, fullInput, "command")
                Log.d(TAG, "Sent command to PTY: $command")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending command", e)
            }
        }
    }

    /**
     * 发送输入
     */
    fun sendInput(input: String) {
        coroutineScope.launch(Dispatchers.IO) {
            val session = sessionManager.getCurrentSession() ?: return@launch

            try {
                writeInputToKernel(session, input, "direct-input")

                // 如果用户提供了交互式输入，则重置等待状态
                if (session.isWaitingForInteractiveInput) {
                    sessionManager.updateSession(session.id) {
                        it.copy(isWaitingForInteractiveInput = false)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending input", e)
            }
        }
    }

    /**
     * 发送中断信号
     */
    fun sendInterruptSignal() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val currentSession = sessionManager.getCurrentSession()
                currentSession?.let {
                    writeInputToKernel(it, "\u0003", "interrupt")
                    Log.d(TAG, "Sent interrupt signal (Ctrl+C) to session ${it.id}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending interrupt signal", e)
            }
        }
    }

    private fun initializeSession(sessionId: String) {
        coroutineScope.launch {
            // 前置检查：没有 root 就不要进入安装/登录流程，否则用户只能看到一个泛化的初始化超时
            if (!ensureRootLaunch()) {
                Log.e(TAG, "Root launch unavailable, aborting session $sessionId")
                sessionManager.closeSession(sessionId)
                return@launch
            }

            // 上一次安装失败过：不要反复输出一屏日志，直接给出可读原因
            val stamp = installFailureStamp()
            if (stamp.isFile) {
                val reason = runCatching { stamp.readText().trim() }.getOrDefault("")
                if (reason.isNotEmpty()) {
                    Log.e(TAG, "Install failure stamp present: $reason")
                    publishEnvironmentBlocker("上一次内置环境安装失败：$reason")
                }
            }

            val success = initializeEnvironment()
            if (success) {
                startSession(sessionId)
            } else {
                // 环境初始化失败也要让等待者立刻退出，否则只能干等到 30 秒超时
                publishEnvironmentBlocker(
                    "内置环境初始化失败：请检查 files/rootfs-packs 下的环境包，或使用「修复内置环境」。"
                )
                sessionManager.closeSession(sessionId)
            }
        }
    }

    private fun startSession(sessionId: String) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting session...")
                closingSessions.remove(sessionId)

                // 获取单例的终端提供者
                val provider = getTerminalProvider()

                // 启动终端会话
                val result = provider.startSession(sessionId)
                val (terminalSession, pty) = result.getOrThrow()
                val sessionWriter = terminalSession.stdin.writer()

                // 启动读取协程
                val readJob = launch {
                    var reachedEof = false
                    try {
                        terminalSession.stdout.use { inputStream ->
                            val buffer = ByteArray(4096)
                            var bytesRead: Int
                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                val chunk = String(buffer, 0, bytesRead)
                                Log.d(TAG, "Read chunk: '$chunk'")
                                outputProcessor.processOutput(sessionId, chunk, sessionManager)
                            }
                            reachedEof = true
                        }
                    } catch (e: java.io.InterruptedIOException) {
                        Log.i(TAG, "Read job interrupted for session $sessionId.")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in read job for session $sessionId", e)
                    } finally {
                        if (closingSessions.remove(sessionId)) {
                            return@launch
                        }
                        if (reachedEof || !terminalSession.process.isAlive) {
                            handleTerminalSessionExit(sessionId, terminalSession)
                        }
                    }
                }

                // 更新会话信息
                sessionManager.updateSession(sessionId) { session ->
                    session.copy(
                        terminalSession = terminalSession,
                        pty = pty,
                        sessionWriter = sessionWriter,
                        readJob = readJob
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting session", e)
            }
        }
    }

    private fun handleTerminalSessionExit(sessionId: String, terminalSession: TerminalSession) {
        if (sessionManager.getSession(sessionId) == null) {
            return
        }

        val exitCode =
            if (terminalSession.process.isAlive) {
                -1
            } else {
                runCatching { terminalSession.process.waitFor() }
                    .getOrElse {
                        Log.w(TAG, "Failed to read exit code for session $sessionId", it)
                        -1
                    }
            }

        Log.i(TAG, "Terminal session $sessionId exited with code $exitCode")
        outputProcessor.handleSessionExit(
            sessionId = sessionId,
            message = context.getString(R.string.terminal_exited_with_code, exitCode),
            sessionManager = sessionManager
        )

        // 已退出的会话不能继续留在状态表中，否则同名 terminal.create 会复用已关闭的 PTY，
        // 后续命令只会写入失效 writer 并等待一个永远不会到达的完成事件。下一次按名称创建时，
        // 会话管理器会启动新的会话。
        sessionManager.closeSession(sessionId)
    }
    
    /**
     * 获取或创建单例的终端提供者
     */
    private suspend fun getTerminalProvider(): TerminalProvider {
        providerMutex.withLock {
            if (terminalProvider == null) {
                val sshConfig = sshConfigManager.getConfig()
                val provider = if (sshConfig != null && sshConfigManager.isEnabled()) {
                    Log.d(TAG, "Creating singleton SSH terminal provider")
                    SSHTerminalProvider(context, sshConfig, this)
                } else {
                    Log.d(TAG, "Creating singleton local terminal provider")
                    LocalTerminalProvider(context)
                }
                provider.connect().getOrThrow()
                terminalProvider = provider
            }
        }
        return terminalProvider!!
    }

    /**
     * 内置环境一键修复：清掉损坏的安装残留、缓存副本和失败标记，下次开终端会重新解压。
     * 不删除用户数据（rootfs-packs 下的环境包会被保留）。
     */
    suspend fun repairEnvironment(): Boolean = withContext(Dispatchers.IO) {
        try {
            prepareForMaintenance()
            delay(300)
            val env = selectedRootfsEnv()
            deleteRecursivelyQuietly(File(filesDir, "$env.tar.xz"))
            deleteRecursivelyQuietly(File(filesDir, "$env.install.tmp"))
            deleteRecursivelyQuietly(File(filesDir, "$env.install.lock"))
            deleteRecursivelyQuietly(File(filesDir, "usr/var/lib/proot-distro/installed-rootfs/$env"))
            deleteRecursivelyQuietly(File(filesDir, "extract.log"))
            clearInstallFailureStamp()
            rootLaunchState = RootLaunchState.UNKNOWN
            publishEnvironmentBlocker(null)
            Log.d(TAG, "repairEnvironment done for env=$env")
            true
        } catch (e: Exception) {
            Log.e(TAG, "repairEnvironment failed", e)
            false
        }
    }

    private fun deleteRecursivelyQuietly(file: File) {
        runCatching {
            if (file.exists()) {
                file.deleteRecursively()
                Log.d(TAG, "removed ${file.absolutePath}")
            }
        }.onFailure { Log.w(TAG, "failed to remove ${file.absolutePath}", it) }
    }

    suspend fun initializeEnvironment(): Boolean {
        if (isEnvInitialized) {
            return withContext(Dispatchers.IO) {
                try {
                    val startScript = generateStartScript()
                    File(filesDir, "common.sh").writeText(startScript.replace("\r\n", "\n").replace("\r", "\n"))
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Environment script refresh failed", e)
                    false
                }
            }
        }

        envInitMutex.lock()
        try {
            if (isEnvInitialized) {
                return true
            }

            val success = withContext(Dispatchers.IO) {
                try {
                    Log.d(TAG, "Starting environment initialization...")

                    // 1. Create necessary directories
                    createDirectories()

                    // 2. Link native libraries
                    linkNativeLibs()
                    createBusyboxSymlinks()

                    // 3. Extract assets
                    extractAssets()

                    // 4. Generate and write startup script
                    val startScript = generateStartScript()
                    File(filesDir, "common.sh").writeText(startScript.replace("\r\n", "\n").replace("\r", "\n"))


                    Log.d(TAG, "Environment initialization completed successfully.")
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Environment initialization failed", e)
                    false
                }
            }
            if (success) {
                isEnvInitialized = true
            }
            return success
        } finally {
            envInitMutex.unlock()
        }
    }

    private fun createDirectories() {
        if (!usrDir.exists()) {
            usrDir.mkdirs()
        }
        if (!binDir.exists()) {
            binDir.mkdirs()
            Log.d(TAG, "Created bin directory at: ${binDir.absolutePath}")
        }
        File(filesDir, "tmp").mkdirs()
    }

    private fun linkNativeLibs() {
        Log.d(TAG, "Linking native libraries from: $nativeLibDir")

        val nativeLibDirFile = File(nativeLibDir)
        if (!nativeLibDirFile.exists() || !nativeLibDirFile.isDirectory) {
            Log.e(TAG, "Native library directory not found or is not a directory.")
            return
        }

        Log.d(TAG, "Native lib directory contents:")
        nativeLibDirFile.listFiles()?.forEach { file ->
            Log.d(TAG, "  - ${file.name} (file ${file.length()} bytes)")
        }

        val busybox = File(binDir, "busybox")

        // First, we need to link busybox itself so we can use it.
        val busyboxSo = File(nativeLibDir, "libbusybox.so")
        Log.d(TAG, "Checking busybox: libbusybox.so exists = ${busyboxSo.exists()}, busybox exists = ${busybox.exists()}")

        if (!busyboxSo.exists()) {
            Log.e(TAG, "libbusybox.so not found, cannot create busybox link")
            return
        }

        // Always ensure proper busybox link - remove any existing file/broken link first
        try {
            val link = busybox.toPath()
            val target = busyboxSo.toPath()

            // Delete existing file/broken link if it exists to prevent FileAlreadyExistsException
            Files.deleteIfExists(link)

            // CRITICAL: Set execute permission on the target .so file before creating symlink
            busyboxSo.setExecutable(true, false)

            // Create the symbolic link
            Files.createSymbolicLink(link, target)
            Log.d(TAG, "Created busybox symbolic link using Java NIO")

            // Verify the link was created successfully and is functional
            if (busybox.exists() && busybox.canExecute()) {
                Log.d(TAG, "Verification: busybox link exists and is executable at ${busybox.absolutePath}")
            } else {
                Log.e(TAG, "Verification failed: busybox link not functional after creation")
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create busybox link using Java NIO", e)
            return
        }

        try {
            Files.deleteIfExists(File(binDir, "libtalloc.so.2").toPath())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove stale libtalloc link", e)
        }

        // Symlink other binaries
        val libraries = mapOf(
            "liboperit_proot.so" to "proot",
            "liboperit_loader.so" to "loader",
            "libbash.so" to "bash",
            "libsudo.so" to "sudo"
        )

        libraries.forEach { (libName, linkName) ->
            val libFile = File(nativeLibDir, libName)
            val linkFile = File(binDir, linkName)

            Log.d(TAG, "Checking $libName at ${libFile.absolutePath}, exists: ${libFile.exists()}")

            if (!libFile.exists()) {
                Log.w(TAG, "Native library not found: $libName")
                return@forEach
            }

            // Always ensure proper link - remove any existing file/broken link first
            try {
                val link = linkFile.toPath()
                val target = libFile.toPath()

                // Delete existing file/broken link if it exists to prevent FileAlreadyExistsException
                Files.deleteIfExists(link)

                // CRITICAL: Set execute permission on the target .so file before creating symlink
                libFile.setExecutable(true, false)

                // Create the symbolic link
                Files.createSymbolicLink(link, target)
                Log.d(TAG, "Created $linkName symbolic link using Java NIO")

                // Verify the link was created successfully and is executable
                if (linkFile.exists() && linkFile.canExecute()) {
                    Log.d(TAG, "Verification: $linkName link exists and is executable at ${linkFile.absolutePath}")
                } else {
                    Log.w(TAG, "Verification failed: $linkName link not executable after creation")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create $linkName link using Java NIO", e)
            }
        }
    }

    @Throws(IOException::class)
    private fun createSymbolicLink(target: File, linkName: String, linkDir: File, force: Boolean) {
        val linkFile = File(linkDir, linkName)

        // Use relative path for target if it's in the same directory
        val targetPath = if (target.parentFile == linkDir) {
            Paths.get(target.name)
        } else {
            target.toPath()
        }

        if (force) {
            Files.deleteIfExists(linkFile.toPath())
        }
        Files.createSymbolicLink(linkFile.toPath(), targetPath)
    }

    /** 环境包目录：每个 "<环境名>.tar.xz" 都是一个可安装环境 */
    private fun rootfsPacksDir(): File = File(filesDir, ROOTFS_PACKS_DIR).apply { mkdirs() }

    /**
     * 判断 su 是否可用且已授权。
     * 注意：必须放在启动会话之前执行——未授权时 su 会直接拒绝，某些 ROM 会连带杀掉调用进程，
     * 所以这里只探测一次并缓存结果，探测失败就不要进入安装/登录流程。
     */
    private fun probeSuAccess(): Boolean = try {
        val process = ProcessBuilder("su", "-c", "id -u")
            .redirectErrorStream(true)
            .start()
        when {
            !process.waitFor(8, java.util.concurrent.TimeUnit.SECONDS) -> {
                process.destroyForcibly()
                false
            }
            process.exitValue() != 0 -> false
            else -> process.inputStream.bufferedReader().use { it.readText() }.trim() == "0"
        }
    } catch (e: Exception) {
        Log.w(TAG, "probeSuAccess failed", e)
        false
    }

    private fun ensureRootLaunch(): Boolean {
        if (rootLaunchState == RootLaunchState.UNKNOWN) {
            val granted = probeSuAccess()
            rootLaunchState = if (granted) RootLaunchState.GRANTED else RootLaunchState.DENIED
            Log.d(TAG, "Root launch probe result: granted=$granted")
        }
        if (rootLaunchState != RootLaunchState.GRANTED) {
            publishEnvironmentBlocker(
                "内置 Linux 环境需要 root：proot 在应用进程下会被系统拦截（execve 返回 ENOSYS）。\n" +
                    "请给本应用 root 授权后重开终端，或改用系统 Termux。"
            )
            return false
        }
        publishEnvironmentBlocker(null)
        return true
    }

    /** 把「会话初始化超时」细分成可读原因，避免用户只看到 Session initialization timeout */
    private fun describeInitFailure(): String {
        if (rootLaunchState == RootLaunchState.DENIED) {
            return "内置 Linux 环境需要 root 授权：proot 在应用进程下会被系统拦截（execve 返回 ENOSYS）。请在 root 管理器中给本应用授权后重开终端。"
        }
        val stamp = installFailureStamp()
        if (stamp.isFile) {
            val reason = runCatching { stamp.readText().trim() }.getOrDefault("")
            if (reason.isNotEmpty()) {
                return "内置环境安装失败：$reason"
            }
        }
        return "Session initialization timeout"
    }

    /** 安装流程失败时写入的标记文件：内容非空代表上一次解压/校验失败 */
    private fun installFailureStamp(): File = File(filesDir, "linux_env_install_failed.txt")

    private fun clearInstallFailureStamp() {
        runCatching { installFailureStamp().delete() }
    }

    /** 当前选中的环境：优先 rootfs-packs/CURRENT，其次按名字排序的第一个包 */
    private fun selectedRootfsEnv(): String {
        val packs = rootfsPacksDir().listFiles()
            ?.filter { it.isFile && it.name.endsWith(".tar.xz") && it.length() > 1_000_000L }
            ?.map { it.name.removeSuffix(".tar.xz") }
            ?.sorted()
            .orEmpty()
        if (packs.isEmpty()) return "ubuntu"
        val currentFile = File(rootfsPacksDir(), "CURRENT")
        val wanted = runCatching { currentFile.readText().trim() }.getOrNull().orEmpty()
        val chosen = if (wanted.isNotEmpty() && packs.contains(wanted)) wanted else packs.first()
        runCatching { currentFile.writeText(chosen) }
        return chosen
    }

    private fun extractAssets() {
        try {
            // 1. 启动辅助脚本（每次覆盖）
            val scriptFile = File(filesDir, "setup_fake_sysdata.sh")
            context.assets.open("setup_fake_sysdata.sh").use { input ->
                val text = input.readBytes().toString(Charsets.UTF_8)
                scriptFile.writeText(
                    text.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n"),
                    Charsets.UTF_8
                )
            }

            // 2. 环境包：从 rootfs-packs/<env>.tar.xz 拷贝到 files/<env>.tar.xz
            val env = selectedRootfsEnv()
            val target = File(filesDir, "$env.tar.xz")
            if (!target.exists()) {
                val pack = File(rootfsPacksDir(), "$env.tar.xz")
                val legacy = File(filesDir, "operit-rootfs.tar.xz")
                val source = when {
                    pack.isFile && pack.length() > 1_000_000L -> pack
                    legacy.isFile && legacy.length() > 1_000_000L -> legacy
                    else -> throw IllegalStateException(
                        "未找到环境包：请把 <环境名>.tar.xz 放到 " + rootfsPacksDir().absolutePath +
                            "（或在 " + filesDir.absolutePath + " 放 operit-rootfs.tar.xz）"
                    )
                }
                source.copyTo(target, overwrite = true)
                Log.d(TAG, "Imported rootfs pack from " + source.absolutePath)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to extract assets", e)
            throw e
        }
    }

    private fun generateStartScript(): String {
        val tmpDir = File(filesDir, "tmp").absolutePath
        val binDir = binDir.absolutePath
        val homeDir = filesDir.absolutePath
        val usrDir = usrDir.absolutePath
        val prootDistroPath = "$usrDir/var/lib/proot-distro"
        val rootfsEnv = selectedRootfsEnv()
        val ubuntuName = rootfsEnv
        val ubuntuPath = "$prootDistroPath/installed-rootfs/$rootfsEnv"
        val operitPackage = context.packageName
        val operitDataDir = context.applicationInfo.dataDir
        val currentEmulatedStoragePath = PRootMountMapping.currentEmulatedStoragePath()
        val currentUserDataRootPath = PRootMountMapping.currentUserDataRootPath()
        val operitUserDataMountPath = "$currentUserDataRootPath/$operitPackage"
        val operitLegacyDataMountPath = "/data/data/$operitPackage"

        // 获取当前选择的源
        val aptSource = sourceManager.getSelectedSource(PackageManagerType.APT)
        val pipSource = sourceManager.getSelectedSource(PackageManagerType.PIP)
        val npmSource = sourceManager.getSelectedSource(PackageManagerType.NPM)
        val rustSource = sourceManager.getSelectedSource(PackageManagerType.RUST)

        val common = """
        export TMPDIR=$tmpDir
        export BIN=$binDir
        export HOME=$homeDir
        export UBUNTU_PATH=$ubuntuPath
        export UBUNTU=$rootfsEnv.tar.xz
        export UBUNTU_NAME=$ubuntuName
        export USE_CHROOT=${if (prefs.getBoolean("chroot_enabled", false)) "1" else "0"}
        export OPERIT_UID=$(id -u)
        export OPERIT_GID=$(id -g)
        export OPERIT_GROUPS=$(id -G | tr ' ' ',')
        export L_NOT_INSTALLED="not installed"
        export L_INSTALLING="installing"
        export L_INSTALLED="installed"
        clear_lines(){
          printf "\\033[1A" # Move cursor up one line
          printf "\\033[K"  # Clear the line
          printf "\\033[1A" # Move cursor up one line
          printf "\\033[K"  # Clear the line
        }
        progress_echo(){
          echo -e "\\033[31m- ${'$'}@\\033[0m"
          echo "${'$'}@" > "${'$'}TMPDIR/progress_des"
        }
        bump_progress(){
          current=0
          if [ -f "${'$'}TMPDIR/progress" ]; then
            current=${'$'}(cat "${'$'}TMPDIR/progress" 2>/dev/null || echo 0)
          fi
          next=${'$'}((current + 1))
          printf "${'$'}next" > "${'$'}TMPDIR/progress"
        }
        write_default_dns(){
          target_file="${'$'}1"
          if [ -z "${'$'}target_file" ]; then
            return 1
          fi
          cat > "${'$'}target_file" <<'EOF'
nameserver 8.8.8.8
nameserver 1.1.1.1
nameserver 223.5.5.5
nameserver 223.6.6.6
nameserver 119.29.29.29
nameserver 180.76.76.76
EOF
        }
        can_access_bind_source(){
          bind_source="${'$'}1"
          if [ -z "${'$'}bind_source" ]; then
            return 1
          fi
          if [ ! -e "${'$'}bind_source" ] && [ ! -L "${'$'}bind_source" ]; then
            return 1
          fi
          "${'$'}BIN/busybox" ls -Ld "${'$'}bind_source" >/dev/null 2>&1
        }
        append_proot_bind_arg(){
          bind_source="${'$'}1"
          bind_target="${'$'}2"
          if ! can_access_bind_source "${'$'}bind_source"; then
            return 0
          fi
          if [ -z "${'$'}bind_target" ] || [ "${'$'}bind_source" = "${'$'}bind_target" ]; then
            PROOT_BIND_ARGS="${'$'}PROOT_BIND_ARGS -b ${'$'}bind_source"
          else
            PROOT_BIND_ARGS="${'$'}PROOT_BIND_ARGS -b ${'$'}bind_source:${'$'}bind_target"
          fi
        }
        # 把任意参数安全地包成单层 shell 单引号字面量（su -c 只接受单个字符串）
        sq(){
          printf "'%s'" "$(printf '%s' "${'$'}1" | sed "s/'/'\\\\''/g")"
        }
        # 把整组参数逐个单引号化后拼接成一条命令
        sq_all(){
          sq_all_out=""
          for sq_all_arg in "${'$'}@"; do
            sq_all_out="${'$'}sq_all_out $(sq "${'$'}sq_all_arg")"
          done
          printf '%s' "${'$'}sq_all_out"
        }
        # su -c 启动 proot 的唯一入口。参数先以「单引号字面量」拼接成一条命令，
        # 避免 su 把后续参数拆成它自己的参数。
        probe_root_proot(){
          command -v su >/dev/null 2>&1 || return 1
          su -c 'id -u' 2>/dev/null | grep -qx 0 || return 1
          return 0
        }
        root_proot_works(){
          command -v su >/dev/null 2>&1 || return 1
          su -c "PROOT_TMP_DIR=${'$'}TMPDIR PROOT_LOADER=${'$'}PROOT_LOADER LD_LIBRARY_PATH= $(sq "${'$'}BIN/proot") -0 -r $(sq "${'$'}UBUNTU_PATH") -w /root /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /bin/bash --noprofile --norc -c 'exit 0'" >/dev/null 2>&1
        }
        run_proot_binary(){
          if [ "${'$'}PROOT_AS_ROOT" = "1" ]; then
            su -c "PROOT_TMP_DIR=${'$'}TMPDIR PROOT_LOADER=${'$'}PROOT_LOADER LD_LIBRARY_PATH= $(sq "${'$'}BIN/proot") $(sq_all "${'$'}@")"
          else
            LD_LIBRARY_PATH= "${'$'}BIN/proot" "${'$'}@"
          fi
        }
        # 以 root 身份启动 proot，失败时自动回退到直连（并给出原因）
        try_root_proot(){
          if [ "${'$'}PROOT_AS_ROOT" != "1" ]; then
            return 1
          fi
          PROOT_TMP_DIR="${'$'}TMPDIR" PROOT_LOADER="${'$'}PROOT_LOADER" LD_LIBRARY_PATH= \
            exec su -c "$(sq "${'$'}BIN/proot") $(sq_all "${'$'}@")"
        }
        exec_proot_binary(){
          if [ "${'$'}PROOT_AS_ROOT" = "1" ]; then
            try_root_proot "${'$'}@"
            echo "PRoot 以 root 身份启动失败，回退到应用身份。" >&2
          fi
          LD_LIBRARY_PATH= exec "${'$'}BIN/proot" "${'$'}@"
        }
        LAST_PROOT_PROBE_STATUS=0
        LAST_PROOT_PROBE_OUTPUT=""
        LAST_PROOT_PROBE_ARGS=""
        run_proot_probe(){
          LAST_PROOT_PROBE_ARGS="${'$'}*"
          if [ "${'$'}PROOT_LINK2SYMLINK" = "1" ]; then
            LAST_PROOT_PROBE_OUTPUT="${'$'}(
              run_proot_binary \
                -v 1 \
                -0 \
                -r "${'$'}UBUNTU_PATH" \
                --link2symlink \
                "${'$'}@" \
                -w /root \
                /usr/bin/env -i \
                  HOME=/root \
                  TERM=xterm-256color \
                  LANG=en_US.UTF-8 \
                  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
                  /bin/bash --noprofile --norc -c 'exit 0' 2>&1
            )"
            LAST_PROOT_PROBE_STATUS="${'$'}?"
          else
            LAST_PROOT_PROBE_OUTPUT="${'$'}(
              run_proot_binary \
                -v 1 \
                -0 \
                -r "${'$'}UBUNTU_PATH" \
                "${'$'}@" \
                -w /root \
                /usr/bin/env -i \
                  HOME=/root \
                  TERM=xterm-256color \
                  LANG=en_US.UTF-8 \
                  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
                  /bin/bash --noprofile --norc -c 'exit 0' 2>&1
            )"
            LAST_PROOT_PROBE_STATUS="${'$'}?"
          fi
          return "${'$'}LAST_PROOT_PROBE_STATUS"
        }
        print_last_proot_probe_failure(){
          echo "PRoot startup probe failed."
          echo "exit_code=${'$'}LAST_PROOT_PROBE_STATUS"
          echo "ubuntu_path=${'$'}UBUNTU_PATH"
          echo "proot_loader=${'$'}{PROOT_LOADER:-<unset>}"
          echo "proot_no_seccomp=${'$'}{PROOT_NO_SECCOMP:-<unset>}"
          echo "ld_library_path=${'$'}{LD_LIBRARY_PATH:-<unset>}"
          echo "proot_exec_ld_library_path=<empty>"
          echo "proot_link2symlink=${'$'}PROOT_LINK2SYMLINK"
          if [ -n "${'$'}PROOT_BIND_ARGS" ]; then
            echo "bind_args=${'$'}PROOT_BIND_ARGS"
          else
            echo "bind_args=<none>"
          fi
          if [ -n "${'$'}LAST_PROOT_PROBE_ARGS" ]; then
            echo "extra_args=${'$'}LAST_PROOT_PROBE_ARGS"
          else
            echo "extra_args=<none>"
          fi
          echo "--- proot stdout/stderr begin ---"
          if [ -n "${'$'}LAST_PROOT_PROBE_OUTPUT" ]; then
            printf '%s\n' "${'$'}LAST_PROOT_PROBE_OUTPUT"
          else
            echo "<empty>"
          fi
          echo "--- proot stdout/stderr end ---"
        }
        # 诊断：以 root 身份跑一次探测，失败时把 stderr 打出来
        print_root_proot_tail_failure(){
          root_probe_output="$(su -c "PROOT_TMP_DIR=${'$'}TMPDIR PROOT_LOADER=${'$'}PROOT_LOADER LD_LIBRARY_PATH= $(sq "${'$'}BIN/proot") -0 -r $(sq "${'$'}UBUNTU_PATH") -w /root /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /bin/bash --noprofile --norc -c 'exit 0'" 2>&1)"
          root_probe_status="${'$'}?"
          if [ "${'$'}root_probe_status" != "0" ]; then
            echo "root 身份启动 proot 失败（exit=${'$'}root_probe_status）："
            printf '%s\n' "${'$'}root_probe_output"
            return 0
          fi
          return 1
        }
        # root 身份启动时，尽量把 Android 侧目录带进 guest；缺了运行时库就自动补一个只读绑定
        auto_enable_system_paths(){
          if [ "${'$'}PROOT_AS_ROOT" != "1" ]; then
            return 0
          fi
          if [ -n "${'$'}PROOT_BIND_ARGS" ]; then
            case "${'$'}PROOT_BIND_ARGS" in
              *"/system"*) return 0 ;;
            esac
          fi
          if [ -d /system ]; then
            PROOT_BIND_ARGS="${'$'}PROOT_BIND_ARGS -b /system"
            echo "已为 root 模式追加只读绑定：/system"
          fi
          return 0
        }
        probe_proot_link2symlink(){
          run_proot_binary \
            -v 1 \
            -0 \
            -r "${'$'}UBUNTU_PATH" \
            --link2symlink \
            -w /root \
            /usr/bin/env -i \
              HOME=/root \
              TERM=xterm-256color \
              LANG=en_US.UTF-8 \
              PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
              /bin/bash --noprofile --norc -c 'exit 0' >/dev/null 2>&1
        }
        probe_and_append_bind_arg(){
          bind_source="${'$'}1"
          bind_target="${'$'}2"
          if ! can_access_bind_source "${'$'}bind_source"; then
            return 0
          fi

          candidate_bind_args="-b ${'$'}bind_source"
          if [ -n "${'$'}bind_target" ] && [ "${'$'}bind_source" != "${'$'}bind_target" ]; then
            candidate_bind_args="-b ${'$'}bind_source:${'$'}bind_target"
          fi

          if [ -n "${'$'}PROOT_BIND_ARGS" ]; then
            set -- ${'$'}PROOT_BIND_ARGS ${'$'}candidate_bind_args
          else
            set -- ${'$'}candidate_bind_args
          fi

          if run_proot_probe "${'$'}@"; then
            append_proot_bind_arg "${'$'}bind_source" "${'$'}bind_target"
          fi
        }
        resolve_proot_runtime(){
          PROOT_LINK2SYMLINK=0
          PROOT_BIND_ARGS=""
          PROOT_AS_ROOT=0

          # 先判定是否以 root 身份启动 proot：
          # app 进程下 proot 派生的子进程会被系统拦截（execve 返回 ENOSYS），
          # 在有 root 的机器上只有走 su 才能正常工作。
          if probe_root_proot; then
            PROOT_AS_ROOT=1
            if print_root_proot_tail_failure; then
              :
            fi
          else
            PROOT_AS_ROOT=0
            progress_echo "未检测到可用的 root：proot 在应用身份下会被系统拦截（execve 返回 ENOSYS）。"
          fi

          if ! run_proot_probe; then
            print_last_proot_probe_failure
            if [ "${'$'}PROOT_AS_ROOT" != "1" ]; then
              echo "提示：本机 proot 需要 root 身份运行；当前未获得 root 授权。"
            fi
            return 1
          fi

          if probe_proot_link2symlink; then
            PROOT_LINK2SYMLINK=1
          fi
          auto_enable_system_paths
          return 0
        }
        """.trimIndent()

        val installUbuntu = """
        install_ubuntu(){
          OK_FILE="${'$'}UBUNTU_PATH/.operit_installed_ok"
          LOCK_DIR="${'$'}UBUNTU_PATH.install.lock"
          LOCK_PID_FILE="${'$'}LOCK_DIR/pid"
          TMP_DIR="${'$'}UBUNTU_PATH.install.tmp"

          UBUNTU_PARENT="${'$'}{UBUNTU_PATH%/*}"
          mkdir -p "${'$'}UBUNTU_PARENT" 2>/dev/null

          attempt=0
          while true; do
            if mkdir "${'$'}LOCK_DIR" 2>/dev/null; then
              echo "${'$'}${'$'}" > "${'$'}LOCK_PID_FILE" 2>/dev/null || true
              break
            fi

            if [ -f "${'$'}LOCK_PID_FILE" ]; then
              lock_pid=${'$'}(cat "${'$'}LOCK_PID_FILE" 2>/dev/null)
              if [ -z "${'$'}lock_pid" ]; then
                if [ "${'$'}attempt" -gt 2 ]; then
                  rm -rf "${'$'}LOCK_DIR" 2>/dev/null
                  continue
                fi
              elif ! kill -0 "${'$'}lock_pid" 2>/dev/null; then
                rm -rf "${'$'}LOCK_DIR" 2>/dev/null
                continue
              fi
            else
              if [ "${'$'}attempt" -gt 2 ]; then
                rm -rf "${'$'}LOCK_DIR" 2>/dev/null
                continue
              fi
            fi

            attempt=${'$'}((attempt + 1))
            if [ "${'$'}attempt" -gt 120 ]; then
              progress_echo "Ubuntu install lock timeout"
              return 1
            fi
            sleep 1
          done

          cleanup_install(){
            rm -rf "${'$'}TMP_DIR" 2>/dev/null
            rm -rf "${'$'}LOCK_DIR" 2>/dev/null
          }
          trap 'cleanup_install' EXIT INT TERM

          if [ -f "${'$'}OK_FILE" ]; then
            VERSION=`cat ${'$'}UBUNTU_PATH/etc/issue.net 2>/dev/null`
            progress_echo "Ubuntu ${'$'}L_INSTALLED -> ${'$'}VERSION"
          else
            if [ -f "${'$'}UBUNTU_PATH/etc/issue.net" ]; then
              echo "ok" > "${'$'}OK_FILE" 2>/dev/null || true
              VERSION=`cat ${'$'}UBUNTU_PATH/etc/issue.net 2>/dev/null`
              progress_echo "Ubuntu ${'$'}L_INSTALLED -> ${'$'}VERSION"
            else
              progress_echo "Ubuntu ${'$'}L_NOT_INSTALLED, ${'$'}L_INSTALLING..."
              if [ ! -f "${'$'}HOME/${'$'}UBUNTU" ]; then
                echo "未找到环境包：请把 <环境名>.tar.xz 放到 ${'$'}HOME/rootfs-packs/ 后重开终端" > "${'$'}HOME/linux_env_install_failed.txt"
                progress_echo "未找到环境包"
                cleanup_install
                trap - EXIT INT TERM
                return 1
              fi
              rm -rf "${'$'}TMP_DIR" 2>/dev/null
              mkdir -p "${'$'}TMP_DIR" 2>/dev/null
              progress_echo "Extracting Ubuntu rootfs..."
              # 校验环境包：有 .sha256 就核对，再解压到临时目录；失败写到 files/ 下的标记文件，
              # 让下一次启动能直接告诉用户原因，而不是只报一个初始化超时。
              ENV_PACK="${'$'}HOME/${'$'}UBUNTU"
              if [ -f "${'$'}ENV_PACK.sha256" ] && command -v sha256sum >/dev/null 2>&1; then
                if ! ( cd "${'$'}HOME" && sha256sum -c "${'$'}UBUNTU.sha256" >/dev/null 2>&1 ); then
                  echo "环境包校验失败（sha256 不匹配）" > "${'$'}HOME/linux_env_install_failed.txt"
                  progress_echo "环境包校验失败（sha256 不匹配）"
                  cleanup_install
                  trap - EXIT INT TERM
                  return 1
                fi
              fi
              busybox tar xf "${'$'}ENV_PACK" -C "${'$'}TMP_DIR"/ >"${'$'}HOME/extract.log" 2>&1
              if [ ${'$'}? -ne 0 ]; then
                echo "环境包解压失败（压缩流可能损坏），详见 ${'$'}HOME/extract.log" > "${'$'}HOME/linux_env_install_failed.txt"
                progress_echo "环境包解压失败，详见 ${'$'}HOME/extract.log"
                cleanup_install
                trap - EXIT INT TERM
                return 1
              fi
              EXTR_OK=1
              for EXTR_F in usr/bin/env etc/os-release; do
                if [ ! -e "${'$'}TMP_DIR/${'$'}EXTR_F" ]; then EXTR_OK=0; fi
              done
              if [ "${'$'}EXTR_OK" != "1" ]; then
                echo "环境包不完整：缺少 usr/bin/env 或 etc/os-release" > "${'$'}HOME/linux_env_install_failed.txt"
                progress_echo "环境包不完整（缺少关键文件）"
                cleanup_install
                trap - EXIT INT TERM
                return 1
              fi
              rm -f "${'$'}HOME/linux_env_install_failed.txt" 2>/dev/null
              echo "Extraction complete"
              if [ -d "${'$'}TMP_DIR/${'$'}UBUNTU_NAME" ]; then
                mv "${'$'}TMP_DIR/${'$'}UBUNTU_NAME"/* "${'$'}TMP_DIR"/ 2>/dev/null
                rm -rf "${'$'}TMP_DIR/${'$'}UBUNTU_NAME" 2>/dev/null
              fi

              mkdir -p "${'$'}TMP_DIR/root" 2>/dev/null
              echo 'export ANDROID_DATA=/home/' >> "${'$'}TMP_DIR/root/.bashrc"
              mkdir -p "${'$'}TMP_DIR/etc" 2>/dev/null
              write_default_dns "${'$'}TMP_DIR/etc/resolv.conf"
              echo "ok" > "${'$'}TMP_DIR/.operit_installed_ok" 2>/dev/null || true

              rm -rf "${'$'}UBUNTU_PATH" 2>/dev/null
              mv "${'$'}TMP_DIR" "${'$'}UBUNTU_PATH" 2>/dev/null
              if [ ${'$'}? -ne 0 ]; then
                cleanup_install
                trap - EXIT INT TERM
                return 1
              fi
              rm -f "${'$'}HOME/${'$'}UBUNTU" 2>/dev/null
            fi
          fi

          mkdir -p ${'$'}UBUNTU_PATH/etc 2>/dev/null
          write_default_dns "${'$'}UBUNTU_PATH/etc/resolv.conf"

          rm -rf "${'$'}LOCK_DIR" 2>/dev/null
          trap - EXIT INT TERM
        }
        """.trimIndent()

        val configureSources = """
        configure_sources(){
          # 配置APT源
          cat <<'EOF' > ${'$'}UBUNTU_PATH/etc/apt/sources.list
        # From Operit Settings - ${aptSource.name}
        deb ${aptSource.url} noble main restricted universe multiverse
        deb ${aptSource.url} noble-updates main restricted universe multiverse
        deb ${aptSource.url} noble-backports main restricted universe multiverse
        EOF
          
          # 配置Pip/Uv源
          mkdir -p ${'$'}UBUNTU_PATH/root/.config/pip 2>/dev/null
          echo '[global]' > ${'$'}UBUNTU_PATH/root/.config/pip/pip.conf
          echo 'index-url = ${pipSource.url}' >> ${'$'}UBUNTU_PATH/root/.config/pip/pip.conf
          
          mkdir -p ${'$'}UBUNTU_PATH/root/.config/uv 2>/dev/null
          echo 'index-url = "${pipSource.url}"' > ${'$'}UBUNTU_PATH/root/.config/uv/uv.toml
          
          # 配置NPM源
          mkdir -p ${'$'}UBUNTU_PATH/root 2>/dev/null
          echo 'registry=${npmSource.url}' > ${'$'}UBUNTU_PATH/root/.npmrc
        }
        """.trimIndent()

        val fixPermissions = """
        fix_permissions(){
          echo "Fixing permissions..."
          # Fix "cannot find name for group ID" warnings
          # Append Android groups to Ubuntu /etc/group
          current_groups=$(id -G)
          for gid in ${'$'}current_groups; do
            if ! grep -q ":${'$'}gid:" ${'$'}UBUNTU_PATH/etc/group; then
              echo "android_group_${'$'}gid:x:${'$'}gid:" >> ${'$'}UBUNTU_PATH/etc/group
            fi
          done
          echo "Permissions fixed."
        }
        """.trimIndent()

        // 读取共享tmp设置
        val sharedTmpEnabled = prefs.getBoolean("shared_tmp_enabled", true)
        val prootBindSetup = PRootMountMapping.buildRuntimeBindMounts(
            homeDir = homeDir,
            appDataDir = operitDataDir,
            packageName = operitPackage,
            chrootEnabled = false
        ).toMutableList().apply {
            if (sharedTmpEnabled) {
                add(4, PRootBindMount(tmpDir, "/dev/shm"))
            }
        }.joinToString(separator = "\n") { mount ->
            "          probe_and_append_bind_arg \"${mount.sourcePath}\" \"${mount.targetPath}\""
        }
        
        val loginUbuntu = """
        login_ubuntu(){
          COMMAND_TO_EXEC="$1"
          if [ -z "${'$'}COMMAND_TO_EXEC" ]; then
            COMMAND_TO_EXEC="/bin/bash -il"
          fi

          # Setup fake sysdata
          if [ "${'$'}USE_CHROOT" != "1" ]; then
            export INSTALLED_ROOTFS_DIR=$(dirname "${'$'}UBUNTU_PATH")
            export distro_name=$(basename "${'$'}UBUNTU_PATH")
            
            if [ -f "${'$'}HOME/setup_fake_sysdata.sh" ]; then
                source "${'$'}HOME/setup_fake_sysdata.sh"
                setup_fake_sysdata
            fi
          fi

          # 使用 proot 直接进入解压的 Ubuntu 根文件系统。
          # - 清理并设置 PATH，避免继承宿主 PATH 造成命令找不到或混用 busybox。
          # - 绑定常见伪文件系统、外部存储与 Operit 应用沙箱，保障交互和软件包管理工作正常。
          # 在 proot 环境中创建必要的目录
          mkdir -p "${'$'}UBUNTU_PATH/storage/emulated" 2>/dev/null
          mkdir -p "${'$'}UBUNTU_PATH$operitUserDataMountPath" 2>/dev/null
          mkdir -p "${'$'}UBUNTU_PATH$operitLegacyDataMountPath" 2>/dev/null
          mkdir -p "${'$'}UBUNTU_PATH/data/local/tmp" 2>/dev/null
          mkdir -p "${'$'}UBUNTU_PATH$homeDir" 2>/dev/null

          if [ "${'$'}USE_CHROOT" = "1" ]; then
            CMD_FILE="${'$'}TMPDIR/command_to_exec"
            printf "%s" "${'$'}COMMAND_TO_EXEC" > "${'$'}CMD_FILE" 2>/dev/null || true
            CHROOT_WRAPPER="${'$'}TMPDIR/operit_chroot_wrapper.sh"
            cat > "${'$'}CHROOT_WRAPPER" <<'EOF'
        BIN="$1"
        UBUNTU_PATH="$2"
        CMD_FILE="$3"
        HOME_DIR="$4"
        OPERIT_UID="$5"
        OPERIT_GID="$6"
        OPERIT_GROUPS="$7"
        cleanup_mounts(){
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH/dev/pts" 2>/dev/null || true
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH/dev" 2>/dev/null || true
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH/sys" 2>/dev/null || true
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH/proc" 2>/dev/null || true
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH${'$'}HOME_DIR" 2>/dev/null || true
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH$currentUserDataRootPath" 2>/dev/null || true
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH/data/data" 2>/dev/null || true
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH/data/local/tmp" 2>/dev/null || true
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH/sdcard" 2>/dev/null || true
        }
        cleanup_mounts

        "${'$'}BIN/busybox" mkdir -p "${'$'}UBUNTU_PATH/proc" "${'$'}UBUNTU_PATH/sys" "${'$'}UBUNTU_PATH/dev" "${'$'}UBUNTU_PATH/dev/pts" "${'$'}UBUNTU_PATH/sdcard" "${'$'}UBUNTU_PATH$currentUserDataRootPath" "${'$'}UBUNTU_PATH/data/data" "${'$'}UBUNTU_PATH/data/local/tmp" "${'$'}UBUNTU_PATH${'$'}HOME_DIR" 2>/dev/null
        "${'$'}BIN/busybox" mount -t proc proc "${'$'}UBUNTU_PATH/proc" 2>/dev/null || true
        "${'$'}BIN/busybox" mount --bind /dev "${'$'}UBUNTU_PATH/dev" 2>/dev/null || true
        "${'$'}BIN/busybox" mount --bind /sys "${'$'}UBUNTU_PATH/sys" 2>/dev/null || true
        "${'$'}BIN/busybox" mount --bind /dev/pts "${'$'}UBUNTU_PATH/dev/pts" 2>/dev/null || true
        "${'$'}BIN/busybox" mount --bind $currentEmulatedStoragePath "${'$'}UBUNTU_PATH/sdcard" 2>/dev/null || true
        "${'$'}BIN/busybox" mount --bind $currentUserDataRootPath "${'$'}UBUNTU_PATH$currentUserDataRootPath" 2>/dev/null || true
        "${'$'}BIN/busybox" mount --bind /data/data "${'$'}UBUNTU_PATH/data/data" 2>/dev/null || true
        "${'$'}BIN/busybox" mount --bind /data/local/tmp "${'$'}UBUNTU_PATH/data/local/tmp" 2>/dev/null || true
        "${'$'}BIN/busybox" mount --bind "${'$'}HOME_DIR" "${'$'}UBUNTU_PATH${'$'}HOME_DIR" 2>/dev/null || true
        COMMAND_TO_EXEC="$(cat "${'$'}CMD_FILE" 2>/dev/null)"
        "${'$'}BIN/busybox" chroot "${'$'}UBUNTU_PATH" /usr/bin/env -i HOME=/root TERM=xterm-256color LANG=en_US.UTF-8 PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin "COMMAND_TO_EXEC=${'$'}COMMAND_TO_EXEC" "OPERIT_UID=${'$'}OPERIT_UID" "OPERIT_GID=${'$'}OPERIT_GID" "OPERIT_GROUPS=${'$'}OPERIT_GROUPS" /bin/bash -lc 'echo LOGIN_SUCCESSFUL; echo TERMINAL_READY; umask 0002; if [ -n "${'$'}OPERIT_GID" ]; then chown 0:"${'$'}OPERIT_GID" /root 2>/dev/null || true; chmod 2775 /root 2>/dev/null || true; fi; eval "${'$'}COMMAND_TO_EXEC"'
        ret=${'$'}?
        cleanup_mounts
        exit ${'$'}ret
        EOF
            chmod 700 "${'$'}CHROOT_WRAPPER" 2>/dev/null || true
            exec su -c "sh \"${'$'}CHROOT_WRAPPER\" \"${'$'}BIN\" \"${'$'}UBUNTU_PATH\" \"${'$'}CMD_FILE\" \"${homeDir}\" \"${'$'}OPERIT_UID\" \"${'$'}OPERIT_GID\" \"${'$'}OPERIT_GROUPS\""
          fi
          if ! resolve_proot_runtime; then
            return 1
          fi
$prootBindSetup
          if [ "${'$'}USE_CHROOT" != "1" ]; then
            if [ ! -e /proc/stat ]; then probe_and_append_bind_arg "${'$'}UBUNTU_PATH/proc/.stat" "/proc/stat"; fi
            if [ ! -e /proc/loadavg ]; then probe_and_append_bind_arg "${'$'}UBUNTU_PATH/proc/.loadavg" "/proc/loadavg"; fi
            if [ ! -e /proc/uptime ]; then probe_and_append_bind_arg "${'$'}UBUNTU_PATH/proc/.uptime" "/proc/uptime"; fi
            if [ ! -e /proc/version ]; then probe_and_append_bind_arg "${'$'}UBUNTU_PATH/proc/.version" "/proc/version"; fi
            if [ ! -e /proc/vmstat ]; then probe_and_append_bind_arg "${'$'}UBUNTU_PATH/proc/.vmstat" "/proc/vmstat"; fi
            if [ ! -e /proc/sys/kernel/cap_last_cap ]; then probe_and_append_bind_arg "${'$'}UBUNTU_PATH/proc/.sysctl_entry_cap_last_cap" "/proc/sys/kernel/cap_last_cap"; fi
            if [ ! -e /proc/sys/fs/inotify/max_user_watches ]; then probe_and_append_bind_arg "${'$'}UBUNTU_PATH/proc/.sysctl_inotify_max_user_watches" "/proc/sys/fs/inotify/max_user_watches"; fi
          fi
          if [ -n "${'$'}PROOT_BIND_ARGS" ]; then
            set -- ${'$'}PROOT_BIND_ARGS
          else
            set --
          fi
          if [ "${'$'}PROOT_LINK2SYMLINK" = "1" ]; then
            exec_proot_binary \
              -0 \
              -r "${'$'}UBUNTU_PATH" \
              --link2symlink \
              "${'$'}@" \
              -w /root \
              /usr/bin/env -i \
                HOME=/root \
                TERM=xterm-256color \
                LANG=en_US.UTF-8 \
                PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
                COMMAND_TO_EXEC="${'$'}COMMAND_TO_EXEC" \
                /bin/bash -lc 'echo LOGIN_SUCCESSFUL; echo TERMINAL_READY; eval "${'$'}COMMAND_TO_EXEC"'
          else
            exec_proot_binary \
              -0 \
              -r "${'$'}UBUNTU_PATH" \
              "${'$'}@" \
              -w /root \
              /usr/bin/env -i \
                HOME=/root \
                TERM=xterm-256color \
                LANG=en_US.UTF-8 \
                PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
                COMMAND_TO_EXEC="${'$'}COMMAND_TO_EXEC" \
                /bin/bash -lc 'echo LOGIN_SUCCESSFUL; echo TERMINAL_READY; eval "${'$'}COMMAND_TO_EXEC"'
          fi
        }
        """.trimIndent()

        val sshShell = """
        ssh_shell(){
          set -x
          install_ubuntu
          configure_sources
          fix_permissions
          sleep 1
          bump_progress
          
          # 先进入Ubuntu环境，然后连接SSH
          # 当SSH退出时，用户会回到本地Ubuntu shell
          login_ubuntu 'echo "Connecting to SSH..."; '"${'$'}SSH_COMMAND"'; echo "SSH connection closed. You are now in local Ubuntu terminal."; /bin/bash -il'
        }
        """.trimIndent()

        return """
        $common
        $installUbuntu
        $configureSources
        $fixPermissions
        $loginUbuntu
        $sshShell
        clear_lines
        start_shell(){
          install_ubuntu
          configure_sources
          fix_permissions
          sleep 1
          bump_progress
          login_ubuntu
        }
        """.trimIndent()
    }

    fun closeTerminalSession(sessionId: String) {
        closingSessions.add(sessionId)
        // Delegate to provider to ensure underlying process is killed
        coroutineScope.launch {
            try {
                terminalProvider?.closeSession(sessionId)
            } catch (e: Exception) {
                Log.e(TAG, "Error closing session via provider", e)
            }
        }

        activeSessions[sessionId]?.let { session ->
            session.process.destroy()
            activeSessions.remove(sessionId)
            Log.d(TAG, "Closed and removed session: $sessionId")
        }
    }

    private fun handleRegularCommand(command: String, session: com.ai.assistance.operit.terminal.data.TerminalSessionData, commandId: String) {
        session.currentCommandOutput.clear()
        session.currentOutputLineCount = 0

        val newCommandItem = CommandHistoryItem(
            id = commandId,
            prompt = session.currentDirectory,
            command = command,
            output = "",
            isExecuting = true
        )

        // Set the current executing command reference for efficient access
        session.currentExecutingCommand = newCommandItem

        // 发出命令开始执行事件
        coroutineScope.launch {
            _commandExecutionEvents.emit(CommandExecutionEvent(
                commandId = newCommandItem.id,
                sessionId = session.id,
                outputChunk = "",
                isCompleted = false
            ))
        }
    }

    fun prepareForMaintenance() {
        // 释放 provider 连接
        kotlinx.coroutines.runBlocking {
            terminalProvider?.disconnect()
            Log.d(TAG, "Disconnected terminal provider")
            
            // 停止SSHD服务器
            sshdServerManager.stopServer()
            Log.d(TAG, "Stopped SSHD server")
        }
        terminalProvider = null
        
        activeSessions.keys.toList().forEach { sessionId ->
            closeTerminalSession(sessionId)
        }
        sessionManager.cleanup()
        Log.d(TAG, "Prepared terminal manager for maintenance.")
    }

    fun cleanup() {
        prepareForMaintenance()
        coroutineScope.cancel()
        Log.d(TAG, "All active sessions cleaned up.")
    }

    suspend fun executeHiddenCommand(
        command: String,
        executorKey: String = "default",
        timeoutMs: Long = 120000L
    ): HiddenExecResult {
        val initialized = initializeEnvironment()
        if (!initialized) {
            return HiddenExecResult(
                output = "",
                exitCode = -1,
                state = HiddenExecResult.State.SHELL_START_FAILED,
                error = "Terminal environment initialization failed"
            )
        }

        return getTerminalProvider().executeHiddenCommand(
            command = command,
            executorKey = executorKey,
            timeoutMs = timeoutMs
        )
    }

    /**
     * 获取文件系统提供者
     * 
     * 根据配置返回对应的提供者（本地或SSH）
     * 如果配置了SSH且已连接，返回SSH的文件系统提供者（共享SFTP连接）
     * 否则返回本地文件系统提供者
     */
    fun getFileSystemProvider(): FileSystemProvider = kotlinx.coroutines.runBlocking {
        getTerminalProvider().getFileSystemProvider()
    }
    
    /**
     * 获取SSHD服务器管理器
     * 
     * 用于管理本地SSHD服务器（反向SSH隧道场景）
     */
    fun getSSHDServerManager(): SSHDServerManager = sshdServerManager
}
