#!/usr/bin/env python3
"""Subset FgoStory_td.otf to the Traditional Chinese text actually used.

Sources:
  - Atlas Academy TW story scripts (story_assets/cloud/atlas/TW/script)
  - App Traditional Chinese resources
  - ASCII, CJK punctuation, fullwidth forms and common symbols

The output keeps every codepoint that the original font supported in the
collected corpus. Codepoints missing from the original font are reported but
cannot be invented; they rendered as fallback/tofu before the subset too.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import unicodedata
from pathlib import Path
from xml.sax.saxutils import unescape

from fontTools import subset
from fontTools.ttLib import TTFont

REPO = Path(__file__).resolve().parents[2]
DEFAULT_FONT = REPO / "app/src/main/assets/fonts/FgoStory_td.otf"
DEFAULT_ATLAS = REPO / "story_assets/cloud/atlas/TW/script"
DEFAULT_OUT = REPO / "tools/fonts/out/FgoStory_td_subset.otf"
DEFAULT_REPORT = REPO / "tools/fonts/out/subset-report.json"

# Always useful for UI, names, numbers and punctuation.
EXTRA_TEXT = (
    "0123456789"
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    " !\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
    "　、。，．·…—–―「」『』【】《》〈〉（）〔〕［］｛｝"
    "！？：；／＼｜～＋－＝＊＃％＆＠＄"
    "“”‘’・‧•※→←↑↓★☆♪♡♥"
    "\u200b\u200c\u200d\ufeff"
)


def log(message: str) -> None:
    print(message)


def collect_text(root: Path) -> tuple[set[str], int]:
    chars: set[str] = set()
    if not root.exists():
        log(f"warning: corpus not found: {root}")
        return chars, 0
    count = 0
    for path in root.rglob("*.txt"):
        try:
            chars.update(path.read_text(encoding="utf-8", errors="replace"))
            count += 1
        except OSError:
            continue
    return chars, count


def collect_xml_text(root: Path) -> set[str]:
    chars: set[str] = set()
    if not root.exists():
        return chars
    for path in root.rglob("*.xml"):
        try:
            raw = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        # Keep text node contents only, not markup/attributes.
        for match in re.finditer(r">([^<]+)<", raw):
            chars.update(unescape(match.group(1)))
    return chars


def printable(char: str) -> bool:
    if char in "\u200b\u200c\u200d\ufeff":
        return True
    category = unicodedata.category(char)
    return not category.startswith("C")


def build_charset(font: TTFont, atlas: Path, extra_roots: list[Path], include_range: bool) -> tuple[set[int], set[str], dict]:
    cmap = font.getBestCmap()
    atlas_chars, file_count = collect_text(atlas)
    chars = set(atlas_chars)
    for root in extra_roots:
        chars.update(collect_xml_text(root))
    chars.update(EXTRA_TEXT)

    if include_range:
        # Common CJK punctuation + fullwidth forms that the font already has.
        for cp in list(range(0x3000, 0x3040)) + list(range(0xFF00, 0xFFF0)):
            char = chr(cp)
            if ord(char) in cmap:
                chars.add(char)
        for cp in range(0x00A0, 0x0100):
            if cp in cmap:
                chars.add(chr(cp))

    chars = {c for c in chars if printable(c)}
    supported = {c for c in chars if ord(c) in cmap}
    missing = sorted(c for c in chars if ord(c) not in cmap)
    codepoints = {ord(c) for c in supported}

    # Always retain ASCII and the notdef/recommended glyphs.
    codepoints.update(range(0x20, 0x7F))
    stats = {
        "atlas_files": file_count,
        "atlas_unique_chars": len(atlas_chars),
        "total_unique_chars": len(chars),
        "supported_chars": len(supported),
        "missing_from_original": [f"U+{ord(c):04X}" for c in missing],
    }
    return codepoints, set(supported), stats


def subset_font(source: Path, output: Path, codepoints: set[int]) -> None:
    options = subset.Options()
    options.layout_features = ["*"]
    options.name_IDs = ["*"]
    options.name_legacy = True
    options.name_languages = ["*"]
    options.notdef_glyph = True
    options.notdef_outline = True
    options.recommended_glyphs = True
    options.glyph_names = True
    options.legacy_cmap = True
    options.symbol_cmap = True
    options.hinting = True
    options.recalc_bounds = False
    options.recalc_timestamp = False

    font = subset.load_font(str(source), options)
    subsetter = subset.Subsetter(options=options)
    subsetter.populate(unicodes=codepoints)
    subsetter.subset(font)
    output.parent.mkdir(parents=True, exist_ok=True)
    subset.save_font(font, str(output), options)


def verify(original: Path, output: Path, required: set[str]) -> dict:
    original_font = TTFont(original)
    subset_font_obj = TTFont(output)
    original_cmap = original_font.getBestCmap()
    subset_cmap = subset_font_obj.getBestCmap()

    missing_from_subset = sorted(c for c in required if ord(c) in original_cmap and ord(c) not in subset_cmap)
    missing_original = sorted(c for c in required if ord(c) not in original_cmap)
    required_tables = ["cmap", "CFF ", "GSUB", "GPOS", "BASE", "VORG", "vhea", "vmtx", "name", "post"]
    tables = sorted(subset_font_obj.keys())
    missing_tables = [t for t in required_tables if t not in tables]
    return {
        "original_size": original.stat().st_size,
        "subset_size": output.stat().st_size,
        "original_glyphs": len(original_font.getGlyphOrder()),
        "subset_glyphs": len(subset_font_obj.getGlyphOrder()),
        "subset_cmap_entries": len(subset_cmap),
        "missing_from_subset": [f"U+{ord(c):04X}" for c in missing_from_subset],
        "missing_from_original": [f"U+{ord(c):04X}" for c in missing_original],
        "tables": tables,
        "missing_tables": missing_tables,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--font", type=Path, default=DEFAULT_FONT)
    parser.add_argument("--atlas", type=Path, default=DEFAULT_ATLAS)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--report", type=Path, default=DEFAULT_REPORT)
    parser.add_argument("--no-range", action="store_true", help="Do not add unused CJK/fullwidth range chars")
    parser.add_argument("--verify-visual", action="store_true", default=True)
    args = parser.parse_args()

    if not args.font.is_file():
        log(f"error: font not found: {args.font}")
        return 2

    source_font = TTFont(args.font)
    extra_roots = [
        REPO / "app/src/main/res/values-b+zh+Hant",
        REPO / "chapter/src/main/res/values-b+zh+Hant",
    ]
    codepoints, supported, stats = build_charset(
        source_font, args.atlas, extra_roots, include_range=not args.no_range
    )
    log(f"Atlas files: {stats['atlas_files']}, unique chars: {stats['atlas_unique_chars']}")
    log(f"Selected chars: {stats['supported_chars']} (missing from original: {len(stats['missing_from_original'])})")

    subset_font(args.font, args.out, codepoints)
    report = verify(args.font, args.out, supported)
    report.update(stats)
    report["output"] = str(args.out)

    if args.verify_visual:
        try:
            from PIL import Image, ImageChops, ImageDraw, ImageFont

            samples = [
                "繁體中文測試：幕間、魔術禮裝、羈絆、聖晶石。",
                "「歡迎來到人理保障機關迦勒底。」",
                "0123456789 ABCDEFGHIJKLMNOPQRSTUVWXYZ abcdefghijklmnopqrstuvwxyz",
            ]
            for path in sorted(args.atlas.rglob("*.txt"))[:40]:
                try:
                    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
                        line = line.strip()
                        if line and not line.startswith("["):
                            samples.append(line[:100])
                            break
                except OSError:
                    continue

            def render(path: Path, text: str):
                image = Image.new("L", (2400, 100), "white")
                draw = ImageDraw.Draw(image)
                font = ImageFont.truetype(str(path), 32)
                draw.text((4, 8), text, font=font, fill="black")
                return image

            diffs = []
            for text in samples:
                diff = ImageChops.difference(render(args.font, text), render(args.out, text)).getbbox()
                if diff is not None:
                    diffs.append({"text": text, "bbox": diff})
            report["visual_samples"] = len(samples)
            report["visual_diff"] = diffs
            report["visual_identical"] = not diffs
        except Exception as exc:  # Pillow may be missing in some environments
            report["visual_error"] = str(exc)

    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    log("")
    log(f"original: {report['original_size']:,} bytes / {report['original_glyphs']} glyphs")
    log(f"subset:   {report['subset_size']:,} bytes / {report['subset_glyphs']} glyphs")
    log(f"reduction: {100 * (1 - report['subset_size'] / report['original_size']):.1f}%")
    log(f"report: {args.report}")
    if report["missing_from_subset"]:
        log(f"ERROR: {len(report['missing_from_subset'])} required chars missing from subset")
        return 1
    if report.get("visual_identical") is False:
        log("ERROR: visual comparison differs")
        return 1
    log("OK: all Atlas/UI characters supported by the original are present")
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    raise SystemExit(main())
