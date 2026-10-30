package com.vsc.android.webview

import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 承接 `window.open` 产生的子窗口。
 *
 * VS Code 的登录/授权流程在桌面端走独立 BrowserWindow。
 * 若 `onCreateWindow` 直接返回 false，它会退回 iframe 渲染授权页，
 * 而 GitHub 登录页带 X-Frame-Options: deny，结果就是一个只画出半截按钮的白框。
 *
 * 这里创建一个真实的子 WebView 并交给 Compose 以对话框形式展示。
 * 子 WebView 与主 WebView 共用同一个 CookieManager，
 * 因此授权回调写下的会话 Cookie 能被编辑器直接读到。
 */
object PopupWindows {

    var current: WebView? by mutableStateOf(null)
        private set

    fun attach(webView: WebView) {
        closeCurrent()
        current = webView
    }

    fun close() {
        closeCurrent()
        current = null
    }

    /** 仅当当前展示的正是这个窗口时才关闭，避免误关后来打开的其他窗口 */
    fun closeIf(webView: WebView) {
        if (current === webView) close()
    }

    private fun closeCurrent() {
        current?.let {
            runCatching { it.stopLoading() }
            runCatching { it.destroy() }
        }
    }
}
