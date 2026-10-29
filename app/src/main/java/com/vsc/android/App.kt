package com.vsc.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.vsc.android.runtime.RuntimeConfig
import com.vsc.android.runtime.RuntimePaths
import com.vsc.android.service.CodeServerService

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        RuntimePaths.init(this)
        RuntimeConfig.init(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CodeServerService.CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
