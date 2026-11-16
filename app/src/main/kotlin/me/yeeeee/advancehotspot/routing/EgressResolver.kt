package me.yeeeee.advancehotspot.routing

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.util.EnumMap

/** 查詢各出口類型目前對應到哪個網路介面。 */
class EgressResolver(context: Context) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    /**
     * @param excludeIface 熱點自己的介面，不可當成出口。
     */
    fun resolve(excludeIface: String?): Map<Egress, ResolvedEgress> {
        val result = EnumMap<Egress, ResolvedEgress>(Egress::class.java)
        // 需要同時看到 VPN 與非預設網路，NetworkCallback 無法一次列舉，因此仍使用這個已過時的 API
        @Suppress("DEPRECATION")
        val networks = connectivityManager.allNetworks
        for (network in networks) {
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: continue
            val properties = connectivityManager.getLinkProperties(network) ?: continue
            val iface = properties.interfaceName ?: continue
            if (iface == excludeIface) continue
            // 行動網路可能同時存在 IMS 等專用連線，只取可上網的那一條
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue

            val egress = when {
                // VPN 的 capabilities 也會帶著底層網路的 transport，必須先判斷
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> Egress.VPN
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Egress.WIFI
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Egress.CELLULAR
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Egress.ETHERNET
                else -> continue
            }
            val dns = properties.dnsServers.firstOrNull { it is Inet4Address }?.hostAddress
            result.putIfAbsent(egress, ResolvedEgress(iface, dns))
        }
        return result
    }
}
