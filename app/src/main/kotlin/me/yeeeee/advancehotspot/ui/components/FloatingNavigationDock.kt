package me.yeeeee.advancehotspot.ui.components

import android.view.HapticFeedbackConstants
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// 尺寸、彈簧與手感參數取自 FlClash（prism_ui 的 NavigationDock）
private val BarHeight = 62.dp
private val BarPadding = 4.dp
private val EdgeMargin = 21.dp
private val MaxItemExtent = 86.dp
private val IconSize = 28.dp
private val LabelGap = 2.dp

// 按下時整條導覽列長大的量
private val PressGrowth = 6.dp

// 按住時鏡片四周長大的量，以及鏡片下內容的放大比例
private val LensGrowth = 8.dp
private const val LENS_MAGNIFY = 0.16f

// 拖過頭時的橡皮筋：鏡片最多超出幾個目的地、導覽列最多被拉長多少
private const val OVERDRAG = 0.35f
private const val PULL_LIMIT = 7f / 32f
private const val PULL_STRETCH = 0.5f

// 果凍：依鏡片速度拉長、依煞車壓扁
private const val JELLY_SPEED = 3f
private const val JELLY_STRETCH = 0.2f
private const val JELLY_BRAKE = 25f
private const val JELLY_SQUASH = 0.15f
private const val JELLY_ASPECT = 1.4f
private const val JELLY_STIFFNESS = 100f
private const val JELLY_DAMPING = 2f * 0.4f * 10f // 2·ζ·√k
private const val JELLY_REST = 3e-3f

// 放開時依甩動速度往前推測的時間（秒）
private const val FLING_PROJECTION = 0.1f

// 觸碰光暈
private val GlowReach = 28.dp
private const val GLOW_GROWTH = 3f
private const val GLOW_PEAK = 0.06f
private const val GLOW_WASH = 0.7f

// 滑鼠懸停
private const val HOVER_ALPHA = 0.08f
private const val HOVER_SWELL = 0.4f
private const val HOVER_MAGNET = 0.2f

// Flutter 的 SpringDescription.withDurationAndBounce 換算成 Compose：ζ = 1 - bounce，k = (2π / T)²
private fun springOf(durationMs: Int, bounce: Float = 0f): AnimationSpec<Float> {
    val omega = 2f * Math.PI.toFloat() / (durationMs / 1000f)
    return spring(dampingRatio = 1f - bounce, stiffness = omega * omega)
}

private val TravelSpring = springOf(400, 0.15f)
private val LiftSpring = springOf(250)
private val TrackSpring = springOf(120)
private val HoverSpring = springOf(260, 0.18f)
private val FadeSpring = springOf(200)
private val GlowInSpring = springOf(100)
private val GlowOutSpring = springOf(500)
private val FollowSpring = spring<Float>(dampingRatio = 0.9f, stiffness = 1024f)
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

private fun rubberBand(overshoot: Float, limit: Float): Float {
    if (limit <= 0f) return 0f
    val pull = 1f - 1f / (abs(overshoot) * 0.55f / limit + 1f)
    return limit * pull * sign(overshoot)
}

private fun saturate(value: Float): Float = 1f - 2f / (exp(2f * value) + 1f)

/**
 * 仿 FlClash 的液態玻璃浮動導覽列。
 *
 * - 膠囊本身是一片玻璃：折射、模糊並染色 [backdrop]（畫面內容）。
 * - 選取的分頁底下有一片鏡片；按下時鏡片浮起成透明的玻璃透鏡並放大底下的內容。
 * - 可以按住左右拖曳鏡片，拖過頭有橡皮筋效果，快速移動時鏡片會像果凍一樣伸縮。
 * - 拖曳經過每個分頁時有細微的震動回饋；用滑鼠時有懸停高光。
 *
 * @param backdrop 錄下畫面內容的圖層；為 null 時改用不透明的表面色。
 */
