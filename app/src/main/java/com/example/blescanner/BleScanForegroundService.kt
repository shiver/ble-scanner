package com.example.blescanner

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class BleScanForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scanJob: Job? = null
    private lateinit var bleScanner: BleScanner

    override fun onCreate() {
        super.onCreate()
        bleScanner = AndroidBleScanner(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_SET_FOREGROUND_MODE -> restartScanning(BleScanMode.LowLatency)
            ACTION_SET_BACKGROUND_MODE -> restartScanning(BleScanMode.Balanced)
            else -> startForegroundScanning(BleScanMode.Balanced)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scanJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun restartScanning(scanMode: BleScanMode) {
        scanJob?.cancel()
        scanJob = null
        startForegroundScanning(scanMode)
    }

    private fun startForegroundScanning(scanMode: BleScanMode) {
        if (!canPostForegroundNotification()) {
            stopSelf()
            return
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        if (scanJob?.isActive == true) return

        scanJob = serviceScope.launch {
            bleScanner.scanResults(scanMode)
                .catch { stopSelf() }
                .collect { device ->
                    BleScanRepository.update(device)
                }
        }
    }

    private fun canPostForegroundNotification(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "BLE scanning",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows when BLE Scanner is scanning in the background"
        }

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("BLE Scanner")
            .setContentText("Scanning for nearby BLE devices")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppPendingIntent)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.example.blescanner.action.STOP_BACKGROUND_SCAN"
        const val ACTION_SET_FOREGROUND_MODE = "com.example.blescanner.action.SET_FOREGROUND_MODE"
        const val ACTION_SET_BACKGROUND_MODE = "com.example.blescanner.action.SET_BACKGROUND_MODE"
        private const val NOTIFICATION_CHANNEL_ID = "ble_scanning"
        private const val NOTIFICATION_ID = 1001
    }
}
