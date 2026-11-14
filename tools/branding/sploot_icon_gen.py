#!/usr/bin/env python3
"""Generate Sploot brand artwork via grok-imagine-image-2 (PPQ API).

Sploot is a calm sleep-tracking app. Art direction (phase 13): dreamlike,
quiet, koala-ADJACENT. Imagery is the koala's WORLD — eucalyptus, mist,
moonlight — not koalas. The ONE koala presence is the launcher icon: a
flat minimal koala-head silhouette with closed sleeping eyes, generated
on a PURE WHITE background so sploot_icon_build.py can key it to
transparency.

The API key is read (never printed/moved/copied) from an absolute path in
small_scripts.

Usage:
  python3 sploot_icon_gen.py icon       # koala-head silhouette art (1:1, 1k) x2 variants
  python3 sploot_icon_gen.py icon3d     # cute soft-3D mechanical koala head (1:1, 1k) x2
  python3 sploot_icon_gen.py mist       # misty eucalypt/moonlight wash (2:1, 2k)
  python3 sploot_icon_gen.py all
"""
import json
import os
import sys
import urllib.request

API_KEY_PATH = "/home/twain/Projects/small_scripts/ppq_imageGen_apikey.txt"
URL = "https://api.ppq.ai/v1/images/generations"
MODEL = "grok-imagine-image-2"

HERE = os.path.dirname(os.path.abspath(__file__))

PALETTE_CLAUSE = (
    "Strictly limited harmonious palette of soft desaturated pastels: soft "
    "misty lavender, dusty periwinkle moon-blue, muted eucalyptus sage "
    "green, warm silver-grey, and a subtle hint of dusty rose; gentle "
    "low-contrast dreamy mood, like a moonlit eucalypt forest at dusk; "
    "absolutely no saturated neon colors, no dark gothic tones."
)

