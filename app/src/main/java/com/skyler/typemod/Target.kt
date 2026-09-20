package com.skyler.typemod

/**
 * 目标 App（超级小爱输入法 / Xiaomi Hyper XiaoAi Keyboard）专有常量。
 *
 * 全部基于样本 0.2.910.ba19145a (versionCode 20910)。
 * R8 混淆类名与资源 ID 均随版本变化，升级 APK 后必须重新核对。
 */
object Target {

    const val PACKAGE = "com.xiaomi.type"
    const val VERSION_NAME = "0.2.910.ba19145a"
    const val VERSION_CODE = 20910

    // ---- 分离键盘几何 / 开关 ----
    const val CLS_PAD_SPLIT_DIMS = "la.n"      // PadSplitQwertyDims（用于观测真实几何）
    const val CLS_PREF_FACADE = "n9.e"         // SharedPreferences 门面（带 ConcurrentHashMap 缓存）
    const val M_PREF_BOOL = "a"                // static boolean a(String key, boolean def)
    const val M_SPLIT_ENABLED_GETTER = "o"     // static boolean o() -> split_keyboard_enabled
    const val KEY_SPLIT_ENABLED = "split_keyboard_enabled"

    // ---- 分离键盘中心间隙资源 ----
    // 权威来源：apktool 解码的 res/values/public.xml，并与 z9.b 的 R 常量双向核对
    //   public.xml 0x7f070a02  <->  z9.b = 2131167746 = 0x7F070A02
    const val RES_CENTER_GAP_LAND = 0x7f070a02      // pad_qwerty_landscape_split_center_gap = 284.0dip
    const val RES_CENTER_GAP_PORT = 0x7f070a15      // pad_qwerty_portrait_split_center_gap  = 113.0dip
    const val RES_T9_GAP_LAND = 0x7f070a30          // pad_t9_landscape_split_center_gap    = 274.0dip
    const val RES_T9_GAP_PORT = 0x7f070a3f          // pad_t9_portrait_split_center_gap     =  94.0dip
    const val RES_GAP_GENERIC = 0x7f070b78          // split_keyboard_center_gap            = 124.0dip
    const val RES_GAP_Q18_INNER_LAND = 0x7f070b79   // split_keyboard_center_gap_q18_inner_landscape = 119.0dip
    const val RES_GAP_Q18_INNER_PORT = 0x7f070b7a   // split_keyboard_center_gap_q18_inner_portrait  =  81.0dip

    // 注：分离键盘横屏间隙走 bb.h1.h() -> Resources.getDimension()，
    // 不经过 Compose 尺寸解析器 a.a.t，因此 Hook 点是 Resources 而非 a.a.t。

    /** 原厂默认值（dip），用于 UI 提示与日志对照 */
    const val ORIGINAL_GAP_LAND_DP = 284f
    const val ORIGINAL_GAP_PORT_DP = 113f

    /** 横屏间隙资源集合 */
    val LANDSCAPE_GAP_RES_IDS = intArrayOf(
        RES_CENTER_GAP_LAND, RES_T9_GAP_LAND,
        RES_GAP_GENERIC, RES_GAP_Q18_INNER_LAND,
    )

    /** 竖屏间隙资源集合 */
    val PORTRAIT_GAP_RES_IDS = intArrayOf(
        RES_CENTER_GAP_PORT, RES_T9_GAP_PORT,
        RES_GAP_Q18_INNER_PORT,
    )

    val ALL_GAP_RES_IDS = LANDSCAPE_GAP_RES_IDS + PORTRAIT_GAP_RES_IDS

    // ---- 输入法服务（用于「重启输入法」） ----
    const val CLS_IME_SERVICE = "com.mi.ime.MiInputMethodService"
    const val M_IME_ON_WINDOW_SHOWN = "onWindowShown"

    /**
     * 轮询重启信号的挂载点：(方法名, 参数个数)。
     * 多挂几个点，任何一次输入会话开始都会立刻检查信号，不必死等键盘弹出。
     */
    val IME_POLL_POINTS = arrayOf(
        "onWindowShown" to 0,
        "onStartInput" to 2,       // (EditorInfo, boolean)
        "onStartInputView" to 2,   // (EditorInfo, boolean)
    )

    /**
     * 资源名映射（用于运行时按名解析 ID）。
     *
     * 为什么不全用硬编码 ID：资源 ID 会随版本变化（已实测 0.2.596 / 0.2.790 / 0.2.910 三版），
     * 而资源**名**是稳定的。运行时用 Resources.getIdentifier(name, "dimen", 包名) 解析，
     * 解析失败则回退到下面的硬编码 ID（针对 0.2.910 实测值）。
     */
    val RES_NAMES: Map<String, Int> = linkedMapOf(
        "pad_qwerty_landscape_split_center_gap" to RES_CENTER_GAP_LAND,
        "pad_qwerty_portrait_split_center_gap" to RES_CENTER_GAP_PORT,
        "pad_t9_landscape_split_center_gap" to RES_T9_GAP_LAND,
        "pad_t9_portrait_split_center_gap" to RES_T9_GAP_PORT,
        "split_keyboard_center_gap" to RES_GAP_GENERIC,
        "split_keyboard_center_gap_q18_inner_landscape" to RES_GAP_Q18_INNER_LAND,
        "split_keyboard_center_gap_q18_inner_portrait" to RES_GAP_Q18_INNER_PORT,
        "keyboard_key_corner_radius" to RES_KEY_CORNER,
        "keyboard_key_large_corner_radius" to RES_KEY_LARGE_CORNER,
        "pad_qwerty_landscape_key_corner_radius" to RES_QWERTY_LAND_KEY_CORNER,
        "pad_t9_landscape_key_corner_radius" to RES_T9_LAND_KEY_CORNER,
        "pad_qwerty_landscape_key_height" to RES_KEY_HEIGHT_LAND,
        "keyboard_key_height_pad_portrait" to RES_KEY_HEIGHT_PORT,
        "pad_qwerty_landscape_key_spacing" to RES_KEY_SPACING_LAND,
        "keyboard_key_spacing_pad_portrait" to RES_KEY_SPACING_PORT,
        "pad_qwerty_landscape_row_spacing" to RES_ROW_SPACING_LAND,
        "keyboard_key_vertical_spacing_pad_portrait" to RES_ROW_SPACING_PORT,
    )

