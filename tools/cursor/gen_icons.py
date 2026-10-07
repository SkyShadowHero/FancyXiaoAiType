#!/usr/bin/env python3
"""生成光标素材到模块 res，并生成 Java 表。

来源：
  material : ~/tmp/material-cursors（作者 varlesh，只取 light 变体，含阴影）
  windows  : ~/tmp/Win11-Cursor（MIT，.cur/.ani → PIL）
  macos    : ~/tmp/apple_cursor（GPL-3.0，SVG → rsvg）
  aosp     : ~/tmp/aosp-cursors（官方矢量，拆填充/描边层，运行时染色）

产物：
  res/drawable-nodpi/cur_{material,windows,macos}_<key>.png + prev_*.png
  res/drawable/aosp_<key>_{fill,stroke}.xml
  java/.../CursorIcons.java  {type, material, aospFill, aospStroke, windows, macos, hotX*10, hotY*10}
"""
import os
import re
import subprocess

HOME = os.path.expanduser("~")
MC = f"{HOME}/tmp/material-cursors/src/material_light_cursors"
WIN = f"{HOME}/tmp/Win11-Cursor/Windows 11 cursor/light"   # light = 白箭头黑边（Win11 默认款）
MAC = f"{HOME}/tmp/apple_cursor/svg"
AOSP = f"{HOME}/tmp/aosp-cursors/vector/drawable"
PROJ = f"{HOME}/cursor-module"
NODPI = f"{PROJ}/app/src/main/res/drawable-nodpi"
DRAW = f"{PROJ}/app/src/main/res/drawable"
JAVA = f"{PROJ}/app/src/main/java/io/github/skyshadowhero/cursor"

SIZE = 192
PREVIEW = 128

# type, aospKey, materialSvg, windowsCur, macosSvg, hotX, hotY
CURSORS = [
    (1000, "pointer_arrow", "default", "arrow.cur", "left_ptr", 4.5, 3.5),
    (1002, "pointer_context_menu", "context-menu", None, "context-menu", 4.5, 3.5),
    (1003, "pointer_hand", "pointer", "hand.cur", "hand2", 9.5, 2.5),
    (1004, "pointer_help", "help", "help.cur", "question_arrow", 4.5, 3.5),
    (1005, "pointer_wait", "wait-01", None, "wait", 12.0, 12.0),
    (1006, "pointer_cell", "cell", None, "plus", 12.0, 12.0),
    (1007, "pointer_crosshair", "crosshair", "crosshair.cur", "crosshair", 12.0, 12.0),
    (1008, "pointer_text", "text", "ibeam.cur", "xterm", 12.0, 11.0),
    (1009, "pointer_vertical_text", "vertical-text", None, "vertical-text", 12.0, 12.0),
    (1010, "pointer_alias", "alias", None, "link", 11.5, 11.5),
    (1011, "pointer_copy", "copy", None, "copy", 8.5, 7.5),
    (1012, "pointer_nodrop", "no-drop", "no.cur", "dnd_no_drop", 8.5, 7.5),
    (1013, "pointer_all_scroll", "all-scroll", "sizeall.cur", "move", 12.0, 12.0),
    (1014, "pointer_horizontal_double_arrow", "size_hor", "sizewe.cur", "sb_h_double_arrow", 12.0, 12.0),
    (1015, "pointer_vertical_double_arrow", "size_ver", "sizens.cur", "sb_v_double_arrow", 12.0, 12.0),
    (1016, "pointer_top_right_diagonal_double_arrow", "top_right_corner", "sizenwse.cur", "ur_angle", 12.0, 12.0),
    (1017, "pointer_top_left_diagonal_double_arrow", "top_left_corner", "sizenesw.cur", "ul_angle", 12.0, 12.0),
    (1018, "pointer_zoom_in", "zoom-in", None, "zoom-in", 10.5, 9.5),
    (1019, "pointer_zoom_out", "zoom-out", None, "zoom-out", 10.5, 9.5),
    (1020, "pointer_grab", "openhand", None, "hand1", 9.5, 4.5),
    (1021, "pointer_grabbing", None, None, None, 8.5, 7.5),
    (1022, "pointer_handwriting", "pencil", "nwpen.cur", "pencil", 8.25, 23.75),
    (1023, "pointer_spot_hover", None, "pin.cur", "dotbox", 12.0, 12.0),
    (1024, "pointer_spot_touch", None, None, "wayland-cursor", 12.0, 12.0),
    (1025, "pointer_spot_anchor", None, None, None, 12.0, 12.0),
]


