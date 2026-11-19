@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package me.yeeeee.advancehotspot.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.yeeeee.advancehotspot.R
import me.yeeeee.advancehotspot.routing.Egress
import me.yeeeee.advancehotspot.traffic.TrafficStats
import me.yeeeee.advancehotspot.traffic.formatBytes
import me.yeeeee.advancehotspot.traffic.formatRate
import me.yeeeee.advancehotspot.ui.components.ChoiceFlow
import me.yeeeee.advancehotspot.ui.components.DeviceAvatar
import me.yeeeee.advancehotspot.ui.components.ErrorBanner
import me.yeeeee.advancehotspot.ui.components.HintText
import me.yeeeee.advancehotspot.ui.components.InfoItem
import me.yeeeee.advancehotspot.ui.components.LoadingBox
import me.yeeeee.advancehotspot.ui.components.MorphingBadge
import me.yeeeee.advancehotspot.ui.components.ScreenScaffold
import me.yeeeee.advancehotspot.ui.components.SectionHeader
import me.yeeeee.advancehotspot.ui.components.SegmentSurface
import me.yeeeee.advancehotspot.ui.components.SegmentedGroup
import me.yeeeee.advancehotspot.ui.components.SwitchItem

/** 裝置在清單中的分類，決定詳細資料面板提供哪些操作。 */
private enum class DeviceGroup { CONNECTED, OFFLINE, BLOCKED }

@Composable
fun DevicesScreen(modifier: Modifier = Modifier, viewModel: DevicesViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var detailMac by rememberSaveable { mutableStateOf<String?>(null) }
    var renamingMac by rememberSaveable { mutableStateOf<String?>(null) }
    var staticIpMac by rememberSaveable { mutableStateOf<String?>(null) }
    var limitMac by rememberSaveable { mutableStateOf<String?>(null) }
    DevicesPolling(viewModel)

    ScreenScaffold(
        title = stringResource(R.string.tab_devices),
        subtitle = if (state.loaded) stringResource(R.string.devices_subtitle, state.connected.size) else null,
        modifier = modifier,
    ) {
        if (!state.loaded) {
            LoadingBox(Modifier.padding(top = 120.dp))
            return@ScreenScaffold
        }
        state.failure?.let { ErrorBanner(stringResource(R.string.hotspot_operation_failed, it)) }

        SectionHeader(stringResource(R.string.devices_connected_title, state.connected.size))
        if (state.connected.isEmpty()) {
            EmptyDevices()
        } else {
            DeviceGroupList(state, state.connected, DeviceGroup.CONNECTED) { detailMac = it }
        }
        if (state.routingEnabled && state.offline.isNotEmpty()) {
            SectionHeader(stringResource(R.string.devices_offline_title, state.offline.size))
            DeviceGroupList(state, state.offline, DeviceGroup.OFFLINE) { detailMac = it }
        }
        if (state.blocked.isNotEmpty()) {
            SectionHeader(stringResource(R.string.devices_blocked_title, state.blocked.size))
            DeviceGroupList(state, state.blocked, DeviceGroup.BLOCKED) { detailMac = it }
        }
    }

    detailMac?.let { mac ->
        val found = findDevice(state, mac)
        if (found == null) {
            // 裝置已從所有清單中消失（例如解除封鎖後尚未重新連線），關閉面板
            LaunchedEffect(mac) { detailMac = null }
        } else {
            DeviceSheet(
                state = state,
                device = found.first,
                group = found.second,
                onDismiss = { detailMac = null },
                onRename = { renamingMac = mac },
                onStaticIp = { staticIpMac = mac },
                onLimit = { limitMac = mac },
                onToggleBlock = { blocked -> viewModel.setBlocked(mac, blocked) },
                onDisconnect = { viewModel.disconnect(mac) },
                onPolicy = { viewModel.setPolicy(mac, it) },
            )
        }
    }

    limitMac?.let { mac ->
        val device = state.connected.firstOrNull { it.mac == mac }
        LimitDialog(
            initial = device?.limit ?: me.yeeeee.advancehotspot.traffic.TrafficLimit(),
            onConfirm = { viewModel.setLimit(mac, it) },
            onDismiss = { limitMac = null },
        )
    }

    staticIpMac?.let { mac ->
        val device = findDevice(state, mac)?.first
        TextInputDialog(
            title = stringResource(R.string.static_ip_title),
            label = stringResource(R.string.static_ip_label),
            hint = stringResource(R.string.static_ip_hint, state.gateway.substringBeforeLast('.')),
            // 不預填目前的 IP：它可能還是舊網段的位址，直接儲存會被判定為不合法
            initial = device?.staticIp.orEmpty(),
            errorMessage = stringResource(R.string.static_ip_error),
            onConfirm = { viewModel.setStaticIp(mac, it) },
            onDismiss = { staticIpMac = null },
            keyboardType = KeyboardType.Decimal,
        )
    }

    renamingMac?.let { mac ->
        val device = findDevice(state, mac)?.first
        TextInputDialog(
            title = stringResource(R.string.rename_title),
            label = stringResource(R.string.rename_label),
            hint = stringResource(R.string.rename_hint),
            initial = device?.nickname.orEmpty(),
            errorMessage = null,
            onConfirm = { nickname ->
                viewModel.rename(mac, nickname)
                true
            },
            onDismiss = { renamingMac = null },
        )
    }
}

