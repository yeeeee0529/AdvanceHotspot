package me.yeeeee.advancehotspot.ui.devices

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.yeeeee.advancehotspot.devices.DeviceStore
import me.yeeeee.advancehotspot.hotspot.ClientSnapshot
import me.yeeeee.advancehotspot.hotspot.HotspotController
import me.yeeeee.advancehotspot.intranet.IntranetStore
import me.yeeeee.advancehotspot.intranet.buildHostEntries
import me.yeeeee.advancehotspot.intranet.fqdnOf
import me.yeeeee.advancehotspot.intranet.isAssignableAddress
import me.yeeeee.advancehotspot.intranet.isValidGateway
import me.yeeeee.advancehotspot.routing.DevicePolicy
import me.yeeeee.advancehotspot.routing.Egress
import me.yeeeee.advancehotspot.routing.EgressResolver
import me.yeeeee.advancehotspot.routing.ResolvedEgress
import me.yeeeee.advancehotspot.routing.RoutingRuntime
import me.yeeeee.advancehotspot.routing.RoutingStatus
import me.yeeeee.advancehotspot.routing.RoutingStore
import me.yeeeee.advancehotspot.traffic.TrafficLimit
import me.yeeeee.advancehotspot.traffic.TrafficStats
import me.yeeeee.advancehotspot.traffic.TrafficStore

data class DeviceItem(
    val mac: String,
    val ip: String?,
    val hostname: String?,
    val nickname: String?,
    val policy: DevicePolicy,
    /** 這台裝置在內網中的完整名稱，例如 `nas.lan`。 */
    val localName: String? = null,
    val staticIp: String? = null,
    val limit: TrafficLimit = TrafficLimit(),
)

data class DevicesUiState(
    val loaded: Boolean = false,
    val connected: List<DeviceItem> = emptyList(),
    val blocked: List<DeviceItem> = emptyList(),
    /** 目前不在線上、但存有路由策略的裝置。 */
    val offline: List<DeviceItem> = emptyList(),
    val busy: Boolean = false,
    /** 來自 helper 的診斷訊息，原樣顯示。 */
    val failure: String? = null,
    val routingEnabled: Boolean = false,
    val defaultEgress: Egress = Egress.SYSTEM,
    /** 各出口類型目前對應的介面；不在其中代表未連線。 */
    val resolvedEgress: Map<Egress, ResolvedEgress> = emptyMap(),
    val routingStatus: RoutingStatus = RoutingStatus(),
    val intranetEnabled: Boolean = false,
    val gateway: String = "",
    val trafficEnabled: Boolean = false,
    /** 各裝置（MAC）的累計用量與即時速率。 */
    val traffic: Map<String, TrafficStats> = emptyMap(),
)

class DevicesViewModel(application: Application) : AndroidViewModel(application) {
    private val controller = HotspotController(application.applicationInfo.sourceDir)
    private val store = DeviceStore(application)
    private val routingStore = RoutingStore(application)
    private val intranetStore = IntranetStore(application)
    private val trafficStore = TrafficStore(application)
    private val resolver = EgressResolver(application)
    private val _state = MutableStateFlow(
        DevicesUiState(
            routingEnabled = routingStore.enabled,
            defaultEgress = routingStore.defaultEgress,
            intranetEnabled = intranetStore.enabled,
            gateway = intranetStore.gateway,
            trafficEnabled = trafficStore.enabled,
        ),
    )
    val state: StateFlow<DevicesUiState> = _state.asStateFlow()

    private var latest: ClientSnapshot? = null
    private var refreshJob: Job? = null

    init {
        // 行程被系統回收後服務不一定還在，開啟畫面時確保它在執行
        RoutingRuntime.sync(application)
        viewModelScope.launch {
            RoutingRuntime.status.collect { status -> _state.update { it.copy(routingStatus = status) } }
        }
        viewModelScope.launch {
            RoutingRuntime.traffic.collect { traffic -> _state.update { it.copy(traffic = traffic) } }
        }
    }

    /** 由畫面定期呼叫；前一次還沒結束時直接略過。 */
    fun refresh() {
        if (refreshJob?.isActive == true || _state.value.busy) return
        refreshJob = viewModelScope.launch {
            try {
                show(intranetStore.mergeLeases(controller.clients()))
                _state.update { it.copy(failure = null) }
            } catch (exception: Exception) {
                _state.update { it.copy(loaded = true, failure = exception.message ?: exception.toString()) }
            }
        }
    }

    fun rename(mac: String, nickname: String) {
        store.setNickname(mac, nickname)
        latest?.let(::show)
    }

