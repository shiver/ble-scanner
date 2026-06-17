package com.example.blescanner

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BluetoothPermissionsTest {
    @Test
    fun requiredPermissionsForAndroid12AndAbove() {
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            BluetoothPermissions.runtimePermissionsForSdk(Build.VERSION_CODES.S),
        )
    }

    @Test
    fun requiredPermissionsForAndroid13AndAboveIncludesNotifications() {
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
            BluetoothPermissions.runtimePermissionsForSdk(Build.VERSION_CODES.TIRAMISU),
        )
    }

    @Test
    fun requiredPermissionsForAndroid11AndBelow() {
        assertEquals(
            listOf(Manifest.permission.ACCESS_FINE_LOCATION),
            BluetoothPermissions.runtimePermissionsForSdk(Build.VERSION_CODES.R),
        )
    }

    @Test
    fun requiredPermissionsForAndroid9AndBelowIncludesFineAndCoarseLocation() {
        assertEquals(
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            BluetoothPermissions.runtimePermissionsForSdk(Build.VERSION_CODES.P),
        )
    }

    @Test
    fun backgroundLocationPermissionForAndroid10AndAbove() {
        assertEquals(
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            BluetoothPermissions.backgroundLocationPermissionForSdk(Build.VERSION_CODES.Q),
        )
        assertEquals(
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            BluetoothPermissions.backgroundLocationPermissionForSdk(Build.VERSION_CODES.R),
        )
    }

    @Test
    fun backgroundLocationPermissionForAndroid9AndBelowIsNotRequired() {
        assertEquals(
            null,
            BluetoothPermissions.backgroundLocationPermissionForSdk(Build.VERSION_CODES.P),
        )
    }

    @Test
    fun requiredPermissionsForAndroid11AndBelowDoesNotRequestNotifications() {
        assertFalse(
            BluetoothPermissions.runtimePermissionsForSdk(Build.VERSION_CODES.R)
                .contains(Manifest.permission.POST_NOTIFICATIONS),
        )
    }
}
