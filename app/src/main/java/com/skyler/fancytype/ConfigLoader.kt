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
        // 悬浮键盘尺寸上限处在热点路径上（夹取方法每帧都在调），不能按需读配置，
        // 所以这里先刷一次缓存；之后由下面的监听 + 缓存自带的节流刷新跟上改动。
        FloatingSize.refresh(p)
        // 注册监听：设置页改动后，Hook 侧下次读取立即取到新值（真正的实时生效）。
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            L.i("event=remote_pref_changed key=$key")
            if (key == PrefKeys.FLOAT_KB_UNLOCK || key == PrefKeys.FLOAT_KB_MAX_SCALE) {
                FloatingSize.refresh(prefs)
            }
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
                marginEnabled = p.runCatching { getBoolean(PrefKeys.MARGIN_ENABLED, false) }
                    .getOrDefault(false),
                marginHorizontalDp = p.runCatching {
                    getFloat(PrefKeys.MARGIN_HORIZONTAL_DP, PrefKeys.MARGIN_HORIZONTAL_DEFAULT)
                }.getOrDefault(PrefKeys.MARGIN_HORIZONTAL_DEFAULT),
                marginBottomDp = p.runCatching {
                    getFloat(PrefKeys.MARGIN_BOTTOM_DP, PrefKeys.MARGIN_BOTTOM_DEFAULT)
                }.getOrDefault(PrefKeys.MARGIN_BOTTOM_DEFAULT),
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
                // ---- 悬浮候选词窗口 ----
                candidateEnabled = p.runCatching { getBoolean(PrefKeys.CANDIDATE_ENABLED, false) }
                    .getOrDefault(false),
                candidateCornerDp = p.runCatching {
                    getFloat(PrefKeys.CANDIDATE_CORNER_DP, PrefKeys.CANDIDATE_CORNER_DEFAULT)
                }.getOrDefault(PrefKeys.CANDIDATE_CORNER_DEFAULT),
                candidateSpacingDp = p.runCatching {
                    getFloat(PrefKeys.CANDIDATE_SPACING_DP, PrefKeys.CANDIDATE_SPACING_DEFAULT)
                }.getOrDefault(PrefKeys.CANDIDATE_SPACING_DEFAULT),
                // ---- 悬浮键盘：圆角（工具栏与候选窗口共用）----
                floatBarEnabled = p.runCatching { getBoolean(PrefKeys.FLOATBAR_ENABLED, false) }
                    .getOrDefault(false),
                floatBarCornerDp = p.runCatching {
                    getFloat(PrefKeys.FLOATBAR_CORNER_DP, PrefKeys.FLOATBAR_CORNER_DEFAULT)
                }.getOrDefault(PrefKeys.FLOATBAR_CORNER_DEFAULT),
                // ---- 悬浮键盘：工具栏 ----
                toolbarShadowDp = p.runCatching {
                    getFloat(PrefKeys.TOOLBAR_SHADOW_DP, PrefKeys.TOOLBAR_SHADOW_DEFAULT)
                }.getOrDefault(PrefKeys.TOOLBAR_SHADOW_DEFAULT),
                toolbarButtonSpacingDp = p.runCatching {
                    getFloat(PrefKeys.TOOLBAR_BUTTON_SPACING_DP, PrefKeys.TOOLBAR_BUTTON_SPACING_DEFAULT)
                }.getOrDefault(PrefKeys.TOOLBAR_BUTTON_SPACING_DEFAULT),
                toolbarVPaddingDp = p.runCatching {
                    getFloat(PrefKeys.TOOLBAR_VPADDING_DP, PrefKeys.TOOLBAR_VPADDING_DEFAULT)
                }.getOrDefault(PrefKeys.TOOLBAR_VPADDING_DEFAULT),
                toolbarPaddingStartDp = p.runCatching {
                    getFloat(PrefKeys.TOOLBAR_PADDING_START_DP, PrefKeys.TOOLBAR_PADDING_START_DEFAULT)
                }.getOrDefault(PrefKeys.TOOLBAR_PADDING_START_DEFAULT),
                toolbarPaddingEndDp = p.runCatching {
                    getFloat(PrefKeys.TOOLBAR_PADDING_END_DP, PrefKeys.TOOLBAR_PADDING_END_DEFAULT)
                }.getOrDefault(PrefKeys.TOOLBAR_PADDING_END_DEFAULT),
                toolbarHandleOffsetStartDp = p.runCatching {
                    getFloat(
                        PrefKeys.TOOLBAR_HANDLE_OFFSET_START_DP,
                        PrefKeys.TOOLBAR_HANDLE_OFFSET_START_DEFAULT,
                    )
                }.getOrDefault(PrefKeys.TOOLBAR_HANDLE_OFFSET_START_DEFAULT),
                // ---- 悬浮键盘：候选窗口 ----
                candWinMaxWidthDp = p.runCatching {
                    getFloat(PrefKeys.CAND_WIN_MAX_WIDTH_DP, PrefKeys.CAND_WIN_MAX_WIDTH_DEFAULT)
                }.getOrDefault(PrefKeys.CAND_WIN_MAX_WIDTH_DEFAULT),
                candWinHPaddingDp = p.runCatching {
                    getFloat(PrefKeys.CAND_WIN_H_PADDING_DP, PrefKeys.CAND_WIN_H_PADDING_DEFAULT)
                }.getOrDefault(PrefKeys.CAND_WIN_H_PADDING_DEFAULT),
                candWinPinyinTopDp = p.runCatching {
                    getFloat(PrefKeys.CAND_WIN_PINYIN_TOP_DP, PrefKeys.CAND_WIN_PINYIN_TOP_DEFAULT)
                }.getOrDefault(PrefKeys.CAND_WIN_PINYIN_TOP_DEFAULT),
                candWinPinyinBottomDp = p.runCatching {
                    getFloat(PrefKeys.CAND_WIN_PINYIN_BOTTOM_DP, PrefKeys.CAND_WIN_PINYIN_BOTTOM_DEFAULT)
                }.getOrDefault(PrefKeys.CAND_WIN_PINYIN_BOTTOM_DEFAULT),
                candWinShadowDp = p.runCatching {
                    getFloat(PrefKeys.CAND_WIN_SHADOW_DP, PrefKeys.CAND_WIN_SHADOW_DEFAULT)
                }.getOrDefault(PrefKeys.CAND_WIN_SHADOW_DEFAULT),
                candWinSpacingDp = p.runCatching {
                    getFloat(PrefKeys.CAND_WIN_SPACING_DP, PrefKeys.CAND_WIN_SPACING_DEFAULT)
                }.getOrDefault(PrefKeys.CAND_WIN_SPACING_DEFAULT),
                candWinRowPaddingDp = p.runCatching {
                    getFloat(PrefKeys.CAND_WIN_ROW_PADDING_DP, PrefKeys.CAND_WIN_ROW_PADDING_DEFAULT)
                }.getOrDefault(PrefKeys.CAND_WIN_ROW_PADDING_DEFAULT),
                candWinCandFontDp = p.runCatching {
                    getFloat(PrefKeys.CAND_WIN_CAND_FONT_DP, PrefKeys.CAND_WIN_CAND_FONT_DEFAULT)
                }.getOrDefault(PrefKeys.CAND_WIN_CAND_FONT_DEFAULT),
                // ---- 悬浮候选窗口：字号覆盖（独立开关）----
                candFontEnabled = p.runCatching { getBoolean(PrefKeys.CAND_FONT_ENABLED, false) }
                    .getOrDefault(false),
                candWinNumberFontDp = p.runCatching {
                    getFloat(PrefKeys.CAND_WIN_NUMBER_FONT_DP, PrefKeys.CAND_WIN_NUMBER_FONT_DEFAULT)
                }.getOrDefault(PrefKeys.CAND_WIN_NUMBER_FONT_DEFAULT),
                candWinPinyinFontDp = p.runCatching {
                    getFloat(PrefKeys.CAND_WIN_PINYIN_FONT_DP, PrefKeys.CAND_WIN_PINYIN_FONT_DEFAULT)
                }.getOrDefault(PrefKeys.CAND_WIN_PINYIN_FONT_DEFAULT),
                // ---- 悬浮键盘：描边宽度 ----
                toolbarBorderWidthDp = p.runCatching {
                    getFloat(PrefKeys.TOOLBAR_BORDER_WIDTH_DP, PrefKeys.BORDER_WIDTH_DEFAULT)
                }.getOrDefault(PrefKeys.BORDER_WIDTH_DEFAULT),
                candWinBorderWidthDp = p.runCatching {
                    getFloat(PrefKeys.CAND_WIN_BORDER_WIDTH_DP, PrefKeys.BORDER_WIDTH_DEFAULT)
                }.getOrDefault(PrefKeys.BORDER_WIDTH_DEFAULT),
                // ---- 悬浮键盘：解锁最大尺寸 ----
                floatKbUnlock = p.runCatching { getBoolean(PrefKeys.FLOAT_KB_UNLOCK, false) }
                    .getOrDefault(false),
                floatKbMaxScale = p.runCatching {
                    getFloat(PrefKeys.FLOAT_KB_MAX_SCALE, PrefKeys.FLOAT_KB_MAX_SCALE_DEFAULT)
                }.getOrDefault(PrefKeys.FLOAT_KB_MAX_SCALE_DEFAULT),
                // 颜色相关配置已全部移除，见 PrefKeys 末尾的说明
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
        // ---- 键盘外边距（离屏幕左/右/下的距离，dip） ----
        val marginEnabled: Boolean,
        val marginHorizontalDp: Float,
        val marginBottomDp: Float,
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
        // ---- 悬浮候选词窗口（圆角 / 间距）----
        val candidateEnabled: Boolean,
        val candidateCornerDp: Float,
        val candidateSpacingDp: Float,
        // ---- 悬浮键盘：圆角（工具栏与候选窗口共用）----
        val floatBarEnabled: Boolean,
        val floatBarCornerDp: Float,
        // ---- 悬浮键盘：工具栏 ----
        val toolbarShadowDp: Float,
        val toolbarButtonSpacingDp: Float,
        val toolbarVPaddingDp: Float,
        val toolbarPaddingStartDp: Float,
        val toolbarPaddingEndDp: Float,
        /** 拖拽竖条的左边距（dip） */
        val toolbarHandleOffsetStartDp: Float,
        // ---- 悬浮键盘：候选窗口 ----
        val candWinMaxWidthDp: Float,
        val candWinHPaddingDp: Float,
        val candWinPinyinTopDp: Float,
        val candWinPinyinBottomDp: Float,
        val candWinShadowDp: Float,
        val candWinSpacingDp: Float,
        val candWinRowPaddingDp: Float,
        /** 悬浮候选窗口的候选词字号（dip），行高按比例联动 */
        val candWinCandFontDp: Float,
        // ---- 悬浮候选窗口：字号覆盖（独立于「启用悬浮键盘调节」）----
        val candFontEnabled: Boolean,
        /** 候选词序号字号（dip） */
        val candWinNumberFontDp: Float,
        /** 拼音字号（dip） */
        val candWinPinyinFontDp: Float,
        // ---- 悬浮键盘：描边宽度 ----
        val toolbarBorderWidthDp: Float,
        val candWinBorderWidthDp: Float,
        // ---- 悬浮键盘：解锁最大尺寸 ----
        /** 是否解锁输入法自带的 110% 尺寸上限 */
        val floatKbUnlock: Boolean,
        /** 解锁后的最大倍率（相对键盘自然宽度） */
        val floatKbMaxScale: Float,
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
                marginEnabled = false,
                marginHorizontalDp = PrefKeys.MARGIN_HORIZONTAL_DEFAULT,
                marginBottomDp = PrefKeys.MARGIN_BOTTOM_DEFAULT,
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
                candidateEnabled = false,
                candidateCornerDp = PrefKeys.CANDIDATE_CORNER_DEFAULT,
                candidateSpacingDp = PrefKeys.CANDIDATE_SPACING_DEFAULT,
                floatBarEnabled = false,
                floatBarCornerDp = PrefKeys.FLOATBAR_CORNER_DEFAULT,
                toolbarShadowDp = PrefKeys.TOOLBAR_SHADOW_DEFAULT,
                toolbarButtonSpacingDp = PrefKeys.TOOLBAR_BUTTON_SPACING_DEFAULT,
                toolbarVPaddingDp = PrefKeys.TOOLBAR_VPADDING_DEFAULT,
                toolbarPaddingStartDp = PrefKeys.TOOLBAR_PADDING_START_DEFAULT,
                toolbarPaddingEndDp = PrefKeys.TOOLBAR_PADDING_END_DEFAULT,
                toolbarHandleOffsetStartDp = PrefKeys.TOOLBAR_HANDLE_OFFSET_START_DEFAULT,
                candWinMaxWidthDp = PrefKeys.CAND_WIN_MAX_WIDTH_DEFAULT,
                candWinHPaddingDp = PrefKeys.CAND_WIN_H_PADDING_DEFAULT,
                candWinPinyinTopDp = PrefKeys.CAND_WIN_PINYIN_TOP_DEFAULT,
                candWinPinyinBottomDp = PrefKeys.CAND_WIN_PINYIN_BOTTOM_DEFAULT,
                candWinShadowDp = PrefKeys.CAND_WIN_SHADOW_DEFAULT,
                candWinSpacingDp = PrefKeys.CAND_WIN_SPACING_DEFAULT,
                candWinRowPaddingDp = PrefKeys.CAND_WIN_ROW_PADDING_DEFAULT,
                candWinCandFontDp = PrefKeys.CAND_WIN_CAND_FONT_DEFAULT,
                candFontEnabled = false,
                candWinNumberFontDp = PrefKeys.CAND_WIN_NUMBER_FONT_DEFAULT,
                candWinPinyinFontDp = PrefKeys.CAND_WIN_PINYIN_FONT_DEFAULT,
                toolbarBorderWidthDp = PrefKeys.BORDER_WIDTH_DEFAULT,
                candWinBorderWidthDp = PrefKeys.BORDER_WIDTH_DEFAULT,
                floatKbUnlock = false,
                floatKbMaxScale = PrefKeys.FLOAT_KB_MAX_SCALE_DEFAULT,
            )
        }
    }
}
