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
    // 这里的常量都**与输入法版本无关**：包名、prefs 键、资源名、默认 dp 值。
    // 「按混淆类名定位」的目标不放在这里 —— R8 每次发版都会改名，
    // 那些按输入法版本分档记录在 AppTargets / TargetCatalog 里。
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
    const val MATERIAL_GATE_SET_CLASS = "java.util.LinkedHashMap\$LinkedKeySet"

    // ==================================================================
    // 触屏候选词 / 悬浮键盘的工具栏与候选窗口（0.2.974 实测资源名）
    // ==================================================================
    //
    // 术语对照（App 内部类名与资源前缀都没混淆，但叫法和界面上不一致）：
    //   界面「工具栏」    <- App 内部 movable_bar_*   可拖动的输入法栏本体
    //   界面「候选窗口」  <- App 内部 floating_bar_*  悬浮键盘上的候选词窗口
    //   界面「候选词」    <- App 内部 candidate_*     触屏虚拟键盘上方的候选栏
    //
    // 「工具栏」「候选窗口」同属悬浮键盘（在「悬浮键盘」页里分两组），
    // 「候选词」属于触屏虚拟键盘（在「虚拟键盘」页里），三者互不共用资源。
    //
    // 三者都由 Compose 渲染，**样式全部来自 dimen 资源**（没有 XML 布局），
    // 所以直接复用已有的尺寸覆写通道（Resources.getDimension*），不需要 Hook
    // Compose 函数（Compose 函数也无法可靠 Hook）。
    //
    // 涉及的类（供定位）：
    //   候选词      aa/ca.java（候选渲染）、na/u.java
    //   工具栏      a/a.java + hb/n1.java（栏本体）、z7/s.java（mini 收起态）、
    //              hb/g.java（阴影）
    //   候选窗口    fa/l.java + rh/k.java（候选窗口本体与它的候选行）
    //
    // 分组纪律：只有「语义相同且默认值一致」的资源才登记进同一个滑块，
    // 否则会出现「拉一个滑块顺带改掉另一个默认值不同项」的意外。
    // 因此默认值不同的（工具栏左右内边距 32/18、工具栏阴影 8 与候选窗口阴影 3）各自单列。
    //
    // 注意：触屏「候选词」**没有独立的阴影 dimen**。`popup_selector_shadow_elevation`
    // 看着像，实测它属于「按键预览气泡的字符选择弹窗」（aa/ca.java 的 s0 方法内与
    // key_preview_bubble_shadow_* 一起使用）。所以「候选词」这一组不提供阴影项。

    // ---- 触屏虚拟键盘的候选词 ----

    /** 候选项圆角（默认 8dp） */
    const val NAME_CANDIDATE_CORNER = "candidate_item_corner_radius"

    /** 候选项之间的横向间距（默认 5dp） */
    const val NAME_CANDIDATE_SPACING = "candidate_item_spacing"

    /**
     * 触屏候选词相关资源名。
     *
     * 注意这里**没有** `candidate_item_horizontal_padding`：实测在真机上覆写了但界面无变化，
     * 且日志里该资源 ID 从未触发过 `dimen_override`（相邻的圆角与间距都触发了），
     * 说明它没走被 hook 的读取路径，属于改不动的项，故不提供设置。
     */
    val CANDIDATE_NAMES = arrayOf(
        NAME_CANDIDATE_CORNER,
        NAME_CANDIDATE_SPACING,
    )

    // ---- 悬浮键盘 · 圆角（工具栏与候选窗口共用）----

    /**
     * 圆角：工具栏本体、工具栏收起态（mini）、候选窗口三处默认都是 16dp。
     *
     * 三处刻意共用同一组滑块，不拆开：圆角在观感上属于这一整块悬浮 UI，
     * 拆成两个滑块很容易调出工具栏与候选窗口圆角不一致的割裂效果。
     * 因此它在界面上单独成组（「圆角」），不归到工具栏或候选窗口任一侧。
     */
    val FLOATBAR_CORNER_NAMES = arrayOf(
        "movable_bar_corner_radius",
        "floating_bar_corner_radius",
        "movable_bar_mini_corner_radius",
    )

    // ---- 悬浮键盘 · 工具栏（App 内部 movable_bar_*）----

    /** 工具栏阴影（默认 8dp） */
    const val NAME_TOOLBAR_SHADOW = "movable_bar_shadow_elevation"

    /** 工具栏上按钮之间的间距（默认 20dp） */
    const val NAME_TOOLBAR_BUTTON_SPACING = "movable_bar_button_spacing"

    /** 工具栏内行上下内边距（默认 11dp） */
    const val NAME_TOOLBAR_VPADDING = "movable_bar_row_padding_vertical"

    /**
     * 工具栏总高度（默认 52dp）。
     *
     * **必须跟着 [NAME_TOOLBAR_VPADDING] 一起变**，否则界面会出错：
     * 反编译显示布局是
     *     Modifier.height(<内容>, movable_bar_height)        // 外层固定高度
     *       └ Modifier.padding(vertical = row_padding_vertical)  // 内层 padding
     * 外层的 52dp 是写死的，而上下 padding 加在它内部。只调 padding 不动高度，
     * 内容就会被固定高度裁掉 —— 表现是「只看到上边距，下面一片空」。
     * 所以覆写时保持「内容可用高度」不变：height = 52 + 2 × (padding − 11)。
     */
    const val NAME_TOOLBAR_HEIGHT = "movable_bar_height"

    /** 工具栏默认高度（dip），用于与上下内边距联动计算 */
    const val ORIGINAL_TOOLBAR_HEIGHT_DP = 52f

    /**
     * 左侧拖拽竖条（DragHandle）的高度与顶部偏移（默认 20dp / 16dp）。
     *
     * 反编译显示它是**固定尺寸 + 固定偏移**画的：
     *     Modifier.offset(start = 14dp, top = 16dp) → size(3dp × 20dp)
     * 默认 16dp 的顶偏移刚好等于 (52 − 20) / 2，也就是垂直居中。
     * 但工具栏变高之后偏移不会自己变，竖条就会偏上、显得又短又歪。
     * 所以这两个值必须跟着 [NAME_TOOLBAR_HEIGHT] 一起算：
     * 竖条按高度等比缩放，再垂直居中。
     */
    const val NAME_TOOLBAR_HANDLE_HEIGHT = "movable_bar_drag_handle_height"

    /** 竖条默认高度（dip） */
    const val ORIGINAL_TOOLBAR_HANDLE_HEIGHT_DP = 20f

    /** 竖条距顶部的偏移（默认 16dp = 居中值） */
    const val NAME_TOOLBAR_HANDLE_OFFSET_TOP = "movable_bar_drag_handle_offset_top"

    /** 竖条距左侧的偏移（默认 14dp），即竖条的左边距 */
    const val NAME_TOOLBAR_HANDLE_OFFSET_START = "movable_bar_drag_handle_offset_start"

    /** 工具栏左内边距（默认 32dp） */
    const val NAME_TOOLBAR_PADDING_START = "movable_bar_padding_start"

    /** 工具栏右内边距（默认 18dp，与左不同，故单独一项） */
    const val NAME_TOOLBAR_PADDING_END = "movable_bar_padding_end"

    /** 工具栏描边宽度（默认 0.5dp） */
    const val NAME_TOOLBAR_BORDER_WIDTH = "movable_bar_border_width"

    /** 工具栏相关资源名 */
    val FLOATBAR_TOOLBAR_NAMES = arrayOf(
        NAME_TOOLBAR_SHADOW,
        NAME_TOOLBAR_BUTTON_SPACING,
        NAME_TOOLBAR_VPADDING,
        NAME_TOOLBAR_HEIGHT,
        NAME_TOOLBAR_HANDLE_HEIGHT,
        NAME_TOOLBAR_HANDLE_OFFSET_TOP,
        NAME_TOOLBAR_HANDLE_OFFSET_START,
        NAME_TOOLBAR_PADDING_START,
        NAME_TOOLBAR_PADDING_END,
        NAME_TOOLBAR_BORDER_WIDTH,
    )

    // ---- 悬浮键盘 · 收缩态（mini）----

    /**
     * 收缩态（mini）的整体几何：**按展开态高度等比缩放**。
     *
     * App 里这些资源都是固定值，与展开态没有任何联动。但展开态被调高之后，
     * 收起状态还是原来的小尺寸，两个形态看起来不像同一个东西，所以这里让收缩态
     * 跟着展开态一起缩放。
     *
     * 实现上是**缩放原始值**而不是写死新值（见 XposedEntry.resolveScale）：
     *     factor = 展开态高度 / 52
     * 这样即使 App 某版本调整了这些默认值，缩放依然跟着走，不会写死失配。
     * 默认设置下 factor = 1，收缩态与原生完全一致。
     *
     * 不含 `movable_bar_mini_corner_radius`（已在共用的圆角组里）
     * 与 `movable_bar_mini_exit_threshold`（拖拽阈值，属于行为不是外观）。
     *
     * 收缩态**不跟随**「竖条左边距」：曾试过用 `movable_bar_mini_h_padding` 驱动，
     * 但那是左右对称的 padding 且收缩态宽度固定（63dp），会连带挤到内容、
     * 还得为此补偿宽度，牵动过大，已撤销。该滑块只作用于展开态。
     */
    val FLOATBAR_MINI_SCALED_NAMES = arrayOf(
        "movable_bar_mini_height",
        "movable_bar_mini_width",
        "movable_bar_mini_h_padding",
        "movable_bar_mini_v_padding",
        "movable_bar_mini_input_icon_size",
        "movable_bar_mini_handle_width",
        "movable_bar_mini_gap",
    )

    // ---- 悬浮键盘 · 候选窗口（App 内部 floating_bar_*）----

    /**
     * 候选窗口最大宽度（默认 560dp）。
     *
     * 反编译（fa/l.java:119）里的宽度算法：
     *     maxW = floating_bar_max_width
     *     if (constraints.maxWidth < maxW) maxW = constraints.maxWidth   // 与可用宽度取小
     *     contentW = maxW − 2 × floating_bar_horizontal_padding
     *                     − (floating_bar_expand_button_spacer + floating_bar_expand_button_size)
     * 即「窗口宽 = min(本资源, 可用宽度)」，展开按钮固定占 29dp（7+22）。
     * 所以调大超过可用宽度没有意义，调小才会真正收窄窗口。
     */
    const val NAME_CAND_WIN_MAX_WIDTH = "floating_bar_max_width"

    /**
     * 候选窗口左右内边距（默认 16dp）。
     *
     * 同时用在两处（rh/k.java:23272 拼音行、fa/l.java:120 候选行）：
     * 既是拼音行与候选行的左右留白，也参与上面那条宽度公式。
     */
    const val NAME_CAND_WIN_H_PADDING = "floating_bar_horizontal_padding"

    /** 拼音行上边距（默认 12dp） */
    const val NAME_CAND_WIN_PINYIN_TOP = "floating_bar_pinyin_top_padding"

    /** 拼音行下边距（默认 8dp，与上不同，故单独一项） */
    const val NAME_CAND_WIN_PINYIN_BOTTOM = "floating_bar_pinyin_bottom_padding"

    /** 候选窗口阴影（默认 3dp，与工具栏阴影不同，故单独一项） */
    const val NAME_CAND_WIN_SHADOW = "floating_bar_shadow_elevation"

    /** 候选窗口内候选词之间的间距（默认 22dp） */
    const val NAME_CAND_WIN_SPACING = "floating_bar_candidate_item_spacing"

    /** 候选窗口描边宽度（默认 0.5dp） */
    const val NAME_CAND_WIN_BORDER_WIDTH = "floating_bar_border_width"

    // ---- 悬浮候选窗口 · 字号覆盖（独立开关，三个字号）----
    //
    // 为什么需要单独覆盖：输入法自带的「候选词大小」设置（prefs 键
    // `candidate_text_size_level`）是虚拟键盘与悬浮候选窗口**共用**的 ——
    // 它先算出一个缩放系数（`MiInputMethodService` 里
    // `scale = 字号表[级别] / 19`，而 19 正是 `candidate_text_size`），
    // 再乘到各自的基准值上（`fa/l.java:137` / `rh/k.java` 里 `有效字号 = scale × 本资源`）。
    //
    // 所以调这三个**基准值**只影响悬浮候选窗口，虚拟键盘那边完全不变。
    // 实测归属（均为悬浮窗口专属，与虚拟键盘的候选栏独立）：
    //   候选词字号 22dp + 行高 21.69dp  -> fa/l.java
    //   序号字号   16dp + 行高 15.91dp  -> fa/l.java
    //   拼音字号   17dp + 行高 15.91dp  -> rh/k.java
    //
    // 三个行高都不给滑块，而是**按各自字号等比联动**（只放大字号不动行高，
    // 字的上下边缘会被行高裁掉）。见 `XposedEntry.resolveScale`。

    /** 候选词字号（默认 22dp） */
    const val NAME_CAND_WIN_CAND_FONT = "floating_bar_candidate_font_size"

    /** 候选词行高（默认 21.69dp），按候选词字号等比联动 */
    const val NAME_CAND_WIN_CAND_LINE_HEIGHT = "floating_bar_candidate_line_height"

    /** 候选词序号字号（默认 16dp） */
    const val NAME_CAND_WIN_NUMBER_FONT = "floating_bar_number_font_size"

    /** 序号行高（默认 15.91dp），按序号字号等比联动 */
    const val NAME_CAND_WIN_NUMBER_LINE_HEIGHT = "floating_bar_number_line_height"

    /** 拼音字号（默认 17dp） */
    const val NAME_CAND_WIN_PINYIN_FONT = "floating_bar_pinyin_font_size"

    /** 拼音行高（默认 15.91dp），按拼音字号等比联动 */
    const val NAME_CAND_WIN_PINYIN_LINE_HEIGHT = "floating_bar_pinyin_line_height"

    /** 三个字号的原值（dip）—— 0.2.974 实测，用于算各自行高的联动比例 */
    const val ORIGINAL_CAND_FONT_DP = 22f
    const val ORIGINAL_NUMBER_FONT_DP = 16f
    const val ORIGINAL_PINYIN_FONT_DP = 17f

    /** 候选窗口内候选词行的上下内边距（上、下默认都是 12dp） */
    val CAND_WIN_ROW_PADDING_NAMES = arrayOf(
        "floating_bar_candidate_row_top_padding",
        "floating_bar_candidate_row_bottom_padding",
    )

    /** 候选窗口相关资源名 */
    val FLOATBAR_CAND_NAMES = arrayOf(
        NAME_CAND_WIN_MAX_WIDTH,
        NAME_CAND_WIN_H_PADDING,
        NAME_CAND_WIN_PINYIN_TOP,
        NAME_CAND_WIN_PINYIN_BOTTOM,
        NAME_CAND_WIN_SHADOW,
        NAME_CAND_WIN_SPACING,
        NAME_CAND_WIN_CAND_FONT,
        NAME_CAND_WIN_CAND_LINE_HEIGHT,
        NAME_CAND_WIN_NUMBER_FONT,
        NAME_CAND_WIN_NUMBER_LINE_HEIGHT,
        NAME_CAND_WIN_PINYIN_FONT,
        NAME_CAND_WIN_PINYIN_LINE_HEIGHT,
        NAME_CAND_WIN_BORDER_WIDTH,
    ) + CAND_WIN_ROW_PADDING_NAMES

    // ---- 悬浮键盘 · 颜色：已全部移除 ----
    //
    // 两个窗口的背景色与描边色**不是资源**，是运行时 Compose Color，装在两个主题
    // 数据类里（工具栏 `na.y` 12 参构造、候选窗口 `na.g` 4 参构造，
    // 两套配色都在 na/x.java 里 new 出来；字段真实名都是 a = 背景、b = 描边）。
    //
    // 所以只能 hook 构造函数改写参数，而**实测这条路会把悬浮 UI 搞挂**：
    // 改背景色后打不开，只改描边色同样打不开；第二次尝试已加了 runCatching
    // 降级保护（改写失败即用原始参数继续、proceed 只调用一次）仍然复现。
    // 说明问题不在「改写代码抛异常」，而在改写主题构造参数这个机制本身，
    // 或 App 对颜色值有额外依赖。
    //
    // 因此颜色功能（hook、配置、界面）已全部删除，不再保留相关常量。
    // 若将来还要做：不要再走构造函数改写，改考虑 hook 绘制调用点 ——
    // 工具栏 a/a.java:198、候选窗口 fa/l.java:335 的 Modifier.background / Modifier.border。

    /** 悬浮键盘相关资源名（圆角 + 工具栏 + 收缩态 + 候选窗口） */
    val FLOATBAR_NAMES = FLOATBAR_CORNER_NAMES + FLOATBAR_TOOLBAR_NAMES +
        FLOATBAR_MINI_SCALED_NAMES + FLOATBAR_CAND_NAMES

    /** 本功能新增的全部资源名（供运行时统一登记） */
    val FLOATING_NAMES = CANDIDATE_NAMES + FLOATBAR_NAMES
}
