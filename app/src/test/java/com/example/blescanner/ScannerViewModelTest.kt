package com.example.blescanner

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class ScannerViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val devices = MutableStateFlow<List<BleDevice>>(emptyList())
    private var now = 1_000L

    private lateinit var viewModel: ScannerViewModel

    @Before
    fun setUp() {
        viewModel = ScannerViewModel(
            application = Application(),
            deviceSource = devices,
            nowMillis = { now },
        )
    }

    @After
    fun tearDown() {
        viewModel.stopScan()
    }

    @Test
    fun startScanSetsScanningState() = runTest(mainDispatcherRule.testDispatcher) {
        viewModel.startScan()
        runCurrent()

        assertTrue(viewModel.uiState.value.isScanning)
        assertEquals(null, viewModel.uiState.value.errorMessage)
        viewModel.stopScan()
        runCurrent()
    }

    @Test
    fun stopScanClearsScanningStateWithoutError() = runTest(mainDispatcherRule.testDispatcher) {
        viewModel.startScan()
        runCurrent()

        viewModel.stopScan()
        runCurrent()

        assertFalse(viewModel.uiState.value.isScanning)
        assertEquals(null, viewModel.uiState.value.errorMessage)
    }

    @Test
    fun duplicateAddressUpdatesExistingDevice() = runTest(mainDispatcherRule.testDispatcher) {
        viewModel.startScan()
        runCurrent()
        devices.value = listOf(device(address = "AA:BB", rssi = -80))
        runCurrent()
        devices.value = listOf(device(address = "AA:BB", rssi = -40, name = "Updated"))
        advanceTimeBy(1_000L.milliseconds)
        runCurrent()

        val visibleDevices = viewModel.uiState.value.devices
        assertEquals(1, visibleDevices.size)
        assertEquals("Updated", visibleDevices.single().name)
        assertEquals(-40, visibleDevices.single().rssi)
        viewModel.stopScan()
        runCurrent()
    }

    @Test
    fun devicesAreSortedByDescendingRssi() = runTest(mainDispatcherRule.testDispatcher) {
        viewModel.startScan()
        runCurrent()
        devices.value = listOf(
            device(address = "weak", rssi = -90),
            device(address = "strong", rssi = -35),
            device(address = "middle", rssi = -60),
        )
        advanceTimeBy(1_000L.milliseconds)
        runCurrent()

        assertEquals(
            listOf("strong", "middle", "weak"),
            viewModel.uiState.value.devices.map { it.address },
        )
        viewModel.stopScan()
        runCurrent()
    }

    @Test
    fun rssiFilterHidesDevicesBelowThreshold() = runTest(mainDispatcherRule.testDispatcher) {
        viewModel.startScan()
        runCurrent()
        devices.value = listOf(
            device(address = "weak", rssi = -80),
            device(address = "strong", rssi = -50),
        )
        advanceTimeBy(1_000L.milliseconds)
        runCurrent()

        viewModel.setMinimumRssi(-60)
        runCurrent()

        assertEquals(
            listOf("strong"),
            viewModel.uiState.value.devices.map { it.address },
        )
        viewModel.stopScan()
        runCurrent()
    }

    @Test
    fun staleDevicesExpireAfterTimeout() = runTest(mainDispatcherRule.testDispatcher) {
        viewModel.startScan()
        runCurrent()
        devices.value = listOf(device(address = "stale", rssi = -50, lastSeenMillis = 1_000L))
        advanceTimeBy(1_000L.milliseconds)
        runCurrent()
        assertEquals(listOf("stale"), viewModel.uiState.value.devices.map { it.address })

        now = 12_000L
        advanceTimeBy(1_000L.milliseconds)
        runCurrent()

        assertEquals(emptyList<String>(), viewModel.uiState.value.devices.map { it.address })
        viewModel.stopScan()
        runCurrent()
    }

    private fun device(
        address: String,
        rssi: Int,
        name: String? = address,
        lastSeenMillis: Long = now,
    ): BleDevice = BleDevice(
        name = name,
        address = address,
        rssi = rssi,
        lastSeenMillis = lastSeenMillis,
        iBeacon = null,
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
