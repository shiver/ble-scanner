package com.example.blescanner

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ScannerViewModel(
    application: Application,
    private val bleScanner: BleScanner,
    // `nowMillis()` allows us to inject a fake time during tests so we don't actually have to wait.
    private val nowMillis: () -> Long,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application = application,
        bleScanner = AndroidBleScanner(application),
        nowMillis = System::currentTimeMillis,
    )
    private val devicesByAddress = mutableMapOf<String, BleDevice>()

    private val _uiState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null
    private var publishJob: Job? = null

    fun startScan() {
        if (_uiState.value.isScanning) return

        _uiState.value = _uiState.value.copy(
            isScanning = true,
            errorMessage = null,
        )

        scanJob = viewModelScope.launch {
            bleScanner.scanResults()
                .collect { device ->
                    devicesByAddress[device.address] = device
                }
        }

        scanJob?.invokeOnCompletion { throwable ->
            if (throwable != null && throwable !is CancellationException) {
                _uiState.value = _uiState.value.copy(
                    isScanning = false,
                    errorMessage = throwable.message ?: "BLE scan stopped unexpectedly",
                )
                stopPublishing()
            }
        }

        startPublishing()
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
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
            while (isActive) {
                publishDevices()
                delay(UI_UPDATE_INTERVAL_MS)
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
        val visibleDevices = devicesByAddress.values
            .asSequence()
            .filter { device -> now - device.lastSeenMillis <= DEVICE_TIMEOUT_MS }
            .sortedByDescending { device -> device.rssi }
            .toList()

        devicesByAddress.keys
            .filter { address -> devicesByAddress[address]?.let { now - it.lastSeenMillis > DEVICE_TIMEOUT_MS } == true }
            .forEach { address -> devicesByAddress.remove(address) }

        _uiState.value = currentState.copy(devices = visibleDevices)
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
)
