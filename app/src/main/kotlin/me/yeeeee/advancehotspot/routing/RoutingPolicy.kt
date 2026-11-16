package me.yeeeee.advancehotspot.routing

import android.content.Context
import me.yeeeee.advancehotspot.hotspot.ConnectedClient

/**
 * 出口以網路類型而非介面名稱表示：VPN 每次重連介面名稱可能不同（tun0、tun1…），
 * 綁定名稱會在重連後失效。
 */
enum class Egress {
    /** 只用於單一裝置：沿用全域預設出口。 */
    INHERIT,

    /** 交給系統 tethering，跟隨手機目前的預設網路。 */
    SYSTEM,
    WIFI,
    CELLULAR,
    VPN,
    ETHERNET,
}

data class DevicePolicy(val internet: Boolean = true, val egress: Egress = Egress.INHERIT) {
    val isDefault: Boolean get() = internet && egress == Egress.INHERIT
}

/** 某個出口類型目前對應的實際介面。 */
data class ResolvedEgress(val iface: String, val dns: String?)

/** 出口網路沒有提供 IPv4 DNS 時使用的公共 DNS，查詢仍會從該出口送出。 */
const val FALLBACK_DNS = "1.1.1.1"

/**
 * 把使用者設定的策略與目前的網路狀態合併成可套用的計畫。
 *
 * @param policies 所有已儲存的裝置策略；不在 [clients] 中的裝置也會以 MAC 規則預先佈署，
 *   讓它一連上就受到限制，不必等下一次更新。
 */
fun buildRoutingPlan(
    downstream: Downstream,
    clients: List<ConnectedClient>,
    policies: Map<String, DevicePolicy>,
    defaultEgress: Egress,
    resolved: Map<Egress, ResolvedEgress>,
    extraSubnets: List<String> = emptyList(),
): RoutingPlan {
    fun routeFor(egress: Egress): Route = when (egress) {
        Egress.INHERIT, Egress.SYSTEM -> Route.System
        else -> resolved[egress].let { Route.Bound(it?.iface, it?.dns ?: FALLBACK_DNS) }
    }

    val defaultRoute = routeFor(defaultEgress)

    fun routeFor(policy: DevicePolicy): Route = when {
        !policy.internet -> Route.Blocked
        policy.egress == Egress.INHERIT -> defaultRoute
        else -> routeFor(policy.egress)
    }

    val connectedMacs = clients.map { it.mac }.toSet()
    val connected = clients.map { client ->
        ClientRoute(client.mac, client.ip, routeFor(policies[client.mac] ?: DevicePolicy()))
    }
    val absent = policies.filterKeys { it !in connectedMacs }.map { (mac, policy) ->
        ClientRoute(mac, null, routeFor(policy))
    }
    return RoutingPlan(downstream, connected + absent, defaultRoute, extraSubnets)
}

/** 路由控制的持久化設定。 */
class RoutingStore(context: Context) {
    private val preferences = context.getSharedPreferences("routing", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false)
        set(value) = preferences.edit().putBoolean(KEY_ENABLED, value).apply()

    var defaultEgress: Egress
        get() = parseEgress(preferences.getString(KEY_DEFAULT_EGRESS, null)) ?: Egress.SYSTEM
        set(value) = preferences.edit().putString(KEY_DEFAULT_EGRESS, value.name).apply()

    fun policies(): Map<String, DevicePolicy> = preferences.all
        .filterKeys { it.startsWith(POLICY_PREFIX) }
        .mapNotNull { (key, value) ->
            val parts = (value as? String)?.split(',') ?: return@mapNotNull null
            val egress = parseEgress(parts.getOrNull(1)) ?: return@mapNotNull null
            key.removePrefix(POLICY_PREFIX) to DevicePolicy(internet = parts[0] == "1", egress = egress)
        }
        .toMap()

    fun setPolicy(mac: String, policy: DevicePolicy) {
        val editor = preferences.edit()
        if (policy.isDefault) {
            editor.remove(POLICY_PREFIX + mac)
        } else {
            editor.putString(POLICY_PREFIX + mac, "${if (policy.internet) 1 else 0},${policy.egress.name}")
        }
        editor.apply()
    }

    private fun parseEgress(name: String?): Egress? = Egress.entries.firstOrNull { it.name == name }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_DEFAULT_EGRESS = "default_egress"
        const val POLICY_PREFIX = "policy:"
    }
}
