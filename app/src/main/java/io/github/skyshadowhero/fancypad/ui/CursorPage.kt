package io.github.skyshadowhero.fancypad.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.caverock.androidsvg.SVG
import io.github.skyshadowhero.fancypad.PrefKeys
import io.github.skyshadowhero.fancypad.RemoteConfig
import io.github.skyshadowhero.fancypad.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTitleDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

/**
 * 「光标」页（原 os4光标主题模块）：接管系统光标渲染。
 *
 * 与旧模块的差别：
 * - 不再自带 NavigationRail / Scaffold / TopAppBar —— 外壳由 [AppShell] 提供，页面只出内容；
 * - 原「颜色设置」二级页取消：可改色的预设（AOSP / GoogleDot）在预设列表下方直接展开取色器，
 *   宽屏两个取色器并排。少一层跳转，也不会和外层的顶栏打架；
 * - 顶部多一个总开关 `cursor_enabled`（Hook 侧 [io.github.skyshadowhero.fancypad.HookPrefs] 读取），
 *   关掉就完全交回系统的光标实现。
 *
 * 偏好键沿用原模块（preset / scale / fill_<主题> / stroke_<主题> / themes / theme_labels），
 * 因此 Hook 侧 [io.github.skyshadowhero.fancypad.CursorHooks] 一行都不用改语义。
 */

private const val DEFAULT_FILL = PrefKeys.CURSOR_FILL_DEFAULT
private const val DEFAULT_STROKE = PrefKeys.CURSOR_STROKE_DEFAULT

/** 预设顺序即 Hook 侧 preset 索引 */
private const val PRESET_AOSP = 0
private const val PRESET_GOOGLEDOT = 3

/** 逐项选择（与 Hook 侧一致） */
private const val PRESET_CUSTOM = 9
private const val PRESET_THEME_BASE = PrefKeys.CURSOR_THEME_BASE

