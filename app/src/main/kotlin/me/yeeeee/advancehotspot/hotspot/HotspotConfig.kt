package me.yeeeee.advancehotspot.hotspot

/** 對應 `SoftApConfiguration.SECURITY_TYPE_*`。 */
object SecurityType {
    const val OPEN = 0
    const val WPA2 = 1
    const val WPA3_TRANSITION = 2
    const val WPA3 = 3
}

/** 對應 `SoftApConfiguration.BAND_*` 的位元遮罩。 */
object Band {
    const val BAND_2GHZ = 1
    const val BAND_5GHZ = 2
    const val BAND_6GHZ = 4

    /** 由系統在 2.4 GHz 與 5 GHz 之間自動挑選。 */
    const val AUTO_2GHZ_5GHZ = BAND_2GHZ or BAND_5GHZ

    fun isSingle(band: Int): Boolean = band == BAND_2GHZ || band == BAND_5GHZ || band == BAND_6GHZ
}

/** 對應 `WifiManager.WIFI_AP_STATE_*`。 */
object ApState {
    const val DISABLING = 10
    const val DISABLED = 11
    const val ENABLING = 12
    const val ENABLED = 13
    const val FAILED = 14
}

/**
 * 本 app 管理的熱點設定。
 *
 * 加密方式與頻段保留系統的原始整數值，遇到 app 尚未支援的值（例如 OWE）時仍可原樣寫回。
 */
data class HotspotConfig(
    val ssid: String,
    val passphrase: String,
    val securityType: Int,
    val band: Int,
    /** 0 代表由系統自動選擇頻道。 */
    val channel: Int,
    val hidden: Boolean,
    /** 0 代表不限制，實際上限為 [HotspotCapability.maxSupportedClients]。 */
    val maxClients: Int,
    val autoShutdown: Boolean,
)

data class HotspotCapability(
    val maxSupportedClients: Int,
    /** 以頻段位元為鍵的可用頻道清單，內容會隨國碼法規而變。 */
    val channels: Map<Int, List<Int>>,
)

data class HotspotStatus(
    val apState: Int,
    val config: HotspotConfig,
    val capability: HotspotCapability?,
    val activeFrequencies: List<Int>,
    val clientCount: Int,
) {
    val isEnabled: Boolean get() = apState == ApState.ENABLED
    val isTransitioning: Boolean get() = apState == ApState.ENABLING || apState == ApState.DISABLING
}

enum class ConfigError {
    SSID_EMPTY,
    SSID_TOO_LONG,
    PASSPHRASE_LENGTH,
    SIX_GHZ_REQUIRES_WPA3,
    CHANNEL_UNSUPPORTED,
    MAX_CLIENTS_RANGE,
}

private const val SSID_MAX_BYTES = 32
private val PSK_LENGTH = 8..63

/** 在送交系統前先檢查，回傳第一個違反的規則；沒有問題時回傳 null。 */
fun HotspotConfig.validate(capability: HotspotCapability?): ConfigError? {
    val ssidBytes = ssid.toByteArray(Charsets.UTF_8).size
    return when {
        ssidBytes == 0 -> ConfigError.SSID_EMPTY
        ssidBytes > SSID_MAX_BYTES -> ConfigError.SSID_TOO_LONG
        securityType in setOf(SecurityType.WPA2, SecurityType.WPA3_TRANSITION) &&
            passphrase.length !in PSK_LENGTH -> ConfigError.PASSPHRASE_LENGTH
        securityType == SecurityType.WPA3 && passphrase.isEmpty() -> ConfigError.PASSPHRASE_LENGTH
        band == Band.BAND_6GHZ && securityType != SecurityType.WPA3 -> ConfigError.SIX_GHZ_REQUIRES_WPA3
        channel != 0 && !Band.isSingle(band) -> ConfigError.CHANNEL_UNSUPPORTED
        channel != 0 && capability != null &&
            channel !in capability.channels[band].orEmpty() -> ConfigError.CHANNEL_UNSUPPORTED
        maxClients < 0 -> ConfigError.MAX_CLIENTS_RANGE
        capability != null && maxClients > capability.maxSupportedClients -> ConfigError.MAX_CLIENTS_RANGE
        else -> null
    }
}

/** 將中心頻率（MHz）換算為 Wi-Fi 頻道編號；無法辨識時回傳 null。 */
fun frequencyToChannel(frequencyMhz: Int): Int? = when (frequencyMhz) {
    2484 -> 14
    in 2412..2472 -> (frequencyMhz - 2407) / 5
    in 5160..5885 -> (frequencyMhz - 5000) / 5
    in 5955..7115 -> (frequencyMhz - 5950) / 5
    else -> null
}
