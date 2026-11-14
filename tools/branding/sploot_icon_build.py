#!/usr/bin/env python3
"""Build Sploot Android launcher icons from a koala-head silhouette art PNG.

Phase-13 art direction: THE SHAPE IS THE ICON. The source art
(sploot_icon_art_*.png) is a flat minimal koala head with closed sleeping
eyes on a PURE WHITE background. This script keys the white away to
transparency (flood-fill from the corners, so white *inside* the subject
survives), feathers the alpha edge, un-premultiplies the white fringe so
there is no halo, and writes:

- mipmap-*dpi/ic_launcher.webp       RGBA head only, transparent bg
- mipmap-*dpi/ic_launcher_round.webp same, clipped to a circle
- mipmap-*dpi/ic_launcher_foreground.webp head large in the adaptive safe zone
- values/ic_launcher_background.xml  #00000000 (fully transparent; launchers
  that require an opaque background will composite the mask shape anyway —
  the head is self-contained and big, so masked crops still read correctly)
- drawable-nodpi/img_koala_ghost.webp  the one allowed koala presence: the
  head at very low opacity for the dreamlike Home header wash

Run AFTER sploot_icon_gen.py icon (and optionally mist; see
sploot_mist_build below for the header texture, built by the same pass if
sploot_mist_*.png exists).
"""
import glob
import os
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "..", "app", "src", "main", "res")

DENSITIES = {  # legacy icon px / adaptive foreground px (108dp)
    "mdpi": (48, 108),
    "hdpi": (72, 162),
    "xhdpi": (96, 216),
    "xxhdpi": (144, 324),
    "xxxhdpi": (192, 432),
}

WHITE_TOL = 224          # RGB channels above this count as "white" for keying
FEATHER = 1.2            # px of gaussian alpha feather at 1k


def newest(pattern):
    files = sorted(glob.glob(os.path.join(HERE, pattern)))
    return files[-1] if files else None


def quietest(pattern):
    """Among candidate art files, prefer the QUIETEST subject: the one whose
    non-white pixels have the lowest mean saturation (taste rule: dreamlike,
    never loud). Falls back to newest when tied."""
    import colorsys
    files = sorted(glob.glob(os.path.join(HERE, pattern)))
    if not files:
        return None

    def saturation_of(path):
        im = Image.open(path).convert("RGB")
        im.thumbnail((256, 256))
        px = list(im.getdata())
        subj = [p for p in px if min(p) <= 224]
        if not subj:
            return 0.0
        sats = [colorsys.rgb_to_hls(*(c / 255 for c in p))[2] for p in subj[::7]]
        return sum(sats) / len(sats)

    return min(files, key=saturation_of)


def key_white_to_alpha(im: Image.Image) -> Image.Image:
    """Flood-fill keying: white connected to the border becomes transparent;
    white enclosed by the subject (eye highlights etc.) stays opaque."""
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
        if x < 0 or y < 0 or x >= w or y >= h:
            continue
        i = y * w + x
        if visited[i] or not is_white(px[x, y]):
            continue
        visited[i] = 1
        stack += [(x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)]

    out = Image.new("RGBA", (w, h))
    opx, npx = out.load(), px
    for y in range(h):
        for x in range(w):
            i = y * w + x
            if visited[i]:
                continue
            r, g, b = npx[x, y]
            # soft key near the white threshold (anti-aliased edge)
            m = min(r, g, b)
            a = 255 if m <= WHITE_TOL - 24 else max(0, min(255, int((WHITE_TOL - m) * 255 / 24)))
            # un-premultiply: strip the white the renderer blended in
            if a > 0 and a < 255:
                f = a / 255.0
                r = max(0, min(255, int((r - (1 - f) * 255) / f)))
                g = max(0, min(255, int((g - (1 - f) * 255) / f)))
                b = max(0, min(255, int((b - (1 - f) * 255) / f)))
            opx[x, y] = (r, g, b, a)
    # feather the alpha channel only (color stays keyed, no white halo)
    alpha = out.split()[3].filter(ImageFilter.GaussianBlur(FEATHER))
    out.putalpha(alpha)
    return out


