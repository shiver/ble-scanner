## iBeacon

https://community.silabs.com/s/article/Understanding-iBeacon-Packet-Format-and-SiWx917-Implementation?language=en_US

## Background scanning

Bluetooth scanning modes & filter:
- App visible: Balanced / Unfiltered
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
