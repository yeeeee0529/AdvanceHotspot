@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package me.yeeeee.advancehotspot.ui.hotspot

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonSize
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
import me.yeeeee.advancehotspot.ui.components.ChoiceFlow
import me.yeeeee.advancehotspot.ui.components.ConnectedChoiceGroup
import me.yeeeee.advancehotspot.ui.components.ErrorBanner
import me.yeeeee.advancehotspot.ui.components.HintText
import me.yeeeee.advancehotspot.ui.components.LoadingBox
import me.yeeeee.advancehotspot.ui.components.MorphingBadge
import me.yeeeee.advancehotspot.ui.components.ScreenScaffold
import me.yeeeee.advancehotspot.ui.components.SectionHeader
import me.yeeeee.advancehotspot.ui.components.SegmentSurface
import me.yeeeee.advancehotspot.ui.components.SegmentedGroup
import me.yeeeee.advancehotspot.ui.components.SwitchItem

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
    val showToolbar = state.isDirty && draft != null

    ScreenScaffold(
        title = stringResource(R.string.tab_hotspot),
        modifier = modifier,
        actions = {
            IconButton(onClick = viewModel::refresh, enabled = !state.busy) {
                Icon(painterResource(R.drawable.ic_refresh), contentDescription = stringResource(R.string.action_refresh))
            }
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = showToolbar,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                SaveToolbar(enabled = !state.busy, onDiscard = viewModel::discardChanges, onSave = viewModel::save)
            }
        },
        // 浮動工具列出現時，讓最後的設定項目可以捲到工具列上方
        bottomSpace = if (showToolbar) 112.dp else 24.dp,
    ) {
        if (status == null || draft == null) {
            if (state.busy) {
                LoadingBox(Modifier.heightIn(min = 320.dp))
            } else {
                ErrorBanner(stringResource(R.string.hotspot_load_failed, state.failure.orEmpty()))
            }
            return@ScreenScaffold
        }

        StatusHero(status, state.busy, viewModel::setEnabled)
        state.failure?.let { ErrorBanner(stringResource(R.string.hotspot_operation_failed, it)) }
        SettingsSections(
            draft = draft,
            capability = status.capability,
            enabled = !state.busy,
            onEdit = viewModel::edit,
        )
        state.validationError?.let {
            ErrorBanner(stringResource(it.messageRes()), Modifier.padding(top = 8.dp))
        }
        if (state.isDirty && status.isEnabled) {
            HintText(
                stringResource(R.string.hotspot_restart_notice),
                Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
            )
        }
    }
}

/** 頁面頂端的大型狀態區：變形徽章、狀態文字與主要的開關按鈕。 */
@Composable
private fun StatusHero(status: HotspotStatus, busy: Boolean, onToggle: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val on = status.isEnabled || status.apState == ApState.ENABLING
    val container by animateColorAsState(
        if (status.isEnabled) scheme.primaryContainer else scheme.surface,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "heroContainer",
    )
    val content = if (status.isEnabled) scheme.onPrimaryContainer else scheme.onSurface

    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MorphingBadge(
                    active = status.isEnabled,
                    size = 88.dp,
                    inactiveShape = MaterialShapes.Circle,
                    activeShape = MaterialShapes.Cookie9Sided,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_hotspot),
                        contentDescription = null,
                        tint = if (status.isEnabled) scheme.onPrimary else scheme.onSurfaceVariant,
                        modifier = Modifier.size(40.dp),
                    )
                }
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.hotspot_title), style = MaterialTheme.typography.labelLarge)
                    Text(
                        stringResource(apStateLabel(status.apState)),
                        style = MaterialTheme.typography.headlineLargeEmphasized,
                    )
                    if (status.isEnabled) {
                        status.activeFrequencies.firstOrNull()?.let { frequency ->
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
                }
            }
            if (busy) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            ToggleButton(
                checked = on,
                onCheckedChange = onToggle,
                enabled = !busy && !status.isTransitioning,
                buttonSize = ToggleButtonSize.Medium,
                shapes = ToggleButtonDefaults.shapesFor(ToggleButtonSize.Medium),
                modifier = Modifier.fillMaxWidth(),
                icon = { Icon(painterResource(R.drawable.ic_power), contentDescription = null) },
            ) {
                Text(stringResource(if (on) R.string.hotspot_turn_off else R.string.hotspot_turn_on))
            }
        }
    }
}

/** 有未儲存的變更時浮在底部的工具列。 */
@Composable
private fun SaveToolbar(enabled: Boolean, onDiscard: () -> Unit, onSave: () -> Unit) {
    HorizontalFloatingToolbar(
        expanded = true,
        colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
    ) {
        TextButton(onClick = onDiscard, enabled = enabled) {
            Icon(painterResource(R.drawable.ic_undo), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.action_discard))
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onSave, enabled = enabled, shapes = ButtonDefaults.shapes()) {
            Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.action_save))
        }
    }
}

