package net.oh4ma.advancehotspot.system

import net.oh4ma.advancehotspot.privilege.PrivilegeMode
import net.oh4ma.advancehotspot.privilege.PrivilegeSettings
import net.oh4ma.advancehotspot.privilege.PrivilegedShell
import net.oh4ma.advancehotspot.privilege.ShizukuClient
import net.oh4ma.advancehotspot.privilege.ShizukuState
import net.oh4ma.advancehotspot.root.RootShell

/** 裝置環境快照：特權狀態、核心資訊與後續功能會用到的網路工具。 */
data class EnvironmentInfo(
    val mode: PrivilegeMode,
    /** 目前模式的特權是否可用。 */
    val privilegeReady: Boolean,
    /** 只在 Shizuku 模式下檢查；root 模式為 null。 */
    val shizukuState: ShizukuState?,
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
        val mode = PrivilegeSettings.mode.value
        // Shizuku 模式不碰 root，避免在沒有 root 的手機上等待授權逾時
        val shizukuState = if (mode == PrivilegeMode.SHIZUKU) ShizukuClient.state() else null
        val privilegeReady = when (mode) {
            PrivilegeMode.ROOT -> RootShell.isRootGranted()
            PrivilegeMode.SHIZUKU -> shizukuState == ShizukuState.READY
        }
        val kernel = PrivilegedShell.run("uname -r").stdout.firstOrNull()
        val selinux = PrivilegedShell.run("getenforce").stdout.firstOrNull()
        val toolLines = PrivilegedShell.run(
            REQUIRED_TOOLS.joinToString("; ") { "command -v $it >/dev/null 2>&1 && echo $it=1 || echo $it=0" },
        ).stdout
        val interfaces = parseBriefAddressOutput(PrivilegedShell.run("ip -br addr").stdout)

        return EnvironmentInfo(
            mode = mode,
            privilegeReady = privilegeReady,
            shizukuState = shizukuState,
            kernel = kernel,
            selinux = selinux,
            tools = REQUIRED_TOOLS.map { tool -> ToolStatus(tool, "$tool=1" in toolLines) },
            interfaces = interfaces,
        )
    }
}
