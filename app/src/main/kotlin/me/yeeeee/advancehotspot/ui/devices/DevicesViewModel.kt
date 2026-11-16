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

data class DeviceItem(
    val mac: String,
    val ip: String?,
    val hostname: String?,
    val nickname: String?,
)

data class DevicesUiState(
    val loaded: Boolean = false,
    val connected: List<DeviceItem> = emptyList(),
    val blocked: List<DeviceItem> = emptyList(),
    val busy: Boolean = false,
    /** 來自 helper 的診斷訊息，原樣顯示。 */
    val failure: String? = null,
)

class DevicesViewModel(application: Application) : AndroidViewModel(application) {
    private val controller = HotspotController(application.applicationInfo.sourceDir)
    private val store = DeviceStore(application)
    private val _state = MutableStateFlow(DevicesUiState())
    val state: StateFlow<DevicesUiState> = _state.asStateFlow()

    private var latest: ClientSnapshot? = null
    private var refreshJob: Job? = null

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

    private fun show(snapshot: ClientSnapshot) {
        latest = snapshot
        snapshot.clients.forEach { client ->
            client.hostname?.let { store.rememberHostname(client.mac, it) }
        }
        val blocked = snapshot.blockedMacs.toSet()
        _state.update { previous ->
            previous.copy(
                loaded = true,
                // 被封鎖的裝置在被踢除前可能還短暫留在連線清單，只顯示在封鎖區
                connected = snapshot.clients.filter { it.mac !in blocked }.map { client ->
                    DeviceItem(client.mac, client.ip, client.hostname, store.nickname(client.mac))
                },
                blocked = snapshot.blockedMacs.map { mac ->
                    DeviceItem(mac, null, store.lastHostname(mac), store.nickname(mac))
                },
            )
        }
    }
}
