package com.example.blescanner

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.blescanner.ui.theme.BLEScannerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            BLEScannerTheme {
                val viewModel: ScannerViewModel = viewModel()
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                var permissionState by remember { mutableStateOf(currentPermissionState()) }
                var bluetoothState by remember { mutableStateOf(currentBluetoothState()) }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions(),
                ) {
                    permissionState = currentPermissionState()
                    bluetoothState = currentBluetoothState()
                }

                val isScanning by rememberUpdatedState(uiState.isScanning)

                DisposableEffect(Unit) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_RESUME -> {
                                permissionState = currentPermissionState()
                                bluetoothState = currentBluetoothState()
                                if (isScanning) setBleScanServiceMode(BleScanForegroundService.ACTION_SET_FOREGROUND_MODE)
                            }
                            Lifecycle.Event.ON_STOP -> {
                                if (isScanning) setBleScanServiceMode(BleScanForegroundService.ACTION_SET_BACKGROUND_MODE)
                            }
                            else -> Unit
                        }
                    }
                    lifecycle.addObserver(observer)
                    onDispose { lifecycle.removeObserver(observer) }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ScannerScreen(
                        permissionState = permissionState,
                        bluetoothState = bluetoothState,
                        uiState = uiState,
                        onRequestPermissions = {
                            permissionLauncher.launch(BluetoothPermissions.runtimePermissions().toTypedArray())
                        },
                        onStartScan = {
                            viewModel.startScan()
                            startBleScanForegroundService()
                        },
                        onStopScan = {
                            viewModel.stopScan()
                            stopBleScanForegroundService()
                        },
                        onMinimumRssiChanged = viewModel::setMinimumRssi,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }

    private fun startBleScanForegroundService() {
        val intent = Intent(this, BleScanForegroundService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun setBleScanServiceMode(action: String) {
        val intent = Intent(this, BleScanForegroundService::class.java).apply {
            this.action = action
        }
        startService(intent)
    }

    private fun stopBleScanForegroundService() {
        val intent = Intent(this, BleScanForegroundService::class.java).apply {
            action = BleScanForegroundService.ACTION_STOP
        }
        startService(intent)
    }

    private fun currentPermissionState(): PermissionState {
        val missingPermissions = BluetoothPermissions.runtimePermissions().filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        return PermissionState(
            missingPermissions = missingPermissions,
            shouldShowRationale = missingPermissions.any { permission ->
                shouldShowRequestPermissionRationale(permission)
            },
        )
    }

    private fun currentBluetoothState(): BluetoothState {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            return BluetoothState.Unavailable
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter ?: return BluetoothState.Unavailable

        return if (bluetoothAdapter.isEnabled) {
            BluetoothState.Enabled
        } else {
            BluetoothState.Disabled
        }
    }
}

@Composable
private fun ScannerScreen(
    permissionState: PermissionState,
    bluetoothState: BluetoothState,
    uiState: ScannerUiState,
    onRequestPermissions: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onMinimumRssiChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val canScan = permissionState.hasAllPermissions && bluetoothState == BluetoothState.Enabled

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "BLE Scanner",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        StatusMessage(
            permissionState = permissionState,
            bluetoothState = bluetoothState,
            errorMessage = uiState.errorMessage,
            onRequestPermissions = onRequestPermissions,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onStartScan,
                enabled = canScan && !uiState.isScanning,
            ) {
                Text("Start scan")
            }
            Button(
                onClick = onStopScan,
                enabled = uiState.isScanning,
            ) {
                Text("Stop scan")
            }
        }

        Text(
            text = if (uiState.isScanning) "Scanning..." else "Idle",
            style = MaterialTheme.typography.bodyLarge,
        )

        RssiFilter(
            minimumRssi = uiState.minimumRssi,
            onMinimumRssiChanged = onMinimumRssiChanged,
        )

        if (uiState.devices.isEmpty()) {
            Text(
                text = if (uiState.isScanning) {
                    "No devices found yet."
                } else {
                    "Start scanning to discover nearby BLE devices."
                },
            )
        } else {
            DeviceList(devices = uiState.devices)
        }
    }
}

@Composable
private fun StatusMessage(
    permissionState: PermissionState,
    bluetoothState: BluetoothState,
    errorMessage: String?,
    onRequestPermissions: () -> Unit,
) {
    when {
        !permissionState.hasAllPermissions -> {
            Text(
                text = if (permissionState.shouldShowRationale) {
                    "Bluetooth scanning needs Bluetooth and location-related permissions to find nearby BLE devices."
                } else {
                    "Bluetooth scanning permissions are required."
                },
            )
            Button(onClick = onRequestPermissions) {
                Text("Grant permissions")
            }
        }

        bluetoothState == BluetoothState.Unavailable -> {
            Text("Bluetooth LE is not available on this device.")
        }

        bluetoothState == BluetoothState.Disabled -> {
            Text("Bluetooth is disabled. Enable Bluetooth before scanning.")
        }

        errorMessage != null -> {
            Text("Scan error: $errorMessage")
        }

        else -> {
            Text("Ready to scan")
        }
    }
}

@Composable
private fun RssiFilter(
    minimumRssi: Int,
    onMinimumRssiChanged: (Int) -> Unit,
) {
    Column {
        Text("Minimum RSSI: $minimumRssi dBm")
        Slider(
            value = minimumRssi.toFloat(),
            onValueChange = { value -> onMinimumRssiChanged(value.toInt()) },
            valueRange = -90f..-40f,
            steps = 49,
        )
    }
}

@Composable
private fun DeviceList(devices: List<BleDevice>) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(devices, key = { device -> device.address }) { device ->
            DeviceRow(device = device)
        }
    }
}

@Composable
private fun DeviceRow(device: BleDevice) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = device.name ?: "Unknown",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text("MAC: ${device.address}")
            Text("RSSI: ${device.rssi} dBm")

            device.iBeacon?.let { iBeacon ->
                Spacer(modifier = Modifier.height(8.dp))
                Text("iBeacon", fontWeight = FontWeight.Bold)
                Text("UUID: ${iBeacon.uuid}")
                Text("Major: ${iBeacon.major}")
                Text("Minor: ${iBeacon.minor}")
            }
        }
    }
}

data class PermissionState(
    val missingPermissions: List<String> = emptyList(),
    val shouldShowRationale: Boolean = false,
) {
    val hasAllPermissions: Boolean = missingPermissions.isEmpty()
}

enum class BluetoothState {
    Enabled,
    Disabled,
    Unavailable,
}

@Preview(showBackground = true)
@Composable
private fun ScannerScreenPreview() {
    BLEScannerTheme {
        ScannerScreen(
            permissionState = PermissionState(),
            bluetoothState = BluetoothState.Enabled,
            uiState = ScannerUiState(
                devices = FakeScanScenario.MixedDevices.devices,
            ),
            onRequestPermissions = {},
            onStartScan = {},
            onStopScan = {},
            onMinimumRssiChanged = {},
        )
    }
}
