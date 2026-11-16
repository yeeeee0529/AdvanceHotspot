package me.yeeeee.advancehotspot.routing

/** 熱點介面與其 IPv4 位址。 */
data class Downstream(val iface: String, val address: String, val prefixLength: Int) {
    /** 熱點網段的 CIDR 表示，例如 `10.98.43.0/24`。 */
    val subnet: String
        get() {
            val octets = address.split('.').map { it.toInt() }
            val value = octets.fold(0L) { acc, octet -> (acc shl 8) or octet.toLong() }
            val mask = if (prefixLength == 0) 0L else (0xFFFFFFFFL shl (32 - prefixLength)) and 0xFFFFFFFFL
            val network = value and mask
            val dotted = (3 downTo 0).joinToString(".") { ((network shr (it * 8)) and 0xFF).toString() }
            return "$dotted/$prefixLength"
        }
}

/** 單一裝置實際要套用的轉送方式。 */
sealed interface Route {
    /** 不可上網，仍可使用熱點內網。 */
    data object Blocked : Route

    /** 交給系統 tethering 處理，跟隨系統目前的上游。 */
    data object System : Route

    /**
     * 只能從指定介面出去；介面不存在時（[iface] 為 null）直接斷網，不會改走其他介面。
     *
     * @param dns 這台裝置的 DNS 查詢會被導向的伺服器，必須能從 [iface] 抵達。
     */
    data class Bound(val iface: String?, val dns: String) : Route
}

data class ClientRoute(val mac: String, val ip: String?, val route: Route)

/**
 * 一次完整的路由狀態。每次套用都會整組重建，不做增量修改。
 *
 * @param defaultRoute 沒有列在 [clients] 中的裝置（例如剛連上、尚未被偵測到）適用的方式。
 */
data class RoutingPlan(
    val downstream: Downstream,
    val clients: List<ClientRoute>,
    val defaultRoute: Route,
)

private const val CHAIN_FORWARD = "ah_fwd"
private const val CHAIN_INPUT = "ah_in"
private const val CHAIN_PREROUTING = "ah_pre"
private const val CHAIN_POSTROUTING = "ah_post"
private const val CHAIN_MANGLE = "ah_mangle"

/** 必須小於系統 tethering 規則的 21000，才會先於它被比對。 */
private const val RULE_PRIORITY_LOOKUP = 20500
private const val RULE_PRIORITY_UNREACHABLE = 20501

/** netd 為每個介面建立的路由表編號是 ifindex 加上這個位移。 */
private const val NETD_TABLE_OFFSET = 1000

private val IFACE_PATTERN = Regex("[A-Za-z0-9_.-]{1,15}")
private val MAC_PATTERN = Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}")
private val IPV4_PATTERN = Regex("\\d{1,3}(\\.\\d{1,3}){3}")

/** [chain] 掛在 [table] 的哪一條內建鏈上。 */
private data class Hook(val table: String, val parent: String, val chain: String)

private val IPV4_HOOKS = listOf(
    Hook("filter", "FORWARD", CHAIN_FORWARD),
    Hook("filter", "INPUT", CHAIN_INPUT),
    Hook("nat", "PREROUTING", CHAIN_PREROUTING),
    Hook("nat", "POSTROUTING", CHAIN_POSTROUTING),
    Hook("mangle", "FORWARD", CHAIN_MANGLE),
)

private val IPV6_HOOKS = listOf(
    Hook("filter", "FORWARD", CHAIN_FORWARD),
    Hook("filter", "INPUT", CHAIN_INPUT),
)

/**
 * 產生套用這份計畫的 shell 腳本。
 *
 * iptables 的部分以 `iptables-restore` 一次換掉整條鏈，不會出現規則只套一半的空窗；
 * 斷網保護由 filter 鏈負責，`ip rule` 只決定封包從哪個介面出去。
 * 腳本最後一個指令的結束碼代表整體是否成功。
 */
