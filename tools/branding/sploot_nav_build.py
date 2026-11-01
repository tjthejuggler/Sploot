#!/usr/bin/env python3
"""Build bottom-nav icons from nav_*.png glyph art.

Keys the white background to transparency (flood-fill from the borders so
white inside the subject survives), then writes drawable-nodpi webp masks.
The app tints them via ColorFilter, so luminance becomes alpha only —
the glyphs act as shapes the theme can color.
"""
import glob
import os
from PIL import Image, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "..", "app", "src", "main", "res")
OUT = os.path.join(RES, "drawable-nodpi")

WHITE_TOL = 224
SIZE = 96  # master size; Compose scales


def key_white(im: Image.Image) -> Image.Image:
    im = im.convert("RGB")
    w, h = im.size
    px = im.load()
    visited = bytearray(w * h)
    stack = []
    for x in range(w):
        stack += [(x, 0), (x, h - 1)]
    for y in range(h):
        stack += [(0, y), (w - 1, y)]

    def is_white(p):
        return p[0] > WHITE_TOL and p[1] > WHITE_TOL and p[2] > WHITE_TOL

    while stack:
        x, y = stack.pop()
        if x < 0 or x >= w or y < 0 or y >= h:
            continue
        i = y * w + x
        if visited[i] or not is_white(px[x, y]):
            continue
        visited[i] = 1
        stack += [(x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)]

    out = Image.new("RGBA", (w, h))
    opx = out.load()
    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y]
            if visited[x + y * w if False else (y * w + x)]:
                opx[x, y] = (0, 0, 0, 0)
            else:
                m = min(r, g, b)
                a = 255 if m <= WHITE_TOL - 24 else max(0, min(255, int((WHITE_TOL - m) * 255 / 24)))
                opx[x, y] = (255, 255, 255, a)  # white shape; app tints it
    alpha = out.split()[3].filter(ImageFilter.GaussianBlur(1.0))
    out.putalpha(alpha)
    return out


def crop_to_subject(im: Image.Image, margin: float = 0.10) -> Image.Image:
    bbox = im.split()[3].getbbox()
    if bbox is None:
        return im
    w, h = im.size
    mx, my = int(w * margin), int(h * margin)
    box = (max(0, bbox[0] - mx), max(0, bbox[1] - my),
           min(w, bbox[2] + mx), min(h, bbox[3] + my))
    return im.crop(box)


def main():
    os.makedirs(OUT, exist_ok=True)
    for src in sorted(glob.glob(os.path.join(HERE, "nav_*.png"))):
        name = os.path.splitext(os.path.basename(src))[0]
        im = key_white(Image.open(src))
        im = crop_to_subject(im).resize((SIZE, SIZE), Image.LANCZOS)
        out = os.path.join(OUT, f"ic_nav_{name.split('_', 1)[1]}.webp")
        im.save(out, "WEBP", quality=92)
        print("wrote", out)


if __name__ == "__main__":
    main()