@Composable
fun FloatingNavigationDock(
    destinations: List<DockDestination>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    backdrop: GraphicsLayer?,
    modifier: Modifier = Modifier,
) {
    val count = destinations.size
    val lastIndex = (count - 1).coerceAtLeast(0)
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val density = LocalDensity.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val currentSelected by rememberUpdatedState(selectedIndex)
    val currentOnSelected by rememberUpdatedState(onSelected)

    val lens = remember { Animatable(selectedIndex.toFloat()) }
    val lift = remember { Animatable(0f) }
    val swell = remember { Animatable(0f) }
    val stretch = remember { Animatable(0f) }
    val glow = remember { Animatable(0f) }
    val glowSpread = remember { Animatable(0f) }
    val hover = remember { Animatable(0f) }
    val hoverShow = remember { Animatable(0f) }
    var jelly by remember { mutableFloatStateOf(0f) }
    var glowAt by remember { mutableStateOf(Offset.Zero) }
    var pressing by remember { mutableStateOf(false) }
    var hovering by remember { mutableStateOf(false) }
    var barSize by remember { mutableStateOf(IntSize.Zero) }
    var barOrigin by remember { mutableStateOf(Offset.Zero) }
    val barGlass = remember { LiquidGlassEffect() }
    val lensGlass = remember { LiquidGlassEffect() }

    val padPx = with(density) { BarPadding.toPx() }

    fun pitchOf(width: Int): Float = ((width - padPx * 4) / count.coerceAtLeast(1)).coerceAtLeast(1f)

    LaunchedEffect(selectedIndex) {
        if (!pressing) lens.animateTo(selectedIndex.toFloat(), TravelSpring)
    }

    // 果凍：依鏡片的速度與減速度驅動一個欠阻尼彈簧，靜止時停止逐格計算
    LaunchedEffect(Unit) {
        var velocity = 0f
        var previousLensVelocity = 0f
        var lastFrame = 0L
        while (true) {
            snapshotFlow { lens.isRunning }.first { it }
            lastFrame = 0L
            while (lens.isRunning || abs(jelly) > JELLY_REST || abs(velocity) > JELLY_REST) {
                withFrameNanos { now ->
                    val dt = if (lastFrame == 0L) 1f / 120f else ((now - lastFrame) / 1e9f).coerceIn(1e-3f, 0.05f)
                    lastFrame = now
                    val lensVelocity = lens.velocity
                    val acceleration = (lensVelocity - previousLensVelocity) / dt
                    previousLensVelocity = lensVelocity
                    val braking = max(0f, -acceleration * sign(lensVelocity))
                    val target = JELLY_STRETCH * saturate(abs(lensVelocity) / JELLY_SPEED) -
                        JELLY_SQUASH * saturate(braking / JELLY_BRAKE)
                    velocity += (-JELLY_STIFFNESS * (jelly - target) - JELLY_DAMPING * velocity) * dt
                    jelly += velocity * dt
                }
            }
            jelly = 0f
            velocity = 0f
            previousLensVelocity = 0f
        }
    }

    // 換算：手指在導覽列上的 x → 鏡片位置（以目的地為單位），超出兩端時以橡皮筋收斂
    fun positionAt(x: Float, width: Int): Float {
        val position = (x - padPx * 2) / pitchOf(width) - 0.5f
        return when {
            position < 0f -> rubberBand(position, OVERDRAG)
            position > lastIndex -> lastIndex + rubberBand(position - lastIndex, OVERDRAG)
            else -> position
        }
    }

    fun indexAt(position: Float): Int = position.roundToInt().coerceIn(0, lastIndex)

    fun tick() {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    var liftDrop by remember { mutableStateOf<Job?>(null) }

    // 鏡片到位後才放下
    fun dropLiftWhenSettled(target: Float) {
        liftDrop?.cancel()
        liftDrop = scope.launch {
            snapshotFlow { abs(lens.value - target) <= 0.04f }.first { it }
            lift.animateTo(0f, LiftSpring)
        }
    }

    val container = if (dark) colors.surfaceContainerHigh else colors.surfaceBright
    val barStyle = if (dark) {
        GlassStyle(
            tint = colors.surfaceContainer.copy(alpha = 0.48f),
            blur = with(density) { 10.dp.toPx() },
            refraction = with(density) { 18.dp.toPx() },
            bevel = with(density) { 20.dp.toPx() },
            saturation = 1.925f,
            rim = 0.12f,
            rimWidth = with(density) { 1.3.dp.toPx() },
        )
    } else {
        GlassStyle(
            tint = Color(0x93F4F4F4),
            blur = with(density) { 10.dp.toPx() },
            refraction = with(density) { 18.dp.toPx() },
            bevel = with(density) { 20.dp.toPx() },
            saturation = 2.1f,
            rim = 0.35f,
            rimWidth = with(density) { 1.3.dp.toPx() },
        )
    }
    // 靜止時的鏡片：深色下沉入膠囊，淺色是淡淡的次要色
    val platter = if (dark) {
        lerp(Color.Black, colors.secondary, 0.1f).copy(alpha = 0.85f)
    } else {
        colors.secondaryContainer.copy(alpha = 0.85f)
    }
    val solidLens = if (dark) colors.surfaceContainerHighest else colors.surfaceContainer
    val hoverColor = colors.onSurface
    val shadowStrength = if (dark) 3f else 1f

    Box(
        modifier
            .fillMaxWidth()
            .padding(start = EdgeMargin, end = EdgeMargin, bottom = dockBottomMargin()),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .widthIn(max = MaxItemExtent * count + BarPadding * 4)
                .fillMaxWidth()
                .height(BarHeight)
                .onGloballyPositioned {
                    barSize = it.size
                    barOrigin = it.positionInRoot()
                }
                .graphicsLayer {
                    val growth = PressGrowth.toPx() * swell.value
                    val pullScale = 1f + abs(stretch.value) / size.width.coerceAtLeast(1f) * PULL_STRETCH
                    scaleX = (1f + growth / size.width.coerceAtLeast(1f)) * pullScale
                    scaleY = 1f + growth / size.height.coerceAtLeast(1f)
                    translationX = stretch.value * 0.5f
                }
                .dropShadow(
                    CircleShape,
                    Shadow(24.dp, Color.Black.copy(alpha = 0.08f * shadowStrength), offset = DpOffset(0.dp, 8.dp)),
                )
                .dropShadow(
                    CircleShape,
                    Shadow(3.dp, Color.Black.copy(alpha = 0.04f * shadowStrength), offset = DpOffset(0.dp, 1.dp)),
                )
                .pointerInput(count) {
                    // 滑鼠懸停：只處理非觸控的移動
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            if (change.type == PointerType.Touch || change.pressed) continue
                            when (event.type) {
                                PointerEventType.Enter, PointerEventType.Move -> {
                                    val position = positionAt(change.position.x, size.width)
                                    val index = indexAt(position)
                                    val target = index + (position - index) * HOVER_MAGNET
                                    if (!hovering) {
                                        hovering = true
                                        scope.launch { hover.snapTo(target) }
                                    } else {
                                        scope.launch { hover.animateTo(target, HoverSpring) }
                                    }
                                    scope.launch { hoverShow.animateTo(1f, FadeSpring) }
                                    if (!pressing) scope.launch { swell.animateTo(HOVER_SWELL, HoverSpring) }
                                }
                                PointerEventType.Exit -> {
                                    hovering = false
                                    scope.launch { hoverShow.animateTo(0f, FadeSpring) }
                                    if (!pressing) scope.launch { swell.animateTo(0f, HoverSpring) }
                                }
                            }
                        }
                    }
                }
                .pointerInput(count) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        pressing = true
                        liftDrop?.cancel()
                        var pressedIndex = indexAt(positionAt(down.position.x, size.width))
                        var dragging = false
                        var committed = false
                        glowAt = down.position
                        scope.launch { hoverShow.animateTo(0f, FadeSpring) }
                        scope.launch { swell.animateTo(1f, PressSpring) }
                        scope.launch { glowSpread.snapTo(0f) }
                        scope.launch { glow.animateTo(1f, GlowInSpring) }
                        scope.launch { lift.animateTo(1f, LiftSpring) }
                        scope.launch { lens.animateTo(pressedIndex.toFloat(), TravelSpring) }
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    committed = true
                                    tracker.addPosition(change.uptimeMillis, change.position)
                                    break
                                }
                                tracker.addPosition(change.uptimeMillis, change.position)
                                val x = change.position.x
                                glowAt = change.position
                                if (!dragging && abs(x - down.position.x) > viewConfiguration.touchSlop) dragging = true
                                if (!dragging) continue
                                change.consume()
                                val overshoot = x - x.coerceIn(0f, size.width.toFloat())
                                val limit = min(size.width, size.height) * PULL_LIMIT
                                scope.launch { stretch.animateTo(rubberBand(overshoot, limit), TrackSpring) }
                                val position = positionAt(x, size.width)
                                scope.launch { lens.animateTo(position, FollowSpring) }
                                val index = indexAt(position)
                                if (index != pressedIndex) {
                                    tick()
                                    pressedIndex = index
                                }
                            }
                        } finally {
                            pressing = false
                            if (committed && dragging) {
                                // 甩動：依速度往前推測，最多多走一格
                                val lead = tracker.calculateVelocity().x / pitchOf(size.width) * FLING_PROJECTION
                                val flung = (lens.targetValue + lead).roundToInt()
                                    .coerceIn(pressedIndex - 1, pressedIndex + 1)
                                    .coerceIn(0, lastIndex)
                                if (flung != pressedIndex) {
                                    tick()
                                    pressedIndex = flung
                                }
                            }
                            val target = (if (committed) pressedIndex else currentSelected).toFloat()
                            scope.launch { lens.animateTo(target, TravelSpring) }
                            dropLiftWhenSettled(target)
                            scope.launch { glow.animateTo(0f, GlowOutSpring) }
                            scope.launch { glowSpread.animateTo(1f, GlowOutSpring) }
                            scope.launch { stretch.animateTo(0f, PressSpring) }
                            scope.launch { swell.animateTo(if (hovering) HOVER_SWELL else 0f, PressSpring) }
                            if (committed && pressedIndex != currentSelected) currentOnSelected(pressedIndex)
                        }
                    }
                }
                .drawWithCache {
                    val barLayer = obtainGraphicsLayer()
                    val glassLayer = obtainGraphicsLayer()
                    val lensLayer = obtainGraphicsLayer()
                    val margin = 48.dp.toPx()
                    val lensMargin = 16.dp.toPx()
                    val radius = CornerRadius(size.height / 2f)
                    val pad = BarPadding.toPx()
                    val innerHeight = size.height - pad * 2
                    val pitch = (size.width - pad * 4) / count.coerceAtLeast(1)
                    val restLensWidth = pitch + pad * 2

                    fun lensRect(): Rect {
                        val growth = LensGrowth.toPx() * 2f * lift.value
                        val width = (restLensWidth + growth) * (1f + jelly)
                        val height = (innerHeight + growth) * (1f - jelly * JELLY_ASPECT)
                        val centerX = pad * 2 + (lens.value + 0.5f) * pitch
                        return Rect(Offset(centerX - width / 2f, size.height / 2f - height / 2f), Size(width, height))
                    }

                    onDrawWithContent {
                        val glassOn = backdrop != null
                        val liftValue = lift.value.coerceIn(0f, 1f)
                        val rect = lensRect()
                        val glowValue = glow.value
                        val glowStyle = GlassGlow(
                            center = glowAt,
                            strength = GLOW_PEAK * glowValue * glowValue,
                            reach = GlowReach.toPx() * (1f + GLOW_GROWTH * glowSpread.value),
                            wash = GLOW_WASH,
                        )

                        if (backdrop != null) {
                            glassLayer.renderEffect = barGlass.renderEffect(
                                barStyle,
                                shapeOrigin = Offset(margin, margin),
                                shapeSize = size,
                                glow = glowStyle.copy(center = glowStyle.center + Offset(margin, margin)),
                            )
                            glassLayer.record(IntSize((size.width + margin * 2).toInt(), (size.height + margin * 2).toInt())) {
                                translate(margin - barOrigin.x, margin - barOrigin.y) { drawLayer(backdrop) }
                            }
                        }
                        barLayer.record {
                            if (glassOn) {
                                translate(-margin, -margin) { drawLayer(glassLayer) }
                                drawRoundRect(platter.copy(alpha = platter.alpha * (1f - liftValue)), rect.topLeft, rect.size, CornerRadius(rect.height / 2f))
                            } else {
                                drawRoundRect(container, cornerRadius = radius)
                                drawRoundRect(
                                    hoverColor.copy(alpha = 0.08f * liftValue).compositeOver(solidLens),
                                    rect.topLeft,
                                    rect.size,
                                    CornerRadius(rect.height / 2f),
                                )
                            }
                            val hoverAlpha = HOVER_ALPHA * hoverShow.value
                            if (hoverAlpha > 0f) {
                                val left = pad * 2 + (hover.value + 0.5f) * pitch - restLensWidth / 2f
                                drawRoundRect(
                                    hoverColor.copy(alpha = hoverAlpha),
                                    Offset(left, pad),
                                    Size(restLensWidth, innerHeight),
                                    CornerRadius(innerHeight / 2f),
                                )
                            }
                            this@onDrawWithContent.drawContent()
                        }
                        drawLayer(barLayer)

                        // 浮起的玻璃透鏡：折射並放大底下的畫面與導覽列
                        if (backdrop != null && liftValue > 0.01f) {
                            lensLayer.renderEffect = lensGlass.renderEffect(
                                GlassStyle(
                                    tint = Color.Transparent,
                                    refraction = 6.dp.toPx(),
                                    bevel = 11.dp.toPx(),
                                    magnify = 1f + LENS_MAGNIFY * liftValue,
                                    dispersion = 0.12f,
                                    brightness = if (dark) 1f + 0.15f * liftValue else 1f,
                                    rim = if (dark) 0.3f else 0.6f,
                                    rimWidth = 1.1.dp.toPx(),
                                    opacity = liftValue,
                                ),
                                shapeOrigin = Offset(lensMargin, lensMargin),
                                shapeSize = rect.size,
                            )
                            val layerSize = IntSize((rect.width + lensMargin * 2).toInt(), (rect.height + lensMargin * 2).toInt())
                            lensLayer.record(layerSize) {
                                translate(lensMargin - rect.left, lensMargin - rect.top) {
                                    translate(-barOrigin.x, -barOrigin.y) { drawLayer(backdrop) }
                                    drawLayer(barLayer)
                                }
                            }
                            translate(rect.left - lensMargin, rect.top - lensMargin) { drawLayer(lensLayer) }
                        }
                    }
                },
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = BarPadding * 2, vertical = BarPadding)) {
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
