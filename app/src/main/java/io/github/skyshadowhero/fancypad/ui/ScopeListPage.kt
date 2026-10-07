package io.github.skyshadowhero.fancypad.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一级页面「作用域」。
 *
 * 模块按 LSPosed 作用域分成三块：输入法外观（com.xiaomi.type）、系统光标（system）、
 * 平行窗口动画（com.android.systemui）。它们各自的功能设置都收在对应的作用域里，
 * 点进去才看得到 —— 这样一级页面就是一张「这个模块动了哪些东西」的总览。
 */
@Composable
fun ScopeListPage(
    onEnter: (AppScope) -> Unit,
    onAbout: () -> Unit,
    padding: PaddingValues,
    scaffoldPadding: PaddingValues,
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
    ) {
        item { SmallTitle("作用域") }
        item {
            Card {
                AppScope.entries.forEach { scope ->
                    ArrowPreference(
                        title = scope.label,
                        // 第一行是它对应的 LSPosed 作用域，第二行是这个作用域里有哪些功能
                        summary = scope.pkg + "\n" + scope.summary,
                        startAction = {
                            Icon(
                                imageVector = scope.icon,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MiuixTheme.colorScheme.onBackgroundVariant,
                            )
                        },
                        onClick = { onEnter(scope) },
                    )
                }
            }
        }
        item { SmallTitle("其它") }
        item {
            Card {
                ArrowPreference(
                    title = "关于",
                    summary = "版本 · 更新检查 · 权限与作用域说明",
                    onClick = onAbout,
                )
            }
        }
    }
}
