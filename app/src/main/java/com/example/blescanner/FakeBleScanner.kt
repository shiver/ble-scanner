package com.example.blescanner

import android.util.Log
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.milliseconds

private const val FAKE_BLE_SCANNER_TAG = "FakeBleScanner"

class FakeBleScanner(
    private val scenario: FakeScanScenario = FakeScanScenario.MixedDevices,
) : BleScanner {
    override fun scanResults(
        scanMode: BleScanMode,
        filterMode: BleScanFilterMode,
    ): Flow<BleDevice> = flow {
        Log.i(
            FAKE_BLE_SCANNER_TAG,
            "Fake scan started with mode=$scanMode filterMode=$filterMode scenario=${scenario.name}",
        )

        var sequence = 0
        if (scenario.devices.isEmpty()) {
            Log.i(FAKE_BLE_SCANNER_TAG, "Fake scenario is empty; no devices will be emitted")
            awaitCancellation()
        }

        while (true) {
            for (device in scenario.devices) {
                val emittedDevice = device.copy(
                    rssi = device.rssi + scenario.rssiOffsetFor(sequence),
                    lastSeenMillis = System.currentTimeMillis(),
                )
                Log.i(
                    FAKE_BLE_SCANNER_TAG,
                    "Fake scan result: address=${emittedDevice.address}, rssi=${emittedDevice.rssi}, " +
                        "iBeacon=${emittedDevice.iBeacon != null}, mode=$scanMode, filterMode=$filterMode",
                )
                emit(emittedDevice)
                delay(scenario.delayBetweenDevicesMillis.milliseconds)
            }
            sequence++
        }
    }
}

sealed class FakeScanScenario(
    val name: String,
    val devices: List<BleDevice>,
    val delayBetweenDevicesMillis: Long = 250L,
) {
    open fun rssiOffsetFor(sequence: Int): Int = 0

    data object MixedDevices : FakeScanScenario(
        name = "MixedDevices",
        devices = listOf(
            BleDevice(
                name = "Fake iBeacon",
                address = "00:11:22:33:44:55",
                rssi = -48,
                lastSeenMillis = 0L,
                iBeacon = IBeaconData(
                    uuid = "00112233-4455-6677-8899-aabbccddeeff",
                    major = 1,
                    minor = 2,
                ),
            ),
            BleDevice(
                name = "Fake Sensor",
                address = "AA:BB:CC:DD:EE:FF",
                rssi = -72,
                lastSeenMillis = 0L,
                iBeacon = null,
            ),
            BleDevice(
                name = null,
                address = "12:34:56:78:9A:BC",
                rssi = -88,
                lastSeenMillis = 0L,
                iBeacon = null,
            ),
        ),
    ) {
        override fun rssiOffsetFor(sequence: Int): Int = when (sequence % 3) {
            0 -> 0
            1 -> -2
            else -> 2
        }
    }

    data object Empty : FakeScanScenario(
        name = "Empty",
        devices = emptyList(),
    )
}