/** 光标类型 → 中文名（逐项选择用） */
private val TYPE_LABELS = listOf(
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

private class Preset(
    val id: Int,
    val label: String,
    val preview: Int,
    val link: String = "",
    val themeName: String? = null,
)

/** 显示顺序（id 顺序不变，Hook 侧按 id 认）：material 排在最后 */
private val PRESETS = listOf(
    Preset(PRESET_AOSP, "AOSP", R.drawable.prev_aosp, "github.com/Tech-Tac/aosp-cursors"),
    Preset(2, "MacOS", R.drawable.prev_apple, "github.com/ful1e5/apple_cursor"),
    Preset(PRESET_GOOGLEDOT, "GoogleDot", R.drawable.prev_googledot, "github.com/ful1e5/Google_Cursor"),
    Preset(4, "BreezeX", R.drawable.prev_breezex, "github.com/ful1e5/BreezeX_Cursor"),
    Preset(1, "Material", R.drawable.prev_material, "github.com/varlesh/material-cursors"),
)

@Stable
private class CursorUiState {
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

/**
 * 进程级单例状态。
 * 不用 `remember { CursorUiState() }`：Activity 一旦重建（旋转、切深色模式、退出再进）
 * 就回到默认值，而用户看到的会是「设置没保存」。
 */
private val cursorState = CursorUiState()

/** 内置预设 + 导入的主题（id 从 [PRESET_THEME_BASE] 开始）。 */
private fun presetList(themes: List<String>, labels: List<String>, hasCustom: Boolean = false): List<Preset> =
    PRESETS + (if (hasCustom) listOf(Preset(PRESET_CUSTOM, "自定义（逐项）", R.drawable.prev_aosp, "")) else emptyList()) +
        themes.mapIndexed { i, key ->
            val label = labels.getOrNull(i)?.takeIf { it.isNotBlank() } ?: key
            Preset(PRESET_THEME_BASE + i, label, 0, "", key)
        }

private fun themeKey(raw: String): String = raw.replace(Regex("[^A-Za-z0-9_]"), "_")

@Composable
fun CursorPage(
    cursorEnabled: Boolean,
    onCursorEnabledChange: (Boolean) -> Unit,
    padding: PaddingValues,
    scaffoldPadding: PaddingValues,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = cursorState

    fun loadFromPrefs() {
        val p = RemoteConfig.prefs() ?: return
        state.preset = p.getInt(PrefKeys.CURSOR_PRESET, PRESET_AOSP)
        state.scale = p.getInt(PrefKeys.CURSOR_SCALE, PrefKeys.CURSOR_SCALE_DEFAULT) / 100f
        val tk = themeKeyOf(state.preset) ?: "aosp"
        val dflt = defaultColorsOf(state.preset)
        state.fill = Color(p.getInt(PrefKeys.CURSOR_FILL_PREFIX + tk, dflt.first))
        state.stroke = Color(p.getInt(PrefKeys.CURSOR_STROKE_PREFIX + tk, dflt.second))
    }

    fun refreshImported() {
        val files = RemoteConfig.listRemoteFiles()
        state.importedCount = files.count { it.startsWith("cust_") }
        state.importedKeys = files.filter { it.startsWith("cust_") && it.endsWith(".png") }
            .map { it.removePrefix("cust_").removeSuffix(".png") }
            .filter { it in TYPE_LABELS.map { p -> p.first } }
            .toSet()
    }

    fun reloadThemes() {
        val p = RemoteConfig.prefs()
        val names = (p?.getString(PrefKeys.CURSOR_THEMES, "") ?: "").split("|").filter { it.isNotBlank() }
        state.themes = names
        val labelList = (p?.getString(PrefKeys.CURSOR_THEME_LABELS, "") ?: "").split("|")
        state.labels = names.indices.map { labelList.getOrNull(it) ?: "" }
        if (names.isEmpty()) {
            state.thumbs = emptyMap()
            return
        }
        scope.launch {
            state.thumbs = withContext(Dispatchers.IO) {
                names.mapNotNull { n ->
                    val bmp = runCatching {
                        RemoteConfig.openRemoteFile("cust_${n}_thumb.png")?.use { pfd ->
                            BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor)
                        }
                    }.getOrNull() ?: return@mapNotNull null
                    n to bmp.asImageBitmap()
                }.toMap()
            }
        }
    }

    // 连接状态跟随：直接轮询 RemoteConfig（它在 Application.onCreate 里已经注册过监听器），
    // 不再自己 registerListener —— 那样在「服务早于本页注册就已经绑定」时会永远收不到回调。
    LaunchedEffect(Unit) {
        while (true) {
            val ready = RemoteConfig.isReady
            if (ready != state.bound) {
                state.bound = ready
                if (ready) {
                    loadFromPrefs()
                    refreshImported()
                    reloadThemes()
                }
            }
            delay(500)
        }
    }

    // 防抖写偏好：每次写都会让 system_server 重刷光标，拖动时别每帧都写
    LaunchedEffect(state.preset, state.scale, state.fill, state.stroke, state.bound) {
        if (!state.bound) return@LaunchedEffect
        delay(250)
        val tk = themeKeyOf(state.preset)
        RemoteConfig.edit { e ->
            e.putInt(PrefKeys.CURSOR_PRESET, state.preset)
            e.putInt(PrefKeys.CURSOR_SCALE, (state.scale * 100).roundToInt())
            if (tk != null) {
                e.putInt(PrefKeys.CURSOR_FILL_PREFIX + tk, state.fill.toArgb())
                e.putInt(PrefKeys.CURSOR_STROKE_PREFIX + tk, state.stroke.toArgb())
            }
        }
    }

    // 切换预设 → 读取该主题自己的颜色
    LaunchedEffect(state.preset, state.bound) {
        if (!state.bound) return@LaunchedEffect
        val p = RemoteConfig.prefs() ?: return@LaunchedEffect
        val tk = themeKeyOf(state.preset) ?: return@LaunchedEffect
        val dflt = defaultColorsOf(state.preset)
        state.fill = Color(p.getInt(PrefKeys.CURSOR_FILL_PREFIX + tk, dflt.first))
        state.stroke = Color(p.getInt(PrefKeys.CURSOR_STROKE_PREFIX + tk, dflt.second))
    }

    var pendingKey by remember { mutableStateOf<String?>(null) }
    val typePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val key = pendingKey
        if (uri == null || !RemoteConfig.isReady || key == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val nm = queryName(context, uri) ?: "img"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null) false else upload(key, bytes, nm)
            }
            withContext(Dispatchers.Main) {
                refreshImported()
                state.message = if (ok) "已设置「$key」" else "设置失败：$key"
                RemoteConfig.edit { it.putInt(PrefKeys.CURSOR_PRESET, PRESET_CUSTOM) }
                state.preset = PRESET_CUSTOM
            }
        }
    }

    val onPickType: (String) -> Unit = { key ->
        pendingKey = key
        typePicker.launch(arrayOf("*/*"))
    }
    val onClear: () -> Unit = {
        if (RemoteConfig.isReady) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        RemoteConfig.listRemoteFiles()
                            .filter { it.startsWith("cust_") }
                            .forEach { RemoteConfig.deleteRemoteFile(it) }
                    }
                }
                RemoteConfig.edit { e ->
                    e.putString(PrefKeys.CURSOR_THEMES, "")
                    e.putInt(PrefKeys.CURSOR_PRESET, PRESET_AOSP)
                }
                withContext(Dispatchers.Main) {
                    refreshImported()
                    state.themes = emptyList()
                    state.preset = PRESET_AOSP
                    state.message = "已清空全部导入主题"
                }
            }
        }
        Unit
    }
    val onRename: (String, String) -> Unit = { key, newName ->
        val idx = state.themes.indexOf(key)
        if (idx >= 0) {
            val list = state.themes.indices.map { state.labels.getOrNull(it) ?: "" }.toMutableList()
            list[idx] = newName.trim()
            state.labels = list
            RemoteConfig.edit {
                it.putString(PrefKeys.CURSOR_THEME_LABELS, list.joinToString("|"))
            }
        }
        Unit
    }
    val onDeleteTheme: (String) -> Unit = { theme ->
        if (RemoteConfig.isReady) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        RemoteConfig.listRemoteFiles()
                            .filter { it.startsWith("cust_${theme}_") }
                            .forEach { RemoteConfig.deleteRemoteFile(it) }
                    }
                }
                withContext(Dispatchers.Main) {
                    val list = state.themes.filter { it != theme }
                    RemoteConfig.edit { e ->
                        e.putString(PrefKeys.CURSOR_THEMES, list.joinToString("|"))
                        e.putInt(PrefKeys.CURSOR_PRESET, PRESET_AOSP)
                    }
                    state.themes = list
                    state.preset = PRESET_AOSP
                    state.message = "已删除主题「$theme」"
                    refreshImported()
                }
            }
        }
        Unit
    }

    LazyColumn(
        modifier = Modifier.padding(padding),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = scaffoldPadding.calculateTopPadding() + 8.dp,
            bottom = scaffoldPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SmallTitle("接管") }
        item {
            Card {
                SwitchPreference(
                    checked = cursorEnabled,
                    onCheckedChange = onCursorEnabledChange,
                    title = "接管系统光标",
                    summary = if (state.bound) {
                        "已连接 LSPosed · 改动即时生效"
                    } else {
                        "未连接 LSPosed（模块未启用？）"
                    },
                )
            }
        }
        if (cursorEnabled) {
            previewSection(state)
            presetSection(state)
            if (themeKeyOf(state.preset) != null) colorSection(state)
            sizeSection(state)
            importSection(state, onClear, onDeleteTheme, onRename, onPickType)
            item { note() }
        } else {
            item { noteDisabled() }
        }
    }
}

