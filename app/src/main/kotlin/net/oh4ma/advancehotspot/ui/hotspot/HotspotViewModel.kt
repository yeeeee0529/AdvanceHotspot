package net.oh4ma.advancehotspot.ui.hotspot

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.oh4ma.advancehotspot.hotspot.ApState
import net.oh4ma.advancehotspot.hotspot.ConfigError
import net.oh4ma.advancehotspot.hotspot.HotspotConfig
import net.oh4ma.advancehotspot.hotspot.HotspotController
import net.oh4ma.advancehotspot.hotspot.HotspotStatus
import net.oh4ma.advancehotspot.hotspot.validate
import net.oh4ma.advancehotspot.privilege.PrivilegeSettings
import net.oh4ma.advancehotspot.routing.RoutingRuntime

data class HotspotUiState(
    val status: HotspotStatus? = null,
    /** 使用者編輯中的設定，尚未寫入系統。 */
    val draft: HotspotConfig? = null,
    val busy: Boolean = true,
    val validationError: ConfigError? = null,
    /** 來自 helper 的診斷訊息，原樣顯示。 */
    val failure: String? = null,
) {
    val isDirty: Boolean get() = draft != null && draft != status?.config
}

class HotspotViewModel(application: Application) : AndroidViewModel(application) {
    private val controller = HotspotController(application.applicationInfo.sourceDir)
    private val _state = MutableStateFlow(HotspotUiState())
    val state: StateFlow<HotspotUiState> = _state.asStateFlow()
    private var pollJob: Job? = null

    init {
        refresh()
        // 切換特權模式後以新模式重新讀取，並清掉舊模式留下的錯誤
        viewModelScope.launch { PrivilegeSettings.mode.drop(1).collect { refresh() } }
    }

    /**
     * 由畫面定期呼叫的背景更新：不顯示忙碌狀態，也不覆蓋使用者尚未儲存的編輯；
     * 操作進行中時略過，失敗時保留目前畫面。
     */
    fun poll() {
        if (_state.value.busy || pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            val status = try {
                controller.status()
            } catch (exception: Exception) {
                return@launch
            }
            // 讀取期間若開始了開關或儲存，結果可能已過時
            if (!_state.value.busy) showStatus(status, keepDraft = true)
        }
    }

    fun refresh() = runBusy {
        showStatus(controller.status(), keepDraft = true)
    }

    fun edit(transform: (HotspotConfig) -> HotspotConfig) {
        _state.update { it.copy(draft = it.draft?.let(transform), validationError = null) }
    }

    fun discardChanges() {
        _state.update { it.copy(draft = it.status?.config, validationError = null) }
    }

    fun setEnabled(enabled: Boolean) = runBusy {
        if (enabled) {
            controller.start()
            showStatus(controller.awaitApState(ApState.ENABLED), keepDraft = true)
        } else {
            controller.stop()
            showStatus(controller.awaitApState(ApState.DISABLED), keepDraft = true)
        }
        // 熱點關閉時停止路由服務並清除其通知，開啟時再帶起來；
        // 剛切換完系統的網路共用狀態可能還沒更新，因此直接傳入結果
        RoutingRuntime.sync(getApplication(), hotspotOn = _state.value.status?.isEnabled == true)
    }

    /** 寫入設定；熱點開啟中時會重新啟動讓新設定生效。 */
    fun save() {
        val current = _state.value
        val draft = current.draft ?: return
        draft.validate(current.status?.capability)?.let { error ->
            _state.update { it.copy(validationError = error) }
            return
        }
        runBusy {
            val wasEnabled = controller.status().isEnabled
            controller.applyConfig(draft)
            if (wasEnabled) {
                controller.stop()
                controller.awaitApState(ApState.DISABLED)
                controller.start()
                showStatus(controller.awaitApState(ApState.ENABLED), keepDraft = false)
                RoutingRuntime.sync(getApplication(), hotspotOn = _state.value.status?.isEnabled == true)
            } else {
                showStatus(controller.status(), keepDraft = false)
            }
        }
    }

    private fun showStatus(status: HotspotStatus, keepDraft: Boolean) {
        _state.update { previous ->
            val draft = if (keepDraft && previous.isDirty) previous.draft else status.config
            previous.copy(status = status, draft = draft)
        }
    }

    private fun runBusy(block: suspend () -> Unit) {
        _state.update { it.copy(busy = true, failure = null, validationError = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (exception: Exception) {
                _state.update { it.copy(failure = exception.message ?: exception.toString()) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}
