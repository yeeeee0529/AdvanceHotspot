package me.yeeeee.advancehotspot.routing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import android.os.SystemClock
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
import me.yeeeee.advancehotspot.devices.DeviceStore
import me.yeeeee.advancehotspot.hotspot.ConnectedClient
import me.yeeeee.advancehotspot.intranet.IntranetConfig
import me.yeeeee.advancehotspot.intranet.IntranetStore
import me.yeeeee.advancehotspot.intranet.buildHostEntries
import me.yeeeee.advancehotspot.intranet.intranetTeardownScript
import me.yeeeee.advancehotspot.intranet.isAssignableAddress
import me.yeeeee.advancehotspot.intranet.subnetOf
import me.yeeeee.advancehotspot.intranet.toScript
import me.yeeeee.advancehotspot.MainActivity
import me.yeeeee.advancehotspot.hotspot.ApState
import me.yeeeee.advancehotspot.hotspot.ClientSnapshot
import me.yeeeee.advancehotspot.hotspot.HotspotController
import me.yeeeee.advancehotspot.root.CommandResult
import me.yeeeee.advancehotspot.root.RootShell
import me.yeeeee.advancehotspot.traffic.Counter
import me.yeeeee.advancehotspot.traffic.TRAFFIC_COUNTERS_COMMAND
import me.yeeeee.advancehotspot.traffic.TrafficClient
import me.yeeeee.advancehotspot.traffic.TrafficLimit
import me.yeeeee.advancehotspot.traffic.TrafficStats
import me.yeeeee.advancehotspot.traffic.TrafficStore
import me.yeeeee.advancehotspot.traffic.buildTrafficScript
import me.yeeeee.advancehotspot.traffic.counterDelta
import me.yeeeee.advancehotspot.traffic.parseCounters
import me.yeeeee.advancehotspot.traffic.trafficTeardownScript

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

    /** 各裝置（MAC）的累計用量與即時速率。 */
    val traffic = MutableStateFlow<Map<String, TrafficStats>>(emptyMap())

    private val resets = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    internal val trafficResets = resets.asSharedFlow()

    /** 清除所有裝置的累計用量。 */
    fun resetTraffic() {
        resets.tryEmit(Unit)
    }

    private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    internal val applyRequests = requests.asSharedFlow()

    /** 策略有變動時呼叫，服務會重新套用規則。 */
    fun requestApply() {
        requests.tryEmit(Unit)
    }

    /**
     * 依熱點狀態啟動或停止服務：熱點開啟中就常駐，讓通知顯示目前出口並提供關閉按鈕；
     * 路由控制、自訂內網、流量都未啟用時，服務只負責通知。
     *
     * 熱點關閉時停止服務，前景通知才會消失。服務停止後不會自行偵測熱點再次開啟，
     * 因此只有在 app 內開啟熱點，或之後開啟 app 時，服務才會回來。
     *
     * @param hotspotOn 呼叫端剛切換熱點時直接傳入結果；省略時讀取系統的網路共用狀態。
     */
    fun sync(context: Context, hotspotOn: Boolean = isTethering(context)) {
        val intent = Intent(context, RoutingService::class.java)
        if (hotspotOn) {
            context.startForegroundService(intent)
            requestApply()
        } else {
            context.stopService(intent)
        }
    }

    /** 從系統的 sticky 廣播讀取目前是否有作用中的網路共用介面。 */
    private fun isTethering(context: Context): Boolean {
        val sticky = context.registerReceiver(
            null,
            IntentFilter(ACTION_TETHER_STATE_CHANGED),
            Context.RECEIVER_NOT_EXPORTED,
        ) ?: return false
        return !sticky.getStringArrayListExtra(EXTRA_ACTIVE_TETHER).isNullOrEmpty()
    }

    // ConnectivityManager 中的隱藏常數
    private const val ACTION_TETHER_STATE_CHANGED = "android.net.conn.TETHER_STATE_CHANGED"
    private const val EXTRA_ACTIVE_TETHER = "tetherArray"
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
    private lateinit var intranetStore: IntranetStore
    private lateinit var deviceStore: DeviceStore
    private lateinit var trafficStore: TrafficStore
    private lateinit var resolver: EgressResolver
    private lateinit var controller: HotspotController
    private lateinit var connectivityManager: ConnectivityManager

    private var snapshot: ClientSnapshot? = null
    private var rulesApplied = false

    /** 內網目前套用在哪個介面與閘道上，用於之後移除。 */
    private var intranetApplied: Pair<String, String>? = null
    private var lastLeases: List<String> = emptyList()

    private var trafficApplied = false

    /** 通知上顯示的目前出口。 */
    private var egressLabel: String = ""

    /** 目前計數規則涵蓋的 IP 與其對應的 MAC。 */
    private var trafficIps: Map<String, String> = emptyMap()
    private var lastCounters: Map<String, Counter> = emptyMap()

    /** 各裝置（MAC）的累計用量。 */
    private val totals = mutableMapOf<String, Counter>()
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
        intranetStore = IntranetStore(this)
        deviceStore = DeviceStore(this)
        trafficStore = TrafficStore(this)
        totals.putAll(trafficStore.totals())
        publishTraffic(resetRates = true)
        resolver = EgressResolver(this)
        controller = HotspotController(applicationInfo.sourceDir)
        connectivityManager = getSystemService(ConnectivityManager::class.java)

        egressLabel = currentEgressLabel(null)
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
        scope.launch { watchLeases() }
        scope.launch { pollTraffic() }
        scope.launch {
            RoutingRuntime.trafficResets.collect {
                totals.clear()
                trafficStore.saveTotals(totals)
                publishTraffic(resetRates = true)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_HOTSPOT) stopHotspot()
        return START_STICKY
    }

    /** 通知上的按鈕：關閉熱點，確認關閉後結束服務並清除通知。 */
    private fun stopHotspot() {
        scope.launch {
            runCatching {
                controller.stop()
                controller.awaitApState(ApState.DISABLED)
            }
            RoutingRuntime.sync(this@RoutingService, hotspotOn = false)
        }
    }

    override fun onDestroy() {
        connectivityManager.unregisterNetworkCallback(networkCallback)
        releaseCellular()
        scope.cancel()
        RoutingRuntime.status.value = RoutingStatus()
        trafficStore.saveTotals(totals)
        // 服務的 scope 已取消，收尾工作交給獨立的 scope 完成
        CoroutineScope(Dispatchers.IO).launch {
            val (iface, gateway) = intranetApplied ?: (null to intranetStore.gateway)
            RootShell.run(
                "pkill -f '$WATCH_PROCESS_PATTERN'",
                routingTeardownScript(),
                trafficTeardownScript(),
                intranetTeardownScript(iface, gateway, intranetStore.directory),
            )
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

    /**
     * 自訂內網的租約由 dnsmasq 寫入檔案，沒有事件可以訂閱，只能定期檢查；
     * 裝置拿到新位址後，路由規則與本地名稱都要跟著更新。
     */
    private suspend fun watchLeases() {
        while (scope.isActive) {
            delay(LEASE_POLL_INTERVAL_MS)
            if (intranetApplied == null) continue
            val leases = RootShell.run("cat ${intranetStore.directory}/leases 2>/dev/null").stdout
            if (leases != lastLeases) requestApply()
        }
    }

    /** 建立、更新或移除自訂內網，回傳錯誤訊息；成功時回傳 null。 */
    private suspend fun applyIntranet(downstream: Downstream?, clients: List<ConnectedClient>): String? {
        val gateway = intranetStore.gateway
        val applied = intranetApplied
        val wanted = if (intranetStore.enabled && downstream != null) downstream.iface to gateway else null
        if (applied != null && applied != wanted) {
            // 介面消失時位址與路由已隨之移除，只需要清掉行程與規則
            val iface = applied.first.takeIf { it == downstream?.iface }
            RootShell.run(intranetTeardownScript(iface, applied.second, intranetStore.directory))
            intranetApplied = null
        }
        if (wanted == null || downstream == null) return null

        val config = IntranetConfig(
            gateway = gateway,
            staticLeases = intranetStore.staticLeases().filterValues { isAssignableAddress(gateway, it) },
            hosts = buildHostEntries(
                gateway,
                clients.filter { it.ip != null && isAssignableAddress(gateway, it.ip) }.map { client ->
                    client.ip!! to listOf(deviceStore.nickname(client.mac), client.hostname)
                },
            ),
            upstreamDns = resolver.defaultDnsServers().ifEmpty { listOf(FALLBACK_DNS) },
        )
        return try {
            val result = RootShell.run(config.toScript(downstream.iface, intranetStore.directory))
            intranetApplied = wanted
            if (result.isSuccess) null else result.stderr.takeLast(3).joinToString(" | ").ifEmpty { "intranet exit ${result.exitCode}" }
        } catch (exception: IllegalArgumentException) {
            exception.message
        }
    }

    private suspend fun apply() {
        val systemSnapshot = snapshot
        val downstream = systemSnapshot?.downstream
        if (downstream == null) {
            // 熱點未開啟：移除殘留規則
            applyIntranet(null, emptyList())
            applyRouting(null, emptyList())
            applyTraffic(null, emptyList())
            RoutingRuntime.status.value = RoutingStatus(running = true)
            refreshNotification(null)
            return
        }

        lastLeases = if (intranetStore.enabled) {
            RootShell.run("cat ${intranetStore.directory}/leases 2>/dev/null").stdout
        } else {
            emptyList()
        }
        val current = intranetStore.mergeLeases(systemSnapshot)
        val errors = listOfNotNull(
            applyIntranet(downstream, current.clients),
            applyRouting(downstream, current.clients),
            // 必須最後套用，它的鏈才會排在路由規則之前
            applyTraffic(downstream, current.clients),
        )
        RoutingRuntime.status.value = RoutingStatus(
            running = true,
            downstream = downstream,
            error = errors.firstOrNull(),
        )
        refreshNotification(downstream)
    }

    private fun refreshNotification(downstream: Downstream?) {
        val label = currentEgressLabel(downstream)
        if (label == egressLabel) return
        egressLabel = label
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    /**
     * 熱點流量目前實際走的網卡：路由控制啟用且預設出口綁定網路類型時取該出口，
     * 否則取手機目前預設網路的網卡。
     */
    private fun currentEgressLabel(downstream: Downstream?): String {
        val default = store.defaultEgress
        val iface = if (store.enabled && default != Egress.SYSTEM && default != Egress.INHERIT) {
            resolver.resolve(downstream?.iface)[default]?.iface
        } else {
            connectivityManager.activeNetwork
                ?.let(connectivityManager::getLinkProperties)?.interfaceName
        }
        return iface ?: getString(R.string.hotspot_notification_no_egress)
    }

    /** 套用或移除每裝置路由規則，回傳錯誤訊息；成功時回傳 null。 */
    private suspend fun applyRouting(downstream: Downstream?, clients: List<ConnectedClient>): String? {
        if (downstream == null || !store.enabled) {
            if (rulesApplied) {
                RootShell.run(routingTeardownScript())
                rulesApplied = false
            }
            releaseCellular()
            return null
        }

        val policies = store.policies()
        val defaultEgress = store.defaultEgress
        holdCellular(defaultEgress == Egress.CELLULAR || policies.values.any { it.egress == Egress.CELLULAR })

        val plan = buildRoutingPlan(
            downstream = downstream,
            clients = clients,
            policies = policies,
            defaultEgress = defaultEgress,
            resolved = resolver.resolve(downstream.iface),
            extraSubnets = listOfNotNull(intranetApplied?.let { subnetOf(it.second) }),
        )
        return try {
            val result = RootShell.run(plan.toScript())
            rulesApplied = true
            result.errorOrNull("routing")
        } catch (exception: IllegalArgumentException) {
            exception.message
        }
    }

    /** 套用或移除計數與限速規則，回傳錯誤訊息；成功時回傳 null。 */
    private suspend fun applyTraffic(downstream: Downstream?, clients: List<ConnectedClient>): String? {
        if (downstream == null || !trafficStore.enabled) {
            if (trafficApplied) {
                accumulate(RootShell.run(trafficTeardownScript()).stdout, elapsedMs = null)
                trafficApplied = false
                trafficIps = emptyMap()
                lastCounters = emptyMap()
                publishTraffic(resetRates = true)
                trafficStore.saveTotals(totals)
            }
            return null
        }

        val limits = trafficStore.limits()
        val withAddress = clients.filter { it.ip != null }
        return try {
            val script = buildTrafficScript(
                downstream.iface,
                withAddress.map { TrafficClient(it.ip!!, limits[it.mac] ?: TrafficLimit()) },
            )
            val result = RootShell.run(script)
            // 腳本開頭印出重建前的計數，先計入累計值；重建後計數從零開始
            accumulate(result.stdout, elapsedMs = null)
            trafficIps = withAddress.associate { it.ip!! to it.mac }
            lastCounters = emptyMap()
            trafficApplied = true
            result.errorOrNull("traffic")
        } catch (exception: IllegalArgumentException) {
            exception.message
        }
    }

    /** 定期讀取計數器，更新累計用量與即時速率。 */
    private suspend fun pollTraffic() {
        var lastPoll = SystemClock.elapsedRealtime()
        var lastSave = lastPoll
        while (scope.isActive) {
            delay(TRAFFIC_POLL_INTERVAL_MS)
            val now = SystemClock.elapsedRealtime()
            if (trafficApplied) {
                accumulate(RootShell.run(TRAFFIC_COUNTERS_COMMAND).stdout, elapsedMs = now - lastPoll)
                if (now - lastSave >= TRAFFIC_SAVE_INTERVAL_MS) {
                    trafficStore.saveTotals(totals)
                    lastSave = now
                }
            }
            lastPoll = now
        }
    }

    /**
     * 把一次計數器讀數與上一次的差額計入各裝置的累計值。
     *
     * @param elapsedMs 距離上一次讀數的時間；為 null 時只更新累計值，不計算速率。
     */
    private fun accumulate(lines: List<String>, elapsedMs: Long?) {
        val counters = parseCounters(lines)
        val rates = mutableMapOf<String, Counter>()
        for ((ip, counter) in counters) {
            val mac = trafficIps[ip] ?: continue
            val previous = lastCounters[ip] ?: Counter(0, 0)
            val up = counterDelta(previous.up, counter.up)
            val down = counterDelta(previous.down, counter.down)
            val total = totals[mac] ?: Counter(0, 0)
            totals[mac] = Counter(total.up + up, total.down + down)
            if (elapsedMs != null && elapsedMs > 0) {
                rates[mac] = Counter(up * 1000 / elapsedMs, down * 1000 / elapsedMs)
            }
        }
        lastCounters = counters
        if (elapsedMs != null) publishTraffic(resetRates = false, rates = rates)
    }

    private fun publishTraffic(resetRates: Boolean, rates: Map<String, Counter> = emptyMap()) {
        RoutingRuntime.traffic.value = totals.mapValues { (mac, total) ->
            val rate = if (resetRates) null else rates[mac]
            TrafficStats(total.up, total.down, rate?.up ?: 0, rate?.down ?: 0)
        }
    }

    private fun CommandResult.errorOrNull(label: String): String? =
        if (isSuccess) null else stderr.takeLast(3).joinToString(" | ").ifEmpty { "$label exit $exitCode" }

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
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, RoutingService::class.java).setAction(ACTION_STOP_HOTSPOT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.hotspot_notification_title))
            .setContentText(getString(R.string.hotspot_notification_egress, egressLabel))
            .setContentIntent(openApp)
            .addAction(
                Notification.Action.Builder(null, getString(R.string.hotspot_notification_stop), stop).build(),
            )
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val NOTIFICATION_ID = 1
        const val NOTIFICATION_CHANNEL = "routing"
        const val ACTION_STOP_HOTSPOT = "me.yeeeee.advancehotspot.action.STOP_HOTSPOT"
        const val DEBOUNCE_MS = 300L
        const val WATCH_RESTART_DELAY_MS = 3000L
        const val LEASE_POLL_INTERVAL_MS = 4000L
        const val TRAFFIC_POLL_INTERVAL_MS = 2000L
        const val TRAFFIC_SAVE_INTERVAL_MS = 60_000L
        const val WATCH_PROCESS_PATTERN = "HelperMain watch"
    }
}

/** 開機時熱點若已開啟（例如由系統還原），路由控制啟用時要先把服務帶起來。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) RoutingRuntime.sync(context)
    }
}
