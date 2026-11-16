package me.yeeeee.advancehotspot.routing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.yeeeee.advancehotspot.R
import me.yeeeee.advancehotspot.hotspot.ClientSnapshot
import me.yeeeee.advancehotspot.hotspot.HotspotController
import me.yeeeee.advancehotspot.root.RootShell

data class RoutingStatus(
    val running: Boolean = false,
    /** 規則目前是否已套用在某個熱點介面上。 */
    val downstream: Downstream? = null,
    /** 最近一次套用失敗的診斷訊息。 */
    val error: String? = null,
)

/** 介面與 [RoutingService] 之間的溝通管道。 */
object RoutingRuntime {
    val status = MutableStateFlow(RoutingStatus())

    private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    internal val applyRequests = requests.asSharedFlow()

    /** 策略有變動時呼叫，服務會重新套用規則。 */
    fun requestApply() {
        requests.tryEmit(Unit)
    }

    fun start(context: Context) {
        context.startForegroundService(Intent(context, RoutingService::class.java))
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, RoutingService::class.java))
    }
}

/**
 * 路由控制啟用期間常駐的前景服務。
 *
 * 裝置連線、IP 變動、VPN 重連都會讓既有規則過時，因此由這個服務監看並重新套用；
 * 即使服務被系統終止，已寫入核心的規則仍然存在，受限的裝置不會因此取得額外的連線能力。
 */
class RoutingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val trigger = Channel<Unit>(Channel.CONFLATED)
    private lateinit var store: RoutingStore
    private lateinit var resolver: EgressResolver
    private lateinit var controller: HotspotController
    private lateinit var connectivityManager: ConnectivityManager

    private var snapshot: ClientSnapshot? = null
    private var rulesApplied = false
    private var cellularHold: ConnectivityManager.NetworkCallback? = null

    /** 任何網路的出現、消失或屬性改變都可能影響出口對應。 */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = requestApply()
        override fun onLost(network: Network) = requestApply()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = requestApply()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = RoutingStore(this)
        resolver = EgressResolver(this)
        controller = HotspotController(applicationInfo.sourceDir)
        connectivityManager = getSystemService(ConnectivityManager::class.java)

        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        RoutingRuntime.status.value = RoutingStatus(running = true)

        // 預設的 request 會排除 VPN，這裡要連 VPN 的變動也收到
        val allNetworks = NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        connectivityManager.registerNetworkCallback(allNetworks, networkCallback)

        scope.launch { RoutingRuntime.applyRequests.collect { requestApply() } }
        scope.launch {
            for (unit in trigger) {
                // 網路切換時事件會連續出現，稍等一下合併成一次套用
                delay(DEBOUNCE_MS)
                apply()
            }
        }
        scope.launch(Dispatchers.IO) { watchClients() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        connectivityManager.unregisterNetworkCallback(networkCallback)
        releaseCellular()
        scope.cancel()
        RoutingRuntime.status.value = RoutingStatus()
        // 服務的 scope 已取消，收尾工作交給獨立的 scope 完成
        CoroutineScope(Dispatchers.IO).launch {
            RootShell.run("pkill -f '$WATCH_PROCESS_PATTERN'", routingTeardownScript())
        }
        super.onDestroy()
    }

    private fun requestApply() {
        trigger.trySend(Unit)
    }

    /** 在專用的 root shell 執行 helper 的監看模式；helper 結束就重新啟動。 */
    private suspend fun watchClients() {
        val output = object : CallbackList<String>() {
            override fun onAddElement(line: String) {
                snapshot = controller.parseWatchEvent(line) ?: return
                requestApply()
            }
        }
        while (scope.isActive) {
            // 監看指令會佔住整個 shell，不能和其他指令共用同一個
            val shell = Shell.Builder.create().build()
            shell.newJob().add(controller.watchCommand()).to(output).exec()
            runCatching { shell.close() }
            delay(WATCH_RESTART_DELAY_MS)
        }
    }

    private suspend fun apply() {
        val current = snapshot
        val downstream = current?.downstream
        if (downstream == null) {
            // 熱點未開啟：移除殘留規則
            if (rulesApplied) {
                RootShell.run(routingTeardownScript())
                rulesApplied = false
            }
            RoutingRuntime.status.value = RoutingStatus(running = true)
            return
        }

        val policies = store.policies()
        val defaultEgress = store.defaultEgress
        holdCellular(defaultEgress == Egress.CELLULAR || policies.values.any { it.egress == Egress.CELLULAR })

        val plan = buildRoutingPlan(
            downstream = downstream,
            clients = current.clients,
            policies = policies,
            defaultEgress = defaultEgress,
            resolved = resolver.resolve(downstream.iface),
        )
        val result = try {
            RootShell.run(plan.toScript())
        } catch (exception: IllegalArgumentException) {
            RoutingRuntime.status.value = RoutingStatus(running = true, error = exception.message)
            return
        }
        rulesApplied = true
        RoutingRuntime.status.value = RoutingStatus(
            running = true,
            downstream = downstream,
            error = if (result.isSuccess) null else result.stderr.takeLast(3).joinToString(" | ").ifEmpty { "exit ${result.exitCode}" },
        )
    }

    /**
     * 手機連著 Wi-Fi 時系統會關閉行動網路；有裝置指定走行動網路時，持有一個請求讓它保持連線。
     */
    private fun holdCellular(needed: Boolean) {
        if (!needed) {
            releaseCellular()
            return
        }
        if (cellularHold != null) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {}
        connectivityManager.requestNetwork(request, callback)
        cellularHold = callback
    }

    private fun releaseCellular() {
        cellularHold?.let(connectivityManager::unregisterNetworkCallback)
        cellularHold = null
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                getString(R.string.routing_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.routing_notification_title))
            .setContentText(getString(R.string.routing_notification_text))
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val NOTIFICATION_ID = 1
        const val NOTIFICATION_CHANNEL = "routing"
        const val DEBOUNCE_MS = 300L
        const val WATCH_RESTART_DELAY_MS = 3000L
        const val WATCH_PROCESS_PATTERN = "HelperMain watch"
    }
}

/** 開機後熱點可能由系統介面直接開啟，路由控制啟用時要先把服務帶起來。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && RoutingStore(context).enabled) {
            RoutingRuntime.start(context)
        }
    }
}
