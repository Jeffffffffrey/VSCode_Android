package com.vsc.android.webview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.webkit.CookieManager
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import com.vsc.android.BuildConfig

object WebViewFactory {

    /**
     * VS Code Web 工作台依赖大量浏览器能力，缺一不可：
     * - DOM Storage：工作台布局/打开的文件状态存在 localStorage，缺了每次都是空白工作台
     * - JavaScript：核心
     * - MultipleWindows：扩展与外部链接会走 window.open
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun create(context: Context): WebView = WebView(context).apply {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            useWideViewPort = true
            // 必须关闭：与 useWideViewPort 同开时，Android WebView 会把 layout viewport 高度算成 0，
            // 导致页面里所有 vh 单位为 0（VS Code 的对话框用 max-height:90vh，于是全被裁成一条）
            loadWithOverviewMode = false
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "$userAgentString VSCodeAndroid/${BuildConfig.VERSION_NAME}"
        }
        // 扩展市场与 GitHub 授权依赖第三方 Cookie，Android WebView 默认拦截
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        setBackgroundColor(Color.parseColor("#1E1E1E"))
        // Compose 的 AndroidView 不会自动把焦点交给子 View，
        // 没有焦点时 WebView 的 onCreateInputConnection 不会被调用，软键盘就唤不出来
        isFocusable = true
        isFocusableInTouchMode = true
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
    }

    /** 系统 WebView 版本过低会导致白屏，启动前给用户明确提示 */
    fun currentWebViewVersion(): String? =
        runCatching { WebView.getCurrentWebViewPackage()?.versionName }.getOrNull()
}
