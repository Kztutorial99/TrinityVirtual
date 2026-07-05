package com.trinityvirtual.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.trinityvirtual.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VirtualCoreService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val CHANNEL_ID = "trinity_virtual_channel"
    private val NOTIF_ID = 1001

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        serviceScope.launch {
            when (intent?.action) {
                ACTION_START_ROOT -> RootEngine.startRootEnvironment()
                ACTION_STOP_ROOT -> RootEngine.stopRootEnvironment()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "TrinityVirtual Engine",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Virtual environment running" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TrinityVirtual")
            .setContentText("Virtual environment active")
            .setSmallIcon(R.drawable.ic_trinity_notif)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val ACTION_START_ROOT = "com.trinityvirtual.START_ROOT"
        const val ACTION_STOP_ROOT = "com.trinityvirtual.STOP_ROOT"
    }
}
