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
import me.yeeeee.advancehotspot.routing.DevicePolicy
import me.yeeeee.advancehotspot.routing.Egress
import me.yeeeee.advancehotspot.routing.EgressResolver
import me.yeeeee.advancehotspot.routing.ResolvedEgress
import me.yeeeee.advancehotspot.routing.RoutingRuntime
import me.yeeeee.advancehotspot.routing.RoutingStatus
import me.yeeeee.advancehotspot.routing.RoutingStore

data class DeviceItem(
    val mac: String,
    val ip: String?,
    val hostname: String?,
    val nickname: String?,
    val policy: DevicePolicy,
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
)

class DevicesViewModel(application: Application) : AndroidViewModel(application) {
    private val controller = HotspotController(application.applicationInfo.sourceDir)
    private val store = DeviceStore(application)
    private val routingStore = RoutingStore(application)
    private val resolver = EgressResolver(application)
    private val _state = MutableStateFlow(
        DevicesUiState(routingEnabled = routingStore.enabled, defaultEgress = routingStore.defaultEgress),
    )
    val state: StateFlow<DevicesUiState> = _state.asStateFlow()

    private var latest: ClientSnapshot? = null
    private var refreshJob: Job? = null

    init {
        // 行程被系統回收後服務不一定還在，開啟畫面時確保它在執行
        if (routingStore.enabled) RoutingRuntime.start(application)
        viewModelScope.launch {
            RoutingRuntime.status.collect { status -> _state.update { it.copy(routingStatus = status) } }
        }
    }

    /** 由畫面定期呼叫；前一次還沒結束時直接略過。 */
    fun refresh() {
        if (refreshJob?.isActive == true || _state.value.busy) return
        refreshJob = viewModelScope.launch {
            try {
                show(controller.clients())
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
                show(controller.clients())
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
        if (enabled) RoutingRuntime.start(getApplication()) else RoutingRuntime.stop(getApplication())
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
                    )
                },
                blocked = snapshot.blockedMacs.map { mac ->
                    DeviceItem(mac, null, store.lastHostname(mac), store.nickname(mac), policies[mac] ?: DevicePolicy())
                },
                offline = policies.filterKeys { it !in connectedMacs && it !in blocked }.map { (mac, policy) ->
                    DeviceItem(mac, null, store.lastHostname(mac), store.nickname(mac), policy)
                },
                resolvedEgress = resolver.resolve(snapshot.downstream?.iface),
            )
        }
    }
}
