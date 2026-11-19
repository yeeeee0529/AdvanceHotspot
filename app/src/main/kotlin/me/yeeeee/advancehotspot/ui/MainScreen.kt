package me.yeeeee.advancehotspot.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import me.yeeeee.advancehotspot.R
import me.yeeeee.advancehotspot.ui.components.DockDestination
import me.yeeeee.advancehotspot.ui.components.FloatingNavigationDock
import me.yeeeee.advancehotspot.ui.components.LocalDockInset
import me.yeeeee.advancehotspot.ui.components.floatingNavigationDockInset
import me.yeeeee.advancehotspot.ui.devices.DevicesScreen
import me.yeeeee.advancehotspot.ui.environment.EnvironmentScreen
import me.yeeeee.advancehotspot.ui.hotspot.HotspotScreen
import me.yeeeee.advancehotspot.ui.network.NetworkScreen

private enum class MainTab(@param:StringRes val label: Int, @param:DrawableRes val icon: Int) {
    HOTSPOT(R.string.tab_hotspot, R.drawable.ic_hotspot),
    DEVICES(R.string.tab_devices, R.drawable.ic_devices),
    NETWORK(R.string.tab_network, R.drawable.ic_network),
    ENVIRONMENT(R.string.tab_environment, R.drawable.ic_info),
}

@Composable
fun MainScreen() {
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.HOTSPOT) }
    val destinations = MainTab.entries.map { DockDestination(stringResource(it.label), it.icon) }

    // 導覽列浮在內容上方；各分頁的 ScreenScaffold 依 LocalDockInset 在底部讓出空間
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalDockInset provides floatingNavigationDockInset()) {
            when (selectedTab) {
                MainTab.HOTSPOT -> HotspotScreen()
                MainTab.DEVICES -> DevicesScreen()
                MainTab.NETWORK -> NetworkScreen()
                MainTab.ENVIRONMENT -> EnvironmentScreen()
            }
        }
        FloatingNavigationDock(
            destinations = destinations,
            selectedIndex = selectedTab.ordinal,
            onSelected = { selectedTab = MainTab.entries[it] },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
