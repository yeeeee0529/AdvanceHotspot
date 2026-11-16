package me.yeeeee.advancehotspot.traffic

import android.content.Context
import java.util.Locale

/** 單一裝置的速率上限，單位 kbit/s；0 代表不限制。 */
data class TrafficLimit(val upKbit: Int = 0, val downKbit: Int = 0) {
    val isUnlimited: Boolean get() = upKbit == 0 && downKbit == 0
}

data class TrafficClient(val ip: String, val limit: TrafficLimit)

/** 核心計數器的原始讀數，單位位元組。 */
data class Counter(val up: Long, val down: Long)

/** 顯示用的累計用量與即時速率（位元組、位元組／秒）。 */
data class TrafficStats(
    val upBytes: Long = 0,
    val downBytes: Long = 0,
    val upRate: Long = 0,
    val downRate: Long = 0,
)

private const val CHAIN = "ah_traffic"
private val IFACE_PATTERN = Regex("[A-Za-z0-9_.-]{1,15}")
private val IPV4_PATTERN = Regex("\\d{1,3}(\\.\\d{1,3}){3}")

/** 讀取目前計數器的指令，輸出交給 [parseCounters]。 */
const val TRAFFIC_COUNTERS_COMMAND = "iptables -w -S $CHAIN -v 2>/dev/null"

/**
 * 移除所有介面上系統掛載的 tethering BPF offload 程式。
 *
 * 被 offload 的封包完全不經過 netfilter，不移除的話計數會偏低、限速也不會生效。
 * 上傳方向掛在熱點介面，下載方向掛在上游介面，兩邊都要處理；系統在上游切換時會重新掛上，
 * 所以每次套用都要再執行一次。
 */
val DISABLE_TETHER_OFFLOAD_SCRIPT = """
    for dev in ${'$'}(ls /sys/class/net); do
      tc filter show dev ${'$'}dev ingress 2>/dev/null |
        awk '/tether/ {for(i=1;i<=NF;i++){if(${'$'}i=="protocol")p=${'$'}(i+1); if(${'$'}i=="pref")r=${'$'}(i+1)}; print p, r}' |
        sort -u | while read protocol pref; do
          tc filter del dev ${'$'}dev ingress protocol ${'$'}protocol pref ${'$'}pref 2>/dev/null
        done
    done
""".trimIndent()

/**
 * 產生建立計數與限速規則的腳本。
 *
 * 腳本會先印出重建前的計數器（重建會歸零），呼叫端要把這份輸出計入累計值。
 * 限速以 hashlimit 丟棄超出速率的封包，而不是排隊整形，TCP 會自行降速到上限附近。
 */
fun buildTrafficScript(iface: String, clients: List<TrafficClient>): String {
    requireMatch(IFACE_PATTERN, iface)
    val rules = clients.flatMapIndexed { index, client ->
        val ip = requireMatch(IPV4_PATTERN, client.ip)
        buildList {
            if (client.limit.upKbit > 0) {
                add("-A $CHAIN -i $iface -s $ip ${limitMatch("ahu$index", "srcip", client.limit.upKbit)} -j DROP")
            }
            // 沒有目標的規則只計數，放在限速之後才不會把被丟棄的封包算進去
            add("-A $CHAIN -i $iface -s $ip")
            if (client.limit.downKbit > 0) {
                add("-A $CHAIN -o $iface -d $ip ${limitMatch("ahd$index", "dstip", client.limit.downKbit)} -j DROP")
            }
            add("-A $CHAIN -o $iface -d $ip")
        }
    }
    return buildString {
        appendLine(TRAFFIC_COUNTERS_COMMAND)
        appendLine("rc=0")
        appendLine("iptables-restore -w --noflush <<'AH_RULES' || rc=1")
        appendLine("*filter")
        appendLine(":$CHAIN - [0:0]")
        rules.forEach(::appendLine)
        appendLine("COMMIT")
        appendLine("AH_RULES")
        // 必須排在其他會直接放行封包的鏈之前，否則那些封包不會被計數或限速，所以每次都重新插到最前面
        appendLine("iptables -w -D FORWARD -j $CHAIN 2>/dev/null")
        appendLine("iptables -w -I FORWARD 1 -j $CHAIN || rc=1")
        appendLine(DISABLE_TETHER_OFFLOAD_SCRIPT)
        appendLine("[ \$rc -eq 0 ]")
    }
}

/** 產生移除規則的腳本，同樣會先印出最後的計數器。 */
fun trafficTeardownScript(): String = buildString {
    appendLine(TRAFFIC_COUNTERS_COMMAND)
    appendLine("iptables -w -D FORWARD -j $CHAIN 2>/dev/null")
    appendLine("iptables -w -F $CHAIN 2>/dev/null")
    appendLine("iptables -w -X $CHAIN 2>/dev/null")
    appendLine("true")
}

