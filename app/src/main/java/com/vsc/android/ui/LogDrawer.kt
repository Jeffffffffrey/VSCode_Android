package com.vsc.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vsc.android.runtime.LogBuffer
import com.vsc.android.ui.theme.MonoFontFamily
import com.vsc.android.ui.theme.VscGreen
import com.vsc.android.ui.theme.VscRed
import com.vsc.android.ui.theme.VscSurfaceRaised
import com.vsc.android.ui.theme.VscTextMuted
import com.vsc.android.ui.theme.VscTextPrimary
import com.vsc.android.ui.theme.VscYellow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogDrawer(visible: Boolean, onDismiss: () -> Unit) {
    if (!visible) return

    val version by LogBuffer.version.collectAsStateWithLifecycle()
    val lines = remember(version) { LogBuffer.snapshot() }
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    val context = LocalContext.current

    LaunchedEffect(lines.size, follow) {
        if (follow && lines.isNotEmpty()) {
            listState.scrollToItem(lines.lastIndex)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161616),
        contentColor = VscTextPrimary,
        dragHandle = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .padding(vertical = 10.dp)
                        .width(38.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(Color(0x33FFFFFF)),
                )
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "运行日志",
                    fontSize = 15.sp,
                    color = VscTextPrimary,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PillButton(text = if (follow) "暂停跟随" else "跟随末尾") { follow = !follow }
                    Spacer(Modifier.width(8.dp))
                    PillButton(text = "复制") {
                        copyToClipboard(context, LogBuffer.snapshotText())
                    }
                }
            }

            if (lines.isEmpty()) {
                Text(
                    "暂无输出",
                    fontFamily = MonoFontFamily,
                    fontSize = 12.sp,
                    color = VscTextMuted,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(460.dp),
                ) {
                    items(lines) { line ->
                        Text(
                            text = line,
                            fontFamily = MonoFontFamily,
                            fontSize = 11.sp,
                            lineHeight = 17.sp,
                            color = lineColor(line),
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
fun PillButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0x1AFFFFFF))
            .clickableNoRipple(onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(text, fontSize = 12.sp, color = VscTextMuted)
    }
}

private fun lineColor(line: String): Color = when {
    line.startsWith("::FAIL::") || line.contains("失败") -> VscRed
    line.startsWith("::STEP::") -> VscTextPrimary
    line.contains("已就绪") || line.contains("完成") -> VscGreen
    line.contains("警告") || line.contains("WARN") -> VscYellow
    else -> Color(0xFFB8BEC4)
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    manager?.setPrimaryClip(ClipData.newPlainText("vscode-log", text))
    Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
}
