package com.skyler.fancytype

/** 设置项键名（RemotePreferences 共用的 group 与 key） */
object PrefKeys {
    const val GROUP = "fancytype"

    const val GAP_ENABLED = "gap_enabled"

    /** 横屏中心间隙（dip） */
    const val GAP_LAND = "gap_land"

    /** 竖屏中心间隙（dip） */
    const val GAP_PORT = "gap_port"

    /** 兼容旧键：早期版本只有一个 gap_dp */
    const val GAP_LEGACY = "gap_dp"

    const val PORTRAIT_FORCE_NORMAL = "portrait_force_normal"

    /** 主题模式（ThemeMode 序号），跨启动记忆 */
    const val THEME_MODE = "theme_mode"

    // ---- 按键圆角（不分横竖屏，统一一个值） ----
    const val CORNER_ENABLED = "corner_enabled"
    const val CORNER_DP = "corner_dp"

    /** 按键预览气泡圆角（dip）。与按键圆角独立，默认 14dp。 */
    const val BUBBLE_CORNER_DP = "bubble_corner_dp"

    /** 圆角范围（dip） */
    const val CORNER_MIN = 0f
    const val CORNER_MAX = 48f
    const val CORNER_DEFAULT = 8f
    const val BUBBLE_CORNER_DEFAULT = 14f

    // ---- 键盘外边距（离屏幕左/右/下的距离，dip） ----
    const val MARGIN_ENABLED = "margin_enabled"
    const val MARGIN_HORIZONTAL_DP = "margin_horizontal_dp"
    const val MARGIN_BOTTOM_DP = "margin_bottom_dp"

    const val MARGIN_MIN = 0f
    const val MARGIN_MAX = 80f

    /** 默认参考值：平板横屏左右 25dp、底部 17dp */
    const val MARGIN_HORIZONTAL_DEFAULT = 25f
    const val MARGIN_BOTTOM_DEFAULT = 17f

    // ---- 按键间距 / 键高（二级「间距」页，横竖屏各一套） ----
    const val SPACE_ENABLED = "space_enabled"
    const val SPACE_KEY_H_LAND = "space_key_h_land"
    const val SPACE_KEY_H_PORT = "space_key_h_port"
    const val SPACE_KEY_HORIZ_LAND = "space_key_horiz_land"
    const val SPACE_KEY_HORIZ_PORT = "space_key_horiz_port"
    const val SPACE_ROW_LAND = "space_row_land"
    const val SPACE_ROW_PORT = "space_row_port"

    /** 间距范围（dip） */
    const val SPACE_MIN = 0f
    const val SPACE_MAX = 40f

    /**
     * 按键高度上限（dip）。比横向/行间距高：
     * 默认键高就有 51.5dp，若沿用 40 的上限则「上限 6/10 = 24dp」的默认值本身就会误报，
     * 所以键高单独给 100dp，安全阈值 60dp，默认值落在安全区内。
     */
    const val SPACE_KEY_H_MAX = 100f

    /** 默认（dip） */
    const val SPACE_KEY_H_LAND_DEFAULT = 51.5f
    const val SPACE_KEY_H_PORT_DEFAULT = 51.5f
    const val SPACE_KEY_HORIZ_LAND_DEFAULT = 8f
    const val SPACE_KEY_HORIZ_PORT_DEFAULT = 8f
    const val SPACE_ROW_LAND_DEFAULT = 10f
    const val SPACE_ROW_PORT_DEFAULT = 10f

    /**
     * 间隙范围（dip）。默认横屏 284 / 竖屏 113。
     *
     * 上限不写死：按「对应朝向的屏幕宽度 × 0.9」动态计算
     * （横屏用长边、竖屏用短边），[GAP_MAX_FALLBACK] 仅作下限兜底。
     */
    const val GAP_MIN = 0f
    const val GAP_MAX_FALLBACK = 600f

    /** 默认值 */
    const val GAP_DEFAULT_LAND = 284f
    const val GAP_DEFAULT_PORT = 113f

    /** 动态上限占对应屏幕宽度的比例 */
    const val GAP_MAX_RATIO = 0.9f