fun RoutingPlan.toScript(): String {
    val downstreamIface = requireMatch(IFACE_PATTERN, downstream.iface)
    requireMatch(IPV4_PATTERN, downstream.address)
    val strictDefault = defaultRoute !is Route.System

    val forward = mutableListOf<String>()
    val input = mutableListOf<String>()
    val prerouting = mutableListOf<String>()
    val forward6 = mutableListOf<String>()
    val input6 = mutableListOf<String>()
    val ipRules = mutableListOf<String>()
    val egressIfaces = LinkedHashSet<String>()

    fun dropDnsToHost(target: MutableList<String>, match: String) {
        target += "-A $CHAIN_INPUT -i $downstreamIface $match -p udp --dport 53 -j DROP"
        target += "-A $CHAIN_INPUT -i $downstreamIface $match -p tcp --dport 53 -j DROP"
    }

    for (client in clients) {
        val mac = requireMatch(MAC_PATTERN, client.mac.lowercase())
        val ip = client.ip?.let { requireMatch(IPV4_PATTERN, it) }
        val fromMac = "-m mac --mac-source $mac"

        when (val route = client.route) {
            Route.System -> if (strictDefault) {
                // 預設規則較嚴格時，替跟隨系統的裝置開例外
                if (ip != null) {
                    forward += "-A $CHAIN_FORWARD -i $downstreamIface -s $ip $fromMac -j RETURN"
                    forward += "-A $CHAIN_FORWARD -o $downstreamIface -d $ip -j RETURN"
                    input += "-A $CHAIN_INPUT -i $downstreamIface -s $ip $fromMac -j RETURN"
                }
                forward6 += "-A $CHAIN_FORWARD -i $downstreamIface $fromMac -j RETURN"
                input6 += "-A $CHAIN_INPUT -i $downstreamIface $fromMac -j RETURN"
            }

            Route.Blocked -> {
                forward += "-A $CHAIN_FORWARD -i $downstreamIface $fromMac -j DROP"
                if (ip != null) forward += "-A $CHAIN_FORWARD -o $downstreamIface -d $ip -j DROP"
                forward6 += "-A $CHAIN_FORWARD -i $downstreamIface $fromMac -j DROP"
            }

            is Route.Bound -> {
                // IPv6 無法保證走指定介面，一律擋下以免繞過
                forward6 += "-A $CHAIN_FORWARD -i $downstreamIface $fromMac -j DROP"
                dropDnsToHost(input6, fromMac)

                val egress = route.iface?.let { requireMatch(IFACE_PATTERN, it) }
                if (ip == null || egress == null) {
                    // 還不知道 IP 或出口不存在：先整台擋下，等下一次套用
                    forward += "-A $CHAIN_FORWARD -i $downstreamIface $fromMac -j DROP"
                    dropDnsToHost(input, fromMac)
                    if (ip != null) ipRules += unreachableRule(ip, downstreamIface)
                } else {
                    val dns = requireMatch(IPV4_PATTERN, route.dns)
                    // 裝置自行改 IP，或別的裝置冒用這個 IP，都不放行
                    forward += "-A $CHAIN_FORWARD -i $downstreamIface $fromMac ! -s $ip -j DROP"
                    forward += "-A $CHAIN_FORWARD -i $downstreamIface -s $ip -m mac ! --mac-source $mac -j DROP"
                    forward += "-A $CHAIN_FORWARD -i $downstreamIface -s $ip -o $egress -j ACCEPT"
                    forward += "-A $CHAIN_FORWARD -i $egress -o $downstreamIface -d $ip " +
                        "-m state --state RELATED,ESTABLISHED -j ACCEPT"
                    forward += "-A $CHAIN_FORWARD -i $downstreamIface -s $ip -j DROP"
                    forward += "-A $CHAIN_FORWARD -o $downstreamIface -d $ip -j DROP"
                    // DNS 預設送往手機上的 dnsmasq，會從系統上游查詢而洩漏；改導向出口可達的伺服器
                    for (protocol in listOf("udp", "tcp")) {
                        prerouting += "-A $CHAIN_PREROUTING -i $downstreamIface -s $ip -p $protocol " +
                            "--dport 53 -j DNAT --to-destination $dns"
                    }
                    ipRules += "[ -r /sys/class/net/$egress/ifindex ] && ip rule add from $ip iif $downstreamIface " +
                        "lookup \$(($NETD_TABLE_OFFSET + \$(cat /sys/class/net/$egress/ifindex))) " +
                        "prio $RULE_PRIORITY_LOOKUP || rc=1"
                    ipRules += unreachableRule(ip, downstreamIface)
                    egressIfaces += egress
                }
            }
        }
    }

    if (strictDefault) {
        // 尚未被偵測到的裝置在下一次套用前不得上網
        forward += "-A $CHAIN_FORWARD -i $downstreamIface -j DROP"
        forward += "-A $CHAIN_FORWARD -o $downstreamIface -j DROP"
        dropDnsToHost(input, "")
        forward6 += "-A $CHAIN_FORWARD -i $downstreamIface -j DROP"
        dropDnsToHost(input6, "")
    }

    val postrouting = egressIfaces.map { "-A $CHAIN_POSTROUTING -s ${downstream.subnet} -o $it -j MASQUERADE" }
    // VPN 介面的 MTU 較小，不調整 MSS 會讓部分網站連線卡住
    val mangle = egressIfaces.flatMap { egress ->
        listOf("-o $egress", "-i $egress").map { direction ->
            "-A $CHAIN_MANGLE $direction -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu"
        }
    }

    return buildString {
        appendLine("rc=0")
        appendRestore(
            "iptables-restore",
            mapOf(
                "filter" to mapOf(CHAIN_FORWARD to forward, CHAIN_INPUT to input),
                "nat" to mapOf(CHAIN_PREROUTING to prerouting, CHAIN_POSTROUTING to postrouting),
                "mangle" to mapOf(CHAIN_MANGLE to mangle),
            ),
        )
        appendRestore(
            "ip6tables-restore",
            mapOf("filter" to mapOf(CHAIN_FORWARD to forward6, CHAIN_INPUT to input6)),
        )
        for (hook in IPV4_HOOKS) appendLine(ensureJump("iptables", hook))
        for (hook in IPV6_HOOKS) appendLine(ensureJump("ip6tables", hook))
        appendLine(DELETE_IP_RULES)
        ipRules.forEach(::appendLine)
        // 系統的 BPF offload 會讓已建立的連線繞過 netfilter，移除後所有封包才會經過上面的規則
        appendLine("tc filter del dev $downstreamIface ingress 2>/dev/null")
        appendLine("[ \$rc -eq 0 ]")
    }
}

