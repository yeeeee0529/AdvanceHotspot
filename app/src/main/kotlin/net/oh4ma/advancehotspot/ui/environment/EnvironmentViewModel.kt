package net.oh4ma.advancehotspot.ui.environment

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.oh4ma.advancehotspot.privilege.PrivilegeMode
import net.oh4ma.advancehotspot.privilege.PrivilegeSettings
import net.oh4ma.advancehotspot.privilege.ShizukuClient
import net.oh4ma.advancehotspot.routing.RoutingRuntime
import net.oh4ma.advancehotspot.system.EnvironmentInfo
import net.oh4ma.advancehotspot.system.EnvironmentProbe

data class EnvironmentUiState(
    val loading: Boolean = true,
    val info: EnvironmentInfo? = null,
)

class EnvironmentViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(EnvironmentUiState())
    val state: StateFlow<EnvironmentUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val info = EnvironmentProbe.probe()
            _state.update { EnvironmentUiState(loading = false, info = info) }
        }
    }

    fun setMode(mode: PrivilegeMode) {
        if (mode == PrivilegeSettings.mode.value) return
        PrivilegeSettings.set(mode)
        // 舊服務以原模式清除規則，新服務以新模式接手
        RoutingRuntime.restart(getApplication())
        refresh()
    }

    fun requestShizukuPermission() {
        // 服務的裝置監看會自行重試，授權後不需要重新啟動
        viewModelScope.launch {
            ShizukuClient.requestPermission()
            refresh()
        }
    }
}
