package me.yeeeee.advancehotspot.hotspot

import android.util.Base64
import kotlinx.coroutines.delay
import me.yeeeee.advancehotspot.helper.HelperMain
import me.yeeeee.advancehotspot.root.RootShell
import org.json.JSONArray
import org.json.JSONObject

class HotspotException(message: String) : Exception(message)

/**
 * 熱點控制的 app 端入口：經由 root shell 降權為 shell 身分執行 [HelperMain]，並解析其 JSON 回應。
 *
 * @param apkPath 本 app 的 APK 路徑，作為 `app_process` 的 classpath。
 */
class HotspotController(private val apkPath: String) {

    suspend fun status(): HotspotStatus = parseStatus(invoke("status"))

    /** 寫入設定並回傳系統實際儲存的結果。 */
    suspend fun applyConfig(config: HotspotConfig): HotspotConfig {
        val encoded = Base64.encodeToString(
            config.toJson().toString().toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP,
        )
        return parseConfig(invoke("set-config", encoded).getJSONObject("config"))
    }

    suspend fun start() {
        invoke("start")
    }

    suspend fun stop() {
        invoke("stop")
    }

    /** 輪詢直到熱點進入指定狀態；逾時則回傳最後一次讀到的狀態。 */
    suspend fun awaitApState(target: Int): HotspotStatus {
        var latest = status()
        repeat(STATE_POLL_ATTEMPTS) {
            if (latest.apState == target) return latest
            delay(STATE_POLL_INTERVAL_MS)
            latest = status()
        }
        return latest
    }

    private suspend fun invoke(vararg args: String): JSONObject {
        // 參數只會是固定指令或 URL-safe base64，放進單引號內不需要再跳脫
        val helperCommand = "CLASSPATH=$apkPath app_process /system/bin " +
            "${HelperMain::class.java.name} ${args.joinToString(" ")}"
        val result = RootShell.run("su $SHELL_UID -c '$helperCommand'")
        val line = result.stdout.lastOrNull { it.startsWith(HelperMain.RESPONSE_PREFIX) }
            ?: throw HotspotException(
                "Helper produced no response (exit ${result.exitCode}): " +
                    result.stderr.takeLast(3).joinToString(" | "),
            )
        val response = JSONObject(line.removePrefix(HelperMain.RESPONSE_PREFIX))
        if (!response.optBoolean("ok")) {
            throw HotspotException(response.optString("error", "Unknown helper error"))
        }
        return response
    }

    private fun parseStatus(json: JSONObject): HotspotStatus = HotspotStatus(
        apState = json.getInt("apState"),
        config = parseConfig(json.getJSONObject("config")),
        capability = json.optJSONObject("capability")?.let(::parseCapability),
        activeFrequencies = json.getJSONArray("activeFrequencies").toIntList(),
        clientCount = json.getInt("clientCount"),
    )

    private fun parseConfig(json: JSONObject): HotspotConfig = HotspotConfig(
        ssid = json.getString("ssid"),
        passphrase = json.getString("passphrase"),
        securityType = json.getInt("securityType"),
        band = json.getInt("band"),
        channel = json.getInt("channel"),
        hidden = json.getBoolean("hidden"),
        maxClients = json.getInt("maxClients"),
        autoShutdown = json.getBoolean("autoShutdown"),
    )

    private fun parseCapability(json: JSONObject): HotspotCapability {
        val channels = json.getJSONObject("channels")
        return HotspotCapability(
            maxSupportedClients = json.getInt("maxSupportedClients"),
            channels = channels.keys().asSequence()
                .associate { band -> band.toInt() to channels.getJSONArray(band).toIntList() },
        )
    }

    private fun HotspotConfig.toJson(): JSONObject = JSONObject()
        .put("ssid", ssid)
        .put("passphrase", passphrase)
        .put("securityType", securityType)
        .put("band", band)
        .put("channel", channel)
        .put("hidden", hidden)
        .put("maxClients", maxClients)
        .put("autoShutdown", autoShutdown)

    private fun JSONArray.toIntList(): List<Int> = List(length()) { getInt(it) }

    private companion object {
        const val SHELL_UID = 2000
        const val STATE_POLL_ATTEMPTS = 15
        const val STATE_POLL_INTERVAL_MS = 700L
    }
}
