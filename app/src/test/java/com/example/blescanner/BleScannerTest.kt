package com.example.blescanner

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

class BleScannerTest {
    @Test
    fun requiredPermissionsForAndroid12AndAbove() {
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            ),
            AndroidBleScanner.requiredPermissionsForSdk(Build.VERSION_CODES.S),
        )
    }

    @Test
    fun requiredPermissionsForAndroid11AndBelow() {
        assertEquals(
            listOf(Manifest.permission.ACCESS_FINE_LOCATION),
            AndroidBleScanner.requiredPermissionsForSdk(Build.VERSION_CODES.R),
        )
    }
}
