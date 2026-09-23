package com.skyler.fancytype

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

    /** 默认值（dip），用于 UI 提示与日志对照 */
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

    // ---- 输入法服务 ----
    const val CLS_IME_SERVICE = "com.mi.ime.MiInputMethodService"

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
        "key_preview_bubble_corner_radius" to RES_BUBBLE_CORNER,
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
     * 按键预览气泡（点击按键时弹出来的放大气泡）的圆角。
     * 与按键圆角是两个独立参数：默认键 8dp / 气泡 14dp。
     */
    const val RES_BUBBLE_CORNER = 0x7f0701e3        // key_preview_bubble_corner_radius = 14.0dip
    const val NAME_BUBBLE_CORNER = "key_preview_bubble_corner_radius"
    const val ORIGINAL_BUBBLE_CORNER_DP = 14f

    /**
     * 键盘外边距（离屏幕左/右/下的距离）。
     *
     * 同一类里手机版与平板版、以及按导航方式分的底部变体，实际只会命中其中一个，
     * 不会叠加，所以**全部一起改**，不用逐机型判断。
     */
    val MARGIN_HORIZONTAL_NAMES = arrayOf(
        "keyboard_container_horizontal_padding",
        "keyboard_container_horizontal_padding_landscape",
        "keyboard_container_horizontal_padding_pad_portrait",
        "keyboard_container_horizontal_padding_q18_inner",
        "pad_keyboard_container_horizontal_padding_landscape",
        "pad_landscape_split_content_edge_padding",
        "keyboard_padding_q18_outer_landscape",
        "keyboard_padding_q18_outer_portrait",
    )

    val MARGIN_BOTTOM_NAMES = arrayOf(
        "pad_keyboard_bottom_margin",
        "keyboard_bottom_margin",
        "keyboard_bottom_margin_gesture_no_bar",
        "keyboard_bottom_margin_gesture_with_bar",
        "keyboard_bottom_margin_three_button",
        "keyboard_bottom_margin_with_bottom_view",
    )

    val MARGIN_NAMES = MARGIN_HORIZONTAL_NAMES + MARGIN_BOTTOM_NAMES

    /**
     * 按键圆角资源集合（统一改，不分横竖屏、不按角区分）。
     * 通用键圆角与横屏专用键圆角都会用到，全部纳入。
     */
    val KEY_CORNER_RES_IDS = intArrayOf(
        RES_KEY_CORNER, RES_KEY_LARGE_CORNER,
        RES_QWERTY_LAND_KEY_CORNER, RES_T9_LAND_KEY_CORNER,
    )

    /** 默认圆角（dip） */
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

    // ---- 超级材质（Hyper Material / 毛玻璃键盘背景）----
    //
    // 默认逻辑（bb.b0.j()）：
    //     map        = 解析 prefs["hyper_material_package_versions"]（缺失时由 allowed_packages 推导，值均为 1）
    //     linkedMap  = map 里 value <= 2 的项
    //     z10        = b0.s && pc.m.L0(linkedMap.keySet(), 当前前台包名)
    //     enable     = z10
    //     dark       = force_dark 命中包名（或内部标志）
    //     light      = !dark && z10 && force_light 命中包名
    //
    // 其中 b0.s = xe.b.c() && xe.b.b(service)：
    //     c() = SystemProperties["persist.sys.background_blur_supported"]
    //     b() = Secure["background_blur_enable"] == 1
    // 本机两者均为 true，所以唯一的门就是 pc.m.L0(...)。
    //
    // 设备实测 prefs 里只有 com.android.quicksearchbox 在白名单，且
    // hyper_material_package_versions **不存在**，因此 map 由 allowed_packages 推导。
    // Hook 选择「判定入口」pc.m 的集合包含方法而不是改 prefs：这样无论云端后续如何覆写
    // allowed_packages / package_versions，放行结果都由本模块决定。

    /** 集合包含判定的工具类 */
    const val CLS_COLLECTIONS_UTIL = "pc.m"

    /**
     * 该方法的名字**随版本变化**：`0.2.910` 是 `L0`，`0.2.974` 改成了 `x0`。
     * 但签名 `(Iterable, Object) -> boolean` 在两类版本里都唯一且稳定，
     * 所以实际定位按签名匹配（见 XposedEntry.findIterableContains），这个名字只用于日志。
     */
    const val M_CONTAINS = "L0"

    /** 材质状态机的宿主，用于把拦截范围限制在它自己的判定里 */
    const val CLS_MATERIAL_HELPER = "bb.b0"
    const val M_MATERIAL_APPLY = "j"

    /**
     * 材质判定里该方法的第一个参数始终是 `bb.b0.j()` 内部
     * `new LinkedHashMap()` 的 keySet，jar 里的类名就是这个（稳定，不含混淆编号）。
     */
    const val MATERIAL_GATE_SET_CLASS = "java.util.LinkedHashMap\$LinkedKeySet"

    // 目标应用自身的材质配置键（只读，用于诊断日志）
    const val KEY_MATERIAL_ALLOWED = "hyper_material_allowed_packages"
    const val KEY_MATERIAL_FORCE_DARK = "hyper_material_force_dark"
    const val KEY_MATERIAL_FORCE_LIGHT = "hyper_material_force_light"
    const val KEY_MATERIAL_VERSIONS = "hyper_material_package_versions"

    // ---- 材质「透明度」链路（诊断 + 修正）----

    /**
     * 离屏填充能力门：`z7.a.f18746a`。
     * 为 false 时 `bb.b0` 不会调 `setMiBlurWinType`，模糊拿不到背后的内容 → 背景看着是实心。
     */
    const val CLS_ADVANCED_VISUAL_GATE = "z7.a"
    const val F_SUPPORTS_OFFSCREEN_FILL = "f18746a"

    /** 模糊能力位所在类 `xe.b` 的静态字段 */
    const val CLS_BLUR_GATE = "xe.b"
    const val F_BLUR_SUPPORTED = "f18279a"       // persist.sys.background_blur_supported
    const val F_BLUR_VERSION = "f18281d"         // persist.sys.advanced_visual_release / background_blur_version
    const val F_BLUR_STATUS_DEFAULT = "f18280c"  // persist.sys.background_blur_status_default
    const val F_BIONIC_MATERIAL = "b"            // persist.sys.bionic_material_supported

    /** 材质描述符 `xe.e` 的字段 */
    const val F_DESC_BLEND = "f18303a"   // w5.i 混合色
    const val F_DESC_BLUR = "f18304c"    // d 模糊参数
    const val F_DESC_INNER = "f18305d"   // i3.h 内阴影
    const val F_DESC_CORNER = "f18306e"  // c 描边/圆角

    /** `d` 模糊参数里的字段 */
    const val F_BLUR_MODE = "f18298a"
    const val F_BLUR_RADIUS = "f18300d"
    const val F_BLUR_TYPE = "f18299c"

    /** 材质描述符应用入口：`xe.b.a(View, xe.e)` */
    const val CLS_MATERIAL_APPLIER = "xe.b"
    const val M_APPLY_MATERIAL = "a"
}
