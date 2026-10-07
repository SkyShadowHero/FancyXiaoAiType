package io.github.skyshadowhero.fancypad.ui

import android.content.Context
import android.graphics.Bitmap
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.caverock.androidsvg.SVG
import io.github.skyshadowhero.fancypad.PrefKeys
import io.github.skyshadowhero.fancypad.R
import io.github.skyshadowhero.fancypad.RemoteConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
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
import kotlin.math.roundToInt

/**
 * 光标域（作用域 `system`）的三个页面 —— 平板端的左侧菜单就是这三项（与原 os4光标主题模块一致）：
 * 「主题预设」「大小」「导入」。
 *
 * 状态与常量都在 [CursorState.kt]；「颜色设置」是可改色预设的二级页（[CursorColorsPage]）。
 * 连接跟随由外壳里的 [CursorConnectionEffect] 统一负责，这里不各自轮询。
 */

// --------------------------------------------------------------- 主题预设

@Composable
fun CursorPresetPage(
    cursorEnabled: Boolean,
    onCursorEnabledChange: (Boolean) -> Unit,
    onOpenColors: () -> Unit,
    padding: PaddingValues,
    scaffoldPadding: PaddingValues,
) {
    val state = cursorState

    LaunchedEffect(state.preset, state.scale, state.fill, state.stroke, state.bound) {
        state.persistDebounced()
    }

    LazyColumn(
        modifier = Modifier.padding(padding),
        contentPadding = pagePadding(scaffoldPadding),
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
            presetSection(state, onOpenColors)
        }
    }
}

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

/**
 * 主题预设。可改色的预设（AOSP / GoogleDot）被选中时，该行下方展开「颜色设置 ›」，
 * 点进去是二级页（与原模块一致）。
 */
