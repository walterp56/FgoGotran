"""Package the built FGO terminology DB for CDN release.

This script does not build the SQLite DB. Run build_db.py first, then run this
script to create the files that should be uploaded under cdn.fgogotran.com.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any


SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parent
REPO_ROOT = ROOT.parent
DEFAULT_DB = REPO_ROOT / "app" / "src" / "main" / "assets" / "db" / "fgo_terms.db"
DEFAULT_ASSET_MANIFEST = REPO_ROOT / "app" / "src" / "main" / "assets" / "db" / "manifest.json"
DEFAULT_OUTPUT = REPO_ROOT / "release" / "cdn"
DEFAULT_BASE_URL = "https://cdn.fgogotran.com"
DEFAULT_LOCALE = "zh-Hans"
DEFAULT_MIN_APP_VERSION = "1.0.0"
HK_TIMEZONE = timezone(timedelta(hours=8))


def default_content_version(now: datetime | None = None) -> str:
    current = now or datetime.now(HK_TIMEZONE)
    return current.strftime("%Y.%m.%d.1")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_db_stats(db_path: Path) -> dict[str, Any]:
    conn = sqlite3.connect(db_path)
    try:
        user_version = conn.execute("PRAGMA user_version").fetchone()[0]
        tables = {
            row[0]
            for row in conn.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            ).fetchall()
        }
        required_tables = {"character_names", "terms"}
        missing_tables = sorted(required_tables - tables)
        if missing_tables:
            raise RuntimeError(f"DB is missing required tables: {', '.join(missing_tables)}")

        character_names = conn.execute(
            "SELECT COUNT(*) FROM character_names"
        ).fetchone()[0]
        terms = conn.execute("SELECT COUNT(*) FROM terms").fetchone()[0]
        categories = [
            {"category": category, "count": count}
            for category, count in conn.execute(
                "SELECT category, COUNT(*) FROM terms GROUP BY category ORDER BY COUNT(*) DESC"
            ).fetchall()
        ]
        return {
            "schemaVersion": int(user_version),
            "characterNameCount": int(character_names),
            "termCount": int(terms),
            "totalCount": int(character_names + terms),
            "categories": categories,
        }
    finally:
        conn.close()


def cdn_url(base_url: str, relative_path: str) -> str:
    return f"{base_url.rstrip('/')}/{relative_path.replace(chr(92), '/')}"


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def package_release(args: argparse.Namespace) -> dict[str, Any]:
    db_path = args.db.resolve()
    if not db_path.exists():
        raise FileNotFoundError(f"DB does not exist: {db_path}")

    content_version = args.content_version or default_content_version()
    locale = args.locale
    output_root = args.output.resolve()
    release_prefix = f"db/{locale}/releases/{content_version}"
    latest_prefix = f"db/{locale}/latest"
    release_dir = output_root / release_prefix
    latest_dir = output_root / latest_prefix

    release_dir.mkdir(parents=True, exist_ok=True)
    latest_dir.mkdir(parents=True, exist_ok=True)

    release_db = release_dir / "fgo_terms.db"
    shutil.copy2(db_path, release_db)

    db_hash = sha256_file(release_db)
    db_size = release_db.stat().st_size
    stats = read_db_stats(release_db)

    sha_file = release_dir / "fgo_terms.db.sha256"
    sha_file.write_text(f"{db_hash}  fgo_terms.db\n", encoding="utf-8")

    generated_at = datetime.now(HK_TIMEZONE).isoformat(timespec="seconds")
    manifest = {
        "manifestVersion": 1,
        "contentVersion": content_version,
        "schemaVersion": stats["schemaVersion"],
        "locale": locale,
        "generatedAt": generated_at,
        "minimumAppVersion": args.minimum_app_version,
        "releaseNotes": args.release_notes,
        "dbUrl": cdn_url(args.base_url, f"{release_prefix}/fgo_terms.db"),
        "dbSha256": db_hash,
        "dbSize": db_size,
        "characterNameCount": stats["characterNameCount"],
        "termCount": stats["termCount"],
        "totalCount": stats["totalCount"],
    }
    write_json(latest_dir / "manifest.json", manifest)
    write_json(args.asset_manifest, manifest)

    return {
        "releaseDir": str(release_dir),
        "manifest": str(latest_dir / "manifest.json"),
        "assetManifest": str(args.asset_manifest),
        "contentVersion": content_version,
        "dbSha256": db_hash,
        "dbSize": db_size,
        "characterNameCount": stats["characterNameCount"],
        "termCount": stats["termCount"],
    }


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Create CDN release files for FgoGotran terminology DB."
    )
    parser.add_argument("--db", type=Path, default=DEFAULT_DB)
    parser.add_argument("--asset-manifest", type=Path, default=DEFAULT_ASSET_MANIFEST)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--locale", default=DEFAULT_LOCALE)
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--content-version")
    parser.add_argument("--minimum-app-version", default=DEFAULT_MIN_APP_VERSION)
    parser.add_argument("--release-notes", default="FgoGotran terminology database update")
    args = parser.parse_args()

    result = package_release(args)
    print("Packaged CDN release")
    for key, value in result.items():
        print(f"  {key}: {value}")


if __name__ == "__main__":
    main()
