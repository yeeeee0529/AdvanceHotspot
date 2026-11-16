package me.yeeeee.advancehotspot.ui.hotspot

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.yeeeee.advancehotspot.hotspot.ApState
import me.yeeeee.advancehotspot.hotspot.ConfigError
import me.yeeeee.advancehotspot.hotspot.HotspotConfig
import me.yeeeee.advancehotspot.hotspot.HotspotController
import me.yeeeee.advancehotspot.hotspot.HotspotStatus
import me.yeeeee.advancehotspot.hotspot.validate

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

    init {
        refresh()
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
