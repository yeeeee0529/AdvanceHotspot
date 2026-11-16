@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package me.yeeeee.advancehotspot.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import me.yeeeee.advancehotspot.R
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

    Scaffold(
        bottomBar = {
            ShortNavigationBar {
                MainTab.entries.forEach { tab ->
                    ShortNavigationBarItem(
                        selected = tab == selectedTab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        // 只扣掉底部導覽列；頂部的狀態列留給各分頁自己的頂部列處理
        val bottom = PaddingValues(bottom = innerPadding.calculateBottomPadding())
        Box(Modifier.fillMaxSize().padding(bottom).consumeWindowInsets(bottom)) {
            when (selectedTab) {
                MainTab.HOTSPOT -> HotspotScreen()
                MainTab.DEVICES -> DevicesScreen()
                MainTab.NETWORK -> NetworkScreen()
                MainTab.ENVIRONMENT -> EnvironmentScreen()
            }
        }
    }
}
