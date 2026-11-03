# -*- coding: utf-8 -*-
"""CubClone 桌面图标（自适应图标前景）生成器。

把候选页 120 格画布的几何渲染成 5 个密度的 ic_launcher_fg.png：
- 画布 108x108 dp（adaptive icon foreground）
- 内容缩放到直径 60/108，落在中心 66 安全区以内
- 背景透明，白底由 adaptive icon 的 background 层提供
"""
import sys, math
from PIL import Image, ImageDraw

INDIGO = (0x3A, 0x5C, 0xCC)  # 主色 靛蓝 #3A5CCC
TEAL   = (0x1F, 0xA8, 0xA0)  # 辅色 青绿 #1FA8A0

# G6-3: 青绿空心块(34,30)44x44 内孔17x17 + 靛蓝实心块(53,49)44x44，120 格画布
SHAPES = [
    ("rect", 34, 30, 44, 44, 10, TEAL),      # 青绿底
    ("rect", 47.5, 43.5, 17, 17, 4, (0,0,0,0)),  # 挖孔（透明，直接写像素）
    ("rect", 53, 49, 44, 44, 10, INDIGO),    # 靛蓝前块
]

SS = 8  # 超采样倍数
DENS = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

def rrect(d, x, y, w, h, r, fill, k):
    d.rounded_rectangle([x*k, y*k, (x+w)*k, (y+h)*k], radius=r*k, fill=fill)

def render(k):
    img = Image.new("RGBA", (120*k, 120*k), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for s in SHAPES:
        _, x, y, w, h, r, c = s
        rrect(d, x, y, w, h, r, c, k)
    return img

def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "app/res"
    # 内容 bbox: x 34..97, y 30..93 => 63x63，缩放到 60/108
    hi = render(SS)
    bbox = (34*SS, 30*SS, 97*SS, 93*SS)
    hi = hi.crop(bbox).resize((60*SS, 60*SS), Image.LANCZOS)
    for name, px in DENS.items():
        img = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        content = hi.resize((int(px*60/108), int(px*60/108)), Image.LANCZOS)
        ox = (px - content.width)//2
        oy = (px - content.height)//2
        img.paste(content, (ox, oy), content)
        p = f"{out}/drawable-{name}/ic_launcher_fg.png"
        img.save(p)
        print(p, img.size)

if __name__ == "__main__":
    main()
