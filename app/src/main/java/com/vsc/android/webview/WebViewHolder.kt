package com.vsc.android.webview

import android.content.Context
import android.webkit.WebView
import com.vsc.android.runtime.RuntimeConfig

/**
 * WebView 单例。
 *
 * 编辑器整页重建会丢掉工作台状态、打开的文件与滚动位置，
 * 因此 WebView 必须跨重组/跨页面存活，只在真正退出进程时销毁。
 */
object WebViewHolder {

    private var webView: WebView? = null

    val fileChooserHost = FileChooserHost()

    fun obtain(context: Context, onExternalUrl: (String) -> Unit): WebView {
        webView?.let { return it }

        val created = WebViewFactory.create(context)
        created.addJavascriptInterface(AndroidBridge(context) { }, "Android")
        created.webViewClient = VSCodeWebViewClient(onExternalUrl)
        created.webChromeClient = VSCodeChromeClient(fileChooserHost)
        webView = created
        return created
    }

    fun editorUrl(port: Int = RuntimeConfig.port): String = "http://127.0.0.1:$port"

    fun destroy() {
        webView?.let {
            runCatching { it.stopLoading() }
            runCatching { it.destroy() }
        }
        webView = null
    }
}
