package com.example.blescanner

object IBeaconParser {

    // BLE advertising field:
    //   [length] [type = 0xff] [manufacturer data...]
    //
    //   Manufacturer data:
    //   4c 00        Apple company identifier, little-endian
    //   02           iBeacon type
    //   15           iBeacon data length, 21 bytes
    //   UUID         16 bytes
    //   major        2 bytes, big-endian
    //   minor        2 bytes, big-endian
    //   tx power     1 byte
    fun parse(scanRecordBytes: ByteArray?): IBeaconData? {
        if (scanRecordBytes == null) return null

        var index = 0
        while (index < scanRecordBytes.size) {
            // We convert to UByte because ByteArray stores signed bytes (-128 to 127), and then we
            // convert to Int since it is easier and less errorprone for the conditionals and
            // arithmetic we'll need to do with the `length`.
            val length = scanRecordBytes[index].toUByte().toInt()
            if (length == 0) return null

            val typeIndex = index + 1
            val nextIndex = index + length + 1
            if (typeIndex >= scanRecordBytes.size || nextIndex > scanRecordBytes.size) return null

            val type = scanRecordBytes[typeIndex].toUByte().toInt()
            if (type == MANUFACTURER_SPECIFIC_DATA_TYPE) {
                val manufacturerDataIndex = typeIndex + 1
                val manufacturerDataLength = length - 1
                val iBeacon = parseManufacturerData(
                    scanRecordBytes = scanRecordBytes,
                    offset = manufacturerDataIndex,
                    length = manufacturerDataLength,
                )
                if (iBeacon != null) return iBeacon
            }

            index = nextIndex
        }

        return null
    }

    private fun parseManufacturerData(
        scanRecordBytes: ByteArray,
        offset: Int,
        length: Int,
    ): IBeaconData? {
        if (length < IBEACON_MANUFACTURER_DATA_LENGTH) return null

        val appleCompanyId = unsignedByte(scanRecordBytes[offset]) or
            (unsignedByte(scanRecordBytes[offset + 1]) shl 8)
        if (appleCompanyId != APPLE_COMPANY_ID) return null

        val beaconType = unsignedByte(scanRecordBytes[offset + 2])
        val beaconDataLength = unsignedByte(scanRecordBytes[offset + 3])
        if (beaconType != IBEACON_TYPE || beaconDataLength != IBEACON_DATA_LENGTH) return null

        val uuidStart = offset + 4
        val uuid = scanRecordBytes.copyOfRange(uuidStart, uuidStart + 16).toUuidString()
        val major = unsignedShortBigEndian(scanRecordBytes, uuidStart + 16)
        val minor = unsignedShortBigEndian(scanRecordBytes, uuidStart + 18)

        return IBeaconData(uuid = uuid, major = major, minor = minor)
    }

    private fun ByteArray.toUuidString(): String {
        val hex = joinToString(separator = "") { byte -> "%02x".format(unsignedByte(byte)) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }

    private fun unsignedShortBigEndian(bytes: ByteArray, offset: Int): Int =
        (unsignedByte(bytes[offset]) shl 8) or unsignedByte(bytes[offset + 1])

    private fun unsignedByte(byte: Byte): Int = byte.toInt() and 0xff

    private const val MANUFACTURER_SPECIFIC_DATA_TYPE = 0xff
    private const val APPLE_COMPANY_ID = 0x004c
    private const val IBEACON_TYPE = 0x02
    private const val IBEACON_DATA_LENGTH = 0x15
    private const val IBEACON_MANUFACTURER_DATA_LENGTH = 25
}
