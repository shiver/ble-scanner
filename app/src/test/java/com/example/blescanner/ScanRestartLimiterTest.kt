package com.example.blescanner

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanRestartLimiterTest {
    private val limiter = ScanRestartLimiter(minRestartIntervalMillis = 6_000L)

    @Test
    fun firstRestartIsAllowedImmediately() {
        assertEquals(
            0L,
            limiter.delayBeforeRestartMillis(
                lastScanStartElapsedMillis = 0L,
                nowElapsedMillis = 1_000L,
            ),
        )
    }

    @Test
    fun restartBeforeMinimumIntervalReturnsRemainingDelay() {
        assertEquals(
            4_000L,
            limiter.delayBeforeRestartMillis(
                lastScanStartElapsedMillis = 1_000L,
                nowElapsedMillis = 3_000L,
            ),
        )
    }

    @Test
    fun restartAfterMinimumIntervalIsAllowedImmediately() {
        assertEquals(
            0L,
            limiter.delayBeforeRestartMillis(
                lastScanStartElapsedMillis = 1_000L,
                nowElapsedMillis = 7_000L,
            ),
        )
    }
}
