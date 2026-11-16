package me.yeeeee.advancehotspot.intranet

/** 內網固定使用 /24，足以容納熱點支援的裝置數，也讓設定只需要一個閘道位址。 */
const val INTRANET_PREFIX_LENGTH = 24
const val INTRANET_DOMAIN = "lan"
const val DEFAULT_GATEWAY = "10.77.0.1"

/** 閘道本身在內網中的名稱。 */
private const val GATEWAY_HOST_LABEL = "hotspot"

/** dnsmasq 實際監聽的 DHCP 埠；67 埠被系統的 DHCP 服務佔用，無法共用。 */
private const val DHCP_ALTERNATE_PORT = 1067
private const val LEASE_TIME = "1h"
private const val DNSMASQ = "/system/bin/dnsmasq"

private const val CHAIN_PREROUTING = "ah_dhcp_pre"
private const val CHAIN_POSTROUTING = "ah_dhcp_post"
private const val CHAIN_OUTPUT = "ah_dhcp_out"

/** netd 的 local_network 路由表，轉送回熱點裝置的封包靠它找到路。 */
private const val LOCAL_NETWORK_TABLE = 97
private const val NETD_TABLE_OFFSET = 1000

private val IFACE_PATTERN = Regex("[A-Za-z0-9_.-]{1,15}")
private val MAC_PATTERN = Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}")
private val IPV4_PATTERN = Regex("\\d{1,3}(\\.\\d{1,3}){3}")
private val LABEL_PATTERN = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")
private val PATH_PATTERN = Regex("[A-Za-z0-9_./~=-]+")

data class HostEntry(val ip: String, val name: String)

data class Lease(val ip: String, val hostname: String?)

/**
 * 內網的完整設定。
 *
 * @param staticLeases MAC 位址對應固定 IP。
 * @param hosts 本地 DNS 名稱（不含網域），查詢時會自動補上 [INTRANET_DOMAIN]。
 * @param upstreamDns 非本地名稱要轉送的上游 DNS 伺服器。
 */
data class IntranetConfig(
    val gateway: String,
    val staticLeases: Map<String, String>,
    val hosts: List<HostEntry>,
    val upstreamDns: List<String>,
)

/** 閘道必須是私有位址，且不是網段的網路位址或廣播位址。 */
fun isValidGateway(address: String): Boolean {
    val octets = parseIpv4(address) ?: return false
    val private = octets[0] == 10 ||
        (octets[0] == 172 && octets[1] in 16..31) ||
        (octets[0] == 192 && octets[1] == 168)
    return private && octets[3] in 1..254
}

/** [address] 是否為 [gateway] 所在 /24 中可分配給裝置的位址。 */
fun isAssignableAddress(gateway: String, address: String): Boolean {
    val gatewayOctets = parseIpv4(gateway) ?: return false
    val octets = parseIpv4(address) ?: return false
    return octets.take(3) == gatewayOctets.take(3) && octets[3] in 1..254 && address != gateway
}

fun subnetOf(gateway: String): String = "${gateway.substringBeforeLast('.')}.0/$INTRANET_PREFIX_LENGTH"

/**
 * 把任意名稱轉成合法的 DNS label；無法轉換時回傳 null。
 *
 * 只保留英數字與連字號，中文等其他字元會被捨棄。
 */
fun sanitizeHostLabel(raw: String?): String? {
    val label = raw.orEmpty().lowercase()
        .replace(Regex("[^a-z0-9-]+"), "-")
        .trim('-')
        .take(63)
        .trim('-')
    return label.ifEmpty { null }
}

/**
 * 為每台裝置產生本地名稱；暱稱優先於裝置自己回報的主機名稱，重複的名稱會加上序號。
 *
 * @param devices 每台裝置的 IP 與候選名稱（依優先順序）。
 */
fun buildHostEntries(gateway: String, devices: List<Pair<String, List<String?>>>): List<HostEntry> {
    val used = mutableSetOf(GATEWAY_HOST_LABEL)
    val entries = mutableListOf(HostEntry(gateway, GATEWAY_HOST_LABEL))
    for ((ip, candidates) in devices) {
        val base = candidates.firstNotNullOfOrNull(::sanitizeHostLabel) ?: continue
        var name = base
        var suffix = 2
        while (!used.add(name)) name = "${base.take(60)}-${suffix++}"
        entries += HostEntry(ip, name)
    }
    return entries
}

/** 回傳 [ip] 對應的完整本地網域名稱；沒有對應時回傳 null。 */
fun List<HostEntry>.fqdnOf(ip: String?): String? =
    firstOrNull { it.ip == ip }?.let { "${it.name}.$INTRANET_DOMAIN" }

