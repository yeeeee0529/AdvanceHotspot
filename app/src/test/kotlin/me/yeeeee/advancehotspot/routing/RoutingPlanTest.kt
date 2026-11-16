package me.yeeeee.advancehotspot.routing

import me.yeeeee.advancehotspot.hotspot.ConnectedClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingPlanTest {
    private val downstream = Downstream("wlan2", "10.98.43.91", 24)
    private val phone = ConnectedClient("aa:bb:cc:00:00:01", "10.98.43.10", "phone")
    private val laptop = ConnectedClient("aa:bb:cc:00:00:02", "10.98.43.20", "laptop")
    private val vpn = mapOf(Egress.VPN to ResolvedEgress("tun0", "10.8.0.1"))

    @Test
    fun computesSubnetFromAddressAndPrefix() {
        assertEquals("10.98.43.0/24", downstream.subnet)
        assertEquals("192.168.40.0/22", Downstream("wlan2", "192.168.43.1", 22).subnet)
    }

    @Test
    fun resolvesPoliciesIntoRoutes() {
        val plan = buildRoutingPlan(
            downstream = downstream,
            clients = listOf(phone, laptop),
            policies = mapOf(
                phone.mac to DevicePolicy(egress = Egress.VPN),
                "aa:bb:cc:00:00:03" to DevicePolicy(internet = false),
            ),
            defaultEgress = Egress.SYSTEM,
            resolved = vpn,
        )

        assertEquals(Route.System, plan.defaultRoute)
        assertEquals(Route.Bound("tun0", "10.8.0.1"), plan.clients[0].route)
        assertEquals(Route.System, plan.clients[1].route)
        // 未連線但有策略的裝置也要預先佈署
        assertEquals(ClientRoute("aa:bb:cc:00:00:03", null, Route.Blocked), plan.clients[2])
    }

    @Test
    fun bindsToMissingEgressWithoutInterface() {
        val plan = buildRoutingPlan(downstream, listOf(phone), emptyMap(), Egress.VPN, emptyMap())

        assertEquals(Route.Bound(null, FALLBACK_DNS), plan.defaultRoute)
        assertEquals(Route.Bound(null, FALLBACK_DNS), plan.clients[0].route)
    }

    @Test
    fun boundClientIsRoutedThroughEgressAndFailsClosed() {
        val script = RoutingPlan(
            downstream,
            listOf(ClientRoute(phone.mac, phone.ip, Route.Bound("tun0", "10.8.0.1"))),
            Route.System,
        ).toScript()

        assertTrue("-A ah_fwd -i wlan2 -s 10.98.43.10 -o tun0 -j ACCEPT" in script)
        // 不是從指定出口離開的封包一律丟棄
        assertTrue("-A ah_fwd -i wlan2 -s 10.98.43.10 -j DROP" in script)
        assertTrue("ip rule add from 10.98.43.10 iif wlan2 unreachable prio 20501" in script)
        assertTrue("--dport 53 -j DNAT --to-destination 10.8.0.1" in script)
        assertTrue("-A ah_post -s 10.98.43.0/24 -o tun0 -j MASQUERADE" in script)
        // IPv6 無法導向指定出口，必須擋下
        assertTrue("-A ah_fwd -i wlan2 -m mac --mac-source aa:bb:cc:00:00:01 -j DROP" in script)
        // 預設跟隨系統時不可加上全面丟棄的規則
        assertFalse("-A ah_fwd -i wlan2 -j DROP" in script)
    }

    @Test
    fun boundClientWithoutEgressInterfaceIsFullyBlocked() {
        val script = RoutingPlan(
            downstream,
            listOf(ClientRoute(phone.mac, phone.ip, Route.Bound(null, FALLBACK_DNS))),
            Route.System,
        ).toScript()

        assertTrue("-A ah_fwd -i wlan2 -m mac --mac-source aa:bb:cc:00:00:01 -j DROP" in script)
        assertFalse("-j ACCEPT" in script)
        assertFalse("lookup" in script)
    }

    @Test
    fun strictDefaultDropsUnknownDevicesButExemptsSystemClients() {
        val script = RoutingPlan(
            downstream,
            listOf(ClientRoute(laptop.mac, laptop.ip, Route.System)),
            Route.Bound("tun0", "10.8.0.1"),
        ).toScript()

        val exemption = script.indexOf("-A ah_fwd -i wlan2 -s 10.98.43.20 -m mac --mac-source aa:bb:cc:00:00:02 -j RETURN")
        val catchAll = script.indexOf("-A ah_fwd -i wlan2 -j DROP")
        assertTrue(exemption >= 0)
        assertTrue(catchAll > exemption)
    }

    @Test
    fun rejectsValuesThatCouldInjectShellSyntax() {
        val plan = RoutingPlan(
            downstream,
            listOf(ClientRoute(phone.mac, "10.0.0.1; reboot", Route.Blocked)),
            Route.System,
        )

        assertThrows(IllegalArgumentException::class.java) { plan.toScript() }
    }
}
