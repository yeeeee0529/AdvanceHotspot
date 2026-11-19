@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package me.yeeeee.advancehotspot.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toPath
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon

/** 頁面左右留白；分段清單、區段標題都以此對齊。 */
val ScreenHorizontalPadding = 16.dp

/**
 * 各分頁共用的頁面骨架：可隨捲動收合的大型頂部列，加上可捲動的內容欄。
 *
 * 背景使用 surfaceContainer，讓 surface 色的分段清單項目浮在背景上，符合 M3 Expressive 的設定頁樣式。
 */
@Composable
fun ScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    bottomSpace: Dp = 24.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val background = MaterialTheme.colorScheme.surfaceContainer
    // 底部浮動導覽列蓋在內容上：底部系統列的內距改由導覽列高度取代
    val dockInset = LocalDockInset.current
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = background,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(title) },
                subtitle = subtitle?.let { { Text(it) } },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = background,
                    scrolledContainerColor = background,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = { Box(Modifier.padding(bottom = dockInset)) { floatingActionButton() } },
        floatingActionButtonPosition = FabPosition.Center,
        contentWindowInsets = if (dockInset > 0.dp) {
            ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        } else {
            ScaffoldDefaults.contentWindowInsets
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(start = ScreenHorizontalPadding, end = ScreenHorizontalPadding, bottom = bottomSpace + dockInset),
            content = content,
        )
    }
}

/** 頁面載入中的置中指示器。 */
@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        LoadingIndicator(Modifier.size(72.dp))
    }
}

/** 分段清單上方的區段標題。 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

/** 收集一組分段清單項目，讓每一項依位置取得對應的圓角形狀。 */
class SegmentedGroupScope internal constructor() {
    internal val entries = mutableListOf<Pair<Any?, @Composable (ListItemShapes) -> Unit>>()

    fun item(key: Any? = null, content: @Composable (shapes: ListItemShapes) -> Unit) {
        entries += key to content
    }
}

/**
 * M3 Expressive 的分段清單：項目之間留細縫，整組的第一項與最後一項使用大圓角。
 */
@Composable
fun SegmentedGroup(modifier: Modifier = Modifier, build: SegmentedGroupScope.() -> Unit) {
    val scope = SegmentedGroupScope().apply(build)
    val count = scope.entries.size
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        scope.entries.forEachIndexed { index, (itemKey, content) ->
            key(itemKey ?: index) {
                content(ListItemDefaults.segmentedShapes(index, count))
            }
        }
    }
}

/** 放置自訂內容（文字欄位、按鈕群組等）的分段項目。 */
@Composable
fun SegmentSurface(shapes: ListItemShapes, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = shapes.shape,
        color = ListItemDefaults.segmentedColors().containerColor,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** 整列可點擊切換的開關項目。 */
@Composable
fun SwitchItem(
    shapes: ListItemShapes,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
) {
    // 可切換的清單項目預設在開啟時改用「已選取」底色；開關列的狀態已由 Switch 表達，維持一般底色
    val base = ListItemDefaults.segmentedColors()
    SegmentedListItem(
        checked = checked,
        onCheckedChange = onCheckedChange,
        shapes = shapes,
        modifier = modifier,
        enabled = enabled,
        colors = ListItemDefaults.segmentedColors(
            selectedContainerColor = base.containerColor,
            selectedContentColor = base.contentColor,
            selectedLeadingContentColor = base.leadingContentColor,
            selectedTrailingContentColor = base.trailingContentColor,
            selectedOverlineContentColor = base.overlineContentColor,
            selectedSupportingContentColor = base.supportingContentColor,
        ),
        leadingContent = icon?.let { { Icon(painterResource(it), contentDescription = null) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        supportingContent = supporting?.let { { Text(it) } },
    ) {
        Text(title)
    }
}

/** 唯讀的「標籤：值」項目。 */
@Composable
fun InfoItem(
    shapes: ListItemShapes,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    monospace: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    SegmentedListItem(
        shapes = shapes,
        modifier = modifier,
        overlineContent = { Text(label) },
        trailingContent = trailing,
    ) {
        Text(value, fontFamily = if (monospace) FontFamily.Monospace else null)
    }
}

/**
 * 相連按鈕群組（connected button group），用於少量互斥選項的單選。
 * 選項超過四個時請改用 [ChoiceFlow]，否則在手機寬度下文字會被擠壓。
 */
@Composable
fun <T> ConnectedChoiceGroup(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, option ->
            ToggleButton(
                checked = option == selected,
                onCheckedChange = { onSelect(option) },
                enabled = enabled,
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                contentPadding = PaddingValues(horizontal = 4.dp),
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
            ) {
                Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 選項較多時使用的單選：會換行的切換按鈕，選中時由圓形變為圓角方形。 */
@Composable
fun <T> ChoiceFlow(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            ToggleButton(
                checked = option == selected,
                onCheckedChange = { onSelect(option) },
                enabled = enabled,
                modifier = Modifier.semantics { role = Role.RadioButton },
            ) {
                Text(label(option), maxLines = 1)
            }
        }
    }
}

/** 依 [Morph] 進度繪製的形狀，進度 0 為起點形狀、1 為終點形狀。 */
private class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress)
        path.transform(Matrix().apply { scale(x = size.width, y = size.height) })
        path.translate(size.center - path.getBounds().center)
        return Outline.Generic(path)
    }
}

