@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package net.oh4ma.advancehotspot.ui.network

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import net.oh4ma.advancehotspot.R
import net.oh4ma.advancehotspot.ui.components.ChoiceFlow
import net.oh4ma.advancehotspot.ui.components.HintText
import net.oh4ma.advancehotspot.ui.components.ScreenScaffold
import net.oh4ma.advancehotspot.ui.components.SectionHeader
import net.oh4ma.advancehotspot.ui.components.SegmentSurface
import net.oh4ma.advancehotspot.ui.components.SegmentedGroup
import net.oh4ma.advancehotspot.ui.components.SwitchItem
import net.oh4ma.advancehotspot.ui.devices.DEFAULT_EGRESS_CHOICES
import net.oh4ma.advancehotspot.ui.devices.DevicesPolling
import net.oh4ma.advancehotspot.ui.devices.DevicesUiState
import net.oh4ma.advancehotspot.ui.devices.DevicesViewModel
import net.oh4ma.advancehotspot.ui.devices.EgressStatus
import net.oh4ma.advancehotspot.ui.devices.labelRes

/** 「網路」分頁：路由控制、自訂內網與流量統計三個功能的總開關與全域設定。 */
@Composable
fun NetworkScreen(modifier: Modifier = Modifier, viewModel: DevicesViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 前景服務的通知需要這個權限才看得到；使用者拒絕時服務仍會執行
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    // 出口狀態來自裝置輪詢
    DevicesPolling(viewModel)

    ScreenScaffold(title = stringResource(R.string.tab_network), modifier = modifier) {
        if (!state.rootFeatures) {
            SegmentedGroup(Modifier.padding(top = 8.dp)) {
                item("shizuku") { shapes ->
                    SegmentSurface(shapes) { HintText(stringResource(R.string.network_requires_root)) }
                }
            }
        }
        RoutingSection(
            state = state,
            onToggle = { enabled ->
                if (enabled) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                viewModel.setRoutingEnabled(enabled)
            },
            onDefaultEgress = viewModel::setDefaultEgress,
        )
        IntranetSection(state, viewModel::setIntranetEnabled, viewModel::setGateway)
        TrafficSection(state, viewModel::setTrafficEnabled, viewModel::resetTraffic)
    }
}

@Composable
private fun RoutingSection(
    state: DevicesUiState,
    onToggle: (Boolean) -> Unit,
    onDefaultEgress: (net.oh4ma.advancehotspot.routing.Egress) -> Unit,
) {
    SectionHeader(stringResource(R.string.routing_title))
    SegmentedGroup {
        item("toggle") { shapes ->
            SwitchItem(
                shapes = shapes,
                title = stringResource(R.string.routing_switch),
                supporting = stringResource(R.string.routing_description),
                checked = state.routingEnabled,
                onCheckedChange = onToggle,
                enabled = state.rootFeatures,
                icon = R.drawable.ic_network,
            )
        }
        if (state.routingEnabled) {
            item("egress") { shapes ->
                SegmentSurface(shapes) {
                    Text(stringResource(R.string.routing_default_egress), style = MaterialTheme.typography.labelLarge)
                    ChoiceFlow(
                        options = DEFAULT_EGRESS_CHOICES,
                        selected = state.defaultEgress,
                        label = { stringResource(it.labelRes()) },
                        onSelect = onDefaultEgress,
                    )
                    EgressStatus(state.defaultEgress, state.resolvedEgress)
                }
            }
            item("status") { shapes ->
                SegmentSurface(shapes) {
                    val downstream = state.routingStatus.downstream
                    Text(
                        if (downstream == null) {
                            stringResource(R.string.routing_inactive)
                        } else {
                            stringResource(R.string.routing_active, downstream.iface, downstream.subnet)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    state.routingStatus.error?.let {
                        HintText(stringResource(R.string.routing_error, it), isError = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun IntranetSection(state: DevicesUiState, onToggle: (Boolean) -> Unit, onGateway: (String) -> Boolean) {
    var gatewayText by rememberSaveable(state.gateway) { mutableStateOf(state.gateway) }
    var invalid by rememberSaveable { mutableStateOf(false) }

    SectionHeader(stringResource(R.string.intranet_title))
    SegmentedGroup {
        item("toggle") { shapes ->
            SwitchItem(
                shapes = shapes,
                title = stringResource(R.string.intranet_switch),
                supporting = stringResource(R.string.intranet_description),
                checked = state.intranetEnabled,
                onCheckedChange = onToggle,
                enabled = state.rootFeatures,
                icon = R.drawable.ic_devices,
            )
        }
        if (state.intranetEnabled) {
            item("gateway") { shapes ->
                SegmentSurface(shapes) {
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = gatewayText,
                            onValueChange = {
                                gatewayText = it
                                invalid = false
                            },
                            label = { Text(stringResource(R.string.intranet_gateway)) },
                            isError = invalid,
                            supportingText = {
                                Text(
                                    stringResource(
                                        if (invalid) R.string.intranet_gateway_error else R.string.intranet_gateway_hint,
                                    ),
                                )
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalButton(
                            onClick = { invalid = !onGateway(gatewayText.trim()) },
                            enabled = gatewayText.trim() != state.gateway,
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text(stringResource(R.string.action_apply))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrafficSection(state: DevicesUiState, onToggle: (Boolean) -> Unit, onReset: () -> Unit) {
    SectionHeader(stringResource(R.string.traffic_title))
    SegmentedGroup {
        item("toggle") { shapes ->
            SwitchItem(
                shapes = shapes,
                title = stringResource(R.string.traffic_switch),
                supporting = stringResource(R.string.traffic_description),
                checked = state.trafficEnabled,
                onCheckedChange = onToggle,
                enabled = state.rootFeatures,
                icon = R.drawable.ic_speed,
            )
        }
        if (state.trafficEnabled) {
            item("summary") { shapes ->
                SegmentSurface(shapes) {
                    // 即時速率移到「熱點」分頁；這裡只保留重設
                    HintText(stringResource(R.string.traffic_speed_moved))
                    FilledTonalButton(onClick = onReset, shapes = ButtonDefaults.shapes()) {
                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_reset_traffic))
                    }
                }
            }
        }
    }
}
