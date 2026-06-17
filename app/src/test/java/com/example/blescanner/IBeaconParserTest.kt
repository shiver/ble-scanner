package com.example.blescanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IBeaconParserTest {
    @Test
    fun parsesValidIBeaconFrame() {
        val result = IBeaconParser.parse(validIBeaconScanRecord())

        assertEquals(
            IBeaconData(
                uuid = "00112233-4455-6677-8899-aabbccddeeff",
                major = 258,
                minor = 772,
            ),
            result,
        )
    }

    @Test
    fun returnsNullForNonIBeaconManufacturerData() {
        val bytes = byteArrayOf(
            0x05, 0xff.toByte(),
            0x01, 0x02, // non-Apple company id
            0x03, 0x04,
        )

        assertNull(IBeaconParser.parse(bytes))
    }

    @Test
    fun returnsNullForNonManufacturerSpecificDataType() {
        val bytes = validIBeaconScanRecord().apply {
            this[1] = 0x16 // service data type, not manufacturer-specific data
        }

        assertNull(IBeaconParser.parse(bytes))
    }

    @Test
    fun returnsNullForMalformedShortIBeaconData() {
        val bytes = byteArrayOf(
            0x06, 0xff.toByte(),
            0x4c, 0x00, // Apple company id
            0x02, 0x15, // iBeacon type and advertised length, but missing payload
            0x00,
        )

        assertNull(IBeaconParser.parse(bytes))
    }

    @Test
    fun returnsNullForMalformedAdvertisedLengthPastEndOfRecord() {
        val bytes = byteArrayOf(
            0x10, 0xff.toByte(),
            0x4c, 0x00,
        )

        assertNull(IBeaconParser.parse(bytes))
    }

    @Test
    fun parsesIBeaconAfterOtherAdvertisementStructures() {
        val result = IBeaconParser.parse(
            byteArrayOf(
                0x02, 0x01, 0x06, // flags
            ) + validIBeaconScanRecord(),
        )

        assertEquals("00112233-4455-6677-8899-aabbccddeeff", result?.uuid)
        assertEquals(258, result?.major)
        assertEquals(772, result?.minor)
    }

    private fun validIBeaconScanRecord(): ByteArray = byteArrayOf(
        0x1a, // AD structure length: type + 25 bytes manufacturer data
        0xff.toByte(), // manufacturer-specific data type
        0x4c, 0x00, // Apple company id, little-endian
        0x02, // iBeacon type
        0x15, // remaining iBeacon data length
        0x00, 0x11, 0x22, 0x33,
        0x44, 0x55,
        0x66, 0x77,
        0x88.toByte(), 0x99.toByte(),
        0xaa.toByte(), 0xbb.toByte(), 0xcc.toByte(), 0xdd.toByte(), 0xee.toByte(), 0xff.toByte(),
        0x01, 0x02, // major = 258, big-endian
        0x03, 0x04, // minor = 772, big-endian
        0xc5.toByte(), // measured power, currently ignored
    )
}
