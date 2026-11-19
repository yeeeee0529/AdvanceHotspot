package net.oh4ma.advancehotspot.privilege

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.oh4ma.advancehotspot.helper.HelperUserService
import net.oh4ma.advancehotspot.helper.IHelperService
import net.oh4ma.advancehotspot.root.CommandResult
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku

class ShizukuException(message: String) : Exception(message)

enum class ShizukuState {
    /** Shizuku 沒有安裝或伺服器沒有啟動。 */
    NOT_RUNNING,

    /** 伺服器在執行，但本 app 尚未取得授權。 */
    PERMISSION_REQUIRED,

    /** 伺服器以 root 啟動；熱點的隱藏 API 只接受 shell 身分，必須改用 ADB 或無線偵錯啟動。 */
    WRONG_UID,

    READY,
}

/** 管理與 Shizuku 的連線，以及在 Shizuku 端常駐的 [HelperUserService]。 */
object ShizukuClient {
    private const val SHELL_UID = 2000
    private const val BIND_TIMEOUT_MS = 10_000L

    private lateinit var appContext: Context
    private val bindLock = Mutex()
    private var service: IHelperService? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun state(): ShizukuState = when {
        !Shizuku.pingBinder() -> ShizukuState.NOT_RUNNING
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuState.PERMISSION_REQUIRED
        Shizuku.getUid() != SHELL_UID -> ShizukuState.WRONG_UID
        else -> ShizukuState.READY
    }

    /** 跳出 Shizuku 的授權對話框，回傳使用者是否同意。 */
    suspend fun requestPermission(): Boolean = withContext(Dispatchers.Main) {
        if (!Shizuku.pingBinder()) return@withContext false
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return@withContext true
        val result = CompletableDeferred<Boolean>()
        val listener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            result.complete(grantResult == PackageManager.PERMISSION_GRANTED)
        }
        Shizuku.addRequestPermissionResultListener(listener)
        try {
            Shizuku.requestPermission(0)
            result.await()
        } finally {
            Shizuku.removeRequestPermissionResultListener(listener)
        }
    }

    /** 取得已連線的 helper；尚未連線時綁定 UserService 並等待它啟動。 */
    suspend fun service(): IHelperService = bindLock.withLock {
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        service = null
        when (state()) {
            ShizukuState.NOT_RUNNING -> throw ShizukuException("Shizuku is not running")
            ShizukuState.PERMISSION_REQUIRED -> throw ShizukuException("Shizuku permission is not granted")
            ShizukuState.WRONG_UID -> throw ShizukuException(
                "Shizuku is running as uid ${Shizuku.getUid()}; start it with ADB or wireless debugging",
            )
            ShizukuState.READY -> Unit
        }
        val connected = CompletableDeferred<IHelperService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                connected.complete(IHelperService.Stub.asInterface(binder))
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
            }
        }
        withContext(Dispatchers.Main) { Shizuku.bindUserService(userServiceArgs(), connection) }
        val bound = withTimeoutOrNull(BIND_TIMEOUT_MS) { connected.await() }
            ?: throw ShizukuException("Timed out waiting for the Shizuku helper service")
        service = bound
        bound
    }

    /** 以 Shizuku 伺服器的身分執行 shell 指令。 */
    suspend fun shell(command: String): CommandResult {
        val helper = service()
        val json = JSONObject(withContext(Dispatchers.IO) { helper.shell(command) })
        return CommandResult(
            exitCode = json.getInt("exitCode"),
            stdout = json.getJSONArray("stdout").toStringList(),
            stderr = json.getJSONArray("stderr").toStringList(),
        )
    }

    private fun userServiceArgs(): Shizuku.UserServiceArgs {
        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val debuggable = appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return Shizuku.UserServiceArgs(ComponentName(appContext.packageName, HelperUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("helper")
            .debuggable(debuggable)
            .version(packageInfo.longVersionCode.toInt())
    }

    private fun JSONArray.toStringList(): List<String> = List(length()) { getString(it) }
}
