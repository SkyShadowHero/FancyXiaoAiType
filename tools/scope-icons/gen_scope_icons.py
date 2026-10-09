#!/usr/bin/env python3
"""生成「功能」一级列表用的 4 个 scope 图标，并**统一到同一套度量**。

为什么需要归一化：四个来源的 viewport 和线宽都不一样，直接摆在一起会很乱 ——
    小爱输入法   viewport 28，线宽 2   → 24dp 上是 1.71dp
    AOSP 平行窗口 viewport 48，线宽 4   → 24dp 上是 2.00dp
    光标主题     填充色块，线宽写不进   → 视觉最重
    小窗          viewport 20，线宽 1.8 → 24dp 上是 2.16dp

统一标准（也正好是 Miuix 那套图标的量级，所以能和剩下两个入口并排看）：
    viewport 24 × 24，内容最长边 20，线宽 1.8
做法：给每个图标套一层 group 做等比缩放 + 平移居中，并把 strokeWidth 反算成
`1.8 / 缩放比`，这样**渲染出来的**线宽一律是 1.8dp。

小窗用的是**原版素材**（caption_normal_freeform，外框 + 字形都在），只做了上面这套
等比缩放，图形本身没有改动。

用法：
    python3 gen_scope_icons.py <小爱输入法.apk> <MiuiWMShellResources.apk> <out_drawable_dir>
"""
import os
import re
import subprocess
import sys

IME_APK = sys.argv[1]
WM_APK = sys.argv[2]
OUT_DIR = sys.argv[3]
HOME = os.path.expanduser("~")

# 本地设计素材（不在版本库里）
SRC_CURSOR = os.path.join(
    HOME, "cursor-module/app/src/main/res/drawable/aosp_pointer_arrow_stroke.xml")
SRC_EMBED = os.path.join(
    HOME, "aosp-embedding/app/src/main/res/drawable/ic_launcher_foreground.xml")

# ---- 统一标准 ----
VIEWPORT = 24.0
TARGET_LONGEST = 20.0   # 内容最长边（view 单位 = 24dp 图标上的 dp）
TARGET_STROKE = 1.8     # 线宽（同上）

NUM_RE = re.compile(r"[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?")
CMD_RE = re.compile(r"([MmLlHhVvCcSsQqTtAaZz])([^MmLlHhVvCcSsQqTtAaZz]*)")
PATH_RE = re.compile(r'android:pathData="([^"]+)"')

STROKE_LINE_CAP = {"0": "butt", "1": "round", "2": "square"}
STROKE_LINE_JOIN = {"0": "miter", "1": "round", "2": "bevel"}


# ----------------------------------------------------------------- 几何

def path_bbox(d):
    """路径包围盒。

    曲线用控制点 + 终点近似（圆角矩形的控制点刚好落在矩形角上，误差可以忽略）；
    弧只取端点 —— 这些图标里的弧都是 ≤90° 的圆角，极值就在端点上。
    """
    pts = []
    x = y = sx = sy = 0.0
    for cmd, args in CMD_RE.findall(d):
        nums = [float(n) for n in NUM_RE.findall(args)]
        rel = cmd.islower()
        c = cmd.upper()
        if c in ("M", "L"):
            for i in range(0, len(nums) - 1, 2):
                nx, ny = nums[i], nums[i + 1]
                x, y = (x + nx, y + ny) if rel else (nx, ny)
                if c == "M" and i == 0:
                    sx, sy = x, y
                pts.append((x, y))
        elif c == "H":
            for n in nums:
                x = x + n if rel else n
                pts.append((x, y))
        elif c == "V":
            for n in nums:
                y = y + n if rel else n
                pts.append((x, y))
        elif c in ("C", "S", "Q", "T"):
            step = {"C": 6, "S": 4, "Q": 4, "T": 2}[c]
            for i in range(0, len(nums) - step + 1, step):
                chunk = nums[i:i + step]
                for j in range(0, len(chunk) - 1, 2):
                    px, py = chunk[j], chunk[j + 1]
                    pts.append((x + px, y + py) if rel else (px, py))
                x, y = pts[-1]
        elif c == "A":
            for i in range(0, len(nums) - 6, 7):
                px, py = nums[i + 5], nums[i + 6]
                x, y = (x + px, y + py) if rel else (px, py)
                pts.append((x, y))
        elif c == "Z":
            x, y = sx, sy
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    return min(xs), min(ys), max(xs), max(ys)