    // ---- 超级材质（放行 Hyper Material 毛玻璃键盘背景） ----
    const val MATERIAL_ENABLED = "material_enabled"

    /** 强制所有应用：不看清单，任何前台应用弹出键盘都启用材质 */
    const val MATERIAL_FORCE_ALL = "material_force_all"

    /**
     * 手动选择的应用。存成换行分隔的字符串而不是 StringSet：
     * RemotePreferences 的集合类型在跨进程同步上更容易出意外，字符串最稳且便于日志排查。
     */
    const val MATERIAL_PACKAGES = "material_packages"

    // ==================================================================
    // 悬浮候选词窗口（圆角 / 间距）
    // ==================================================================

    const val CANDIDATE_ENABLED = "candidate_enabled"

    /** 候选项圆角（dip），默认 8dp */
    const val CANDIDATE_CORNER_DP = "candidate_corner_dp"

    /** 候选项横向间距（dip），默认 5dp */
    const val CANDIDATE_SPACING_DP = "candidate_spacing_dp"

    const val CANDIDATE_CORNER_MIN = 0f
    const val CANDIDATE_CORNER_MAX = 48f
    const val CANDIDATE_CORNER_DEFAULT = 8f

    const val CANDIDATE_SPACING_MIN = 0f
    const val CANDIDATE_SPACING_MAX = 40f
    const val CANDIDATE_SPACING_DEFAULT = 5f

    // ==================================================================
    // 悬浮键盘（工具栏 + 候选窗口）
    //
    // 术语：界面上把 App 内部的 movable_bar_* 叫「工具栏」，
    //       floating_bar_* 叫「候选窗口」。
    // 圆角是两者**共用**的，所以只有一组键。
    // ==================================================================

    /** 整页总开关 */
    const val FLOATBAR_ENABLED = "floatbar_enabled"

    /** 圆角（dip）：工具栏本体 + 工具栏收起态 + 候选窗口，三处共用，默认 16dp */
    const val FLOATBAR_CORNER_DP = "floatbar_corner_dp"

    const val FLOATBAR_CORNER_MIN = 0f
    const val FLOATBAR_CORNER_MAX = 48f
    const val FLOATBAR_CORNER_DEFAULT = 16f

    // ---- 工具栏 ----

    /** 工具栏阴影（dip），默认 8dp */
    const val TOOLBAR_SHADOW_DP = "toolbar_shadow_dp"

    /** 按钮间距（dip），默认 20dp */
    const val TOOLBAR_BUTTON_SPACING_DP = "toolbar_button_spacing_dp"

    /** 行上下内边距（dip），默认 11dp */
    const val TOOLBAR_VPADDING_DP = "toolbar_vpadding_dp"

    /** 左内边距（dip），默认 32dp */
    const val TOOLBAR_PADDING_START_DP = "toolbar_padding_start_dp"

    /** 右内边距（dip），默认 18dp */
    const val TOOLBAR_PADDING_END_DP = "toolbar_padding_end_dp"

    /** 拖拽竖条的左边距（dip），默认 14dp */
    const val TOOLBAR_HANDLE_OFFSET_START_DP = "toolbar_handle_offset_start_dp"

    const val TOOLBAR_SHADOW_MIN = 0f
    const val TOOLBAR_SHADOW_MAX = 32f
    const val TOOLBAR_SHADOW_DEFAULT = 8f

    const val TOOLBAR_BUTTON_SPACING_MIN = 0f
    const val TOOLBAR_BUTTON_SPACING_MAX = 48f
    const val TOOLBAR_BUTTON_SPACING_DEFAULT = 20f

    const val TOOLBAR_VPADDING_MIN = 0f
    const val TOOLBAR_VPADDING_MAX = 48f
    const val TOOLBAR_VPADDING_DEFAULT = 11f

    const val TOOLBAR_SIDE_PADDING_MIN = 0f
    const val TOOLBAR_SIDE_PADDING_MAX = 64f
    const val TOOLBAR_PADDING_START_DEFAULT = 32f
    const val TOOLBAR_PADDING_END_DEFAULT = 18f

