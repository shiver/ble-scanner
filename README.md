# BLE Scanner

A short project to explore Bluetooth scanning capabilities for Android devices, with support for
manually parsing iBeacon manufacturer data.

## iBeacon parsing

I opted to forego a library for iBeacon parsing in this instance, primarily because the format itself 
is quite simple. I tend to avoid taking on new dependencies by default, both for security reasons,
but also because I prefer to know exactly why I need a dependency before I take it on.

I found the following resource somewhat helpful when creating the parser: https://community.silabs.com/s/article/Understanding-iBeacon-Packet-Format-and-SiWx917-Implementation?language=en_US

As for how this works

## Background scanning

### Foreground services vs WorkManager
For this task I opted to use a foreground service rather than a `WorkManager`. My understanding of 
`WorkManager` is that it is more intended to be used for periodic tasks background tasks, whereas 
foreground services are better suited to long-running user visible tasks like ours.

With each new Android release, it appears as if Bluetooth scanning has been further and further 
restricted, justified either by reduced power consumption, or security concerns. As such there are 
a few provisions that I needed to make in order to avoid these imposed limitations.

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
mode. However, when the screen is turned off, Bluetooth scanning is appeared to be heavily restricted, 
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
- App not visible: LowPower / Unfiltered
- Screen off: LowPower / Apple Manufacturer ID

Given that the task specifically called attention to iBeacon devices, I opted to target iBeacon 
devices (Apple Manufacturer IDs) when the screen is off. Apple/iBeacon devices aren't required for 
this to work, but they make a good enough default for the purposes of this task.

This obviously means that scanning in this state will miss a lot of non-Apple devices.

### Background scanning permissions

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