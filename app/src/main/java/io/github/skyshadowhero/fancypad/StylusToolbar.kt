package io.github.skyshadowhero.fancypad

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Redo
import top.yukonga.miuix.kmp.icon.extended.Send
import top.yukonga.miuix.kmp.icon.extended.Undo
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 手写工具条（Miuix）—— 只在随手写会话期间出现，可拖拽。
 *
 * 按钮就按需求这六个：**撤回 / 恢复 / 删除 / 发送 / 标点 / 展开虚拟键盘**，
 * 点「标点」在下方展开一排标点，点某个标点即上屏并收起。
 *
 * 「撤回 / 恢复」是**手写自己的历史**：模块只知道自己落过哪些字
 *（见 [StylusImeHooks.committedHistory]），所以撤回 = 删掉上一次手写落的文本，
 * 恢复 = 把它重新落回去。用键盘打的字不进这个历史（输入法拿不到宿主编辑器的撤销栈）。
 */
@Composable
internal fun StylusToolbar(
    canUndo: State<Boolean>,
    canRedo: State<Boolean>,
    punctuationOpen: State<Boolean>,
    onTogglePunctuation: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDelete: () -> Unit,
    onSend: () -> Unit,
    onPunctuation: (String) -> Unit,
    onShowKeyboard: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolbarButton(MiuixIcons.Undo, "撤回", canUndo.value, onUndo)
                ToolbarButton(MiuixIcons.Redo, "恢复", canRedo.value, onRedo)
                ToolbarButton(MiuixIcons.Delete, "删除", true, onDelete)
                ToolbarButton(MiuixIcons.Send, "发送", true, onSend)
                ToolbarButton(MiuixIcons.More, "标点", true, onTogglePunctuation)
                ToolbarButton(MiuixIcons.GridView, "键盘", true, onShowKeyboard)
            }

            if (punctuationOpen.value) {
                PunctuationPanel(onPunctuation)
            }
        }
    }
}

/** 一个图标按钮；[enabled] 为 false 时置灰且不响应。 */
@Composable
private fun ToolbarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = { if (enabled) onClick() },
        modifier = Modifier.size(44.dp),
    ) {
        Icon(
            icon,
            label,
            tint = if (enabled) MiuixTheme.colorScheme.onSurface
            else MiuixTheme.colorScheme.onBackgroundVariant,
        )
    }
}

/** 常用标点。点一下即上屏并收起（见调用方的 onPunctuation）。 */
private val PUNCTUATION = listOf(
    "，", "。", "？", "！", "、", "；", "：", "·",
    "“", "”", "‘", "’", "（", "）", "《", "》",
    "…", "—", "～", "％", "＠", "＆", "＊", "＃",
)

@Composable
private fun PunctuationPanel(onPick: (String) -> Unit) {
    Column(
        modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        PUNCTUATION.chunked(8).forEach { row ->
            Row(horizontalArrangement = Arrangement.Center) {
                row.forEach { mark ->
                    Text(
                        text = mark,
                        style = MiuixTheme.textStyles.title3,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .size(40.dp)
                            .clickable { onPick(mark) }
                            .padding(top = 6.dp),
                    )
                }
            }
        }
    }
}
