package net.oh4ma.advancehotspot.ui.hotspot

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.oh4ma.advancehotspot.system.PublicIpInfo
import net.oh4ma.advancehotspot.system.PublicIpLookup

data class PublicIpUiState(
    val info: PublicIpInfo? = null,
    val loading: Boolean = true,
    /** 查詢失敗時的診斷訊息，原樣顯示。 */
    val failure: String? = null,
)

/** 熱點頁的出口 IP 卡片：開啟時查一次，預設網路改變（例如開關 VPN、切換 Wi-Fi）時自動重查。 */
class PublicIpViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(PublicIpUiState())
    val state: StateFlow<PublicIpUiState> = _state.asStateFlow()
    private var lookupJob: Job? = null
    private val connectivity = application.getSystemService(ConnectivityManager::class.java)

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh(settleMs = NETWORK_SETTLE_MS)

        override fun onLost(network: Network) = refresh(settleMs = NETWORK_SETTLE_MS)
    }

    init {
        // 註冊時會立即回呼一次目前的預設網路，順便完成第一次查詢
        connectivity.registerDefaultNetworkCallback(networkCallback)
    }

    /** 重新查詢；網路剛切換時先等一下，避免查到切換前的出口。 */
    fun refresh(settleMs: Long = 0) {
        lookupJob?.cancel()
        lookupJob = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            if (settleMs > 0) delay(settleMs)
            try {
                val info = PublicIpLookup.lookup()
                _state.value = PublicIpUiState(info = info, loading = false)
            } catch (exception: Exception) {
                if (exception is kotlinx.coroutines.CancellationException) throw exception
                _state.update { it.copy(loading = false, failure = exception.message ?: exception.javaClass.simpleName) }
            }
        }
    }

    override fun onCleared() {
        connectivity.unregisterNetworkCallback(networkCallback)
    }
}

private const val NETWORK_SETTLE_MS = 1500L
