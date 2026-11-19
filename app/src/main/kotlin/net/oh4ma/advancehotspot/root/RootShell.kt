package net.oh4ma.advancehotspot.root

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 單次 root 指令的執行結果。 */
data class CommandResult(
    val exitCode: Int,
    val stdout: List<String>,
    val stderr: List<String>,
) {
    val isSuccess: Boolean get() = exitCode == 0
}

/** 所有 root 指令的單一出入口，底層共用 libsu 的常駐 shell。 */
object RootShell {
    private const val SHELL_TIMEOUT_SECONDS = 10L

    /** 必須在第一次取得 shell 之前呼叫，由 [net.oh4ma.advancehotspot.App] 負責。 */
    fun configure() {
        // 指令輸出含熱點密碼，不可開啟 libsu 的 verbose logging，否則會寫進 logcat
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(Shell.Builder.create().setTimeout(SHELL_TIMEOUT_SECONDS))
    }

    /** 第一次呼叫會觸發 root 管理器的授權提示。 */
    suspend fun isRootGranted(): Boolean = withContext(Dispatchers.IO) {
        Shell.getShell().isRoot
    }

    /** 依序執行指令並回傳最後的結束碼與完整輸出。 */
    suspend fun run(vararg commands: String): CommandResult = withContext(Dispatchers.IO) {
        val stdout = ArrayList<String>()
        val stderr = ArrayList<String>()
        val result = Shell.cmd(*commands).to(stdout, stderr).exec()
        CommandResult(result.code, stdout, stderr)
    }
}
