package net.oh4ma.advancehotspot.ui.devices

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import net.oh4ma.advancehotspot.R
import net.oh4ma.advancehotspot.routing.Egress
import net.oh4ma.advancehotspot.routing.ResolvedEgress
import net.oh4ma.advancehotspot.traffic.TrafficLimit
import net.oh4ma.advancehotspot.ui.components.HintText

private const val REFRESH_INTERVAL_MS = 4000L

/**
 * 只在畫面位於前景時輪詢裝置清單，每次輪詢都要啟動一個 helper 行程。
 *
 * 「裝置」與「網路」分頁共用同一個 [DevicesViewModel]，同一時間只會顯示其中一頁，所以只有一份輪詢在跑。
 */
@Composable
fun DevicesPolling(viewModel: DevicesViewModel) {
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
}

val DEFAULT_EGRESS_CHOICES = listOf(Egress.SYSTEM, Egress.WIFI, Egress.CELLULAR, Egress.VPN, Egress.ETHERNET)
val DEVICE_EGRESS_CHOICES = listOf(Egress.INHERIT) + DEFAULT_EGRESS_CHOICES

@StringRes
fun Egress.labelRes(): Int = when (this) {
    Egress.INHERIT -> R.string.egress_inherit
    Egress.SYSTEM -> R.string.egress_system
    Egress.WIFI -> R.string.egress_wifi
    Egress.CELLULAR -> R.string.egress_cellular
    Egress.VPN -> R.string.egress_vpn
    Egress.ETHERNET -> R.string.egress_ethernet
}

/** 顯示指定出口目前對應的介面；跟隨系統時沒有可顯示的資訊。 */
@Composable
fun EgressStatus(egress: Egress, resolved: Map<Egress, ResolvedEgress>) {
    if (egress == Egress.SYSTEM || egress == Egress.INHERIT) return
    val label = stringResource(egress.labelRes())
    val target = resolved[egress]
    HintText(
        if (target != null) {
            stringResource(R.string.egress_status_online, label, target.iface)
        } else {
            stringResource(R.string.egress_status_offline, label)
        },
        isError = target == null,
    )
}

/** 上限以 Mbps 顯示；0 代表不限制。 */
@Composable
fun limitLabel(kbit: Int): String =
    if (kbit == 0) stringResource(R.string.traffic_unlimited) else formatMbps(kbit) + " Mbps"

private fun formatMbps(kbit: Int): String = (kbit / 1000.0).toBigDecimal().stripTrailingZeros().toPlainString()

/** 把使用者輸入的 Mbps 轉成 kbit/s；空白代表不限制，無法解析或超出範圍時回傳 null。 */
private fun parseMbps(text: String): Int? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return 0
    val mbps = trimmed.toDoubleOrNull() ?: return null
    if (mbps < 0 || mbps > MAX_LIMIT_MBPS) return null
    return (mbps * 1000).toInt()
}

private const val MAX_LIMIT_MBPS = 10_000.0

@Composable
fun LimitDialog(initial: TrafficLimit, onConfirm: (TrafficLimit) -> Unit, onDismiss: () -> Unit) {
    var upText by rememberSaveable { mutableStateOf(if (initial.upKbit == 0) "" else formatMbps(initial.upKbit)) }
    var downText by rememberSaveable { mutableStateOf(if (initial.downKbit == 0) "" else formatMbps(initial.downKbit)) }
    var invalid by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.limit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, text, onChange) in listOf(
                    Triple(R.string.limit_download, downText) { value: String -> downText = value },
                    Triple(R.string.limit_upload, upText) { value: String -> upText = value },
                )) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = {
                            onChange(it)
                            invalid = false
                        },
                        label = { Text(stringResource(label)) },
                        placeholder = { Text(stringResource(R.string.traffic_unlimited)) },
                        isError = invalid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                    )
                }
                HintText(
                    stringResource(if (invalid) R.string.limit_error else R.string.limit_hint),
                    isError = invalid,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val up = parseMbps(upText)
                    val down = parseMbps(downText)
                    if (up == null || down == null) {
                        invalid = true
                    } else {
                        onConfirm(TrafficLimit(upKbit = up, downKbit = down))
                        onDismiss()
                    }
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * 單一文字欄位的輸入對話框。
 *
 * @param onConfirm 回傳 false 代表輸入不合法，對話框會留著並顯示 [errorMessage]。
 */
@Composable
fun TextInputDialog(
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
