package com.vsc.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vsc.android.BootstrapViewModel
import com.vsc.android.runtime.BootstrapState
import com.vsc.android.ui.theme.VscBackground
import com.vsc.android.webview.PopupWindows

@Composable
fun VscApp(viewModel: BootstrapViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }

    // 整屏交给 WebView，不做任何 insets 收缩：
    // VS Code 的工作台会自己贴满可视区，额外留白反而会把底部内容顶出屏幕
    Box(
        Modifier
            .fillMaxSize()
            .background(VscBackground),
    ) {
        when (val current = state) {
            is BootstrapState.Ready -> EditorScreen(
                port = current.port,
                onOpenSettings = { showSettings = true },
            )

            is BootstrapState.Failed -> FailureScreen(
                state = current,
                onRetry = viewModel::retry,
            )

            else -> BootstrapScreen(
                state = current,
                onCancel = viewModel::stop,
            )
        }

        // window.open 出来的子窗口（GitHub 授权等），必须用真窗口而不是 iframe
        val popup = PopupWindows.current
        if (popup != null) {
            PopupWindowDialog(webView = popup, onClose = { PopupWindows.close() })
        }

        if (showSettings) {
            SettingsScreen(
                onDismiss = { showSettings = false },
                onRestartService = {
                    showSettings = false
                    viewModel.restart()
                },
                onResetRuntime = {
                    showSettings = false
                    viewModel.resetRuntime()
                },
            )
        }
    }
}
