package com.example.blescanner

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val BLE_SCANNER_TAG = "BleScanner"

interface BleScanner {
    fun scanResults(): Flow<BleDevice>
}

class AndroidBleScanner(
    context: Context,
) : BleScanner {
    private val applicationContext = context.applicationContext
    private val bluetoothManager =
        applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter

    @SuppressLint("MissingPermission")
    override fun scanResults(): Flow<BleDevice> = callbackFlow {
        Log.i(BLE_SCANNER_TAG, "scanResults collection started")
        // Importantly we don't cache things like whether or not we have the necessary permissions, or
        // the bluetooth devices, since this can change between scanning sessions.
        if (!hasRequiredPermissions()) {
            Log.w(BLE_SCANNER_TAG, "Cannot start scan: missing permissions ${requiredPermissions()}")
            close(SecurityException("Missing required Bluetooth scan permissions"))
            return@callbackFlow
        }

        if (!applicationContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Log.w(BLE_SCANNER_TAG, "Cannot start scan: Bluetooth LE is unavailable")
            close(IllegalStateException("Bluetooth LE is not available on this device"))
            return@callbackFlow
        }

        if (bluetoothAdapter == null) {
            Log.w(BLE_SCANNER_TAG, "Cannot start scan: Bluetooth adapter is unavailable")
            close(IllegalStateException("Bluetooth is not available on this device"))
            return@callbackFlow
        }

        if (!bluetoothAdapter.isEnabled) {
            Log.w(BLE_SCANNER_TAG, "Cannot start scan: Bluetooth is disabled")
            close(IllegalStateException("Bluetooth is disabled"))
            return@callbackFlow
        }

        val bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
        if (bluetoothLeScanner == null) {
            Log.w(BLE_SCANNER_TAG, "Cannot start scan: Bluetooth LE scanner is unavailable")
            close(IllegalStateException("Bluetooth LE scanner is not available"))
            return@callbackFlow
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                Log.i(BLE_SCANNER_TAG, "Scan result: address=${result.device.address}, rssi=${result.rssi}")
                trySend(result.toBleDevice())
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                Log.i(BLE_SCANNER_TAG, "Batch scan results: count=${results.size}")
                results.forEach { result -> trySend(result.toBleDevice()) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(BLE_SCANNER_TAG, "BLE scan failed with error code $errorCode")
                close(IllegalStateException("BLE scan failed with error code $errorCode"))
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            Log.i(BLE_SCANNER_TAG, "Starting BLE scan")
            bluetoothLeScanner.startScan(null, settings, callback)
        } catch (securityException: SecurityException) {
            close(securityException)
            return@callbackFlow
        }

        awaitClose {
            try {
                Log.i(BLE_SCANNER_TAG, "Stopping BLE scan")
                bluetoothLeScanner.stopScan(callback)
            } catch (_: SecurityException) {
                // Permission may have been revoked while scanning. The scan is already ending.
            }
        }
    }

    fun hasRequiredPermissions(): Boolean = requiredPermissions().all { permission ->
        ContextCompat.checkSelfPermission(applicationContext, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun requiredPermissions(): List<String> = requiredPermissionsForSdk(Build.VERSION.SDK_INT)

    companion object {
        fun requiredPermissionsForSdk(sdkInt: Int): List<String> =
            BluetoothPermissions.runtimePermissionsForSdk(sdkInt)
    }
}

@SuppressLint("MissingPermission")
private fun ScanResult.toBleDevice(): BleDevice = BleDevice(
    name = scanRecord?.deviceName ?: device.name,
    address = device.address,
    rssi = rssi,
    lastSeenMillis = System.currentTimeMillis(),
    iBeacon = IBeaconParser.parse(scanRecord?.bytes),
)

object BluetoothPermissions {
    fun runtimePermissionsForSdk(sdkInt: Int): List<String> =
        if (sdkInt >= Build.VERSION_CODES.S) {
            buildList {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun runtimePermissions(): List<String> = runtimePermissionsForSdk(Build.VERSION.SDK_INT)
}

data class BleDevice(
    val name: String?,
    val address: String,
    val rssi: Int,
    val lastSeenMillis: Long,
    val iBeacon: IBeaconData?,
)

data class IBeaconData(
    val uuid: String,
    val major: Int,
    val minor: Int,
)
