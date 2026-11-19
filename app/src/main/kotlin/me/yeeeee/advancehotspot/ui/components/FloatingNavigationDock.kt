package me.yeeeee.advancehotspot.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// 尺寸與彈簧參數取自 FlClash（prism_ui 的 NavigationDock）的非玻璃樣式
private val BarHeight = 62.dp
private val BarPadding = 4.dp
private val EdgeMargin = 21.dp
private val MaxItemExtent = 86.dp
private val IconSize = 28.dp
private val LabelGap = 2.dp
private const val PressScale = 1.04f

// 選取指示（鏡片）在分頁間移動：400 ms、彈跳 0.15
private val TravelSpring = spring<Float>(dampingRatio = 0.85f, stiffness = 247f)

// 拖曳時鏡片緊跟手指
private val FollowSpring = spring<Float>(dampingRatio = 0.9f, stiffness = 1024f)

// 按下時整條導覽列微微放大後回彈
private val PressSpring = spring<Float>(dampingRatio = 0.593f, stiffness = 311f)

/** 各分頁內容底部要讓出的高度，讓最後一項能捲到浮動導覽列上方。 */
val LocalDockInset = staticCompositionLocalOf { 0.dp }

/** 浮動導覽列的一個目的地。 */
class DockDestination(val label: String, @param:DrawableRes val icon: Int)

/** 浮動導覽列佔用的底部高度，含與螢幕底邊的間距。 */
@Composable
fun floatingNavigationDockInset(): Dp = BarHeight + dockBottomMargin()

@Composable
private fun dockBottomMargin(): Dp =
    max(EdgeMargin, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())

/**
 * 仿 FlClash 的浮動膠囊導覽列：置中懸浮在內容上方，選取的分頁底下有一片會滑動的鏡片。
 * 點選切換分頁；也可以按住左右拖曳鏡片，放開時切到手指下的分頁。
 */
@Composable
fun FloatingNavigationDock(
    destinations: List<DockDestination>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = destinations.size
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    // FlClash 的 floating 表面層級：深色用 block、淺色用 card；鏡片再往上一層
    val container = if (dark) colors.surfaceContainerHigh else colors.surfaceBright
    val lensBase = if (dark) colors.surfaceContainerHighest else colors.surfaceContainer
    val shadowStrength = if (dark) 3f else 1f

    val lens = remember { Animatable(selectedIndex.toFloat()) }
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) PressScale else 1f, PressSpring, label = "dockPress")
    val lift by animateFloatAsState(if (pressed) 1f else 0f, spring(stiffness = 600f), label = "dockLift")
    val scope = rememberCoroutineScope()
    val currentSelected by rememberUpdatedState(selectedIndex)
    val currentOnSelected by rememberUpdatedState(onSelected)

    LaunchedEffect(selectedIndex) {
        if (!pressed) lens.animateTo(selectedIndex.toFloat(), TravelSpring)
    }

    Box(
        modifier
            .fillMaxWidth()
            .padding(start = EdgeMargin, end = EdgeMargin, bottom = dockBottomMargin()),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(
            Modifier
                .widthIn(max = MaxItemExtent * count + BarPadding * 4)
                .fillMaxWidth()
                .height(BarHeight)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .dropShadow(
                    CircleShape,
                    Shadow(24.dp, Color.Black.copy(alpha = 0.08f * shadowStrength), offset = DpOffset(0.dp, 8.dp)),
                )
                .dropShadow(
                    CircleShape,
                    Shadow(3.dp, Color.Black.copy(alpha = 0.04f * shadowStrength), offset = DpOffset(0.dp, 1.dp)),
                )
                .clip(CircleShape)
                .background(container)
                .padding(BarPadding),
        ) {
            val density = LocalDensity.current
            val pitch = (maxWidth - BarPadding * 2) / count
            val pitchPx = with(density) { pitch.toPx() }
            val paddingPx = with(density) { BarPadding.toPx() }

            fun indexAt(x: Float): Int = ((x - paddingPx) / pitchPx).toInt().coerceIn(0, count - 1)

            fun lensAt(x: Float): Float = ((x - paddingPx) / pitchPx - 0.5f).coerceIn(0f, (count - 1).toFloat())

            // 鏡片：靜止時左右各多出 BarPadding，與膠囊邊緣留下等寬的間隙
            Box(
                Modifier
                    .offset { IntOffset((lens.value * pitchPx).roundToInt(), 0) }
                    .width(pitch + BarPadding * 2)
                    .fillMaxHeight()
                    .graphicsLayer {
                        scaleX = 1f + 0.06f * lift
                        scaleY = 1f + 0.06f * lift
                    }
                    .background(colors.onSurface.copy(alpha = 0.08f * lift).compositeOver(lensBase), CircleShape),
            )

            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = BarPadding)
                    .pointerInput(count, pitchPx) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            pressed = true
                            var lastX = down.position.x
                            var dragging = false
                            // 座標系是 Row 內部；換算回含內距的 BoxWithConstraints 座標
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                lastX = change.position.x
                                if (!dragging && abs(lastX - down.position.x) > viewConfiguration.touchSlop) {
                                    dragging = true
                                }
                                if (dragging) {
                                    change.consume()
                                    val target = lensAt(lastX + paddingPx)
                                    scope.launch { lens.animateTo(target, FollowSpring) }
                                }
                            }
                            pressed = false
                            val target = indexAt(lastX + paddingPx)
                            if (target != currentSelected) {
                                currentOnSelected(target)
                            } else {
                                scope.launch { lens.animateTo(target.toFloat(), TravelSpring) }
                            }
                        }
                    },
                horizontalArrangement = Arrangement.Start,
            ) {
                destinations.forEachIndexed { index, destination ->
                    // 鏡片蓋住多少，圖示與文字就染上多少主色
                    val cover = (1f - abs(lens.value - index)).coerceIn(0f, 1f)
                    DockItem(
                        destination = destination,
                        selected = index == selectedIndex,
                        tint = lerp(colors.onSurfaceVariant, colors.primary, cover),
                        onClick = { onSelected(index) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DockItem(
    destination: DockDestination,
    selected: Boolean,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxHeight()
            .padding(horizontal = 2.dp)
            // 觸控由整條導覽列處理；這裡只提供無障礙語意
            .clearAndSetSemantics {
                role = Role.Tab
                this.selected = selected
                contentDescription = destination.label
                onClick {
                    onClick()
                    true
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(destination.icon), contentDescription = null, tint = tint, modifier = Modifier.size(IconSize))
        Spacer(Modifier.height(LabelGap))
        Text(
            destination.label,
            color = tint,
            fontSize = 10.sp,
            lineHeight = 14.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
