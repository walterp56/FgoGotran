"""Build a local ZIP for manual OneDrive upload and shared version metadata.

The ZIP and checksum are prepared alongside the Android APK. The explicit
--publish-manifest-s3 option publishes only the shared manifest, never the ZIP
or its checksum.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import tempfile
import urllib.request
import zipfile
from pathlib import Path
from urllib.parse import urlsplit

import local_distribution as helper
from shared_release_manifest import MANIFEST_KEY, merge_manifest, publish_manifest, read_manifest, write_manifest

ROOT = Path(__file__).resolve().parents[2]
LOCAL_ROOT = ROOT / "FgoGotranLocal"
DEFAULT_OUTPUT = ROOT / "release/cdn"
DEFAULT_BASE_URL = "https://cdn.fgogotran.com"


def package_release(root: Path, output: Path, shared: dict, *, download_url: str = helper.MANUAL_DOWNLOAD_URL,
                    notes: list[str] | None = None,
                    notes_hant: list[str] | None = None, notes_en: list[str] | None = None) -> dict:
    helper.validate_download_url(download_url)
    android_version = shared.get("versionName")
    if not isinstance(android_version, str) or not re.fullmatch(
        r"[0-9][0-9A-Za-z._+-]*", android_version
    ) or android_version.endswith("."):
        raise ValueError("A valid Android version name is required for the shared release directory")
    release_prefix = f"app/android/releases/{android_version}"
    apk_url = shared.get("apkUrl")
    if not isinstance(apk_url, str):
        raise ValueError("The shared manifest must identify the Android APK")
    apk_dir, _, apk_name = urlsplit(apk_url).path.rpartition("/")
    if not apk_dir.endswith(f"/{release_prefix}") or not re.fullmatch(r"[0-9A-Za-z][0-9A-Za-z._+-]*\.apk", apk_name):
        raise ValueError("The Android APK URL must use the Android version directory")
    manifest = output / MANIFEST_KEY
    if manifest.exists() and read_manifest(manifest) != shared:
        raise ValueError("The output shared manifest differs from the supplied base; re-read it before packaging")

    folder = output / release_prefix
    apk = folder / apk_name
    if not apk.is_file():
        raise FileNotFoundError(f"Prepare the Android APK in the shared release directory first: {apk}")
    expected_hash, expected_size = shared.get("apkSha256"), shared.get("apkSize")
    if (not isinstance(expected_hash, str) or not re.fullmatch(r"[a-f0-9]{64}", expected_hash)
        or type(expected_size) is not int or expected_size <= 0):
        raise ValueError("The shared manifest must include the Android APK SHA-256 and byte size")
    if apk.stat().st_size != expected_size or helper.file_hash(apk) != expected_hash:
        raise ValueError("The Android APK in the shared release directory does not match the manifest")

    version = json.loads((root / helper.VERSION_FILE).read_text(encoding="utf-8"))
    files = {}
    candidates = [root / name for name in helper.MANAGED_FILES]
    for directory in helper.MANAGED_DIRS:
        candidates.extend((root / directory).rglob("*"))
    for path in sorted(candidates):
        if not path.is_file() or path.is_symlink():
            continue
        name = path.relative_to(root).as_posix()
        try:
            helper.safe_relative(name)
        except ValueError:
            continue
        if name == helper.INVENTORY:
            continue
        helper.target_file(root, name)
        if name not in helper.MANAGED_FILES and path.suffix.lower() not in {".py", ".json", ".ps1", ".md", ".png", ".ico", ".svg", ".css"}:
            continue
        files[name] = path.read_bytes()
    inventory = {"schemaVersion": 1, "version": version,
                 "files": {name: hashlib.sha256(data).hexdigest() for name, data in files.items()}}
    helper.validate_inventory(inventory)
    files[helper.INVENTORY] = (json.dumps(inventory, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    archive = folder / f"FgoGotranLocal-{version['versionName']}-windows-x64.zip"
    with tempfile.NamedTemporaryFile(dir=folder, suffix=".zip.tmp", delete=False) as handle:
        temporary = Path(handle.name)
    try:
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED) as package:
            for name, data in sorted(files.items()):
                entry = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                entry.external_attr = 0o100644 << 16
                package.writestr(entry, data)
        digest = helper.file_hash(temporary)
        if archive.exists() and helper.file_hash(archive) != digest:
            raise ValueError("Versioned ZIP already exists with different contents; use a new Local version")
        release = {**version, "platform": "windows-x64",
                   "downloadMode": "manual", "downloadUrl": download_url,
                   "packageSha256": digest, "packageSize": temporary.stat().st_size,
                   "changelog": notes or [f"FgoGotranLocal {version['versionName']}"]}
        localized = {locale: value for locale, value in
                     (("zh-Hans", notes), ("zh-Hant", notes_hant), ("en", notes_en)) if value}
        if localized:
            release["changelogI18n"] = localized
        helper.inspect_package(temporary, release)
        merged = merge_manifest(shared, {"fgogotranLocal": release}, "local")
        temporary.replace(archive)
        archive.with_suffix(".zip.sha256").write_text(f"{digest}  {archive.name}\n", encoding="utf-8")
        write_manifest(manifest, merged)
    finally:
        temporary.unlink(missing_ok=True)
    return {"archive": str(archive), "manifest": str(manifest), "release": release}


def publish_release_metadata(result: dict, s3_uri: str, *, aws: str = "aws") -> None:
    # The owner uploads ZIP/checksum to OneDrive separately. No artifact upload.
    publish_manifest(Path(result["manifest"]), "local", s3_uri, aws=aws)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=LOCAL_ROOT)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--shared-manifest", type=Path)
    parser.add_argument("--download-url", default=helper.MANUAL_DOWNLOAD_URL)
    parser.add_argument("--changelog", action="append")
    parser.add_argument("--changelog-hant", action="append")
    parser.add_argument("--changelog-en", action="append")
    parser.add_argument("--publish-manifest-s3", default="")
    parser.add_argument("--aws-cli", default="aws")
    args = parser.parse_args()
    manifest = args.shared_manifest or args.output / MANIFEST_KEY
    if manifest.is_file():
        shared = read_manifest(manifest)
    else:
        # Read only. Never synthesize Android metadata from a Local version.
        with urllib.request.urlopen(f"{args.base_url.rstrip('/')}/{MANIFEST_KEY}", timeout=15) as response:
            shared = json.load(response)
    result = package_release(args.root, args.output, shared,
                             download_url=args.download_url, notes=args.changelog,
                             notes_hant=args.changelog_hant, notes_en=args.changelog_en)
    if args.publish_manifest_s3:
        publish_release_metadata(result, args.publish_manifest_s3, aws=args.aws_cli)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
