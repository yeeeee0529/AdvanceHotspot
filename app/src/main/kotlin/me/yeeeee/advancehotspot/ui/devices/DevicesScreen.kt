package me.yeeeee.advancehotspot.ui.devices

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import me.yeeeee.advancehotspot.R

private const val REFRESH_INTERVAL_MS = 4000L

@Composable
fun DevicesScreen(modifier: Modifier = Modifier, viewModel: DevicesViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var renamingMac by rememberSaveable { mutableStateOf<String?>(null) }

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
                onToggleBlock = { viewModel.setBlocked(device.mac, true) },
            )
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
                    onToggleBlock = { viewModel.setBlocked(device.mac, false) },
                )
            }
        }
    }

    renamingMac?.let { mac ->
        val device = (state.connected + state.blocked).firstOrNull { it.mac == mac }
        RenameDialog(
            initial = device?.nickname.orEmpty(),
            onConfirm = { nickname ->
                viewModel.rename(mac, nickname)
                renamingMac = null
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
            Row(Modifier.align(Alignment.End)) {
                TextButton(onClick = onRename) { Text(stringResource(R.string.action_rename)) }
                TextButton(onClick = onToggleBlock, enabled = enabled) { Text(blockLabel) }
            }
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.rename_label)) },
                supportingText = { Text(stringResource(R.string.rename_hint)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