    fun setBlocked(mac: String, blocked: Boolean) {
        val current = latest ?: return
        val updated = if (blocked) current.blockedMacs + mac else current.blockedMacs - mac
        _state.update { it.copy(busy = true, failure = null) }
        viewModelScope.launch {
            try {
                refreshJob?.join()
                controller.setBlocked(updated.distinct())
                show(intranetStore.mergeLeases(controller.clients()))
            } catch (exception: Exception) {
                _state.update { it.copy(failure = exception.message ?: exception.toString()) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun setRoutingEnabled(enabled: Boolean) {
        routingStore.enabled = enabled
        _state.update { it.copy(routingEnabled = enabled) }
        RoutingRuntime.sync(getApplication())
    }

    fun setTrafficEnabled(enabled: Boolean) {
        trafficStore.enabled = enabled
        _state.update { it.copy(trafficEnabled = enabled) }
        RoutingRuntime.sync(getApplication())
    }

    fun resetTraffic() = RoutingRuntime.resetTraffic()

    fun setLimit(mac: String, limit: TrafficLimit) {
        trafficStore.setLimit(mac, limit)
        latest?.let(::show)
        RoutingRuntime.requestApply()
    }

    fun setIntranetEnabled(enabled: Boolean) {
        intranetStore.enabled = enabled
        _state.update { it.copy(intranetEnabled = enabled) }
        RoutingRuntime.sync(getApplication())
    }

    /** 回傳 false 代表位址不合法，設定沒有變更。 */
    fun setGateway(gateway: String): Boolean {
        if (!isValidGateway(gateway)) return false
        intranetStore.gateway = gateway
        _state.update { it.copy(gateway = gateway) }
        RoutingRuntime.requestApply()
        return true
    }

    /** 傳入空白代表取消固定 IP；回傳 false 代表位址不在內網網段內。 */
    fun setStaticIp(mac: String, ip: String): Boolean {
        val trimmed = ip.trim()
        if (trimmed.isNotEmpty() && !isAssignableAddress(intranetStore.gateway, trimmed)) return false
        intranetStore.setStaticLease(mac, trimmed.ifEmpty { null })
        latest?.let(::show)
        RoutingRuntime.requestApply()
        return true
    }

    fun setDefaultEgress(egress: Egress) {
        routingStore.defaultEgress = egress
        _state.update { it.copy(defaultEgress = egress) }
        RoutingRuntime.requestApply()
    }

    fun setPolicy(mac: String, policy: DevicePolicy) {
        routingStore.setPolicy(mac, policy)
        latest?.let(::show)
        RoutingRuntime.requestApply()
    }

    private fun show(snapshot: ClientSnapshot) {
        latest = snapshot
        snapshot.clients.forEach { client ->
            client.hostname?.let { store.rememberHostname(client.mac, it) }
        }
        val blocked = snapshot.blockedMacs.toSet()
        val policies = routingStore.policies()
        val connectedMacs = snapshot.clients.map { it.mac }.toSet()
        val staticLeases = intranetStore.staticLeases()
        val limits = trafficStore.limits()
        // 與服務產生名稱檔時使用相同的規則，畫面顯示的名稱才會與實際可解析的一致
        val hosts = if (intranetStore.enabled) {
            val gateway = intranetStore.gateway
            buildHostEntries(
                gateway,
                snapshot.clients.filter { it.ip != null && isAssignableAddress(gateway, it.ip) }.map { client ->
                    client.ip!! to listOf(store.nickname(client.mac), client.hostname)
                },
            )
        } else {
            emptyList()
        }
        _state.update { previous ->
            previous.copy(
                loaded = true,
                // 被封鎖的裝置在被踢除前可能還短暫留在連線清單，只顯示在封鎖區
                connected = snapshot.clients.filter { it.mac !in blocked }.map { client ->
                    DeviceItem(
                        mac = client.mac,
                        ip = client.ip,
                        hostname = client.hostname,
                        nickname = store.nickname(client.mac),
                        policy = policies[client.mac] ?: DevicePolicy(),
                        localName = hosts.fqdnOf(client.ip),
                        staticIp = staticLeases[client.mac],
                        limit = limits[client.mac] ?: TrafficLimit(),
                    )
                },
                blocked = snapshot.blockedMacs.map { mac ->
                    DeviceItem(mac, null, store.lastHostname(mac), store.nickname(mac), policies[mac] ?: DevicePolicy())
                },
                offline = (policies.keys + staticLeases.keys).distinct()
                    .filter { it !in connectedMacs && it !in blocked }
                    .map { mac ->
                        DeviceItem(
                            mac = mac,
                            ip = null,
                            hostname = store.lastHostname(mac),
                            nickname = store.nickname(mac),
                            policy = policies[mac] ?: DevicePolicy(),
                            staticIp = staticLeases[mac],
                        )
                    },
                resolvedEgress = resolver.resolve(snapshot.downstream?.iface),
            )
        }
    }
}
