#!/usr/bin/env python3
"""为 4 套 SVG 主题生成「主体 mask + 描边 mask」两张白图（运行时染色），并输出 CursorIcons.java。

主题：material(varlesh) / apple(ful1e5/apple_cursor) / googledot(ful1e5/Google_Cursor) / breezex(ful1e5/BreezeX_Cursor)
- ful1e5 那三套的占位色：主体 #00FF00、描边 #0000FF（见各自 render.json）
- apple 的 hand1/hand2/move、breezex 的 X_cursor/pirate/wayland-cursor/dnd_no_drop
  写的是成图色（主体 white、描边 black），按文件自动切换取色规则
- material 是成图色：主体 #e4e4e4、描边 #010101，另外还有 #000000 的模糊投影层（丢弃）

mask 生成的几条硬规则（踩过的坑都在这）：
1. 形状不只有 <path>：material/googledot 大量用 <circle>/<ellipse>/<rect>，只抓 path 会得到空白 mask；
2. 同一条 path 可以既是主体又是描边（ful1e5 的 copy.svg：fill=#00FF00 + stroke=#0000FF）→ 两层都要收；
3. drop-shadow 的 <g filter=...> 里往往是主体的副本：只有「几何签名在组外也出现」才丢弃，
   googledot 的描边圆圈恰恰只存在于该组里，必须保留（只是把 filter 去掉）；
4. opacity≈0 的 <g>/形状（ful1e5 的 1% 全画布垫片 rect）要整组丢掉，否则描边层会变成整块白。
AOSP 仍然用官方矢量拆层（res/drawable/aosp_<key>_{fill,stroke}.xml），不在这里生成。
"""
import os, re, json, subprocess

HOME = os.path.expanduser("~")
PROJ = f"{HOME}/cursor-module"
NODPI = f"{PROJ}/app/src/main/res/drawable-nodpi"
DRAW = f"{PROJ}/app/src/main/res/drawable"
JAVA = f"{PROJ}/app/src/main/java/io/github/skyshadowhero/cursor"
AOSP = f"{HOME}/tmp/aosp-cursors/vector/drawable"
SIZE, PREVIEW = 192, 128

# 主题: (键名, 搜索目录列表, 主体色列表, 描边色列表, 默认主体色, 默认描边色)
# 主题: 键名 / 渲染模式 / 素材目录 / mask 模式用的主体色、描边色 / 默认填充色、默认描边色 / 是否能改色
#   mode="full" → 直接出原色图（含投影），不可改色（material / apple / breezex）
#   mode="mask" → 出主体+描边两层 mask，运行时染色（googledot）
#   aosp 由 tools/gen_icons.py 拆官方矢量层，也是可改色
THEMES = [
    ("material", "full", [f"{HOME}/tmp/material-cursors/src/material_light_cursors"],
     [], [], 0, 0),
    ("apple", "full", [f"{HOME}/tmp/apple_cursor/svg", f"{HOME}/tmp/apple_cursor/svg/wait"],
     [], [], 0, 0),
    ("breezex", "full", [f"{HOME}/tmp/BreezeX_Cursor/svg"],
     [], [], 0, 0),
    ("googledot", "mask", [f"{HOME}/tmp/Google_Cursor/svg/static", f"{HOME}/tmp/Google_Cursor/svg/animated"],
     ["#00FF00", "#00ff00", "#00d161", "#8c382a", "#c5523f", "#f2b736", "#fc3c36", "white"],
     ["#0000FF", "#0000ff", "#414141"], 0xFFFFFFFF, 0xFF000000),
]

