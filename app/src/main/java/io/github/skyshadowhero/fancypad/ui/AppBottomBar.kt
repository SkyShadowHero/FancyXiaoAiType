package io.github.skyshadowhero.fancypad.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.neoruaa.glasmio.icons.SfSymbol
import io.github.neoruaa.glasmio.ui.GlasmioNavigationBar
import io.github.neoruaa.glasmio.ui.GlasmioTabItem
import io.github.skyshadowhero.fancypad.ui.ds.DsTheme
import io.github.skyshadowhero.fancypad.ui.ds.isGlasmio
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.basic.Icon
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

private val BottomBarItemHeight = 50.dp
private val BottomBarPillInset = 4.dp

/** 指示器滑动弹簧：临界阻尼，快速到位不回弹（dynamic-youth 同款） */
private val BottomBarSlideSpec = spring<Float>(dampingRatio = 1f, stiffness = 900f, visibilityThreshold = 0.001f)

/** 按压缩放弹簧 */
private val BottomBarPressSpec = spring<Float>(dampingRatio = 1f, stiffness = 1000f)

/** 松手后横向偏移回弹的参数 */
private val BottomBarPanSpec = spring<Float>(dampingRatio = 1f, stiffness = 300f, visibilityThreshold = 0.5f)

/** 横向跟手偏移的上限 */
private val BottomBarLeanMax = 4.dp

private fun Density.bottomBarLean(offsetPx: Float, widthPx: Float): Float {
    if (widthPx <= 0f) return 0f
    val fraction = (offsetPx / widthPx).coerceIn(-1f, 1f)
    return BottomBarLeanMax.toPx() * sign(fraction) * EaseOut.transform(abs(fraction))
}

/** 指示器的全部动画状态：只驱动图层，不参与重组。 */
private class BottomBarIndicatorState(initialIndex: Int) {
    val position = Animatable(initialIndex.toFloat())
    val pressScale = Animatable(1f)
    val alpha = Animatable(1f)
    val pan = Animatable(0f)
    val pressed = mutableStateOf(false)
}

private fun indicatorScaleX(velocity: Float): Float {
    val v = (velocity / 10f).coerceIn(-0.2f, 0.2f)
    return 1f / (1f - v * 0.75f)
}

private fun indicatorScaleY(velocity: Float): Float {
    val v = (velocity / 10f).coerceIn(-0.2f, 0.2f)
    return 1f - v * 0.25f
}

/** 底栏占用的高度（页面底部让位用）。 */
val BottomBarReservedHeight = BottomBarItemHeight + 20.dp

/**
 * 底栏：抄自 `dynamic-youth-kmp` 的 `AppBottomBar`（`fix/navigation-glass` 分支）。
 *
 * 结构：底部渐变遮罩 → 限宽 400dp 的胶囊条（模糊 8dp）→ 滑块胶囊 + 两层内容
 * （下层常规色、上层强调色裁成胶囊形状）→ 拖动只认「按在胶囊上」。
 * 动画参数逐项照抄：滑动 `spring(1f, 900f, 0.001f)`、按压 `1.12 / alpha 0.88`、
 * 横向跟手 4dp `EaseOut`、松手 `spring(1f, 300f, 0.5f)` 弹回。
 */
