package net.oh4ma.advancehotspot.system

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 對外連線看到的公網 IP、所屬組織與國別。 */
data class PublicIpInfo(
    val ip: String,
    val organization: String?,
    /** ISO 3166-1 alpha-2 國碼，例如 TW。 */
    val countryCode: String?,
    val countryName: String?,
) {
    /** 以繁體中文顯示的國名；查不到時用服務回傳的英文名稱。 */
    fun localizedCountry(): String? {
        val code = countryCode
        if (code != null) {
            val name = Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.TRADITIONAL_CHINESE)
            if (name.isNotBlank() && name != code) return name
        }
        return countryName
    }
}

/**
 * 查詢本機目前的出口 IP。與 FlClash 相同，依序嘗試幾個免金鑰的公開服務，取第一個成功的結果。
 *
 * 請求走手機目前的預設網路；有 VPN 時查到的是 VPN 的出口。
 */
object PublicIpLookup {
    private val sources: List<Pair<String, (JSONObject) -> PublicIpInfo?>> = listOf(
        "https://ident.me/json" to ::fromIdentMe,
        "https://ipwho.is/" to ::fromIpWhoIs,
        "https://api.ip.sb/geoip" to ::fromIpSb,
    )

    suspend fun lookup(): PublicIpInfo = withContext(Dispatchers.IO) {
        val failures = mutableListOf<String>()
        for ((url, parse) in sources) {
            try {
                val info = parse(JSONObject(fetch(url)))
                if (info != null) return@withContext info
                failures += "$url: unexpected response"
            } catch (exception: Exception) {
                failures += "$url: ${exception.message ?: exception.javaClass.simpleName}"
            }
        }
        throw IOException("All IP lookup services failed (${failures.joinToString("; ")})")
    }

    private fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            // 部分服務會拒絕沒有 User-Agent 的請求
            connection.setRequestProperty("User-Agent", "AdvanceHotspot")
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private const val TIMEOUT_MS = 6000
}

private fun JSONObject.text(name: String): String? = optString(name).trim().takeIf { it.isNotEmpty() && it != "null" }

private fun fromIdentMe(json: JSONObject): PublicIpInfo? {
    val ip = json.text("ip") ?: return null
    return PublicIpInfo(ip, json.text("aso"), json.text("cc"), json.text("country"))
}

private fun fromIpWhoIs(json: JSONObject): PublicIpInfo? {
    if (!json.optBoolean("success", true)) return null
    val ip = json.text("ip") ?: return null
    val connection = json.optJSONObject("connection")
    val organization = connection?.text("org") ?: connection?.text("isp")
    return PublicIpInfo(ip, organization, json.text("country_code"), json.text("country"))
}

private fun fromIpSb(json: JSONObject): PublicIpInfo? {
    val ip = json.text("ip") ?: return null
    return PublicIpInfo(
        ip,
        json.text("organization") ?: json.text("isp") ?: json.text("asn_organization"),
        json.text("country_code"),
        json.text("country"),
    )
}
