## iBeacon

https://community.silabs.com/s/article/Understanding-iBeacon-Packet-Format-and-SiWx917-Implementation?language=en_US
https://bitbucket.org/bluetooth-SIG/public/src/main/assigned_numbers/company_identifiers/company_identifiers.yaml

## Background scanning
When a scan is active it is possible that we start/restart scanning several times as
application state changes:
- When the app changes from visible to not-visible and vice versa
- or, when the device screen is disabled

However, if we do this too frequently Android may decide to disable scanning for a few
seconds. To avoid this we intentionally introduce a delay before we start a new scan as
determined by `MIN_SCAN_RESTART_INTERVAL_MS`.

We do this so that we can use the right scanning mode for the occasion:
- LowLatency when the app is visible,
- Balanced when the app is not visible,
- and LowPower when the screen is off

Bluetooth scanning modes & filter:
- App visible: LowLatency / Unfiltered
- App not visible: LowPower / Apple Manufacturer ID
- Screen off: LowPower / Apple Manufacturer ID

### Screen On

While this works it does require a number of additional permissions, which also changes depending on 
the Android release version.

### Screen Off

It is common for Android OEMs to introduce additional limitations when scanning for Bluetooth devices
when the filter provided to `startScan()` is unrestricted. When tested on both Oppo and Samsung 
devices, both were able to scan for devices when the application is not visible, whilst in 
LowPower mode. However, when the screen is turned off, Bluetooth scanning is prevented, unless 
done so with a specific `ScanFilter`.

```kotlin
ScanFilter.Builder()
    .setManufacturerData(
        APPLE_COMPANY_ID,
        byteArrayOf(),
        byteArrayOf()
    )
    .build()
```

Given that the task specifically called attention to iBeacon devices, I have opted to have an 
unrestricted filter when the application is visible, and then to target iBeacon devices 
(Apple Manufacturer IDs) when not visible. Apple devices aren't required for this to work, but they
make a good enough default for the purposes of this task.
