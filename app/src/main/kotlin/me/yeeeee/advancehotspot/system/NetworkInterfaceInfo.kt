package me.yeeeee.advancehotspot.system

/** `ip -br addr` 的單一介面資訊。 */
data class NetworkInterfaceInfo(
    val name: String,
    val state: String,
    val addresses: List<String>,
) {
    val isUp: Boolean get() = state == "UP" || (state == "UNKNOWN" && addresses.isNotEmpty())
}

/**
 * 解析 `ip -br addr` 的輸出。
 *
 * 每行格式為 `<名稱> <狀態> [位址...]`；虛擬介面的名稱會帶 `@parent` 後綴，這裡一併去除。
 */
fun parseBriefAddressOutput(lines: List<String>): List<NetworkInterfaceInfo> =
    lines.mapNotNull { line ->
        val columns = line.trim().split(Regex("\\s+"))
        if (columns.size < 2) return@mapNotNull null
        NetworkInterfaceInfo(
            name = columns[0].substringBefore('@'),
            state = columns[1],
            addresses = columns.drop(2),
        )
    }
