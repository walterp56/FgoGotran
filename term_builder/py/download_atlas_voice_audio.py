"""
Download official FGO character voice MP3s listed by Atlas Academy.

The input is a character TSV with at least jp_name/cn_name/aliases columns.
Matched servant lore is read from Atlas Academy's nice_servant_lore export, then
each voiceLine audioAssets URL is downloaded into a local, ignored folder.
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
import time
import unicodedata
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field, replace
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen


SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parent
REPO_ROOT = ROOT.parent
DEFAULT_CHARACTERS = ROOT / "character_names.tsv"
DEFAULT_ATLAS_LORE = ROOT / "atlas_nice_servant_lore.json"
DEFAULT_OVERRIDES = ROOT / "character_audio_overrides.tsv"
DEFAULT_OUTPUT_DIR = ROOT / "atlas_voice_audio"

ATLAS_LORE_SERVANT_URL = "https://api.atlasacademy.io/export/JP/nice_servant_lore.json"
ATLAS_HEADERS = {"Accept": "application/json", "User-Agent": "fgoGotran-atlas-voice-downloader/1.0"}
STATIC_ATLAS_HOST = "static.atlasacademy.io"

ALIAS_SPLIT_RE = re.compile(r"[|,，、]+")
MIDDLE_DOT_RE = re.compile(r"[\u30FB\uFF65\u00B7\u2022\u2219]")
NAME_PART_SPLIT_RE = re.compile(r"[\u30FB\uFF65\u00B7\u2022\u2219/\uFF0F&\uFF06=\uFF1D\s\[\]()（）【】〔〕]+")
SPACE_RE = re.compile(r"\s+")
INVALID_PATH_COMPONENT_RE = re.compile(r'[<>:"/\\|?*\x00-\x1f]+')
MIN_TOKEN_MATCH_LENGTH = 2
MIN_SINGLE_TOKEN_MATCH_LENGTH = 5
MAX_FOLDER_COMPONENT_LENGTH = 120
MAX_FILE_STEM_LENGTH = 160


@dataclass(frozen=True)
class CharacterRow:
    line_num: int
    jp_name: str
    cn_name: str
    aliases: tuple[str, ...]


@dataclass(frozen=True)
class CharacterServantMatch:
    character: CharacterRow
    servant: dict[str, Any]
    match_kind: str
    matched_name: str


@dataclass(frozen=True)
class AudioManifestRow:
    jp_name: str
    cn_name: str
    aliases: str
    character_folder: str
    match_kind: str
    matched_name: str
    svt_id: str
    collection_no: str
    servant_name: str
    voice_type: str
    line_name: str
    voice_id: str
    audio_url: str
    local_path: str


@dataclass
class DownloadStats:
    downloaded: int = 0
    skipped: int = 0
    failed: int = 0
    bytes_written: int = 0
    failed_rows: list[tuple[str, Path, str]] = field(default_factory=list)


def read_characters(path: Path) -> list[CharacterRow]:
    rows: list[CharacterRow] = []
    with path.open("r", encoding="utf-8-sig", newline="") as file:
        reader = csv.DictReader(file, delimiter="\t")
        required = {"jp_name", "cn_name"}
        if not required.issubset(reader.fieldnames or []):
            raise SystemExit(f"{path} must contain at least columns: jp_name, cn_name")
        for row in reader:
            jp_name = clean(row.get("jp_name"))
            cn_name = clean(row.get("cn_name"))
            if not jp_name and not cn_name:
                continue
            if not jp_name or not cn_name:
                raise SystemExit(f"{path}:{reader.line_num} must include jp_name and cn_name")
            rows.append(
                CharacterRow(
                    line_num=reader.line_num,
                    jp_name=jp_name,
                    cn_name=cn_name,
                    aliases=split_aliases(row.get("aliases")),
                )
            )
    return rows


def disambiguate_local_paths(
    rows: list[AudioManifestRow],
    output_dir: Path,
    folder_style: str,
) -> list[AudioManifestRow]:
    if folder_style != "character":
        return rows

    urls_by_path: dict[str, set[str]] = {}
    for row in rows:
        urls_by_path.setdefault(row.local_path, set()).add(row.audio_url)

    colliding_paths = {
        local_path
        for local_path, urls in urls_by_path.items()
        if len(urls) > 1
    }
    if not colliding_paths:
        return rows

    return [
        replace(row, local_path=disambiguated_character_audio_path(output_dir, row).relative_to(output_dir).as_posix())
        if row.local_path in colliding_paths
        else row
        for row in rows
    ]


def disambiguated_character_audio_path(output_dir: Path, row: AudioManifestRow) -> Path:
    extension = Path(urlparse(row.audio_url).path).suffix or ".mp3"
    file_stem = safe_path_component(
        "__".join(
            part
            for part in (
                source_audio_folder(row.audio_url),
                row.voice_id,
                row.line_name,
            )
            if part
        ),
        max_length=MAX_FILE_STEM_LENGTH,
    )
    return (
        output_dir
        / "audio"
        / row.character_folder
        / safe_path_component(row.voice_type or "voice", max_length=MAX_FOLDER_COMPONENT_LENGTH)
        / f"{file_stem}{extension}"
    )


def read_servants(path: Path) -> list[dict[str, Any]]:
    with path.open("r", encoding="utf-8") as file:
        data = json.load(file)
    if not isinstance(data, list):
        raise SystemExit(f"{path} must contain an Atlas servant list")
    return [row for row in data if isinstance(row, dict)]


def read_overrides(path: Path) -> dict[str, tuple[int, ...]]:
    if not path.exists():
        return {}
    overrides: dict[str, tuple[int, ...]] = {}
    with path.open("r", encoding="utf-8-sig", newline="") as file:
        reader = csv.DictReader(file, delimiter="\t")
        if not {"jp_name", "svt_id"}.issubset(reader.fieldnames or []):
            raise SystemExit(f"{path} must contain at least columns: jp_name, svt_id")
        for row in reader:
            svt_ids = tuple(
                int(value.strip())
                for value in ALIAS_SPLIT_RE.split(clean(row.get("svt_id")))
                if value.strip().isdigit()
            )
            if not svt_ids:
                continue
            names = [clean(row.get("jp_name")), *split_aliases(row.get("aliases"))]
            for name in names:
                key = normalize_key(name)
                if key:
                    overrides[key] = svt_ids
    return overrides


def download_atlas_lore(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = path.with_suffix(path.suffix + ".tmp")
    request = Request(ATLAS_LORE_SERVANT_URL, headers=ATLAS_HEADERS)
    try:
        with urlopen(request, timeout=240) as response, tmp_path.open("wb") as file:
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk:
                    break
                file.write(chunk)
        tmp_path.replace(path)
    except (HTTPError, URLError, TimeoutError, OSError) as exc:
        if tmp_path.exists():
            tmp_path.unlink()
        raise SystemExit(f"Failed to fetch Atlas lore export {ATLAS_LORE_SERVANT_URL}: {exc}") from exc


def build_exact_index(servants: list[dict[str, Any]]) -> dict[str, list[dict[str, Any]]]:
    index: dict[str, list[dict[str, Any]]] = {}
    for servant in servants:
        for name in servant_names(servant):
            key = normalize_key(name)
            if key:
                index.setdefault(key, []).append(servant)
    return index


def servant_names(servant: dict[str, Any]) -> tuple[str, ...]:
    names: list[str] = []
    for key in ("name", "originalName", "battleName", "originalBattleName", "ruby"):
        names.append(clean(servant.get(key)))

    costume = servant.get("costume")
    if isinstance(costume, dict):
        for costume_row in costume.values():
            if not isinstance(costume_row, dict):
                continue
            names.append(clean(costume_row.get("name")))
            names.append(clean(costume_row.get("shortName")))

    return tuple(unique_preserve_order(name for name in names if name))


def match_characters(
    characters: list[CharacterRow],
    servants: list[dict[str, Any]],
    overrides: dict[str, tuple[int, ...]],
    *,
    include_ambiguous: bool,
) -> tuple[list[CharacterServantMatch], list[dict[str, str]]]:
    exact_index = build_exact_index(servants)
    servants_by_id = {int(servant["id"]): servant for servant in servants if isinstance(servant.get("id"), int)}
    matches: list[CharacterServantMatch] = []
    unmatched: list[dict[str, str]] = []

    for character in characters:
        names = character_lookup_names(character)
        override_ids = first_override(names, overrides)
        if override_ids:
            found = [servants_by_id[svt_id] for svt_id in override_ids if svt_id in servants_by_id]
            if found:
                for servant in found:
                    matches.append(CharacterServantMatch(character, servant, "override", str(servant.get("id"))))
                continue
            unmatched.append(unmatched_row(character, "override_svt_id_not_found", ",".join(map(str, override_ids))))
            continue

        exact_matches = exact_servant_matches(names, exact_index)
        if exact_matches:
            for servant, matched_name in exact_matches:
                matches.append(CharacterServantMatch(character, servant, "exact", matched_name))
            continue

        token_matches = token_servant_matches(names, servants)
        if len(token_matches) == 1:
            servant, matched_name = token_matches[0]
            matches.append(CharacterServantMatch(character, servant, "token", matched_name))
            continue
        if len(token_matches) > 1 and include_ambiguous:
            for servant, matched_name in token_matches:
                matches.append(CharacterServantMatch(character, servant, "token_ambiguous", matched_name))
            continue
        if len(token_matches) > 1:
            detail = ", ".join(
                f"{servant.get('id')}:{clean(servant.get('name'))}"
                for servant, _ in token_matches[:8]
            )
            unmatched.append(unmatched_row(character, "ambiguous_token_match", detail))
            continue

        unmatched.append(unmatched_row(character, "no_servant_match", ""))

    return matches, unmatched


def first_override(names: tuple[str, ...], overrides: dict[str, tuple[int, ...]]) -> tuple[int, ...] | None:
    for name in names:
        override = overrides.get(normalize_key(name))
        if override:
            return override
    return None


def exact_servant_matches(
    names: tuple[str, ...],
    exact_index: dict[str, list[dict[str, Any]]],
) -> list[tuple[dict[str, Any], str]]:
    seen_ids: set[int] = set()
    matches: list[tuple[dict[str, Any], str]] = []
    for name in names:
        key = normalize_key(name)
        if not key:
            continue
        for servant in exact_index.get(key, ()):
            svt_id = int(servant.get("id") or 0)
            if svt_id in seen_ids:
                continue
            seen_ids.add(svt_id)
            matches.append((servant, name))
    return matches


def token_servant_matches(names: tuple[str, ...], servants: list[dict[str, Any]]) -> list[tuple[dict[str, Any], str]]:
    best_by_id: dict[int, tuple[dict[str, Any], str, int]] = {}
    for name in names:
        parts = split_name_parts(name)
        if not parts:
            continue
        if len(parts) == 1 and len(parts[0]) < MIN_SINGLE_TOKEN_MATCH_LENGTH:
            continue
        for servant in servants:
            servant_keys = [normalize_key(servant_name) for servant_name in servant_names(servant)]
            if not servant_keys:
                continue
            if all(any(part in servant_key for servant_key in servant_keys) for part in parts):
                svt_id = int(servant.get("id") or 0)
                score = sum(len(part) for part in parts)
                previous = best_by_id.get(svt_id)
                if previous is None or score > previous[2]:
                    best_by_id[svt_id] = (servant, name, score)
    return [(servant, matched_name) for servant, matched_name, _ in best_by_id.values()]


def collect_audio_rows(
    matches: list[CharacterServantMatch],
    output_dir: Path,
    include_types: set[str] | None,
    folder_style: str,
) -> list[AudioManifestRow]:
    rows: list[AudioManifestRow] = []
    seen_character_urls: set[tuple[str, str]] = set()
    for match in matches:
        servant = match.servant
        profile = servant.get("profile") if isinstance(servant.get("profile"), dict) else {}
        voices = profile.get("voices") if isinstance(profile.get("voices"), list) else []
        for voice_group in voices:
            if not isinstance(voice_group, dict):
                continue
            voice_type = clean(voice_group.get("type"))
            if include_types is not None and voice_type not in include_types:
                continue
            voice_lines = voice_group.get("voiceLines") if isinstance(voice_group.get("voiceLines"), list) else []
            for voice_line in voice_lines:
                if not isinstance(voice_line, dict):
                    continue
                line_name = clean(voice_line.get("overwriteName")) or clean(voice_line.get("name"))
                ids = voice_line.get("id") if isinstance(voice_line.get("id"), list) else []
                audio_assets = voice_line.get("audioAssets") if isinstance(voice_line.get("audioAssets"), list) else []
                for index, audio_url in enumerate(audio_assets):
                    audio_url = clean(audio_url)
                    if not is_atlas_audio_url(audio_url):
                        continue
                    voice_id = clean(ids[index] if index < len(ids) else Path(urlparse(audio_url).path).stem)
                    character_key = f"{match.character.jp_name}|{match.character.cn_name}"
                    if (character_key, audio_url) in seen_character_urls:
                        continue
                    seen_character_urls.add((character_key, audio_url))
                    local_file = local_audio_path(
                        output_dir,
                        match,
                        voice_type,
                        line_name,
                        voice_id,
                        audio_url,
                        folder_style,
                    )
                    rows.append(
                        AudioManifestRow(
                            jp_name=match.character.jp_name,
                            cn_name=match.character.cn_name,
                            aliases="|".join(match.character.aliases),
                            character_folder=character_folder_name(match),
                            match_kind=match.match_kind,
                            matched_name=match.matched_name,
                            svt_id=str(servant.get("id") or ""),
                            collection_no=str(servant.get("collectionNo") or ""),
                            servant_name=clean(servant.get("name")),
                            voice_type=voice_type,
                            line_name=line_name,
                            voice_id=voice_id,
                            audio_url=audio_url,
                            local_path=local_file.relative_to(output_dir).as_posix(),
                        )
                    )
    return disambiguate_local_paths(rows, output_dir, folder_style)


def write_manifest(path: Path, rows: list[AudioManifestRow]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    header = (
        "jp_name",
        "cn_name",
        "aliases",
        "character_folder",
        "match_kind",
        "matched_name",
        "svt_id",
        "collection_no",
        "servant_name",
        "voice_type",
        "line_name",
        "voice_id",
        "audio_url",
        "local_path",
    )
    with path.open("w", encoding="utf-8", newline="") as file:
        writer = csv.writer(file, delimiter="\t", lineterminator="\n")
        writer.writerow(header)
        for row in rows:
            writer.writerow(tuple(getattr(row, column) for column in header))


def write_unmatched(path: Path, rows: list[dict[str, str]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    header = ("line_num", "jp_name", "cn_name", "aliases", "reason", "detail")
    with path.open("w", encoding="utf-8", newline="") as file:
        writer = csv.DictWriter(file, delimiter="\t", fieldnames=header, lineterminator="\n")
        writer.writeheader()
        for row in rows:
            writer.writerow(row)


def write_failed(path: Path, output_dir: Path, rows: list[tuple[str, Path, str]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as file:
        writer = csv.writer(file, delimiter="\t", lineterminator="\n")
        writer.writerow(("audio_url", "local_path", "error"))
        for url, local_path, error in rows:
            display_path = local_path
            if local_path.is_relative_to(output_dir):
                display_path = local_path.relative_to(output_dir)
            writer.writerow((url, display_path.as_posix(), error))


def download_audio_files(
    output_dir: Path,
    rows: list[AudioManifestRow],
    *,
    overwrite: bool,
    delay_seconds: float,
    timeout_seconds: int,
    workers: int,
) -> DownloadStats:
    stats = DownloadStats()
    unique_targets: dict[Path, str] = {}
    for row in rows:
        unique_targets.setdefault(output_dir / row.local_path, row.audio_url)

    pending: list[tuple[str, Path]] = []
    for local_path, url in unique_targets.items():
        if local_path.exists() and local_path.stat().st_size > 0 and not overwrite:
            stats.skipped += 1
            continue
        pending.append((url, local_path))

    if not pending:
        return stats

    total = len(unique_targets)
    worker_count = max(1, workers)
    completed = stats.skipped
    print(
        f"Starting downloads: {len(pending)} pending, "
        f"{stats.skipped} already present, {worker_count} worker(s)."
    )

    with ThreadPoolExecutor(max_workers=worker_count) as executor:
        futures = {
            executor.submit(download_file_with_delay, url, local_path, timeout_seconds, delay_seconds): (url, local_path)
            for url, local_path in pending
        }
        for future in as_completed(futures):
            url, local_path = futures[future]
            completed += 1
            try:
                bytes_written = future.result()
                stats.downloaded += 1
                stats.bytes_written += bytes_written
            except (HTTPError, URLError, TimeoutError, OSError) as exc:
                stats.failed += 1
                stats.failed_rows.append((url, local_path, str(exc)))
                print(f"warning: failed {url}: {exc}", file=sys.stderr)
            if completed == 1 or completed % 100 == 0 or completed == total:
                print(
                    f"Progress {completed}/{total}: "
                    f"{stats.downloaded} downloaded, {stats.skipped} skipped, {stats.failed} failed"
                )
    return stats


def download_file_with_delay(url: str, path: Path, timeout_seconds: int, delay_seconds: float) -> int:
    bytes_written = download_file(url, path, timeout_seconds)
    if delay_seconds > 0:
        time.sleep(delay_seconds)
    return bytes_written


def download_file(url: str, path: Path, timeout_seconds: int) -> int:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = path.with_suffix(path.suffix + ".tmp")
    request = Request(url, headers=ATLAS_HEADERS)
    bytes_written = 0
    try:
        with urlopen(request, timeout=timeout_seconds) as response, tmp_path.open("wb") as file:
            while True:
                chunk = response.read(256 * 1024)
                if not chunk:
                    break
                bytes_written += len(chunk)
                file.write(chunk)
        tmp_path.replace(path)
        return bytes_written
    except Exception:
        if tmp_path.exists():
            tmp_path.unlink()
        raise


def local_audio_path(
    output_dir: Path,
    match: CharacterServantMatch,
    voice_type: str,
    line_name: str,
    voice_id: str,
    audio_url: str,
    folder_style: str,
) -> Path:
    if folder_style == "character":
        extension = Path(urlparse(audio_url).path).suffix or ".mp3"
        file_stem = safe_path_component(
            "__".join(part for part in (voice_id, line_name) if part),
            max_length=MAX_FILE_STEM_LENGTH,
        )
        return (
            output_dir
            / "audio"
            / character_folder_name(match)
            / safe_path_component(voice_type or "voice", max_length=MAX_FOLDER_COMPONENT_LENGTH)
            / f"{file_stem}{extension}"
        )

    parsed = urlparse(audio_url)
    parts = [safe_path_component(part) for part in Path(parsed.path).parts if part not in {"\\", "/"}]
    if len(parts) >= 2:
        return output_dir / "audio" / parts[-2] / parts[-1]
    return output_dir / "audio" / safe_path_component(Path(parsed.path).name or "audio.mp3")


def source_audio_folder(audio_url: str) -> str:
    parsed = urlparse(audio_url)
    parts = [part for part in Path(parsed.path).parts if part not in {"\\", "/"}]
    if len(parts) >= 2:
        return parts[-2]
    return "audio"


def character_folder_name(match: CharacterServantMatch) -> str:
    return safe_path_component(
        "__".join(
            part
            for part in (
                match.character.jp_name,
                match.character.cn_name,
                str(match.servant.get("id") or ""),
            )
            if part
        ),
        max_length=MAX_FOLDER_COMPONENT_LENGTH,
    )


def safe_path_component(value: str, *, max_length: int = MAX_FOLDER_COMPONENT_LENGTH) -> str:
    cleaned = INVALID_PATH_COMPONENT_RE.sub("_", value)
    cleaned = SPACE_RE.sub(" ", cleaned).strip(" ._")
    if len(cleaned) > max_length:
        cleaned = cleaned[:max_length].rstrip(" ._")
    return cleaned or "unknown"


def is_atlas_audio_url(url: str) -> bool:
    parsed = urlparse(url)
    return parsed.scheme == "https" and parsed.netloc == STATIC_ATLAS_HOST and parsed.path.lower().endswith(".mp3")


def character_lookup_names(character: CharacterRow) -> tuple[str, ...]:
    return tuple(unique_preserve_order((character.jp_name, *character.aliases)))


def unmatched_row(character: CharacterRow, reason: str, detail: str) -> dict[str, str]:
    return {
        "line_num": str(character.line_num),
        "jp_name": character.jp_name,
        "cn_name": character.cn_name,
        "aliases": "|".join(character.aliases),
        "reason": reason,
        "detail": detail,
    }


def split_aliases(value: str | None) -> tuple[str, ...]:
    if not value:
        return ()
    return tuple(unique_preserve_order(part.strip() for part in ALIAS_SPLIT_RE.split(value) if part.strip()))


def split_name_parts(value: str) -> tuple[str, ...]:
    parts = [
        normalize_key(part)
        for part in NAME_PART_SPLIT_RE.split(unicodedata.normalize("NFKC", value))
        if part.strip()
    ]
    return tuple(unique_preserve_order(part for part in parts if len(part) >= MIN_TOKEN_MATCH_LENGTH))


def unique_preserve_order(values) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for value in values:
        key = normalize_key(value)
        if not key or key in seen:
            continue
        seen.add(key)
        result.append(value)
    return result


def normalize_key(value: object) -> str:
    normalized = unicodedata.normalize("NFKC", clean(value))
    normalized = normalized.strip().strip("「」『』[]()（）【】,，.。:：;；!！?？")
    normalized = MIDDLE_DOT_RE.sub("", normalized)
    normalized = SPACE_RE.sub("", normalized)
    normalized = normalized.translate(str.maketrans("", "", "[]()（）【】〔〕/／&＆=＝"))
    return normalized.lower()


def clean(value: object) -> str:
    if value is None:
        return ""
    return str(value).replace("\t", " ").replace("\r", " ").replace("\n", " ").strip()


def parse_type_filter(value: str) -> set[str] | None:
    if not value.strip():
        return None
    return {part.strip() for part in value.split(",") if part.strip()}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--characters", type=Path, default=DEFAULT_CHARACTERS)
    parser.add_argument("--atlas-lore", type=Path, default=DEFAULT_ATLAS_LORE)
    parser.add_argument("--overrides", type=Path, default=DEFAULT_OVERRIDES)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--unmatched", type=Path)
    parser.add_argument("--failed", type=Path)
    parser.add_argument("--fetch-atlas-lore", action="store_true", help="Download/refresh Atlas nice_servant_lore first.")
    parser.add_argument("--dry-run", action="store_true", help="Write manifests without downloading MP3 files.")
    parser.add_argument("--include-ambiguous", action="store_true", help="Include ambiguous token matches instead of reporting them.")
    parser.add_argument("--include-types", default="", help="Comma-separated voice group types to include, e.g. home,battle.")
    parser.add_argument(
        "--folder-style",
        choices=("character", "atlas"),
        default="character",
        help="Use character-named folders by default, or Atlas ChrVoice_* folders.",
    )
    parser.add_argument("--limit-characters", type=int, default=0, help="Only process the first N matched character rows.")
    parser.add_argument("--limit-assets", type=int, default=0, help="Only keep/download the first N manifest rows.")
    parser.add_argument("--overwrite", action="store_true", help="Redownload files that already exist.")
    parser.add_argument("--delay", type=float, default=0.0, help="Optional delay between MP3 downloads, in seconds.")
    parser.add_argument("--timeout", type=int, default=45, help="Per-file download timeout in seconds.")
    parser.add_argument("--workers", type=int, default=8, help="Parallel MP3 download workers.")
    parser.add_argument("--strict", action="store_true", help="Exit non-zero if any MP3 download fails.")
    args = parser.parse_args()

    if args.fetch_atlas_lore or not args.atlas_lore.exists():
        download_atlas_lore(args.atlas_lore)

    characters = read_characters(args.characters)
    servants = read_servants(args.atlas_lore)
    overrides = read_overrides(args.overrides)
    matches, unmatched = match_characters(
        characters,
        servants,
        overrides,
        include_ambiguous=args.include_ambiguous,
    )
    if args.limit_characters > 0:
        allowed_keys = set()
        for match in matches:
            allowed_keys.add(f"{match.character.line_num}:{match.character.jp_name}")
            if len(allowed_keys) >= args.limit_characters:
                break
        matches = [
            match
            for match in matches
            if f"{match.character.line_num}:{match.character.jp_name}" in allowed_keys
        ]

    output_dir = args.output_dir
    manifest_path = args.manifest or output_dir / "manifest.tsv"
    unmatched_path = args.unmatched or output_dir / "unmatched.tsv"
    failed_path = args.failed or output_dir / "failed.tsv"
    include_types = parse_type_filter(args.include_types)
    audio_rows = collect_audio_rows(matches, output_dir, include_types, args.folder_style)
    if args.limit_assets > 0:
        audio_rows = audio_rows[: args.limit_assets]

    write_manifest(manifest_path, audio_rows)
    write_unmatched(unmatched_path, unmatched)

    unique_audio_count = len({row.audio_url for row in audio_rows})
    target_file_count = len({row.local_path for row in audio_rows})
    matched_character_count = len({f"{match.character.line_num}:{match.character.jp_name}" for match in matches})
    print(f"Read character rows: {len(characters)}")
    print(f"Read Atlas servants: {len(servants)}")
    print(f"Matched character rows: {matched_character_count}")
    print(f"Unmatched/ambiguous rows: {len(unmatched)}")
    print(f"Wrote manifest rows: {len(audio_rows)}")
    print(f"Unique MP3 files: {unique_audio_count}")
    print(f"Target local files: {target_file_count}")
    print(f"Manifest: {manifest_path}")
    print(f"Unmatched: {unmatched_path}")

    if args.dry_run:
        print("Dry run: no MP3 files downloaded.")
        return

    stats = download_audio_files(
        output_dir,
        audio_rows,
        overwrite=args.overwrite,
        delay_seconds=max(0.0, args.delay),
        timeout_seconds=max(1, args.timeout),
        workers=args.workers,
    )
    print(
        "Download complete: "
        f"{stats.downloaded} downloaded, "
        f"{stats.skipped} skipped, "
        f"{stats.failed} failed, "
        f"{stats.bytes_written} bytes written"
    )
    write_failed(failed_path, output_dir, stats.failed_rows)
    if stats.failed:
        print(f"Failed download report: {failed_path}")
    if stats.failed and args.strict:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
