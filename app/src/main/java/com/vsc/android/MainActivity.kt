package com.vsc.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vsc.android.runtime.BootstrapState
import com.vsc.android.service.CodeServerService
import com.vsc.android.ui.VscApp
import com.vsc.android.ui.theme.VscTheme
import com.vsc.android.webview.WebViewHolder

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 必须真正边到边：否则 Compose 只拿到扣除系统栏后的高度（745），
        // 而 Chromium 视口仍按整屏（776）排版，底部 31px 会被裁在 View 之外，
        // 露出 WebView 自身的背景色 —— 就是那条黑边。
        WindowCompat.setDecorFitsSystemWindows(window, false)

        requestNotificationPermission()
        CodeServerService.start(this)
        hideNavigationBar()

        setContent {
            VscTheme {
                val viewModel: BootstrapViewModel = viewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()

                LaunchedEffect(Unit) { viewModel.start() }

                // 首次解压可能持续数分钟，期间不允许息屏
                DisposableEffect(state) {
                    if (state !is BootstrapState.Ready) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                }

                VscApp(viewModel)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 输入法、通知栏下拉等操作会把导航条带回来，重新取到焦点时再收一次
        if (hasFocus) hideNavigationBar()
    }

    /**
     * 隐藏底部导航条。
     *
     * targetSdk 36 强制边到边，导航条会**压在**页面内容之上，
     * 遮住 VS Code 底部状态栏和聊天输入框最后一行；
     * 留出空白又会变成一条黑边。隐藏导航条是唯一两者都不牺牲的做法，
     * 用户仍可从屏幕底部上滑临时唤出。
     */
    private fun hideNavigationBar() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.navigationBars())
    }

    override fun onDestroy() {
        super.onDestroy()
        // 真正退出才销毁 WebView；配置变更走 configChanges 不重建
        if (isFinishing) WebViewHolder.destroy()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION)
        }
    }

    private companion object {
        const val REQUEST_NOTIFICATION = 1001
    }
}
