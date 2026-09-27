"""
Add the English name-box column to jp_cn_name_map.tsv from Atlas NA script labels.

The existing jp_cn_name_map.tsv is produced by update_jp_cn_name_map.py (JP/CN/TW).
This add-on keeps those columns untouched and appends:

    en_name

using the NA region's script name boxes (short in-game names such as Lev, Mash,
Dr. Roman), with NA servant names and the local character_names.tsv en_name column
as fallbacks. Run after update_jp_cn_name_map.py.

Default mode is dry-run. Use --write after reviewing the summary.
"""

from __future__ import annotations

import argparse
import csv
import sys
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from update_jp_cn_name_map import (
    ATLAS_BASE,
    DEFAULT_CACHE_DIR,
    DEFAULT_CHARACTER_NAMES,
    DEFAULT_TERM_TSV,
    SERVANT_FIELDS,
    atlas_script_ids,
    best_value,
    clean,
    clean_int,
    clean_label,
    fetch_json,
    fetch_region_labels,
    split_name_parts,
    split_parallel_parts,
)

HEADER = ("jp_name", "cn_name_simp", "cn_name_trad", "en_name", "count")
MASK_TOKENS = {"???", "？？？", "■", "□", "▇", "█"}
DEFAULT_MAP_TSV = Path(__file__).resolve().parent.parent / "voice_tune" / "jp_cn_name_map.tsv"


def configure_output() -> None:
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure:
            try:
                reconfigure(encoding="utf-8", errors="replace")
            except Exception:
                pass


def read_rows(path: Path) -> list[dict[str, str]]:
    if not path.exists():
        raise SystemExit(f"Missing name map: {path}")
    with path.open("r", encoding="utf-8-sig", newline="") as file:
        reader = csv.DictReader(file, delimiter="\t")
        if not {"jp_name", "cn_name_simp", "cn_name_trad"}.issubset(reader.fieldnames or []):
            raise SystemExit(f"{path} is missing the JP/CN columns")
        return [dict(row) for row in reader]


