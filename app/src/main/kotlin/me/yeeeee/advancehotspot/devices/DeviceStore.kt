package me.yeeeee.advancehotspot.devices

import android.content.Context

/**
 * 以 MAC 位址為鍵保存使用者對裝置的標註。
 *
 * 主機名稱也一併記下，裝置離線或被封鎖後仍能認出是哪一台。
 */
class DeviceStore(context: Context) {
    private val nicknames = context.getSharedPreferences("device_nicknames", Context.MODE_PRIVATE)
    private val hostnames = context.getSharedPreferences("device_hostnames", Context.MODE_PRIVATE)

    fun nickname(mac: String): String? = nicknames.getString(mac, null)

    /** 傳入空白字串代表清除暱稱。 */
    fun setNickname(mac: String, nickname: String) {
        val trimmed = nickname.trim()
        val editor = nicknames.edit()
        if (trimmed.isEmpty()) editor.remove(mac) else editor.putString(mac, trimmed)
        editor.apply()
    }

    fun lastHostname(mac: String): String? = hostnames.getString(mac, null)

    fun rememberHostname(mac: String, hostname: String) {
        if (hostnames.getString(mac, null) != hostname) {
            hostnames.edit().putString(mac, hostname).apply()
        }
    }
}
