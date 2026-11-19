package net.oh4ma.advancehotspot.helper

import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * 由 Shizuku 啟動的 UserService，與 Shizuku 伺服器同一個身分執行。
 *
 * 功能與 [HelperMain] 的指令列相同，差別在於常駐並透過 binder 回應，不必每次重新啟動行程。
 * Shizuku 以 ADB 或無線偵錯啟動時，這個行程是 shell 身分（uid 2000），可以呼叫熱點的隱藏 API；
 * 以 root 啟動時則是 uid 0，系統會拒絕，所以每次呼叫前都先檢查。
 */
class HelperUserService : IHelperService.Stub() {
    private val bridge: SoftApBridge by lazy { SoftApBridge(HelperMain.createShellContext()) }
    private val watchLock = Any()
    private var cancelWatch: (() -> Unit)? = null

    override fun destroy() {
        exitProcess(0)
    }

    override fun execute(args: Array<String>): String {
        val response = if (Process.myUid() == SHELL_UID) {
            HelperMain.execute(bridge, args)
        } else {
            JSONObject().put("ok", false).put("error", wrongUidMessage())
        }
        return response.toString()
    }

    override fun watch(watcher: IClientWatcher) {
        check(Process.myUid() == SHELL_UID) { wrongUidMessage() }
        synchronized(watchLock) {
            stopWatchingLocked()
            cancelWatch = bridge.startWatching { event ->
                // app 行程已結束時 binder 會丟出例外，此時停止監看
                runCatching { watcher.onEvent(event.toString()) }.onFailure { stopWatching() }
            }
        }
    }

    override fun stopWatching() {
        synchronized(watchLock) { stopWatchingLocked() }
    }

    private fun stopWatchingLocked() {
        cancelWatch?.let { cancel -> runCatching { cancel() } }
        cancelWatch = null
    }

    override fun shell(command: String): String {
        val process = ProcessBuilder("sh", "-c", command).start()
        process.outputStream.close()
        // stderr 另開執行緒讀取，避免兩條管線其中一條塞滿造成死結
        var stderr = emptyList<String>()
        val stderrReader = Thread { stderr = process.errorStream.bufferedReader().readLines() }.apply { start() }
        val stdout = process.inputStream.bufferedReader().readLines()
        stderrReader.join()
        val exitCode = if (process.waitFor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.exitValue()
        } else {
            process.destroyForcibly()
            -1
        }
        return JSONObject()
            .put("exitCode", exitCode)
            .put("stdout", JSONArray(stdout))
            .put("stderr", JSONArray(stderr))
            .toString()
    }

    private fun wrongUidMessage(): String =
        "Shizuku is running as uid ${Process.myUid()}; hotspot APIs need the shell user (uid $SHELL_UID). " +
            "Start Shizuku with ADB or wireless debugging instead of root."

    private companion object {
        const val SHELL_UID = 2000
        const val SHELL_TIMEOUT_SECONDS = 10L
    }
}
