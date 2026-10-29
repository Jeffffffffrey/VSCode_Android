package com.vsc.android.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vsc.android.runtime.BootstrapState
import com.vsc.android.ui.theme.MonoFontFamily
import com.vsc.android.ui.theme.VscAccentLight
import com.vsc.android.ui.theme.VscBackground
import com.vsc.android.ui.theme.VscGreen
import com.vsc.android.ui.theme.VscRed
import com.vsc.android.ui.theme.VscSurfaceDeep
import com.vsc.android.ui.theme.VscSurfaceDeepest
import com.vsc.android.ui.theme.VscTextMuted
import com.vsc.android.ui.theme.VscTextPrimary
import com.vsc.android.ui.theme.VscTextSecondary
import kotlinx.coroutines.delay

private val STEP_LABELS = listOf(
    "检查运行环境",
    "解压内置资源",
    "解压 Ubuntu 运行环境",
    "安装 VS Code 服务端",
    "写入编辑器配置",
    "启动服务",
)

@Composable
fun BootstrapScreen(
    state: BootstrapState,
    onCancel: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    var showLogs by remember { mutableStateOf(false) }

    val activeStep = when (state) {
        is BootstrapState.Preparing -> state.step
        is BootstrapState.Starting -> STEP_LABELS.size
        else -> 1
    }
    val target = ((activeStep - 1).toFloat() / STEP_LABELS.size).coerceIn(0f, 1f)
    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(650, easing = FastOutSlowInEasing),
        label = "progress",
    )

    // 只按步骤重置计时器：Progress 更新会频繁产生新 state，不能作为 key
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(activeStep) {
        seconds = 0
        while (true) {
            delay(1000)
            seconds++
        }
    }

    val detail = when (state) {
        is BootstrapState.Preparing -> state.detail.ifBlank { state.desc }
        is BootstrapState.Starting -> state.detail
        else -> ""
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(VscSurfaceDeep, VscBackground, VscSurfaceDeepest),
                ),
            ),
    ) {
        // 顶部品牌光晕
        Box(
            Modifier
                .fillMaxWidth()
                .height(460.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(VscAccentLight.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(0f, 0f),
                        radius = 900f,
                    ),
                ),
        )

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp)
                .padding(contentPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))

            ProgressRing(progress = progress)

            Spacer(Modifier.height(26.dp))

            Text(
                "正在准备 VS Code 运行环境",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = VscTextPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "首次启动需要解压约 170MB 资源，请保持屏幕常亮",
                fontSize = 12.sp,
                color = VscTextMuted,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(26.dp))

            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
                STEP_LABELS.forEachIndexed { index, label ->
                    val step = index + 1
                    StepRow(
                        label = label,
                        status = when {
                            step < activeStep -> StepStatus.Done
                            step == activeStep -> StepStatus.Active
                            else -> StepStatus.Pending
                        },
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            Text(
                text = if (detail.isBlank()) "已用时 ${seconds}s" else "已用时 ${seconds}s · $detail",
                fontFamily = MonoFontFamily,
                fontSize = 11.sp,
                color = VscTextMuted,
                maxLines = 1,
            )

            Spacer(Modifier.weight(1f))

            Row(verticalAlignment = Alignment.CenterVertically) {
                PillButton(text = "查看日志") { showLogs = true }
                Spacer(Modifier.width(10.dp))
                PillButton(text = "取消", onClick = onCancel)
            }
            Spacer(Modifier.height(22.dp))
        }

        LogDrawer(visible = showLogs, onDismiss = { showLogs = false })
    }
}

@Composable
fun FailureScreen(
    state: BootstrapState.Failed,
    onRetry: () -> Unit,
) {
    var showLogs by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(VscSurfaceDeep, VscBackground))),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(VscRed.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = VscRed,
                    modifier = Modifier.size(30.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "启动失败",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = VscTextPrimary,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                state.reason,
                fontSize = 13.sp,
                color = VscTextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .widthIn(max = 460.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x14FFFFFF))
                    .padding(16.dp),
            ) {
                Text(
                    state.hint,
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    color = VscTextMuted,
                )
            }
            Spacer(Modifier.height(26.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(VscAccentLight)
                        .clickableNoRipple(onRetry)
                        .padding(horizontal = 26.dp, vertical = 11.dp),
                ) {
                    Text("重试", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
                }
                Spacer(Modifier.width(10.dp))
                PillButton(text = "查看完整日志") { showLogs = true }
            }
        }
        LogDrawer(visible = showLogs, onDismiss = { showLogs = false })
    }
}

private enum class StepStatus { Done, Active, Pending }

@Composable
private fun StepRow(label: String, status: StepStatus) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )

    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            when (status) {
                StepStatus.Done -> Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = VscGreen,
                    modifier = Modifier.size(15.dp),
                )

                StepStatus.Active -> {
                    Box(
                        Modifier
                            .size(15.dp)
                            .alpha(pulse * 0.45f)
                            .background(VscAccentLight, CircleShape),
                    )
                    Box(Modifier.size(7.dp).background(VscAccentLight, CircleShape))
                }

                StepStatus.Pending -> Box(
                    Modifier.size(7.dp).background(Color(0x33FFFFFF), CircleShape),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = if (status == StepStatus.Active) FontWeight.Medium else FontWeight.Normal,
            color = when (status) {
                StepStatus.Done -> VscTextMuted
                StepStatus.Active -> VscTextSecondary
                StepStatus.Pending -> Color(0x66FFFFFF)
            },
        )
    }
}

@Composable
private fun ProgressRing(progress: Float) {
    Box(Modifier.size(188.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 9.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)

            drawArc(
                color = Color(0x14FFFFFF),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (progress > 0f) {
                drawArc(
                    color = VscAccentLight,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${(progress * 100).toInt()}",
                fontSize = 42.sp,
                fontWeight = FontWeight.Light,
                color = VscTextPrimary,
            )
            Text(
                "PERCENT",
                fontSize = 9.sp,
                letterSpacing = 2.sp,
                color = VscTextMuted,
            )
        }
    }
}
