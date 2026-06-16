package com.example.blescanner

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object BleScanRepository {
    private val devicesByAddress = mutableMapOf<String, BleDevice>()

    private val _devices = MutableStateFlow<List<BleDevice>>(emptyList())
    val devices: StateFlow<List<BleDevice>> = _devices.asStateFlow()

    fun update(device: BleDevice) {
        devicesByAddress[device.address] = device
        publish()
    }

    fun clear() {
        devicesByAddress.clear()
        publish()
    }

    private fun publish() {
        _devices.value = devicesByAddress.values.sortedByDescending { device -> device.rssi }
    }
}
