package me.yeeeee.advancehotspot.system

import me.yeeeee.advancehotspot.root.RootShell

/** 裝置環境快照：root 狀態、核心資訊與後續功能會用到的網路工具。 */
data class EnvironmentInfo(
    val rootGranted: Boolean,
    val kernel: String?,
    val selinux: String?,
    val tools: List<ToolStatus>,
    val interfaces: List<NetworkInterfaceInfo>,
)

data class ToolStatus(val name: String, val available: Boolean)

object EnvironmentProbe {
    /** 路由、防火牆與限速功能依賴的指令。 */
    private val REQUIRED_TOOLS = listOf("ip", "iptables", "ip6tables", "tc", "ndc")

    suspend fun probe(): EnvironmentInfo {
        val rootGranted = RootShell.isRootGranted()
        val kernel = RootShell.run("uname -r").stdout.firstOrNull()
        val selinux = RootShell.run("getenforce").stdout.firstOrNull()
        val toolLines = RootShell.run(
            REQUIRED_TOOLS.joinToString("; ") { "command -v $it >/dev/null 2>&1 && echo $it=1 || echo $it=0" },
        ).stdout
        val interfaces = parseBriefAddressOutput(RootShell.run("ip -br addr").stdout)

        return EnvironmentInfo(
            rootGranted = rootGranted,
            kernel = kernel,
            selinux = selinux,
            tools = REQUIRED_TOOLS.map { tool -> ToolStatus(tool, "$tool=1" in toolLines) },
            interfaces = interfaces,
        )
    }
}
