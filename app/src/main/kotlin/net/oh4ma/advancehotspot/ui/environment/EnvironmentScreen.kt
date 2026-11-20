@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package net.oh4ma.advancehotspot.ui.environment

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import net.oh4ma.advancehotspot.R
import net.oh4ma.advancehotspot.privilege.PrivilegeMode
import net.oh4ma.advancehotspot.privilege.ShizukuState
import net.oh4ma.advancehotspot.system.EnvironmentInfo
import net.oh4ma.advancehotspot.ui.components.ChoiceFlow
import net.oh4ma.advancehotspot.ui.components.HintText
import net.oh4ma.advancehotspot.ui.components.InfoItem
import net.oh4ma.advancehotspot.ui.components.LoadingBox
import net.oh4ma.advancehotspot.ui.components.MorphingBadge
import net.oh4ma.advancehotspot.ui.components.ScreenScaffold
import net.oh4ma.advancehotspot.ui.components.SectionHeader
import net.oh4ma.advancehotspot.ui.components.SegmentSurface
import net.oh4ma.advancehotspot.ui.components.SegmentedGroup

@Composable
fun EnvironmentScreen(modifier: Modifier = Modifier, viewModel: EnvironmentViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val info = state.info

    ScreenScaffold(
        title = stringResource(R.string.tab_environment),
        modifier = modifier,
        // 首次載入由 LoadingBox 顯示，避免同時出現兩個指示器
        refreshing = state.loading && info != null,
        onRefresh = viewModel::refresh,
    ) {
        if (info == null) {
            LoadingBox(Modifier.padding(top = 120.dp))
        } else {
            EnvironmentContent(info, viewModel::setMode, viewModel::requestShizukuPermission)
        }
    }
}

@Composable
private fun EnvironmentContent(
    info: EnvironmentInfo,
    onMode: (PrivilegeMode) -> Unit,
    onRequestShizukuPermission: () -> Unit,
) {
    val unknown = stringResource(R.string.value_unknown)
    PrivilegeHero(info, onRequestShizukuPermission)

    SectionHeader(stringResource(R.string.privilege_title))
    SegmentedGroup {
        item("mode") { shapes ->
            SegmentSurface(shapes) {
                ChoiceFlow(
                    options = PrivilegeMode.entries,
                    selected = info.mode,
                    label = {
                        stringResource(
                            when (it) {
                                PrivilegeMode.ROOT -> R.string.privilege_mode_root
                                PrivilegeMode.SHIZUKU -> R.string.privilege_mode_shizuku
                            },
                        )
                    },
                    onSelect = onMode,
                )
                HintText(stringResource(R.string.privilege_mode_hint))
            }
        }
    }

    SectionHeader(stringResource(R.string.system_title))
    SegmentedGroup {
        item { InfoItem(it, stringResource(R.string.system_device), "${Build.MANUFACTURER} ${Build.MODEL}") }
        item {
            InfoItem(
                it,
                stringResource(R.string.system_android),
                "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            )
        }
        item { InfoItem(it, stringResource(R.string.system_kernel), info.kernel ?: unknown) }
        item { InfoItem(it, stringResource(R.string.system_selinux), info.selinux ?: unknown) }
    }

    SectionHeader(stringResource(R.string.tools_title))
    SegmentedGroup {
        info.tools.forEach { tool ->
            item(tool.name) { shapes ->
                SegmentedListItem(
                    shapes = shapes,
                    supportingContent = {
                        Text(stringResource(if (tool.available) R.string.tool_available else R.string.tool_missing))
                    },
                    trailingContent = {
                        Icon(
                            painterResource(if (tool.available) R.drawable.ic_check else R.drawable.ic_close),
                            contentDescription = null,
                            tint = if (tool.available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    },
                ) {
                    Text(tool.name, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }

    SectionHeader(stringResource(R.string.interfaces_title))
    SegmentedGroup {
        // 只列出啟用中的介面，rmnet 等閒置介面數量太多會淹沒有用的資訊
        info.interfaces.filter { it.isUp }.forEach { iface ->
            item(iface.name) { shapes ->
                val addresses = iface.addresses.ifEmpty { listOf(stringResource(R.string.interface_no_address)) }
                SegmentedListItem(
                    shapes = shapes,
                    supportingContent = {
                        Column {
                            addresses.forEach { Text(it, fontFamily = FontFamily.Monospace) }
                        }
                    },
                ) {
                    Text(iface.name, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun PrivilegeHero(info: EnvironmentInfo, onRequestShizukuPermission: () -> Unit) {
    val granted = info.privilegeReady
    val title: Int
    val status: Int
    val hint: Int?
    if (info.mode == PrivilegeMode.ROOT) {
        title = R.string.root_title
        status = if (granted) R.string.root_granted else R.string.root_denied
        hint = if (granted) null else R.string.root_denied_hint
    } else {
        title = R.string.shizuku_title
        when (info.shizukuState) {
            ShizukuState.READY, null -> {
                status = R.string.shizuku_ready
                hint = null
            }
            ShizukuState.NOT_RUNNING -> {
                status = R.string.shizuku_not_running
                hint = R.string.shizuku_not_running_hint
            }
            ShizukuState.PERMISSION_REQUIRED -> {
                status = R.string.shizuku_permission_required
                hint = R.string.shizuku_permission_hint
            }
            ShizukuState.WRONG_UID -> {
                status = R.string.shizuku_wrong_uid
                hint = R.string.shizuku_wrong_uid_hint
            }
        }
    }
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = if (granted) scheme.primaryContainer else scheme.errorContainer,
        contentColor = if (granted) scheme.onPrimaryContainer else scheme.onErrorContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            MorphingBadge(
                active = granted,
                size = 72.dp,
                inactiveShape = MaterialShapes.Cookie4Sided,
                activeShape = MaterialShapes.Sunny,
                activeColor = scheme.primary,
                inactiveColor = scheme.error,
            ) {
                Icon(
                    painterResource(if (granted) R.drawable.ic_check else R.drawable.ic_close),
                    contentDescription = null,
                    tint = if (granted) scheme.onPrimary else scheme.onError,
                    modifier = Modifier.size(32.dp),
                )
            }
            Spacer(Modifier.width(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(status), style = MaterialTheme.typography.headlineMediumEmphasized)
                hint?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
                if (info.shizukuState == ShizukuState.PERMISSION_REQUIRED) {
                    Button(
                        onClick = onRequestShizukuPermission,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text(stringResource(R.string.action_request_permission))
                    }
                }
            }
        }
    }
}
