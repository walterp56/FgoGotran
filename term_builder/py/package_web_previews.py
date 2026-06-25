"""Generate JSON preview files for the website glossary table.

release-preview.ps1 copies the generated files into:
  web/public/term-preview/zh-Hans/latest/character_names.preview.json
  web/public/term-preview/zh-Hans/latest/terms.preview.json

The website reads them as:
  /term-preview/zh-Hans/latest/character_names.preview.json
  /term-preview/zh-Hans/latest/terms.preview.json

These files are derived from the editable TSV sources. Raw TSV upload is not
required for the current website.
"""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path
from typing import Any


SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parent
REPO_ROOT = ROOT.parent
DEFAULT_CHARACTER_TSV = ROOT / "character_names.tsv"
DEFAULT_TERMS_TSV = ROOT / "term.tsv"
DEFAULT_OUTPUT = REPO_ROOT / "release" / "cdn"
DEFAULT_LOCALE = "zh-Hans"


def clean(value: str | None) -> str:
    return (value or "").strip()


def read_tsv(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle, delimiter="\t"))


def character_preview_rows(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for line_num, row in enumerate(read_tsv(path), start=2):
        jp_name = clean(row.get("jp_name"))
        cn_name = clean(row.get("cn_name"))
        if not jp_name and not cn_name:
            continue
        if not jp_name or not cn_name:
            raise SystemExit(f"{path}:{line_num} missing jp_name or cn_name")
        rows.append(
            {
                "jp_name": jp_name,
                "cn_name": cn_name,
                "category": "character",
                "aliases": clean(row.get("aliases")),
            }
        )
    return rows


def term_preview_rows(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for line_num, row in enumerate(read_tsv(path), start=2):
        jp_term = clean(row.get("jp_term"))
        cn_term = clean(row.get("cn_term"))
        category = clean(row.get("category"))
        if not jp_term and not cn_term and not category:
            continue
        if not jp_term or not cn_term or not category:
            raise SystemExit(f"{path}:{line_num} missing jp_term, cn_term, or category")
        rows.append(
            {
                "jp_term": jp_term,
                "cn_term": cn_term,
                "category": category,
                "aliases": clean(row.get("aliases")),
            }
        )
    return rows


def write_json(path: Path, data: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def package_previews(args: argparse.Namespace) -> dict[str, Any]:
    output_dir = args.output.resolve() / "preview" / args.locale / "latest"
    characters = character_preview_rows(args.character_tsv)
    terms = term_preview_rows(args.terms_tsv)

    character_path = output_dir / "character_names.preview.json"
    terms_path = output_dir / "terms.preview.json"
    write_json(character_path, characters)
    write_json(terms_path, terms)

    return {
        "characterPreview": str(character_path),
        "termsPreview": str(terms_path),
        "characterRows": len(characters),
        "termRows": len(terms),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Create CDN preview JSON files for the website.")
    parser.add_argument("--character-tsv", type=Path, default=DEFAULT_CHARACTER_TSV)
    parser.add_argument("--terms-tsv", type=Path, default=DEFAULT_TERMS_TSV)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--locale", default=DEFAULT_LOCALE)
    args = parser.parse_args()

    result = package_previews(args)
    print("Packaged web preview files")
    for key, value in result.items():
        print(f"  {key}: {value}")


if __name__ == "__main__":
    main()