// --------------------------------------------------------------------- 各分段

private fun LazyListScope.previewSection(state: CursorUiState) {
    val presets = presetList(state.themes, state.labels, state.importedKeys.isNotEmpty())
    val current = presets.firstOrNull { it.id == state.preset } ?: presets.first()
    val thumb = current.themeName?.let { state.thumbs[it] }
    item { SmallTitle("当前光标") }
    item {
        Card {
            // Card 自身 insideMargin = 0，裸内容要自己用组件默认内边距
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(BasicComponentDefaults.InsideMargin),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                    if (thumb != null) {
                        Image(
                            bitmap = thumb,
                            contentDescription = null,
                            modifier = Modifier.size((32 + 16 * state.scale).coerceAtMost(72f).dp),
                        )
                    } else {
                        Image(
                            painter = painterResource(
                                if (current.preview != 0) current.preview else R.drawable.prev_aosp
                            ),
                            contentDescription = null,
                            modifier = Modifier.size((32 + 16 * state.scale).coerceAtMost(72f).dp),
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(current.label, style = MiuixTheme.textStyles.body1)
                    Text(
                        "${(state.scale * 100).roundToInt()}% · 24dp 基准",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
            }
        }
    }
}

private fun LazyListScope.presetSection(state: CursorUiState) {
    item { SmallTitle("主题预设") }
    item {
        Card {
            presetList(state.themes, state.labels, state.importedKeys.isNotEmpty()).forEach { p ->
                val rowThumb = p.themeName?.let { state.thumbs[it] }
                RadioButtonPreference(
                    title = p.label,
                    summary = p.link.ifEmpty { null },
                    selected = state.preset == p.id,
                    onClick = { state.preset = p.id },
                    startAction = if (rowThumb != null) {
                        { ThemeThumb(rowThumb) }
                    } else null,
                )
            }
        }
    }
}

/** 大小：滑块 + 吸附点与旧模块一致 */
private fun LazyListScope.sizeSection(state: CursorUiState) {
    item { SmallTitle("大小") }
    item {
        Card {
            SliderPreference(
                value = state.scale,
                onValueChange = { state.scale = it },
                title = "光标大小",
                summary = "拖动即时预览",
                valueText = "${(state.scale * 100).roundToInt()}%",
                valueRange = PrefKeys.CURSOR_SCALE_MIN..PrefKeys.CURSOR_SCALE_MAX,
                steps = 26,
            )
        }
    }
}

/**
 * 颜色：只在可改色的预设（AOSP / GoogleDot）下出现，取代原来的「颜色设置」二级页。
 * 宽屏并排两个取色器，窄屏上下排列（[Arrangement] 已按行/列分派）。
 */
private fun LazyListScope.colorSection(state: CursorUiState) {
    item { SmallTitle("颜色（${themeLabelOf(state.preset)}）") }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            colorPickerCard(state, true)
            colorPickerCard(state, false)
        }
    }
}