    const val TOOLBAR_HANDLE_OFFSET_START_MIN = 0f
    const val TOOLBAR_HANDLE_OFFSET_START_MAX = 64f
    const val TOOLBAR_HANDLE_OFFSET_START_DEFAULT = 14f

    // ---- 候选窗口 ----

    /** 候选窗口最大宽度（dip），默认 560dp。实际宽度还会与可用宽度取小，见 Target.NAME_CAND_WIN_MAX_WIDTH */
    const val CAND_WIN_MAX_WIDTH_DP = "candwin_max_width_dp"

    /** 候选窗口左右内边距（dip），默认 16dp。拼音行与候选行共用，且参与宽度计算 */
    const val CAND_WIN_H_PADDING_DP = "candwin_h_padding_dp"

    /** 拼音行上边距（dip），默认 12dp */
    const val CAND_WIN_PINYIN_TOP_DP = "candwin_pinyin_top_dp"

    /** 拼音行下边距（dip），默认 8dp */
    const val CAND_WIN_PINYIN_BOTTOM_DP = "candwin_pinyin_bottom_dp"

    /** 候选窗口阴影（dip），默认 3dp */
    const val CAND_WIN_SHADOW_DP = "candwin_shadow_dp"

    /** 候选词之间的间距（dip），默认 22dp */
    const val CAND_WIN_SPACING_DP = "candwin_spacing_dp"

    /** 候选词行的上下内边距（dip），上下默认都是 12dp */
    const val CAND_WIN_ROW_PADDING_DP = "candwin_row_padding_dp"

    // ==================================================================
    // 悬浮候选窗口 · 字号覆盖（独立开关，三个字号）
    //
    // 输入法自带的「候选词大小」设置是虚拟键盘与悬浮候选窗口共用的，
    // 这里覆盖的是悬浮窗口专属的三个基准字号资源，因此只影响悬浮窗口。
    // 三个行高按各自字号等比联动，不单独设。
    // ==================================================================

    /** 「覆盖候选窗口字体大小」独立开关；不开启时完全不碰字号资源 */
    const val CAND_FONT_ENABLED = "cand_font_enabled"

    /** 候选词字号（dip），默认 22dp */
    const val CAND_WIN_CAND_FONT_DP = "candwin_cand_font_dp"

    /** 候选词序号字号（dip），默认 16dp */
    const val CAND_WIN_NUMBER_FONT_DP = "candwin_number_font_dp"

    /** 拼音字号（dip），默认 17dp */
    const val CAND_WIN_PINYIN_FONT_DP = "candwin_pinyin_font_dp"

    const val CAND_WIN_CAND_FONT_MIN = 10f
    const val CAND_WIN_CAND_FONT_MAX = 48f
    const val CAND_WIN_CAND_FONT_DEFAULT = 22f

    const val CAND_WIN_NUMBER_FONT_MIN = 8f
    const val CAND_WIN_NUMBER_FONT_MAX = 40f
    const val CAND_WIN_NUMBER_FONT_DEFAULT = 16f

    const val CAND_WIN_PINYIN_FONT_MIN = 8f
    const val CAND_WIN_PINYIN_FONT_MAX = 40f
    const val CAND_WIN_PINYIN_FONT_DEFAULT = 17f

    const val CAND_WIN_MAX_WIDTH_MIN = 100f
    const val CAND_WIN_MAX_WIDTH_MAX = 1000f
    const val CAND_WIN_MAX_WIDTH_DEFAULT = 560f

    const val CAND_WIN_H_PADDING_MIN = 0f
    const val CAND_WIN_H_PADDING_MAX = 64f
    const val CAND_WIN_H_PADDING_DEFAULT = 16f

    const val CAND_WIN_PINYIN_TOP_DEFAULT = 12f
    const val CAND_WIN_PINYIN_BOTTOM_DEFAULT = 8f

    /** 拼音行边距范围（dip），上下共用 */
    const val CAND_WIN_PINYIN_MIN = 0f
    const val CAND_WIN_PINYIN_MAX = 48f

