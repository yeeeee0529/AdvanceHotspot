package me.yeeeee.advancehotspot.helper

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.os.Process
import android.util.Base64
import org.json.JSONObject
import java.lang.reflect.InvocationTargetException
import kotlin.system.exitProcess

/**
 * 以 `app_process` 啟動、用 shell 身分（uid 2000）執行的指令列進入點。
 *
 * 熱點相關的隱藏 API 會檢查呼叫端的套件名稱與 uid 是否相符，root（uid 0）沒有對應套件而會被拒絕；
 * `com.android.shell` 則同時擁有 `NETWORK_SETTINGS` 與 `TETHER_PRIVILEGED`，所以由 root 降權成 shell 來呼叫。
 * 這個行程不經過 zygote，因此不受隱藏 API 限制。
 *
 * 結果固定以一行 `RESPONSE_PREFIX + JSON` 輸出到 stdout。
 */
// 整個 helper 的目的就是呼叫隱藏 API，且只針對單一 Android 版本
@SuppressLint("PrivateApi")
object HelperMain {
    const val RESPONSE_PREFIX = "AHRESULT:"

    /** `watch` 指令每次狀態變動輸出一行，以此為前綴。 */
    const val EVENT_PREFIX = "AHEVENT:"
    private const val SHELL_PACKAGE = "com.android.shell"

    @JvmStatic
    fun main(args: Array<String>) {
        val response = try {
            // 一般 app 不該呼叫，但這個行程沒有框架代為建立主執行緒的 Looper
            @Suppress("DEPRECATION")
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
            val bridge = SoftApBridge(createShellContext())
            if (args.firstOrNull() == "watch") {
                bridge.watch { event ->
                    println(EVENT_PREFIX + event)
                    System.out.flush()
                }
            }
            val result = when (val command = args.firstOrNull()) {
                "status" -> bridge.status()
                "set-config" -> bridge.applyConfig(decodeJsonArgument(args))
                "clients" -> bridge.clients()
                "set-blocked" -> bridge.setBlocked(decodeJsonArgument(args))
                "start" -> bridge.start()
                "stop" -> bridge.stop()
                else -> throw IllegalArgumentException("Unknown command: $command")
            }
            result.put("ok", true)
        } catch (throwable: Throwable) {
            val cause = unwrap(throwable)
            JSONObject().put("ok", false).put("error", cause.toString())
        }
        println(RESPONSE_PREFIX + response)
        System.out.flush()
        exitProcess(if (response.optBoolean("ok")) 0 else 1)
    }

    private fun decodeJsonArgument(args: Array<String>): JSONObject {
        val encoded = args.getOrNull(1) ?: throw IllegalArgumentException("Missing base64 JSON argument")
        return JSONObject(String(Base64.decode(encoded, Base64.URL_SAFE), Charsets.UTF_8))
    }

    /**
     * 取得以 `com.android.shell` 身分對外呼叫的 Context。
     *
     * 由系統 Context 衍生出來的套件 Context 會沿用 `android` 作為 op package name，
     * 系統服務比對 uid 時會拒絕，因此另外包一層覆寫身分。
     */
    private fun createShellContext(): Context {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val activityThread = activityThreadClass.getMethod("systemMain").invoke(null)
        val systemContext = activityThreadClass.getMethod("getSystemContext").invoke(activityThread) as Context
        return ShellContext(systemContext.createPackageContext(SHELL_PACKAGE, 0))
    }

    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getOpPackageName(): String = SHELL_PACKAGE

        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build()
    }

    private tailrec fun unwrap(throwable: Throwable): Throwable {
        val cause = (throwable as? InvocationTargetException)?.targetException ?: return throwable
        return unwrap(cause)
    }
}
