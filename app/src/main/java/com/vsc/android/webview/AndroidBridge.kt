package com.vsc.android.webview

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.vsc.android.BuildConfig
import com.vsc.android.runtime.RuntimeConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * WebView 与原生之间的桥。
 *
 * Android WebView 的 JS 拿不到系统剪贴板（navigator.clipboard 在无用户手势时被拒绝），
 * VS Code 的「粘贴」会直接失效，因此必须走原生剪贴板。
 */
class AndroidBridge(
    private val context: Context,
    /** 用来取正确的 windowToken；Compose 下 currentFocus 不一定是 WebView */
    private val webView: WebView?,
    private val onToggleFullscreen: () -> Unit,
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun getClipboard(): String = onMain {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = manager?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(context).toString()
        } else {
            ""
        }
    } ?: ""

    @JavascriptInterface
    fun setClipboard(text: String) {
        onMain {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            manager?.setPrimaryClip(ClipData.newPlainText("vscode", text))
        }
    }

    /**
     * 强制收起输入法。
     *
     * WebView 的 IME 适配器只要挂上，之后每次触摸系统都会再弹一次键盘；
     * 页面在点到非编辑区时调用这里把键盘收掉。
     */
    /**
     * 键盘当前是否可见。
     *
     * 页面判断「要不要收键盘」必须依赖真实状态：
     * 只看 DOM 焦点会被 Chromium 的 native-edit-context 误导。
     */
    @JavascriptInterface
    fun isKeyboardShown(): Boolean {
        val view = webView ?: return false
        val insets = ViewCompat.getRootWindowInsets(view) ?: return false
        return insets.isVisible(WindowInsetsCompat.Type.ime())
    }

    /**
     * 显式弹出输入法。
     *
     * 不能只依赖 Chromium 的隐式 showSoftInput：WebView 未持有窗口焦点、
     * 或刚被收过一次时，它会静默失败（表现为「点输入框没键盘」）。
     * 页面在输入框拿到焦点时调用这里兜底。
     */
    @JavascriptInterface
    fun showKeyboard() {
        mainHandler.post {
            val view = webView ?: return@post
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            view.requestFocus()
            imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    @JavascriptInterface
    fun hideKeyboard() {
        // 不能阻塞 JS 线程等待主线程：
        // 这个调用发生在 pointerdown 期间，阻塞会让 Chromium 丢掉随后的 mousedown/click
        mainHandler.post {
            val target = webView ?: return@post
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(target.windowToken, 0)
        }
    }

    @JavascriptInterface
    fun getPort(): Int = RuntimeConfig.port

    @JavascriptInterface
    fun getAppVersion(): String = BuildConfig.VERSION_NAME

    @JavascriptInterface
    fun getCodeServerVersion(): String = RuntimeConfig.codeServerVersion

    @JavascriptInterface
    fun toggleFullscreen() {
        onMain { onToggleFullscreen() }
    }

    private fun <T> onMain(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: T? = null
        val latch = CountDownLatch(1)
        mainHandler.post {
            result = runCatching(block).getOrNull()
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        return result
    }
}