# type, 键名, 候选 SVG 名, hotspotX, hotspotY
# 类型编号来自设备自己的 android.view.PointerIcon（~/tmp/ptr/PointerIcon.java），
# 注意 1002/1003/1004 是 HAND/HELP/WAIT，1005 是空号，触控笔三种是 2000/2001/2002。
# logical = AOSP 逻辑名，用于查 aosp-cursors/alias.list 得到各仓库的 X11 名字。
TYPES = [
    (1000, "pointer_arrow", "arrow"),
    (1001, "pointer_context_menu", "context-menu"),
    (1002, "pointer_hand", "hand"),
    (1003, "pointer_help", "help"),
    (1004, "pointer_wait", "wait"),
    (1006, "pointer_cell", "cell"),
    (1007, "pointer_crosshair", "crosshair"),
    (1008, "pointer_text", "text"),
    (1009, "pointer_vertical_text", "vertical-text"),
    (1010, "pointer_alias", "alias"),
    (1011, "pointer_copy", "copy"),
    (1012, "pointer_nodrop", "nodrop"),
    (1013, "pointer_all_scroll", "all-scroll"),
    (1014, "pointer_horizontal_double_arrow", "horizontal-double-arrow"),
    (1015, "pointer_vertical_double_arrow", "vertical-double-arrow"),
    (1016, "pointer_top_right_diagonal_double_arrow", "top-right-diagonal-double-arrow"),
    (1017, "pointer_top_left_diagonal_double_arrow", "top-left-diagonal-double-arrow"),
    (1018, "pointer_zoom_in", "zoom-in"),
    (1019, "pointer_zoom_out", "zoom-out"),
    (1020, "pointer_grab", "grab"),
    (1021, "pointer_grabbing", "grabbing"),
    (1022, "pointer_handwriting", "handwriting"),
    (2000, "pointer_spot_hover", "spot-hover"),
    (2001, "pointer_spot_touch", "spot-touch"),
    (2002, "pointer_spot_anchor", "spot-anchor"),
]

COLOR_RE = re.compile(
    r'(?<![-\w])(fill|stroke)\s*([=:])\s*("([^"]*)"|\'([^\']*)\'|([^;"\'\s>]+))',
    re.I)
SHAPE_RE = re.compile(
    r'<(path|circle|rect|ellipse|polygon|polyline|line)\b(?:[^>]*?/>|[^>]*?>.*?</\1\s*>)',
    re.S | re.I)
GEO = ("d", "cx", "cy", "r", "rx", "ry", "x", "y", "width", "height", "points")


def norm_color(v):
    v = v.strip()
    low = v.lower()
    if low == "white":
        return "#ffffff"
    if low == "black":
        return "#000000"
    if len(v) == 4 and v.startswith("#"):
        return "#" + "".join(ch * 2 for ch in v[1:])
    return low


def color_match(m):
    """(颜色值, 引号) —— 兼容 fill="#x" / fill='#x' / style="fill:#x"。"""
    val = m.group(4) if m.group(4) is not None else (m.group(5) if m.group(5) is not None else m.group(6))
    quote = '"' if m.group(4) is not None else ("'" if m.group(5) is not None else "")
    return val, quote


def props(el):
    """一条形状上的 fill / stroke 颜色（含 style="fill:#xxx" 写法）。"""
    fills, strokes = [], []
    for m in COLOR_RE.finditer(el):
        val, _ = color_match(m)
        (fills if m.group(1).lower() == "fill" else strokes).append(norm_color(val))
    return fills, strokes


def opacity_of(el):
    vals = [1.0]
    for m in re.finditer(r'(?<![-\w])opacity\s*[=:]\s*"?([0-9.]+)', el, re.I):
        vals.append(float(m.group(1)))
    return min(vals)


def geometry(el):
    out = []
    for a in GEO:
        m = re.search(r'(?<![-\w])%s\s*=\s*"([^"]*)"' % a, el, re.I)
        if m:
            out.append(a + "=" + re.sub(r"\s+", "", m.group(1)))
    return "|".join(out)


def find_svg(dirs, names):
    for d in dirs:
        for n in names:
            p = f"{d}/{n}.svg"
            if os.path.exists(p):
                return p
    return None


def recolor(el, keep_white, to_none):
    """把形状里的颜色重写成 #FFFFFF（保留层）或 none（丢弃层）。"""
    def rep(m):
        attr, sep = m.group(1), m.group(2)
        val, quote = color_match(m)
        if norm_color(val) in ("none", "transparent", "currentcolor"):
            return m.group(0)
        if val.lower() in to_none or norm_color(val) in to_none:
            return f"{attr}{sep}{quote}none{quote}"
        return f"{attr}{sep}{quote}#FFFFFF{quote}"
    return COLOR_RE.sub(rep, el)


def render(svg, out, size=SIZE):
    os.makedirs(os.path.dirname(out), exist_ok=True)
    r = subprocess.run(["rsvg-convert", "-w", str(size), "-h", str(size), svg, "-o", out],
                       capture_output=True)
    if r.returncode != 0:
        print("   rsvg 失败:", os.path.basename(svg), r.stderr.decode()[:200])
    return r.returncode == 0