@Composable
fun AppBottomBar(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: LayerBackdrop? = null,
) {
    val tabs = RootTab.entries
    if (isGlasmio) {
        val symbols = listOf(SfSymbol.House, SfSymbol.Settings, SfSymbol.InfoCircle)
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(start = 68.dp, end = 68.dp, top = 44.dp, bottom = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            GlasmioNavigationBar(
                items = tabs.mapIndexed { index, tab ->
                    GlasmioTabItem(key = tab, label = tab.label, symbol = symbols[index % symbols.size])
                },
                selectedKey = tabs[selectedIndex.coerceIn(0, tabs.lastIndex)],
                onSelected = { tab -> onSelect(tabs.indexOf(tab)) },
                onExpand = {},
                compactProgress = remember { mutableStateOf(0f) },
                compactSize = 64.dp,
                // 平板/横屏下不要让玻璃底栏拉满整行，与 Miuix 皮肤保持同样的最大宽度
                modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth(),
            )
        }
        return
    }

    val colors = DsTheme.colorScheme
    val scrim = remember(colors.surface) {
        Brush.verticalGradient(
            0f to Color.Transparent,
            0.30f to colors.onBackground.copy(alpha = 0.025f),
            1f to colors.onBackground.copy(alpha = 0.06f),
        )
    }
    val state = remember { BottomBarIndicatorState(selectedIndex) }
    // 指示器动画期间把模糊半径压小：大半径的 backdrop 采样在切换 tab 的尾帧最费时，
    // 停下后自动恢复，肉眼看不出差别。
    val animating by remember(state) {
        derivedStateOf {
            state.position.isRunning || state.pressScale.isRunning || state.pan.isRunning
        }
    }
    val blurRadius = if (animating) 0f else 34f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(scrim)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 68.dp, end = 68.dp, top = 44.dp, bottom = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .fillMaxWidth()
                .graphicsLayer { translationX = bottomBarLean(state.pan.value, size.width) }
                // 轻微投影，给悬浮感；不要太大
                .shadow(
                    elevation = 6.dp,
                    shape = CircleShape,
                    ambientColor = Color.Black.copy(alpha = 0.10f),
                    spotColor = Color.Black.copy(alpha = 0.14f),
                )
                .clip(CircleShape)
                // 0.5dp 白色描边（深色模式下更暗一些）
                .border(
                    width = 0.5.dp,
                    color = if (colors.isDark) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.75f),
                    shape = CircleShape,
                )
                .then(
                    // 动画期间**完全摘掉模糊**：只要 backdrop 失效（切 tab 时页面每帧都在动），
                    // textureBlur 就会整条重采样一遍，即使 radius = 0 也照样跑一遍 effect，
                    // 这就是之前「底栏自己掉帧」的来源。静止时才挂上模糊。
                    if (backdrop == null) {
                        Modifier.background(
                            if (colors.isDark) Color(0xFF1C1C1E).copy(alpha = 0.72f)
                            else Color(0xFFF2F2F5).copy(alpha = 0.55f),
                        )
                    } else {
                        Modifier.textureBlur(
                            backdrop = backdrop,
                            shape = CircleShape,
                            blurRadius = 20f,
                            colors = top.yukonga.miuix.kmp.blur.BlurDefaults.blurColors(
                                blendColors = listOf(
                                    top.yukonga.miuix.kmp.blur.BlendColorEntry(
                                        color = if (colors.isDark) Color(0xFF1C1C1E).copy(alpha = 0.72f)
                                        else Color(0xFFF2F2F5).copy(alpha = 0.55f),
                                    ),
                                ),
                            ),
                        )
                    },
                ),
        ) {
            BottomBarContent(state = state, items = tabs, selectedIndex = selectedIndex, onSelect = onSelect)
        }
    }
}

