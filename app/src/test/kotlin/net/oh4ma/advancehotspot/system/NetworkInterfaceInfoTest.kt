package net.oh4ma.advancehotspot.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkInterfaceInfoTest {
    @Test
    fun parsesAddressesAndStripsParentSuffix() {
        val parsed = parseBriefAddressOutput(
            listOf(
                "lo               UNKNOWN        127.0.0.1/8 ::1/128 ",
                "tunl0@NONE       DOWN           ",
                "wlan0            UP             10.12.33.0/16 fe80::b6a9:fcff:fe52:5b6c/64 ",
                "",
            ),
        )

        assertEquals(listOf("lo", "tunl0", "wlan0"), parsed.map { it.name })
        assertEquals(listOf("10.12.33.0/16", "fe80::b6a9:fcff:fe52:5b6c/64"), parsed[2].addresses)
        assertTrue(parsed[1].addresses.isEmpty())
    }

    @Test
    fun treatsUnknownStateWithAddressAsUp() {
        val parsed = parseBriefAddressOutput(
            listOf(
                "tun0             UNKNOWN        10.8.0.2/24 ",
                "dummy0           UNKNOWN        ",
                "wlan1            DOWN           ",
            ),
        )

        assertTrue(parsed[0].isUp)
        assertFalse(parsed[1].isUp)
        assertFalse(parsed[2].isUp)
    }
}