def bbox_of_text(text):
    boxes = [path_bbox(d) for d in PATH_RE.findall(text)]
    if not boxes:
        return None
    return (min(b[0] for b in boxes), min(b[1] for b in boxes),
            max(b[2] for b in boxes), max(b[3] for b in boxes))


# ----------------------------------------------------------------- 取源

def aapt_file(apk, drawable_name):
    """在资源表里找 drawable/<name> 对应的编译文件名。"""
    out = subprocess.run(["aapt2", "dump", "resources", apk],
                         capture_output=True, text=True).stdout
    lines = out.splitlines()
    for i, line in enumerate(lines):
        if line.strip().endswith("drawable/" + drawable_name):
            for follow in lines[i + 1:i + 5]:
                m = re.search(r"\(file\)\s+(res/\S+\.xml)", follow)
                if m:
                    return m.group(1)
    return None


def aapt_paths(apk, drawable_name):
    """从 APK 里读一个矢量图标，返回 (viewport_w, viewport_h, body_xml)。"""
    f = aapt_file(apk, drawable_name)
    if not f:
        raise SystemExit(f"!! {apk} 里没有 drawable/{drawable_name}")
    xml = subprocess.run(["aapt2", "dump", "xmltree", apk, "--file", f],
                         capture_output=True, text=True).stdout
    w = h = vw = vh = None
    paths = []
    cur = None
    for raw in xml.splitlines():
        line = raw.strip()
        if line.startswith("E: path"):
            cur = {}
            paths.append(cur)
            continue
        if not line.startswith("A: "):
            continue
        key, _, value = line[3:].partition("=")
        key = re.sub(r"\(0x[0-9a-fA-F]+\)\s*$", "", key).strip().split(":")[-1]
        value = value.strip()
        if value.startswith('"'):
            value = value[1:value.find('"', 1)]
        if cur is None:
            if key == "viewportWidth":
                vw = value
            elif key == "viewportHeight":
                vh = value
        else:
            cur[key] = value
    return vw or "24", vh or "24", "\n".join(path_xml(p) for p in paths)


def path_xml(p):
    attrs = []
    if "pathData" in p:
        attrs.append(f'android:pathData="{p["pathData"]}"')
    if "fillColor" in p:
        attrs.append(f'android:fillColor="{norm_color(p["fillColor"])}"')
    if "fillAlpha" in p:
        attrs.append(f'android:fillAlpha="{p["fillAlpha"]}"')
    if "fillType" in p:
        attrs.append(f'android:fillType="{"evenOdd" if p["fillType"] == "1" else "nonZero"}"')
    if "strokeColor" in p:
        attrs.append(f'android:strokeColor="{norm_color(p["strokeColor"])}"')
    if "strokeWidth" in p:
        attrs.append(f'android:strokeWidth="{p["strokeWidth"]}"')
    if "strokeAlpha" in p:
        attrs.append(f'android:strokeAlpha="{p["strokeAlpha"]}"')
    if "strokeLineCap" in p:
        attrs.append(f'android:strokeLineCap="{STROKE_LINE_CAP.get(p["strokeLineCap"], "butt")}"')
    if "strokeLineJoin" in p:
        attrs.append(f'android:strokeLineJoin="{STROKE_LINE_JOIN.get(p["strokeLineJoin"], "miter")}"')
    return "    <path\n        " + "\n        ".join(attrs) + " />"


