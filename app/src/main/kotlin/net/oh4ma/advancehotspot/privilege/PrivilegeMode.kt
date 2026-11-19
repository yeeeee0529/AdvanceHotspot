package net.oh4ma.advancehotspot.privilege

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 取得特權的方式。 */
enum class PrivilegeMode {
    /** 經由 KernelSU 取得 root，所有功能可用。 */
    ROOT,

    /** 經由 Shizuku 以 shell 身分執行，只有熱點設定、開關與裝置管理可用。 */
    SHIZUKU,
}

/** 使用者選擇的特權模式；由 [net.oh4ma.advancehotspot.App] 在啟動時初始化。 */
object PrivilegeSettings {
    private const val PREFERENCES = "privilege"
    private const val KEY_MODE = "mode"

    private lateinit var preferences: SharedPreferences
    private val _mode = MutableStateFlow(PrivilegeMode.ROOT)
    val mode: StateFlow<PrivilegeMode> = _mode.asStateFlow()

    /** 路由、內網與流量需要 root 才能修改 netfilter、tc 與路由表。 */
    val rootFeaturesAvailable: Boolean get() = _mode.value == PrivilegeMode.ROOT

    fun init(context: Context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        _mode.value = preferences.getString(KEY_MODE, null)
            ?.let { saved -> PrivilegeMode.entries.firstOrNull { it.name == saved } }
            ?: PrivilegeMode.ROOT
    }

    fun set(mode: PrivilegeMode) {
        preferences.edit().putString(KEY_MODE, mode.name).apply()
        _mode.value = mode
    }
}
