"""
Build the Android bundled Room database from term_builder/fgo_terms.json.

Default output:
  app/src/main/assets/db/fgo_terms.db
"""

from __future__ import annotations

import argparse
import json
import shutil
import sqlite3
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parent
REPO_ROOT = ROOT.parent
DEFAULT_JSON = ROOT / "fgo_terms.json"
DEFAULT_DB = REPO_ROOT / "app" / "src" / "main" / "assets" / "db" / "fgo_terms.db"


def build_db(json_path: Path = DEFAULT_JSON, db_path: Path = DEFAULT_DB) -> None:
    terms = json.loads(json_path.read_text(encoding="utf-8"))

    db_path.parent.mkdir(parents=True, exist_ok=True)
    if db_path.exists():
        db_path.unlink()
    for sidecar in (db_path.with_suffix(db_path.suffix + "-wal"), db_path.with_suffix(db_path.suffix + "-shm")):
        if sidecar.exists():
            sidecar.unlink()

    conn = sqlite3.connect(db_path)
    conn.execute("PRAGMA journal_mode=DELETE")
    conn.execute("PRAGMA user_version=1")
    conn.execute(
        """
        CREATE TABLE character_names (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            jp_name TEXT NOT NULL,
            cn_name TEXT NOT NULL,
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
            category TEXT NOT NULL,
            aliases TEXT
        )
        """
    )
    conn.execute("CREATE UNIQUE INDEX index_terms_jp_term ON terms(jp_term)")

    for term in terms:
        insert_term(conn, term)

    conn.commit()
    character_count = conn.execute("SELECT COUNT(*) FROM character_names").fetchone()[0]
    term_count = conn.execute("SELECT COUNT(*) FROM terms").fetchone()[0]
    categories = conn.execute(
        "SELECT category, COUNT(*) FROM terms GROUP BY category ORDER BY COUNT(*) DESC"
    ).fetchall()
    conn.close()

    print(f"Built {db_path}")
    print(f"  character_names: {character_count}")
    print(f"  terms: {term_count}")
    for category, category_count in categories:
        print(f"  {category}: {category_count}")

    local_copy = ROOT / "fgo_terms.db"
    if local_copy.resolve() != db_path.resolve():
        shutil.copy2(db_path, local_copy)
        print(f"Copied local DB preview to {local_copy}")


def insert_term(conn: sqlite3.Connection, term: dict[str, Any]) -> None:
    jp_name = clean(term.get("jp_name"))
    cn_name = clean(term.get("cn_name"))
    category = clean(term.get("category")) or "term"
    aliases = clean(term.get("aliases")) or "[]"
    if not jp_name or not cn_name:
        return
    if category in {"character", "servant"}:
        conn.execute(
            """
            INSERT OR REPLACE INTO character_names (jp_name, cn_name, aliases)
            VALUES (?, ?, ?)
            """,
            (jp_name, cn_name, aliases),
        )
    else:
        conn.execute(
            """
            INSERT OR REPLACE INTO terms (jp_term, cn_term, category, aliases)
            VALUES (?, ?, ?, ?)
            """,
            (jp_name, cn_name, category, aliases),
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