private fun findDevice(state: DevicesUiState, mac: String): Pair<DeviceItem, DeviceGroup>? =
    state.connected.firstOrNull { it.mac == mac }?.let { it to DeviceGroup.CONNECTED }
        ?: state.blocked.firstOrNull { it.mac == mac }?.let { it to DeviceGroup.BLOCKED }
        ?: state.offline.firstOrNull { it.mac == mac }?.let { it to DeviceGroup.OFFLINE }

@Composable
private fun DeviceItem.displayName(): String = nickname ?: hostname ?: stringResource(R.string.device_unknown_name)

@Composable
private fun EmptyDevices() {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(vertical = 32.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MorphingBadge(active = false, size = 72.dp, inactiveShape = MaterialShapes.Cookie6Sided) {
                Icon(
                    painterResource(R.drawable.ic_devices),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp),
                )
            }
            Text(
                stringResource(R.string.devices_connected_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeviceGroupList(
    state: DevicesUiState,
    devices: List<DeviceItem>,
    group: DeviceGroup,
    onOpen: (String) -> Unit,
) {
    SegmentedGroup {
        devices.forEach { device ->
            item(device.mac) { shapes ->
                val traffic = if (state.trafficEnabled && group == DeviceGroup.CONNECTED) state.traffic[device.mac] else null
                SegmentedListItem(
                    onClick = { onOpen(device.mac) },
                    shapes = shapes,
                    leadingContent = {
                        DeviceAvatar(device.mac, device.displayName(), dimmed = group != DeviceGroup.CONNECTED)
                    },
                    supportingContent = {
                        Column {
                            Text(
                                listOfNotNull(device.ip ?: device.mac, device.localName).joinToString("  ·  "),
                                fontFamily = FontFamily.Monospace,
                            )
                            deviceBadges(state, device, group).takeIf { it.isNotEmpty() }?.let { badges ->
                                Text(
                                    badges.joinToString("  ·  "),
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    },
                    trailingContent = traffic?.let { { RateColumn(it) } },
                ) {
                    Text(device.displayName())
                }
            }
        }
    }
}

/** 清單項目上的簡短狀態標籤，讓使用者不必展開也能看出特殊設定。 */
@Composable
private fun deviceBadges(state: DevicesUiState, device: DeviceItem, group: DeviceGroup): List<String> {
    if (group == DeviceGroup.BLOCKED) return emptyList()
    val badges = mutableListOf<String>()
    if (state.routingEnabled) {
        if (!device.policy.internet) {
            badges += stringResource(R.string.device_internet_blocked)
        } else if (device.policy.egress != Egress.INHERIT) {
            badges += stringResource(device.policy.egress.labelRes())
        }
    }
    if (state.intranetEnabled && device.staticIp != null) badges += stringResource(R.string.action_static_ip)
    if (state.trafficEnabled && !device.limit.isUnlimited) badges += stringResource(R.string.device_limited)
    return badges
}

@Composable
private fun RateColumn(stats: TrafficStats) {
    Column(horizontalAlignment = Alignment.End) {
        Text("↓ " + formatRate(stats.downRate), style = MaterialTheme.typography.labelMedium)
        Text(
            "↑ " + formatRate(stats.upRate),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DeviceSheet(
    state: DevicesUiState,
    device: DeviceItem,
    group: DeviceGroup,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onStaticIp: () -> Unit,
    onLimit: () -> Unit,
    onToggleBlock: (Boolean) -> Unit,
    onDisconnect: () -> Unit,
    onPolicy: (me.yeeeee.advancehotspot.routing.DevicePolicy) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                DeviceAvatar(device.mac, device.displayName(), size = 56.dp, dimmed = group != DeviceGroup.CONNECTED)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(device.displayName(), style = MaterialTheme.typography.headlineSmallEmphasized)
                    if (device.nickname != null && device.hostname != null) {
                        Text(
                            device.hostname,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (state.trafficEnabled && group == DeviceGroup.CONNECTED) {
                val stats = state.traffic[device.mac] ?: TrafficStats()
                Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(
                        label = stringResource(R.string.traffic_download),
                        rate = formatRate(stats.downRate),
                        total = formatBytes(stats.downBytes),
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = stringResource(R.string.traffic_upload),
                        rate = formatRate(stats.upRate),
                        total = formatBytes(stats.upBytes),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (!device.limit.isUnlimited) {
                    HintText(
                        stringResource(
                            R.string.traffic_limit_summary,
                            limitLabel(device.limit.upKbit),
                            limitLabel(device.limit.downKbit),
                        ),
                        Modifier.padding(start = 8.dp, top = 8.dp),
                    )
                }
            }

            if (state.routingEnabled && group != DeviceGroup.BLOCKED) {
                SectionHeader(stringResource(R.string.routing_title))
                SegmentedGroup {
                    item("internet") { shapes ->
                        SwitchItem(
                            shapes = shapes,
                            title = stringResource(R.string.device_internet),
                            checked = device.policy.internet,
                            onCheckedChange = { onPolicy(device.policy.copy(internet = it)) },
                            icon = R.drawable.ic_public,
                        )
                    }
                    if (device.policy.internet) {
                        item("egress") { shapes ->
                            SegmentSurface(shapes) {
                                Text(stringResource(R.string.device_egress), style = MaterialTheme.typography.labelLarge)
                                ChoiceFlow(
                                    options = DEVICE_EGRESS_CHOICES,
                                    selected = device.policy.egress,
                                    label = { stringResource(it.labelRes()) },
                                    onSelect = { onPolicy(device.policy.copy(egress = it)) },
                                )
                                val effective = if (device.policy.egress == Egress.INHERIT) {
                                    state.defaultEgress
                                } else {
                                    device.policy.egress
                                }
                                EgressStatus(effective, state.resolvedEgress)
                            }
                        }
                    }
                }
            }

            SectionHeader(stringResource(R.string.device_details))
            SegmentedGroup {
                device.ip?.let { ip ->
                    item("ip") { shapes -> InfoItem(shapes, stringResource(R.string.device_ip), ip, monospace = true) }
                }
                item("mac") { shapes -> InfoItem(shapes, stringResource(R.string.device_mac), device.mac, monospace = true) }
                device.localName?.let { name ->
                    item("local") { shapes ->
                        InfoItem(shapes, stringResource(R.string.device_local_name), name, monospace = true)
                    }
                }
                device.staticIp?.let { ip ->
                    item("static") { shapes ->
                        InfoItem(shapes, stringResource(R.string.action_static_ip), ip, monospace = true)
                    }
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionButton(R.drawable.ic_edit, stringResource(R.string.action_rename), onRename)
                if (state.intranetEnabled) {
                    ActionButton(R.drawable.ic_pin, stringResource(R.string.action_static_ip), onStaticIp)
                }
                if (state.trafficEnabled && group == DeviceGroup.CONNECTED) {
                    ActionButton(R.drawable.ic_speed, stringResource(R.string.action_limit), onLimit)
                }
                if (group == DeviceGroup.CONNECTED) {
                    FilledTonalButton(onClick = onDisconnect, enabled = !state.busy, shapes = ButtonDefaults.shapes()) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_disconnect))
                    }
                }
                val blocked = group == DeviceGroup.BLOCKED
                Button(
                    onClick = { onToggleBlock(!blocked) },
                    enabled = !state.busy,
                    shapes = ButtonDefaults.shapes(),
                    colors = if (blocked) {
                        ButtonDefaults.buttonColors()
                    } else {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        )
                    },
                ) {
                    Icon(painterResource(R.drawable.ic_block), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (blocked) R.string.action_unblock else R.string.action_block))
                }
            }
        }
    }
}

@Composable
private fun ActionButton(icon: Int, label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, shapes = ButtonDefaults.shapes()) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun StatTile(label: String, rate: String, total: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(rate, style = MaterialTheme.typography.titleLargeEmphasized)
            Text(stringResource(R.string.traffic_total_value, total), style = MaterialTheme.typography.bodySmall)
        }
    }
}