def norm_color(value):
    v = value.lstrip("#")
    return "#00000000" if len(v) == 8 and v[:2] == "00" else "#ff000000"


# ----------------------------------------------------------------- 归一化输出

TEMPLATE = """<?xml version="1.0" encoding="utf-8"?>
<!--
  「功能」一级列表的 scope 图标：{desc}
  已归一化到统一度量：viewport 24、内容最长边 {longest:g}、线宽 {stroke:g}（= 24dp 图标上的 dp）。
  等比缩放 {scale:.4f}×，平移居中；strokeWidth 反算过，所以实际渲染线宽和其它图标一致。
  颜色统一纯黑，App 侧 ColorFilter 染色跟随主题。
  重新生成：tools/scope-icons/gen_scope_icons.py
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <group
        android:scaleX="{scale:.6f}"
        android:scaleY="{scale:.6f}"
        android:translateX="{tx:.3f}"
        android:translateY="{ty:.3f}">
{body}
    </group>
</vector>
"""


def emit(name, desc, body, forced_stroke=None):
    box = bbox_of_text(body)
    if box is None:
        raise SystemExit(f"!! {name}: 没找到 pathData")
    x1, y1, x2, y2 = box
    w, h = x2 - x1, y2 - y1
    scale = TARGET_LONGEST / max(w, h)
    tx = VIEWPORT / 2 - scale * (x1 + x2) / 2
    ty = VIEWPORT / 2 - scale * (y1 + y2) / 2
    stroke = (forced_stroke / scale) if forced_stroke else (TARGET_STROKE / scale)
    body = re.sub(r'android:strokeWidth="[^"]*"',
                  f'android:strokeWidth="{stroke:.3f}"', body)
    with open(os.path.join(OUT_DIR, name + ".xml"), "w", encoding="utf-8") as f:
        f.write(TEMPLATE.format(desc=desc, longest=TARGET_LONGEST, stroke=TARGET_STROKE,
                                scale=scale, tx=tx, ty=ty, body=body))
    print(f"ok {name}.xml   内容 {w:g}×{h:g} → 缩放 {scale:.4f}  线宽 {stroke:.3f}"
          f"（渲染 {TARGET_STROKE:g}）")


def main():
    os.makedirs(OUT_DIR, exist_ok=True)

    # 1) 小爱输入法：工具栏的「分割键盘」图标
    _, _, body = aapt_paths(IME_APK, "ic_toolbar_split_keyboard")
    emit("ic_scope_ime", "小爱输入法的「分割键盘」工具栏图标", body)

    # 2) 光标主题：AOSP 指针箭头，只留外轮廓并改成描边，线宽才归一得动
    with open(SRC_CURSOR, encoding="utf-8") as f:
        src = f.read()
    contour = PATH_RE.search(src).group(1)
    outer = contour[:contour.index("z") + 1]          # 第一段子路径 = 箭头外轮廓
    body = ('    <path\n        android:pathData="%s"\n'
            '        android:strokeColor="#ff000000"\n'
            '        android:strokeWidth="1"\n'
            '        android:strokeLineCap="round"\n'
            '        android:strokeLineJoin="round" />' % outer)
    emit("ic_scope_cursor", "光标主题：AOSP 指针箭头（外轮廓描边）", body)

    # 3) 平行窗口动画：aosp-embedding 的 launcher 前景（保留自带的三层阴影 group）
    with open(SRC_EMBED, encoding="utf-8") as f:
        src = f.read()
    body = src[src.index(">", src.index("<vector")) + 1:src.index("</vector>")].strip("\n")
    emit("ic_scope_embedding", "aosp-embedding 的 launcher 前景（两层矩形 + 中缝）", body)

    # 4) 小窗控制器：原版 caption_normal_freeform（外框 + 字形都在）
    _, _, body = aapt_paths(WM_APK, "caption_normal_freeform")
    emit("ic_scope_caption", "控制菜单「小窗」按钮的原版图标（caption_normal_freeform）", body)


if __name__ == "__main__":
    main()