def iter_groups(x):
    """产出 (start, end, tag, inner)。本项目所有 SVG 的 <g> 都不嵌套，够用。"""
    pat = re.compile(r"<g\b[^>]*>", re.I)
    pos = 0
    while True:
        m = pat.search(x, pos)
        if not m:
            return
        end = x.find("</g>", m.end())
        if end == -1:
            return
        yield m.start(), end + 4, m.group(0), x[m.end():end]
        pos = m.end()


def drop_invisible_groups(x):
    """opacity≈0 的整组（ful1e5 的 1% 全画布垫片）连子元素一起丢。"""
    while True:
        hit = None
        for g in iter_groups(x):
            if opacity_of(g[2]) < 0.05:
                hit = g
                break
        if not hit:
            return x
        x = x[:hit[0]] + x[hit[1]:]


def unwrap_shadow_groups(x):
    """drop-shadow 组就地展开（保留原始先后顺序！），组内与组外几何重复的副本丢掉。

    为什么不能"整组删掉"：googledot 的描边圆圈只存在于 filter 组里；
    为什么不能"取出来追加到末尾"：left_ptr 的描边在前、hand2 的描边在后，
    顺序决定了谁压谁，这正是之前 hand 变成一坨白饼的原因。
    """
    while True:
        hit = None
        for g in iter_groups(x):
            if re.search(r"(?<![-\w])filter\s*=", g[2], re.I):
                hit = g
                break
        if not hit:
            return x
        outer = set(geometry(m.group(0)) for m in SHAPE_RE.finditer(x[:hit[0]] + x[hit[1]:]))
        inner = hit[3]
        for m in SHAPE_RE.finditer(hit[3]):
            el = m.group(0)
            if geometry(el) and geometry(el) in outer:
                inner = inner.replace(el, "", 1)      # 主体的投影副本
        x = x[:hit[0]] + inner + x[hit[1]:]


def is_backdrop(el, vb):
    """全画布垫片（ful1e5 的 BackgroundImageFix）：<rect> 铺满，或 <path> 是个大方框。"""
    tag = re.match(r"<\s*(\w+)", el).group(1).lower()
    if re.search(r"(?<![-\w])stroke\s*[=:]\s*\"?(?!none)", el, re.I) and \
       not re.search(r"stroke\s*[=:]\s*\"?none", el, re.I):
        return False
    if tag == "rect":
        def num(a, d=0.0):
            m = re.search(r'(?<![-\w])%s\s*=\s*"([-\d.]+)' % a, el, re.I)
            return float(m.group(1)) if m else d
        return (abs(num("x")) < 1 and abs(num("y")) < 1
                and num("width") >= vb[0] * 0.95 and num("height") >= vb[1] * 0.95)
    if tag == "path":
        d = re.search(r'(?<![-\w])d\s*=\s*"([^"]*)"', el, re.I)
        if not d:
            return False
        s = re.sub(r"[\s,]+", "", d.group(1)).upper().rstrip("Z")
        m = re.fullmatch(r"M0?0?H(\d+(?:\.\d+)?)V(\d+(?:\.\d+)?)H0?V0?", s)
        return bool(m)
    return False


def set_attr(el, attr, value):
    """在开始标签里补一个属性（自闭合 <path .../> 与配对 <path ...></path> 都安全）。"""
    m = re.match(r"<[^>]*?(/?)>", el, re.S)
    if not m:
        return el
    tag = m.group(0)
    close = "/>" if m.group(1) else ">"
    body = tag[:-len(close)].rstrip()
    if re.search(r'(?<![-\w])%s\s*[=:]' % attr, body, re.I):
        return el
    return body + f' {attr}="{value}"' + close + el[len(tag):]


def to_black(el, stroke_only=False):
    """把形状变成"掏洞用"的黑色版本；stroke_only=True 时只保留描边那一圈。"""
    def rep(m):
        attr, sep = m.group(1), m.group(2)
        val, quote = color_match(m)
        if attr.lower() == "fill":
            return f"{attr}{sep}{quote}{'none' if stroke_only else '#000000'}{quote}"
        if norm_color(val) in ("none", "transparent", "currentcolor"):
            return m.group(0)
        return f"{attr}{sep}{quote}#000000{quote}"
    return set_attr(COLOR_RE.sub(rep, el), "fill", "none" if stroke_only else "#000000")


