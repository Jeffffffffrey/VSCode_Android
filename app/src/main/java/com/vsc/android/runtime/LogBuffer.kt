package com.vsc.android.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 环形日志缓冲。
 *
 * UI 通过 [version] 订阅变更、再用 [snapshot] 取快照，避免每一行日志都复制整个列表。
 * 变更通知做了 80ms 节流，防止高频输出把主线程刷爆。
 */
object LogBuffer {

    private const val MAX_LINES = 2000
    private const val BUMP_INTERVAL_MS = 80L

    private val lock = Any()
    private val deque = ArrayDeque<String>(MAX_LINES)

    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    private var lastBump = 0L

    fun append(line: String) {
        val shouldBump = synchronized(lock) {
            deque.addLast(line)
            while (deque.size > MAX_LINES) deque.removeFirst()
            val now = System.currentTimeMillis()
            if (now - lastBump >= BUMP_INTERVAL_MS) {
                lastBump = now
                true
            } else {
                false
            }
        }
        if (shouldBump) _version.value = System.currentTimeMillis()
    }

    fun appendBlock(text: String) {
        text.lineSequence().forEach { if (it.isNotEmpty()) append(it) }
        flush()
    }

    /** 强制推送一次变更，用于进程结束时刷新 UI */
    fun flush() {
        synchronized(lock) { lastBump = System.currentTimeMillis() }
        _version.value = System.currentTimeMillis()
    }

    fun snapshot(): List<String> = synchronized(lock) { deque.toList() }

    fun tail(count: Int): List<String> = synchronized(lock) {
        if (deque.size <= count) deque.toList() else deque.toList().takeLast(count)
    }

    fun snapshotText(): String = synchronized(lock) { deque.joinToString("\n") }

    fun clear() {
        synchronized(lock) { deque.clear() }
        flush()
    }
}
