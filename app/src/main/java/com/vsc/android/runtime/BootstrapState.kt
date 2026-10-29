package com.vsc.android.runtime

/**
 * 引导状态：Service / ViewModel / UI 三层共享的契约。
 */
sealed interface BootstrapState {

    /** 未开始 */
    data object Idle : BootstrapState

    /** 准备中，step 从 1 开始，total 为总步数 */
    data class Preparing(
        val step: Int,
        val total: Int,
        val desc: String,
        val detail: String = "",
    ) : BootstrapState {
        val progress: Float
            get() = if (total <= 0) 0f else (step - 1).toFloat().coerceIn(0f, total.toFloat()) / total
    }

    /** 服务进程已拉起，等待端口响应 */
    data class Starting(val detail: String = "") : BootstrapState

    /** 服务可用 */
    data class Ready(val port: Int) : BootstrapState

    /** 失败，reason 为主因，hint 为可执行建议 */
    data class Failed(val reason: String, val hint: String) : BootstrapState
}