    const val CAND_WIN_SHADOW_DEFAULT = 3f

    const val CAND_WIN_SPACING_MIN = 0f
    const val CAND_WIN_SPACING_MAX = 48f
    const val CAND_WIN_SPACING_DEFAULT = 22f

    const val CAND_WIN_ROW_PADDING_DEFAULT = 12f

    // ==================================================================
    // 悬浮键盘 · 描边宽度
    //
    // 描边**宽度**是 dimen 资源，走和其它尺寸一样的资源覆写通道。
    //
    // 注意：描边**颜色**不在这里 —— 见文件末尾的说明。
    // ==================================================================

    const val TOOLBAR_BORDER_WIDTH_DP = "toolbar_border_width_dp"
    const val CAND_WIN_BORDER_WIDTH_DP = "candwin_border_width_dp"

    const val BORDER_WIDTH_MIN = 0f
    const val BORDER_WIDTH_MAX = 8f
    const val BORDER_WIDTH_DEFAULT = 0.5f

    // ==================================================================
    // 悬浮键盘 · 解锁最大尺寸
    //
    // 悬浮键盘（平板上可拖动、可缩放的触屏键盘）不是按像素定尺寸的，
    // 而是按「自然宽度的倍率」布局，输入法把这个倍率夹在 0.65 ~ 1.1 之间
    // （窗口宽度同理夹在 0.65× ~ 1.1× 自然宽度）。于是平板上最大也只能到
    // 自然尺寸的 110%，看着很小。
    //
    // 这里只抬**上限**，下限的 0.65 保持不动 —— 下限决定键盘最小能缩到多小，
    // 不该被这个开关影响。
    // ==================================================================

    /** 解锁悬浮键盘最大尺寸 */
    const val FLOAT_KB_UNLOCK = "float_kb_unlock"

    /** 解锁后的最大倍率（相对键盘自然宽度） */
    const val FLOAT_KB_MAX_SCALE = "float_kb_max_scale"

    /** 输入法自身的缩放下限，只用于识别与换算，不修改 */
    const val FLOAT_KB_NATIVE_MIN_SCALE = 0.65f

    /** 输入法自身的缩放上限；不高于它就等于没解锁 */
    const val FLOAT_KB_NATIVE_MAX_SCALE = 1.1f

    const val FLOAT_KB_MAX_SCALE_MIN = 1f

    /**
     * 上限给到 4 倍。实际能被屏幕装下多少由输入法自己再夹一次
     * （窗口位置会按可用区域收拢），所以这里放宽不会把键盘顶出屏幕。
     */
    const val FLOAT_KB_MAX_SCALE_MAX = 4f

    /**
     * 默认值就是输入法原本的上限 1.1 倍 —— 滑块上的吸附点也在这里，
     * 标出「不放大」的位置，方便随时拖回来对照。
     */
    const val FLOAT_KB_MAX_SCALE_DEFAULT = FLOAT_KB_NATIVE_MAX_SCALE

    // ==================================================================
    // 颜色功能已全部移除（背景色与描边色）
    //
    // 两个窗口的背景色与描边色**不是资源**，是运行时 Compose Color，
    // 装在两个主题数据类里（工具栏 na.y、候选窗口 na.g），
    // 因此只能 hook 它们的构造函数、用 Chain.proceed(newArgs) 改写参数。
    //
    // **实测这条路会把悬浮 UI 搞挂**：改背景色后打不开；只改描边色同样打不开。
    // 第二次尝试已经加了 runCatching 降级保护（改写失败就用原始参数继续，
    // proceed 只调用一次），仍然复现 —— 说明问题不在「我的代码抛异常」，
    // 而在「改写主题构造参数」这个机制本身，或 App 对颜色值有额外依赖。
    //
    // 所以颜色相关功能（键、配置字段、界面项、hook）已**全部删除**。
    // 若将来还要做，不要再走构造函数改写这条路，考虑改 hook 绘制调用点
    // （例如 a/a.java:198 与 fa/l.java:335 的 Modifier.background / Modifier.border）。
    // ==================================================================
}
