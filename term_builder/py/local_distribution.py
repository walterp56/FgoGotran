"""Build-time validation for portable ZIPs; never installed as an updater."""
from __future__ import annotations

import hashlib
import json
import re
import stat
import zipfile
from pathlib import Path, PurePosixPath
from urllib.parse import urlsplit

MANUAL_DOWNLOAD_URL = "https://1drv.ms/f/c/9e1fdb13e6d5d039/IgDO_uKE7yZWT6cPSy-v_LlnAW4zG9Wv51mwltpuVAVI9HI?e=uEzfCt"


def validate_download_url(value: str) -> None:
    if not isinstance(value, str) or any(character.isspace() for character in value):
        raise ValueError("Invalid OneDrive link")
    parsed = urlsplit(value)
    if (parsed.scheme != "https" or parsed.hostname not in
        {"1drv.ms", "onedrive.live.com", "onedrive.com", "www.onedrive.com"}
        or not (parsed.path.strip("/") or parsed.query) or parsed.username or parsed.password
        or parsed.port not in (None, 443) or parsed.fragment):
        raise ValueError("A public OneDrive sharing link is required")

INVENTORY = "installed-files.json"
VERSION_FILE = "src/fgogotran_local/version.json"
MAX_EXPANDED_BYTES = 200 * 1024 * 1024
MANAGED_DIRS = {"src", "scripts", "platforms", "public", "docs"}
MANAGED_FILES = {
    "Start-FgoGotranLocal.cmd", "pyproject.toml", "requirements.txt",
    "README.md", "SECURITY.md", "THIRD_PARTY.md",
    "config.example.json", "简体中文使用指南.md",
}


def file_hash(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def safe_relative(name: str) -> str:
    if not isinstance(name, str) or not name or "\\" in name or ":" in name:
        raise ValueError("Unsafe path")
    path = PurePosixPath(name)
    if path.is_absolute() or str(path) != name or any(part in {".", ".."} for part in path.parts):
        raise ValueError("Unsafe relative path")
    for part in path.parts:
        if (part.endswith((" ", ".")) or any(ord(char) < 32 for char in part)
            or any(char in part for char in '<>"|?*')
            or re.fullmatch(r"(?:con|prn|aux|nul|com[0-9]|lpt[0-9])(?:\..*)?", part, re.I)):
            raise ValueError("Unsafe Windows path")
    if not (name in MANAGED_FILES or name == INVENTORY or path.parts[0] in MANAGED_DIRS):
        raise ValueError("Not a managed program path")
    if any(part.casefold() in {"__pycache__", ".git", ".venv", "user_data", "models"} for part in path.parts):
        raise ValueError("Private file in package")
    if path.suffix.casefold() in {".gguf", ".pyc", ".log"}:
        raise ValueError("Runtime data in package")
    return name


def validate_inventory(document: dict) -> dict[str, str]:
    if not isinstance(document, dict) or document.get("schemaVersion") != 1:
        raise ValueError("Unsupported file inventory")
    version = document.get("version", {})
    if (type(version.get("versionCode")) is not int or version["versionCode"] < 1
        or not isinstance(version.get("versionName"), str)
        or not re.fullmatch(r"v\d+\.\d+\.\d+", version["versionName"])):
        raise ValueError("Invalid inventory version")
    files = document.get("files")
    if not isinstance(files, dict) or not files or len(files) > 5000:
        raise ValueError("Invalid file inventory")
    seen = set()
    for name, digest in files.items():
        safe_relative(name)
        if name == INVENTORY or name.casefold() in seen:
            raise ValueError("Duplicate or recursive inventory")
        seen.add(name.casefold())
        if not isinstance(digest, str) or not re.fullmatch(r"[a-f0-9]{64}", digest):
            raise ValueError("Invalid file digest")
    if not {VERSION_FILE, "Start-FgoGotranLocal.cmd", "requirements.txt", "pyproject.toml"} <= files.keys():
        raise ValueError("Incomplete program package")
    # Reject file/directory collisions, including Windows case folding.
    for name in files:
        if any(str(parent).casefold() in seen for parent in PurePosixPath(name).parents if str(parent) != "."):
            raise ValueError("File/directory collision")
    return files


def target_file(root: Path, name: str, data: Path | None = None) -> Path:
    safe_relative(name)
    target = root / name
    # Reject symlinks/junctions in every existing ancestor and the leaf itself.
    for item in (target, *target.parents):
        if item == root.parent:
            break
        if item.is_symlink() or (hasattr(item, "is_junction") and item.is_junction()):
            raise ValueError("Linked program path")
    resolved = target.resolve()
    if not resolved.is_relative_to(root.resolve()):
        raise ValueError("Program path escapes installation")
    if data and (resolved == data or resolved.is_relative_to(data) or data.is_relative_to(resolved)):
        raise ValueError("Program path overlaps user data")
    return target


def inspect_package(archive: Path, release: dict) -> dict:
    if archive.stat().st_size != release["packageSize"] or file_hash(archive) != release["packageSha256"]:
        raise ValueError("ZIP checksum mismatch")
    with zipfile.ZipFile(archive) as package:
        entries = package.infolist()
        if len(entries) > 5001 or sum(item.file_size for item in entries) > MAX_EXPANDED_BYTES:
            raise ValueError("ZIP too large")
        names = set()
        for item in entries:
            safe_relative(item.filename)
            if item.is_dir() or item.filename.casefold() in names or stat.S_ISLNK(item.external_attr >> 16):
                raise ValueError("Duplicate, directory or linked ZIP entry")
            names.add(item.filename.casefold())
        document = json.loads(package.read(INVENTORY))
        files = validate_inventory(document)
        if {item.filename for item in entries} != set(files) | {INVENTORY}:
            raise ValueError("ZIP entries do not match inventory")
        version = {key: release[key] for key in ("versionCode", "versionName")}
        if document["version"] != version or json.loads(package.read(VERSION_FILE)) != version:
            raise ValueError("ZIP version mismatch")
        platform = json.loads(package.read("platforms/windows-x64/platform.json"))
        if platform.get("id") != "windows-x64" or platform.get("architecture") != "x64":
            raise ValueError("ZIP platform mismatch")
        for name, digest in files.items():
            if hashlib.sha256(package.read(name)).hexdigest() != digest:
                raise ValueError("Program file checksum mismatch")
    return document

