"""
Build the terminology SQLite database from term_builder/fgo_terms.json.

Schema 3 adds English targets while keeping Japanese source matching unchanged.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import sqlite3
import unicodedata
from pathlib import Path
from typing import Any


SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parent
REPO_ROOT = ROOT.parent
DEFAULT_JSON = ROOT / "fgo_terms.json"
DEFAULT_DB = ROOT / "fgo_terms.db"

NAME_SEPARATOR_RE = re.compile(r"[\u30FB\uFF65\u00B7\uFF0F/\uFF06&\uFF1D=\s]+")
EN_COMPONENT_SEPARATOR_RE = re.compile(r"\s+")
COMPONENT_PAIR_SEPARATOR_RE = re.compile(r"[;；]")
MIN_COMPONENT_LENGTH = 2


def build_db(json_path: Path = DEFAULT_JSON, db_path: Path = DEFAULT_DB) -> None:
    terms = json.loads(json_path.read_text(encoding="utf-8"))

    db_path.parent.mkdir(parents=True, exist_ok=True)
    if db_path.exists():
        db_path.unlink()
    for sidecar in (
        db_path.with_suffix(db_path.suffix + "-wal"),
        db_path.with_suffix(db_path.suffix + "-shm"),
    ):
        if sidecar.exists():
            sidecar.unlink()

    conn = sqlite3.connect(db_path)
    conn.execute("PRAGMA journal_mode=DELETE")
    conn.execute("PRAGMA user_version=3")
    conn.execute(
        """
        CREATE TABLE character_names (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            jp_name TEXT NOT NULL,
            cn_name TEXT NOT NULL,
            en_name TEXT NOT NULL DEFAULT '',
            gender TEXT NOT NULL DEFAULT '',
            aliases TEXT
        )
        """
    )
    conn.execute("CREATE UNIQUE INDEX index_character_names_jp_name ON character_names(jp_name)")
    conn.execute(
        """
        CREATE TABLE terms (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            jp_term TEXT NOT NULL,
            cn_term TEXT NOT NULL,
            en_term TEXT NOT NULL DEFAULT '',
            category TEXT NOT NULL,
            gender TEXT NOT NULL DEFAULT '',
            aliases TEXT
        )
        """
    )
    conn.execute("CREATE UNIQUE INDEX index_terms_jp_term ON terms(jp_term)")

    generated_character_parts = 0
    generated_term_parts = 0
    for term in terms:
        character_parts, term_parts = insert_term(conn, term)
        generated_character_parts += character_parts
        generated_term_parts += term_parts

    conn.commit()
    character_count = conn.execute("SELECT COUNT(*) FROM character_names").fetchone()[0]
    term_count = conn.execute("SELECT COUNT(*) FROM terms").fetchone()[0]
    en_character_count = conn.execute(
        "SELECT COUNT(*) FROM character_names WHERE en_name != ''"
    ).fetchone()[0]
    en_term_count = conn.execute(
        "SELECT COUNT(*) FROM terms WHERE en_term != ''"
    ).fetchone()[0]
    categories = conn.execute(
        "SELECT category, COUNT(*) FROM terms GROUP BY category ORDER BY COUNT(*) DESC"
    ).fetchall()
    conn.close()

    print(f"Built {db_path}")
    print(f"  character_names: {character_count} (en: {en_character_count})")
    print(f"  generated_character_parts: {generated_character_parts}")
    print(f"  generated_term_parts: {generated_term_parts}")
    print(f"  terms: {term_count} (en: {en_term_count})")
    for category, category_count in categories:
        print(f"  {category}: {category_count}")

    local_copy = ROOT / "fgo_terms.db"
    if local_copy.resolve() != db_path.resolve():
        shutil.copy2(db_path, local_copy)
        print(f"Copied local DB preview to {local_copy}")


def insert_term(conn: sqlite3.Connection, term: dict[str, Any]) -> tuple[int, int]:
    jp_name = clean(term.get("jp_name"))
    cn_name = clean(term.get("cn_name"))
    en_name = clean(term.get("en_name"))
    en_components = clean(term.get("en_components"))
    category = clean(term.get("category")) or "term"
    gender = clean(term.get("gender"))
    aliases = clean(term.get("aliases")) or "[]"
    if not jp_name or not cn_name:
        return 0, 0

    if category in {"character", "servant"}:
        insert_character_name(conn, jp_name, cn_name, en_name, gender, aliases, replace=True)
        generated = 0
        for jp_part, cn_part, en_part in merged_component_targets(
            jp_name, cn_name, en_name, en_components
        ):
            generated += insert_character_name(
                conn, jp_part, cn_part, en_part, "", "[]", replace=False
            )
        return generated, 0

    insert_term_row(conn, jp_name, cn_name, en_name, category, gender, aliases, replace=True)
    generated = 0
    for jp_part, cn_part, en_part in merged_component_targets(
        jp_name, cn_name, en_name, en_components
    ):
        generated += insert_term_row(
            conn,
            jp_part,
            cn_part,
            en_part,
            f"{category}_part",
            "",
            "[]",
            replace=False,
        )
    return 0, generated


def insert_term_row(
    conn: sqlite3.Connection,
    jp_term: str,
    cn_term: str,
    en_term: str,
    category: str,
    gender: str,
    aliases: str,
    *,
    replace: bool,
) -> int:
    conflict = "REPLACE" if replace else "IGNORE"
    cursor = conn.execute(
        f"""
        INSERT OR {conflict} INTO terms
            (jp_term, cn_term, en_term, category, gender, aliases)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        (jp_term, cn_term, en_term, category, gender, aliases),
    )
    return max(cursor.rowcount, 0)