def masks(svg, fill_out, stroke_out, accent_out, body_colors, line_colors, mode="placeholder"):
    """生成主体 mask / 描边 mask，返回是否渲染成功。

    mode="complement"（material）：主体 = 除描边色、除 #000000 投影之外的一切；
    mode="placeholder"（ful1e5 三套）：按占位色分主体/描边，个别文件用成图色 white/black。

    层序问题（关键）：原图里"谁压谁"是按元素先后顺序决定的，而且**逐图不同**
      left_ptr:   描边(前) → 主体(后)      → 先画描边即可
      hand2:      主体(前) → 描边(后)      → 描边要压在主体上
      context-menu: 描边(前) → 主体 → 描边(后，三个点)
    固定的"先描边后主体"必然错一半。这里只在 hook 里保留两层的画法，
    把"应该压在上面"的描边形状从主体 mask 里**掏洞**（SVG <mask>），
    于是压在下层画出来的描边会从洞里透出来，观感与原始层序一致。
    """
    raw = open(svg).read()
    head_m = re.search(r"<svg\b[^>]*>", raw, re.S)
    head = head_m.group(0) if head_m else '<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32">'
    vbm = re.search(r'viewBox\s*=\s*"([-\d.\s,]+)"', head)
    if vbm:
        v = [float(t) for t in re.split(r"[\s,]+", vbm.group(1).strip()) if t]
        vb = (v[2], v[3]) if len(v) == 4 else (v[0], v[1])
    else:
        w = re.search(r'width\s*=\s*"([\d.]+)', head)
        h = re.search(r'height\s*=\s*"([\d.]+)', head)
        vb = (float(w.group(1)) if w else 32, float(h.group(1)) if h else 32)

    body_set = set(c.lower() for c in body_colors)
    line_set = set(c.lower() for c in line_colors)
    if mode == "placeholder" and not any(c in raw for c in body_colors + line_colors):
        # 这些文件写的是成图色：主体 white、描边 black
        found = set()
        for m in SHAPE_RE.finditer(raw):
            f, s = props(m.group(0))
            found |= set(f) | set(s)
        found.discard("none")
        found = set(c for c in found if not c.startswith("url("))
        if "#000000" in found:
            line_set, found = {"#000000"}, found - {"#000000"}
        else:
            line_set = set()
        body_set = found

    x = re.sub(r"<defs\b.*?</defs>", "", raw, flags=re.S)
    if mode == "complement":
        # material 的投影层：fill:#000000 且带 filter:url(...) 或 opacity<=0.5（path877/881/845）。
        # 必须在这里杀，因为后面会把 filter 引用清掉；而 opacity=1 的黑色是正经图形
        # （右键菜单的三条白杠背景、放大镜的加号），不能一起杀。
        def kill_shadow(m):
            el = m.group(0)
            f, _ = props(el)
            if "#000000" in f and (re.search(r"filter\s*[:=]\s*url\(", el, re.I)
                                   or opacity_of(el) <= 0.5):
                return ""
            return el
        x = SHAPE_RE.sub(kill_shadow, x)
    x = drop_invisible_groups(x)
    x = unwrap_shadow_groups(x)
    # 展开其余 <g>（这些组没有 transform；全量检查过：只有 material progress-01 有）
    x = re.sub(r"</?g\b[^>]*>", "", x, flags=re.I)
    # clip-path / mask / filter 引用一律去掉（defs 已删，留着会整块不渲染）
    x = re.sub(r'\s+(?:clip-path|mask|filter)\s*=\s*"[^"]*"', "", x, flags=re.I)
    x = re.sub(r"filter\s*:\s*url\([^)]*\)\s*;?", "", x, flags=re.I)

    kinds = []      # (元素, 是否主体, 是否描边, 是否只有描边无填充, 是否原色插图)
    for m in SHAPE_RE.finditer(x):
        el = m.group(0)
        if opacity_of(el) < 0.05 or is_backdrop(el, vb):
            continue
        fills, strokes = props(el)
        fv = [c for c in fills if c != "none"]
        sv = [c for c in strokes if c != "none"]
        grad = [c for c in fv + sv if c.startswith("url(")]
        stroke_only = not fv
        is_acc = False
        if mode == "complement":
            # material：主体=#e4e4e4，#010101=描边，其余颜色=原色插图（皮肤/蓝镜片/白杠…）
            is_line = "#010101" in sv or fv == ["#010101"]
            is_body = "#e4e4e4" in fv + sv
            is_acc = (not is_line) and (not is_body) and bool(fv or sv)
        else:
            is_line = any(c in line_set for c in fv + sv)
            is_body = bool(grad) or any(c in body_set for c in fv + sv)
        if is_body or is_line or is_acc:
            kinds.append((el, is_body, is_line, stroke_only, is_acc))

    first_body = next((i for i, k in enumerate(kinds) if k[1]), None)
    body = [k[0] for k in kinds if k[1]]
    line = [k[0] for k in kinds if k[2]]
    accent = [k[0] for k in kinds if k[4]]
    # 该压在主体上面的描边 → 从主体里掏洞
    punch = []
    for i, (el, is_body, is_line, stroke_only, is_acc) in enumerate(kinds):
        if not is_line:
            continue
        if is_body:                                     # 自身 fill+stroke：掏掉描边圈
            punch.append(to_black(el, True))
        elif first_body is not None and i > first_body:  # 主体之后画的描边：掏整块
            punch.append(to_black(el, stroke_only))

    def build(keep, drop_set):
        return head + "\n" + "\n".join(recolor(el, "#FFFFFF", drop_set) for el in keep) + "\n</svg>\n"

    def build_body():
        inner = "\n".join(recolor(el, "#FFFFFF", line_set) for el in body)
        if not punch:
            return head + "\n" + inner + "\n</svg>\n"
        mask = (f'<defs><mask id="p" maskUnits="userSpaceOnUse" x="0" y="0" '
                f'width="{vb[0]}" height="{vb[1]}">'
                f'<rect x="0" y="0" width="{vb[0]}" height="{vb[1]}" fill="#FFFFFF"/>'
                + "".join(punch) + "</mask></defs>")
        return head + "\n" + mask + '\n<g mask="url(#p)">\n' + inner + "\n</g>\n</svg>\n"

    tmp = "/data/data/com.termux/files/home/tmp/_mask.svg"
    open(tmp, "w").write(build_body())
    ok1 = render(tmp, fill_out)
    open(tmp, "w").write(build(line, body_set))
    ok2 = render(tmp, stroke_out)
    # 原色插图层：不染色，保序原样输出（material 专用）
    if mode == "complement":
        if accent:
            open(tmp, "w").write(head + "\n" + "\n".join(accent) + "\n</svg>\n")
            render(tmp, accent_out)
        elif os.path.exists(accent_out):
            os.unlink(accent_out)
    return ok1 and ok2


