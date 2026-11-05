"""Merge both products into the existing Android manifest without lost updates.

Offline packaging preserves the other product. Publication always re-reads the
S3 origin and uses its ETag as a conditional write, retrying concurrent changes.
Never replace this with an unconditional `aws s3 cp` of a local manifest.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import re
import subprocess
import tempfile
from pathlib import Path
from urllib.parse import urlsplit
from local_distribution import validate_download_url

MANIFEST_KEY = "app/android/latest/manifest.json"
CACHE_CONTROL = "no-cache, no-store, must-revalidate, max-age=0, s-maxage=0"
ANDROID_FIELDS = {
    "manifestVersion", "versionCode", "versionName", "releaseDate", "minimumAndroid",
    "apkUrl", "apkSha256", "apkSize", "changelog", "changelogI18n",
}


def read_manifest(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8-sig"))
    if not isinstance(value, dict) or value.get("manifestVersion") != 1:
        raise ValueError("Unsupported shared release manifest")
    return value


def write_manifest(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".json.tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def validate_local_metadata(value: dict) -> None:
    """Accept the reader's small version feed; ZIP details are optional.

    Packaging still verifies its ZIP separately. If artifact information is
    supplied for publication, validate it rather than silently accepting it.
    """
    if not isinstance(value, dict):
        raise ValueError("Invalid Local release")
    if (type(value.get("versionCode")) is not int or value["versionCode"] < 1
        or not isinstance(value.get("versionName"), str)
        or not re.fullmatch(r"v\d+\.\d+\.\d+", value["versionName"])
        or value.get("platform", "windows-x64") != "windows-x64"):
        raise ValueError("Invalid Local release")
    if value.get("downloadMode", "manual") != "manual":
        raise ValueError("Local releases must use manual downloads")
    notes, localized = value.get("changelog", []), value.get("changelogI18n", {})
    if not isinstance(notes, list) or not isinstance(localized, dict) or len(localized) > 20:
        raise ValueError("Invalid release notes")
    for entries in [notes, *localized.values()]:
        if (not isinstance(entries, list) or len(entries) > 100
            or any(not isinstance(note, str) or len(note) > 4000 for note in entries)):
            raise ValueError("Invalid release notes")
    if "downloadUrl" in value:
        validate_download_url(value["downloadUrl"])
    if "packageSha256" in value and (
        not isinstance(value["packageSha256"], str)
        or not re.fullmatch(r"[a-f0-9]{64}", value["packageSha256"])
    ):
        raise ValueError("Invalid ZIP checksum")
    if "packageSize" in value and (
        type(value["packageSize"]) is not int or value["packageSize"] < 1
    ):
        raise ValueError("Invalid ZIP size")


def merge_manifest(current: dict, candidate: dict, product: str) -> dict:
    result = copy.deepcopy(current)
    if product == "android":
        incoming = {key: value for key, value in candidate.items() if key in ANDROID_FIELDS}
        previous = current
        digest_key = "apkSha256"
    elif product == "local":
        incoming = candidate.get("fgogotranLocal")
        previous = current.get("fgogotranLocal", {})
        digest_key = "packageSha256"
        if not current.get("apkUrl") or type(current.get("versionCode")) is not int:
            raise ValueError("Local publication requires the existing Android manifest")
        validate_local_metadata(incoming)
    else:
        raise ValueError("Unknown product")
    if not isinstance(incoming, dict) or type(incoming.get("versionCode")) is not int:
        raise ValueError("Missing release version code")
    if (incoming["versionCode"] < 1 or not incoming.get("versionName")
        or (product == "android" and not incoming.get(digest_key))):
        raise ValueError("Incomplete release metadata")
    previous_code = previous.get("versionCode", 0)
    if incoming["versionCode"] < previous_code:
        raise ValueError("Refusing to publish an older version")
    if incoming["versionCode"] == previous_code:
        digest_changed = (
            incoming.get(digest_key) != previous.get(digest_key) if product == "android"
            else bool(incoming.get(digest_key) and previous.get(digest_key)
                      and incoming[digest_key] != previous[digest_key])
        )
        if (incoming["versionName"] != previous.get("versionName")
            or digest_changed):
            raise ValueError("A published version code cannot identify different program files")
        if product == "local":
            # A notes-only refresh must not erase an already recorded ZIP identity.
            # A version-only first release can later be enriched by its first ZIP.
            if ("packageSize" in incoming and "packageSize" in previous
                and incoming["packageSize"] != previous["packageSize"]):
                raise ValueError("A published ZIP size cannot change at the same version")
            incoming = {**previous, **incoming}
    if product == "android":
        for key in ANDROID_FIELDS:
            result.pop(key, None)
        result.update(incoming)
    else:
        result["fgogotranLocal"] = copy.deepcopy(incoming)
    return result


def publish_manifest(path: Path, product: str, s3_uri: str, *, aws: str = "aws", runner=subprocess.run) -> dict:
    location = urlsplit(s3_uri)
    if location.scheme != "s3" or not location.netloc or location.query or location.fragment:
        raise ValueError("Expected s3://bucket[/prefix]")
    key = "/".join(part for part in (location.path.strip("/"), MANIFEST_KEY) if part)
    candidate = read_manifest(path)
    with tempfile.TemporaryDirectory(prefix="fgogotran-manifest-") as folder:
        origin = Path(folder) / "origin.json"
        output = Path(folder) / "merged.json"
        for _ in range(5):
            get = runner([aws, "s3api", "get-object", "--bucket", location.netloc, "--key", key,
                          str(origin), "--output", "json"], capture_output=True, text=True, timeout=60)
            etag = None
            if get.returncode == 0:
                metadata = json.loads(get.stdout)
                etag = metadata.get("ETag")
                if not isinstance(etag, str) or not etag:
                    raise RuntimeError("S3 origin did not return an ETag; publication stopped")
                current = read_manifest(origin)
            elif any(marker in get.stderr for marker in ("NoSuchKey", "(404)")):
                current = {}
            else:
                raise RuntimeError("Cannot read the S3 manifest; publication stopped")
            merged = merge_manifest(current, candidate, product)
            write_manifest(output, merged)
            condition = ["--if-match", etag] if etag else ["--if-none-match", "*"]
            put = runner([aws, "s3api", "put-object", "--bucket", location.netloc, "--key", key,
                          "--body", str(output), "--content-type", "application/json; charset=utf-8",
                          "--cache-control", CACHE_CONTROL, *condition, "--output", "json"],
                         capture_output=True, text=True, timeout=60)
            if put.returncode == 0:
                write_manifest(path, merged)
                return merged
            if not any(marker in put.stderr for marker in
                       ("PreconditionFailed", "ConditionalRequestConflict", "(412)", "(409)")):
                raise RuntimeError("Conditional manifest publication failed; no unconditional fallback is allowed")
        raise RuntimeError("Shared manifest kept changing; retry the release later")


def publish_artifact(path: Path, key: str, s3_uri: str, *, aws: str = "aws", runner=subprocess.run) -> None:
    """Versioned artifacts are create-only; retries may reuse identical bytes."""
    location = urlsplit(s3_uri)
    if location.scheme != "s3" or not location.netloc or location.query or location.fragment:
        raise ValueError("Expected s3://bucket[/prefix]")
    if key.startswith("/") or ".." in key.split("/"):
        raise ValueError("Unsafe artifact key")
    key = "/".join(part for part in (location.path.strip("/"), key) if part)
    content_type = {".apk": "application/vnd.android.package-archive", ".zip": "application/zip"}.get(path.suffix, "text/plain; charset=utf-8")
    put = runner([aws, "s3api", "put-object", "--bucket", location.netloc, "--key", key,
                  "--body", str(path), "--content-type", content_type, "--if-none-match", "*", "--cache-control",
                  "public, max-age=31536000, immutable", "--output", "json"],
                 capture_output=True, text=True, timeout=300)
    if put.returncode == 0:
        return
    if not any(marker in put.stderr for marker in ("PreconditionFailed", "(412)")):
        raise RuntimeError("Immutable artifact publication failed")
    with tempfile.TemporaryDirectory(prefix="fgogotran-artifact-") as folder:
        existing = Path(folder) / "existing"
        get = runner([aws, "s3api", "get-object", "--bucket", location.netloc, "--key", key,
                      str(existing), "--output", "json"], capture_output=True, text=True, timeout=300)
        def digest(file: Path) -> str:
            result = hashlib.sha256()
            with file.open("rb") as source:
                for chunk in iter(lambda: source.read(1024 * 1024), b""):
                    result.update(chunk)
            return result.hexdigest()
        if get.returncode != 0 or digest(existing) != digest(path):
            raise RuntimeError("A different artifact already exists at this versioned URL; use a new release version")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--product", choices=["android", "local"], required=True)
    parser.add_argument("--s3-uri", required=True)
    parser.add_argument("--aws-cli", default="aws")
    parser.add_argument("--artifact-file", type=Path, action="append", default=[])
    parser.add_argument("--artifact-key", action="append", default=[])
    args = parser.parse_args()
    if len(args.artifact_file) != len(args.artifact_key):
        parser.error("Each artifact file needs an artifact key")
    if args.product == "local" and args.artifact_file:
        parser.error("Local publishes version metadata only; upload ZIPs to OneDrive")
    for path, key in zip(args.artifact_file, args.artifact_key):
        publish_artifact(path, key, args.s3_uri, aws=args.aws_cli)
    publish_manifest(args.manifest, args.product, args.s3_uri, aws=args.aws_cli)
    print("Shared manifest published with an S3 conditional write.")


if __name__ == "__main__":
    main()