JOBS = {
    "icon": [
        {
            "name": "sploot_icon_art",
            "prompt": (
                "A flat minimal vector silhouette of a single koala HEAD "
                "only, front-on, centered on a PURE WHITE (#FFFFFF) "
                "background. The head is ONE soft solid muted grey-violet "
                "shape with two rounded ears; the only interior details are "
                "two tiny closed sleeping eyes drawn as gentle downward "
                "curves in slightly deeper charcoal, and a soft rounded "
                "charcoal nose. Absolutely no background, no gradient, no "
                "shadow, no scenery, no moon, no leaves, no text, no "
                "letters, no numbers — the koala head silhouette alone on "
                "flat pure white. Clean geometric minimal flat vector "
                "style, rounded organic shapes, generous white margin. " +
                PALETTE_CLAUSE
            ),
            "aspect_ratio": "1:1",
            "resolution": "1k",
        },
        {
            "name": "sploot_icon_art",
            "prompt": (
                "Minimal flat vector app-icon glyph: a soft rounded koala "
                "head silhouette front-on — big round fluffy ears and a "
                "gentle round face as one single solid dusty lavender-grey "
                "shape, eyes closed as two small calm sleeping arcs, one "
                "soft charcoal oval nose, faintly smiling. Solid muted "
                "eucalyptus sage tint. On a COMPLETELY flat pure white "
                "#FFFFFF background with nothing else: no moon, no leaves, "
                "no gradient background, no border, no shadow, no text, no "
                "letters. Very simple, very quiet, generous white margin. " +
                PALETTE_CLAUSE
            ),
            "aspect_ratio": "1:1",
            "resolution": "1k",
        },
    ],
    "icon3d": [
        {
            "name": "sploot_icon3d_art",
            "prompt": (
                "A cute 3D-rendered app icon mascot: a koala HEAD only, "
                "front-on, perfectly centered on a PURE WHITE (#FFFFFF) "
                "background. Same classic koala head proportions — big round "
                "fluffy ears, round gentle face — but rendered like a "
                "premium collectible vinyl toy with a subtle soft-matte "
                "mechanical robot feel: smooth ceramic-like matte surfaces, "
                "soft panel seams along the ears and forehead, tiny rivet "
                "dots at the ear rims, a small softly-glowing LED crescent "
                "under one ear. Closed sleeping eyes as gentle downward "
                "arcs, soft rounded charcoal nose, faint serene smile. Soft "
                "diffuse studio lighting, gentle ambient occlusion, subtle "
                "top-left key light, NO cast shadow on the background, no "
                "floor, no scenery, no moon, no leaves, no text, no "
                "letters, no numbers. Adorable, calm, huggable, "
                "professional product-render quality, generous white "
                "margin around the head. " + PALETTE_CLAUSE
            ),
            "aspect_ratio": "1:1",
            "resolution": "1k",
        },
        {
            "name": "sploot_icon3d_art",
            "prompt": (
                "Charming soft-3D koala head icon for a calm sleep app: a "
                "single koala head, front-on, centered on COMPLETELY flat "
                "pure white #FFFFFF with nothing else. Sculpted like a "
                "gentle rounded robot-vinyl toy — plump round cheeks, two "
                "big rounded ears with delicate matte mechanical panel "
                "lines and a faint brushed-metal inner-ear accent, tiny "
                "hexagonal seam detail on the forehead. Sweet closed "
                "sleeping eyes (two calm arcs), soft oval charcoal nose, "
                "very subtle contented smile. Pixar-adjacent cuteness but "
                "quiet and dreamlike, desaturated pastel ceramic palette "
                "with a faint moonlit rim light. Soft studio lighting, no "
                "ground shadow, no reflections on the background, no "
                "gradient background, no border, no text, no letters, no "
                "numbers. Generous white margin. " + PALETTE_CLAUSE
            ),
            "aspect_ratio": "1:1",
            "resolution": "1k",
        },
    ],
    "mist": [
        {
            "name": "sploot_mist",
            "prompt": (
                "A very wide, extremely soft and quiet watercolor wash: "
                "misty eucalyptus branches with pale round leaves drifting "
                "in from the upper corner, a faint diffuse moonlight glow "
                "in a misty lavender-periwinkle sky, distant blurred tree "
                "silhouettes dissolving into fog, two or three tiny faint "
                "stars. EVERYTHING low contrast, washed out, at the edge "
                "of visibility, like a half-remembered dream; no koala, no "
                "animals, no people, no text, no letters, no numbers, no "
                "border. " + PALETTE_CLAUSE
            ),
            "aspect_ratio": "2:1",
            "resolution": "2k",
        },
    ],
}


def generate(job):
    api_key = open(API_KEY_PATH).read().strip()
    payload = {
        "model": MODEL,
        "prompt": job["prompt"],
        "quality": "medium",
        "resolution": job["resolution"],
        "aspect_ratio": job["aspect_ratio"],
        "n": 1,
    }
    req = urllib.request.Request(
        URL,
        data=json.dumps(payload).encode(),
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=300) as resp:
        result = json.loads(resp.read().decode())
    print("cost:", result.get("cost", "?"))
    for i, img in enumerate(result.get("data", [])):
        url = img.get("url")
        if not url:
            print("no url returned:", img)
            continue
        with urllib.request.urlopen(url, timeout=120) as r:
            data = r.read()
        # find next free suffix for this basename
        n = 0
        while os.path.exists(os.path.join(HERE, f"{job['name']}_{n}.png")):
            n += 1
        fname = os.path.join(HERE, f"{job['name']}_{n}.png")
        with open(fname, "wb") as f:
            f.write(data)
        print("saved", os.path.basename(fname), len(data), "bytes")


def main():
    targets = sys.argv[1:] or ["all"]
    if targets == ["all"]:
        targets = list(JOBS)
    for t in targets:
        if t not in JOBS:
            print("unknown job:", t)
            sys.exit(2)
        for job in JOBS[t]:
            print(f"== {t}: {job['name']} ({job['aspect_ratio']}, {job['resolution']})")
            generate(job)


if __name__ == "__main__":
    main()
