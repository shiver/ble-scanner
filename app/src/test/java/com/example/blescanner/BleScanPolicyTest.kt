package com.example.blescanner

import org.junit.Assert.assertEquals
import org.junit.Test

class BleScanPolicyTest {
    @Test
    fun visibleAppWithInteractiveScreenUsesLowLatencyAllDeviceScan() {
        assertEquals(
            BleScanConfig(
                scanMode = BleScanMode.LowLatency,
                filterMode = BleScanFilterMode.AllDevices,
            ),
            BleScanPolicy.configFor(appVisible = true, screenInteractive = true),
        )
    }

    @Test
    fun backgroundAppWithInteractiveScreenUsesBalancedAllDeviceScan() {
        assertEquals(
            BleScanConfig(
                scanMode = BleScanMode.Balanced,
                filterMode = BleScanFilterMode.AllDevices,
            ),
            BleScanPolicy.configFor(appVisible = false, screenInteractive = true),
        )
    }

    @Test
    fun screenOffUsesLowPowerIBeaconScan() {
        assertEquals(
            BleScanConfig(
                scanMode = BleScanMode.LowPower,
                filterMode = BleScanFilterMode.IBeacon,
            ),
            BleScanPolicy.configFor(appVisible = true, screenInteractive = false),
        )
        assertEquals(
            BleScanConfig(
                scanMode = BleScanMode.LowPower,
                filterMode = BleScanFilterMode.IBeacon,
            ),
            BleScanPolicy.configFor(appVisible = false, screenInteractive = false),
        )
    }
}
