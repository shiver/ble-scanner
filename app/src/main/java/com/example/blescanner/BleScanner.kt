package com.example.blescanner

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

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

    override fun scanResults(): Flow<BleDevice> = callbackFlow {
        // Importantly we don't cache things like whether or not we have the necessary permissions, or
        // the bluetooth devices, since this can change between scanning sessions.
        if (!hasRequiredPermissions()) {
            close(SecurityException("Missing required Bluetooth scan permissions"))
            return@callbackFlow
        }

        if (!applicationContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            close(IllegalStateException("Bluetooth LE is not available on this device"))
            return@callbackFlow
        }

        if (bluetoothAdapter == null) {
            close(IllegalStateException("Bluetooth is not available on this device"))
            return@callbackFlow
        }

        if (!bluetoothAdapter.isEnabled) {
            close(IllegalStateException("Bluetooth is disabled"))
            return@callbackFlow
        }

        val bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
        if (bluetoothLeScanner == null) {
            close(IllegalStateException("Bluetooth LE scanner is not available"))
            return@callbackFlow
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(result.toBleDevice())
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { result -> trySend(result.toBleDevice()) }
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("BLE scan failed with error code $errorCode"))
            }
        }

        bluetoothLeScanner.startScan(callback)

        awaitClose {
            bluetoothLeScanner.stopScan(callback)
        }
    }

    fun hasRequiredPermissions(): Boolean = requiredPermissions().all { permission ->
        ContextCompat.checkSelfPermission(applicationContext, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun requiredPermissions(): List<String> = requiredPermissionsForSdk(Build.VERSION.SDK_INT)

    companion object {
        fun requiredPermissionsForSdk(sdkInt: Int): List<String> =
            if (sdkInt >= Build.VERSION_CODES.S) {
                listOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                )
            } else {
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
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