def render_svg(svg, out, size=SIZE):
    os.makedirs(os.path.dirname(out), exist_ok=True)
    r = subprocess.run(["rsvg-convert", "-w", str(size), "-h", str(size), svg, "-o", out],
                       capture_output=True)
    if r.returncode != 0:
        print(f"    !! svg 失败 {os.path.basename(svg)}: {r.stderr.decode()[:100]}")
        return False
    return True


def render_mac_svg(name, out, size=SIZE):
    """apple_cursor 源文件用占位色（#00FF00 主体 / #0000FF 描边），按 render.json 的 macOS 方案替换。"""
    src = f"{MAC}/{name}.svg"
    if not os.path.exists(src):
        return False
    xml = open(src).read()
    for a, b in (("#00FF00", "#000000"), ("#00ff00", "#000000"),
                 ("#0000FF", "#FFFFFF"), ("#0000ff", "#FFFFFF")):
        xml = xml.replace(a, b)
    tmp = f"{HOME}/tmp/mac_recolor.svg"
    open(tmp, "w").write(xml)
    return render_svg(tmp, out, size)


def render_cur(cur, out, size=SIZE):
    """Windows .cur/.ico → PNG（PIL 直接读）。"""
    try:
        from PIL import Image
    except ImportError:
        return False
    try:
        with Image.open(cur) as im:
            sizes = im.info.get("sizes") or {im.size}
            best = max(sizes, key=lambda s: s[0] * s[1])
            if im.size != best:
                try:
                    im.size = best
                except Exception:
                    pass
            im = im.convert("RGBA")
            if im.size != (size, size):
                im = im.resize((size, size), Image.LANCZOS)
            os.makedirs(os.path.dirname(out), exist_ok=True)
            im.save(out, "PNG")
        return True
    except Exception as e:
        print(f"    !! .cur 失败 {os.path.basename(cur)}: {e}")
        return False


def aosp_arrow_svg(fill, stroke, out):
    """用官方 AOSP 箭头 path 合成 SVG（做预览图用）。"""
    src = open(f"{AOSP}/pointer_arrow_vector.xml").read()
    paths = re.findall(r"<path\b.*?/>", src, re.S)

    def d(p):
        return re.search(r'android:pathData="([^"]*)"', p, re.S).group(1)

    body = "".join(f'<path d="{d(p)}" fill="{fill}"/>' for p in paths if "VectorFill" in p)
    ring = "".join(f'<path d="{d(p)}" fill="{stroke}"/>' for p in paths if "VectorStroke" in p)
    svg = ('<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24">'
           + ring + body + "</svg>")
    open(out, "w").write(svg)
    return out


def main():
    os.makedirs(NODPI, exist_ok=True)
    stats = {"material": 0, "windows": 0, "macos": 0}
    for ptype, key, mc, win, mac, hx, hy in CURSORS:
        if mc and render_svg(f"{MC}/{mc}.svg", f"{NODPI}/cur_material_{key}.png"):
            stats["material"] += 1
        if win and render_cur(f"{WIN}/{win}", f"{NODPI}/cur_windows_{key}.png"):
            stats["windows"] += 1
        if mac and render_mac_svg(mac, f"{NODPI}/cur_macos_{key}.png"):
            stats["macos"] += 1

    # material wait 24 帧（动画光标用）
    frames = 0
    for i in range(1, 25):
        if render_svg(f"{MC}/wait-{i:02d}.svg", f"{NODPI}/wait_f{i:02d}.png", 96):
            frames += 1
    items = "".join(
        f'    <item android:drawable="@drawable/wait_f{i:02d}" android:duration="60" />\n'
        for i in range(1, 25))
    open(f"{DRAW}/cursor_wait_frames.xml", "w").write(
        '<?xml version="1.0" encoding="utf-8"?>\n<animation-list '
        'xmlns:android="http://schemas.android.com/apk/res/android" android:oneshot="false">\n'
        + items + '</animation-list>\n')
    open(f"{DRAW}/cursor_wait_icon.xml", "w").write(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<!-- 动画光标：框架 loadResource 会把 AnimationDrawable 变成多帧 PointerIcon -->\n'
        '<pointer-icon xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:bitmap="@drawable/cursor_wait_frames"\n'
        '    android:hotSpotX="12dp"\n'
        '    android:hotSpotY="12dp" />\n')
    print("wait 帧:", frames)

    render_svg(f"{MC}/default.svg", f"{NODPI}/prev_material.png", PREVIEW)
    render_mac_svg("left_ptr", f"{NODPI}/prev_macos.png", PREVIEW)
    render_cur(f"{WIN}/arrow.cur", f"{NODPI}/prev_windows.png", PREVIEW)
    tmp = "/data/data/com.termux/files/home/tmp"
    render_svg(aosp_arrow_svg("#1A1A1AFF", "#FFFFFFFF", f"{tmp}/prev_aosp.svg"),
               f"{NODPI}/prev_aosp.png", PREVIEW)
    render_svg(aosp_arrow_svg("#0A84FFFF", "#FFFFFFFF", f"{tmp}/prev_custom.svg"),
               f"{NODPI}/prev_custom.png", PREVIEW)
    print("图标：", stats)


