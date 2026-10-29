package com.vsc.android.webview

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
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
