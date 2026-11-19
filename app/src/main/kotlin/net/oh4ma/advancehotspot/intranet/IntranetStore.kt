package net.oh4ma.advancehotspot.intranet

import android.content.Context
import net.oh4ma.advancehotspot.hotspot.ClientSnapshot
import net.oh4ma.advancehotspot.privilege.PrivilegeSettings
import net.oh4ma.advancehotspot.root.RootShell

/** 內網的持久化設定。 */
class IntranetStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("intranet", Context.MODE_PRIVATE)

    /** Shizuku 模式下一律視為關閉；儲存的設定不變，切回 root 模式就會恢復。 */
    var enabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false) && PrivilegeSettings.rootFeaturesAvailable
        set(value) = preferences.edit().putBoolean(KEY_ENABLED, value).apply()

    var gateway: String
        get() = preferences.getString(KEY_GATEWAY, null) ?: DEFAULT_GATEWAY
        set(value) = preferences.edit().putString(KEY_GATEWAY, value).apply()

    /** dnsmasq 的設定檔、租約與 pid 存放處。 */
    val directory: String get() = "${context.filesDir.absolutePath}/intranet"

    fun staticLeases(): Map<String, String> = preferences.all
        .filterKeys { it.startsWith(LEASE_PREFIX) }
        .mapNotNull { (key, value) -> (value as? String)?.let { key.removePrefix(LEASE_PREFIX) to it } }
        .toMap()

    /** 傳入 null 代表取消這台裝置的固定 IP。 */
    fun setStaticLease(mac: String, ip: String?) {
        val editor = preferences.edit()
        if (ip == null) editor.remove(LEASE_PREFIX + mac) else editor.putString(LEASE_PREFIX + mac, ip)
        editor.apply()
    }

    /**
     * 內網啟用時，裝置的位址由本 app 的 DHCP 發放，系統回報的 IP 與主機名稱不再正確，
     * 改以租約檔的內容覆寫。
     */
    suspend fun mergeLeases(snapshot: ClientSnapshot): ClientSnapshot {
        if (!enabled) return snapshot
        val leases = parseLeases(RootShell.run("cat $directory/leases 2>/dev/null").stdout)
        return snapshot.copy(
            clients = snapshot.clients.map { client ->
                val lease = leases[client.mac] ?: return@map client
                client.copy(ip = lease.ip, hostname = lease.hostname ?: client.hostname)
            },
        )
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_GATEWAY = "gateway"
        const val LEASE_PREFIX = "lease:"
    }
}
