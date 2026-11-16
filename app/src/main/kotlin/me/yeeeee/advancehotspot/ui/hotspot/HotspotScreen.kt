package me.yeeeee.advancehotspot.ui.hotspot

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.yeeeee.advancehotspot.R
import me.yeeeee.advancehotspot.hotspot.ApState
import me.yeeeee.advancehotspot.hotspot.Band
import me.yeeeee.advancehotspot.hotspot.ConfigError
import me.yeeeee.advancehotspot.hotspot.HotspotCapability
import me.yeeeee.advancehotspot.hotspot.HotspotConfig
import me.yeeeee.advancehotspot.hotspot.HotspotStatus
import me.yeeeee.advancehotspot.hotspot.SecurityType
import me.yeeeee.advancehotspot.hotspot.frequencyToChannel

private data class Choice(val value: Int, @param:StringRes val label: Int)

private val SECURITY_CHOICES = listOf(
    Choice(SecurityType.WPA3_TRANSITION, R.string.security_wpa3_transition),
    Choice(SecurityType.WPA3, R.string.security_wpa3),
    Choice(SecurityType.WPA2, R.string.security_wpa2),
    Choice(SecurityType.OPEN, R.string.security_open),
)

private val BAND_CHOICES = listOf(
    Choice(Band.AUTO_2GHZ_5GHZ, R.string.band_auto),
    Choice(Band.BAND_2GHZ, R.string.band_2ghz),
    Choice(Band.BAND_5GHZ, R.string.band_5ghz),
    Choice(Band.BAND_6GHZ, R.string.band_6ghz),
)

@Composable
fun HotspotScreen(modifier: Modifier = Modifier, viewModel: HotspotViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val status = state.status
    val draft = state.draft

    if (status == null || draft == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (state.busy) {
                CircularProgressIndicator()
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Text(stringResource(R.string.hotspot_load_failed, state.failure.orEmpty()))
                    TextButton(onClick = viewModel::refresh) { Text(stringResource(R.string.action_refresh)) }
                }
            }
        }
        return
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusCard(status, state.busy, viewModel::setEnabled, viewModel::refresh)
        state.failure?.let { FailureCard(it) }
        SettingsCard(
            draft = draft,
            capability = status.capability,
            enabled = !state.busy,
            onEdit = viewModel::edit,
        )
        state.validationError?.let {
            Text(
                stringResource(it.messageRes()),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (state.isDirty && status.isEnabled) {
            Text(
                stringResource(R.string.hotspot_restart_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
            TextButton(onClick = viewModel::discardChanges, enabled = state.isDirty && !state.busy) {
                Text(stringResource(R.string.action_discard))
            }
            Button(onClick = viewModel::save, enabled = state.isDirty && !state.busy) {
                Text(stringResource(R.string.action_save))
            }
        }
    }
}

@Composable
private fun StatusCard(
    status: HotspotStatus,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onRefresh: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (status.isEnabled) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.hotspot_title), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(apStateLabel(status.apState)), style = MaterialTheme.typography.headlineSmall)
                }
                Switch(
                    checked = status.isEnabled || status.apState == ApState.ENABLING,
                    onCheckedChange = onToggle,
                    enabled = !busy && !status.isTransitioning,
                )
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (status.isEnabled) {
                val frequency = status.activeFrequencies.firstOrNull()
                if (frequency != null) {
                    Text(
                        stringResource(
                            R.string.hotspot_active_channel,
                            frequencyToChannel(frequency)?.toString() ?: "?",
                            frequency,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    stringResource(R.string.hotspot_client_count, status.clientCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            TextButton(onClick = onRefresh, enabled = !busy, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.action_refresh))
            }
        }
    }
}

