"""Package a signed APK and latest manifest for CDN release.

Default input:
  app/release/app-release.apk
  app/release/output-metadata.json

Default output:
  release/cdn/app/android/releases/<versionName>/FgoGotran-<versionName>.apk
  release/cdn/app/android/releases/<versionName>/FgoGotran-<versionName>.apk.sha256
  release/cdn/app/android/latest/manifest.json
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any


SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parent
REPO_ROOT = ROOT.parent
DEFAULT_APK_DIR = REPO_ROOT / "app" / "release"
DEFAULT_GRADLE_FILE = REPO_ROOT / "app" / "build.gradle.kts"
DEFAULT_OUTPUT = REPO_ROOT / "release" / "cdn"
DEFAULT_BASE_URL = "https://cdn.fgogotran.com"
DEFAULT_APP_PREFIX = "app/android"
HK_TIMEZONE = timezone(timedelta(hours=8))

ANDROID_NAMES = {
    23: "Android 6.0",
    24: "Android 7.0",
    25: "Android 7.1",
    26: "Android 8.0",
    27: "Android 8.1",
    28: "Android 9",
    29: "Android 10",
    30: "Android 11",
    31: "Android 12",
    32: "Android 12L",
    33: "Android 13",
    34: "Android 14",
    35: "Android 15",
}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def cdn_url(base_url: str, relative_path: str) -> str:
    return f"{base_url.rstrip('/')}/{relative_path.replace(chr(92), '/')}"


def safe_path_segment(value: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9._-]+", "-", value.strip())
    return cleaned.strip("-") or "release"


def android_label(min_sdk: int | None) -> str:
    if min_sdk is None:
        return "Unknown"
    name = ANDROID_NAMES.get(min_sdk, "Android")
    return f"{name} (API {min_sdk})"


def read_output_metadata(apk_dir: Path) -> dict[str, Any]:
    metadata_path = apk_dir / "output-metadata.json"
    if not metadata_path.exists():
        raise FileNotFoundError(f"Missing Gradle output metadata: {metadata_path}")
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    elements = metadata.get("elements") or []
    if not elements:
        raise RuntimeError(f"No APK elements found in {metadata_path}")
    element = elements[0]
    output_file = element.get("outputFile")
    if not output_file:
        raise RuntimeError(f"APK outputFile missing in {metadata_path}")
    return {
        "versionName": str(element.get("versionName") or ""),
        "versionCode": int(element.get("versionCode") or 0),
        "apkPath": apk_dir / output_file,
    }


def read_gradle_min_sdk(gradle_file: Path = DEFAULT_GRADLE_FILE) -> int | None:
    if not gradle_file.exists():
        return None
    text = gradle_file.read_text(encoding="utf-8")
    match = re.search(r"\bminSdk\s*=\s*(\d+)", text)
    return int(match.group(1)) if match else None


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def package_apk_release(args: argparse.Namespace) -> dict[str, Any]:
    apk_dir = args.apk_dir.resolve()
    metadata = read_output_metadata(apk_dir)
    version_name = args.version_name or metadata["versionName"]
    version_code = args.version_code or metadata["versionCode"]
    if not version_name:
        raise RuntimeError("versionName is empty; pass --version-name")
    if version_code <= 0:
        raise RuntimeError("versionCode is empty; pass --version-code")

    source_apk = Path(args.apk or metadata["apkPath"]).resolve()
    if not source_apk.exists():
        raise FileNotFoundError(f"APK does not exist: {source_apk}")

    release_slug = safe_path_segment(args.release_slug or version_name)
    output_root = args.output.resolve()
    app_prefix = args.app_prefix.strip("/")
    release_prefix = f"{app_prefix}/releases/{release_slug}"
    latest_prefix = f"{app_prefix}/latest"
    release_dir = output_root / release_prefix
    latest_dir = output_root / latest_prefix
    release_dir.mkdir(parents=True, exist_ok=True)
    latest_dir.mkdir(parents=True, exist_ok=True)

    apk_name = args.apk_name or f"FgoGotran-{version_name}.apk"
    release_apk = release_dir / apk_name
    shutil.copy2(source_apk, release_apk)

    apk_hash = sha256_file(release_apk)
    apk_size = release_apk.stat().st_size
    sha_file = release_dir / f"{apk_name}.sha256"
    sha_file.write_text(f"{apk_hash}  {apk_name}\n", encoding="utf-8")

    min_sdk = args.min_sdk if args.min_sdk is not None else read_gradle_min_sdk()
    changelog = args.changelog or [f"FgoGotran {version_name} release"]
    release_date = datetime.now(HK_TIMEZONE).isoformat(timespec="seconds")
    manifest = {
        "manifestVersion": 1,
        "versionName": version_name,
        "versionCode": version_code,
        "releaseDate": release_date,
        "minimumAndroid": args.minimum_android or android_label(min_sdk),
        "apkUrl": cdn_url(args.base_url, f"{release_prefix}/{apk_name}"),
        "apkSha256": apk_hash,
        "apkSize": apk_size,
        "changelog": changelog,
    }
    write_json(latest_dir / "manifest.json", manifest)

    return {
        "releaseDir": str(release_dir),
        "manifest": str(latest_dir / "manifest.json"),
        "versionName": version_name,
        "versionCode": version_code,
        "apkSha256": apk_hash,
        "apkSize": apk_size,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Create CDN release files for the signed APK.")
    parser.add_argument("--apk-dir", type=Path, default=DEFAULT_APK_DIR)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--app-prefix", default=DEFAULT_APP_PREFIX)
    parser.add_argument("--release-slug")
    parser.add_argument("--apk-name")
    parser.add_argument("--version-name")
    parser.add_argument("--version-code", type=int)
    parser.add_argument("--min-sdk", type=int)
    parser.add_argument("--minimum-android")
    parser.add_argument("--changelog", action="append")
    args = parser.parse_args()

    result = package_apk_release(args)
    print("Packaged APK release")
    for key, value in result.items():
        print(f"  {key}: {value}")


if __name__ == "__main__":
    main()
