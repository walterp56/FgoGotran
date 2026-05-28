"""
Fetch FGO terms from Atlas Academy and merge optional Mooncell CN TSV terms.

Outputs:
  term_builder/fgo_terms.json

Atlas is used for structured JP/CN game data where both regions expose the same
stable IDs. Mooncell is intentionally ingested from a local TSV so you can curate
or export CN terms without making the Android app depend on fragile wiki HTML.
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
import time
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


ATLAS_BASE = "https://api.atlasacademy.io"
HEADERS = {"Accept": "application/json", "User-Agent": "fgoGotran-term-builder/1.0"}

ROOT = Path(__file__).resolve().parent
DEFAULT_OUTPUT = ROOT / "fgo_terms.json"
DEFAULT_MOONCELL_TSV = ROOT / "mooncell_terms.tsv"
DEFAULT_CHARACTER_TSV = ROOT / "character_names.tsv"
DEFAULT_TERMS_TSV = ROOT / "terms.tsv"


COMMON_TERMS = [
    ("カルデア", "迦勒底", "place", ["Chaldea"]),
    ("マスター", "御主", "game_term", ["Master"]),
    ("サーヴァント", "从者", "game_term", ["Servant"]),
    ("英霊", "英灵", "game_term", []),
    ("宝具", "宝具", "game_term", ["Noble Phantasm"]),
    ("聖杯", "圣杯", "game_term", []),
    ("聖杯戦争", "圣杯战争", "game_term", []),
    ("令呪", "令咒", "game_term", []),
    ("霊基", "灵基", "game_term", []),
    ("霊衣", "灵衣", "game_term", []),
    ("魔術", "魔术", "game_term", []),
    ("魔術師", "魔术师", "game_term", []),
    ("魔力", "魔力", "game_term", []),
    ("特異点", "特异点", "game_term", []),
    ("異聞帯", "异闻带", "game_term", []),
    ("空想樹", "空想树", "game_term", []),
    ("人理", "人理", "game_term", []),
    ("人理修復", "人理修复", "game_term", []),
    ("レイシフト", "灵子转移", "game_term", ["Rayshift"]),
    ("シールダー", "盾兵", "class", ["Shielder"]),
    ("セイバー", "剑士", "class", ["Saber"]),
    ("アーチャー", "弓兵", "class", ["Archer"]),
    ("ランサー", "枪兵", "class", ["Lancer"]),
    ("ライダー", "骑兵", "class", ["Rider"]),
    ("キャスター", "术师", "class", ["Caster"]),
    ("アサシン", "暗匿者", "class", ["Assassin"]),
    ("バーサーカー", "狂战士", "class", ["Berserker"]),
    ("ルーラー", "裁定者", "class", ["Ruler"]),
    ("アヴェンジャー", "复仇者", "class", ["Avenger"]),
    ("ムーンキャンサー", "MoonCancer", "class", []),
    ("アルターエゴ", "Alterego", "class", []),
    ("フォーリナー", "Foreigner", "class", []),
    ("プリテンダー", "Pretender", "class", []),
]


def fetch_json(endpoint: str, retries: int = 3) -> Any:
    url = f"{ATLAS_BASE}{endpoint}"
    for attempt in range(1, retries + 1):
        try:
            request = Request(url, headers=HEADERS)
            with urlopen(request, timeout=45) as response:
                return json.loads(response.read().decode("utf-8"))
        except (HTTPError, URLError, TimeoutError, json.JSONDecodeError) as exc:
            if attempt == retries:
                print(f"warning: failed to fetch {url}: {exc}", file=sys.stderr)
                return [] if endpoint.endswith(".json") else {}
            time.sleep(attempt)


def add_term(
    terms: list[dict[str, Any]],
    jp_name: str | None,
    cn_name: str | None,
    category: str,
    aliases: list[str] | None = None,
    source: str = "atlas",
) -> None:
    jp = clean_text(jp_name)
    cn = clean_text(cn_name)
    if not jp or not cn or jp == cn:
        return
    clean_aliases = sorted(
        {
            clean_text(alias)
            for alias in (aliases or [])
            if clean_text(alias) and clean_text(alias) != jp
        }
    )
    terms.append(
        {
            "jp_name": jp,
            "cn_name": cn,
            "category": category,
            "aliases": json.dumps(clean_aliases, ensure_ascii=False),
            "source": source,
        }
    )


def clean_text(value: Any) -> str:
    if value is None:
        return ""
    return str(value).strip()


def validate_required_columns(
    path: Path,
    line_num: int,
    row: dict[str, Any],
    required: set[str],
) -> None:
    missing = [column for column in sorted(required) if not clean_text(row.get(column))]
    if not missing:
        return

    first_value = clean_text(next((row.get(column) for column in required if row.get(column)), ""))
    hint = ""
    if "\t" not in first_value and "  " in first_value:
        hint = " This row looks space-separated; use real tab characters between columns."
    raise SystemExit(
        f"{path}:{line_num} is missing required column(s): "
        f"{', '.join(missing)}.{hint}"
    )


def by_key(rows: Any, key_name: str = "id") -> dict[Any, dict[str, Any]]:
    if not isinstance(rows, list):
        return {}
    result = {}
    for row in rows:
        key = row.get(key_name)
        if key is not None:
            result[key] = row
    return result


def ingest_common_terms(terms: list[dict[str, Any]]) -> None:
    for jp, cn, category, aliases in COMMON_TERMS:
        add_term(terms, jp, cn, category, aliases, "manual")


def ingest_servants(terms: list[dict[str, Any]]) -> None:
    jp_servants = fetch_json("/export/JP/servant/all.json")
    cn_servants = by_key(fetch_json("/export/CN/servant/all.json"), "collectionNo")
    if not isinstance(jp_servants, list):
        return

    for servant in jp_servants:
        collection_no = servant.get("collectionNo")
        cn_servant = cn_servants.get(collection_no, {})
        aliases = [servant.get("ruby"), servant.get("battleName")]
        add_term(terms, servant.get("name"), cn_servant.get("name"), "servant", aliases)

        jp_nps = servant.get("noblePhantasms") or []
        cn_nps = by_key(cn_servant.get("noblePhantasms") or [], "id")
        for jp_np in jp_nps:
            cn_np = cn_nps.get(jp_np.get("id"), {})
            jp_name = jp_np.get("originalName") or jp_np.get("name")
            cn_name = cn_np.get("name")
            add_term(terms, jp_name, cn_name, "noble_phantasm")

        jp_skills = servant.get("skills") or []
        cn_skills = by_key(cn_servant.get("skills") or [], "id")
        for jp_skill in jp_skills:
            cn_skill = cn_skills.get(jp_skill.get("id"), {})
            jp_name = jp_skill.get("originalName") or jp_skill.get("name")
            cn_name = cn_skill.get("name")
            add_term(terms, jp_name, cn_name, "skill")


def ingest_pair_export(
    terms: list[dict[str, Any]],
    endpoint_name: str,
    category: str,
    key_name: str = "id",
) -> None:
    jp_rows = fetch_json(f"/export/JP/{endpoint_name}.json")
    cn_rows = by_key(fetch_json(f"/export/CN/{endpoint_name}.json"), key_name)
    if not isinstance(jp_rows, list):
        return

    for jp_row in jp_rows:
        cn_row = cn_rows.get(jp_row.get(key_name), {})
        jp_name = jp_row.get("originalName") or jp_row.get("name")
        cn_name = cn_row.get("name")
        add_term(terms, jp_name, cn_name, category)


def ingest_mooncell_tsv(terms: list[dict[str, Any]], path: Path) -> None:
    if not path.exists():
        print(f"Mooncell TSV not found, skipping: {path}")
        return

    with path.open("r", encoding="utf-8-sig", newline="") as file:
        reader = csv.DictReader(file, delimiter="\t")
        required = {"jp_name", "cn_name"}
        if not required.issubset(reader.fieldnames or []):
            raise SystemExit(
                f"{path} must contain at least columns: jp_name, cn_name"
            )
        for row in reader:
            aliases = [
                alias.strip()
                for alias in (row.get("aliases") or "").replace("，", ",").split(",")
                if alias.strip()
            ]
            add_term(
                terms,
                row.get("jp_name"),
                row.get("cn_name"),
                row.get("category") or "mooncell",
                aliases,
                row.get("source") or "mooncell",
            )


def ingest_character_names_tsv(terms: list[dict[str, Any]], path: Path) -> None:
    if not path.exists():
        print(f"Character TSV not found, skipping: {path}")
        return

    with path.open("r", encoding="utf-8-sig", newline="") as file:
        reader = csv.DictReader(file, delimiter="\t")
        required = {"jp_name", "cn_name"}
        if not required.issubset(reader.fieldnames or []):
            raise SystemExit(f"{path} must contain at least columns: jp_name, cn_name")
        for row in reader:
            if not any(clean_text(value) for value in row.values()):
                continue
            validate_required_columns(path, reader.line_num, row, required)
            aliases = split_aliases(row.get("aliases") or "")
            add_term(
                terms,
                row.get("jp_name"),
                row.get("cn_name"),
                "character",
                aliases,
                row.get("source") or "mooncell",
            )


def ingest_terms_tsv(terms: list[dict[str, Any]], path: Path) -> None:
    if not path.exists():
        print(f"Terms TSV not found, skipping: {path}")
        return

    with path.open("r", encoding="utf-8-sig", newline="") as file:
        reader = csv.DictReader(file, delimiter="\t")
        required = {"jp_term", "cn_term", "category"}
        if not required.issubset(reader.fieldnames or []):
            raise SystemExit(f"{path} must contain columns: jp_term, cn_term, category")
        for row in reader:
            if not any(clean_text(value) for value in row.values()):
                continue
            validate_required_columns(path, reader.line_num, row, required)
            aliases = split_aliases(row.get("aliases") or "")
            add_term(
                terms,
                row.get("jp_term"),
                row.get("cn_term"),
                row.get("category") or "term",
                aliases,
                row.get("source") or "mooncell",
            )


def ingest_local_tsvs(
    terms: list[dict[str, Any]],
    character_tsv: Path,
    terms_tsv: Path,
    legacy_tsv: Path,
) -> None:
    if character_tsv.exists() or terms_tsv.exists():
        ingest_character_names_tsv(terms, character_tsv)
        ingest_terms_tsv(terms, terms_tsv)
    else:
        ingest_mooncell_tsv(terms, legacy_tsv)


def split_aliases(value: str) -> list[str]:
    return [
        alias.strip()
        for alias in value.split(",")
        if alias.strip()
    ]


def dedupe_terms(terms: list[dict[str, Any]]) -> list[dict[str, Any]]:
    priority = {"manual": 0, "mooncell": 1, "atlas": 2}
    merged: dict[str, dict[str, Any]] = {}
    for term in sorted(terms, key=lambda item: priority.get(item.get("source"), 9)):
        key = term["jp_name"]
        if key not in merged:
            merged[key] = term
            continue
        old_aliases = set(json.loads(merged[key].get("aliases") or "[]"))
        new_aliases = set(json.loads(term.get("aliases") or "[]"))
        merged[key]["aliases"] = json.dumps(sorted(old_aliases | new_aliases), ensure_ascii=False)
    return sorted(merged.values(), key=lambda item: (item["category"], item["jp_name"]))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mooncell-tsv", type=Path, default=DEFAULT_MOONCELL_TSV)
    parser.add_argument("--character-tsv", type=Path, default=DEFAULT_CHARACTER_TSV)
    parser.add_argument("--terms-tsv", type=Path, default=DEFAULT_TERMS_TSV)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--skip-atlas", action="store_true")
    args = parser.parse_args()

    terms: list[dict[str, Any]] = []
    ingest_common_terms(terms)
    ingest_local_tsvs(terms, args.character_tsv, args.terms_tsv, args.mooncell_tsv)

    if not args.skip_atlas:
        ingest_servants(terms)
        ingest_pair_export(terms, "nice_item", "item")
        ingest_pair_export(terms, "nice_equip", "craft_essence")

    unique_terms = dedupe_terms(terms)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(unique_terms, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(f"Wrote {len(unique_terms)} terms to {args.output}")


if __name__ == "__main__":
    main()
