package com.vsc.android.runtime

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 运行时状态的中枢：前台 Service 与 UI 都只和它打交道。
 */
object RuntimeRepository {

    private val _state = MutableStateFlow<BootstrapState>(BootstrapState.Idle)
    val state: StateFlow<BootstrapState> = _state.asStateFlow()

    private var manager: RuntimeProcessManager? = null

    @Synchronized
    fun ensureStarted(context: Context) {
        val app = context.applicationContext
        RuntimePaths.init(app)
        RuntimeConfig.init(app)

        if (_state.value is BootstrapState.Ready) return
        if (manager?.isRunning == true) return
        if (_state.value is BootstrapState.Preparing || _state.value is BootstrapState.Starting) return

        val newManager = RuntimeProcessManager(app) { newState -> _state.value = newState }
        manager = newManager
        // 先占位再启动，避免 Service 与 Activity 同时调用时拉起两个实例
        _state.value = BootstrapState.Preparing(1, RuntimeProcessManager.TOTAL_STEPS, "启动中")
        newManager.start()
    }

    fun restart(context: Context) {
        stop(context)
        ensureStarted(context)
    }

    @Synchronized
    fun stop(context: Context) {
        manager?.stop()
        manager = null
        _state.value = BootstrapState.Idle
    }

    fun markFailed(reason: String, hint: String) {
        _state.value = BootstrapState.Failed(reason, hint)
    }

    /**
     * 重置运行环境：只删解压产物，保留已落盘的归档，下次启动重新解压。
     */
    fun resetRuntime(context: Context) {
        stop(context)
        runCatching { RuntimePaths.rootfsDir.deleteRecursively() }
        runCatching { RuntimePaths.hardLinkMapFile.delete() }
        runCatching { RuntimePaths.bootstrapMarker.delete() }
        runCatching { RuntimePaths.scriptsDir.deleteRecursively() }
        LogBuffer.append("· 运行环境已重置，下次启动将重新解压")
        _state.value = BootstrapState.Idle
    }
}