def insert_character_name(
    conn: sqlite3.Connection,
    jp_name: str,
    cn_name: str,
    en_name: str,
    gender: str,
    aliases: str,
    *,
    replace: bool,
) -> int:
    conflict = "REPLACE" if replace else "IGNORE"
    cursor = conn.execute(
        f"""
        INSERT OR {conflict} INTO character_names
            (jp_name, cn_name, en_name, gender, aliases)
        VALUES (?, ?, ?, ?, ?)
        """,
        (jp_name, cn_name, en_name, gender, aliases),
    )
    return max(cursor.rowcount, 0)


def merged_component_targets(
    jp_name: str,
    cn_name: str,
    en_name: str,
    raw_en_components: str,
) -> list[tuple[str, str, str]]:
    targets: dict[str, list[str]] = {}

    def add(jp_part: str, cn_part: str, en_part: str, explicit: bool = False) -> None:
        jp_part = clean(jp_part)
        cn_part = clean(cn_part)
        en_part = clean(en_part)
        jp_key = normalize_name_key(jp_part)
        minimum_length = 1 if explicit else MIN_COMPONENT_LENGTH
        if len(jp_key) < minimum_length:
            return
        if not explicit and not has_japanese(jp_part):
            return
        if not cn_part and not en_part:
            return
        current = targets.get(jp_key)
        if current is None:
            targets[jp_key] = [jp_part, cn_part, en_part]
            return
        if not current[1] and cn_part:
            current[1] = cn_part
        if not current[2] and en_part:
            current[2] = en_part

    for jp_part, cn_part in split_parallel_components(jp_name, cn_name):
        add(jp_part, cn_part, "")

    explicit_en_components = parse_en_components(jp_name, raw_en_components)
    if explicit_en_components:
        for jp_part, en_part in explicit_en_components:
            add(jp_part, "", en_part, explicit=True)
    else:
        for jp_part, en_part in parallel_en_components(jp_name, en_name):
            add(jp_part, "", en_part)

    return [
        (jp_part, cn_part, en_part)
        for jp_part, cn_part, en_part in targets.values()
        if jp_part and (cn_part or en_part)
    ]


