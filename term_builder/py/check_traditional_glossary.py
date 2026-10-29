"""Read-only audit of the three-column Hant exception table. No downloads or DB writes."""
from __future__ import annotations

import argparse
import csv
import json
import re
import sys
import unicodedata
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path

from build_db import split_parallel_components

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OVERRIDES = ROOT / "app/src/main/assets/translation/traditional_glossary_overrides.tsv"
HEADER = ("jp_source", "cn_reference", "zh_hant")
NAME_PUNCTUATION = set("・･·•.．。、,，!！?？:：;；()（）[]［］{}｛｝「」『』<>＜＞《》〈〉")
OCR_NAME_FOLD = str.maketrans("一二工－-ァィゥェォャュョ", "ーニエーーアイウエオヤユヨ")


def source_key(text: str) -> str:
    # Mirrors Translator.normalizeNameLookup, only AFTER an existing row was found.
    text = unicodedata.normalize("NFKC", text)
    return "".join(c for c in text if not c.isspace() and c not in NAME_PUNCTUATION).translate(OCR_NAME_FOLD)


def read_tsv(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8-sig", newline="") as stream:
        return list(csv.DictReader(stream, delimiter="\t"))


def read_overrides(path: Path) -> dict[str, dict[str, str]]:
    with path.open(encoding="utf-8-sig", newline="") as stream:
        lines = [line.rstrip("\r\n") for line in stream if line.strip() and not line.lstrip().startswith("#")]
    if not lines or tuple(lines[0].split("\t")) != HEADER:
        raise ValueError(f"Expected exactly these columns: {HEADER}")
    result = {}
    for number, line in enumerate(lines[1:], 2):
        fields = [value.strip() for value in line.split("\t")]
        if len(fields) != 3 or not all(fields) or any(unicodedata.category(c) == "Cc" for f in fields for c in f):
            raise ValueError(f"Line {number}: expected three nonempty fields without control characters")
        key = source_key(fields[0])
        if not key or key in result:
            raise ValueError(f"Line {number}: duplicate or blank Japanese key: {fields[0]}")
        result[key] = dict(zip(HEADER, fields))
    return result


@dataclass(frozen=True)
class CurrentEntry:
    source: str
    reference: str
    canonical_source: str


def load_current_entries(characters: Path, terms: Path) -> dict[str, list[CurrentEntry]]:
    result: dict[str, list[CurrentEntry]] = defaultdict(list)
    parts = []
    for path, jp_column, cn_column in [(characters, "jp_name", "cn_name"), (terms, "jp_term", "cn_term")]:
        for row in read_tsv(path):
            jp, cn = row[jp_column].strip(), row[cn_column].strip()
            entry = CurrentEntry(jp, cn, jp)
            result[source_key(jp)].append(entry)
            for alias in re.split(r"[,|]", row.get("aliases", "")):
                if alias.strip():
                    result[source_key(alias)].append(CurrentEntry(alias.strip(), cn, jp))
            parts.extend(CurrentEntry(a, b, a) for a, b in split_parallel_components(jp, cn))
    # Explicit curated rows win over generated components, as in build_db.py.
    for entry in parts:
        key = source_key(entry.source)
        if key not in result:
            result[key].append(entry)
    return dict(result)


def cached_roster(cache: Path, region: str) -> list[dict]:
    files = sorted(cache.glob(f"*__{region}__nice_servant.json*"))
    if len(files) != 1:
        return []
    return json.loads(files[0].read_text(encoding="utf-8-sig"))


def load_candidates(cache: Path, name_map: Path) -> dict[str, set[str]]:
    """Names, skills and NPs use the SAME entity ID and field. All are review candidates."""
    result: dict[str, set[str]] = defaultdict(set)

    def pair_names(japanese, traditional, fields):
        targets = defaultdict(list)
        for target in traditional:
            targets[target["id"]].append(target)
        for row in japanese:
            for target in targets.get(row["id"], []):
                for field in fields:
                    if row.get(field) and target.get(field):
                        result[source_key(row[field])].add(target[field].strip())

    jp = cached_roster(cache, "JP")
    tw = {r["id"]: r for r in cached_roster(cache, "TW")}
    pair_names(jp, list(tw.values()), ("name", "originalName", "battleName", "originalBattleName"))
    for row in jp:
        target = tw.get(row["id"])
        if target is None:
            continue
        for collection in ("skills", "noblePhantasms"):
            pair_names(row.get(collection, []), target.get(collection, []), ("name",))
    if name_map.is_file():
        for row in read_tsv(name_map):
            if row.get("cn_name_trad", "").strip():
                result[source_key(row["jp_name"])].add(row["cn_name_trad"].strip())
    return dict(result)


def character_comparison_map(translator: Path) -> dict[str, str]:
    # Screening only: this is the app's fallback map, NOT an Android ICU accuracy measurement.
    source = translator.read_text(encoding="utf-8")
    block = source.split("private val traditionalToSimplified = mapOf(", 1)[1].split("\n    )", 1)[0]
    return dict(re.findall(r"'(.)' to '(.)'", block))


def comparison_key(text: str, mapping: dict[str, str]) -> str:
    text = unicodedata.normalize("NFKC", text)
    return "".join(mapping.get(c, c) for c in text if not c.isspace() and c not in "·・.=")


def audit(overrides, current, candidates, character_map):
    report = []
    errors = []
    for key, row in overrides.items():
        refs = {entry.reference for entry in current.get(key, [])}
        if not refs:
            status = "missing_source"
        elif row["cn_reference"] not in refs:
            status = "stale_reference"
        elif len(refs) > 1:
            status = "conflicting_source"
        else:
            status = "covered"
        report.append({"status": status, **row})
        if status != "covered":
            errors.append(f"{status}: {row['jp_source']}")
    for key, entries in sorted(current.items()):
        if key in overrides or not candidates.get(key):
            continue
        # Aliases inherit the canonical override, without a second copy in the TSV.
        entries = list(dict.fromkeys(entries))
        if all(source_key(e.canonical_source) in overrides and
               overrides[source_key(e.canonical_source)]["cn_reference"] == e.reference for e in entries):
            continue
        refs = {e.reference for e in entries}
        targets = candidates[key]
        # Separator-only variation is not a naming conflict.
        target_forms = {comparison_key(t, {}) for t in targets}
        status = "source_conflict" if len(refs) != 1 else "reference_conflict" if len(target_forms) > 1 else "review"
        reference = " | ".join(sorted(refs))
        if status == "review" and all(comparison_key(reference, character_map) ==
                                      comparison_key(t, character_map) for t in targets):
            continue
        report.append({"status": status, "jp_source": entries[0].source,
                       "cn_reference": reference, "zh_hant": " | ".join(sorted(targets))})
    return report, errors


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--overrides", type=Path, default=DEFAULT_OVERRIDES)
    parser.add_argument("--characters", type=Path, default=ROOT / "term_builder/character_names.tsv")
    parser.add_argument("--terms", type=Path, default=ROOT / "term_builder/term.tsv")
    parser.add_argument("--atlas-cache", type=Path, default=ROOT / "term_builder/atlas_cache/jp_cn_name_map")
    parser.add_argument("--name-map", type=Path, default=ROOT / "term_builder/voice_tune/jp_cn_name_map.tsv")
    parser.add_argument("--report", type=Path, help="Explicit optional TSV output; default is stdout only")
    parser.add_argument("--check", action="store_true", help="Exit 1 for invalid or stale approved overrides")
    args = parser.parse_args(argv)
    try:
        overrides = read_overrides(args.overrides)
        current = load_current_entries(args.characters, args.terms)
        candidates = load_candidates(args.atlas_cache, args.name_map)
        mapping = character_comparison_map(ROOT / "app/src/main/java/com/fgogotran/translation/Translator.kt")
        report, errors = audit(overrides, current, candidates, mapping)
    except (OSError, ValueError, KeyError, IndexError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print(f"Approved overrides: {len(overrides)}; invalid/stale: {len(errors)}; review rows: {sum(r['status'] != 'covered' for r in report)}", file=sys.stderr)
    print("Review rows are candidates, not confirmed naming errors. Glyph screening uses the fallback map, not Android ICU.", file=sys.stderr)
    stream = args.report.open("w", encoding="utf-8", newline="") if args.report else sys.stdout
    try:
        writer = csv.DictWriter(stream, fieldnames=("status", *HEADER), delimiter="\t", lineterminator="\n")
        writer.writeheader()
        writer.writerows(report)
    finally:
        if args.report:
            stream.close()
    return 1 if args.check and errors else 0


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())