@Composable
private fun BottomBarContent(
    state: BottomBarIndicatorState,
    items: List<RootTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val colors = DsTheme.colorScheme
    val scope = rememberCoroutineScope()
    val capsule = remember { Path() }
    val matrix = remember { Matrix() }

    val currentIndex by rememberUpdatedState(selectedIndex)
    val currentOnSelect by rememberUpdatedState(onSelect)

    LaunchedEffect(selectedIndex) {
        state.position.animateTo(selectedIndex.toFloat(), BottomBarSlideSpec)
    }

    LaunchedEffect(state) {
        snapshotFlow { state.pressed.value }.collectLatest { pressed ->
            coroutineScope {
                launch {
                    state.pressScale.animateTo(
                        targetValue = if (pressed) 0.92f else 1f,
                        animationSpec = BottomBarPressSpec,
                    )
                }
                launch {
                    state.alpha.animateTo(
                        targetValue = if (pressed) 0.88f else 1f,
                        animationSpec = tween(140),
                    )
                }
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 0.dp)
            .pointerInput(items.size) {
                val lastSlot = (items.size - 1).toFloat()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slotPx = size.width.toFloat() / items.size
                    val insetPx = BottomBarPillInset.toPx()
                    val pillLeft = state.position.value * slotPx + insetPx
                    val onIndicator = down.position.x in pillLeft..(pillLeft + slotPx - insetPx * 2)
                    if (onIndicator) state.pressed.value = true

                    val startSlot = state.position.value
                    var travelled = 0f
                    val slop = awaitTouchSlopOrCancellation(down.id) { change, over ->
                        change.consume()
                        travelled = over.x
                    }
                    if (slop == null) {
                        state.pressed.value = false
                        return@awaitEachGesture
                    }

                    drag(slop.id) { change ->
                        travelled += change.positionChange().x
                        change.consume()
                        val slot = (startSlot + travelled / slotPx).coerceIn(0f, lastSlot)
                        scope.launch {
                            if (onIndicator) state.position.snapTo(slot)
                            state.pan.snapTo(travelled)
                        }
                    }

                    val released = (startSlot + travelled / slotPx).coerceIn(0f, lastSlot)
                    val target = released.roundToInt().coerceIn(0, items.size - 1)
                    if (onIndicator) {
                        if (target != currentIndex) currentOnSelect(target)
                        scope.launch { state.position.animateTo(target.toFloat(), BottomBarSlideSpec) }
                    }
                    scope.launch { state.pan.animateTo(0f, BottomBarPanSpec) }
                    state.pressed.value = false
                }
            },
    ) {
        val slotWidth = maxWidth / items.size
        val slotPx = with(LocalDensity.current) { slotWidth.toPx() }
        val pillWidth = slotWidth - BottomBarPillInset * 2
        val pillHeight = BottomBarItemHeight - 7.dp

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(pillWidth)
                .height(pillHeight)
                .graphicsLayer {
                    translationX = state.position.value * slotPx + BottomBarPillInset.toPx()
                    scaleX = indicatorScaleX(state.position.velocity) * state.pressScale.value
                    scaleY = indicatorScaleY(state.position.velocity) * state.pressScale.value
                    alpha = state.alpha.value
                }
                .clip(CircleShape)
                .drawBehind {
                    // 按下时稍稍变灰（读 pressScale，绘制阶段求值，不触发重组）
                    val p = ((1f - state.pressScale.value) / 0.08f).coerceIn(0f, 1f)
                    val base = colors.onBackground.copy(alpha = 0.06f)
                    val gray = colors.onBackground.copy(alpha = 0.06f + 0.06f * p)
                    drawRoundRect(
                        color = if (p > 0f) gray else base,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
                    )
                },
        )

        Row(
            modifier = Modifier.fillMaxWidth().height(BottomBarItemHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                BottomBarItem(
                    modifier = Modifier.weight(1f),
                    icon = item.icon,
                    label = item.label,
                    onClick = { onSelect(index) },
                )
            }
        }

        // 主题色版本叠在上层，裁成指示器形状：变色范围跟着胶囊走
        Row(
            modifier = Modifier
                .clearAndSetSemantics {}
                .fillMaxWidth()
                .height(BottomBarItemHeight)
                .drawWithContent {
                    val w = pillWidth.toPx()
                    val h = pillHeight.toPx()
                    val left = state.position.value * slotPx + BottomBarPillInset.toPx()
                    val top = (size.height - h) / 2f
                    val cx = left + w / 2f
                    val cy = top + h / 2f
                    val sx = indicatorScaleX(state.position.velocity) * state.pressScale.value
                    val sy = indicatorScaleY(state.position.velocity) * state.pressScale.value

                    capsule.reset()
                    capsule.addRoundRect(
                        RoundRect(
                            left = left,
                            top = top,
                            right = left + w,
                            bottom = top + h,
                            cornerRadius = CornerRadius(h / 2f, h / 2f),
                        ),
                    )
                    matrix.reset()
                    matrix.translate(cx, cy)
                    matrix.scale(sx, sy)
                    matrix.translate(-cx, -cy)
                    capsule.transform(matrix)

                    clipPath(capsule) { this@drawWithContent.drawContent() }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                BottomBarItemContent(
                    modifier = Modifier.weight(1f),
                    icon = item.icon,
                    label = item.label,
                    color = colors.accent,
                )
            }
        }
    }
}

@Composable
private fun BottomBarItem(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    BottomBarItemContent(
        modifier = modifier
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            ),
        icon = icon,
        label = label,
        color = DsTheme.colorScheme.onBackground,
    )
}

@Composable
private fun BottomBarItemContent(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    color: Color,
) {
    Column(
        modifier = modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(20.dp),
            tint = color,
        )
        Spacer(Modifier.height(0.dp))
        androidx.compose.material3.Text(
            text = label,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            fontSize = 11.sp,
            color = color,
            maxLines = 1,
        )
    }
}
