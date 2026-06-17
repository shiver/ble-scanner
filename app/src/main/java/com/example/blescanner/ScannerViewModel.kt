package com.example.blescanner

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class ScannerViewModel(
    application: Application,
    private val deviceSource: StateFlow<List<BleDevice>>,
    // `nowMillis()` allows us to inject a fake time during tests so we don't actually have to wait.
    private val nowMillis: () -> Long,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application = application,
        deviceSource = BleScanRepository.devices,
        nowMillis = System::currentTimeMillis,
    )

    // Devices are keyed by the BleDevice hardware address.
    // Importantly this is most likely not fixed for the lifetime of the device, but it is good
    // enough for the task and our purposes, since this is unlikely to change in the short term, and
    // we also expire devices from the list that we haven't seen after `DEVICE_TIMEOUT_MS`.
    private val devicesByAddress = mutableMapOf<String, BleDevice>()

    private val _uiState = kotlinx.coroutines.flow.MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState

    private var publishJob: Job? = null

    init {
        viewModelScope.launch {
            deviceSource.collect { devices ->
                devices.forEach { device -> devicesByAddress[device.address] = device }
            }
        }
    }

    fun startScan() {
        if (_uiState.value.isScanning) return

        _uiState.value = _uiState.value.copy(
            isScanning = true,
            errorMessage = null,
        )
        startPublishing()
    }

    fun setMinimumRssi(minimumRssi: Int) {
        _uiState.value = _uiState.value.copy(minimumRssi = minimumRssi)
        publishDevices()
    }

    fun stopScan() {
        stopPublishing()
        publishDevices()
        _uiState.value = _uiState.value.copy(isScanning = false)
    }

    override fun onCleared() {
        stopScan()
        super.onCleared()
    }

    private fun startPublishing() {
        publishJob?.cancel()
        publishJob = viewModelScope.launch {
            while (true) {
                publishDevices()
                delay(UI_UPDATE_INTERVAL_MS.milliseconds)
            }
        }
    }

    private fun stopPublishing() {
        publishJob?.cancel()
        publishJob = null
    }

    private fun publishDevices() {
        val now = nowMillis()
        val currentState = _uiState.value

        devicesByAddress.entries.removeAll { (_, device) ->
            now - device.lastSeenMillis > DEVICE_TIMEOUT_MS
        }

        val visibleDevices = devicesByAddress.values
            .asSequence()
            .filter { device -> device.rssi >= currentState.minimumRssi }
            .sortedByDescending { device -> device.rssi }
            .toList()

        if (visibleDevices != currentState.devices) {
            _uiState.value = currentState.copy(devices = visibleDevices)
        }
    }

    companion object {
        private const val UI_UPDATE_INTERVAL_MS = 1_000L
        private const val DEVICE_TIMEOUT_MS = 10_000L
    }
}

data class ScannerUiState(
    val isScanning: Boolean = false,
    val devices: List<BleDevice> = emptyList(),
    val errorMessage: String? = null,
    val minimumRssi: Int = -90,
)
