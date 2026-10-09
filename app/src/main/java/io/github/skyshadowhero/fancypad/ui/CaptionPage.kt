package io.github.skyshadowhero.fancypad.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.skyshadowhero.fancypad.PrefKeys
import io.github.skyshadowhero.fancypad.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/**
 * 控制菜单里一个按钮的界面信息。
 *
 * [key] 必须与 `PrefKeys.CB_*` 一致（存偏好用）；[short] 是横排拖动条上显示的短标签；
 * [icon] 是从 MiuiWMShellResources 提取来的真实图标（见 tools/caption/gen_caption_icons.py）。
 */
private data class MenuButton(
    val key: String,
    val label: String,
    val short: String,
    val icon: Int,
)

private val FRAMEWORK_MENU_BUTTONS = listOf(
    MenuButton(PrefKeys.CB_FULLSCREEN, "全屏", "全屏", R.drawable.cap_btn_fullscreen),
    MenuButton(PrefKeys.CB_CASTING, "投屏", "投屏", R.drawable.cap_btn_casting),
    MenuButton(PrefKeys.CB_SPLIT_LEFT, "分屏·左/上", "分屏左", R.drawable.cap_btn_split_left),
    MenuButton(PrefKeys.CB_SPLIT_RIGHT, "分屏·右/下", "分屏右", R.drawable.cap_btn_split_right),
    MenuButton(PrefKeys.CB_FREEFORM, "小窗", "小窗", R.drawable.cap_btn_freeform),
    MenuButton(PrefKeys.CB_NEW_WINDOW, "新窗口", "新窗口", R.drawable.cap_btn_new_window),
    MenuButton(PrefKeys.CB_CLOSE, "关闭小窗", "关闭小窗", R.drawable.cap_btn_close),
)

/** 顺序列表里可能出现的全部按钮（含红色那个，它复用关闭的图标、界面里染红）。 */
private val ALL_MENU_BUTTONS = FRAMEWORK_MENU_BUTTONS + MenuButton(
    PrefKeys.CB_FORCE_CLOSE, "强制关闭", "强关", R.drawable.cap_btn_close,
)

private fun splitPref(spec: String): List<String> =
    spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }

/** 「关闭小窗」按钮是否启用 —— 「× 改成 −」依赖它。 */
private fun closeButtonEnabled(hidden: List<String>) = PrefKeys.CB_CLOSE !in hidden

/** 「新窗口」按钮是否启用 —— 「始终显示新窗口」依赖它。 */
private fun newWindowButtonEnabled(hidden: List<String>) = PrefKeys.CB_NEW_WINDOW !in hidden

/** 横排拖动条：每格宽度、格间距、图标大小。 */
private val STRIP_ITEM_WIDTH = 68.dp
private val STRIP_ITEM_GAP = 6.dp
private val STRIP_ICON_SIZE = 22.dp

/** 红色「强制关闭」在界面里的强调色（和 Hook 侧那个 × 同色）。 */
private val FORCE_CLOSE_RED = Color(0xFFE53935)