    /** 各功能对应的资源名（供运行时解析） */
    val GAP_LAND_NAMES = arrayOf(
        "pad_qwerty_landscape_split_center_gap", "pad_t9_landscape_split_center_gap",
        "split_keyboard_center_gap", "split_keyboard_center_gap_q18_inner_landscape",
    )
    val GAP_PORT_NAMES = arrayOf(
        "pad_qwerty_portrait_split_center_gap", "pad_t9_portrait_split_center_gap",
        "split_keyboard_center_gap_q18_inner_portrait",
    )
    val CORNER_NAMES = arrayOf(
        "keyboard_key_corner_radius", "keyboard_key_large_corner_radius",
        "pad_qwerty_landscape_key_corner_radius", "pad_t9_landscape_key_corner_radius",
    )
    const val NAME_KEY_HEIGHT_LAND = "pad_qwerty_landscape_key_height"
    const val NAME_KEY_HEIGHT_PORT = "keyboard_key_height_pad_portrait"
    const val NAME_KEY_SPACING_LAND = "pad_qwerty_landscape_key_spacing"
    const val NAME_KEY_SPACING_PORT = "keyboard_key_spacing_pad_portrait"
    const val NAME_ROW_SPACING_LAND = "pad_qwerty_landscape_row_spacing"
    const val NAME_ROW_SPACING_PORT = "keyboard_key_vertical_spacing_pad_portrait"

    // ---- 按键圆角资源 ----
    // 实测：keyboard_key_corner_radius 被「每个按键」读取（同一资源、无按角区分），
    // 所以只能统一改按键圆角；左下/右下角并非独立参数。
    const val RES_KEY_CORNER = 0x7f070208            // keyboard_key_corner_radius = 8.0dip（实测 20.65px @2.58）
    const val RES_KEY_LARGE_CORNER = 0x7f07020e      // keyboard_key_large_corner_radius = 22.0dip
    const val RES_QWERTY_LAND_KEY_CORNER = 0x7f0709fa // pad_qwerty_landscape_key_corner_radius = 8.0dip
    const val RES_T9_LAND_KEY_CORNER = 0x7f070a28   // pad_t9_landscape_key_corner_radius = 8.0dip
    const val RES_KB_CONTAINER_CORNER = 0x7f0701fc   // keyboard_container_corner_radius = 24.0dip（实测 61.95px）

    /**
     * 按键圆角资源集合（统一改，不分横竖屏、不按角区分）。
     * 通用键圆角与横屏专用键圆角都会用到，全部纳入。
     */
    val KEY_CORNER_RES_IDS = intArrayOf(
        RES_KEY_CORNER, RES_KEY_LARGE_CORNER,
        RES_QWERTY_LAND_KEY_CORNER, RES_T9_LAND_KEY_CORNER,
    )

    /** 原厂默认圆角（dip） */
    const val ORIGINAL_KEY_CORNER_DP = 8f
    const val ORIGINAL_KEY_LARGE_CORNER_DP = 22f

    // ---- 按键间距 / 键高资源（横屏取 pad_landscape 版，竖屏取 pad_portrait 版）----
    const val RES_KEY_HEIGHT_LAND = 0x7f0709fb   // pad_qwerty_landscape_key_height        = 51.5dip
    const val RES_KEY_HEIGHT_PORT = 0x7f07020b   // keyboard_key_height_pad_portrait       = 51.5dip
    const val RES_KEY_SPACING_LAND = 0x7f0709fc  // pad_qwerty_landscape_key_spacing       =  8.0dip
    const val RES_KEY_SPACING_PORT = 0x7f070212  // keyboard_key_spacing_pad_portrait      =  8.0dip
    const val RES_ROW_SPACING_LAND = 0x7f070a00  // pad_qwerty_landscape_row_spacing       = 10.0dip
    const val RES_ROW_SPACING_PORT = 0x7f070218  // keyboard_key_vertical_spacing_pad_portrait = 10.0dip

    val ALL_SPACE_RES_IDS = intArrayOf(
        RES_KEY_HEIGHT_LAND, RES_KEY_HEIGHT_PORT,
        RES_KEY_SPACING_LAND, RES_KEY_SPACING_PORT,
        RES_ROW_SPACING_LAND, RES_ROW_SPACING_PORT,
    )
}
