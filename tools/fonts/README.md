# Traditional Chinese story font subset

`FgoStory_td.otf` is **GenRyuMin2 TW SB** (源流明體), SIL OFL 1.1.
The full pan-CJK font is 16.3 MB. The app only needs the characters used by
the Traditional Chinese story and UI.

## Result

Built from:

- Atlas Academy TW story store: `story_assets/cloud/atlas/TW/script`
  - 7,861 scripts
  - 5,214 unique characters
- `app/src/main/res/values-b+zh+Hant`
- `chapter/src/main/res/values-b+zh+Hant`
- ASCII, CJK punctuation, fullwidth forms

Output:

| | Original | Subset |
|---|---:|---:|
| Size | 16,326,508 B | 2,238,284 B |
| Glyphs | 37,684 | 5,677 |
| Reduction | — | **86.3%** |

Verification:

```text
missing_from_subset = 0
tables missing       = none
visual_identical     = true
```

The 22 characters missing from the original font were already missing before
subsetting (Latin/Greek/format characters, not Chinese glyphs); the subset does
not make rendering worse.

## Install the subset

Drop-in file:

```text
tools\fonts\out\FgoStory_td.otf
```

Replace the app asset with it:

```powershell
Copy-Item `
  tools\fonts\out\FgoStory_td.otf `
  app\src\main\assets\fonts\FgoStory_td.otf `
  -Force
```

Keep the filename `FgoStory_td.otf`; the app loads it by name.

## Rebuild

```powershell
python -m pip install -r tools\fonts\requirements.txt

python tools\fonts\subset_fgo_td.py
```

Useful flags:

```text
--font <path>    source OTF
--atlas <dir>    Atlas TW script directory
--out <path>     output OTF
--report <path>  JSON report
--no-range       do not add unused CJK/fullwidth range characters
```

## Why subsetting is safe

The script keeps:

- every codepoint in the Atlas TW corpus that the original font supports
- all UI strings
- ASCII and common CJK punctuation
- OpenType layout tables (`GSUB`, `GPOS`, `BASE`, `VORG`, `vhea`, `vmtx`)
- vertical metrics, names, and CFF outlines

It verifies that every required codepoint from the original remains in the
subset cmap, and renders a sample with Pillow to confirm the raster output is
identical.

## Limitations

- New story text with a character outside the corpus needs a rebuild.
- The subset covers the current Atlas TW snapshot
  (`fetched_at_utc: 2026-10-21`), not future chapters.
- Do not include unused CJK Extension ranges; that defeats the size reduction.
- Keep the SIL OFL 1.1 license/attribution for redistribution.

## Exact upstream font

- Project: <https://github.com/ButTaiwan/genryu-font>
- Release: <https://github.com/ButTaiwan/genryu-font/releases/tag/v2.100>
- TW OTF: <https://github.com/ButTaiwan/genryu-font/releases/download/v2.100/GenRyuMin2TW-otf.zip>
