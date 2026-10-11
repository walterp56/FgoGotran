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
import os
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
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
ANALYTICS_MARKER = "com.fgogotran.AWS_USAGE_ANALYTICS_ENABLED"
ANDROID_XML = "{http://schemas.android.com/apk/res/android}"
ANALYTICS_RELEASE_ACTION = (
    "The owner must manually enable awsUsageAnalyticsEnabled in analytics-release.properties "
    "BEFORE building a new signed release APK. AI must not change that setting, patch the APK, "
    "or bypass this check."
)

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


def find_apkanalyzer(explicit: Path | None = None) -> Path:
    """Find installed SDK tooling without changing owner settings or installing anything."""
    if explicit is not None:
        tool = explicit.resolve()
        if tool.is_file():
            return tool
        raise FileNotFoundError(f"apkanalyzer does not exist: {tool}")
    executable = "apkanalyzer.bat" if os.name == "nt" else "apkanalyzer"
    on_path = shutil.which(executable)
    if on_path:
        return Path(on_path)
    sdk_roots = [Path(value) for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT")
                 if (value := os.environ.get(key))]
    sdk_roots.extend((Path.home() / "AppData/Local/Android/Sdk",
                      Path.home() / "Library/Android/sdk", Path.home() / "Android/Sdk"))
    for sdk in sdk_roots:
        candidates = [sdk / "cmdline-tools/latest/bin" / executable]
        candidates.extend(sorted(sdk.glob(f"cmdline-tools/*/bin/{executable}"), reverse=True))
        candidates.append(sdk / "tools/bin" / executable)
        for tool in candidates:
            if tool.is_file():
                return tool
    raise RuntimeError("Cannot verify APK analytics: apkanalyzer was not found. Install Android SDK "
                       "Command-line Tools or pass --apkanalyzer with its installed path. No release files written.")


def require_analytics_enabled_manifest(manifest_xml: str) -> None:
    """Read the compiled APK marker, never the current local build setting as proof."""
    try:
        manifest = ET.fromstring(manifest_xml)
    except ET.ParseError as error:
        raise RuntimeError("Cannot read the compiled APK manifest; release packaging stopped.") from error
    applications = manifest.findall("application")
    if manifest.tag != "manifest" or len(applications) != 1:
        raise RuntimeError("Compiled APK manifest has no unique application; release packaging stopped.")
    markers = [item for item in applications[0].findall("meta-data")
               if item.get(ANDROID_XML + "name") == ANALYTICS_MARKER]
    if len(markers) != 1 or markers[0].get(ANDROID_XML + "value") != "true" \
            or markers[0].get(ANDROID_XML + "resource") is not None:
        raise RuntimeError("APK AWS usage analytics is OFF or unverified; release packaging stopped. "
                           + ANALYTICS_RELEASE_ACTION)


def verify_apk_analytics(source_apk: Path, apkanalyzer: Path | None = None) -> None:
    # OWNER-CONTROLLED: no skip/override, APK patching, or owner-setting writes.
    tool = find_apkanalyzer(apkanalyzer)
    command = [str(tool), "manifest", "print", str(source_apk)]
    # Windows batch launchers may interpret shell metacharacters even with shell=False.
    if os.name == "nt" and tool.suffix.lower() in (".bat", ".cmd"):
        if any(re.search(r'[&|<>^%!"\r\n]', argument) for argument in command):
            raise RuntimeError("Cannot safely invoke apkanalyzer with these paths; release packaging stopped.")
    try:
        result = subprocess.run(command, capture_output=True, encoding="utf-8", errors="replace",
                                check=True, timeout=30)
    except (OSError, subprocess.SubprocessError) as error:
        raise RuntimeError("APK analytics verification failed. Check apkanalyzer and JAVA_HOME; "
                           "no release files written. " + ANALYTICS_RELEASE_ACTION) from error
    require_analytics_enabled_manifest(result.stdout)


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

    # Must pass before creating directories, replacing any APK, or writing a manifest.
    verify_apk_analytics(source_apk, getattr(args, "apkanalyzer", None))

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
    changelog_i18n = build_changelog_i18n(
        simplified=changelog,
        traditional=args.changelog_hant,
        english=args.changelog_en,
    )
    if changelog_i18n:
        manifest["changelogI18n"] = changelog_i18n
    write_json(latest_dir / "manifest.json", manifest)

    return {
        "releaseDir": str(release_dir),
        "manifest": str(latest_dir / "manifest.json"),
        "versionName": version_name,
        "versionCode": version_code,
        "apkSha256": apk_hash,
        "apkSize": apk_size,
    }


def build_changelog_i18n(
    simplified: list[str],
    traditional: list[str] | None,
    english: list[str] | None,
) -> dict[str, list[str]]:
    """Locale -> changelog map.

    The legacy ``changelog`` field always keeps the Simplified Chinese list so older app
    versions keep working; this map is only added when a translation is supplied.
    """
    if not traditional and not english:
        return {}
    localized: dict[str, list[str]] = {"zh-Hans": list(simplified)}
    if traditional:
        localized["zh-Hant"] = list(traditional)
    if english:
        localized["en"] = list(english)
    return localized


def main() -> None:
    parser = argparse.ArgumentParser(description="Create CDN release files for the signed APK.")
    parser.add_argument("--apk-dir", type=Path, default=DEFAULT_APK_DIR)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--apkanalyzer", type=Path, help="Installed Android SDK apkanalyzer path (verification cannot be skipped)")
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
    parser.add_argument("--changelog-hant", action="append")
    parser.add_argument("--changelog-en", action="append")
    args = parser.parse_args()

    result = package_apk_release(args)
    print("Packaged APK release")
    for key, value in result.items():
        print(f"  {key}: {value}")


if __name__ == "__main__":
    main()