/** 產生移除所有規則的腳本，讓轉送完全回到系統原本的行為。 */
fun routingTeardownScript(): String = buildString {
    for ((command, hooks) in listOf("iptables" to IPV4_HOOKS, "ip6tables" to IPV6_HOOKS)) {
        for (hook in hooks) {
            appendLine("$command -w -t ${hook.table} -D ${hook.parent} -j ${hook.chain} 2>/dev/null")
            appendLine("$command -w -t ${hook.table} -F ${hook.chain} 2>/dev/null")
            appendLine("$command -w -t ${hook.table} -X ${hook.chain} 2>/dev/null")
        }
    }
    appendLine(DELETE_IP_RULES)
    appendLine("true")
}

private val DELETE_IP_RULES = listOf(RULE_PRIORITY_LOOKUP, RULE_PRIORITY_UNREACHABLE)
    .joinToString("\n") { "while ip rule del prio $it 2>/dev/null; do :; done" }

private fun unreachableRule(ip: String, downstreamIface: String): String =
    "ip rule add from $ip iif $downstreamIface unreachable prio $RULE_PRIORITY_UNREACHABLE || rc=1"

private fun ensureJump(command: String, hook: Hook): String =
    "$command -w -t ${hook.table} -C ${hook.parent} -j ${hook.chain} 2>/dev/null || " +
        "$command -w -t ${hook.table} -I ${hook.parent} 1 -j ${hook.chain} || rc=1"

/** `--noflush` 搭配宣告鏈名，只會清空並重建這裡列出的鏈，不影響系統的其他規則。 */
private fun StringBuilder.appendRestore(command: String, tables: Map<String, Map<String, List<String>>>) {
    appendLine("$command -w --noflush <<'AH_RULES' || rc=1")
    for ((table, chains) in tables) {
        appendLine("*$table")
        chains.keys.forEach { appendLine(":$it - [0:0]") }
        chains.values.flatten().forEach(::appendLine)
        appendLine("COMMIT")
    }
    appendLine("AH_RULES")
}

/** 所有會進入腳本的外部值都必須通過格式檢查，避免被當成 shell 語法執行。 */
private fun requireMatch(pattern: Regex, value: String): String {
    require(pattern.matches(value)) { "Refusing to use unexpected value in routing script: $value" }
    return value
}
