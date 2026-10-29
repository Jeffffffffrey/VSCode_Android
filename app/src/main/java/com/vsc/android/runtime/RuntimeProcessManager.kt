package com.vsc.android.runtime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * 引导与服务的全生命周期管理。
 *
 * 流程（共 6 步）：
 * 1. 检查运行环境（可执行组件 / 存储空间）
 * 2. 解压内置资源（APK assets → files/runtime）
 * 3. 解压 Ubuntu 运行环境
 * 4. 安装 VS Code 服务端
 * 5. 写入编辑器配置
 * 6. 启动服务（proot 常驻）
 */
class RuntimeProcessManager(
    private val context: Context,
    private val onState: (BootstrapState) -> Unit,
) {

    companion object {
        private const val TAG = "RuntimeProcess"
        const val TOTAL_STEPS = 6
        private const val READY_TIMEOUT_MS = 180_000L
        private const val PROBE_INTERVAL_MS = 400L
        private const val PROGRESS_EMIT_EVERY = 2000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var serverProcess: Process? = null

    @Volatile
    private var cancelled = false

    private val pumpJobs: MutableList<Job> = mutableListOf()

    val isRunning: Boolean
        get() = serverProcess?.isAlive == true

    fun start() {
        scope.launch {
            try {
                bootstrap()
            } catch (t: Throwable) {
                Log.e(TAG, "引导失败", t)
                if (!cancelled) onState(toFailedState(t))
            }
        }
    }

    fun stop() {
        cancelled = true
        pumpJobs.forEach { runCatching { it.cancel() } }
        pumpJobs.clear()
        runCatching { serverProcess?.destroy() }
        serverProcess = null

        // proot 是父进程，code-server 是它的子进程；父进程被 kill 后子进程可能残留，
        // 这里按特征名兜底清理，避免端口被占导致下次启动失败
        runCatching {
            ProcessBuilder(
                "/system/bin/sh",
                "-c",
                "pkill -9 -f 'opt/code-server' 2>/dev/null; pkill -9 -f 'bin/code-server' 2>/dev/null",
            ).start().waitFor()
        }
        onState(BootstrapState.Idle)
    }

    // ---------------------------------------------------------------- bootstrap

    private suspend fun bootstrap() {
        val port = RuntimeConfig.port

        emit(BootstrapState.Preparing(1, TOTAL_STEPS, "检查运行环境"))

        // 端口已在本机监听：说明旧实例仍在跑，直接复用，不重复拉起
        if (PortProbe.isServing(port)) {
            Log.i(TAG, "端口 $port 已就绪，复用现有服务")
            log("检测到已有服务在 127.0.0.1:$port 运行，直接复用")
            onState(BootstrapState.Ready(port))
            return
        }

        RuntimePaths.ensureDirs()
        val extractor = AssetExtractor(context)
        extractor.ensureFreeSpace()
        NativeBinaryInstaller().install()

        emit(BootstrapState.Preparing(2, TOTAL_STEPS, "解压内置资源"))
        extractor.extractIfNeeded { name, fraction ->
            emit(
                BootstrapState.Preparing(
                    step = 2,
                    total = TOTAL_STEPS,
                    desc = "解压内置资源",
                    detail = "$name  ${(fraction * 100).toInt()}%",
                ),
            )
        }

        installRootfs()

        installCodeServer()

        emit(BootstrapState.Preparing(5, TOTAL_STEPS, "写入编辑器配置"))
        val builder = BootstrapScriptBuilder(context)
        builder.applyRootfsConfig()
        builder.applyEditorConfig()
        RuntimePaths.bootstrapMarker.writeText(System.currentTimeMillis().toString())

        emit(BootstrapState.Preparing(6, TOTAL_STEPS, "启动服务"))
        val runScript = builder.buildRunScript()
        launchServer(runScript)

        emit(BootstrapState.Starting("等待 127.0.0.1:$port 响应…"))
        awaitReady(port)
    }

    /** 解压 Ubuntu rootfs；已解压过则直接跳过 */
    private fun installRootfs() {
        val step = 3
        val rootfs = RuntimePaths.rootfsDir
        val marker = File(rootfs, ".extract-ok")

        if (marker.exists() && File(rootfs, "etc").isDirectory) {
            log("运行环境已存在，跳过解压")
            emit(BootstrapState.Preparing(step, TOTAL_STEPS, "解压 Ubuntu 运行环境", "已就绪"))
            return
        }

        if (rootfs.exists()) {
            log("清理不完整的运行环境")
            rootfs.deleteRecursively()
        }

        val archive = File(RuntimePaths.runtimeAssets, "rootfs.tgz")
        if (!archive.exists()) {
            throw IllegalStateException("缺少 rootfs 归档，无法继续")
        }

        emit(BootstrapState.Preparing(step, TOTAL_STEPS, "解压 Ubuntu 运行环境", "准备中…"))
        val entries = TarExtractor.extract(archive, rootfs) { count, current ->
            if (count % PROGRESS_EMIT_EVERY == 0L) {
                emit(
                    BootstrapState.Preparing(
                        step = step,
                        total = TOTAL_STEPS,
                        desc = "解压 Ubuntu 运行环境",
                        detail = "已解压 $count 个文件 · ${current.takeLast(40)}",
                    ),
                )
            }
        }

        flattenIfNested(rootfs)
        if (!File(rootfs, "etc").isDirectory) {
            throw IllegalStateException("rootfs 解压后缺少 /etc 目录")
        }
        marker.writeText(System.currentTimeMillis().toString())
        log("运行环境解压完成，共 $entries 个条目")
    }

    /** 解压 code-server 到 rootfs/opt */
    private fun installCodeServer() {
        val step = 4
        val target = RuntimePaths.codeServerDir
        val marker = File(target, ".extract-ok")
        val executable = File(target, "bin/code-server")

        if (marker.exists() && executable.exists()) {
            log("编辑器已安装，跳过解压")
            emit(BootstrapState.Preparing(step, TOTAL_STEPS, "安装 VS Code 服务端", "已就绪"))
            return
        }

        val archive = File(RuntimePaths.runtimeAssets, "code-server-arm64.tgz")
        if (!archive.exists()) {
            throw IllegalStateException("缺少 code-server 归档，无法继续")
        }

        val optDir = File(RuntimePaths.rootfsDir, "opt")
        optDir.mkdirs()

        emit(BootstrapState.Preparing(step, TOTAL_STEPS, "安装 VS Code 服务端", "准备中…"))
        val entries = TarExtractor.extract(archive, optDir) { count, current ->
            if (count % PROGRESS_EMIT_EVERY == 0L) {
                emit(
                    BootstrapState.Preparing(
                        step = step,
                        total = TOTAL_STEPS,
                        desc = "安装 VS Code 服务端",
                        detail = "已解压 $count 个文件 · ${current.takeLast(40)}",
                    ),
                )
            }
        }

        if (!executable.exists()) {
            throw IllegalStateException("code-server 可执行文件缺失：${executable.absolutePath}")
        }
        marker.writeText(System.currentTimeMillis().toString())
        log("编辑器解压完成，共 $entries 个条目")
    }

    /**
     * 部分 rootfs 归档把所有内容放在单一顶层目录里，
     * 这里检测并把内容上移一层，保证 `etc/` 直接位于 rootfs 根下。
     */
    private fun flattenIfNested(rootfs: File) {
        if (File(rootfs, "etc").isDirectory) return
        val top = rootfs.listFiles()?.filter { it.isDirectory }?.singleOrNull() ?: return
        if (!File(top, "etc").isDirectory) return

        log("展平嵌套的 rootfs 目录：${top.name}")
        top.listFiles()?.forEach { child -> child.renameTo(File(rootfs, child.name)) }
        top.delete()
    }

    // ------------------------------------------------------------------ process

    private fun launchServer(script: File) {
        val process = createProcess(script)
        serverProcess = process
        pumpJobs += scope.launch { pumpOutput(process) }
    }

    private fun createProcess(script: File): Process {
        val builder = ProcessBuilder("/system/bin/sh", script.absolutePath)
        builder.directory(RuntimePaths.usrDir)
        builder.redirectErrorStream(true)
        val env = builder.environment()
        env["HOME"] = RuntimePaths.homeDir.absolutePath
        env["TMPDIR"] = RuntimePaths.tmpDir.absolutePath
        env["PREFIX"] = RuntimePaths.usrDir.absolutePath
        env["TERMUX_PREFIX"] = RuntimePaths.usrDir.absolutePath
        env["PATH"] = "${RuntimePaths.binDir.absolutePath}:/system/bin:/system/xbin:/vendor/bin"
        env["PROOT_LOADER"] = File(RuntimePaths.binDir, "loader").absolutePath
        env["PROOT_TMP_DIR"] = RuntimePaths.tmpDir.absolutePath
        env["LD_LIBRARY_PATH"] = RuntimePaths.binDir.absolutePath
        env["TERM"] = "xterm-256color"
        env["LANG"] = "C.UTF-8"

        val process = builder.start()
        // 脚本不需要交互输入，及时关闭 stdin 防止子进程阻塞在读取上
        runCatching { process.outputStream.close() }
        return process
    }

    private fun pumpOutput(process: Process) {
        runCatching {
            BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    handleLine(line)
                }
            }
        }.onFailure { Log.w(TAG, "读取输出中断", it) }
        LogBuffer.flush()
    }

    private fun handleLine(line: String) {
        // 控制标记不打进用户日志，避免同一条信息重复出现
        if (!line.startsWith("::")) LogBuffer.append(line)
        when {
            line.startsWith("::STEP::") -> {
                val parts = line.split("::", limit = 4)
                if (parts.size >= 4) {
                    val step = parts[2].toIntOrNull() ?: return
                    if (!cancelled) onState(BootstrapState.Preparing(step, TOTAL_STEPS, parts[3]))
                }
            }

            line.startsWith("::LOG::") -> log(line.removePrefix("::LOG::"))
            line.startsWith("::FAIL::") -> Log.e(TAG, "脚本失败：${line.removePrefix("::FAIL::")}")
            else -> Unit
        }
    }

    // ------------------------------------------------------------------- ready

    private suspend fun awaitReady(port: Int) {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (cancelled) return

            if (PortProbe.isServing(port)) {
                log("服务已就绪：http://127.0.0.1:$port")
                onState(BootstrapState.Ready(port))
                return
            }

            val process = serverProcess
            if (process != null && !process.isAlive) {
                val code = runCatching { process.exitValue() }.getOrDefault(-1)
                throw IllegalStateException("服务进程已退出（退出码 $code）")
            }

            delay(PROBE_INTERVAL_MS)
        }
        throw IllegalStateException("等待服务响应超时（${READY_TIMEOUT_MS / 1000} 秒）")
    }

    // ------------------------------------------------------------------ helpers

    private fun emit(state: BootstrapState) {
        if (!cancelled) onState(state)
    }

    private fun log(message: String) {
        LogBuffer.append("· $message")
    }

    private fun toFailedState(t: Throwable): BootstrapState.Failed {
        val reason = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
        val tail = LogBuffer.tail(30).joinToString("\n")
        val hint = when {
            reason.contains("缺少内置可执行组件") || reason.contains("libproot") ->
                "执行 scripts\\prepare-jnilibs.ps1 提取 proot 及其依赖后重新打包安装。"

            reason.contains("没有内置运行时资源") || reason.contains("缺少") ->
                "执行 scripts\\fetch-runtime.ps1 下载 code-server 与 Ubuntu rootfs 后重新打包。"

            reason.contains("空间不足") ->
                "清理平板存储，至少保证 2.2GB 可用空间后重试。"

            reason.contains("超时") ->
                "在设置中换一个端口后重试；若仍失败，展开日志查看最后几行。"

            reason.contains("退出码") ->
                "展开日志查看最后 30 行，通常能直接看到 proot 或 rootfs 的问题。"

            else -> "展开日志查看详情，或在设置页导出诊断日志。"
        }
        Log.e(TAG, "失败原因：$reason\n$tail")
        return BootstrapState.Failed(reason, hint)
    }
}
