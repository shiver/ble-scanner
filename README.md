## iBeacon

https://community.silabs.com/s/article/Understanding-iBeacon-Packet-Format-and-SiWx917-Implementation?language=en_US

## Background scanning

### Screen On

While this works it does require a number of additional permissions, which also changes depending on the Android release version.


### Screen Off
It is common for Android OEMs to introduce additional limitations when scanning for Bluetooth devices
when the filter provided to `startScan()` is unrestricted. When tested on both Oppo and Samsung 
devices, both were able to continue to scan for devices when the application is not visible, and in 
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
unrestricted filter when the application is visible, and the to target iBeacon devices 
(the Apple Manufacturer ID) when not visible. 

Technically we could target other specific data about the device, but given the nature of the task
I opted to go with this for simplicity's sake.