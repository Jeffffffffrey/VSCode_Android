package com.vsc.android.webview

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
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
    @JavascriptInterface
    fun hideKeyboard() {
        onMain {
            val activity = context.findActivity() ?: return@onMain
            val view = activity.currentFocus ?: activity.window.decorView
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
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

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
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
