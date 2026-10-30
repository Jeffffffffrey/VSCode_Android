package com.vsc.android.webview

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class VSCodeChromeClient(
    private val fileChooserHost: FileChooserHost,
) : WebChromeClient() {

    companion object {
        private const val TAG = "VSCodeChromeClient"

        /** 子窗口迟迟加载不出真实页面就销毁，避免弹出一个永久空白的黑框 */
        private const val POPUP_IDLE_TIMEOUT_MS = 3000L
    }

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?,
    ): Boolean {
        val callback = filePathCallback ?: return false
        return fileChooserHost.handle(fileChooserParams, callback)
    }

    /**
     * VS Code 的登录/扩展授权都走 window.open。
     * 返回 true 并用 WebViewTransport 交付一个真实子窗口，
     * 才能避免授权页被塞进 iframe（GitHub 会 X-Frame-Options: deny）。
     */
    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?,
    ): Boolean {
        val parent = view ?: return false
        val message = resultMsg ?: return false

        val transport = message.obj as? WebView.WebViewTransport ?: return false

        // 记录子窗口是否加载出了真实内容，用于决定是否展示对话框
        var hasContent = false

        val child = runCatching {
            buildChildWebView(parent) { hasContent = true }
        }.getOrElse {
            Log.w(TAG, "创建子窗口失败", it)
            return false
        }

        transport.webView = child
        message.sendToTarget()

        // 纯空白弹窗（window.open 后没有导航）不该打扰用户，超时直接销毁
        Handler(Looper.getMainLooper()).postDelayed({
            if (!hasContent) {
                Log.d(TAG, "子窗口无内容，销毁")
                PopupWindows.closeIf(child)
                runCatching { child.destroy() }
            }
        }, POPUP_IDLE_TIMEOUT_MS)

        return true
    }

    private fun buildChildWebView(parent: WebView, onContentLoaded: () -> Unit): WebView {
        val child = WebView(parent.context)
        child.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            setSupportMultipleWindows(true)
            userAgentString = parent.settings.userAgentString
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(child, true)

        child.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val scheme = uri.scheme ?: return false
                if (scheme == "http" || scheme == "https") return false

                // vscode://、vscode-server:// 等自定义协议交回系统（= 交回本应用以外）
                runCatching {
                    v.context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PopupWindows.close()
                return true
            }

            override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(v, url, favicon)
                // 有真实内容才开始显示对话框。
                // 直接 attach 的话，window.open 出来的空白窗口会立刻弹出全屏黑框挡住界面
                if (!url.isNullOrBlank() && url != "about:blank") {
                    onContentLoaded()
                    PopupWindows.attach(v)
                }
            }

            override fun onPageFinished(v: WebView, url: String?) {
                super.onPageFinished(v, url)
                // 授权完成后通常会跳回本机服务，此时自动收起子窗口
                if (url != null && url.contains("127.0.0.1") && url.contains("callback")) {
                    PopupWindows.close()
                }
            }
        }

        child.webChromeClient = VSCodeChromeClient(fileChooserHost)
        return child
    }
}
