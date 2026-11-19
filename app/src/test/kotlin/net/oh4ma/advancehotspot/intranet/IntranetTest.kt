package net.oh4ma.advancehotspot.intranet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IntranetTest {
    @Test
    fun acceptsOnlyPrivateGateways() {
        assertTrue(isValidGateway("10.77.0.1"))
        assertTrue(isValidGateway("192.168.50.254"))
        assertTrue(isValidGateway("172.20.1.1"))
        assertFalse(isValidGateway("8.8.8.8"))
        assertFalse(isValidGateway("10.77.0.0"))
        assertFalse(isValidGateway("10.77.0.255"))
        assertFalse(isValidGateway("10.77.0.999"))
        assertFalse(isValidGateway("10.77.0.1; reboot"))
    }

    @Test
    fun checksAddressesAgainstGatewaySubnet() {
        assertTrue(isAssignableAddress("10.77.0.1", "10.77.0.50"))
        assertFalse(isAssignableAddress("10.77.0.1", "10.77.1.50"))
        assertFalse(isAssignableAddress("10.77.0.1", "10.77.0.1"))
        assertFalse(isAssignableAddress("10.77.0.1", "10.77.0.255"))
        assertEquals("10.77.0.0/24", subnetOf("10.77.0.1"))
    }

    @Test
    fun sanitizesHostLabels() {
        assertEquals("jethrow-de-s24-ultra", sanitizeHostLabel("Jethrow-de-S24-Ultra"))
        assertEquals("my-nas", sanitizeHostLabel("  My NAS!  "))
        // 純中文暱稱沒有可用的字元
        assertNull(sanitizeHostLabel("客廳電視"))
        assertNull(sanitizeHostLabel(null))
    }

    @Test
    fun buildsHostEntriesPreferringNicknameAndDeduplicating() {
        val entries = buildHostEntries(
            "10.77.0.1",
            listOf(
                "10.77.0.10" to listOf("NAS", "synology"),
                "10.77.0.11" to listOf(null, "iPhone"),
                "10.77.0.12" to listOf("客廳", "iPhone"),
                "10.77.0.13" to listOf(null, null),
            ),
        )

        assertEquals(
            listOf(
                HostEntry("10.77.0.1", "hotspot"),
                HostEntry("10.77.0.10", "nas"),
                HostEntry("10.77.0.11", "iphone"),
                HostEntry("10.77.0.12", "iphone-2"),
                HostEntry("10.77.0.13", "device-13"),
            ),
            entries,
        )
        assertEquals("nas.lan", entries.fqdnOf("10.77.0.10"))
        assertEquals("device-13.lan", entries.fqdnOf("10.77.0.13"))
        assertNull(entries.fqdnOf("10.77.0.14"))
    }

    @Test
    fun parsesLeaseFile() {
        val leases = parseLeases(
            listOf(
                "1794846858 D4:FF:1A:E9:F1:E3 10.77.0.10 shintsuwagababa 01:d4:ff:1a:e9:f1:e3",
                "1794846900 aa:bb:cc:00:00:01 10.77.0.11 * *",
                "",
            ),
        )

        assertEquals(Lease("10.77.0.10", "shintsuwagababa"), leases["d4:ff:1a:e9:f1:e3"])
        assertEquals(Lease("10.77.0.11", null), leases["aa:bb:cc:00:00:01"])
    }

    @Test
    fun scriptRedirectsDhcpWhileKeepingBroadcastDestination() {
        val script = IntranetConfig(
            gateway = "10.77.0.1",
            staticLeases = mapOf("aa:bb:cc:00:00:01" to "10.77.0.50"),
            hosts = listOf(HostEntry("10.77.0.50", "nas")),
            upstreamDns = listOf("1.1.1.1"),
        ).toScript("wlan2", "/data/user/0/app/files/intranet")

        assertTrue("-d 255.255.255.255 -p udp --dport 67 -j DNAT --to-destination 255.255.255.255:1067" in script)
        assertTrue("--dhcp-range=10.77.0.2,10.77.0.254,255.255.255.0,1h" in script)
        assertTrue("aa:bb:cc:00:00:01,10.77.0.50" in script)
        assertTrue("10.77.0.50 nas" in script)
        assertTrue("--server=1.1.1.1" in script)
    }

    @Test
    fun scriptRejectsStaticAddressOutsideSubnet() {
        val config = IntranetConfig("10.77.0.1", mapOf("aa:bb:cc:00:00:01" to "192.168.1.5"), emptyList(), emptyList())

        assertThrows(IllegalArgumentException::class.java) { config.toScript("wlan2", "/data/intranet") }
    }

    @Test
    fun scriptsExpireStaleDhcpConntrackEntryInBothDirections() {
        val drop = "-t raw -I PREROUTING -i wlan2 -p udp --dport 67 -j DROP"
        val setup = IntranetConfig("10.77.0.1", emptyMap(), emptyList(), emptyList())
            .toScript("wlan2", "/data/intranet")
        val teardown = intranetTeardownScript("wlan2", "10.77.0.1", "/data/intranet")

        // 啟用時要淘汰尚未轉向的項目，停用時要淘汰仍在轉向的項目
        assertTrue(drop in setup)
        assertTrue(".*sport=67 dport=68' /proc/net/nf_conntrack" in setup)
        assertTrue(drop in teardown)
        assertTrue(".*sport=1067 dport=68' /proc/net/nf_conntrack" in teardown)
        assertFalse("raw" in intranetTeardownScript(null, "10.77.0.1", "/data/intranet"))
    }

    @Test
    fun scriptRoutesLocalRepliesAheadOfVpnRules() {
        val setup = IntranetConfig("10.77.0.1", emptyMap(), emptyList(), emptyList())
            .toScript("wlan2", "/data/intranet")

        assertTrue("ip rule add to 10.77.0.0/24 iif lo lookup 97 prio 10500" in setup)
        assertTrue("ip rule del prio 10500" in intranetTeardownScript(null, "10.77.0.1", "/data/intranet"))
    }
}
