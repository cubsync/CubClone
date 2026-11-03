# -*- coding: utf-8 -*-
"""CubClone 桌面图标（自适应图标前景）生成器 —— 对齐 cubsync 定稿管线（org.py/brand.py）。

做法与定稿工具完全一致：
- 120 设计格几何 -> SS=8 超采样 -> ImageDraw 覆盖写（挖孔 = fill=(0,0,0,0) 写透明）
- LANCZOS 缩回目标尺寸
- 内置自检：跑完自动断言孔内透明、双色正确，任一失败即退出码 1

产出（5 密度 ic_launcher_fg.png）：
- 自适应图标前景 108dp 画布，内容按 fill=48/108（G5 定稿口径），落在中心 66 安全区以内
- 背景透明；白底由 adaptive icon 的 background 层提供（与 on-white 口径一致）
"""
import sys, os
from PIL import Image, ImageDraw

INDIGO = (0x3A, 0x5C, 0xCC)   # 主色 靛蓝 #3A5CCC
TEAL   = (0x1F, 0xA8, 0xA0)   # 辅色 青绿 #1FA8A0
WHITE  = (0xFF, 0xFF, 0xFF)

SS = 8                        # 与 org.py 同款超采样
CANVAS = 120.0

# G6-1 几何（120 格，与候选页逐值一致）
#   青绿圆 (52,48) r22 + 孔 r8.5（挖穿，组织幼体 5/13 比例）
#   靛蓝圆 (68,64) r22 实心
SHAPES = [
    ("circ", 52.0, 48.0, 22.0, TEAL),        # 青绿底圆
    ("circ", 52.0, 48.0, 8.5, (0, 0, 0, 0)), # 挖孔（真写透明，覆盖写）
    ("circ", 68.0, 64.0, 22.0, INDIGO),      # 靛蓝前圆
]
BBOX = (30.0, 26.0, 90.0, 86.0)   # 图形紧密包围盒（60x60）
FILL = 48.0 / 108.0               # 内容占 108dp 画布比例（与 G5 定稿口径一致，远小于 66 安全区）

DENS = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}


def render_mark(k):
    """120 格画布 × k 倍超采样渲染，返回 RGBA。支持 rect / circ。"""
    img = Image.new("RGBA", (int(CANVAS * k), int(CANVAS * k)), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for shape in SHAPES:
        kind = shape[0]
        if kind == "rect":
            _, x, y, w, h, r, c = shape
            d.rounded_rectangle([x * k, y * k, (x + w) * k, (y + h) * k],
                                radius=r * k, fill=c)
        else:  # circ
            _, x, y, r, c = shape
            d.ellipse([(x - r) * k, (y - r) * k, (x + r) * k, (y + r) * k],
                      fill=c)
    return img


def render_fg(size):
    """渲染单密度自适应图标前景（透明底）。层级与候选页一致：青绿→孔→靛蓝（靛蓝在上）。"""
    px = int(size * SS)                       # 输出目标 × SS 超采样
    hi = render_mark(SS)
    x0, y0, x1, y1 = BBOX
    hi = hi.crop((x0 * SS, y0 * SS, x1 * SS, y1 * SS))
    content_px = int(px * FILL)
    hi = hi.resize((content_px, content_px), Image.LANCZOS)
    img = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    ox = oy = (px - content_px) // 2
    img.paste(hi, (ox, oy), hi)
    return img.resize((size, size), Image.LANCZOS)


def selfcheck(img):
    """像素断言：孔内透明、青绿/靛蓝纯色。失败抛异常。"""
    W = img.width
    s = FILL * W
    ox = (W - s) / 2.0
    x0, y0, x1, y1 = BBOX
    span = max(x1 - x0, y1 - y0)
    k = s / span
    def probe(x, y):                      # 120 格坐标 -> 画布像素
        return img.getpixel((int(ox + (x - x0) * k), int(ox + (y - y0) * k)))
    ok = True
    cases = [
        # LANCZOS 重采样会在孔心留下 alpha 1~3 的振铃残影（org.py 成品同样存在），
        # 视觉不可见，断言按 alpha<=8 算透明。
        ("孔内(52,48) 透明", probe(52, 48)[3] <= 8, True),
        ("青绿(36,40) 纯青绿", probe(36, 40), TEAL + (255,)),
        ("靛蓝(75,71) 纯靛蓝", probe(75, 71), INDIGO + (255,)),
        # 层级：靛蓝在上，孔与靛蓝重叠区必须仍是纯靛蓝（不能透白）
        ("层级(56,52) 靛蓝盖孔", probe(56, 52), INDIGO + (255,)),
    ]
    for name, got, want in cases:
        good = (got == want) if isinstance(want, bool) else (tuple(got) == tuple(want))
        ok = ok and good
        print("%-22s %-8s got=%s want=%s" % (name, "PASS" if good else "FAIL", got, want))
    return ok


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "app/res"
    preview = sys.argv[2] if len(sys.argv) > 2 else None
    all_ok = True
    for name, px in DENS.items():
        img = render_fg(px)
        p = os.path.join(out, "drawable-%s" % name, "ic_launcher_fg.png")
        img.save(p)
        print(p, img.size)
        if name == "xxxhdpi" and not selfcheck(img):
            all_ok = False
    if preview:
        img = render_fg(432)
        bg = Image.new("RGBA", img.size, WHITE + (255,))
        bg.alpha_composite(img)
        bg.convert("RGB").save(preview)
        print("preview ->", preview)
    if not all_ok:
        print("SELFCHECK FAILED")
        sys.exit(1)
    print("selfcheck OK")


if __name__ == "__main__":
    main()