@Composable
private fun SettingsSections(
    draft: HotspotConfig,
    capability: HotspotCapability?,
    enabled: Boolean,
    onEdit: ((HotspotConfig) -> HotspotConfig) -> Unit,
) {
    var passphraseVisible by rememberSaveable { mutableStateOf(false) }
    val needsPassphrase = draft.securityType != SecurityType.OPEN

    SectionHeader(stringResource(R.string.settings_section_network))
    SegmentedGroup {
        item("ssid") { shapes ->
            SegmentSurface(shapes) {
                OutlinedTextField(
                    value = draft.ssid,
                    onValueChange = { value -> onEdit { it.copy(ssid = value) } },
                    label = { Text(stringResource(R.string.settings_ssid)) },
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item("security") { shapes ->
            SegmentSurface(shapes) {
                Text(stringResource(R.string.settings_security), style = MaterialTheme.typography.labelLarge)
                ConnectedChoiceGroup(
                    options = SECURITY_CHOICES,
                    selected = SECURITY_CHOICES.first { it.value == draft.securityType },
                    label = { stringResource(it.label) },
                    onSelect = { choice -> onEdit { it.copy(securityType = choice.value) } },
                    enabled = enabled,
                )
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
                            IconButton(onClick = { passphraseVisible = !passphraseVisible }) {
                                Icon(
                                    painterResource(
                                        if (passphraseVisible) R.drawable.ic_visibility_off else R.drawable.ic_visibility,
                                    ),
                                    contentDescription = stringResource(
                                        if (passphraseVisible) R.string.action_hide else R.string.action_show,
                                    ),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    SectionHeader(stringResource(R.string.settings_section_radio))
    SegmentedGroup {
        item("band") { shapes ->
            SegmentSurface(shapes) {
                Text(stringResource(R.string.settings_band), style = MaterialTheme.typography.labelLarge)
                ConnectedChoiceGroup(
                    options = BAND_CHOICES,
                    selected = BAND_CHOICES.firstOrNull { it.value == draft.band } ?: BAND_CHOICES.first(),
                    label = { stringResource(it.label) },
                    // 換頻段後原本的頻道不一定存在，一律回到自動
                    onSelect = { choice -> onEdit { it.copy(band = choice.value, channel = 0) } },
                    enabled = enabled,
                )
                if (draft.band == Band.AUTO_2GHZ_5GHZ) HintText(stringResource(R.string.band_auto_hint))
            }
        }
        if (Band.isSingle(draft.band)) {
            item("channel") { shapes ->
                SegmentSurface(shapes) {
                    Text(stringResource(R.string.settings_channel), style = MaterialTheme.typography.labelLarge)
                    val channels = listOf(0) + capability?.channels?.get(draft.band).orEmpty()
                    ChoiceFlow(
                        options = channels,
                        selected = draft.channel,
                        label = { if (it == 0) stringResource(R.string.channel_auto) else it.toString() },
                        onSelect = { value -> onEdit { it.copy(channel = value) } },
                        enabled = enabled,
                    )
                    HintText(stringResource(R.string.settings_channel_hint))
                }
            }
        }
    }

    SectionHeader(stringResource(R.string.settings_section_advanced))
    SegmentedGroup {
        item("maxClients") { shapes ->
            SegmentSurface(shapes) {
                OutlinedTextField(
                    value = if (draft.maxClients == 0) "" else draft.maxClients.toString(),
                    onValueChange = { value ->
                        val parsed = value.filter(Char::isDigit).take(3).toIntOrNull() ?: 0
                        onEdit { it.copy(maxClients = parsed) }
                    },
                    label = { Text(stringResource(R.string.settings_max_clients)) },
                    placeholder = { Text(stringResource(R.string.settings_max_clients_unlimited)) },
                    // 不顯示系統回報的上限：那個數字來自設定檔，Wi-Fi 韌體實際能接受的台數比它少
                    supportingText = { Text(stringResource(R.string.settings_max_clients_hint)) },
                    singleLine = true,
                    enabled = enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item("hidden") { shapes ->
            SwitchItem(
                shapes = shapes,
                title = stringResource(R.string.settings_hidden),
                checked = draft.hidden,
                onCheckedChange = { value -> onEdit { it.copy(hidden = value) } },
                enabled = enabled,
            )
        }
        item("autoShutdown") { shapes ->
            SwitchItem(
                shapes = shapes,
                title = stringResource(R.string.settings_auto_shutdown),
                checked = draft.autoShutdown,
                onCheckedChange = { value -> onEdit { it.copy(autoShutdown = value) } },
                enabled = enabled,
            )
        }
        item("isolation") { shapes ->
            SwitchItem(
                shapes = shapes,
                title = stringResource(R.string.settings_client_isolation),
                supporting = stringResource(R.string.settings_client_isolation_hint),
                checked = draft.clientIsolation,
                onCheckedChange = { value -> onEdit { it.copy(clientIsolation = value) } },
                enabled = enabled,
            )
        }
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
