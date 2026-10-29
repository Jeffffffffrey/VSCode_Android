package com.vsc.android.ui

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vsc.android.ui.theme.VscHairline
import com.vsc.android.ui.theme.VscSurfaceDeep
import com.vsc.android.ui.theme.VscTextMuted
import com.vsc.android.ui.theme.VscTextPrimary

/**
 * 承载 window.open 出来的子 WebView（GitHub 授权、外部文档等）。
 * 不用 iframe 是硬要求：GitHub 登录页禁止被嵌入。
 */
@Composable
fun PopupWindowDialog(webView: WebView, onClose: () -> Unit) {
    BackHandler { onClose() }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.92f)
                .clip(RoundedCornerShape(14.dp))
                .background(VscSurfaceDeep)
                .border(1.dp, VscHairline, RoundedCornerShape(14.dp)),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("登录 / 授权", fontSize = 13.sp, color = VscTextMuted)
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).clickableNoRipple(onClose),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = VscTextPrimary,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
                AndroidView(
                    factory = { webView },
                    modifier = Modifier.fillMaxSize().background(Color.White),
                )
            }
        }
    }
}
