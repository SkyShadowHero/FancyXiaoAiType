package io.github.skyshadowhero.fancypad.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.skyshadowhero.fancypad.ui.ds.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Theme

/**
 * 一级页面「功能」。
 *
 * 只列功能名和图标，**不带描述** —— 每项的具体设置点进去才有。
 * 功能与 LSPosed 作用域的对应关系放在「关于」页。
 *
 * 最下面那张卡是**整个界面自己**的设置：主题（Miuix / Glasmio 两套外观）与关于。
 */
@Composable
fun ScopeListPage(
    onEnter: (AppScope) -> Unit,
    onTheme: () -> Unit,
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
                            // iconRes 是模块自带的矢量（系统 / 输入法 / 设计稿提取），
                            // 没给才退回 Miuix 图标。两者都靠 tint 跟随主题。
                            if (scope.iconRes != 0) {
                                Icon(
                                    painter = painterResource(scope.iconRes),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    tint = DsTheme.colorScheme.onBackgroundVariant,
                                )
                            } else {
                                Icon(
                                    imageVector = scope.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    tint = DsTheme.colorScheme.onBackgroundVariant,
                                )
                            }
                        },
                        onClick = { onEnter(scope) },
                    )
                }
            }
        }
        item {
            Card {
                ArrowPreference(
                    title = "主题设置",
                    summary = "Miuix / Glasmio 两套界面外观",
                    startAction = {
                        Icon(
                            imageVector = MiuixIcons.Theme,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = DsTheme.colorScheme.onBackgroundVariant,
                        )
                    },
                    onClick = onTheme,
                )
                ArrowPreference(
                    title = "关于",
                    onClick = onAbout,
                )
            }
        }
    }
}
