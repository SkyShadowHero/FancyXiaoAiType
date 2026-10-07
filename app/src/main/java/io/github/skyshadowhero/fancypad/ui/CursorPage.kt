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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.caverock.androidsvg.SVG
import io.github.skyshadowhero.fancypad.PrefKeys
import io.github.skyshadowhero.fancypad.R
import io.github.skyshadowhero.fancypad.RemoteConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * 「光标」页（作用域 system）—— 接管系统光标渲染。
 *
 * 状态与常量放在 [CursorState.kt]（与二级页「颜色设置」共用）。
 * 颜色设置按原模块的做法留在**二级页**：可改色的预设（AOSP / GoogleDot）被选中时，
 * 该行下方出现「颜色设置 ›」，点进去才是取色器。
 */
@Composable
fun CursorPage(
    cursorEnabled: Boolean,
    onCursorEnabledChange: (Boolean) -> Unit,
    onOpenColors: () -> Unit,
    padding: PaddingValues,
    scaffoldPadding: PaddingValues,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = cursorState

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

    // 连接状态跟随：轮询 RemoteConfig（Application.onCreate 里已注册监听器），
    // 不再自己 registerListener —— 那样在「服务早于本页注册就已绑定」时永远收不到回调。
    LaunchedEffect(Unit) {
        while (true) {
            val ready = RemoteConfig.isReady
            if (ready != state.bound) {
                state.bound = ready
                if (ready) {
                    state.loadFromPrefs()
                    state.refreshImported()
                    reloadThemes()
                }
            }
            delay(500)
        }
    }

    // 防抖落盘（拖滑块 / 取色器时不要每帧写）
    LaunchedEffect(state.preset, state.scale, state.fill, state.stroke, state.bound) {
        state.persistDebounced()
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
                    state.refreshImported()
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
            presetSection(state, onOpenColors)
            sizeSection(state)
            importSection(state, onClear, onDeleteTheme, onRename, onPickType)
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

/**
 * 主题预设。可改色的预设（AOSP / GoogleDot）被选中时，该行下方展开「颜色设置 ›」——
 * 和原 os4光标主题模块一致：颜色不在这里直接摊开，而是进二级页。
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