/** 解析 dnsmasq 的租約檔，每行格式為 `<到期時間> <MAC> <IP> <主機名稱> <client-id>`。 */
fun parseLeases(lines: List<String>): Map<String, Lease> = lines.mapNotNull { line ->
    val columns = line.trim().split(Regex("\\s+"))
    if (columns.size < 4) return@mapNotNull null
    columns[1].lowercase() to Lease(ip = columns[2], hostname = columns[3].takeIf { it != "*" })
}.toMap()

/**
 * 產生建立或更新內網的 shell 腳本，可重複執行。
 *
 * 做法是在熱點介面加上第二組位址當作閘道，再啟動兩個 dnsmasq：一個只負責 DHCP，一個只負責 DNS。
 * 系統的 DHCP 服務佔住 67 埠且無法關閉，所以用 NAT 把 DHCP 封包轉到替代埠，
 * 廣播封包只改埠號、保留廣播位址，否則來源為 0.0.0.0 的請求會被核心視為異常而丟棄。
 *
 * @param directory 存放設定檔、租約與 pid 的目錄。
 */
fun IntranetConfig.toScript(iface: String, directory: String): String {
    requireMatch(IFACE_PATTERN, iface)
    requireMatch(PATH_PATTERN, directory)
    require(isValidGateway(gateway)) { "Invalid intranet gateway: $gateway" }
    val base = gateway.substringBeforeLast('.')
    val subnet = subnetOf(gateway)

    val dhcpHosts = staticLeases.map { (mac, ip) ->
        require(isAssignableAddress(gateway, ip)) { "Static address $ip is outside $subnet" }
        "${requireMatch(MAC_PATTERN, mac.lowercase())},$ip"
    }
    val hostLines = hosts.map { "${requireMatch(IPV4_PATTERN, it.ip)} ${requireMatch(LABEL_PATTERN, it.name)}" }
    val servers = upstreamDns.map { "--server=${requireMatch(IPV4_PATTERN, it)}" }

    val dhcpCommand = listOf(
        DNSMASQ, "-k", "-C", "/dev/null", "--no-resolv", "--no-hosts", "--port=0",
        "--interface=$iface", "--bind-interfaces",
        "--dhcp-range=$base.2,$base.254,255.255.255.0,$LEASE_TIME",
        "--dhcp-option=3,$gateway", "--dhcp-option=6,$gateway", "--dhcp-option=15,$INTRANET_DOMAIN",
        "--dhcp-authoritative", "--dhcp-alternate-port=$DHCP_ALTERNATE_PORT,68",
        "--dhcp-hostsfile=$directory/dhcp-hosts", "--dhcp-leasefile=$directory/leases", "--user=root",
    ).joinToString(" ")
    val dnsCommand = (
        listOf(
            DNSMASQ, "-k", "-C", "/dev/null", "--no-resolv", "--no-hosts",
            "--bind-interfaces", "--listen-address=$gateway",
            "--domain=$INTRANET_DOMAIN", "--expand-hosts", "--local=/$INTRANET_DOMAIN/",
            "--addn-hosts=$directory/hosts", "--user=root",
        ) + servers
        ).joinToString(" ")

    return buildString {
        appendLine("rc=0")
        appendLine("D=$directory")
        appendLine("mkdir -p \$D")
        appendFile("\$D/hosts", hostLines)
        appendFile("\$D/dhcp-hosts", dhcpHosts)
        appendLine("touch \$D/leases")
        appendLine("ip addr add $gateway/$INTRANET_PREFIX_LENGTH dev $iface 2>/dev/null")
        appendLine("ip route replace $subnet dev $iface table $LOCAL_NETWORK_TABLE || rc=1")
        appendLine(
            "ip route replace $subnet dev $iface " +
                "table \$(($NETD_TABLE_OFFSET + \$(cat /sys/class/net/$iface/ifindex))) || rc=1",
        )
        appendLine("iptables-restore -w --noflush <<'AH_RULES' || rc=1")
        appendLine("*nat")
        appendLine(":$CHAIN_PREROUTING - [0:0]")
        appendLine(":$CHAIN_POSTROUTING - [0:0]")
        appendLine(
            "-A $CHAIN_PREROUTING -i $iface -d 255.255.255.255 -p udp --dport 67 " +
                "-j DNAT --to-destination 255.255.255.255:$DHCP_ALTERNATE_PORT",
        )
        appendLine(
            "-A $CHAIN_PREROUTING -i $iface -d $gateway -p udp --dport 67 -j REDIRECT --to-ports $DHCP_ALTERNATE_PORT",
        )
        // 回應必須看起來來自 67 埠，部分 DHCP 用戶端會檢查來源埠
        appendLine(
            "-A $CHAIN_POSTROUTING -o $iface -p udp --sport $DHCP_ALTERNATE_PORT --dport 68 " +
                "-j SNAT --to-source $gateway:67",
        )
        appendLine("COMMIT")
        appendLine("*filter")
        appendLine(":$CHAIN_OUTPUT - [0:0]")
        // 保險：即使系統的 DHCP 服務收到請求，也不讓它的回應送出去
        appendLine("-A $CHAIN_OUTPUT -o $iface -p udp --sport 67 -m owner --uid-owner network_stack -j DROP")
        appendLine("COMMIT")
        appendLine("AH_RULES")
        for ((table, parent, chain) in HOOKS) {
            appendLine(
                "iptables -w -t $table -C $parent -j $chain 2>/dev/null || " +
                    "iptables -w -t $table -I $parent 1 -j $chain || rc=1",
            )
        }
        append(ENSURE_FUNCTION)
        appendLine("ah_ensure dhcp '$dhcpCommand' || rc=1")
        appendLine("ah_ensure dns '$dnsCommand' || rc=1")
        appendLine("[ \$rc -eq 0 ]")
    }
}