def split_parallel_components(jp_name: str, cn_name: str) -> list[tuple[str, str]]:
    jp_parts = split_name_components(jp_name)
    cn_parts = split_name_components(cn_name)
    if len(jp_parts) < 2 or len(jp_parts) != len(cn_parts):
        return []

    components: list[tuple[str, str]] = []
    seen: set[str] = set()
    for jp_part, cn_part in zip(jp_parts, cn_parts):
        jp_key = normalize_name_key(jp_part)
        if len(jp_key) < MIN_COMPONENT_LENGTH or jp_key in seen:
            continue
        if not has_japanese(jp_part):
            continue
        cn_part = clean(cn_part)
        if not cn_part:
            continue
        seen.add(jp_key)
        components.append((jp_part, cn_part))
    return components


def parallel_en_components(jp_name: str, en_name: str) -> list[tuple[str, str]]:
    jp_parts = split_name_components(jp_name)
    en_parts = split_en_components(en_name)
    if len(jp_parts) < 2 or len(jp_parts) != len(en_parts):
        return []

    components: list[tuple[str, str]] = []
    seen: set[str] = set()
    for jp_part, en_part in zip(jp_parts, en_parts):
        jp_key = normalize_name_key(jp_part)
        if len(jp_key) < MIN_COMPONENT_LENGTH or jp_key in seen:
            continue
        if not has_japanese(jp_part):
            continue
        en_part = clean(en_part)
        if not en_part:
            continue
        seen.add(jp_key)
        components.append((jp_part, en_part))
    return components


def parse_en_components(jp_name: str, raw_value: str) -> list[tuple[str, str]]:
    raw_value = clean(raw_value)
    if not raw_value:
        return []

    normalized_jp = normalize_name_key(jp_name)
    components: list[tuple[str, str]] = []
    seen: set[str] = set()
    for piece in COMPONENT_PAIR_SEPARATOR_RE.split(raw_value):
        piece = piece.strip()
        if not piece:
            continue
        if "=" not in piece:
            raise SystemExit(f"Invalid en_components pair for {jp_name!r}: {piece!r}")
        jp_part, en_part = (part.strip() for part in piece.split("=", 1))
        if "=" in en_part:
            raise SystemExit(
                f"Invalid en_components separator for {jp_name!r} in {piece!r}: "
                "use ';' between pairs"
            )
        jp_key = normalize_name_key(jp_part)
        if len(jp_key) < 1 or jp_key not in normalized_jp:
            raise SystemExit(
                f"Invalid en_components key for {jp_name!r}: {jp_part!r}"
            )
        if not en_part:
            raise SystemExit(f"Empty en_components target for {jp_name!r}: {jp_part!r}")
        if jp_key in seen:
            raise SystemExit(f"Duplicate en_components key for {jp_name!r}: {jp_part!r}")
        seen.add(jp_key)
        components.append((jp_part, en_part))
    return components


def split_name_components(text: str) -> list[str]:
    normalized = unicodedata.normalize("NFKC", text)
    return [part.strip() for part in NAME_SEPARATOR_RE.split(normalized) if part.strip()]


def split_en_components(text: str) -> list[str]:
    normalized = unicodedata.normalize("NFKC", text)
    return [part.strip() for part in EN_COMPONENT_SEPARATOR_RE.split(normalized) if part.strip()]


def normalize_name_key(text: str) -> str:
    normalized = unicodedata.normalize("NFKC", text)
    return NAME_SEPARATOR_RE.sub("", normalized).strip()


def has_japanese(text: str) -> bool:
    return any(
        "\u3040" <= char <= "\u30ff"
        or "\uff66" <= char <= "\uff9d"
        or "\u3400" <= char <= "\u9fff"
        for char in text
    )


def clean(value: Any) -> str:
    if value is None:
        return ""
    return str(value).strip()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--json", type=Path, default=DEFAULT_JSON)
    parser.add_argument("--db", type=Path, default=DEFAULT_DB)
    args = parser.parse_args()
    build_db(args.json, args.db)


if __name__ == "__main__":
    main()
