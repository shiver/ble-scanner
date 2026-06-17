package com.example.blescanner

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

private const val BLE_SCAN_SERVICE_TAG = "BleScanService"

class BleScanForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val scanRestartLimiter = ScanRestartLimiter(MIN_SCAN_RESTART_INTERVAL_MS)

    private lateinit var bleScanner: BleScanner
    private var currentScanMode: BleScanMode? = null
    private var currentFilterMode: BleScanFilterMode? = null

    private var scanJob: Job? = null
    private var heartbeatJob: Job? = null
    private var pendingRestartJob: Job? = null
    private var appVisible = true
    private var screenInteractive = true
    private var lastScanStartElapsedMillis = 0L

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.i(BLE_SCAN_SERVICE_TAG, "Screen off detected")
                    screenInteractive = false
                    restartScanningForCurrentState()
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.i(BLE_SCAN_SERVICE_TAG, "Screen on detected")
                    screenInteractive = true
                    restartScanningForCurrentState()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // The Android emulator doesn't support Bluetooth scanning, so the `FakeBleScanner` allows
        // us to at least test the UI without needing a physical device.
        bleScanner = if (DeviceEnvironment.isEmulator()) {
            Log.i(BLE_SCAN_SERVICE_TAG, "Emulator detected; using FakeBleScanner")
            FakeBleScanner()
        } else {
            AndroidBleScanner(this)
        }
        screenInteractive = getSystemService(PowerManager::class.java).isInteractive
        registerReceiver(
            screenStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
        )
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_SET_FOREGROUND_MODE -> {
                appVisible = true
                restartScanningForCurrentState()
            }
            ACTION_SET_BACKGROUND_MODE -> {
                appVisible = false
                restartScanningForCurrentState()
            }
            ACTION_SET_SCREEN_OFF_MODE -> {
                screenInteractive = false
                restartScanningForCurrentState()
            }
            else -> {
                appVisible = true
                restartScanningForCurrentState()
            }
        }

        // If Android decides to kill the service for some reason, try to restart it and redeliver
        // the intent so we can hopefully get back to the right state we were in before the
        // termination (foreground, background, screen off, etc.)
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        scanJob?.cancel()
        heartbeatJob?.cancel()
        pendingRestartJob?.cancel()
        unregisterReceiver(screenStateReceiver)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun restartScanningForCurrentState() {
        restartScanning(BleScanPolicy.configFor(
            appVisible = appVisible,
            screenInteractive = screenInteractive,
        ))
    }

    private fun restartScanning(config: BleScanConfig) {
        restartScanning(scanMode = config.scanMode, filterMode = config.filterMode)
    }

    private fun restartScanning(
        scanMode: BleScanMode,
        filterMode: BleScanFilterMode,
    ) {
        if (
            scanJob?.isActive == true &&
            currentScanMode == scanMode &&
            currentFilterMode == filterMode
        ) {
            return
        }

        // When a scan is active it is possible that we start/restart scanning several times as
        // application state changes:
        // - When the app changes from visible to not-visible and vice versa
        // - or, when the device screen is disabled

        // However, if we do this too frequently Android may decide to disable scanning for a few
        // seconds. To avoid this we intentionally introduce a delay before we start a new scan as
        // determined by `MIN_SCAN_RESTART_INTERVAL_MS`.
        val delayMillis = scanRestartLimiter.delayBeforeRestartMillis(
            lastScanStartElapsedMillis = lastScanStartElapsedMillis,
            nowElapsedMillis = SystemClock.elapsedRealtime(),
        )
        if (delayMillis > 0L) {
            Log.i(
                BLE_SCAN_SERVICE_TAG,
                "Delaying BLE scan restart for ${delayMillis}ms to avoid Android scan frequency limits",
            )
            pendingRestartJob?.cancel()
            pendingRestartJob = serviceScope.launch {
                delay(delayMillis.milliseconds)
                restartScanning(scanMode, filterMode)
            }
            return
        }

        pendingRestartJob?.cancel()
        currentScanMode = scanMode
        currentFilterMode = filterMode
        scanJob?.cancel()
        scanJob = null
        startForegroundScanning(scanMode, filterMode)
    }

    private fun startForegroundScanning(
        scanMode: BleScanMode,
        filterMode: BleScanFilterMode,
    ) {
        if (!canPostForegroundNotification()) {
            stopSelf()
            return
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        if (scanJob?.isActive == true) return

        lastScanStartElapsedMillis = SystemClock.elapsedRealtime()
        startHeartbeat(scanMode, filterMode)

        scanJob = serviceScope.launch {
            bleScanner.scanResults(scanMode, filterMode)
                .catch { stopSelf() }
                .collect { device ->
                    BleScanRepository.update(device)
                }
        }
    }

    // The heartbeat is intended to act as a debug tool for when the application is either not
    // visible or when the screen is off. This way, if no new bluetooth devices are present, we can
    // at least be sure that the foreground service is indeed running as intended.
    private fun startHeartbeat(
        scanMode: BleScanMode,
        filterMode: BleScanFilterMode,
    ) {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (true) {
                Log.i(
                    BLE_SCAN_SERVICE_TAG,
                    "Foreground service alive; scanActive=${scanJob?.isActive == true}; mode=$scanMode filterMode=$filterMode",
                )
                delay(10_000L.milliseconds)
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
            description = "Shows when BLE Scanner is actively scanning"
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
            .setContentText(notificationContentText())
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationContentText()))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppPendingIntent)
            .build()
    }

    private fun notificationContentText(): String =
        BleScanNotificationText.contentText(hasBackgroundLocationPermission())

    private fun hasBackgroundLocationPermission(): Boolean {
        val permission = BluetoothPermissions.backgroundLocationPermission() ?: return true
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val ACTION_STOP = "com.example.blescanner.action.STOP_BACKGROUND_SCAN"
        const val ACTION_SET_FOREGROUND_MODE = "com.example.blescanner.action.SET_FOREGROUND_MODE"
        const val ACTION_SET_BACKGROUND_MODE = "com.example.blescanner.action.SET_BACKGROUND_MODE"
        const val ACTION_SET_SCREEN_OFF_MODE = "com.example.blescanner.action.SET_SCREEN_OFF_MODE"
        private const val NOTIFICATION_CHANNEL_ID = "ble_scanning"
        private const val NOTIFICATION_ID = 1001
        private const val MIN_SCAN_RESTART_INTERVAL_MS = 6_000L
    }
}
