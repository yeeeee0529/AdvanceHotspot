package net.oh4ma.advancehotspot.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficTest {
    @Test
    fun parsesCountersFromCountingRulesOnly() {
        val counters = parseCounters(
            listOf(
                "-N ah_traffic",
                "-A ah_traffic -s 10.77.0.50/32 -i wlan2 -m hashlimit --hashlimit-above 125kb/s " +
                    "--hashlimit-burst 250kb --hashlimit-mode srcip --hashlimit-name ahu0 -c 7 9999 -j DROP",
                "-A ah_traffic -s 10.77.0.50/32 -i wlan2 -c 20 1381",
                "-A ah_traffic -d 10.77.0.50/32 -o wlan2 -c 12 2110",
                "-A ah_traffic -s 10.77.0.51/32 -i wlan2 -c 1 60",
            ),
        )

        assertEquals(Counter(up = 1381, down = 2110), counters["10.77.0.50"])
        assertEquals(Counter(up = 60, down = 0), counters["10.77.0.51"])
    }

    @Test
    fun treatsSmallerReadingAsCounterReset() {
        assertEquals(500, counterDelta(previous = 1000, current = 1500))
        assertEquals(300, counterDelta(previous = 1000, current = 300))
    }

    @Test
    fun addsLimitRulesBeforeCountingRules() {
        val script = buildTrafficScript(
            "wlan2",
            listOf(
                TrafficClient("10.77.0.50", TrafficLimit(upKbit = 1000, downKbit = 8000)),
                TrafficClient("10.77.0.51", TrafficLimit()),
            ),
        )

        // 8000 kbit/s 等於 1000 KB/s
        val limit = script.indexOf("-o wlan2 -d 10.77.0.50 -m hashlimit --hashlimit-name ahd0 --hashlimit-mode dstip " +
            "--hashlimit-above 1000kb/s --hashlimit-burst 2000kb -j DROP")
        val count = script.indexOf("-A ah_traffic -o wlan2 -d 10.77.0.50\n")
        assertTrue(limit >= 0)
        assertTrue(count > limit)
        assertTrue("--hashlimit-above 125kb/s" in script)
        // 沒有設定上限的裝置只計數
        assertFalse("ahu1" in script)
        assertTrue("-A ah_traffic -i wlan2 -s 10.77.0.51\n" in script)
    }

    @Test
    fun rejectsValuesThatCouldInjectShellSyntax() {
        assertThrows(IllegalArgumentException::class.java) {
            buildTrafficScript("wlan2", listOf(TrafficClient("10.0.0.1; reboot", TrafficLimit())))
        }
    }

    @Test
    fun formatsBytesAndRates() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("2 KB", formatBytes(2048))
        assertEquals("1.5 MB", formatBytes(1_572_864))
        assertEquals("2.00 GB", formatBytes(2L shl 30))
        assertEquals("800 bps", formatRate(100))
        assertEquals("80 Kbps", formatRate(10_000))
        assertEquals("8.0 Mbps", formatRate(1_000_000))
    }
}
