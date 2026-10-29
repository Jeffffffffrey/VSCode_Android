package com.vsc.android.webview

import android.content.Intent
import android.net.Uri
import android.os.Message
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Log

class VSCodeChromeClient(
    private val fileChooserHost: FileChooserHost,
) : WebChromeClient() {

    companion object {
        private const val TAG = "VSCodeChromeClient"
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

        val child = runCatching { buildChildWebView(parent) }.getOrElse {
            Log.w(TAG, "创建子窗口失败", it)
            return false
        }

        transport.webView = child
        message.sendToTarget()
        PopupWindows.attach(child)
        return true
    }

    private fun buildChildWebView(parent: WebView): WebView {
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
