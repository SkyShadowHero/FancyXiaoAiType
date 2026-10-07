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
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一级页面「功能」。
 *
 * 只列功能名和图标，**不带描述** —— 每项的具体设置点进去才有。
 * 功能与 LSPosed 作用域的对应关系放在「关于」页。
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
        item {
            Card {
                AppScope.entries.forEach { scope ->
                    ArrowPreference(
                        title = scope.label,
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
        item {
            Card {
                ArrowPreference(
                    title = "关于",
                    onClick = onAbout,
                )
            }
        }
    }
}
