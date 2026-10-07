package io.github.skyshadowhero.fancypad.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import io.github.skyshadowhero.fancypad.PrefKeys
import io.github.skyshadowhero.fancypad.RemoteConfig
import io.github.skyshadowhero.fancypad.R
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 光标域的共享状态与常量。
 *
 * 「光标」页与它的二级页「颜色设置」都在这里读写同一份状态 —— 两个页面是同一路由栈里的
 * 两个 entry，页面切换时各自独立组合，所以状态必须放在这两个页面之外（进程级单例），
 * 否则从颜色页返回后预设 / 大小会回退成默认值。
 */

internal const val DEFAULT_FILL = PrefKeys.CURSOR_FILL_DEFAULT
internal const val DEFAULT_STROKE = PrefKeys.CURSOR_STROKE_DEFAULT

/** 预设顺序即 Hook 侧 preset 索引 */
internal const val PRESET_AOSP = 0
internal const val PRESET_GOOGLEDOT = 3

/** 逐项选择（与 Hook 侧一致） */
internal const val PRESET_CUSTOM = 9
internal const val PRESET_THEME_BASE = PrefKeys.CURSOR_THEME_BASE

/** 光标类型 → 中文名（逐项选择用） */
internal val TYPE_LABELS = listOf(
    "pointer_arrow" to "默认箭头", "pointer_text" to "文本选择", "pointer_vertical_text" to "竖排文本",
    "pointer_crosshair" to "十字准星", "pointer_hand" to "手型", "pointer_help" to "帮助",
    "pointer_wait" to "等待/忙碌", "pointer_cell" to "单元格", "pointer_alias" to "快捷方式",
    "pointer_copy" to "复制", "pointer_nodrop" to "禁止放置", "pointer_all_scroll" to "全向移动",
    "pointer_horizontal_double_arrow" to "水平缩放", "pointer_vertical_double_arrow" to "垂直缩放",
    "pointer_top_left_diagonal_double_arrow" to "左上斜向", "pointer_top_right_diagonal_double_arrow" to "右上斜向",
    "pointer_zoom_in" to "放大", "pointer_zoom_out" to "缩小", "pointer_grab" to "可抓取",
    "pointer_grabbing" to "抓取中", "pointer_handwriting" to "手写", "pointer_context_menu" to "右键菜单",
    "pointer_spot_hover" to "触控笔悬停", "pointer_spot_touch" to "触控笔接触", "pointer_spot_anchor" to "触控笔锚点",
)

internal class Preset(
    val id: Int,
    val label: String,
    val preview: Int,
    val link: String = "",
    val themeName: String? = null,
)

/** 显示顺序（id 顺序不变，Hook 侧按 id 认）：material 排在最后 */
internal val PRESETS = listOf(
    Preset(PRESET_AOSP, "AOSP", R.drawable.prev_aosp, "github.com/Tech-Tac/aosp-cursors"),
    Preset(2, "MacOS", R.drawable.prev_apple, "github.com/ful1e5/apple_cursor"),
    Preset(PRESET_GOOGLEDOT, "GoogleDot", R.drawable.prev_googledot, "github.com/ful1e5/Google_Cursor"),
    Preset(4, "BreezeX", R.drawable.prev_breezex, "github.com/ful1e5/BreezeX_Cursor"),
    Preset(1, "Material", R.drawable.prev_material, "github.com/varlesh/material-cursors"),
)

@Stable
internal class CursorUiState {
    var preset by mutableIntStateOf(PRESET_AOSP)
    var scale by mutableFloatStateOf(1f)
    var fill by mutableStateOf(Color(DEFAULT_FILL))
    var stroke by mutableStateOf(Color(DEFAULT_STROKE))
    var bound by mutableStateOf(false)
    var importedCount by mutableIntStateOf(0)
    var importedKeys by mutableStateOf<Set<String>>(emptySet())
    var message by mutableStateOf("")
    var themes by mutableStateOf<List<String>>(emptyList())
    var labels by mutableStateOf<List<String>>(emptyList())
    var thumbs by mutableStateOf<Map<String, ImageBitmap>>(emptyMap())
}