/** 三个页面共用的列表外壳（吸顶留白 + 卡片间距一致）。 */
@Composable
private fun CaptionList(
    padding: PaddingValues,
    scaffoldPadding: PaddingValues,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = Modifier.padding(padding),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = scaffoldPadding.calculateTopPadding() + 8.dp,
            bottom = scaffoldPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/**
 * 总开关关掉时的提示卡。
 *
 * 不再写「去功能页打开…」—— 每一页顶上都有总开关本身，指路反而是错的。
 */
@Composable
private fun CaptionDisabledHint() {
    Card {
        Text(
            text = "小窗控制器总开关已关，这一页的设置都不生效。",
            modifier = Modifier.padding(16.dp),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
        )
    }
}

/** 总开关那一张卡（只在「功能」页出现）。 */
@Composable
private fun CaptionMasterCard(uiState: AppUiState) {
    Card {
        SwitchPreference(
            checked = uiState.captionEnabled,
            onCheckedChange = { checked ->
                uiState.captionEnabled = checked
                uiState.save { e -> e.putBoolean(PrefKeys.CAPTION_ENABLED, checked) }
            },
            title = "启用小窗控制器",
            summary = "关掉后本页设置全部失效",
        )
    }
}

/**
 * 「小窗控制器 · 功能」页：总开关 + 各功能的开关。
 *
 * 「× 改成 −」依赖「关闭小窗」按钮、「始终显示新窗口」依赖「新窗口」按钮 ——
 * 对应按钮被关掉时这两个开关会**变灰**（点不动），因为它们的改动无处可施。
 */
@Composable
fun CaptionFeaturesPage(
    uiState: AppUiState,
    padding: PaddingValues,
    scaffoldPadding: PaddingValues,
) {
    val hidden = splitPref(uiState.captionButtonHidden)
    val closeOn = closeButtonEnabled(hidden)
    val newWindowOn = newWindowButtonEnabled(hidden)

    CaptionList(padding, scaffoldPadding) {
        item { SmallTitle("小窗控制器") }
        item { CaptionMasterCard(uiState) }

        if (!uiState.captionEnabled) {
            item { CaptionDisabledHint() }
            return@CaptionList
        }

        item { SmallTitle("功能开关") }
        item {
            Card {
                SwitchPreference(
                    checked = uiState.captionCloseAsMinus && closeOn,
                    onCheckedChange = { checked ->
                        uiState.captionCloseAsMinus = checked
                        uiState.save { e -> e.putBoolean(PrefKeys.CAPTION_CLOSE_AS_MINUS, checked) }
                    },
                    title = "× 改成 −",
                    summary = if (closeOn) {
                        "只换图标，点击仍是关闭"
                    } else {
                        "「关闭小窗」按钮已关闭，先在上面的按钮页打开它"
                    },
                    enabled = closeOn,
                )
                SwitchPreference(
                    checked = uiState.captionForceClose,
                    onCheckedChange = { checked ->
                        uiState.captionForceClose = checked
                        uiState.save { e -> e.putBoolean(PrefKeys.CAPTION_FORCE_CLOSE, checked) }
                    },
                    title = "添加强制关闭应用按钮",
                    summary = "播完关闭动画后杀进程，后台不留",
                )
                SwitchPreference(
                    checked = uiState.captionHideCurrentState,
                    onCheckedChange = { checked ->
                        uiState.captionHideCurrentState = checked
                        uiState.save { e ->
                            e.putBoolean(PrefKeys.CAPTION_HIDE_CURRENT_STATE, checked)
                        }
                    },
                    title = "隐藏当前状态的按钮",
                    summary = "全屏时藏「全屏」，小窗时藏「小窗」",
                )
                SwitchPreference(
                    checked = uiState.captionAlwaysNewWindow && newWindowOn,
                    onCheckedChange = { checked ->
                        uiState.captionAlwaysNewWindow = checked
                        uiState.save { e ->
                            e.putBoolean(PrefKeys.CAPTION_ALWAYS_NEW_WINDOW, checked)
                        }
                    },
                    title = "始终显示「新窗口」",
                    summary = if (newWindowOn) {
                        "应用不支持多实例时也显示"
                    } else {
                        "「新窗口」按钮已关闭，先在上面的按钮页打开它"
                    },
                    enabled = newWindowOn,
                )
                SwitchPreference(
                    checked = uiState.captionHideDots,
                    onCheckedChange = { checked ->
                        uiState.captionHideDots = checked
                        uiState.save { e -> e.putBoolean(PrefKeys.CAPTION_HIDE_DOTS, checked) }
                    },
                    title = "隐藏三点控制器",
                    summary = "只是看不见，那块区域仍然点得开菜单",
                )
            }
        }
    }
}

/**
 * 「小窗控制器 · 按钮」页：每个按钮的显隐 + 顺序。
 *
 * 被关掉的按钮在顺序条上也会隐藏（[visibleOrder]）；隐藏项的位次仍留在完整顺序里，
 * 以后重新打开还在原来那一段。
 */
@Composable
fun CaptionButtonsPage(
    uiState: AppUiState,
    padding: PaddingValues,
    scaffoldPadding: PaddingValues,
) {
    val hidden = splitPref(uiState.captionButtonHidden)

    fun setHidden(buttonKey: String, hide: Boolean) {
        val next = hidden.toMutableList()
        if (hide) {
            if (buttonKey !in next) next.add(buttonKey)
        } else {
            next.remove(buttonKey)
        }
        val value = next.joinToString(",")
        uiState.captionButtonHidden = value
        uiState.save { e -> e.putString(PrefKeys.CAPTION_BUTTON_HIDDEN, value) }
    }

    fun saveOrder(list: List<String>) {
        val value = list.joinToString(",")
        uiState.captionButtonOrder = value
        uiState.save { e -> e.putString(PrefKeys.CAPTION_BUTTON_ORDER, value) }
    }

    // 完整顺序（含被隐藏的按钮）—— 隐藏按钮的位次要留着，重新启用时还在原处
    val storedOrder = splitPref(uiState.captionButtonOrder)
    val fullOrder = storedOrder.filter { buttonKey ->
        ALL_MENU_BUTTONS.any { it.key == buttonKey }
    } + ALL_MENU_BUTTONS.map { it.key }.filter { it !in storedOrder }

    // 顺序条上只列「启用的」按钮：被开关关掉的、以及没打开的红色强制关闭，都不出现
    val visibleOrder = fullOrder
        .filter { it !in hidden }
        .filter { it != PrefKeys.CB_FORCE_CLOSE || uiState.captionForceClose }

    /**
     * 拖动只作用于**看得见的那几个**，但落库写的是完整顺序：
     * 把可见下标映射回完整顺序里的下标再挪，隐藏按钮的相对次序不会被拖动搅乱。
     */
    fun moveVisible(fromVisible: Int, toVisible: Int) {
        if (fromVisible == toVisible) return
        if (fromVisible !in visibleOrder.indices || toVisible !in visibleOrder.indices) return
        val from = fullOrder.indexOf(visibleOrder[fromVisible])
        val to = fullOrder.indexOf(visibleOrder[toVisible])
        if (from < 0 || to < 0) return
        val next = fullOrder.toMutableList()
        next.add(to, next.removeAt(from))
        saveOrder(next)
    }

    CaptionList(padding, scaffoldPadding) {
        item { SmallTitle("小窗控制器") }
        item { CaptionMasterCard(uiState) }

        if (!uiState.captionEnabled) {
            item { CaptionDisabledHint() }
            return@CaptionList
        }

        item { SmallTitle("按钮开关") }
        item {
            Card {
                FRAMEWORK_MENU_BUTTONS.forEach { button ->
                    SwitchPreference(
                        checked = button.key !in hidden,
                        onCheckedChange = { checked -> setHidden(button.key, !checked) },
                        title = button.label,
                    )
                }
            }
        }

        item { SmallTitle("按钮顺序 · 长按拖动图标") }
        item {
            Card {
                if (visibleOrder.isEmpty()) {
                    Text(
                        text = "没有启用的按钮",
                        modifier = Modifier.padding(16.dp),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                } else {
                    ButtonOrderStrip(
                        order = visibleOrder,
                        shortLabelOf = { buttonKey ->
                            ALL_MENU_BUTTONS.first { it.key == buttonKey }.short
                        },
                        iconOf = { buttonKey ->
                            // 开了「× 改成 −」的话，顺序条里的关闭图标也要跟着变
                            if (buttonKey == PrefKeys.CB_CLOSE && uiState.captionCloseAsMinus) {
                                R.drawable.cap_btn_close_minus
                            } else {
                                ALL_MENU_BUTTONS.first { it.key == buttonKey }.icon
                            }
                        },
                        onMove = { from, to -> moveVisible(from, to) },
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        text = "恢复默认顺序",
                        onClick = { saveOrder(splitPref(PrefKeys.CAPTION_BUTTON_DEFAULT_ORDER)) },
                    )
                }
            }
        }
    }
}

/**
 * 横排图标顺序条：长按某个图标左右拖就能换位。
 *
 * <p>画的就是菜单里的真实图标（提取自 WMShell 资源），所以这块等于一个所见即所得的预览。
 *
 * <p>三个关键点：
 * <ol>
 *   <li>每格必须用 {@code key(buttonKey)} 包住。否则换位后 Compose 是按**位置**复用节点的 ——
 *       被拖那格的 {@code pointerInput} 会拿到新 key 被重启，手势拖一格就断。</li>
 *   <li>下标一律**按 key 现查**：手势 lambda 不随重组重建，捕获的 index 会是旧的。</li>
 *   <li>拖动期间关掉横向滚动（{@code horizontalScroll(enabled = ...)}），否则滚动容器会把
 *       横向手势抢走。</li>
 * </ol>
 */
@Composable
private fun ButtonOrderStrip(
    order: List<String>,
    shortLabelOf: (String) -> String,
    iconOf: (String) -> Int,
    onMove: (from: Int, to: Int) -> Unit,
) {
    val slotPx = with(LocalDensity.current) { (STRIP_ITEM_WIDTH + STRIP_ITEM_GAP).toPx() }
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var draggingOffset by remember { mutableFloatStateOf(0f) }
    val latestOrder = rememberUpdatedState(order)
    val latestMove = rememberUpdatedState(onMove)
    val scrollState = rememberScrollState()
    val rowShape = RoundedCornerShape(12.dp)
    val normalTint = MiuixTheme.colorScheme.onBackground

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState, enabled = draggingKey == null)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(STRIP_ITEM_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        order.forEach { buttonKey ->
            val dragging = buttonKey == draggingKey
            val isForceClose = buttonKey == PrefKeys.CB_FORCE_CLOSE
            key(buttonKey) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(STRIP_ITEM_WIDTH)
                        .zIndex(if (dragging) 1f else 0f)
                        .offset {
                            IntOffset(if (dragging) draggingOffset.roundToInt() else 0, 0)
                        }
                        .background(
                            color = if (dragging) MiuixTheme.colorScheme.surfaceContainerHigh
                            else Color.Transparent,
                            shape = rowShape,
                        )
                        .pointerInput(buttonKey) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingKey = buttonKey
                                    draggingIndex = latestOrder.value.indexOf(buttonKey)
                                    draggingOffset = 0f
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    draggingOffset += amount.x
                                    val steps = (draggingOffset / slotPx).roundToInt()
                                    if (steps != 0) {
                                        val last = latestOrder.value.lastIndex
                                        val target = (draggingIndex + steps).coerceIn(0, last)
                                        if (target != draggingIndex) {
                                            latestMove.value(draggingIndex, target)
                                            // 扣掉这一格的距离，被拖的图标才继续贴着手指
                                            draggingOffset -= (target - draggingIndex) * slotPx
                                            draggingIndex = target
                                        }
                                    }
                                    // 到两端后别让图标飞出条外
                                    draggingOffset = draggingOffset.coerceIn(-slotPx, slotPx)
                                },
                                onDragEnd = {
                                    draggingKey = null
                                    draggingIndex = -1
                                    draggingOffset = 0f
                                },
                                onDragCancel = {
                                    draggingKey = null
                                    draggingIndex = -1
                                    draggingOffset = 0f
                                },
                            )
                        }
                        .padding(vertical = 8.dp),
                ) {
                    Image(
                        painter = painterResource(iconOf(buttonKey)),
                        contentDescription = shortLabelOf(buttonKey),
                        colorFilter = ColorFilter.tint(
                            if (isForceClose) FORCE_CLOSE_RED else normalTint
                        ),
                        modifier = Modifier.size(STRIP_ICON_SIZE),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = shortLabelOf(buttonKey),
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (isForceClose) FORCE_CLOSE_RED
                        else MiuixTheme.colorScheme.onBackgroundVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
