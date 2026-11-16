package me.yeeeee.advancehotspot.ui.environment

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.yeeeee.advancehotspot.R
import me.yeeeee.advancehotspot.system.EnvironmentInfo
import me.yeeeee.advancehotspot.system.NetworkInterfaceInfo

@Composable
fun EnvironmentScreen(modifier: Modifier = Modifier, viewModel: EnvironmentViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val info = state.info
    if (info == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        EnvironmentContent(info, !state.loading, viewModel::refresh, modifier)
    }
}

@Composable
private fun EnvironmentContent(
    info: EnvironmentInfo,
    refreshEnabled: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unknown = stringResource(R.string.value_unknown)
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { RootCard(info.rootGranted) }
        item {
            SectionCard(stringResource(R.string.system_title)) {
                LabeledRow(stringResource(R.string.system_device), "${Build.MANUFACTURER} ${Build.MODEL}")
                LabeledRow(
                    stringResource(R.string.system_android),
                    "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                )
                LabeledRow(stringResource(R.string.system_kernel), info.kernel ?: unknown)
                LabeledRow(stringResource(R.string.system_selinux), info.selinux ?: unknown)
            }
        }
        item {
            SectionCard(stringResource(R.string.tools_title)) {
                info.tools.forEach { tool ->
                    LabeledRow(
                        label = tool.name,
                        value = stringResource(
                            if (tool.available) R.string.tool_available else R.string.tool_missing,
                        ),
                        monospaceLabel = true,
                    )
                }
            }
        }
        item {
            SectionCard(stringResource(R.string.interfaces_title)) {
                // 只列出啟用中的介面，rmnet 等閒置介面數量太多會淹沒有用的資訊
                info.interfaces.filter { it.isUp }.forEach { InterfaceRow(it) }
            }
        }
        item {
            TextButton(onClick = onRefresh, enabled = refreshEnabled) {
                Text(stringResource(R.string.action_refresh))
            }
        }
    }
}

@Composable
private fun RootCard(granted: Boolean) {
    val colors = if (granted) {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    } else {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
    Card(modifier = Modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.root_title), style = MaterialTheme.typography.labelLarge)
            Text(
                stringResource(if (granted) R.string.root_granted else R.string.root_denied),
                style = MaterialTheme.typography.headlineSmall,
            )
            if (!granted) {
                Text(stringResource(R.string.root_denied_hint), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun LabeledRow(label: String, value: String, monospaceLabel: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospaceLabel) FontFamily.Monospace else null,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun InterfaceRow(iface: NetworkInterfaceInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(iface.name, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
        val addresses = iface.addresses.ifEmpty { listOf(stringResource(R.string.interface_no_address)) }
        addresses.forEach { address ->
            Text(
                address,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