/** 產生移除內網的腳本；[iface] 為 null 代表熱點介面已經不存在。 */
fun intranetTeardownScript(iface: String?, gateway: String, directory: String): String {
    requireMatch(PATH_PATTERN, directory)
    return buildString {
        appendLine("D=$directory")
        for (name in listOf("dhcp", "dns")) {
            appendLine("pid=\$(cat \$D/$name.pid 2>/dev/null)")
            appendLine("[ -n \"\$pid\" ] && grep -q dnsmasq /proc/\$pid/cmdline 2>/dev/null && kill \$pid")
            appendLine("rm -f \$D/$name.pid \$D/$name.sig \$D/$name.in")
        }
        for ((table, parent, chain) in HOOKS) {
            appendLine("iptables -w -t $table -D $parent -j $chain 2>/dev/null")
            appendLine("iptables -w -t $table -F $chain 2>/dev/null")
            appendLine("iptables -w -t $table -X $chain 2>/dev/null")
        }
        if (iface != null && isValidGateway(gateway)) {
            requireMatch(IFACE_PATTERN, iface)
            val subnet = subnetOf(gateway)
            appendLine("ip route del $subnet dev $iface table $LOCAL_NETWORK_TABLE 2>/dev/null")
            appendLine(
                "ip route del $subnet dev $iface " +
                    "table \$(($NETD_TABLE_OFFSET + \$(cat /sys/class/net/$iface/ifindex))) 2>/dev/null",
            )
            appendLine("ip addr del $gateway/$INTRANET_PREFIX_LENGTH dev $iface 2>/dev/null")
        }
        appendLine("true")
    }
}

private val HOOKS = listOf(
    Triple("nat", "PREROUTING", CHAIN_PREROUTING),
    Triple("nat", "POSTROUTING", CHAIN_POSTROUTING),
    Triple("filter", "OUTPUT", CHAIN_OUTPUT),
)

/**
 * 確保指定的 dnsmasq 正在以給定的指令執行：指令沒變就只送 SIGHUP 重新載入名稱檔，否則重新啟動。
 *
 * Android 版的 dnsmasq 會從 stdin 讀取控制指令，接到 EOF 會進入忙碌迴圈，
 * 所以把 stdin 接到一個以讀寫模式開啟的 FIFO，讓它永遠不會讀到 EOF。
 */
private val ENSURE_FUNCTION = """
    ah_ensure() {
      name=${'$'}1; command=${'$'}2
      pid=${'$'}(cat ${'$'}D/${'$'}name.pid 2>/dev/null)
      if [ -n "${'$'}pid" ] && grep -q dnsmasq /proc/${'$'}pid/cmdline 2>/dev/null; then
        if [ "${'$'}(cat ${'$'}D/${'$'}name.sig 2>/dev/null)" = "${'$'}command" ]; then
          kill -HUP ${'$'}pid
          return 0
        fi
        kill ${'$'}pid
        sleep 1
      fi
      rm -f ${'$'}D/${'$'}name.in ${'$'}D/${'$'}name.pid
      mkfifo ${'$'}D/${'$'}name.in || return 1
      nohup ${'$'}command --pid-file=${'$'}D/${'$'}name.pid <>${'$'}D/${'$'}name.in >/dev/null 2>&1 &
      echo "${'$'}command" > ${'$'}D/${'$'}name.sig
      sleep 1
      [ -s ${'$'}D/${'$'}name.pid ]
    }

""".trimIndent() + "\n"

private fun StringBuilder.appendFile(path: String, lines: List<String>) {
    appendLine("cat > $path <<'AH_FILE'")
    lines.forEach(::appendLine)
    appendLine("AH_FILE")
}

private fun parseIpv4(address: String): List<Int>? {
    if (!IPV4_PATTERN.matches(address)) return null
    return address.split('.').map { it.toInt() }.takeIf { octets -> octets.all { it in 0..255 } }
}

/** 所有會進入腳本的外部值都必須通過格式檢查，避免被當成 shell 語法執行。 */
private fun requireMatch(pattern: Regex, value: String): String {
    require(pattern.matches(value)) { "Refusing to use unexpected value in intranet script: $value" }
    return value
}
