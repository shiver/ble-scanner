package com.example.blescanner

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.Locale
import androidx.core.util.isNotEmpty

private const val BLE_SCANNER_TAG = "BleScanner"

interface BleScanner {
    fun scanResults(
        scanMode: BleScanMode = BleScanMode.Balanced,
        filterMode: BleScanFilterMode = BleScanFilterMode.AllDevices,
    ): Flow<BleDevice>
}

enum class BleScanFilterMode {
    AllDevices,
    IBeacon,
}

enum class BleScanMode(val scanSettingsValue: Int) {
    LowLatency(ScanSettings.SCAN_MODE_LOW_LATENCY),
    Balanced(ScanSettings.SCAN_MODE_BALANCED),
    LowPower(ScanSettings.SCAN_MODE_LOW_POWER),
}

class AndroidBleScanner(
    context: Context,
) : BleScanner {
    private val applicationContext = context.applicationContext
    private val bluetoothManager =
        applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter

    override fun scanResults(
        scanMode: BleScanMode,
        filterMode: BleScanFilterMode,
    ): Flow<BleDevice> = callbackFlow {
        Log.i(BLE_SCANNER_TAG, "scanResults collection started")
        // Importantly we don't cache things like whether we have the necessary permissions, or
        // the bluetooth device is enabled, since this can change between scanning sessions.
        if (!hasRequiredPermissions()) {
            Log.w(BLE_SCANNER_TAG, "Cannot start scan: missing permissions ${requiredPermissions()}")
            close(SecurityException("Missing required Bluetooth scan permissions"))
            return@callbackFlow
        }

        val hasBleFeature = applicationContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        Log.i(BLE_SCANNER_TAG, "Bluetooth LE feature declared by device: $hasBleFeature")

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
            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val manufacturerId = result.scanRecord?.manufacturerSpecificData?.firstKey()
                val manufacturerDataPrefix = manufacturerId
                    ?.let { id -> result.scanRecord?.manufacturerSpecificData?.get(id) }
                    ?.take(4)
                    ?.toByteArray()
                    ?.toHexString()
                    ?: "none"
                val manufacturerIdText = manufacturerId?.let { id -> "0x${id.toString(16).padStart(4, '0')}" } ?: "none"

                Log.i(
                    BLE_SCANNER_TAG,
                    "Scan result: address=${result.device.address}, rssi=${result.rssi}, " +
                        "manufacturerId=$manufacturerIdText, manufacturerDataFirst4=$manufacturerDataPrefix",
                )
                trySend(result.toBleDevice())
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
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
            .setScanMode(scanMode.scanSettingsValue)
            .build()

        val filters = filtersFor(filterMode)

        try {
            Log.i(BLE_SCANNER_TAG, "Starting BLE scan with mode=$scanMode filterMode=$filterMode")
            bluetoothLeScanner.startScan(filters, settings, callback)
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

    private fun filtersFor(filterMode: BleScanFilterMode): List<ScanFilter> =
        when (filterMode) {
            BleScanFilterMode.AllDevices -> listOf(ScanFilter.Builder().build())
            BleScanFilterMode.IBeacon -> listOf(
                ScanFilter.Builder()
                    .setManufacturerData(
                        APPLE_COMPANY_ID,
                        byteArrayOf(),
                        byteArrayOf()
                    )
                    .build(),
            )
        }

    fun hasRequiredPermissions(): Boolean =
        BluetoothPermissions.missingRuntimePermissions(applicationContext).isEmpty()

    fun requiredPermissions(): List<String> = requiredPermissionsForSdk(Build.VERSION.SDK_INT)

    companion object {
        fun requiredPermissionsForSdk(sdkInt: Int): List<String> =
            BluetoothPermissions.runtimePermissionsForSdk(sdkInt)
    }
}

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
private fun ScanResult.toBleDevice(): BleDevice {
    val manufacturerId = scanRecord?.manufacturerSpecificData?.firstKey()
    return BleDevice(
        name = scanRecord?.deviceName ?: device.name,
        address = device.address,
        rssi = rssi,
        lastSeenMillis = System.currentTimeMillis(),
        iBeacon = IBeaconParser.parse(scanRecord?.bytes),
        manufacturerId = manufacturerId,
        manufacturerName = manufacturerId?.bluetoothCompanyName(),
    )
}

private fun <T> android.util.SparseArray<T>.firstKey(): Int? =
    if (isNotEmpty()) keyAt(0) else null

private fun ByteArray.toHexString(): String =
    joinToString(separator = " ") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun Int.bluetoothCompanyName(): String = when (this) {
    0x004c -> "Apple, Inc."
    0x0006 -> "Microsoft"
    0x000f -> "Broadcom"
    0x0075 -> "Samsung Electronics"
    0x00e0 -> "Google"
    0x0131 -> "Sony Corporation"
    0x0182 -> "Qualcomm Technologies International"
    0x02ff -> "Tile, Inc."
    0x0499 -> "Ruuvi Innovations Ltd."
    else -> "Unknown company"
}

object BluetoothPermissions {
    fun runtimePermissionsForSdk(sdkInt: Int): List<String> =
        when {
            sdkInt >= Build.VERSION_CODES.S -> buildList {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            sdkInt >= Build.VERSION_CODES.Q -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            else -> listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        }

    fun missingRuntimePermissions(context: Context): List<String> {
        val sdkInt = Build.VERSION.SDK_INT
        val permissions = runtimePermissionsForSdk(sdkInt)

        if (sdkInt in Build.VERSION_CODES.M until Build.VERSION_CODES.Q) {
            val hasFineLocation = context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            val hasCoarseLocation = context.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            return permissions
                .filterNot { permission ->
                    permission == Manifest.permission.ACCESS_FINE_LOCATION ||
                        permission == Manifest.permission.ACCESS_COARSE_LOCATION
                }
                .filterNot(context::hasPermission) +
                if (hasFineLocation || hasCoarseLocation) emptyList() else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        return permissions.filterNot(context::hasPermission)
    }

    fun backgroundLocationPermissionForSdk(sdkInt: Int): String? =
        if (sdkInt >= Build.VERSION_CODES.Q) Manifest.permission.ACCESS_BACKGROUND_LOCATION else null

    fun runtimePermissions(): List<String> = runtimePermissionsForSdk(Build.VERSION.SDK_INT)

    fun backgroundLocationPermission(): String? = backgroundLocationPermissionForSdk(Build.VERSION.SDK_INT)
}

private fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

object DeviceEnvironment {
    fun isEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.lowercase(Locale.US)
        val model = Build.MODEL.lowercase(Locale.US)
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.US)
        val brand = Build.BRAND.lowercase(Locale.US)
        val device = Build.DEVICE.lowercase(Locale.US)
        val product = Build.PRODUCT.lowercase(Locale.US)

        return fingerprint.startsWith("generic") ||
            fingerprint.startsWith("unknown") ||
            model.contains("google_sdk") ||
            model.contains("emulator") ||
            model.contains("android sdk built for") ||
            manufacturer.contains("genymotion") ||
            brand.startsWith("generic") && device.startsWith("generic") ||
            product.contains("sdk") ||
            product.contains("emulator") ||
            product.contains("simulator")
    }
}

private const val APPLE_COMPANY_ID = 0x004c
private const val SAMSUNG_COMPANY_ID = 0x0075
private const val IBEACON_TYPE = 0x02
private const val IBEACON_DATA_LENGTH = 0x15

data class BleDevice(
    val name: String?,
    val address: String,
    val rssi: Int,
    val lastSeenMillis: Long,
    val iBeacon: IBeaconData?,
    val manufacturerId: Int? = null,
    val manufacturerName: String? = null,
)

data class IBeaconData(
    val uuid: String,
    val major: Int,
    val minor: Int,
)
