#!/usr/bin/env python3
"""把 MiuiWMShellResources 里控制菜单按钮的矢量图标提取成 App 自己的 drawable。

App 进程拿不到 `com.android.wm.shell` 的资源包（那是 SystemUI 侧的），
所以只能把矢量图抄一份到 App 里。颜色统一成纯黑：App 侧用 ColorFilter 染色，
日/夜主题都能自适应（原图那道 30% alpha 的外框描边会原样保留）。

用法（Termux）：
    python3 gen_caption_icons.py <wmshell-res.apk> <app/src/main/res/drawable>
"""
import os
import re
import subprocess
import sys

APK = sys.argv[1]
OUT_DIR = sys.argv[2]

# 输出名 -> 源 drawable 名（都是 20 viewport / 10dp 的字形矢量）
ICONS = {
    "cap_btn_fullscreen": "caption_normal_fullscreen",
    "cap_btn_casting": "caption_normal_padcasting",
    "cap_btn_split_left": "caption_normal_splitleft",
    "cap_btn_split_right": "caption_normal_splitright",
    "cap_btn_freeform": "caption_normal_freeform",
    "cap_btn_new_window": "caption_newwindow",
    "cap_btn_close": "caption_close",
}

SKIP = {"http://schemas.android.com/apk/res/android:pathData"}  # 特殊处理（带 Raw 尾巴）

# 关闭按钮的「−」变体：保留圆角外框，把 × 那条 path 换成一根圆头横线。
# 几何和 Hook 侧的 FrameWithMinusDrawable 对齐：横线本体半长 3.887，
# 圆头会各多出半个线宽（0.9），所以起止点收到 7.013 / 12.987。
MINUS_OUT = "cap_btn_close_minus"
MINUS_SRC = "caption_close"
MINUS_PATH = {
    "pathData": "M7.013,10L12.987,10",
    "strokeColor": "#ff000000",
    "strokeWidth": "1.8",
    "strokeLineCap": "round",
}


def dump(name):
    return subprocess.run(
        ["aapt2", "dump", "xmltree", APK, "--file", f"res/drawable/{name}.xml"],
        capture_output=True, text=True, check=True).stdout


def parse_paths(xml):
    """从 aapt2 的 xmltree 输出里抓 <path> 的属性。"""
    paths = []
    cur = None
    for raw in xml.splitlines():
        line = raw.strip()
        if line.startswith("E: path"):
            cur = {}
            paths.append(cur)
            continue
        if not line.startswith("A: ") or cur is None:
            continue
        body = line[3:]
        # aapt2 的格式是 `android:name(0x01010404)=value`（带资源 id 的属性）；
        # 少数属性是 `android:name =value`，两种都要吃得下。
        key, _, value = body.partition("=")
        key = re.sub(r"\(0x[0-9a-fA-F]+\)\s*$", "", key).strip()
        key = key.split(":")[-1].strip()
        value = value.strip()
        if value.startswith('"'):
            # pathData 后面可能跟 (Raw: "...")，只取第一个引号对
            end = value.find('"', 1)
            value = value[1:end]
        cur[key] = value
    return paths


def norm_color(value):
    """透明色保留；其它一律换成纯黑，交给 App 侧 ColorFilter 染色。"""
    v = value.lstrip("#")
    if len(v) == 8 and v[:2] == "00":
        return "#00000000"
    if len(v) == 8:
        return "#ff000000"
    return "#ff000000"


def norm_fill_type(value):
    return "evenOdd" if value == "1" else "nonZero"


# aapt2 把这些枚举打成数字，但 aapt 链接资源时只认名字，必须翻译回来
STROKE_LINE_CAP = {"0": "butt", "1": "round", "2": "square"}
STROKE_LINE_JOIN = {"0": "miter", "1": "round", "2": "bevel"}


def norm_enum(value, table):
    return table.get(value, table.get("0"))


def build(paths):
    out = []
    for p in paths:
        attrs = []
        if "pathData" in p:
            attrs.append(f'android:pathData="{p["pathData"]}"')
        if "fillColor" in p:
            attrs.append(f'android:fillColor="{norm_color(p["fillColor"])}"')
        if "fillAlpha" in p:
            attrs.append(f'android:fillAlpha="{p["fillAlpha"]}"')
        if "fillType" in p:
            attrs.append(f'android:fillType="{norm_fill_type(p["fillType"])}"')
        if "strokeColor" in p:
            attrs.append(f'android:strokeColor="{norm_color(p["strokeColor"])}"')
        if "strokeWidth" in p:
            attrs.append(f'android:strokeWidth="{p["strokeWidth"]}"')
        if "strokeAlpha" in p:
            attrs.append(f'android:strokeAlpha="{p["strokeAlpha"]}"')
        if "strokeLineCap" in p:
            attrs.append(
                f'android:strokeLineCap="{norm_enum(p["strokeLineCap"], STROKE_LINE_CAP)}"')
        if "strokeLineJoin" in p:
            attrs.append(
                f'android:strokeLineJoin="{norm_enum(p["strokeLineJoin"], STROKE_LINE_JOIN)}"')
        out.append("    <path\n        " + "\n        ".join(attrs) + " />")
    return "\n".join(out)


TEMPLATE = """<?xml version="1.0" encoding="utf-8"?>
<!--
  从 MiuiWMShellResources-install.apk 提取的控制菜单按钮图标（{src}）。
  颜色统一成纯黑，App 侧用 ColorFilter 染色以跟随主题；外框那道 30% alpha 描边原样保留。
  重新生成：tools/caption/gen_caption_icons.py
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="20dp"
    android:height="20dp"
    android:viewportWidth="20"
    android:viewportHeight="20">
{paths}
</vector>
"""


def write(out_name, src_label, paths):
    target = os.path.join(OUT_DIR, out_name + ".xml")
    with open(target, "w", encoding="utf-8") as f:
        f.write(TEMPLATE.format(src=src_label, paths=build(paths)))
    print(f"ok {out_name}.xml  <- {src_label}  ({len(paths)} path)")


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    for out_name, src_name in ICONS.items():
        paths = parse_paths(dump(src_name))
        if not paths:
            print(f"!! {src_name}: 没解析到 path，跳过")
            continue
        write(out_name, src_name, paths)

    # 关闭按钮的「−」变体：只留外框（fillColor 透明的那条），再补一根横线
    paths = parse_paths(dump(MINUS_SRC))
    frame = [p for p in paths if p.get("fillColor", "#").lstrip("#")[:2] == "00"]
    if frame:
        write(MINUS_OUT, MINUS_SRC + " / × 换成 −", frame + [MINUS_PATH])
    else:
        print(f"!! {MINUS_SRC}: 没找到外框 path，跳过 − 变体")


if __name__ == "__main__":
    main()
