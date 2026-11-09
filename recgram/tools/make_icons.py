#!/usr/bin/env python3
"""PNG-иконки RecGram для Android 6–7 (без адаптивных иконок).

Рисует то же, что векторные слои mipmap-anydpi-v26/ic_launcher.xml:
красный ободок, синий круг, белое облачко чата с красной точкой записи.
Координаты — в системе слоя адаптивной иконки 108×108, видимая часть 18..90.

Запуск (нужен Pillow):  python make_icons.py <путь к TMessagesProj/src/main/res>
"""
import pathlib
import sys

from PIL import Image, ImageDraw

RED = (255, 0, 0, 255)
BLUE_TOP = (0x38, 0xAE, 0xEB)
BLUE_BOTTOM = (0x28, 0x9A, 0xD1)
WHITE = (255, 255, 255, 255)
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
SS = 8  # суперсэмплинг


def render(px):
    s = px * SS / 72.0  # 72 единицы видимой области → px

    def p(x, y):
        return ((x - 18) * s, (y - 18) * s)

    size = px * SS
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # красный круг на всю иконку (legacy-иконка круглая, поля ~2/48)
    m = size * 2 / 48
    d.ellipse([m, m, size - m, size - m], fill=RED)
    # синий круг r=31 с вертикальным градиентом
    grad = Image.new("RGBA", (size, size))
    gd = ImageDraw.Draw(grad)
    y0, y1 = p(0, 23)[1], p(0, 85)[1]
    for y in range(size):
        t = min(max((y - y0) / (y1 - y0), 0), 1)
        c = tuple(round(BLUE_TOP[i] + (BLUE_BOTTOM[i] - BLUE_TOP[i]) * t) for i in range(3))
        gd.line([(0, y), (size, y)], fill=c + (255,))
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse([*p(23, 23), *p(85, 85)], fill=255)
    img.paste(grad, (0, 0), mask)
    # облачко: скруглённый прямоугольник 35..73 × 37..65, r=10, и хвостик слева внизу
    d.rounded_rectangle([*p(35, 37), *p(73, 65)], radius=10 * s, fill=WHITE)
    d.polygon([p(40, 60), p(50, 65), p(41, 72.5), p(39.2, 71.6), p(40.4, 64)], fill=WHITE)
    # красная точка записи r=6.5 в центре облачка
    d.ellipse([*p(47.5, 44.5), *p(60.5, 57.5)], fill=RED)
    return img.resize((px, px), Image.LANCZOS)


def main():
    res = pathlib.Path(sys.argv[1])
    for dens, px in SIZES.items():
        icon = render(px)
        for name in ("ic_launcher.png", "ic_launcher_round.png"):
            out = res / f"mipmap-{dens}" / name
            icon.save(out, optimize=True)
            print(out, px)


if __name__ == "__main__":
    main()