def crop_to_subject(im: Image.Image, margin: float = 0.06) -> Image.Image:
    """Crop to the alpha bounding box plus a small relative margin."""
    bbox = im.split()[3].getbbox()
    if bbox is None:
        return im
    w, h = im.size
    bw, bh = bbox[2] - bbox[0], bbox[3] - bbox[1]
    mx, my = int(w * margin), int(h * margin)
    box = (max(0, bbox[0] - mx), max(0, bbox[1] - my),
           min(w, bbox[2] + mx), min(h, bbox[3] + my))
    return im.crop(box)


def circle_mask(size):
    mask = Image.new("L", (size * 4, size * 4), 0)
    d = ImageDraw.Draw(mask)
    d.ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
    return mask.resize((size, size), Image.LANCZOS)


def build_launcher(head: Image.Image):
    # fully transparent adaptive background
    with open(f"{RES}/values/ic_launcher_background.xml", "w") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n'
                '<resources>\n    <color name="ic_launcher_background">#00000000</color>\n'
                '</resources>\n')

    for dpi, (legacy, fg) in DENSITIES.items():
        mip = f"{RES}/mipmap-{dpi}"
        os.makedirs(mip, exist_ok=True)
        # legacy icons: square canvas, head centered, generous but not huge
        sq = int(legacy * 0.82)
        head_sq = head.resize((sq, sq), Image.LANCZOS)
        canvas = Image.new("RGBA", (legacy, legacy), (0, 0, 0, 0))
        canvas.paste(head_sq, ((legacy - sq) // 2, (legacy - sq) // 2), head_sq)
        canvas.save(f"{mip}/ic_launcher.webp", "WEBP", quality=95)
        round_im = canvas.copy()
        round_im.putalpha(Image.composite(
            circle_mask(legacy), Image.new("L", (legacy, legacy), 0), round_im.split()[3]))
        round_im.save(f"{mip}/ic_launcher_round.webp", "WEBP", quality=95)
        # adaptive foreground: head LARGE within the 66dp safe zone of 108dp
        safe = int(fg * 0.60)
        head_fg = head.resize((safe, safe), Image.LANCZOS)
        fgc = Image.new("RGBA", (fg, fg), (0, 0, 0, 0))
        fgc.paste(head_fg, ((fg - safe) // 2, (fg - safe) // 2), head_fg)
        fgc.save(f"{mip}/ic_launcher_foreground.webp", "WEBP", quality=95)
        print("wrote", dpi)

    adaptive = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/ic_launcher_background" />\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
        '</adaptive-icon>\n'
    )
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        with open(f"{RES}/mipmap-anydpi/{name}", "w") as f:
            f.write(adaptive)


def build_ghost(head: Image.Image):
    """The single in-app koala presence: head at ~10% ink for the header wash."""
    g = head.copy()
    alpha = g.split()[3].point(lambda a: int(a * 0.10))
    g.putalpha(alpha)
    g.thumbnail((640, 640), Image.LANCZOS)
    out = f"{RES}/drawable-nodpi/img_koala_ghost.webp"
    os.makedirs(os.path.dirname(out), exist_ok=True)
    g.save(out, "WEBP", quality=90)
    print("wrote", out)


def build_mist():
    """Header wash texture from sploot_mist_*.png: flatten contrast + tint
    toward the night-mist palette so it recedes behind the header."""
    src = newest("sploot_mist_*.png")
    if not src:
        print("no mist art found; skipping img_mist")
        return
    im = Image.open(src).convert("RGB")
    im.thumbnail((1400, 1400), Image.LANCZOS)
    # contrast reduction: pull toward the palette's night mist (#C1B7D8)
    mist = (0xC1, 0xB7, 0xD8)
    px = im.load()
    for y in range(im.height):
        for x in range(im.width):
            r, g, b = px[x, y]
            px[x, y] = (
                int(r * 0.45 + mist[0] * 0.55),
                int(g * 0.45 + mist[1] * 0.55),
                int(b * 0.45 + mist[2] * 0.55),
            )
    out = f"{RES}/drawable-nodpi/img_mist.webp"
    os.makedirs(os.path.dirname(out), exist_ok=True)
    im.save(out, "WEBP", quality=88)
    print("wrote", out)


if __name__ == "__main__":
    # prefer the soft-3D mechanical candidates when present; else legacy flat art
    art = quietest("sploot_icon3d_art_*.png") or quietest("sploot_icon_art_*.png")
    print("keying", os.path.basename(art))
    head = crop_to_subject(key_white_to_alpha(Image.open(art)))
    build_launcher(head)
    build_ghost(head)
    build_mist()
    print("done")