private fun LazyListScope.presetSection(state: CursorUiState, onOpenColors: () -> Unit) {
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
                if (themeKeyOf(p.id) != null) {
                    AnimatedVisibility(
                        visible = state.preset == p.id,
                        enter = expandVertically(animationSpec = tween(220)) +
                            fadeIn(animationSpec = tween(220)),
                        exit = shrinkVertically(animationSpec = tween(180)) +
                            fadeOut(animationSpec = tween(140)),
                    ) {
                        Column {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            // 用 startAction 占位做缩进：hover/pressed 遮罩覆盖整行，前面不会显空
                            ArrowPreference(
                                title = "颜色设置",
                                onClick = onOpenColors,
                                startAction = { Spacer(Modifier.width(32.dp)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------- 大小

@Composable
fun CursorSizePage(
    padding: PaddingValues,
    scaffoldPadding: PaddingValues,
) {
    val state = cursorState

    LaunchedEffect(
        state.preset, state.scale, state.fill, state.stroke, state.bound,
        state.shakeBoost, state.shakeReversals, state.shakeFrames, state.shakeHoldMs,
    ) {
        state.persistDebounced()
    }

    LazyColumn(
        modifier = Modifier.padding(padding),
        contentPadding = pagePadding(scaffoldPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
        shakeSection(state)
    }
}

/**
 * 摇晃放大（macOS「摇晃鼠标指针以定位」）。
 *
 * 判定与放大都在 system_server 侧（{@code CursorShake} → {@code CursorHooks}）：
 * 快速左右（或上下）摇晃鼠标，光标放大；**一直摇会一直变大**，停手后自动缩回。
 * 放大倍数乘在「光标大小」之上，不改动用户设定本身。
 */
private fun LazyListScope.shakeSection(state: CursorUiState) {
    item { SmallTitle("摇晃放大") }
    item {
        Card {
            SwitchPreference(
                checked = state.shakeEnabled,
                onCheckedChange = { checked ->
                    state.shakeEnabled = checked
                    // 开关即时落盘：不像滑块那样防抖，避免开关状态晚 250ms 才生效
                    if (state.bound) {
                        RemoteConfig.edit { it.putBoolean(PrefKeys.CURSOR_SHAKE_ENABLED, checked) }
                    }
                },
                title = "摇晃放大光标",
                summary = "快速左右摇晃鼠标，光标临时变大（类似 macOS）；需先开启「接管系统光标」",
            )
            AnimatedVisibility(
                visible = state.shakeEnabled,
                enter = expandVertically(animationSpec = tween(220)) +
                    fadeIn(animationSpec = tween(220)),
                exit = shrinkVertically(animationSpec = tween(180)) +
                    fadeOut(animationSpec = tween(140)),
            ) {
                Column {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SliderPreference(
                        value = state.shakeBoost,
                        onValueChange = { state.shakeBoost = it },
                        title = "放大倍数",
                        summary = "首次摇中时放大到「光标大小」的几倍；继续摇会在此基础上继续变大",
                        valueText = "${(state.shakeBoost * 100).roundToInt()}%",
                        valueRange = PrefKeys.CURSOR_SHAKE_BOOST_MIN..PrefKeys.CURSOR_SHAKE_BOOST_MAX,
                        steps = 17,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SliderPreference(
                        value = state.shakeReversals.toFloat(),
                        onValueChange = { state.shakeReversals = it.roundToInt() },
                        title = "触发灵敏度",
                        summary = "需要来回换向这么多次才放大；觉得太灵敏就往大调",
                        valueText = "${state.shakeReversals} 次",
                        valueRange = PrefKeys.CURSOR_SHAKE_REVERSALS_MIN.toFloat()..PrefKeys.CURSOR_SHAKE_REVERSALS_MAX.toFloat(),
                        steps = 4,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SliderPreference(
                        value = state.shakeFrames.toFloat(),
                        onValueChange = { state.shakeFrames = it.roundToInt() },
                        title = "动画时长",
                        summary = "60fps 逐帧播放；每帧都要让系统重载一次光标，所以时长越长帧数越多",
                        valueText = if (state.shakeFrames <= 1) {
                            "1 帧（直接跳变）"
                        } else {
                            "${state.shakeFrames} 帧 · ${state.shakeFrames * 16} 毫秒"
                        },
                        valueRange = PrefKeys.CURSOR_SHAKE_FRAMES_MIN.toFloat()..PrefKeys.CURSOR_SHAKE_FRAMES_MAX.toFloat(),
                        steps = 28,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SliderPreference(
                        value = state.shakeHoldMs.toFloat(),
                        onValueChange = { state.shakeHoldMs = it.roundToInt() },
                        title = "保持时长",
                        summary = "停手后多久缩回；期间继续摇晃会顺延并继续变大",
                        valueText = "${state.shakeHoldMs} 毫秒",
                        valueRange = PrefKeys.CURSOR_SHAKE_HOLD_MIN.toFloat()..PrefKeys.CURSOR_SHAKE_HOLD_MAX.toFloat(),
                        steps = 25,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------- 导入

@Composable
fun CursorImportPage(
    padding: PaddingValues,
    scaffoldPadding: PaddingValues,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = cursorState

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
                state.refreshImported()
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
                    state.refreshImported()
                    state.themes = emptyList()
                    state.thumbs = emptyMap()
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
                RemoteConfig.edit { e ->
                    e.putString(PrefKeys.CURSOR_THEMES, state.themes.filter { it != theme }.joinToString("|"))
                    e.putInt(PrefKeys.CURSOR_PRESET, PRESET_AOSP)
                }
                withContext(Dispatchers.Main) {
                    state.preset = PRESET_AOSP
                    state.message = "已删除主题「$theme」"
                    state.refreshImported()
                    state.loadThemes()
                }
            }
        }
        Unit
    }

    LazyColumn(
        modifier = Modifier.padding(padding),
        contentPadding = pagePadding(scaffoldPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        importSection(state, onClear, onDeleteTheme, onRename, onPickType)
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
                            + "只要有任意一项选好，「自定义（逐项）」预设就会出现在主题预设列表里。",
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

private fun pagePadding(scaffoldPadding: PaddingValues) = PaddingValues(
    start = 12.dp,
    end = 12.dp,
    top = scaffoldPadding.calculateTopPadding() + 8.dp,
    bottom = scaffoldPadding.calculateBottomPadding() + 24.dp,
)

@Composable
private fun ThemeThumb(bitmap: ImageBitmap) {
    Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(26.dp))
}

// --------------------------------------------------------------------- 导入

/**
 * 说明：只做「逐项选择」。
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
