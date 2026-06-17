package com.example.blescanner

data class BleScanConfig(
    val scanMode: BleScanMode,
    val filterMode: BleScanFilterMode,
)

object BleScanPolicy {
    fun configFor(
        appVisible: Boolean,
        screenInteractive: Boolean,
    ): BleScanConfig = when {
        !screenInteractive -> BleScanConfig(
            scanMode = BleScanMode.LowPower,
            filterMode = BleScanFilterMode.IBeacon,
        )
        appVisible -> BleScanConfig(
            scanMode = BleScanMode.LowLatency,
            filterMode = BleScanFilterMode.AllDevices,
        )
        else -> BleScanConfig(
            scanMode = BleScanMode.Balanced,
            filterMode = BleScanFilterMode.AllDevices,
        )
    }
}

object BleScanNotificationText {
    fun contentText(hasBackgroundLocationPermission: Boolean): String =
        if (hasBackgroundLocationPermission) {
            "Scanning for nearby BLE devices"
        } else {
            "Background scanning may be limited until all-the-time location is enabled."
        }
}

class ScanRestartLimiter(
    private val minRestartIntervalMillis: Long,
) {
    fun delayBeforeRestartMillis(
        lastScanStartElapsedMillis: Long,
        nowElapsedMillis: Long,
    ): Long {
        if (lastScanStartElapsedMillis == 0L) return 0L

        val elapsedSinceLastStart = nowElapsedMillis - lastScanStartElapsedMillis
        return (minRestartIntervalMillis - elapsedSinceLastStart).coerceAtLeast(0L)
    }
}
