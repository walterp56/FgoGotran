"""
Build fgo_terms.db from fgo_terms.json.

Creates a Room-compatible SQLite database with FTS5 full-text search.
"""
import json
import sqlite3
import sys


def build_db(json_path: str = "fgo_terms.json", db_path: str = "fgo_terms.db"):
    with open(json_path, "r", encoding="utf-8") as f:
        terms = json.load(f)

    conn = sqlite3.connect(db_path)
    conn.execute("PRAGMA journal_mode=WAL")

    # Main terms table (Room-compatible schema)
    conn.execute("""
        CREATE TABLE IF NOT EXISTS terms (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            jp_name TEXT NOT NULL UNIQUE,
            cn_name TEXT NOT NULL,
            category TEXT NOT NULL,
            aliases TEXT
        )
    """)

    # FTS5 virtual table for fuzzy matching
    conn.execute("""
        CREATE VIRTUAL TABLE IF NOT EXISTS terms_fts USING fts5(
            jp_name,
            cn_name,
            aliases,
            category,
            content=terms,
            content_rowid=id
        )
    """)

    # Insert data
    for t in terms:
        try:
            conn.execute(
                "INSERT OR IGNORE INTO terms (jp_name, cn_name, category, aliases) VALUES (?, ?, ?, ?)",
                (t["jp_name"], t["cn_name"], t["category"], t.get("aliases", "[]"))
            )
        except Exception as e:
            print(f"  skip: {t.get('jp_name', '?')} - {e}", file=sys.stderr)

    conn.commit()

    # Populate FTS index
    conn.execute("INSERT INTO terms_fts(terms_fts) VALUES('rebuild')")
    conn.commit()

    count = conn.execute("SELECT COUNT(*) FROM terms").fetchone()[0]
    print(f"Built {db_path} with {count} terms")

    # Print category breakdown
    cats = conn.execute(
        "SELECT category, COUNT(*) FROM terms GROUP BY category ORDER BY COUNT(*) DESC"
    ).fetchall()
    for cat, cnt in cats:
        print(f"  {cat}: {cnt}")

    conn.close()


if __name__ == "__main__":
    build_db()