/** 进程级单例：见本文件顶部说明。 */
internal val cursorState = CursorUiState()

/** 内置预设 + 导入的主题（id 从 [PRESET_THEME_BASE] 开始）。 */
internal fun presetList(themes: List<String>, labels: List<String>, hasCustom: Boolean = false): List<Preset> =
    PRESETS + (if (hasCustom) listOf(Preset(PRESET_CUSTOM, "自定义（逐项）", R.drawable.prev_aosp, "")) else emptyList()) +
        themes.mapIndexed { i, key ->
            val label = labels.getOrNull(i)?.takeIf { it.isNotBlank() } ?: key
            Preset(PRESET_THEME_BASE + i, label, 0, "", key)
        }

internal fun themeKey(raw: String): String = raw.replace(Regex("[^A-Za-z0-9_]"), "_")

internal fun themeLabelOf(preset: Int): String =
    PRESETS.firstOrNull { it.id == preset }?.label ?: "导入主题"

/**
 * 可改色的只有 AOSP（官方矢量拆层）和 GoogleDot（mask 两层）；其余主题用仓库原色图。
 * Hook 侧 fill_<键> / stroke_<键> 就是按这个键存的。
 */
internal fun themeKeyOf(preset: Int): String? = when (preset) {
    PRESET_AOSP -> "aosp"
    PRESET_GOOGLEDOT -> "googledot"
    else -> null
}

/** 可改色主题的默认（填充, 描边）色，与 CursorIcons.THEME_COLORS 一致 */
internal fun defaultColorsOf(preset: Int): Pair<Int, Int> = when (preset) {
    PRESET_GOOGLEDOT -> 0xFFFFFFFF.toInt() to 0xFF000000.toInt()   // GoogleDot：白心黑边
    else -> DEFAULT_FILL to DEFAULT_STROKE                         // AOSP：黑 / 白
}

internal fun hex(color: Color): String = String.format("#%06X", color.toArgb() and 0xFFFFFF)

/** 从远端偏好装载一组值（连接刚就绪时调用）。 */
internal fun CursorUiState.loadFromPrefs() {
    val p = RemoteConfig.prefs() ?: return
    preset = p.getInt(PrefKeys.CURSOR_PRESET, PRESET_AOSP)
    scale = p.getInt(PrefKeys.CURSOR_SCALE, PrefKeys.CURSOR_SCALE_DEFAULT) / 100f
    val tk = themeKeyOf(preset) ?: "aosp"
    val dflt = defaultColorsOf(preset)
    fill = Color(p.getInt(PrefKeys.CURSOR_FILL_PREFIX + tk, dflt.first))
    stroke = Color(p.getInt(PrefKeys.CURSOR_STROKE_PREFIX + tk, dflt.second))
}

/** 刷新「已导入的光标」统计（逐项选择用）。 */
internal fun CursorUiState.refreshImported() {
    val files = RemoteConfig.listRemoteFiles()
    importedCount = files.count { it.startsWith("cust_") }
    importedKeys = files.filter { it.startsWith("cust_") && it.endsWith(".png") }
        .map { it.removePrefix("cust_").removeSuffix(".png") }
        .filter { it in TYPE_LABELS.map { p -> p.first } }
        .toSet()
}

/**
 * 防抖落盘。
 *
 * 每次写都会让 system_server 重刷光标，所以拖动滑块 / 取色器时不能每帧都写。
 * 两个页面（光标页、颜色设置页）都用同一个 effect 调它：调用方要求
 * `LaunchedEffect(preset, scale, fill, stroke, bound) { persistDebounced() }`。
 */
internal suspend fun CursorUiState.persistDebounced() {
    if (!bound) return
    delay(250)
    val tk = themeKeyOf(preset)
    RemoteConfig.edit { e ->
        e.putInt(PrefKeys.CURSOR_PRESET, preset)
        e.putInt(PrefKeys.CURSOR_SCALE, (scale * 100).roundToInt())
        if (tk != null) {
            e.putInt(PrefKeys.CURSOR_FILL_PREFIX + tk, fill.toArgb())
            e.putInt(PrefKeys.CURSOR_STROKE_PREFIX + tk, stroke.toArgb())
        }
    }
}
