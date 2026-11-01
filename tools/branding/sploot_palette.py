#!/usr/bin/env python3
"""Extract the Sploot brand palette from the final icon art.

Uses PIL adaptive quantize to get ~12 candidate swatches, then maps the
8 semantically named colors (nightMist, moonSurface, eucalypt, koalaFur,
dawnRose, starlight, textOnDark, outlineMuted) to the closest harmonious
swatches by hue/lightness role. Writes palette.json next to this script
and prints the palette.
"""
import colorsys
import json
import os
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ART = os.path.join(HERE, "sploot_icon_art_0.png")
OUT = os.path.join(HERE, "palette.json")

art = Image.open(ART).convert("RGB")
side = min(art.size)
art = art.crop(((art.width - side) // 2, (art.height - side) // 2,
                (art.width + side) // 2, (art.height + side) // 2))

# 24 candidate swatches with frequency (median-cut merges subtle subject
# tones into the dominant lavender at lower counts)
q = art.quantize(colors=24, method=Image.Quantize.MEDIANCUT)
pal = q.getpalette()
counts = sorted(q.getcolors(), reverse=True)
swatches = []
for cnt, idx in counts:
    r, g, b = pal[idx * 3: idx * 3 + 3]
    h, l, s = colorsys.rgb_to_hls(r / 255, g / 255, b / 255)
    swatches.append({"hex": "#{:02x}{:02x}{:02x}".format(r, g, b),
                     "rgb": (r, g, b), "h": h * 360, "l": l, "s": s,
                     "share": cnt / (side * side)})

def pick(pred, used):
    for sw in swatches:
        if id(sw) not in used and pred(sw):
            used.add(id(sw))
            return sw
    # fallback: least-bad unused
    for sw in swatches:
        if id(sw) not in used:
            used.add(id(sw))
            return sw
    return swatches[0]

used = set()

def hue_in(sw, lo, hi, lmin=0.0, lmax=1.0, smax=1.0):
    h = sw["h"]
    in_h = (lo <= h <= hi) if lo <= hi else (h >= lo or h <= hi)
    return in_h and lmin <= sw["l"] <= lmax and sw["s"] <= smax

def rgb_hex(rgb):
    return "#{:02x}{:02x}{:02x}".format(*rgb)

def shift_hsl(h, s, l):
    r, g, b = colorsys.hls_to_rgb(h / 360, l, s)
    return rgb_hex(tuple(int(round(c * 255)) for c in (r, g, b)))

# background: median edge color (the mist the icon sits in)
p = art.load()
edge = [p[x, 0] for x in range(0, side, 8)] + [p[x, side - 1] for x in range(0, side, 8)] + \
       [p[0, y] for y in range(0, side, 8)] + [p[side - 1, y] for y in range(0, side, 8)]
m = len(edge) // 2
bg_rgb = tuple(sorted(c[i] for c in edge)[m] for i in range(3))
h, l, s = colorsys.rgb_to_hls(*[c / 255 for c in bg_rgb])
bg = {"hex": rgb_hex(bg_rgb), "h": h * 360, "l": l, "s": s, "share": 0.0}
used = set()

# moonSurface: lightest remaining swatch
surface = pick(lambda x: x["l"] >= 0.72, used)
# eucalypt: sage green hue 70-170; derive harmonized sage if absent
euc = pick(lambda x: hue_in(x, 70, 170, 0.25, 0.75), used)
if not (70 <= euc["h"] <= 170):
    euc = {"hex": shift_hsl(115, min(euc["s"], 0.18), euc["l"]), "fromDerived": True}
# koalaFur: low-sat grey
fur = pick(lambda x: x["s"] < 0.25 and 0.30 <= x["l"] <= 0.65, used)
# dawnRose: pink/rose hue 320-20; derive if absent
rose = pick(lambda x: hue_in(x, 320, 20, 0.30, 0.88), used)
if not (rose["h"] >= 320 or rose["h"] <= 20):
    rose = {"hex": shift_hsl(350, min(rose["s"], 0.20), min(rose["l"], 0.78)), "fromDerived": True}
# starlight: pale, near-white
star = pick(lambda x: x["l"] > 0.78, used)
# textOnDark: darkest swatch, deepened for contrast
dark = min(swatches, key=lambda x: x["l"])
text_hex = shift_hsl(dark["h"], min(dark["s"] + 0.05, 1.0), max(dark["l"] * 0.45, 0.14))
# outlineMuted: mid grey, mid lightness, low saturation
outline = pick(lambda x: x["s"] < 0.30 and 0.35 <= x["l"] <= 0.6, used)

palette = {
    "source": "tools/branding/sploot_icon_art_0.png",
    "swatches": [{"hex": x["hex"], "share": round(x["share"], 3)} for x in swatches],
    "colors": {
        "nightMist":    {"hex": bg["hex"],      "fromSwatch": "edge-median",  "role": "background"},
        "moonSurface":  {"hex": surface["hex"], "fromSwatch": surface["hex"], "role": "surface"},
        "eucalypt":     {"hex": euc["hex"],     "fromSwatch": euc.get("fromSwatch", euc["hex"]), "role": "primary"},
        "koalaFur":     {"hex": fur["hex"],     "fromSwatch": fur["hex"],     "role": "secondary"},
        "dawnRose":     {"hex": rose["hex"],    "fromSwatch": rose.get("fromSwatch", rose["hex"]), "role": "accent"},
        "starlight":    {"hex": star["hex"],    "fromSwatch": star["hex"],    "role": "supporting-light"},
        "textOnDark":   {"hex": text_hex,       "fromSwatch": dark["hex"] + " (deepened)", "role": "text"},
        "outlineMuted": {"hex": outline["hex"], "fromSwatch": outline["hex"], "role": "supporting-outline"},
    },
}

with open(OUT, "w") as f:
    json.dump(palette, f, indent=2)
    f.write("\n")

print("swatches:", [(s["hex"], round(s["share"], 2)) for s in swatches])
for name, c in palette["colors"].items():
    print(f"{name:14s} {c['hex']}  ({c['role']})")
print("wrote", OUT)
