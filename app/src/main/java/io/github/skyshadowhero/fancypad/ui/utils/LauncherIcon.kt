package io.github.skyshadowhero.fancypad.ui.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import io.github.skyshadowhero.fancypad.L

/** 只切换桌面 alias；真实设置 Activity 始终可由 LSPosed 打开。 */
object LauncherIcon {
    private const val ALIAS_SUFFIX = ".LauncherAlias"

    private fun component(context: Context) =
        ComponentName(context.packageName, context.packageName + ALIAS_SUFFIX)

    fun isHidden(context: Context): Boolean = runCatching {
        when (context.packageManager.getComponentEnabledSetting(component(context))) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> true
            else -> false
        }
    }.getOrDefault(false)

    /** @return 设置后的真实状态；失败时保持原状态。 */
    fun setHidden(context: Context, hidden: Boolean): Boolean {
        return runCatching {
            context.packageManager.setComponentEnabledSetting(
                component(context),
                if (hidden) {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                },
                PackageManager.DONT_KILL_APP,
            )
            isHidden(context)
        }.onFailure {
            L.e("event=launcher_icon_toggle_failed hidden=$hidden", it)
        }.getOrElse {
            isHidden(context)
        }
    }
}
