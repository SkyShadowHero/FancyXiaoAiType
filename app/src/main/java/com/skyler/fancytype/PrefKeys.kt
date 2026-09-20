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
}
