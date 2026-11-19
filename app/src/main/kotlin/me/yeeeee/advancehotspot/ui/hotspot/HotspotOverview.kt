@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package me.yeeeee.advancehotspot.ui.hotspot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.yeeeee.advancehotspot.R
import me.yeeeee.advancehotspot.traffic.TrafficStats
import me.yeeeee.advancehotspot.traffic.formatBytes
import me.yeeeee.advancehotspot.traffic.formatRate
import me.yeeeee.advancehotspot.ui.components.HintText
import me.yeeeee.advancehotspot.ui.components.SectionHeader

/** 仿 FlClash 儀表板的出口 IP 卡片：國別徽章、IP、國名與組織。 */
@Composable
fun PublicIpCard(state: PublicIpUiState, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val info = state.info
    Surface(
        color = scheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_public), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ip_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (state.loading) {
                        LoadingIndicator(Modifier.size(32.dp))
                    } else {
                        IconButton(onClick = onRefresh) {
                            Icon(painterResource(R.drawable.ic_refresh), contentDescription = stringResource(R.string.action_refresh))
                        }
                    }
                }
            }
            Row(Modifier.padding(top = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = scheme.secondaryContainer,
                    contentColor = scheme.onSecondaryContainer,
                    shape = MaterialShapes.Cookie9Sided.toShape(),
                    modifier = Modifier.size(64.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(info?.countryCode ?: "?", style = MaterialTheme.typography.titleMediumEmphasized)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        info?.ip ?: stringResource(if (state.loading) R.string.ip_loading else R.string.ip_unknown),
                        style = MaterialTheme.typography.headlineSmallEmphasized,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    info?.localizedCountry()?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    }
                }
            }
            if (info != null) {
                Text(
                    stringResource(R.string.ip_organization),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    info.organization ?: stringResource(R.string.ip_unknown),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
            state.failure?.let {
                HintText(stringResource(R.string.ip_failed, it), Modifier.padding(top = 12.dp, end = 12.dp), isError = true)
            }
        }
    }
}

/** 熱點的總上傳與下載速率，加上累計用量；資料來自流量統計。 */
@Composable
fun NetworkSpeedSection(trafficEnabled: Boolean, traffic: Collection<TrafficStats>) {
    SectionHeader(stringResource(R.string.speed_title))
    if (!trafficEnabled) {
        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
            HintText(stringResource(R.string.speed_traffic_off), Modifier.padding(20.dp))
        }
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SpeedTile(
            icon = R.drawable.ic_arrow_upward,
            label = stringResource(R.string.traffic_upload),
            rate = traffic.sumOf { it.upRate },
            total = traffic.sumOf { it.upBytes },
            accent = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.weight(1f),
        )
        SpeedTile(
            icon = R.drawable.ic_arrow_downward,
            label = stringResource(R.string.traffic_download),
            rate = traffic.sumOf { it.downRate },
            total = traffic.sumOf { it.downBytes },
            accent = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SpeedTile(icon: Int, label: String, rate: Long, total: Long, accent: Color, modifier: Modifier = Modifier) {
    // formatRate 的格式是「數值 單位」，拆開後數值放大顯示
    val (value, unit) = formatRate(rate).split(' ', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.extraLarge, modifier = modifier) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(icon), contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.titleSmall)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = MaterialTheme.typography.headlineMediumEmphasized)
                Spacer(Modifier.width(4.dp))
                Text(unit, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
            }
            Text(
                stringResource(R.string.traffic_total_value, formatBytes(total)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
