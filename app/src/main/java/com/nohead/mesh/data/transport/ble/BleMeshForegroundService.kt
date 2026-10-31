package com.nohead.mesh.data.transport.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.nohead.mesh.MainActivity
import com.nohead.mesh.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Policy-compliant Android Foreground Service (`connectedDevice` type) for maintaining
 * BLE mesh relay and discovery when the user enables background mesh participation.
 */
@AndroidEntryPoint
class BleMeshForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "nohead_mesh_foreground_channel"
        private const val NOTIFICATION_ID = 4001
        const val ACTION_START_MESH = "com.nohead.mesh.action.START_MESH"
        const val ACTION_STOP_MESH = "com.nohead.mesh.action.STOP_MESH"

        fun startService(context: Context) {
            val intent = Intent(context, BleMeshForegroundService::class.java).apply {
                action = ACTION_START_MESH
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, BleMeshForegroundService::class.java).apply {
                action = ACTION_STOP_MESH
            }
            try {
                context.stopService(intent)
            } catch (_: Exception) {
            }
        }
    }

    @Inject
    lateinit var bleMeshTransport: BleMeshTransport

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_MESH) {
            serviceScope.launch {
                bleMeshTransport.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
        }

        serviceScope.launch {
            bleMeshTransport.start()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "No head BLE Mesh Relay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps local Bluetooth LE mesh discovery and multi-hop relay active."
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_vector)
            .setContentTitle(getString(R.string.mesh_service_notification_title))
            .setContentText(getString(R.string.mesh_service_notification_text))
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}

