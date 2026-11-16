package me.yeeeee.advancehotspot.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import me.yeeeee.advancehotspot.R
import me.yeeeee.advancehotspot.ui.devices.DevicesScreen
import me.yeeeee.advancehotspot.ui.environment.EnvironmentScreen
import me.yeeeee.advancehotspot.ui.hotspot.HotspotScreen

private enum class MainTab(@param:StringRes val label: Int) {
    HOTSPOT(R.string.tab_hotspot),
    DEVICES(R.string.tab_devices),
    ENVIRONMENT(R.string.tab_environment),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.HOTSPOT) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                MainTab.entries.forEach { tab ->
                    Tab(
                        selected = tab == selectedTab,
                        onClick = { selectedTab = tab },
                        text = { Text(stringResource(tab.label)) },
                    )
                }
            }
            when (selectedTab) {
                MainTab.HOTSPOT -> HotspotScreen()
                MainTab.DEVICES -> DevicesScreen()
                MainTab.ENVIRONMENT -> EnvironmentScreen()
            }
        }
    }
}