@Composable
private fun FailureCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Text(
            stringResource(R.string.hotspot_operation_failed, message),
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SettingsCard(
    draft: HotspotConfig,
    capability: HotspotCapability?,
    enabled: Boolean,
    onEdit: ((HotspotConfig) -> HotspotConfig) -> Unit,
) {
    var passphraseVisible by rememberSaveable { mutableStateOf(false) }
    val needsPassphrase = draft.securityType != SecurityType.OPEN

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = draft.ssid,
                onValueChange = { value -> onEdit { it.copy(ssid = value) } },
                label = { Text(stringResource(R.string.settings_ssid)) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )

            ChoiceRow(R.string.settings_security, SECURITY_CHOICES, draft.securityType, enabled) { value ->
                onEdit { it.copy(securityType = value) }
            }

            if (needsPassphrase) {
                OutlinedTextField(
                    value = draft.passphrase,
                    onValueChange = { value -> onEdit { it.copy(passphrase = value) } },
                    label = { Text(stringResource(R.string.settings_passphrase)) },
                    singleLine = true,
                    enabled = enabled,
                    visualTransformation = if (passphraseVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { passphraseVisible = !passphraseVisible }) {
                            Text(
                                stringResource(
                                    if (passphraseVisible) R.string.action_hide else R.string.action_show,
                                ),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            ChoiceRow(R.string.settings_band, BAND_CHOICES, draft.band, enabled) { value ->
                // 換頻段後原本的頻道不一定存在，一律回到自動
                onEdit { it.copy(band = value, channel = 0) }
            }

            if (Band.isSingle(draft.band)) {
                val channels = capability?.channels?.get(draft.band).orEmpty()
                ChannelRow(channels, draft.channel, enabled) { value -> onEdit { it.copy(channel = value) } }
                Text(
                    stringResource(R.string.settings_channel_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = if (draft.maxClients == 0) "" else draft.maxClients.toString(),
                onValueChange = { value ->
                    val parsed = value.filter(Char::isDigit).take(3).toIntOrNull() ?: 0
                    onEdit { it.copy(maxClients = parsed) }
                },
                label = { Text(stringResource(R.string.settings_max_clients)) },
                placeholder = { Text(stringResource(R.string.settings_max_clients_unlimited)) },
                supportingText = capability?.let {
                    { Text(stringResource(R.string.settings_max_clients_hint, it.maxSupportedClients)) }
                },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            SwitchRow(R.string.settings_hidden, draft.hidden, enabled) { value -> onEdit { it.copy(hidden = value) } }
            SwitchRow(R.string.settings_auto_shutdown, draft.autoShutdown, enabled) { value ->
                onEdit { it.copy(autoShutdown = value) }
            }
            SwitchRow(R.string.settings_client_isolation, draft.clientIsolation, enabled) { value ->
                onEdit { it.copy(clientIsolation = value) }
            }
            Text(
                stringResource(R.string.settings_client_isolation_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChoiceRow(
    @StringRes label: Int,
    choices: List<Choice>,
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            choices.forEach { choice ->
                FilterChip(
                    selected = choice.value == selected,
                    onClick = { onSelect(choice.value) },
                    label = { Text(stringResource(choice.label)) },
                    enabled = enabled,
                )
            }
        }
    }
}

@Composable
private fun ChannelRow(channels: List<Int>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.settings_channel), style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = selected == 0,
                onClick = { onSelect(0) },
                label = { Text(stringResource(R.string.channel_auto)) },
                enabled = enabled,
            )
            channels.forEach { channel ->
                FilterChip(
                    selected = channel == selected,
                    onClick = { onSelect(channel) },
                    label = { Text(channel.toString()) },
                    enabled = enabled,
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(@StringRes label: Int, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@StringRes
private fun apStateLabel(apState: Int): Int = when (apState) {
    ApState.ENABLED -> R.string.ap_state_enabled
    ApState.ENABLING -> R.string.ap_state_enabling
    ApState.DISABLING -> R.string.ap_state_disabling
    ApState.FAILED -> R.string.ap_state_failed
    else -> R.string.ap_state_disabled
}

@StringRes
private fun ConfigError.messageRes(): Int = when (this) {
    ConfigError.SSID_EMPTY -> R.string.error_ssid_empty
    ConfigError.SSID_TOO_LONG -> R.string.error_ssid_too_long
    ConfigError.PASSPHRASE_LENGTH -> R.string.error_passphrase_length
    ConfigError.SIX_GHZ_REQUIRES_WPA3 -> R.string.error_six_ghz_requires_wpa3
    ConfigError.CHANNEL_UNSUPPORTED -> R.string.error_channel_unsupported
    ConfigError.MAX_CLIENTS_RANGE -> R.string.error_max_clients_range
}
