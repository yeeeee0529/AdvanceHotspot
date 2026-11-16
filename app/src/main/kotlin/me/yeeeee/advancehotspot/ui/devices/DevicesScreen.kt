package me.yeeeee.advancehotspot.ui.devices

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import me.yeeeee.advancehotspot.R
import me.yeeeee.advancehotspot.routing.DevicePolicy
import me.yeeeee.advancehotspot.routing.Egress
import me.yeeeee.advancehotspot.routing.ResolvedEgress

private const val REFRESH_INTERVAL_MS = 4000L

@Composable
fun DevicesScreen(modifier: Modifier = Modifier, viewModel: DevicesViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var renamingMac by rememberSaveable { mutableStateOf<String?>(null) }
    var staticIpMac by rememberSaveable { mutableStateOf<String?>(null) }
    // 前景服務的通知需要這個權限才看得到；使用者拒絕時服務仍會執行
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // 只在畫面位於前景時輪詢，每次輪詢都要啟動一個 helper 行程
    var resumed by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        resumed = true
        onPauseOrDispose { resumed = false }
    }
    LaunchedEffect(resumed) {
        while (resumed) {
            viewModel.refresh()
            delay(REFRESH_INTERVAL_MS)
        }
    }

    if (!state.loaded) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.failure?.let { failure ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Text(
                        stringResource(R.string.hotspot_operation_failed, failure),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        item {
            RoutingCard(
                state = state,
                onToggle = { enabled ->
                    if (enabled) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    viewModel.setRoutingEnabled(enabled)
                },
                onDefaultEgress = viewModel::setDefaultEgress,
            )
        }
        item {
            IntranetCard(state, viewModel::setIntranetEnabled, viewModel::setGateway)
        }
        item {
            Text(
                stringResource(R.string.devices_connected_title, state.connected.size),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (state.connected.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.devices_connected_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.connected, key = { "connected-${it.mac}" }) { device ->
            DeviceCard(
                device = device,
                enabled = !state.busy,
                blockLabel = stringResource(R.string.action_block),
                onRename = { renamingMac = device.mac },
                onStaticIp = if (state.intranetEnabled) ({ staticIpMac = device.mac }) else null,
                onToggleBlock = { viewModel.setBlocked(device.mac, true) },
                routing = if (state.routingEnabled) {
                    DeviceRouting(state.defaultEgress, state.resolvedEgress) { viewModel.setPolicy(device.mac, it) }
                } else {
                    null
                },
            )
        }
        if (state.routingEnabled && state.offline.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.devices_offline_title, state.offline.size),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(state.offline, key = { "offline-${it.mac}" }) { device ->
                DeviceCard(
                    device = device,
                    enabled = !state.busy,
                    blockLabel = stringResource(R.string.action_block),
                    onRename = { renamingMac = device.mac },
                onStaticIp = if (state.intranetEnabled) ({ staticIpMac = device.mac }) else null,
                    onToggleBlock = { viewModel.setBlocked(device.mac, true) },
                    routing = DeviceRouting(state.defaultEgress, state.resolvedEgress) {
                        viewModel.setPolicy(device.mac, it)
                    },
                )
            }
        }
        if (state.blocked.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.devices_blocked_title, state.blocked.size),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(state.blocked, key = { "blocked-${it.mac}" }) { device ->
                DeviceCard(
                    device = device,
                    enabled = !state.busy,
                    blockLabel = stringResource(R.string.action_unblock),
                    onRename = { renamingMac = device.mac },
                onStaticIp = if (state.intranetEnabled) ({ staticIpMac = device.mac }) else null,
                    onToggleBlock = { viewModel.setBlocked(device.mac, false) },
                )
            }
        }
    }

    staticIpMac?.let { mac ->
        val device = (state.connected + state.offline + state.blocked).firstOrNull { it.mac == mac }
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
        val device = (state.connected + state.offline + state.blocked).firstOrNull { it.mac == mac }
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

@Composable
private fun DeviceCard(
    device: DeviceItem,
    enabled: Boolean,
    blockLabel: String,
    onRename: () -> Unit,
    onToggleBlock: () -> Unit,
    routing: DeviceRouting? = null,
    onStaticIp: (() -> Unit)? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp)) {
            Text(
                device.nickname ?: device.hostname ?: stringResource(R.string.device_unknown_name),
                style = MaterialTheme.typography.titleMedium,
            )
            if (device.nickname != null && device.hostname != null) {
                Text(
                    device.hostname,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                listOfNotNull(device.ip, device.mac).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            device.localName?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            device.staticIp?.let {
                Text(
                    stringResource(R.string.device_static_ip, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (routing != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                    Text(
                        stringResource(R.string.device_internet),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = device.policy.internet,
                        onCheckedChange = { routing.onPolicy(device.policy.copy(internet = it)) },
                    )
                }
                if (device.policy.internet) {
                    EgressChips(
                        choices = DEVICE_EGRESS_CHOICES,
                        selected = device.policy.egress,
                        onSelect = { routing.onPolicy(device.policy.copy(egress = it)) },
                    )
                    val effective = if (device.policy.egress == Egress.INHERIT) routing.defaultEgress else device.policy.egress
                    EgressStatus(effective, routing.resolved)
                }
            }
            Row(Modifier.align(Alignment.End)) {
                if (onStaticIp != null) {
                    TextButton(onClick = onStaticIp) { Text(stringResource(R.string.action_static_ip)) }
                }
                TextButton(onClick = onRename) { Text(stringResource(R.string.action_rename)) }
                TextButton(onClick = onToggleBlock, enabled = enabled) { Text(blockLabel) }
            }
        }
    }
}

/** 路由控制啟用時，裝置卡片額外需要的資料與回呼。 */
private class DeviceRouting(
    val defaultEgress: Egress,
    val resolved: Map<Egress, ResolvedEgress>,
    val onPolicy: (DevicePolicy) -> Unit,
)

private val DEFAULT_EGRESS_CHOICES = listOf(Egress.SYSTEM, Egress.WIFI, Egress.CELLULAR, Egress.VPN, Egress.ETHERNET)
private val DEVICE_EGRESS_CHOICES = listOf(Egress.INHERIT) + DEFAULT_EGRESS_CHOICES

@StringRes
private fun Egress.labelRes(): Int = when (this) {
    Egress.INHERIT -> R.string.egress_inherit
    Egress.SYSTEM -> R.string.egress_system
    Egress.WIFI -> R.string.egress_wifi
    Egress.CELLULAR -> R.string.egress_cellular
    Egress.VPN -> R.string.egress_vpn
    Egress.ETHERNET -> R.string.egress_ethernet
}

@Composable
private fun RoutingCard(state: DevicesUiState, onToggle: (Boolean) -> Unit, onDefaultEgress: (Egress) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.routing_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = state.routingEnabled, onCheckedChange = onToggle)
            }
            Text(
                stringResource(R.string.routing_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.routingEnabled) {
                Text(stringResource(R.string.routing_default_egress), style = MaterialTheme.typography.labelLarge)
                EgressChips(DEFAULT_EGRESS_CHOICES, state.defaultEgress, onDefaultEgress)
                EgressStatus(state.defaultEgress, state.resolvedEgress)

                val downstream = state.routingStatus.downstream
                Text(
                    if (downstream == null) {
                        stringResource(R.string.routing_inactive)
                    } else {
                        stringResource(R.string.routing_active, downstream.iface, downstream.subnet)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.routingStatus.error?.let {
                    Text(
                        stringResource(R.string.routing_error, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun EgressChips(choices: List<Egress>, selected: Egress, onSelect: (Egress) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        choices.forEach { egress ->
            FilterChip(
                selected = egress == selected,
                onClick = { onSelect(egress) },
                label = { Text(stringResource(egress.labelRes())) },
            )
        }
    }
}

/** 顯示指定出口目前對應的介面；跟隨系統時沒有可顯示的資訊。 */
@Composable
private fun EgressStatus(egress: Egress, resolved: Map<Egress, ResolvedEgress>) {
    if (egress == Egress.SYSTEM || egress == Egress.INHERIT) return
    val label = stringResource(egress.labelRes())
    val target = resolved[egress]
    Text(
        if (target != null) {
            stringResource(R.string.egress_status_online, label, target.iface)
        } else {
            stringResource(R.string.egress_status_offline, label)
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (target != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun IntranetCard(state: DevicesUiState, onToggle: (Boolean) -> Unit, onGateway: (String) -> Boolean) {
    var gatewayText by rememberSaveable(state.gateway) { mutableStateOf(state.gateway) }
    var invalid by rememberSaveable { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.intranet_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = state.intranetEnabled, onCheckedChange = onToggle)
            }
            Text(
                stringResource(R.string.intranet_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.intranetEnabled) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    TextButton(
                        onClick = { invalid = !onGateway(gatewayText.trim()) },
                        enabled = gatewayText.trim() != state.gateway,
                    ) {
                        Text(stringResource(R.string.action_apply))
                    }
                }
            }
        }
    }
}

/**
 * 單一文字欄位的輸入對話框。
 *
 * @param onConfirm 回傳 false 代表輸入不合法，對話框會留著並顯示 [errorMessage]。
 */
@Composable
private fun TextInputDialog(
    title: String,
    label: String,
    hint: String,
    initial: String,
    errorMessage: String?,
    onConfirm: (String) -> Boolean,
    onDismiss: () -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    invalid = false
                },
                label = { Text(label) },
                isError = invalid,
                supportingText = { Text(if (invalid && errorMessage != null) errorMessage else hint) },
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { if (onConfirm(text)) onDismiss() else invalid = true }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
