#!/usr/bin/env python3
"""从各仓库自己的元数据推导「AOSP 类型 → 该主题用哪张 SVG + 热点在哪」。

三份权威数据，全部来自仓库，不再靠猜：
1. 类型↔X11 名字：`aosp-cursors/alias.list` + `name_map.json`（AOSP 官方别名表）
   例：pointer_hand ← pointer / hand2 / pointing_hand；pointer_grabbing ← closedhand / move
2. 热点：
   - ful1e5 三套：`configs/x.build.toml`（apple/BreezeX）与 `build.toml`（Google）里的
     `x_hotspot`/`y_hotspot`，单位是「该 SVG 自身像素空间」→ 换算成比例
   - material：`src/config/<name>.cursor`，格式 `size xhot yhot png`（24px 设计空间）
   - AOSP：`vector/pointer_*_vector_icon.xml` 的 `hotSpotX/Y`（dp）
3. 歧义类型（cell / alias / spot_* 等）用「形状 IoU」定夺：把 AOSP 官方矢量图当参考，
   与仓库里每张 SVG 的剪影求交并比（带 ±平移搜索），取最高分。

产出 `tools/theme_map.json`：{type: {"key":.., "theme": {"svg":.., "hot":[fx,fy], "iou":..}}}
"""
import os, re, json, subprocess, shutil
from PIL import Image, ImageChops

HOME = os.path.expanduser("~")
TMP = f"{HOME}/tmp"
OUT_JSON = f"{HOME}/cursor-module/tools/theme_map.json"
CACHE = f"{TMP}/silh"
REF = f"{TMP}/silh_ref"
AOSP = f"{TMP}/aosp-cursors"
SIZE = 200

# type, 资源键（= AOSP drawable 名去掉 pointer_ 前缀）, AOSP 逻辑名（用于 alias 表）
TYPES = [
    (1000, "arrow", "arrow"),
    (1001, "context_menu", "context-menu"),
    (1002, "hand", "hand"),
    (1003, "help", "help"),
    (1004, "wait", "wait"),
    (1006, "cell", "cell"),
    (1007, "crosshair", "crosshair"),
    (1008, "text", "text"),
    (1009, "vertical_text", "vertical-text"),
    (1010, "alias", "alias"),
    (1011, "copy", "copy"),
    (1012, "nodrop", "nodrop"),
    (1013, "all_scroll", "all-scroll"),
    (1014, "horizontal_double_arrow", "horizontal-double-arrow"),
    (1015, "vertical_double_arrow", "vertical-double-arrow"),
    (1016, "top_right_diagonal_double_arrow", "top-right-diagonal-double-arrow"),
    (1017, "top_left_diagonal_double_arrow", "top-left-diagonal-double-arrow"),
    (1018, "zoom_in", "zoom-in"),
    (1019, "zoom_out", "zoom-out"),
    (1020, "grab", "grab"),
    (1021, "grabbing", "grabbing"),
    (1022, "handwriting", "handwriting"),
    (2000, "spot_hover", "spot-hover"),
    (2001, "spot_touch", "spot-touch"),
    (2002, "spot_anchor", "spot-anchor"),
]

THEMES = {
    "material": {"dirs": [f"{TMP}/material-cursors/src/material_light_cursors"], "hot": "material"},
    "apple": {"dirs": [f"{TMP}/apple_cursor/svg", f"{TMP}/apple_cursor/svg/wait"], "hot": "toml",
              "toml": f"{TMP}/apple_cursor/configs/x.build.toml"},
    "googledot": {"dirs": [f"{TMP}/Google_Cursor/svg/static", f"{TMP}/Google_Cursor/svg/animated"],
                  "hot": "toml", "toml": f"{TMP}/Google_Cursor/build.toml"},
    "breezex": {"dirs": [f"{TMP}/BreezeX_Cursor/svg"], "hot": "toml",
                "toml": f"{TMP}/BreezeX_Cursor/configs/x.build.toml"},
}


