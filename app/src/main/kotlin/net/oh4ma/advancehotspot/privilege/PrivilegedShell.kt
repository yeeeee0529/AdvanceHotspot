package net.oh4ma.advancehotspot.privilege

import net.oh4ma.advancehotspot.root.CommandResult
import net.oh4ma.advancehotspot.root.RootShell

/**
 * 依目前的特權模式執行唯讀的系統指令（例如讀取介面位址、租約檔）。
 *
 * 會修改 netfilter、tc 或路由表的指令只能在 root 模式下執行，請直接使用 [RootShell]。
 */
object PrivilegedShell {
    suspend fun run(vararg commands: String): CommandResult = when (PrivilegeSettings.mode.value) {
        PrivilegeMode.ROOT -> RootShell.run(*commands)
        PrivilegeMode.SHIZUKU -> try {
            ShizukuClient.shell(commands.joinToString("\n"))
        } catch (exception: ShizukuException) {
            CommandResult(exitCode = -1, stdout = emptyList(), stderr = listOfNotNull(exception.message))
        }
    }
}
