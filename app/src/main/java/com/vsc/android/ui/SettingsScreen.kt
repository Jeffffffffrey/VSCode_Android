package com.vsc.android.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.vsc.android.BuildConfig
import com.vsc.android.runtime.LogBuffer
import com.vsc.android.runtime.RuntimeConfig
import com.vsc.android.runtime.RuntimePaths
import com.vsc.android.ui.theme.MonoFontFamily
import com.vsc.android.ui.theme.VscAccentLight
import com.vsc.android.ui.theme.VscGreen
import com.vsc.android.ui.theme.VscHairline
import com.vsc.android.ui.theme.VscOverlayDim
import com.vsc.android.ui.theme.VscRed
import com.vsc.android.ui.theme.VscSurface
import com.vsc.android.ui.theme.VscSurfaceDeep
import com.vsc.android.ui.theme.VscSurfaceRaised
import com.vsc.android.ui.theme.VscTextMuted
import com.vsc.android.ui.theme.VscTextPrimary
import com.vsc.android.ui.theme.VscTextSecondary
import com.vsc.android.webview.WebViewFactory
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun SettingsScreen(
    onDismiss: () -> Unit,
    onRestartService: () -> Unit,
    onResetRuntime: () -> Unit,
) {
    val context = LocalContext.current
    // 初始 false → true 才能触发滑入动画；everVisible 防止刚挂载就被判定为「已关闭」
    var visible by remember { mutableStateOf(false) }
    var everVisible by remember { mutableStateOf(false) }
    var portText by remember { mutableStateOf(RuntimeConfig.port.toString()) }
    var authMode by remember { mutableStateOf(RuntimeConfig.authMode) }
    var password by remember { mutableStateOf(RuntimeConfig.password) }
    var market by remember { mutableStateOf(RuntimeConfig.market) }
    var externalEnabled by remember { mutableStateOf(RuntimeConfig.externalStorageEnabled) }
    var needsRestart by remember { mutableStateOf(false) }
    var storageGranted by remember { mutableStateOf(hasAllFilesAccess(context)) }

    LaunchedEffect(Unit) { visible = true }

    fun close() {
        visible = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(VscOverlayDim)
            .clickableNoRipple { close() },
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally(tween(220)) { it },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .widthIn(min = 340.dp, max = 460.dp)
                    .fillMaxWidth(0.46f)
                    .background(VscSurfaceDeep)
                    .border(1.dp, VscHairline)
                    .clickableNoRipple { },
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 10.dp, top = 16.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("设置", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = VscTextPrimary)
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .clickableNoRipple { close() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = VscTextMuted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 28.dp),
                ) {
                    SectionCard("运行环境") {
                        Text("服务端口", fontSize = 13.sp, color = VscTextSecondary)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = portText,
                                onValueChange = { input ->
                                    portText = input.filter { it.isDigit() }.take(5)
                                },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    fontFamily = MonoFontFamily,
                                    fontSize = 14.sp,
                                    color = VscTextPrimary,
                                ),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = VscAccentLight,
                                    unfocusedBorderColor = VscHairline,
                                    cursorColor = VscAccentLight,
                                ),
                            )
                            Spacer(Modifier.size(10.dp))
                            ActionButton("应用") {
                                val value = portText.toIntOrNull()
                                if (value == null || value !in 1024..65535) {
                                    Toast.makeText(context, "端口需在 1024-65535 之间", Toast.LENGTH_SHORT).show()
                                } else {
                                    RuntimeConfig.setPort(value)
                                    needsRestart = true
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("访问密码", fontSize = 13.sp, color = VscTextSecondary)
                                Text(
                                    "开启后进入编辑器需输入密码",
                                    fontSize = 11.sp,
                                    color = VscTextMuted,
                                )
                            }
                            Switch(
                                checked = authMode == RuntimeConfig.AuthMode.PASSWORD,
                                onCheckedChange = { enabled ->
                                    authMode = if (enabled) {
                                        RuntimeConfig.AuthMode.PASSWORD
                                    } else {
                                        RuntimeConfig.AuthMode.NONE
                                    }
                                    RuntimeConfig.setAuthMode(authMode)
                                    password = RuntimeConfig.password
                                    needsRestart = true
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = VscAccentLight,
                                ),
                            )
                        }

                        if (authMode == RuntimeConfig.AuthMode.PASSWORD && password.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0x14FFFFFF))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    password,
                                    fontFamily = MonoFontFamily,
                                    fontSize = 13.sp,
                                    color = VscGreen,
                                )
                                Text(
                                    "重新生成",
                                    fontSize = 11.sp,
                                    color = VscAccentLight,
                                    modifier = Modifier.clickableNoRipple {
                                        password = RuntimeConfig.regeneratePassword()
                                        needsRestart = true
                                    },
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        ActionButton("重启服务") {
                            onRestartService()
                        }
                    }

                    SectionCard("存储") {
                        Text("公共存储访问", fontSize = 13.sp, color = VscTextSecondary)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (storageGranted) "已授权，可在终端与文件树访问 /sdcard" else "未授权，仅能使用应用私有工作区",
                            fontSize = 11.sp,
                            color = if (storageGranted) VscGreen else VscTextMuted,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ActionButton(if (storageGranted) "重新检查" else "前往授权") {
                                if (hasAllFilesAccess(context)) {
                                    storageGranted = true
                                } else {
                                    openAllFilesAccessSettings(context)
                                }
                            }
                            Spacer(Modifier.size(10.dp))
                            if (storageGranted) {
                                ActionButton("关闭绑定") {
                                    externalEnabled = false
                                    RuntimeConfig.setExternalStorageEnabled(false)
                                    needsRestart = true
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text("默认工作区", fontSize = 13.sp, color = VscTextSecondary)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            RuntimePaths.defaultWorkspaceDir.absolutePath,
                            fontFamily = MonoFontFamily,
                            fontSize = 10.sp,
                            lineHeight = 15.sp,
                            color = VscTextMuted,
                        )
                        if (!externalEnabled) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "已关闭 /sdcard 绑定，重启服务后生效",
                                fontSize = 11.sp,
                                color = VscTextMuted,
                            )
                        }
                    }

                    SectionCard("扩展市场") {
                        MarketRow(
                            title = "Open VSX（推荐）",
                            subtitle = "开源公共市场，允许第三方客户端使用",
                            selected = market == RuntimeConfig.Market.OPEN_VSX,
                        ) {
                            market = RuntimeConfig.Market.OPEN_VSX
                            RuntimeConfig.setMarket(market)
                            needsRestart = true
                        }
                        Spacer(Modifier.height(8.dp))
                        MarketRow(
                            title = "Microsoft Marketplace",
                            subtitle = "扩展数量最多，但用于第三方客户端可能违反其服务条款",
                            selected = market == RuntimeConfig.Market.MICROSOFT,
                            warning = true,
                        ) {
                            market = RuntimeConfig.Market.MICROSOFT
                            RuntimeConfig.setMarket(market)
                            needsRestart = true
                        }
                    }

                    SectionCard("诊断") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ActionButton("导出日志") { exportLogs(context) }
                            Spacer(Modifier.size(10.dp))
                            ActionButton("重置运行环境", danger = true) { onResetRuntime() }
                        }
                        Spacer(Modifier.height(14.dp))
                        InfoRow("应用版本", BuildConfig.VERSION_NAME)
                        InfoRow("code-server", RuntimeConfig.codeServerVersion)
                        InfoRow("运行时版本", RuntimeConfig.runtimeVersion)
                        InfoRow("系统 WebView", WebViewFactory.currentWebViewVersion() ?: "未知")
                        InfoRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    }

                    if (needsRestart) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "以上修改需要重启服务后生效",
                            fontSize = 11.sp,
                            color = VscTextMuted,
                        )
                    }

                    Spacer(Modifier.height(18.dp))
                    Text(
                        "关闭",
                        fontSize = 13.sp,
                        color = VscTextSecondary,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .clickableNoRipple { close() }
                            .padding(10.dp),
                    )
                }
            }
        }
    }

    // 收起动画结束后再真正移除面板
    LaunchedEffect(visible) {
        if (visible) {
            everVisible = true
        } else if (everVisible) {
            delay(230)
            onDismiss()
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(VscSurface)
            .padding(16.dp),
    ) {
        Text(
            title,
            fontSize = 11.sp,
            letterSpacing = 1.2.sp,
            color = VscTextMuted,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        content()
    }
}

@Composable
private fun ActionButton(text: String, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (danger) VscRed.copy(alpha = 0.16f) else Color(0x1AFFFFFF))
            .clickableNoRipple(onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text,
            fontSize = 12.sp,
            color = if (danger) VscRed else VscTextSecondary,
        )
    }
}

