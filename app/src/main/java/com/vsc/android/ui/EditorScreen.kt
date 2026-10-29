package com.vsc.android.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.vsc.android.ui.theme.VscBackground
import com.vsc.android.ui.theme.VscSurface
import com.vsc.android.ui.theme.VscTextMuted
import com.vsc.android.ui.theme.VscTextPrimary
import com.vsc.android.webview.WebViewHolder
import kotlinx.coroutines.delay

@Composable
fun EditorScreen(
    port: Int,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val webView = remember(context) {
        WebViewHolder.obtain(context) { url -> openExternal(context, url) }
    }

    var interactionTick by remember { mutableIntStateOf(0) }
    var toolbarVisible by remember { mutableStateOf(true) }
    var showExitDialog by remember { mutableStateOf(false) }
    var immersive by remember { mutableStateOf(false) }

    LaunchedEffect(interactionTick) {
        toolbarVisible = true
        delay(3000)
        toolbarVisible = false
    }

    LaunchedEffect(port) {
        val target = "http://127.0.0.1:$port"
        if (webView.url != target) webView.loadUrl(target)
    }

    // 文件选择器：参考实现缺失这一环，导致「打开文件」点了没反应
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        val uris = when {
            data?.clipData != null -> {
                val clip = data.clipData!!
                (0 until clip.itemCount).map { clip.getItemAt(it).uri }
            }

            data?.data != null -> listOf(data.data!!)
            else -> emptyList()
        }
        WebViewHolder.fileChooserHost.deliver(uris)
    }

    DisposableEffect(filePicker) {
        WebViewHolder.fileChooserHost.launcher = { intent -> filePicker.launch(intent) }
        onDispose { WebViewHolder.fileChooserHost.launcher = null }
    }

    BackHandler {
        if (webView.canGoBack()) webView.goBack() else showExitDialog = true
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(VscBackground)
            // 用 Initial pass 旁路观察触摸，不消费事件，WebView 仍能正常滚动点击
            .pointerInput(Unit) {
                var lastBump = 0L
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.pressed }) {
                            val now = System.currentTimeMillis()
                            if (now - lastBump > 400L) {
                                lastBump = now
                                interactionTick++
                            }
                        }
                    }
                }
            },
    ) {
        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize(),
        )

        AnimatedVisibility(
            visible = toolbarVisible,
            enter = fadeIn(tween(140)),
            exit = fadeOut(tween(240)),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
        ) {
            Column(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xCC252526))
                    .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(50))
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ToolbarButton(
                    onClick = {
                        interactionTick++
                        webView.reload()
                    },
                    content = {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "刷新",
                            tint = VscTextMuted,
                            modifier = Modifier.size(19.dp),
                        )
                    },
                )

                ToolbarButton(
                    onClick = {
                        interactionTick++
                        immersive = !immersive
                        setImmersive(activity, immersive)
                    },
                    content = { FullscreenGlyph(expanded = immersive) },
                )

                ToolbarButton(
                    onClick = {
                        interactionTick++
                        onOpenSettings()
                    },
                    content = {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "设置",
                            tint = VscTextMuted,
                            modifier = Modifier.size(19.dp),
                        )
                    },
                )
            }
        }
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            containerColor = VscSurface,
            titleContentColor = VscTextPrimary,
            textContentColor = VscTextMuted,
            title = { Text("要离开编辑器吗？") },
            text = { Text("最小化后服务仍在后台运行，下次打开可秒进编辑器。") },
            confirmButton = {
                TextButton(onClick = {
                    showExitDialog = false
                    activity?.moveTaskToBack(true)
                }) { Text("最小化", color = VscTextPrimary) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showExitDialog = false
                    activity?.finish()
                }) { Text("退出") }
            },
        )
    }
}

@Composable
private fun ToolbarButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickableNoRipple(onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** 四角括号图标，避免引入 material-icons-extended 这个 10MB 级依赖 */
@Composable
private fun FullscreenGlyph(expanded: Boolean) {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        val len = w * 0.34f
        val sw = w * 0.09f
        val c = VscTextMuted.copy(alpha = 0.85f)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(c, Offset(x1, y1), Offset(x2, y2), sw, StrokeCap.Round)

        if (expanded) {
            // 向内收起
            line(0f, len, len, len)
            line(len, len, len, 0f)
            line(w - len, 0f, w - len, len)
            line(w - len, len, w, len)
            line(w, h - len, w - len, h - len)
            line(w - len, h - len, w - len, h)
            line(len, h, len, h - len)
            line(len, h - len, 0f, h - len)
        } else {
            line(0f, len, 0f, 0f)
            line(0f, 0f, len, 0f)
            line(w - len, 0f, w, 0f)
            line(w, 0f, w, len)
            line(w, h - len, w, h)
            line(w, h, w - len, h)
            line(len, h, 0f, h)
            line(0f, h, 0f, h - len)
        }
    }
}

private fun setImmersive(activity: Activity?, hidden: Boolean) {
    val target = activity ?: return
    val controller = WindowInsetsControllerCompat(target.window, target.window.decorView)
    if (hidden) {
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    } else {
        controller.show(WindowInsetsCompat.Type.systemBars())
    }
}

private fun openExternal(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
