package me.yeeeee.advancehotspot.ui.environment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.yeeeee.advancehotspot.system.EnvironmentInfo
import me.yeeeee.advancehotspot.system.EnvironmentProbe

data class EnvironmentUiState(
    val loading: Boolean = true,
    val info: EnvironmentInfo? = null,
)

class EnvironmentViewModel : ViewModel() {
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
}