@Composable
private fun MarketRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    warning: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) VscSurfaceRaised else Color.Transparent)
            .clickableNoRipple(onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(if (selected) VscAccentLight else Color(0x22FFFFFF)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(6.dp).background(Color.White, CircleShape))
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = VscTextPrimary)
            Text(
                subtitle,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = if (warning) VscTextMuted else VscTextMuted,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 12.sp, color = VscTextMuted)
        Text(value, fontFamily = MonoFontFamily, fontSize = 11.sp, color = VscTextSecondary)
    }
}

private fun hasAllFilesAccess(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    Environment.isExternalStorageManager()
} else {
    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
        PackageManager.PERMISSION_GRANTED
}

private fun openAllFilesAccessSettings(context: Context) {
    val appIntent = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )
    val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    val launched = runCatching { context.startActivity(appIntent) }.isSuccess ||
        runCatching { context.startActivity(fallback) }.isSuccess
    if (!launched) {
        Toast.makeText(context, "当前系统不支持该设置页，请手动在设置中授权", Toast.LENGTH_LONG).show()
    }
}

private fun exportLogs(context: Context) {
    runCatching {
        val file = File(RuntimePaths.logsDir, "diagnostics-${System.currentTimeMillis()}.log")
        file.parentFile?.mkdirs()
        file.writeText(LogBuffer.snapshotText())
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "VS Code for Android 诊断日志")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "导出诊断日志"))
    }.onFailure {
        Toast.makeText(context, "导出失败：${it.message}", Toast.LENGTH_LONG).show()
    }
}