def write_rows(path: Path, rows: list[dict[str, str]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8-sig", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=HEADER, delimiter="\t", lineterminator="\n")
        writer.writeheader()
        for row in rows:
            writer.writerow({key: row.get(key, "") for key in HEADER})


def is_mask_name(name: str) -> bool:
    return name.strip() in MASK_TOKENS


def build_en_counts(jp_scripts, na_scripts) -> dict[str, Counter[str]]:
    na_by_id = {item.script_id: item.labels for item in na_scripts}
    counts: dict[str, Counter[str]] = defaultdict(Counter)
    for jp_script in jp_scripts:
        na_labels = na_by_id.get(jp_script.script_id, ())
        for index, jp_name in enumerate(jp_script.labels):
            jp_parts = split_name_parts(jp_name)
            if not jp_parts:
                continue
            na_parts = (
                split_parallel_parts(na_labels[index], len(jp_parts))
                if index < len(na_labels)
                else []
            )
            for part_index, jp_part in enumerate(jp_parts):
                if is_mask_name(jp_part):
                    continue
                if na_parts and part_index < len(na_parts):
                    en_part = na_parts[part_index]
                    if en_part and not is_mask_name(en_part):
                        counts[jp_part][en_part] += 1
    return counts


def load_na_servant_fallback(
    jp_region: str,
    en_region: str,
    *,
    cache_dir: Path | None,
    refresh_cache: bool,
) -> dict[str, str]:
    jp_rows = fetch_json(
        f"{ATLAS_BASE}/export/{jp_region}/nice_servant.json",
        cache_dir=cache_dir,
        refresh_cache=refresh_cache,
    )
    en_rows = fetch_json(
        f"{ATLAS_BASE}/export/{en_region}/nice_servant.json",
        cache_dir=cache_dir,
        refresh_cache=refresh_cache,
    )
    en_by_no = {
        int(row["collectionNo"]): row
        for row in en_rows
        if isinstance(row, dict) and clean(row.get("collectionNo")).isdigit()
    }
    result: dict[str, str] = {}
    for jp_row in jp_rows:
        if not isinstance(jp_row, dict) or not clean(jp_row.get("collectionNo")).isdigit():
            continue
        en_row = en_by_no.get(int(jp_row["collectionNo"]))
        if not en_row:
            continue
        for field in SERVANT_FIELDS:
            jp_name = clean_label(jp_row.get(field))
            en_name = clean_label(en_row.get(field))
            if jp_name and en_name:
                result.setdefault(jp_name, en_name)
    return result


def load_local_english_fallback(character_path: Path, term_path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    if character_path.exists():
        with character_path.open("r", encoding="utf-8-sig", newline="") as file:
            for row in csv.DictReader(file, delimiter="\t"):
                jp_name = clean_label(row.get("jp_name"))
                en_name = clean_label(row.get("en_name"))
                if jp_name and en_name:
                    result.setdefault(jp_name, en_name)
    if term_path.exists():
        with term_path.open("r", encoding="utf-8-sig", newline="") as file:
            for row in csv.DictReader(file, delimiter="\t"):
                jp_term = clean_label(row.get("jp_term"))
                en_term = clean_label(row.get("en_term"))
                if jp_term and en_term:
                    result.setdefault(jp_term, en_term)
    return result


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Add the EN name-box column to jp_cn_name_map.tsv")
    parser.add_argument("--map-tsv", type=Path, default=DEFAULT_MAP_TSV)
    parser.add_argument("--jp-region", default="JP")
    parser.add_argument("--en-region", default="NA")
    parser.add_argument("--character-names", type=Path, default=DEFAULT_CHARACTER_NAMES)
    parser.add_argument("--term-tsv", type=Path, default=DEFAULT_TERM_TSV)
    parser.add_argument("--cache-dir", type=Path, default=DEFAULT_CACHE_DIR)
    parser.add_argument("--workers", type=int, default=32)
    parser.add_argument("--max-scripts", type=int, default=0)
    parser.add_argument("--refresh-cache", action="store_true")
    parser.add_argument("--no-cache", action="store_true")
    parser.add_argument("--no-servant-fallback", action="store_true")
    parser.add_argument("--no-local-fallback", action="store_true")
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--detail-limit", type=int, default=50)
    return parser.parse_args()


def main() -> None:
    configure_output()
    args = parse_args()
    cache_dir = None if args.no_cache else args.cache_dir

    rows = read_rows(args.map_tsv)
    script_ids = atlas_script_ids(
        args.jp_region,
        cache_dir=cache_dir,
        refresh_cache=args.refresh_cache,
    )
    if args.max_scripts > 0:
        script_ids = script_ids[: args.max_scripts]

    print(f"Loading JP scripts: {len(script_ids)}", file=sys.stderr)
    jp_scripts = fetch_region_labels(
        args.jp_region,
        script_ids,
        cache_dir=cache_dir,
        refresh_cache=args.refresh_cache,
        workers=args.workers,
    )
    needed_ids = [item.script_id for item in jp_scripts]
    print(f"Loading NA scripts: {len(needed_ids)}", file=sys.stderr)
    na_scripts = fetch_region_labels(
        args.en_region,
        needed_ids,
        cache_dir=cache_dir,
        refresh_cache=args.refresh_cache,
        workers=args.workers,
    )

    counts = build_en_counts(jp_scripts, na_scripts)
    servant_en = (
        {}
        if args.no_servant_fallback
        else load_na_servant_fallback(
            args.jp_region,
            args.en_region,
            cache_dir=cache_dir,
            refresh_cache=args.refresh_cache,
        )
    )
    local_en = (
        {}
        if args.no_local_fallback
        else load_local_english_fallback(args.character_names, args.term_tsv)
    )

    conflicts: list[str] = []
    missing: list[str] = []
    for row in rows:
        jp_name = clean_label(row.get("jp_name"))
        en_name, conflict = best_value(counts.get(jp_name, Counter()))
        if conflict:
            conflicts.append(jp_name)
            en_name = ""
        if not en_name:
            en_name = servant_en.get(jp_name, "") or local_en.get(jp_name, "")
        row["en_name"] = en_name
        row["count"] = clean_int(row.get("count")) or ""
        if not en_name:
            missing.append(jp_name)

    print("English name-box map summary")
    print(f"  rows: {len(rows)}")
    print(f"  with en_name: {len(rows) - len(missing)}")
    print(f"  missing en_name: {len(missing)}")
    print(f"  conflicts: {len(conflicts)}")
    limit = max(args.detail_limit, 0)
    if conflicts and limit:
        print("CONFLICTS")
        for name in conflicts[:limit]:
            print(f"  {name}")
    if missing and limit:
        print("MISSING")
        for name in missing[:limit]:
            print(f"  {name}")

    if args.write:
        write_rows(args.map_tsv, rows)
        print(f"Wrote {len(rows)} rows to {args.map_tsv}")
    else:
        print("No files written. Re-run with --write after reviewing the summary.")


if __name__ == "__main__":
    main()
