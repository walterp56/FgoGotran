"""Generate JSON preview files for the website glossary table.

The preview lists the *curated* entries (the editable TSVs) with the values the
app actually ships, so it matches the runtime database without the component
rows that build_db.py generates by splitting names.

Sources:
  term_builder/fgo_terms.db          values (schema 3, includes en_name/en_term)
  term_builder/character_names.tsv   curated character row set + order
  term_builder/term.tsv              curated term row set + order

Outputs:
  release/cdn/preview/<locale>/latest/character_names.preview.json
  release/cdn/preview/<locale>/latest/terms.preview.json

release-preview.ps1 copies those into:
  web/public/term-preview/<locale>/latest/character_names.preview.json
  web/public/term-preview/<locale>/latest/terms.preview.json

Build the database first:
  python term_builder/py/ingest_atlas.py      (TSVs -> fgo_terms.json)
  python term_builder/py/build_db.py          (fgo_terms.json -> fgo_terms.db)
"""

from __future__ import annotations

import argparse
import csv
import json
import sqlite3
import sys
from pathlib import Path
from typing import Any


SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parent
REPO_ROOT = ROOT.parent
DEFAULT_DB = ROOT / "fgo_terms.db"
DEFAULT_CHARACTER_TSV = ROOT / "character_names.tsv"
DEFAULT_TERMS_TSV = ROOT / "term.tsv"
DEFAULT_OUTPUT = REPO_ROOT / "release" / "cdn"
DEFAULT_LOCALE = "zh-Hans"

CHARACTER_QUERY = """
    SELECT jp_name, cn_name, en_name, aliases
    FROM character_names
    ORDER BY id
"""

TERM_QUERY = """
    SELECT jp_term, cn_term, en_term, category, aliases
    FROM terms
    ORDER BY id
"""


def clean(value: Any) -> str:
    if value is None:
        return ""
    return str(value).strip()


def display_aliases(value: Any) -> str:
    """The database stores aliases as a JSON array; the preview lists them inline."""
    raw = clean(value)
    if not raw or raw == "[]":
        return ""
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError:
        return raw
    if isinstance(parsed, list):
        return ", ".join(clean(item) for item in parsed if clean(item))
    return raw


def read_curated_keys(path: Path, key: str) -> list[str]:
    if not path.exists():
        raise SystemExit(f"Missing curated source: {path}")

    keys: list[str] = []
    seen: set[str] = set()
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle, delimiter="\t"):
            value = clean(row.get(key))
            if not value or value in seen:
                continue
            seen.add(value)
            keys.append(value)
    return keys


def assert_schema(conn: sqlite3.Connection) -> None:
    def columns(table: str) -> set[str]:
        return {row["name"] for row in conn.execute(f"PRAGMA table_info({table})")}

    missing = [name for name in ("en_name",) if name not in columns("character_names")]
    missing += [name for name in ("en_term",) if name not in columns("terms")]
    if missing:
        raise SystemExit(
            "fgo_terms.db is missing schema-3 columns: "
            + ", ".join(missing)
            + "\nRebuild it with: python term_builder/py/build_db.py"
        )


def console_safe(text: str) -> str:
    """Windows consoles are often not UTF-8; fall back to escapes instead of failing."""
    try:
        text.encode(sys.stderr.encoding or "utf-8")
    except (LookupError, UnicodeEncodeError):
        return text.encode("unicode_escape").decode("ascii")
    return text


def warn_missing(source: Path, keys: list[str]) -> None:
    if not keys:
        return
    preview = ", ".join(keys[:10])
    suffix = "" if len(keys) <= 10 else f" (+{len(keys) - 10} more)"
    print(
        console_safe(
            f"WARNING: {len(keys)} curated row(s) in {source.name} are missing from "
            f"fgo_terms.db: {preview}{suffix}"
        ),
        file=sys.stderr,
    )
    print(
        "         Rebuild with term_builder/py/ingest_atlas.py + build_db.py.",
        file=sys.stderr,
    )


def character_preview_rows(conn: sqlite3.Connection, keys: list[str]) -> list[dict[str, Any]]:
    rows = {row["jp_name"]: row for row in conn.execute(CHARACTER_QUERY)}
    missing: list[str] = []
    preview: list[dict[str, Any]] = []

    for jp_name in keys:
        row = rows.get(jp_name)
        if row is None:
            missing.append(jp_name)
            continue
        cn_name = clean(row["cn_name"])
        en_name = clean(row["en_name"])
        if not cn_name and not en_name:
            continue
        preview.append(
            {
                "jp_name": jp_name,
                "cn_name": cn_name,
                "en_name": en_name,
                "category": "character",
                "aliases": display_aliases(row["aliases"]),
            }
        )

    warn_missing(Path("character_names.tsv"), missing)
    return preview


def term_preview_rows(conn: sqlite3.Connection, keys: list[str]) -> list[dict[str, Any]]:
    rows = {row["jp_term"]: row for row in conn.execute(TERM_QUERY)}
    missing: list[str] = []
    preview: list[dict[str, Any]] = []

    for jp_term in keys:
        row = rows.get(jp_term)
        if row is None:
            missing.append(jp_term)
            continue
        cn_term = clean(row["cn_term"])
        en_term = clean(row["en_term"])
        category = clean(row["category"])
        if not cn_term and not en_term:
            continue
        if not category:
            continue
        preview.append(
            {
                "jp_term": jp_term,
                "cn_term": cn_term,
                "en_term": en_term,
                "category": category,
                "aliases": display_aliases(row["aliases"]),
            }
        )

    warn_missing(Path("term.tsv"), missing)
    return preview


def write_json(path: Path, data: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def package_previews(args: argparse.Namespace) -> dict[str, Any]:
    db_path = args.db.resolve()
    if not db_path.exists():
        raise SystemExit(
            f"Missing database: {db_path}\nBuild it first: python term_builder/py/build_db.py"
        )

    character_keys = read_curated_keys(args.character_tsv, "jp_name")
    term_keys = read_curated_keys(args.terms_tsv, "jp_term")

    output_dir = args.output.resolve() / "preview" / args.locale / "latest"
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    try:
        assert_schema(conn)
        characters = character_preview_rows(conn, character_keys)
        terms = term_preview_rows(conn, term_keys)
        database_rows = conn.execute("SELECT COUNT(*) FROM character_names").fetchone()[0]
        database_rows += conn.execute("SELECT COUNT(*) FROM terms").fetchone()[0]
        schema_version = conn.execute("PRAGMA user_version").fetchone()[0]
    finally:
        conn.close()

    character_path = output_dir / "character_names.preview.json"
    terms_path = output_dir / "terms.preview.json"
    write_json(character_path, characters)
    write_json(terms_path, terms)

    return {
        "database": str(db_path),
        "schemaVersion": schema_version,
        "characterPreview": str(character_path),
        "termsPreview": str(terms_path),
        "characterRows": len(characters),
        "termRows": len(terms),
        "previewRows": len(characters) + len(terms),
        "databaseRows": database_rows,
        "generatedRowsSkipped": database_rows - len(characters) - len(terms),
    }


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Create CDN preview JSON files for the website from fgo_terms.db."
    )
    parser.add_argument("--db", type=Path, default=DEFAULT_DB)
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