private fun LazyListScope.importSection(
    state: CursorUiState,
    onClear: () -> Unit,
    onDeleteTheme: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onPickType: (String) -> Unit,
) {
    item { SmallTitle("导入") }
    item {
        Card {
            Column {
                Column(modifier = Modifier.padding(BasicComponentDefaults.InsideMargin)) {
                    Text(
                        "逐项选择：下面每种光标单独挑一张 PNG 或 SVG。已选过的显示「已选择 · 点此替换」；"
                            + "只要有任意一项选好，「自定义（逐项）」预设就会出现在主题预设列表里。\n"
                            + "各预设缺失的类型统一用 AOSP 兜底。",
                    )
                    Spacer(Modifier.height(12.dp))
                    Row {
                        Button(
                            onClick = onClear,
                            enabled = state.bound && state.importedCount > 0,
                        ) { Text("清空（${state.importedCount}）") }
                    }
                }
                // 25 行逐项选择：左右不留外边距，hover/按下遮罩铺满整行（与主题预设列表一致）
                Spacer(Modifier.height(4.dp))
                TYPE_LABELS.forEach { (key, label) ->
                    ArrowPreference(
                        title = label,
                        summary = if (key in state.importedKeys) "已选择 · 点此替换" else "点此选择 PNG / SVG",
                        onClick = { onPickType(key) },
                    )
                }
                Column(modifier = Modifier.padding(BasicComponentDefaults.InsideMargin)) {
                    val cur = presetList(state.themes, state.labels, state.importedKeys.isNotEmpty())
                        .firstOrNull { it.id == state.preset }
                    val curTheme = cur?.themeName
                    if (curTheme != null) {
                        Spacer(Modifier.height(12.dp))
                        var name by remember(curTheme) { mutableStateOf(cur.label) }
                        TextField(
                            value = TextFieldValue(name),
                            onValueChange = { v ->
                                name = v.text
                                onRename(curTheme, v.text)
                            },
                            label = "主题名称",
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { onDeleteTheme(curTheme) }) { Text("删除主题「${cur.label}」") }
                    }
                    if (state.message.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            state.message,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------------- 部件

@Composable
private fun ThemeThumb(bitmap: ImageBitmap) {
    Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(26.dp))
}

@Composable
private fun colorPickerCard(state: CursorUiState, isFill: Boolean) {
    val color = if (isFill) state.fill else state.stroke
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(BasicComponentDefaults.InsideMargin)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(
                            1.dp,
                            MiuixTheme.colorScheme.onBackgroundVariant.copy(alpha = 0.35f),
                            CircleShape,
                        )
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    (if (isFill) "填充色 " else "描边色 ") + hex(color),
                    style = MiuixTheme.textStyles.body1,
                )
            }
            Spacer(Modifier.height(12.dp))
            ColorPicker(
                color = color,
                onColorChanged = { picked ->
                    val opaque = picked.copy(alpha = 1f)      // 光标必须不透明
                    if (isFill) state.fill = opaque else state.stroke = opaque
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                val d = defaultColorsOf(state.preset)
                if (isFill) state.fill = Color(d.first) else state.stroke = Color(d.second)
            }) { Text("恢复默认") }
        }
    }
}

@Composable
private fun note() {
    Text(
        "主题、大小、颜色改完立即生效，不用重启（只有模块升级 / 作用域变更才需要重启一次）。"
            + "可改色的是 AOSP 和 GoogleDot；Material / MacOS / BreezeX 用各仓库原图，颜色固定。",
        modifier = Modifier.padding(SmallTitleDefaults.InsideMargin),
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onBackgroundVariant,
    )
}

@Composable
private fun noteDisabled() {
    Text(
        "已关闭「接管系统光标」：模块不参与光标渲染，两个只读的矢量光标开关也交回系统，"
            + "光标恢复为系统自带样式（设置改完即时生效，不用重启）。",
        modifier = Modifier.padding(SmallTitleDefaults.InsideMargin),
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onBackgroundVariant,
    )
}

private fun themeLabelOf(preset: Int): String =
    PRESETS.firstOrNull { it.id == preset }?.label ?: "导入主题"

/**
 * 可改色的只有 AOSP（官方矢量拆层）和 GoogleDot（mask 两层）；其余主题用仓库原色图。
 * Hook 侧 fill_<键> / stroke_<键> 就是按这个键存的。
 */
private fun themeKeyOf(preset: Int): String? = when (preset) {
    PRESET_AOSP -> "aosp"
    PRESET_GOOGLEDOT -> "googledot"
    else -> null
}

/** 可改色主题的默认（填充, 描边）色，与 CursorIcons.THEME_COLORS 一致 */
private fun defaultColorsOf(preset: Int): Pair<Int, Int> = when (preset) {
    PRESET_GOOGLEDOT -> 0xFFFFFFFF.toInt() to 0xFF000000.toInt()   // GoogleDot：白心黑边
    else -> DEFAULT_FILL to DEFAULT_STROKE                         // AOSP：黑 / 白
}

private fun hex(color: Color): String = String.format("#%06X", color.toArgb() and 0xFFFFFF)

// --------------------------------------------------------------------- 导入

/**
 * 说明：这里只保留「逐项选择」。
 *
 * 旧模块还有一条「整包导入 Linux 主题包（.zip / .tar / XCursor）」入口，
 * 之后按用户要求下线了（逐项选择才是支持路径），因此不再挂那个按钮。
 * XCursor 二进制解析器（XCursor.kt / ThemeArchive）保留在工程里，
 * 将来若恢复整包导入可直接复用。
 */
private fun uploadRaw(suffix: String, png: ByteArray): Boolean = try {
    RemoteConfig.openRemoteFile("cust_$suffix.png")?.use { pfd: ParcelFileDescriptor ->
        FileOutputStream(pfd.fileDescriptor).use { it.write(png) }
    }
    true
} catch (t: Throwable) {
    false
}

private fun upload(key: String, data: ByteArray, srcName: String): Boolean {
    val png = if (srcName.lowercase().endsWith(".svg")) renderSvg(data) ?: return false else data
    return uploadRaw(key, png)
}

private fun renderSvg(bytes: ByteArray, size: Int = 256): ByteArray? = try {
    val svg = SVG.getFromInputStream(ByteArrayInputStream(bytes))
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).drawPicture(svg.renderToPicture(size, size))
    ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }
} catch (t: Throwable) {
    null
}


private fun queryName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
    }
}.getOrNull()
