package com.example.blescanner

import org.junit.Assert.assertEquals
import org.junit.Test

class BleScanNotificationTextTest {
    @Test
    fun contentTextWhenBackgroundLocationIsGranted() {
        assertEquals(
            "Scanning for nearby BLE devices",
            BleScanNotificationText.contentText(hasBackgroundLocationPermission = true),
        )
    }

    @Test
    fun contentTextWhenBackgroundLocationIsMissing() {
        assertEquals(
            "Background scanning may be limited until all-the-time location is enabled.",
            BleScanNotificationText.contentText(hasBackgroundLocationPermission = false),
        )
    }
}
