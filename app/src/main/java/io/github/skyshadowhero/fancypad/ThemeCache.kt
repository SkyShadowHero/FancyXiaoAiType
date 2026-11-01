package io.github.skyshadowhero.fancypad

import android.content.Context

/**
 * 界面主题的**本地缓存**。
 *
 * 主题本身存在 RemotePreferences 里（跨进程、要 binder），但读它要等框架连上；
 * 那之前 UI 已经用默认值（Miuix）渲染过一帧，于是每次冷启动都会先闪一下 Miuix
 * 再变成 Glasmio。这里在**应用自己的 SharedPreferences**（同步、毫秒级）里存一份，
 * 启动时先按它渲染，等远端配置读进来再以远端为准。
 */
object ThemeCache {
    private const val FILE = "fancypad_ui_cache"
    private const val KEY_UI_THEME = "ui_theme"

    fun read(context: Context): Int = runCatching {
        context.applicationContext
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_UI_THEME, 0)
    }.getOrDefault(0)

    fun write(context: Context, value: Int) {
        runCatching {
            context.applicationContext
                .getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_UI_THEME, value)
                .apply()
        }
    }
}