# ---------------------------------------------------------------- 别名表
def load_aliases():
    """X11 名 → AOSP 逻辑名（含 theme-specific fallbacks），并补上 name_map.json。"""
    rows = []
    for line in open(f"{AOSP}/alias.list", encoding="utf-8"):
        line = line.split("#")[0].strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) >= 2:
            rows.append((parts[0], parts[1]))
    nm = json.load(open(f"{AOSP}/name_map.json"))
    for aosp_name, x11 in nm.items():
        rows.append((x11, aosp_name))
    # 反复迭代求传递闭包：pointer_hand ← hand2 ← pointing_hand 等
    direct = {}
    for src, dst in rows:
        direct.setdefault(dst, set()).add(src)
    # BFS：rank 0 = 该逻辑名自身，1 = alias.list 直系，2+ = theme-specific fallback 传递
    rank = {}
    for src, dst in rows:            # 两侧都要初始化，否则 src 查到空字典就断了
        rank.setdefault(src, {src: 0})
        rank.setdefault(dst, {dst: 0})
    changed = True
    while changed:
        changed = False
        for dst, srcs in direct.items():
            for s in srcs:
                for cur, d in list(rank.get(s, {}).items()):
                    tgt = rank.setdefault(dst, {})
                    if cur not in tgt or tgt[cur] > d + 1:
                        tgt[cur] = d + 1
                        changed = True
    return rank


# alias.list 没写、但语义与形状都核对过的补充映射
SUPPLEMENT = {
    "cell": ["plus"],                 # AOSP cell = 粗十字，仓库里叫 plus（cross 是细十字→crosshair）
    "alias": ["alias", "link"],       # 带快捷方式角标的箭头
    "nodrop": ["dnd-no-drop", "dnd_no_drop", "no-drop", "not-allowed"],
    "copy": ["dnd-copy", "dnd_copy"],
    "all-scroll": ["fleur", "sizeall"],
    "vertical-text": ["vertical_text"],
}


def repo_names(theme):
    """仓库里所有可用 SVG 名（去扩展名）。"""
    out = {}
    for d in THEMES[theme]["dirs"]:
        for f in sorted(os.listdir(d)):
            if not f.endswith(".svg"):
                continue
            out.setdefault(f[:-4], f"{d}/{f}")
            m = re.match(r"^(.*?)-\d+$", f[:-4])       # wait-01.svg → wait（多帧取第一帧）
            if m:
                out.setdefault(m.group(1), f"{d}/{f}")
    return out


def svg_size(path):
    head = open(path, errors="ignore").read(4000)
    w = re.search(r'width\s*=\s*"([\d.]+)', head)
    h = re.search(r'height\s*=\s*"([\d.]+)', head)
    return (float(w.group(1)) if w else 0.0, float(h.group(1)) if h else 0.0)


# ---------------------------------------------------------------- 热点
def hotspots(theme):
    """→ {name: (fx, fy)} 比例（0..1），单位换算到该主题自己的设计空间。"""
    cfg = THEMES[theme]
    out = {}
    if cfg["hot"] == "toml":
        import tomllib
        d = tomllib.load(open(cfg["toml"], "rb"))
        cs = d["cursors"]
        fb = cs.get("fallback_settings", {})
        for name, c in cs.items():
            if name == "fallback_settings":
                continue
            x = c.get("x_hotspot", fb.get("x_hotspot"))
            y = c.get("y_hotspot", fb.get("y_hotspot"))
            png = c.get("png", "").replace("-*", "").replace(".png", "")
            p = None
            for d2 in cfg["dirs"]:
                cand = f"{d2}/{png}.svg"
                if os.path.exists(cand):
                    p = cand
                    break
            if p and x is not None:
                w, h = svg_size(p)
                if w and h:
                    out[name] = (x / w, y / h)
    else:  # material：src/config/<name>.cursor = "size xhot yhot png"
        cdir = f"{TMP}/material-cursors/src/config"
        for f in sorted(os.listdir(cdir)):
            if not f.endswith(".cursor"):
                continue
            first = open(f"{cdir}/{f}").readline().split()
            if len(first) >= 3:
                size, x, y = float(first[0]), float(first[1]), float(first[2])
                out[f[:-7]] = (x / size, y / size)
    return out