def emit_aosp_layers():
    ok, skip, done = 0, [], set()
    for ptype, key, mc, win, mac, hx, hy in CURSORS:
        src = f"{AOSP}/{key}_vector.xml"
        if not os.path.exists(src):
            skip.append(key)
            continue
        xml = open(src).read()
        m = re.search(r"<vector\b[^>]*>", xml, re.S)
        if not m:
            skip.append(key + "(动画)")
            continue
        head = m.group(0)
        paths = re.findall(r"<path\b.*?/>", xml, re.S)
        layers = {"fill": [], "stroke": []}
        for p in paths:
            (layers["stroke"] if "pointerIconVectorStroke" in p else layers["fill"]).append(p)
        for layer, plist in layers.items():
            if not plist:
                continue
            body = "\n".join(re.sub(r"\?attr/pointerIconVector\w+", "#FFFFFFFF", p) for p in plist)
            open(f"{DRAW}/aosp_{key}_{layer}.xml", "w").write(
                f'<?xml version="1.0" encoding="utf-8"?>\n'
                f'<!-- AOSP 官方 {key}（{layer} 层，代码 tint） -->\n{head}\n{body}\n</vector>\n')
        ok += 1
        done.add(key)
    print(f"AOSP 拆层 {ok} 个，跳过 {skip}")
    return done


def emit_table():
    """已废弃：CursorIcons.java 现在由 tools/gen_themes.py 统一生成（新格式 18 列）。"""
    print("emit_table() 已废弃，跳过；请运行 tools/gen_themes.py")
    return

def _emit_table_legacy():
    def rid(prefix, key, path):
        return f"R.drawable.{prefix}_{key}" if os.path.exists(path) else "0"

    lines = ["package io.github.skyshadowhero.cursor;", "",
             "/** 自动生成（tools/gen_icons.py），不要手改。",
             " *  {type, material, aosp-fill, aosp-stroke, windows, macos, hotX(0.1dp), hotY(0.1dp)}",
             " *  资源为 0 表示该主题没有这个类型，代码会回退到 material。 */",
             "final class CursorIcons {", "    static final int[][] TABLE = {"]
    for ptype, key, mc, win, mac, hx, hy in CURSORS:
        mc_id = rid("cur_material", key, f"{NODPI}/cur_material_{key}.png")
        win_id = rid("cur_windows", key, f"{NODPI}/cur_windows_{key}.png")
        mac_id = rid("cur_macos", key, f"{NODPI}/cur_macos_{key}.png")
        af = rid("aosp", f"{key}_fill", f"{DRAW}/aosp_{key}_fill.xml")
        as_ = rid("aosp", f"{key}_stroke", f"{DRAW}/aosp_{key}_stroke.xml")
        lines.append(f"            {{{ptype}, {mc_id}, {af}, {as_}, {win_id}, {mac_id},"
                     f" {int(round(hx * 10))}, {int(round(hy * 10))}}},")
    lines += ["    };", "",
              "    /** 与 TABLE 同序的键名（Remote File 名 cust_<key>.png 用它） */",
              "    static final String[] KEYS = {"]
    for ptype, key, mc, win, mac, hx, hy in CURSORS:
        lines.append(f'            "{key}",')
    lines += ["    };", "", "    private CursorIcons() {}", "}", ""]
    open(f"{JAVA}/CursorIcons.java", "w").write("\n".join(lines))
    print("写 CursorIcons.java：", len(CURSORS), "条")


if __name__ == "__main__":
    emit_aosp_layers()          # 只负责拆 AOSP 官方矢量层
    # main()/emit_table() 是旧的素材与表生成，已废弃：
    #   CursorIcons.java 由 tools/gen_themes.py 生成
    #   prev_aosp.png 等预览已在仓库里
