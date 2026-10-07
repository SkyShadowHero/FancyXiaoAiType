package io.github.skyshadowhero.fancypad;

/** 自动生成（tools/gen_themes.py，AOSP 拆层来自 tools/gen_icons.py），不要手改。
 *  每行 18 列：
 *   0 type  1 aospFill  2 aospStroke  3 aospHotX(0.1dp)  4 aospHotY
 *   5 gdFill  6 gdStroke  7 gdHotX  8 gdHotY        ← GoogleDot 可改色（mask 两层）
 *   9 materialImg 10 matHotX 11 matHotY            ← 原色图，不可改色
 *  12 appleImg   13 appleHotX 14 appleHotY
 *  15 breezeImg  16 brzHotX  17 brzHotY
 *  某主题某类型为 0 → 代码回退用 AOSP 官方矢量层画。 */
final class CursorIcons {
    static final int[][] TABLE = {
            {1000, R.drawable.aosp_pointer_arrow_fill, R.drawable.aosp_pointer_arrow_stroke, 45, 35, R.drawable.googledot_pointer_arrow_f, R.drawable.googledot_pointer_arrow_s, 120, 120, R.drawable.material_pointer_arrow, 10, 10, R.drawable.apple_pointer_arrow, 75, 36, R.drawable.breezex_pointer_arrow, 64, 28},
            {1001, R.drawable.aosp_pointer_context_menu_fill, R.drawable.aosp_pointer_context_menu_stroke, 45, 35, R.drawable.googledot_pointer_context_menu_f, R.drawable.googledot_pointer_context_menu_s, 120, 120, R.drawable.material_pointer_context_menu, 10, 10, R.drawable.apple_pointer_context_menu, 37, 36, R.drawable.breezex_pointer_context_menu, 64, 28},
            {1002, R.drawable.aosp_pointer_hand_fill, R.drawable.aosp_pointer_hand_stroke, 95, 25, R.drawable.googledot_pointer_hand_f, R.drawable.googledot_pointer_hand_s, 120, 120, R.drawable.material_pointer_hand, 90, 40, R.drawable.apple_pointer_hand, 86, 49, R.drawable.breezex_pointer_hand, 109, 34},
            {1003, R.drawable.aosp_pointer_help_fill, R.drawable.aosp_pointer_help_stroke, 45, 35, R.drawable.googledot_pointer_help_f, R.drawable.googledot_pointer_help_s, 120, 120, R.drawable.material_pointer_help, 10, 10, R.drawable.apple_pointer_help, 120, 158, R.drawable.breezex_pointer_help, 64, 28},
            {1004, 0, 0, 120, 120, R.drawable.googledot_pointer_wait_f, R.drawable.googledot_pointer_wait_s, 120, 120, 0, 110, 110, 0, 120, 120, 0, 120, 120},
            {1006, R.drawable.aosp_pointer_cell_fill, R.drawable.aosp_pointer_cell_stroke, 120, 120, R.drawable.googledot_pointer_cell_f, R.drawable.googledot_pointer_cell_s, 120, 120, R.drawable.material_pointer_cell, 110, 110, R.drawable.apple_pointer_cell, 120, 120, R.drawable.breezex_pointer_cell, 120, 120},
            {1007, R.drawable.aosp_pointer_crosshair_fill, R.drawable.aosp_pointer_crosshair_stroke, 120, 120, R.drawable.googledot_pointer_crosshair_f, R.drawable.googledot_pointer_crosshair_s, 120, 120, R.drawable.material_pointer_crosshair, 120, 110, R.drawable.apple_pointer_crosshair, 120, 120, R.drawable.breezex_pointer_crosshair, 120, 120},
            {1008, R.drawable.aosp_pointer_text_fill, R.drawable.aosp_pointer_text_stroke, 120, 110, R.drawable.googledot_pointer_text_f, R.drawable.googledot_pointer_text_s, 120, 120, R.drawable.material_pointer_text, 110, 110, R.drawable.apple_pointer_text, 120, 128, R.drawable.breezex_pointer_text, 120, 120},
            {1009, R.drawable.aosp_pointer_vertical_text_fill, R.drawable.aosp_pointer_vertical_text_stroke, 120, 120, R.drawable.googledot_pointer_vertical_text_f, R.drawable.googledot_pointer_vertical_text_s, 120, 120, R.drawable.material_pointer_vertical_text, 120, 100, R.drawable.apple_pointer_vertical_text, 124, 121, R.drawable.breezex_pointer_vertical_text, 120, 120},
            {1010, R.drawable.aosp_pointer_alias_fill, R.drawable.aosp_pointer_alias_stroke, 115, 115, R.drawable.googledot_pointer_alias_f, R.drawable.googledot_pointer_alias_s, 120, 120, R.drawable.material_pointer_alias, 10, 10, R.drawable.apple_pointer_alias, 109, 99, R.drawable.breezex_pointer_alias, 65, 28},
            {1011, R.drawable.aosp_pointer_copy_fill, R.drawable.aosp_pointer_copy_stroke, 85, 75, R.drawable.googledot_pointer_copy_f, R.drawable.googledot_pointer_copy_s, 120, 120, R.drawable.material_pointer_copy, 10, 10, R.drawable.apple_pointer_copy, 52, 16, R.drawable.breezex_pointer_copy, 64, 28},
            {1012, R.drawable.aosp_pointer_nodrop_fill, R.drawable.aosp_pointer_nodrop_stroke, 85, 75, R.drawable.googledot_pointer_nodrop_f, R.drawable.googledot_pointer_nodrop_s, 120, 120, R.drawable.material_pointer_nodrop, 110, 110, R.drawable.apple_pointer_nodrop, 120, 120, R.drawable.breezex_pointer_nodrop, 120, 120},
            {1013, R.drawable.aosp_pointer_all_scroll_fill, R.drawable.aosp_pointer_all_scroll_stroke, 120, 120, R.drawable.googledot_pointer_all_scroll_f, R.drawable.googledot_pointer_all_scroll_s, 120, 120, R.drawable.material_pointer_all_scroll, 110, 110, R.drawable.apple_pointer_all_scroll, 120, 120, R.drawable.breezex_pointer_all_scroll, 120, 120},
            {1014, R.drawable.aosp_pointer_horizontal_double_arrow_fill, R.drawable.aosp_pointer_horizontal_double_arrow_stroke, 120, 120, R.drawable.googledot_pointer_horizontal_double_arrow_f, R.drawable.googledot_pointer_horizontal_double_arrow_s, 120, 120, R.drawable.material_pointer_horizontal_double_arrow, 110, 110, R.drawable.apple_pointer_horizontal_double_arrow, 120, 120, R.drawable.breezex_pointer_horizontal_double_arrow, 119, 117},
            {1015, R.drawable.aosp_pointer_vertical_double_arrow_fill, R.drawable.aosp_pointer_vertical_double_arrow_stroke, 120, 120, R.drawable.googledot_pointer_vertical_double_arrow_f, R.drawable.googledot_pointer_vertical_double_arrow_s, 120, 120, R.drawable.material_pointer_vertical_double_arrow, 110, 110, R.drawable.apple_pointer_vertical_double_arrow, 120, 120, R.drawable.breezex_pointer_vertical_double_arrow, 118, 117},
            {1016, R.drawable.aosp_pointer_top_right_diagonal_double_arrow_fill, R.drawable.aosp_pointer_top_right_diagonal_double_arrow_stroke, 120, 120, R.drawable.googledot_pointer_top_right_diagonal_double_arrow_f, R.drawable.googledot_pointer_top_right_diagonal_double_arrow_s, 120, 120, R.drawable.material_pointer_top_right_diagonal_double_arrow, 210, 20, R.drawable.apple_pointer_top_right_diagonal_double_arrow, 120, 120, R.drawable.breezex_pointer_top_right_diagonal_double_arrow, 118, 117},
            {1017, R.drawable.aosp_pointer_top_left_diagonal_double_arrow_fill, R.drawable.aosp_pointer_top_left_diagonal_double_arrow_stroke, 120, 120, R.drawable.googledot_pointer_top_left_diagonal_double_arrow_f, R.drawable.googledot_pointer_top_left_diagonal_double_arrow_s, 120, 120, R.drawable.material_pointer_top_left_diagonal_double_arrow, 20, 20, R.drawable.apple_pointer_top_left_diagonal_double_arrow, 120, 120, R.drawable.breezex_pointer_top_left_diagonal_double_arrow, 119, 116},
            {1018, R.drawable.aosp_pointer_zoom_in_fill, R.drawable.aosp_pointer_zoom_in_stroke, 105, 95, R.drawable.googledot_pointer_zoom_in_f, R.drawable.googledot_pointer_zoom_in_s, 120, 120, R.drawable.material_pointer_zoom_in, 110, 110, R.drawable.apple_pointer_zoom_in, 92, 92, R.drawable.breezex_pointer_zoom_in, 102, 96},
            {1019, R.drawable.aosp_pointer_zoom_out_fill, R.drawable.aosp_pointer_zoom_out_stroke, 105, 95, R.drawable.googledot_pointer_zoom_out_f, R.drawable.googledot_pointer_zoom_out_s, 120, 120, R.drawable.material_pointer_zoom_out, 110, 110, R.drawable.apple_pointer_zoom_out, 92, 92, R.drawable.breezex_pointer_zoom_out, 102, 96},
            {1020, R.drawable.aosp_pointer_grab_fill, R.drawable.aosp_pointer_grab_stroke, 95, 45, R.drawable.googledot_pointer_grab_f, R.drawable.googledot_pointer_grab_s, 120, 120, R.drawable.material_pointer_grab, 110, 110, R.drawable.apple_pointer_grab, 125, 76, R.drawable.breezex_pointer_grab, 135, 84},
            {1021, R.drawable.aosp_pointer_grabbing_fill, R.drawable.aosp_pointer_grabbing_stroke, 85, 75, R.drawable.googledot_pointer_grabbing_f, R.drawable.googledot_pointer_grabbing_s, 120, 120, 0, 85, 75, R.drawable.apple_pointer_grabbing, 130, 80, R.drawable.breezex_pointer_grabbing, 132, 74},
            {1022, R.drawable.aosp_pointer_handwriting_fill, R.drawable.aosp_pointer_handwriting_stroke, 82, 238, R.drawable.googledot_pointer_handwriting_f, R.drawable.googledot_pointer_handwriting_s, 120, 120, R.drawable.material_pointer_handwriting, 110, 110, R.drawable.apple_pointer_handwriting, 35, 204, R.drawable.breezex_pointer_handwriting, 38, 196},
            {2000, R.drawable.aosp_pointer_spot_hover_fill, 0, 120, 120, R.drawable.googledot_pointer_spot_hover_f, R.drawable.googledot_pointer_spot_hover_s, 120, 120, 0, 120, 120, 0, 120, 120, 0, 120, 120},
            {2001, R.drawable.aosp_pointer_spot_touch_fill, 0, 120, 120, R.drawable.googledot_pointer_spot_touch_f, R.drawable.googledot_pointer_spot_touch_s, 120, 120, 0, 120, 120, 0, 120, 120, 0, 120, 120},
            {2002, R.drawable.aosp_pointer_spot_anchor_fill, 0, 120, 120, 0, 0, 120, 120, 0, 120, 120, 0, 120, 120, 0, 120, 120},
    };