/**
 * 狀態徽章：[active] 切換時在兩個 Material 形狀之間變形，啟用中會緩慢旋轉。
 */
@Composable
fun MorphingBadge(
    active: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    inactiveShape: RoundedPolygon = MaterialShapes.Circle,
    activeShape: RoundedPolygon = MaterialShapes.Cookie9Sided,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    content: @Composable () -> Unit,
) {
    val morph = remember(inactiveShape, activeShape) { Morph(inactiveShape, activeShape) }
    val progress by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.slowSpatialSpec(),
        label = "badgeMorph",
    )
    val color by animateColorAsState(
        targetValue = if (active) activeColor else inactiveColor,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "badgeColor",
    )
    val spin by rememberInfiniteTransition(label = "badgeSpin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 20_000, easing = LinearEasing)),
        label = "badgeAngle",
    )
    val rotation = spin * progress
    Box(
        modifier
            .size(size)
            .graphicsLayer { rotationZ = rotation }
            .clip(MorphShape(morph, progress))
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        // 反向旋轉內容，讓圖示保持正立
        Box(Modifier.graphicsLayer { rotationZ = -rotation }) { content() }
    }
}

private val AVATAR_SHAPES = listOf(
    MaterialShapes.Cookie4Sided,
    MaterialShapes.Clover4Leaf,
    MaterialShapes.Pentagon,
    MaterialShapes.Gem,
    MaterialShapes.Sunny,
    MaterialShapes.Cookie6Sided,
    MaterialShapes.Puffy,
    MaterialShapes.Ghostish,
)

/** 以 [seed]（通常是 MAC 位址）決定形狀與配色的裝置頭像，同一台裝置每次顯示都一樣。 */
@Composable
fun DeviceAvatar(seed: String, name: String, modifier: Modifier = Modifier, size: Dp = 40.dp, dimmed: Boolean = false) {
    val hash = seed.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }
    val shape = AVATAR_SHAPES[hash % AVATAR_SHAPES.size].toShape()
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when {
        dimmed -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
        hash / AVATAR_SHAPES.size % 3 == 0 -> scheme.primaryContainer to scheme.onPrimaryContainer
        hash / AVATAR_SHAPES.size % 3 == 1 -> scheme.secondaryContainer to scheme.onSecondaryContainer
        else -> scheme.tertiaryContainer to scheme.onTertiaryContainer
    }
    Box(
        modifier.size(size).clip(shape).background(container),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.firstOrNull()?.uppercase() ?: "?",
            style = if (size >= 56.dp) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
            color = content,
        )
    }
}

/** 錯誤訊息橫幅。 */
@Composable
fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Text(message, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** 次要的說明文字，放在分段項目內或清單下方。 */
@Composable
fun HintText(text: String, modifier: Modifier = Modifier, isError: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
