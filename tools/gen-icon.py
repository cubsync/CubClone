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

# G6-3 几何（120 格，与候选页逐值一致）
#   青绿空心块 (34,30) 44x44 rx10 + 内孔 (47.5,43.5) 17x17 rx4（挖穿）
#   靛蓝实心块 (53,49) 44x44 rx10
SHAPES = [
    ("rect", 34.0, 30.0, 44.0, 44.0, 10.0, TEAL),        # 青绿底
    ("rect", 47.5, 43.5, 17.0, 17.0, 4.0, (0, 0, 0, 0)), # 挖孔（真写透明，覆盖写）
    ("rect", 53.0, 49.0, 44.0, 44.0, 10.0, INDIGO),      # 靛蓝前块
]
BBOX = (34.0, 30.0, 97.0, 93.0)   # 图形紧密包围盒（63x63）
FILL = 48.0 / 108.0               # 内容占 108dp 画布比例（与 G5 定稿口径一致，远小于 66 安全区）

DENS = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}


def render_mark(k):
    """120 格画布 × k 倍超采样渲染 G6-3，返回 RGBA。"""
    img = Image.new("RGBA", (int(CANVAS * k), int(CANVAS * k)), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for shape in SHAPES:
        _, x, y, w, h, r, c = shape
        d.rounded_rectangle([x * k, y * k, (x + w) * k, (y + h) * k],
                            radius=r * k, fill=c)
    return img


def render_fg(size):
    """渲染单密度自适应图标前景（透明底）。"""
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
    k = s / 63.0
    def probe(x, y):                      # 120 格坐标 -> 画布像素
        return img.getpixel((int(ox + (x - 34) * k), int(ox + (y - 30) * k)))
    ok = True
    cases = [
        ("孔内(50,46) 透明", probe(50, 46), (0, 0, 0, 0)),
        ("青绿(56,36) 纯青绿", probe(56, 36), TEAL + (255,)),
        ("靛蓝(75,71) 纯靛蓝", probe(75, 71), INDIGO + (255,)),
    ]
    for name, got, want in cases:
        good = tuple(got) == tuple(want)
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