def preview_from_masks(ckey, key, fill_argb, stroke_argb, out, size=PREVIEW):
    """预览必须等于真实光标：按 Hook 的绘制顺序（描边 → 主体 → 原色插图）合成。"""
    from PIL import Image
    base = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    for layer, argb in ((f"{NODPI}/{key}_{ckey}_s.png", stroke_argb),
                        (f"{NODPI}/{key}_{ckey}_f.png", fill_argb),
                        (f"{NODPI}/{key}_{ckey}_c.png", None)):
        if not os.path.exists(layer):
            continue
        m = Image.open(layer).convert("RGBA")
        if argb is None:                      # 原色插图：不染色
            base = Image.alpha_composite(base, m)
            continue
        color = Image.new("RGBA", m.size, ((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, 255))
        color.putalpha(m.getchannel("A"))
        base = Image.alpha_composite(base, color)
    base.resize((size, size), Image.LANCZOS).save(out)


# 各仓库 render.json 里的占位色 → 成图色（ful1e5 三套用占位色画图；material 本来就是成图色）
#   apple:    macOS 方案      #00FF00→#000000（主体） #0000FF→#FFFFFF（描边）
#   breezex:  BreezeX-Light   #00FF00→#FFFFFF          #0000FF→#4D4D4D
RENDER_COLORS = {
    "apple": [("#00FF00", "#000000"), ("#0000FF", "#FFFFFF")],
    "breezex": [("#00FF00", "#FFFFFF"), ("#0000FF", "#4D4D4D")],
    "material": [],
}


def apply_render_colors(x, tkey):
    for a, b in RENDER_COLORS.get(tkey, ()):
        x = re.sub(re.escape(a), b, x, flags=re.I)
    return x


def full_color_svg(svg, tkey=""):
    """原色直出用的 SVG：去掉不可见组与全画布垫片，其余（含 <defs> 投影滤镜）原样保留。"""
    raw = open(svg).read()
    head = re.search(r"<svg\b[^>]*>", raw, re.S)
    vb = (200.0, 200.0)
    if head:
        m = re.search(r'viewBox\s*=\s*"([-\d.\s,]+)"', head.group(0))
        if m:
            v = [float(t) for t in re.split(r"[\s,]+", m.group(1).strip()) if t]
            if len(v) == 4:
                vb = (v[2], v[3])
        else:
            w = re.search(r'width\s*=\s*"([\d.]+)', head.group(0))
            h = re.search(r'height\s*=\s*"([\d.]+)', head.group(0))
            if w and h:
                vb = (float(w.group(1)), float(h.group(1)))
    x = apply_render_colors(drop_invisible_groups(raw), tkey)
    for m in list(SHAPE_RE.finditer(x)):
        el = m.group(0)
        if is_backdrop(el, vb) and el in x:
            x = x.replace(el, "", 1)
    return x


def aosp_hotspots():
    """AOSP 官方热点：vector/pointer_<key>_vector_icon.xml 里的 hotSpotX/Y（dp）。"""
    out = {}
    d = f"{HOME}/tmp/aosp-cursors/vector"
    for f in os.listdir(d):
        if not f.endswith("_vector_icon.xml"):
            continue
        key = f[:-len("_vector_icon.xml")]      # 形如 pointer_arrow
        x = open(f"{d}/{f}").read()
        hx = re.search(r'hotSpotX\s*=\s*"([\d.]+)dp', x)
        hy = re.search(r'hotSpotY\s*=\s*"([\d.]+)dp', x)
        if hx and hy:
            out[key] = (float(hx.group(1)) / 24.0, float(hy.group(1)) / 24.0)
    return out


def main():
    os.makedirs(NODPI, exist_ok=True)
    assets = {}
    mapfile = f"{PROJ}/tools/theme_map.json"
    if os.path.exists(mapfile):
        assets = {int(k): v for k, v in json.load(open(mapfile)).items()}
    aosp_hot = aosp_hotspots()
    tmp_svg = f"{HOME}/tmp/_full.svg"

    report = []
    for tkey, mode, dirs, body, line, def_fill, def_stroke in THEMES:
        hit = 0
        for num, ckey, logical in TYPES:
            info = (assets.get(num, {}).get("theme", {}) or {}).get(tkey) or {}
            svg = None
            if info.get("svg"):
                for d in dirs:
                    p = f"{d}/{info['svg']}.svg"
                    if os.path.exists(p):
                        svg = p
                        break
            if svg is None:
                continue
            hit += 1
            if mode == "full":
                open(tmp_svg, "w").write(full_color_svg(svg, tkey))
                render(tmp_svg, f"{NODPI}/{tkey}_{ckey}.png")
            else:
                masks(svg, f"{NODPI}/{tkey}_{ckey}_f.png", f"{NODPI}/{tkey}_{ckey}_s.png",
                      None, body, line, "placeholder")
        report.append((tkey, mode, hit))
    for tkey, mode, hit in report:
        print(f"  {tkey:10s} {mode:5s} 命中 {hit:2d}/{len(TYPES)}")

    # 预览图：必须与 Hook 实际画出来的一致
    for tkey, mode, dirs, body, line, def_fill, def_stroke in THEMES:
        info = (assets.get(1000, {}).get("theme", {}) or {}).get(tkey) or {}
        svg = None
        if info.get("svg"):
            for d in dirs:
                p = f"{d}/{info['svg']}.svg"
                if os.path.exists(p):
                    svg = p
        if svg is None:
            continue
        out = f"{NODPI}/prev_{tkey}.png"
        if mode == "full":
            open(tmp_svg, "w").write(full_color_svg(svg, tkey))
            render(tmp_svg, out, PREVIEW)
        else:
            preview_from_masks("arrow", tkey, def_fill, def_stroke, out)

    # 三层全空自检
    from PIL import Image
    dead = []
    for num, ckey, logical in TYPES:
        ok = False
        for tkey, mode, *_ in THEMES:
            names = [f"{tkey}_{ckey}.png"] if mode == "full" else [f"{tkey}_{ckey}_f.png", f"{tkey}_{ckey}_s.png"]
            for n in names:
                p = f"{NODPI}/{n}"
                if os.path.exists(p) and max(Image.open(p).convert("RGBA").getchannel("A").getextrema()) > 0:
                    ok = True
        aosp_ok = os.path.exists(f"{DRAW}/aosp_{ckey}_fill.xml") or os.path.exists(f"{DRAW}/aosp_{ckey}_stroke.xml")
        if not ok and not aosp_ok:
            dead.append(ckey)
    print(f"  连 AOSP 都没有的类型: {len(dead)} {dead if dead else ''}")

    # ---------------- CursorIcons.java ----------------
    def rid(name):
        return f"R.drawable.{name}" if os.path.exists(f"{NODPI}/{name}.png") else "0"
    def xmlid(key, layer):
        return f"R.drawable.aosp_{key}_{layer}" if os.path.exists(f"{DRAW}/aosp_{key}_{layer}.xml") else "0"
    def hot10(key, theme, fallback):
        info = (assets.get(key, {}).get("theme", {}) or {}).get(theme) or {}
        fx, fy = info.get("hot", (None, None))
        if fx is None:
            return fallback
        return (int(round(fx * 24 * 10)), int(round(fy * 24 * 10)))

    lines = ["package io.github.skyshadowhero.cursor;", "",
             "/** 自动生成（tools/gen_themes.py，AOSP 拆层来自 tools/gen_icons.py），不要手改。",
             " *  每行 18 列：",
             " *   0 type  1 aospFill  2 aospStroke  3 aospHotX(0.1dp)  4 aospHotY",
             " *   5 gdFill  6 gdStroke  7 gdHotX  8 gdHotY        ← GoogleDot 可改色（mask 两层）",
             " *   9 materialImg 10 matHotX 11 matHotY            ← 原色图，不可改色",
             " *  12 appleImg   13 appleHotX 14 appleHotY",
             " *  15 breezeImg  16 brzHotX  17 brzHotY",
             " *  某主题某类型为 0 → 代码回退用 AOSP 官方矢量层画。 */",
             "final class CursorIcons {", "    static final int[][] TABLE = {"]
    for num, ckey, logical in TYPES:
        a_hx, a_hy = hot10(num, "aosp", (int(round(aosp_hot.get(ckey, (0.5, 0.5))[0] * 240)),
                                         int(round(aosp_hot.get(ckey, (0.5, 0.5))[1] * 240))))
        g_hx, g_hy = hot10(num, "googledot", (a_hx, a_hy))
        m_hx, m_hy = hot10(num, "material", (a_hx, a_hy))
        p_hx, p_hy = hot10(num, "apple", (a_hx, a_hy))
        b_hx, b_hy = hot10(num, "breezex", (a_hx, a_hy))
        cols = [str(num), xmlid(ckey, "fill"), xmlid(ckey, "stroke"), str(a_hx), str(a_hy),
                rid(f"googledot_{ckey}_f"), rid(f"googledot_{ckey}_s"), str(g_hx), str(g_hy),
                rid(f"material_{ckey}"), str(m_hx), str(m_hy),
                rid(f"apple_{ckey}"), str(p_hx), str(p_hy),
                rid(f"breezex_{ckey}"), str(b_hx), str(b_hy)]
        lines.append("            {" + ", ".join(cols) + "},")
    lines += ["    };", "",
              "    /** 与 TABLE 同序的类型键名（Remote File 名 cust_<key>.png 用它） */",
              "    static final String[] KEYS = {"]
    for num, ckey, logical in TYPES:
        lines.append(f'            "{ckey}",')
    lines += ["    };", "",
              "    /** 可改色的 mask 主题（TABLE 第 5/6 列） */",
              '    static final String[] THEMES = {"googledot"};',
              "",
              "    /** 可改色主题的默认（填充, 描边）色，索引与 THEMES 对应 */",
              "    static final int[][] THEME_COLORS = {",
              "            {0xFFFFFFFF, 0xFF000000},   // GoogleDot：白心黑边",
              "    };", "", "    private CursorIcons() {}", "}", ""]
    open(f"{JAVA}/CursorIcons.java", "w").write("\n".join(lines))
    print("写 CursorIcons.java：", len(TYPES), "类型 ×", len(THEMES), "主题")


if __name__ == "__main__":
    main()