    /** 与 TABLE 同序的类型键名（Remote File 名 cust_<key>.png 用它） */
    static final String[] KEYS = {
            "pointer_arrow",
            "pointer_context_menu",
            "pointer_hand",
            "pointer_help",
            "pointer_wait",
            "pointer_cell",
            "pointer_crosshair",
            "pointer_text",
            "pointer_vertical_text",
            "pointer_alias",
            "pointer_copy",
            "pointer_nodrop",
            "pointer_all_scroll",
            "pointer_horizontal_double_arrow",
            "pointer_vertical_double_arrow",
            "pointer_top_right_diagonal_double_arrow",
            "pointer_top_left_diagonal_double_arrow",
            "pointer_zoom_in",
            "pointer_zoom_out",
            "pointer_grab",
            "pointer_grabbing",
            "pointer_handwriting",
            "pointer_spot_hover",
            "pointer_spot_touch",
            "pointer_spot_anchor",
    };

    /** 可改色的 mask 主题（TABLE 第 5/6 列） */
    static final String[] THEMES = {"googledot"};

    /** 可改色主题的默认（填充, 描边）色，索引与 THEMES 对应 */
    static final int[][] THEME_COLORS = {
            {0xFFFFFFFF, 0xFF000000},   // GoogleDot：白心黑边
    };

    private CursorIcons() {}
}
