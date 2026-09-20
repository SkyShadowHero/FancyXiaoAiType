package com.skyler.fancytype

import android.content.SharedPreferences

/**
 * Hook 进程内的配置读取。
 *
 * 走 libxposed 的 RemotePreferences（存在 LSPosed 数据库），
 * 由模块 App（[com.skyler.fancytype.ui.SettingsPage]）写入。
 */
object ConfigLoader {

    @Volatile
    private var prefs: SharedPreferences? = null

    /**
     * 变化监听器必须持强引用，否则会被 GC 回收导致回调静默失效。
     */
    @Suppress("unused")
    @Volatile
    private var changeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun attach(p: SharedPreferences?) {
        prefs = p
        // 注册监听：设置页改动后，Hook 侧下次读取立即取到新值（真正的实时生效）。
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            L.i("event=remote_pref_changed key=$key")
        }
        changeListener = listener
        try {
            p?.registerOnSharedPreferenceChangeListener(listener)
        } catch (t: Throwable) {
            L.e("event=pref_listener_failed", t)
        }
    }

    val raw: SharedPreferences? get() = prefs

    /** 完整配置快照 */
    fun snapshot(): Cfg {
        val p = prefs ?: return Cfg.DEFAULT
        return try {
            // 旧键 gap_dp 可能被存成不同类型，用 runCatching 单独兜住，
            // 避免它的 ClassCastException 把整个快照打成默认值。
            val legacy = p.runCatching { getFloat(PrefKeys.GAP_LEGACY, Float.NaN) }.getOrDefault(Float.NaN)
            Cfg(
                gapEnabled = p.runCatching { getBoolean(PrefKeys.GAP_ENABLED, true) }.getOrDefault(true),
                gapLand = p.runCatching { getFloat(PrefKeys.GAP_LAND, 0f) }.getOrDefault(0f)
                    .takeIf { it > 0f } ?: PrefKeys.GAP_DEFAULT_LAND,
                gapPort = p.runCatching { getFloat(PrefKeys.GAP_PORT, 0f) }.getOrDefault(0f)
                    .takeIf { it > 0f } ?: PrefKeys.GAP_DEFAULT_PORT,
                portraitForceNormal = p.runCatching {
                    getBoolean(PrefKeys.PORTRAIT_FORCE_NORMAL, false)
                }.getOrDefault(false),
                cornerEnabled = p.runCatching { getBoolean(PrefKeys.CORNER_ENABLED, false) }.getOrDefault(false),
                cornerDp = p.runCatching { getFloat(PrefKeys.CORNER_DP, PrefKeys.CORNER_DEFAULT) }
                    .getOrDefault(PrefKeys.CORNER_DEFAULT),
                bubbleCornerDp = p.runCatching {
                    getFloat(PrefKeys.BUBBLE_CORNER_DP, PrefKeys.BUBBLE_CORNER_DEFAULT)
                }.getOrDefault(PrefKeys.BUBBLE_CORNER_DEFAULT),
                spaceEnabled = p.runCatching { getBoolean(PrefKeys.SPACE_ENABLED, false) }.getOrDefault(false),
                spaceKeyHLand = p.runCatching { getFloat(PrefKeys.SPACE_KEY_H_LAND, PrefKeys.SPACE_KEY_H_LAND_DEFAULT) }
                    .getOrDefault(PrefKeys.SPACE_KEY_H_LAND_DEFAULT),
                spaceKeyHPort = p.runCatching { getFloat(PrefKeys.SPACE_KEY_H_PORT, PrefKeys.SPACE_KEY_H_PORT_DEFAULT) }
                    .getOrDefault(PrefKeys.SPACE_KEY_H_PORT_DEFAULT),
                spaceKeyHorizLand = p.runCatching {
                    getFloat(PrefKeys.SPACE_KEY_HORIZ_LAND, PrefKeys.SPACE_KEY_HORIZ_LAND_DEFAULT)
                }.getOrDefault(PrefKeys.SPACE_KEY_HORIZ_LAND_DEFAULT),
                spaceKeyHorizPort = p.runCatching {
                    getFloat(PrefKeys.SPACE_KEY_HORIZ_PORT, PrefKeys.SPACE_KEY_HORIZ_PORT_DEFAULT)
                }.getOrDefault(PrefKeys.SPACE_KEY_HORIZ_PORT_DEFAULT),
                spaceRowLand = p.runCatching { getFloat(PrefKeys.SPACE_ROW_LAND, PrefKeys.SPACE_ROW_LAND_DEFAULT) }
                    .getOrDefault(PrefKeys.SPACE_ROW_LAND_DEFAULT),
                spaceRowPort = p.runCatching { getFloat(PrefKeys.SPACE_ROW_PORT, PrefKeys.SPACE_ROW_PORT_DEFAULT) }
                    .getOrDefault(PrefKeys.SPACE_ROW_PORT_DEFAULT),
                materialEnabled = p.runCatching { getBoolean(PrefKeys.MATERIAL_ENABLED, false) }
                    .getOrDefault(false),
                materialForceAll = p.runCatching { getBoolean(PrefKeys.MATERIAL_FORCE_ALL, false) }
                    .getOrDefault(false),
                materialPackages = p.runCatching { getString(PrefKeys.MATERIAL_PACKAGES, "") }
                    .getOrDefault("")
                    .let(::splitPackages),
            ).also {
                if (!legacy.isNaN()) L.sampled("legacy") { "event=legacy_gap_dp_seen value=$legacy" }
            }
        } catch (t: Throwable) {
            L.e("event=config_read_failed", t)
            Cfg.DEFAULT
        }
    }

    fun readLong(key: String, def: Long = 0L): Long = try {
        prefs?.runCatching { getLong(key, def) }?.getOrDefault(def) ?: def
    } catch (t: Throwable) {
        def
    }

    data class Cfg(
        val gapEnabled: Boolean,
        val gapLand: Float,
        val gapPort: Float,
        val portraitForceNormal: Boolean,
        val cornerEnabled: Boolean,
        val cornerDp: Float,
        /** 按键预览气泡圆角（dip） */
        val bubbleCornerDp: Float,
        // ---- 按键间距 / 键高（横竖屏各一套） ----
        val spaceEnabled: Boolean,
        val spaceKeyHLand: Float,
        val spaceKeyHPort: Float,
        val spaceKeyHorizLand: Float,
        val spaceKeyHorizPort: Float,
        val spaceRowLand: Float,
        val spaceRowPort: Float,
        // ---- 超级材质 ----
        val materialEnabled: Boolean,
        val materialForceAll: Boolean,
        val materialPackages: Set<String>,
    ) {
        /** 按当前是否横屏取对应间隙 */
        fun gapFor(landscape: Boolean): Float = if (landscape) gapLand else gapPort

        /** 该前台应用是否应当放行超级材质 */
        fun materialAllowedFor(pkg: String?): Boolean {
            if (!materialEnabled || pkg.isNullOrEmpty()) return false
            return materialForceAll || materialPackages.contains(pkg)
        }

        companion object {
            val DEFAULT = Cfg(
                gapEnabled = true,
                gapLand = PrefKeys.GAP_DEFAULT_LAND,
                gapPort = PrefKeys.GAP_DEFAULT_PORT,
                portraitForceNormal = false,
                cornerEnabled = false,
                cornerDp = PrefKeys.CORNER_DEFAULT,
                bubbleCornerDp = PrefKeys.BUBBLE_CORNER_DEFAULT,
                spaceEnabled = false,
                spaceKeyHLand = PrefKeys.SPACE_KEY_H_LAND_DEFAULT,
                spaceKeyHPort = PrefKeys.SPACE_KEY_H_PORT_DEFAULT,
                spaceKeyHorizLand = PrefKeys.SPACE_KEY_HORIZ_LAND_DEFAULT,
                spaceKeyHorizPort = PrefKeys.SPACE_KEY_HORIZ_PORT_DEFAULT,
                spaceRowLand = PrefKeys.SPACE_ROW_LAND_DEFAULT,
                spaceRowPort = PrefKeys.SPACE_ROW_PORT_DEFAULT,
                materialEnabled = false,
                materialForceAll = false,
                materialPackages = emptySet(),
            )
        }
    }
}
