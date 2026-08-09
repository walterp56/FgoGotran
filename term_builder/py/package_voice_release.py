"""Package FgoGotran voice TSV data for CDN release.

This script creates a versioned ZIP package plus a latest manifest under
release/cdn/voice/zh. It does not tune or regenerate voice profiles; it only
packages the reviewed TSV sources.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import zipfile
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any


SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parent
REPO_ROOT = ROOT.parent
DEFAULT_PROFILES = ROOT / "voice_tune" / "character_voice_profiles_cn.tsv"
DEFAULT_NAME_MAP = ROOT / "jp_cn_name_map.tsv"
DEFAULT_OUTPUT = REPO_ROOT / "release" / "cdn"
DEFAULT_BASE_URL = "https://cdn.fgogotran.com"
DEFAULT_LOCALE = "zh"
DEFAULT_MIN_APP_VERSION = "2.0.0"
PACKAGE_NAME = "voice_data.zip"
PROFILE_NAME = "character_voice_profiles_cn.tsv"
NAME_MAP_NAME = "jp_cn_name_map.tsv"
PROFILE_HEADER = (
    "speaker_id",
    "aliases",
    "voice_type",
    "cn_voice_name",
    "cn_style",
    "cn_pitch",
    "cn_rate",
    "cn_volume",
)
NAME_MAP_HEADER = ("jp_name", "cn_name_simp", "cn_name_trad", "count")
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


def cdn_url(base_url: str, relative_path: str) -> str:
    return f"{base_url.rstrip('/')}/{relative_path.replace(chr(92), '/')}"


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def read_tsv(path: Path) -> tuple[list[str], list[dict[str, str]]]:
    with path.open("r", encoding="utf-8-sig", newline="") as file:
        reader = csv.DictReader(file, delimiter="\t")
        header = list(reader.fieldnames or [])
        rows = [dict(row) for row in reader]
    return header, rows


def validate_profiles(path: Path) -> int:
    if not path.exists():
        raise FileNotFoundError(f"Missing voice profile TSV: {path}")
    header, rows = read_tsv(path)
    if tuple(header) != PROFILE_HEADER:
        raise RuntimeError(f"Unexpected profile TSV header: {header}")
    if not rows:
        raise RuntimeError("Voice profile TSV has no rows")
    seen: set[str] = set()
    for line_no, row in enumerate(rows, start=2):
        speaker_id = (row.get("speaker_id") or "").strip()
        voice_name = (row.get("cn_voice_name") or "").strip()
        if not speaker_id:
            raise RuntimeError(f"Blank speaker_id at {path}:{line_no}")
        if not voice_name:
            raise RuntimeError(f"Blank cn_voice_name for {speaker_id} at {path}:{line_no}")
        if speaker_id in seen:
            raise RuntimeError(f"Duplicate speaker_id {speaker_id} at {path}:{line_no}")
        seen.add(speaker_id)
    return len(rows)


def validate_name_map(path: Path) -> int:
    if not path.exists():
        raise FileNotFoundError(f"Missing JP/CN name map TSV: {path}")
    header, rows = read_tsv(path)
    if tuple(header) != NAME_MAP_HEADER:
        raise RuntimeError(f"Unexpected name map TSV header: {header}")
    if not rows:
        raise RuntimeError("JP/CN name map TSV has no rows")
    seen: set[str] = set()
    for line_no, row in enumerate(rows, start=2):
        jp_name = (row.get("jp_name") or "").strip()
        cn_name_simp = row.get("cn_name_simp") or ""
        if not jp_name:
            raise RuntimeError(f"Blank jp_name at {path}:{line_no}")
        if jp_name in seen:
            raise RuntimeError(f"Duplicate jp_name {jp_name} at {path}:{line_no}")
        if contains_kana(cn_name_simp):
            raise RuntimeError(
                f"Japanese kana in cn_name_simp for {jp_name} at {path}:{line_no}"
            )
        seen.add(jp_name)
    return len(rows)


def contains_kana(value: str) -> bool:
    return any("\u3040" <= char <= "\u30ff" for char in value)


def create_zip(package_path: Path, profile_path: Path, name_map_path: Path) -> None:
    package_path.parent.mkdir(parents=True, exist_ok=True)
    if package_path.exists():
        package_path.unlink()
    with zipfile.ZipFile(package_path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.write(profile_path, PROFILE_NAME)
        archive.write(name_map_path, NAME_MAP_NAME)


def package_voice_release(args: argparse.Namespace) -> dict[str, Any]:
    profile_path = args.profiles.resolve()
    name_map_path = args.name_map.resolve()
    profile_count = validate_profiles(profile_path)
    name_map_count = validate_name_map(name_map_path)

    content_version = args.content_version or default_content_version()
    locale = args.locale
    output_root = args.output.resolve()
    release_prefix = f"voice/{locale}/releases/{content_version}"
    latest_prefix = f"voice/{locale}/latest"
    release_dir = output_root / release_prefix
    latest_dir = output_root / latest_prefix

    package_path = release_dir / PACKAGE_NAME
    create_zip(package_path, profile_path, name_map_path)

    package_hash = sha256_file(package_path)
    profile_hash = sha256_file(profile_path)
    name_map_hash = sha256_file(name_map_path)
    package_size = package_path.stat().st_size
    profile_size = profile_path.stat().st_size
    name_map_size = name_map_path.stat().st_size

    sha_file = release_dir / f"{PACKAGE_NAME}.sha256"
    sha_file.write_text(f"{package_hash}  {PACKAGE_NAME}\n", encoding="utf-8")

    generated_at = datetime.now(HK_TIMEZONE).isoformat(timespec="seconds")
    manifest = {
        "manifestVersion": 1,
        "contentVersion": content_version,
        "schemaVersion": 1,
        "locale": locale,
        "generatedAt": generated_at,
        "minimumAppVersion": args.minimum_app_version,
        "releaseNotes": args.release_notes,
        "packageUrl": cdn_url(args.base_url, f"{release_prefix}/{PACKAGE_NAME}"),
        "packageSha256": package_hash,
        "packageSize": package_size,
        "profileFile": PROFILE_NAME,
        "profileSha256": profile_hash,
        "profileSize": profile_size,
        "profileCount": profile_count,
        "nameMapFile": NAME_MAP_NAME,
        "nameMapSha256": name_map_hash,
        "nameMapSize": name_map_size,
        "nameMapCount": name_map_count,
    }
    write_json(latest_dir / "manifest.json", manifest)

    return {
        "releaseDir": str(release_dir),
        "manifest": str(latest_dir / "manifest.json"),
        "contentVersion": content_version,
        "packageSha256": package_hash,
        "packageSize": package_size,
        "profileCount": profile_count,
        "nameMapCount": name_map_count,
    }


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Create CDN release files for FgoGotran voice data."
    )
    parser.add_argument("--profiles", type=Path, default=DEFAULT_PROFILES)
    parser.add_argument("--name-map", type=Path, default=DEFAULT_NAME_MAP)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--locale", default=DEFAULT_LOCALE)
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--content-version")
    parser.add_argument("--minimum-app-version", default=DEFAULT_MIN_APP_VERSION)
    parser.add_argument("--release-notes", default="FgoGotran voice data update")
    args = parser.parse_args()

    result = package_voice_release(args)
    print("Packaged voice CDN release")
    for key, value in result.items():
        print(f"  {key}: {value}")


if __name__ == "__main__":
    main()
