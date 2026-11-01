#!/usr/bin/env python3
"""Generate Sploot bottom-nav glyph art via the PPQ image API.

Five tiny glyph studies, one per destination: record (moon over a soft
pillow/bed), diary (stacked little night cards with a striped sleep bar),
report (hypnogram steps), trends (rising curve with moon), settings
(eucalypt sprig through a ring). Generated on PURE WHITE backgrounds so
nav_icon_build.py can key them to tintable alpha masks.
"""
import json
import os
import sys
import urllib.request

API_KEY_PATH = "/home/twain/Projects/small_scripts/ppq_imageGen_apikey.txt"
URL = "https://api.ppq.ai/v1/images/generations"
MODEL = "grok-imagine-image-2"

HERE = os.path.dirname(os.path.abspath(__file__))

BASE_STYLE = (
    "A single minimal flat vector app glyph, centered on a COMPLETELY flat "
    "pure white #FFFFFF background with nothing else: no border, no shadow, "
    "no gradient, no background scenery, no text, no letters, no numbers. "
    "Bold simple rounded organic shapes, generous white margin around the "
    "subject, one solid soft muted eucalyptus sage green color with dusty "
    "lavender accents only. Dreamy quiet moonlit mood. "
)

JOBS = [
    ("nav_record", "A soft crescent moon resting above a small rounded pillow, "
     "one tiny star. Sleep recording glyph."),
    ("nav_diary", "Two small rounded rectangles stacked like cards, the top one "
     "filled with thin vertical stripes of different soft colors like a sleep "
     "stage bar chart. Night diary glyph."),
    ("nav_report", "A stepped horizontal waveform like a sleep hypnogram: three "
     "connected flat steps at different heights. Sleep report glyph."),
    ("nav_trends", "A gentle rising line chart with three small data point dots "
     "and a tiny crescent moon at the end of the line. Sleep trends glyph."),
    ("nav_settings", "A circular ring made of a delicate eucalyptus branch with "
     "small round leaves, like a soft gear silhouette. Settings glyph."),
]


def generate(name, prompt):
    api_key = open(API_KEY_PATH).read().strip()
    payload = {
        "model": MODEL,
        "prompt": BASE_STYLE + prompt,
        "quality": "medium",
        "resolution": "1k",
        "aspect_ratio": "1:1",
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
    for img in result.get("data", []):
        url = img.get("url")
        if not url:
            print("no url:", img)
            continue
        with urllib.request.urlopen(url, timeout=120) as r:
            data = r.read()
        out = os.path.join(HERE, f"{name}.png")
        with open(out, "wb") as f:
            f.write(data)
        print("saved", name, len(data), "bytes")


def main():
    only = sys.argv[1:] or None
    for name, prompt in JOBS:
        if only and name not in only:
            continue
        print("==", name)
        generate(name, prompt)


if __name__ == "__main__":
    main()
