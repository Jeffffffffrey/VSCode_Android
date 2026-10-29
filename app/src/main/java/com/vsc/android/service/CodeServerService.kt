package com.vsc.android.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.vsc.android.MainActivity
import com.vsc.android.R
import com.vsc.android.runtime.BootstrapState
import com.vsc.android.runtime.RuntimeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 常驻前台服务：保证 code-server 在后台不被系统回收，并提供「停止」入口。
 */
class CodeServerService : Service() {

    companion object {
        const val CHANNEL_ID = "code_server_status"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.vsc.android.action.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CodeServerService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CodeServerService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat(rebuild(getString(R.string.notification_title), "正在准备运行环境"))
        scope.launch {
            RuntimeRepository.state.collect { state -> updateNotification(state) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            RuntimeRepository.stop(this)
            stopSelf()
            return START_NOT_STICKY
        }
        RuntimeRepository.ensureStarted(applicationContext)
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(state: BootstrapState) {
        val (title, text) = when (state) {
            is BootstrapState.Ready ->
                getString(R.string.notification_title) to "http://127.0.0.1:${state.port}"

            is BootstrapState.Preparing ->
                getString(R.string.notification_title) to "准备中 ${state.step}/${state.total} · ${state.desc}"

            is BootstrapState.Starting ->
                getString(R.string.notification_title) to "正在启动服务…"

            is BootstrapState.Failed ->
                getString(R.string.notification_title) to "启动失败：${state.reason}"

            BootstrapState.Idle ->
                getString(R.string.notification_title) to "已停止"
        }
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, rebuild(title, text))
    }

    private fun rebuild(title: String, text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CodeServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .build()
    }
}