/**
 * 解析 `iptables -S -v` 的輸出，回傳各 IP 的上傳與下載位元組數。
 *
 * 只看沒有 `-j` 的計數規則：`-s <ip>` 是上傳，`-d <ip>` 是下載，`-c <封包數> <位元組數>` 是計數。
 */
fun parseCounters(lines: List<String>): Map<String, Counter> {
    val counters = mutableMapOf<String, Counter>()
    for (line in lines) {
        val tokens = line.trim().split(' ')
        if (tokens.getOrNull(0) != "-A" || tokens.getOrNull(1) != CHAIN || "-j" in tokens) continue
        val bytes = tokens.indexOf("-c").takeIf { it >= 0 }?.let { tokens.getOrNull(it + 2)?.toLongOrNull() } ?: continue
        val source = tokens.valueAfter("-s")
        val destination = tokens.valueAfter("-d")
        val ip = (source ?: destination ?: continue).substringBefore('/')
        val previous = counters[ip] ?: Counter(0, 0)
        counters[ip] = if (source != null) previous.copy(up = bytes) else previous.copy(down = bytes)
    }
    return counters
}

/** 計數器比上次小代表規則被重建過，此時新的讀數本身就是增加量。 */
fun counterDelta(previous: Long, current: Long): Long = if (current >= previous) current - previous else current

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.2f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format(Locale.ROOT, "%.0f KB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

/** 速率以網路慣用的位元為單位顯示。 */
fun formatRate(bytesPerSecond: Long): String {
    val bits = bytesPerSecond * 8
    return when {
        bits >= 1_000_000 -> String.format(Locale.ROOT, "%.1f Mbps", bits / 1_000_000.0)
        bits >= 1_000 -> String.format(Locale.ROOT, "%.0f Kbps", bits / 1_000.0)
        else -> "$bits bps"
    }
}

/** hashlimit 的速率單位是位元組，且突發量不可小於速率；給兩秒的量讓連線起步不至於太慢。 */
private fun limitMatch(name: String, mode: String, kbit: Int): String {
    val kilobytes = (kbit / 8).coerceAtLeast(1)
    return "-m hashlimit --hashlimit-name $name --hashlimit-mode $mode " +
        "--hashlimit-above ${kilobytes}kb/s --hashlimit-burst ${kilobytes * 2}kb"
}

private fun List<String>.valueAfter(flag: String): String? =
    indexOf(flag).takeIf { it >= 0 }?.let { getOrNull(it + 1) }

/** 所有會進入腳本的外部值都必須通過格式檢查，避免被當成 shell 語法執行。 */
private fun requireMatch(pattern: Regex, value: String): String {
    require(pattern.matches(value)) { "Refusing to use unexpected value in traffic script: $value" }
    return value
}

/** 流量統計與限速的持久化設定。 */
class TrafficStore(context: Context) {
    private val preferences = context.getSharedPreferences("traffic", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false)
        set(value) = preferences.edit().putBoolean(KEY_ENABLED, value).apply()

    fun limits(): Map<String, TrafficLimit> = readPairs(LIMIT_PREFIX) { up, down ->
        TrafficLimit(up.toInt(), down.toInt())
    }

    fun setLimit(mac: String, limit: TrafficLimit) {
        val editor = preferences.edit()
        if (limit.isUnlimited) {
            editor.remove(LIMIT_PREFIX + mac)
        } else {
            editor.putString(LIMIT_PREFIX + mac, "${limit.upKbit},${limit.downKbit}")
        }
        editor.apply()
    }

    /** 各裝置的累計上傳與下載位元組數。 */
    fun totals(): Map<String, Counter> = readPairs(TOTAL_PREFIX) { up, down -> Counter(up, down) }

    fun saveTotals(totals: Map<String, Counter>) {
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith(TOTAL_PREFIX) }.forEach(editor::remove)
        totals.forEach { (mac, counter) -> editor.putString(TOTAL_PREFIX + mac, "${counter.up},${counter.down}") }
        editor.apply()
    }

    private fun <T> readPairs(prefix: String, convert: (Long, Long) -> T): Map<String, T> = preferences.all
        .filterKeys { it.startsWith(prefix) }
        .mapNotNull { (key, value) ->
            val parts = (value as? String)?.split(',')?.mapNotNull { it.toLongOrNull() }
            if (parts?.size != 2) return@mapNotNull null
            key.removePrefix(prefix) to convert(parts[0], parts[1])
        }
        .toMap()

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val LIMIT_PREFIX = "limit:"
        const val TOTAL_PREFIX = "total:"
    }
}
