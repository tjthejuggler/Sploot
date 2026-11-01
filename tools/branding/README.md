# Sploot branding tools

Art direction (phase 13): **dreamlike, quiet, koala-ADJACENT.** The koala's
world — eucalyptus, mist, moonlight — not koalas everywhere. The ONE koala
presence in-app is a faint ghost silhouette in the Home header; the launcher
icon is the shape of a minimal koala head itself, on transparency.

## Regenerate

```sh
python3 sploot_icon_gen.py icon   # 2 koala-head candidates on PURE WHITE
python3 sploot_icon_gen.py mist   # misty eucalypt/moonlight wash (2:1)
python3 sploot_icon_build.py      # key, tint, and install all assets
```

- `sploot_icon_gen.py` — PPQ/grok-imagine prompts. The icon art MUST be
  requested on a pure white background so the build step can key it away.
- `sploot_palette.py` — derives palette.json from the icon art (unchanged).
- `sploot_icon_build.py` — the interesting one:
  - picks the **quietest** icon candidate automatically (lowest mean
    subject saturation — taste rule: never loud);
  - **flood-fill-from-corners keying** removes only border-connected
    white, so highlights inside the head survive;
  - anti-aliased edge pixels are soft-keyed (24-level ramp) and
    **un-premultiplied** (`c' = (c − (1−a)·255)/a`) so no white halo
    remains at any size; alpha is then gaussian-feathered ~1 px;
  - adaptive icon: `ic_launcher_background = #00000000` (transparent) with
    the head at 60% of the 108 dp canvas — safely inside the 66 dp safe
    zone, self-contained even under aggressive launcher masks;
  - legacy `ic_launcher(_round).webp` are RGBA head-only with transparency;
  - also emits `img_koala_ghost.webp` (head at 10% alpha, the single
    in-app koala presence) and `img_mist.webp` (contrast-flattened,
    night-mist-tinted header wash).
- Empty-state art is a hand-authored vector, not generated:
  `app/src/main/res/drawable/img_eucalypt_sprig.xml`.
