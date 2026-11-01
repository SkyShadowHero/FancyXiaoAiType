package io.github.skyshadowhero.fancypad.ui.ds

import androidx.compose.foundation.background
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.LocalIndication
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.neoruaa.glasmio.glass.GlassSurface
import io.github.neoruaa.glasmio.glass.GlassSurfaceStyle
import io.github.neoruaa.glasmio.glass.NavigationGlassMaterial
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol
import io.github.neoruaa.glasmio.ui.GlasmioSegmentedControl
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import kotlinx.coroutines.delay

/**
 * iOS 风格顶栏：标题真正居中、左侧返回、右侧动作。
 *
 * 高度固定 56dp（不含状态栏），两个方向都一样 —— 之前按「一级页大标题 / 二级页小标题」
 * 切换会让内容区高度在导航时突变（标题消失、内容闪一下再上移），现在统一高度没有这个跳变。
 * 状态栏高度作为内边距放在背景**里面**，所以状态栏那一块也是顶栏底色（沉浸式）。
 * 右侧动作单独给 44dp 行高，重启按钮 36dp，两个方向都不会被行高切掉。
 */
@Composable
fun GlasmioNavBar(
    title: String,
    subtitle: String,
    canGoBack: Boolean,
    onBack: () -> Unit,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    val colors = DsTheme.colorScheme
    // 顶栏自己不画底色：模糊层由 AppShell 用 Miuix 的 progressive blur 铺在它下面
    //（内容被录进 layerBackdrop，顶栏再采样它）。这里留透明，模糊才透得出来。
    Column(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            // 返回按钮：完全照 demo 的 header action 出现/消失 ——
            // alpha = p、scale = 0.2 + 0.8p、blur = 10dp * (1 - p)（见 glassActionAppear）。
            val backProgress by animateFloatAsState(
                targetValue = if (canGoBack) 1f else 0f,
                animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
                label = "backButton",
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp)
                    .glassActionAppear(backProgress),
            ) {
                GlasmioIconButton(onClick = onBack, enabled = canGoBack) {
                    // demo 里返回箭头就是工具栏的 glass 图标按钮：SfIcon(ChevronBack, mirrored = true)，
                    // 尺寸用 Glasmio 自己的 iconButtonSize，不写死。
                    SfIcon(
                        symbol = SfSymbol.ChevronBack,
                        contentDescription = "返回",
                        mirrored = true,
                        tint = colors.accent,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 72.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    style = DsTheme.textStyles.headline,
                    color = colors.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = DsTheme.textStyles.footnote1,
                        color = colors.onBackgroundVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp)
                    .height(44.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }
}

/** 作用域切换：iOS 的分段控件，样式跟着 Glasmio 主题走。 */
@Composable
fun <T> DsSegmentedRow(
    items: List<Pair<T, String>>,
    selected: T,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlasmioSegmentedControl(
        items = items,
        selected = selected,
        onSelected = onSelected,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        height = 34.dp,
    )
}

/**
 * 平板的左栏（macOS 26 / Liquid Glass 的 sidebar）。
 *
 * 参照 https://github.com/NucleusFramework/compose-macos-26-ui → `components/Sidebar.kt`：
 * - 一块内缩 6dp 的圆角玻璃面板（18dp 圆角），交给 Glasmio 的 [GlassSurface] 真正采样
 *   backdrop 做模糊/折射，而不是自己调一层半透明白；
 * - 选中项是**中性灰底**（浅色黑 10%、深色白 10%）+ 强调色文字，不是强调色填充；
 * - 行高 34dp、圆角 6dp，行内文字跟内容区的行标题同号（Glasmio body ≈17sp）；
 * - 顶部是分组标题：13sp 半粗、55% 内容色；
 * - 没有对号、没有 chevron、按下不缩放。
 */
@Composable
fun <T> DsSidebar(
    title: String,
    items: List<Pair<T, String>>,
    selected: T,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colorScheme
    // 侧栏就用 iOS 的「分组卡片 + 选项行」：和右侧内容区同一套 Glasmio 卡片，
    // 不做 macOS 的侧栏面板/滑动指示器。选中项打勾 + 强调色文字。
    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(start = 12.dp, end = 6.dp, top = 28.dp, bottom = 12.dp),
    ) {
        Text(
            text = title,
            style = androidx.compose.ui.text.TextStyle(
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = colors.onBackgroundVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // 侧栏的点按遮罩不要圆角：这一段单独把 LocalIndication 换成 radius = 0 的版本，
        // 不影响其它页面（其它行仍是 10dp 圆角）。
        CompositionLocalProvider(
            LocalIndication provides rememberDsIndication(colors.onBackground, 0.dp),
        ) {
            GlasmioGroupedList {
                items.forEachIndexed { index, (key, label) ->
                    val active = key == selected
                    GlasmioListRow(
                        title = label,
                        showTopSeparator = index > 0,
                        showDisclosure = false,
                        titleColor = if (active) colors.accent else Color.Unspecified,
                        onClick = { onSelected(key) },
                        trailing = if (active) {
                            {
                                Text(
                                    text = "✓",
                                    style = androidx.compose.ui.text.TextStyle(fontSize = 17.sp),
                                    color = colors.accent,
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

/** Glasmio 侧的 Snackbar：底部一枚胶囊。 */
@Composable
fun GlasmioSnackbarHost(hostState: SnackbarHostState) {
    val data = hostState.currentSnackbarData ?: return
    LaunchedEffect(data) {
        delay(3000)
        data.dismiss()
    }
    val colors = DsTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Row(
            modifier = Modifier
                .background(colors.onBackground.copy(alpha = 0.88f), CircleShape)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = data.visuals.message,
                style = DsTheme.textStyles.body2,
                color = colors.surface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 工具栏动作的出现/消失：照 Glasmio demo 的 header action 写法 ——
 * `alpha = p`、`scale = 0.2 + 0.8p`、`blur = 10dp * (1 - p)`（`GlasmioNavigationScaffold` 里那段）。
 * 所以它不像普通淡入，而是从很小、很糊的一团「炸」出来，消失时缩回去并糊掉。
 */
@Composable
fun Modifier.glassActionAppear(progress: Float): Modifier = graphicsLayer {
    val p = progress.coerceIn(0f, 1f)
    alpha = p
    val s = 0.2f + 0.8f * p
    scaleX = s
    scaleY = s
    // 刻意不用 renderEffect 做模糊：RenderEffect 会按**矩形**裁图层，
    // 胶囊按钮在消失过程中会被裁成方块（就是你说的「按钮变方了」）。
    // 缩放 0.2→1 + 淡入淡出已经足够「炸」，且形状始终是圆角。
}