# ---------------------------------------------------------------- 形状匹配
def aosp_ref_svg(key):
    """把 Android vector XML 转成「全黑」SVG，用作形状参考。"""
    path = f"{AOSP}/vector/drawable/pointer_{key}_vector.xml"
    if key == "wait" and not os.path.exists(path):
        path = f"{AOSP}/vector/drawable/pointer_wait/pointer_wait_vector_0.xml"
    if not os.path.exists(path):
        return None
    x = re.sub(r"<!--.*?-->", "", open(path, encoding="utf-8").read(), flags=re.S)
    w = re.search(r'viewportWidth\s*=\s*"([\d.]+)', x)
    h = re.search(r'viewportHeight\s*=\s*"([\d.]+)', x)
    w, h = (w.group(1) if w else "24"), (h.group(1) if h else "24")
    body = []
    for m in re.finditer(r'<path\b[^>]*>', x, re.S):
        el = m.group(0)
        d = re.search(r'pathData\s*=\s*"([^"]*)"', el)
        if not d:
            continue
        fill = "none" if 'fillColor="@android:color/transparent"' in el else "#000000"
        body.append(f'<path d="{d.group(1)}" fill="{fill}" fill-rule="evenodd"/>')
    os.makedirs(REF, exist_ok=True)
    out = f"{REF}/{key}.svg"
    open(out, "w").write(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}">'
                         + "".join(body) + "</svg>")
    return out


def silhouette(src_svg, tag):
    """渲染成 200x200 的二值剪影（mode 1），带缓存。"""
    os.makedirs(CACHE, exist_ok=True)
    png = f"{CACHE}/{tag}.png"
    if not os.path.exists(png):
        r = subprocess.run(["rsvg-convert", "-w", str(SIZE), "-h", str(SIZE), src_svg, "-o", png],
                           capture_output=True)
        if r.returncode != 0 or not os.path.exists(png):
            return None
    im = Image.open(png).convert("RGBA").getchannel("A").point(lambda v: 255 if v > 127 else 0)
    return im.convert("1")


def iou_shift(a, b, rng=8, step=2):
    """带平移搜索的 IoU：不同仓库的画布留白不一样。"""
    best = 0.0
    for dx in range(-rng, rng + 1, step):
        for dy in range(-rng, rng + 1, step):
            bb = b.transform(b.size, Image.AFFINE, (1, 0, -dx, 0, 1, -dy), resample=Image.NEAREST)
            inter = ImageChops.logical_and(a, bb).histogram()[255]
            union = ImageChops.logical_or(a, bb).histogram()[255]
            if union and inter / union > best:
                best = inter / union
    return best


# ---------------------------------------------------------------- 主流程
def main():
    alias = load_aliases()
    report, mapping = [], {}
    for num, key, logical in TYPES:
        ref_svg = aosp_ref_svg(key)
        ref = silhouette(ref_svg, f"ref_{key}") if ref_svg else None
        entry = {"key": key, "logical": logical, "theme": {}}
        for theme in THEMES:
            names = repo_names(theme)
            hots = hotspots(theme)
            ranks = dict(alias.get(logical, {logical: 0}))
            for extra in SUPPLEMENT.get(logical, ()):
                ranks.setdefault(extra, 1)
            cands = set()
            for name in ranks:
                if name in names:
                    cands.add(name)
            uncovered = not cands
            best = None
            for name in sorted(cands):
                s = silhouette(names[name], f"{theme}_{name}")
                if s is None:
                    continue
                score = (iou_shift(ref, s) if ref is not None else 1.0) - 0.02 * ranks.get(name, 9)
                if best is None or score > best[0]:
                    best = (score, name)
            if best:
                fx, fy = hots.get(best[1], (None, None))
                entry["theme"][theme] = {"svg": best[1], "iou": round(best[0], 3),
                                         "hot": [fx, fy]}
            else:
                entry["theme"][theme] = {}
                if uncovered:
                    report.append(f"    {theme}/{key} 仓库无对应素材 → 用 AOSP 官方图")
        mapping[num] = entry

    os.makedirs(os.path.dirname(OUT_JSON), exist_ok=True)
    json.dump(mapping, open(OUT_JSON, "w"), indent=1, ensure_ascii=False)

    print("类型                        material            apple               googledot           breezex")
    for num, key, logical in TYPES:
        e = mapping[num]
        cells = []
        for theme in ("material", "apple", "googledot", "breezex"):
            t = e["theme"][theme]
            if t:
                h = t["hot"]
                hs = f"{h[0]:.3f},{h[1]:.3f}" if h[0] is not None else "无热点"
                cells.append(f"{t['svg'][:13]:13s}{t['iou']:.2f} {hs[:9]}")
            else:
                cells.append(f"{'—':13s}     ")
        print(f"{num} {key:26s} " + " ".join(cells))
    print()
    for line in report:
        print(line)
    print(f"\n写 {OUT_JSON}")


if __name__ == "__main__":
    main()
