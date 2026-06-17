# BLE Scanner

A short project to explore Bluetooth scanning capabilities for Android devices, with support for
manually parsing iBeacon manufacturer data.

## Build instructions

Open the project in Android Studio, let Gradle sync, then run the `app` configuration on a physical Android device.

From the command line:

```bash
./gradlew assembleDebug
```

The debug APK will be created at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Alternatively you can install to all devices:
```text
./gradlew installDebug
```

## Running the app

You can either build and deploy the application to a device using the build instructions above,
or you can check the [releases](https://github.com/shiver/ble-scanner/releases) for the project on GitHub
to download an APK directly.

## iBeacon parsing

I opted to forego a library for iBeacon parsing in this instance, primarily because the format itself 
is quite simple. I tend to avoid taking on new dependencies by default, both for security reasons,
but also because I prefer to know exactly why I need a dependency before I take it on.

I found the following resource somewhat helpful when creating the parser: https://community.silabs.com/s/article/Understanding-iBeacon-Packet-Format-and-SiWx917-Implementation?language=en_US

The parser walks the raw BLE advertising data as AD structures of the form `[length][type][data...]` and only attempts iBeacon parsing for AD structures with type `0xff` / manufacturer-specific data.

For iBeacon frames, the manufacturer data layout is:

| Offset | Size | Value / field | Endianness | Notes |
| ---: | ---: | --- | --- | --- |
| 0 | 2 bytes | `0x004c` / Apple company identifier | Little-endian, encoded as `4c 00` | Required by the iBeacon format. |
| 2 | 1 byte | `0x02` / iBeacon type | N/A | Identifies this Apple manufacturer payload as iBeacon. |
| 3 | 1 byte | `0x15` / iBeacon data length | N/A | Decimal 21: UUID + major + minor + measured power. |
| 4 | 16 bytes | Proximity UUID | Byte sequence | Rendered as a standard UUID string. |
| 20 | 2 bytes | Major | Big-endian | Parsed as an unsigned 16-bit integer. |
| 22 | 2 bytes | Minor | Big-endian | Parsed as an unsigned 16-bit integer. |
| 24 | 1 byte | Measured power / Tx power | Signed byte | Present in the frame, but currently ignored by the app. |

IMPORTANT: It is likely that some aspect of the parsing may be incorrect or incomplete, as I 
only had a single compatible device available to verify this with.

## Background scanning

### Foreground services vs WorkManager

For this task I opted to use a foreground service rather than a `WorkManager`. My understanding of 
`WorkManager` is that it is more intended to be used for periodic background tasks, whereas 
foreground services are better suited to long-running user visible tasks like our own.

### Frequent scanning restrictions (rescan delay)

It is possible that we start/restart scanning several times in a short duration to try to pick the 
right scanning combination depending on the state of the application:
- When the app changes from visible to not-visible and vice versa
- or, when the device screen is disabled

When we restart scanning too frequently, Android may opt to disable scanning for a few seconds. To 
avoid this we intentionally introduce a delay before starting a new scan, as determined by `MIN_SCAN_RESTART_INTERVAL_MS`.

More specifically we alter the `scanMode` under the following conditions:
- `LowLatency` when the app is visible,
- `Balanced` when the app is not visible,
- and `LowPower` when the screen is off

`LowLatency` may not be not necessary, and we might be able to get away with only `Balanced` and 
`LowPower` modes, but I opted to keep `LowLatency` purely for exploration/demonstration purposes.

### Background scanning with ScanFilter 

It is common for Android OEMs to introduce additional limitations when scanning for Bluetooth devices
when the filter provided to `startScan()` is unrestricted. When tested on both Oppo and Samsung
devices, both were able to scan for devices when the application is not visible, even in `LowPower` 
mode. However, when the screen is turned off, Bluetooth scanning appeared to be heavily restricted, 
unless done so with a specific `ScanFilter`.

```kotlin
ScanFilter.Builder()
    .setManufacturerData(
        APPLE_COMPANY_ID,
        byteArrayOf(),
        byteArrayOf()
    )
    .build()
```

In summary, the application will adopt the following scan mode and filters:
- App visible: LowLatency / Unfiltered
- App not visible: Balanced / Unfiltered
- Screen off: LowPower / Apple Manufacturer ID

Given that the task specifically called attention to iBeacon devices, I opted to target iBeacon 
devices (Apple Manufacturer IDs) when the screen is off. Apple/iBeacon devices aren't required for 
this to work, but they make a good enough default for the purposes of this task.

This obviously means that scanning in this state will miss a lot of non-Apple devices.

### Background scanning permissions

Android's Bluetooth scanning permissions differ significantly by OS version:

| Android version | Runtime permissions used by the app | Notes |
| --- | --- | --- |
| Android 6-9 / API 23-28 | `ACCESS_FINE_LOCATION` and/or `ACCESS_COARSE_LOCATION` | BLE scan results can reveal physical location, so location permission is required for scan results. There is no separate background location permission on these versions. |
| Android 10 / API 29 | `ACCESS_FINE_LOCATION`, then `ACCESS_BACKGROUND_LOCATION` as a separate request | Background location must be requested separately after foreground location is granted. This app can request it directly on Android 10. |
| Android 11 / API 30 | `ACCESS_FINE_LOCATION`; background location via system settings | Android 11 removed the normal runtime dialog path for granting "Allow all the time". The app explains the requirement and directs the user to app settings. |
| Android 12+ / API 31+ | `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION` | Android introduced dedicated nearby-device Bluetooth permissions. This app still requests location because BLE scanning can expose proximity/location information and because location improves compatibility across devices/OEMs. |
| Android 13+ / API 33+ | Same as Android 12+, plus `POST_NOTIFICATIONS` | A foreground service requires a visible notification, and Android 13 requires notification runtime permission. |

A few gotchas:

- Foreground and background location are not the same permission on Android 10+.
- On Android 11+, apps generally cannot show a normal permission dialog for "Allow all the time" location; users must enable it in system settings.
- A foreground service cannot run silently. If the service is active, Android requires a persistent notification.
- Even with the correct permissions and a foreground service, Android/OEM battery policies may throttle or suppress BLE scans, especially when the screen is off.
- Some devices require Location Services to be enabled globally before BLE scan results are delivered, even when app permissions are granted.
- `BLUETOOTH_ADMIN` is not central to this app's modern `BluetoothLeScanner.startScan(...)` flow, but older Bluetooth examples often include it for legacy adapter/discovery operations.

## RSSI filter

I opted to implement the RSSI filter in the UI layer only. So technically our scans will find devices
regardless of their signal strength, and simply opt to exclude it from the list when rendering the UI.

I see that [SDK 36.1](https://developer.android.com/reference/kotlin/android/bluetooth/le/ScanSettings.Builder#setrssithreshold) has introduced the `ScanSettings.setRssiThreshold()` function which should provide the functionality we need, 
but I opted to forego this option for a few reasons:
- We are targeting SDK `24+`, which means the bulk of the devices we run on likely won't support it
- Relying on this would require that we restart scanning even more frequently than we already are, 
which I mentioned earlier was already problematic for us
- Doing this in the UI layer is almost certain to feel better from a UI responsiveness point of view
(dragging the slider gives immediate results)

## Areas for improvement

- There might be a better way to allow for scanning when the screen is off, but it would require more
time to investigate.

- RSSI updates frequently for the same device. It might give a better user experience to track a few
values over time and provide an average/median instead of a new value each time one arrives.

- `BleScanForegroundService` is independently tracking screen state (on/off), and I suspect there might
be a more idiomatic way to do this, which perhaps should be done outside the foreground service. Perhaps
based on the MainActivity's lifecycle events.

- I opted to create `Int.bluetoothCompanyName()` which hardcodes a few common company identifiers, 
primarily because company information wasn't called out as a required field. In a full project I might
opt to vendor in the full list and rely on that as a source of truth instead. https://bitbucket.org/bluetooth-SIG/public/src/main/assigned_numbers/company_identifiers/company_identifiers.yaml

- The `FakeBleScanner` should be a DEBUG only build option and compiled out for release builds.

- I am a little unsure of the permissions when it comes to older versions of Android (eg: `BLUETOOTH_ADMIN`).
Due to a lack of older Android physical devices to test with, and given emulators aren't great when 
it comes to simulating bluetooth, there are likely changes necessary here.

- I include a lot of debug information in the UI (mostly around permissions) for illustration purposes. 
This wouldn't be something I would normally show in a customer facing application.

- The UI/UX could be greatly improved, but I opted to focus on functionality given the limited time.

- Instrumented tests that run on device would be a good addition to be able to verify Bluetooth behaviour.

- I am sure there are many bugs :-)