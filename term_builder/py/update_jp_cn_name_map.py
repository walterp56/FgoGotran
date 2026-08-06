"""
Build/update term_builder/jp_cn_name_map.tsv from Atlas Academy script name boxes.

The TSV stays simple:

    jp_name    cn_name_simp    cn_name_trad    count

Default mode is dry-run. Use --write after reviewing the printed changes.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import sys
import time
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


ATLAS_BASE = "https://api.atlasacademy.io"
STATIC_BASE = "https://static.atlasacademy.io"
USER_AGENT = "fgoGotran-jp-cn-name-map/1.0"

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parent
DEFAULT_MAP_TSV = ROOT / "jp_cn_name_map.tsv"
DEFAULT_CACHE_DIR = ROOT / "atlas_cache" / "jp_cn_name_map"
DEFAULT_CHARACTER_NAMES = ROOT / "character_names.tsv"
DEFAULT_TERM_TSV = ROOT / "term.tsv"
DEFAULT_NAME_BOX_TSV = ROOT / "name_box.tsv"
DEFAULT_VOICE_TUNE_PROFILES = ROOT / "voice_tune" / "character_voice_profiles_cn.tsv"
DEFAULT_VOICE_TUNE_REVIEW = ROOT / "voice_tune" / "needs_review.tsv"

HEADER = ("jp_name", "cn_name_simp", "cn_name_trad", "count")
REQUIRED_HEADER = ("jp_name", "cn_name_simp", "cn_name_trad")
SERVANT_FIELDS = ("name", "originalName", "battleName", "originalBattleName")
BAD_SIMPLIFIED_MATCHES = {
    ("フランチェスカ", "超级青子"),
    ("フランチェスカ？", "超级青子？"),
}
BAD_TRADITIONAL_MATCHES = {
    ("フランチェスカ", "超級青子"),
    ("フランチェスカ・プレラーティ", "蒼崎青子"),
    ("フランチェスカ？", "超級青子？"),
}
TRANSLATION_SUFFIXES = (
    ("？", "？", "？"),
    ("の声", "的声音", "的聲音"),
    ("の影", "的影子", "的影子"),
    ("たち", "们", "們"),
    ("達", "们", "們"),
)
RAW_NAME_BOX_RE = re.compile(r"(?:^|\n)\s*＠(?P<label>[^\r\n]+)")
SPOT_RE = re.compile(r"=spot\[[^\]]*\]")
BRACKET_TAG_RE = re.compile(r"\[([^\]]*)\]")
COLOR_TAG_RE = re.compile(r"^(?:[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8}|-)$")
LEADING_ACTOR_RE = re.compile(r"^[0-9A-ZＡ-Ｚ]+[:：]+\s*")
SPRITE_INDEX_RE = re.compile(r"[（(][0-9?]+[）)]")
NAME_SPLIT_RE = re.compile(r"[\uFF06&\uFF0F/]")


@dataclass(frozen=True)
class MapRow:
    jp_name: str
    cn_name_simp: str
    cn_name_trad: str
    jp_count: int = 0


@dataclass(frozen=True)
class ScriptLabels:
    script_id: str
    labels: tuple[str, ...]


@dataclass
class Changes:
    new_rows: list[MapRow]
    updated_simp: list[tuple[str, str, str]]
    updated_trad: list[tuple[str, str, str]]
    missing_simp: list[str]
    missing_trad: list[str]
    conflicts: list[str]
    stale_rows: list[str]
    pruned_rows: list[str]


def configure_output() -> None:
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure:
            try:
                reconfigure(encoding="utf-8", errors="replace")
            except Exception:
                pass


def clean(value: Any) -> str:
    if value is None:
        return ""
    return str(value).strip()


def clean_int(value: Any) -> int:
    text = clean(value)
    return int(text) if text.isdigit() else 0


def clean_label(value: Any) -> str:
    text = clean(value)
    if not text:
        return ""
    text = SPOT_RE.sub("", text)
    text = LEADING_ACTOR_RE.sub("", text)
    text = BRACKET_TAG_RE.sub(replace_bracket_tag, text)
    text = SPRITE_INDEX_RE.sub("", text)
    text = text.replace("\u3000", " ")
    return " ".join(text.split()).strip()


def split_name_parts(value: Any) -> list[str]:
    name = clean_label(value)
    if not name:
        return []
    return [part for part in (clean_label(part) for part in NAME_SPLIT_RE.split(name)) if part]


def split_parallel_parts(value: Any, expected_count: int) -> list[str]:
    parts = split_name_parts(value)
    if len(parts) == expected_count:
        return parts
    return []


def replace_bracket_tag(match: re.Match[str]) -> str:
    content = match.group(1).strip()
    if not content or COLOR_TAG_RE.fullmatch(content):
        return ""
    if content.startswith("image "):
        return ""
    if re.fullmatch(r"%[0-9]+", content):
        return content
    if content.startswith("#") and ":" in content:
        return content.rsplit(":", 1)[-1].strip()
    if content.startswith("servantName "):
        return content.rsplit(":", 1)[-1].strip()
    if content.startswith("tRoute "):
        value = content.rsplit(",", 1)[-1].strip()
        return re.sub(r"^[0-9]+\s+", "", value)
    return match.group(0)


def read_map(path: Path) -> dict[str, MapRow]:
    if not path.exists():
        return {}

    rows: dict[str, MapRow] = {}
    with path.open("r", encoding="utf-8-sig", newline="") as file:
        reader = csv.DictReader(file, delimiter="\t")
        if not set(REQUIRED_HEADER).issubset(reader.fieldnames or []):
            raise SystemExit(f"{path} must contain columns: {', '.join(REQUIRED_HEADER)}")
        for row in reader:
            jp_parts = split_name_parts(row.get("jp_name"))
            if not jp_parts:
                continue
            simp_parts = split_parallel_parts(row.get("cn_name_simp"), len(jp_parts))
            trad_parts = split_parallel_parts(row.get("cn_name_trad"), len(jp_parts))
            jp_count = clean_int(row.get("count"))
            for index, jp_name in enumerate(jp_parts):
                rows[jp_name] = MapRow(
                    jp_name=jp_name,
                    cn_name_simp=simp_parts[index] if simp_parts else "",
                    cn_name_trad=trad_parts[index] if trad_parts else "",
                    jp_count=jp_count,
                )
    return rows


def split_aliases(value: str | None) -> list[str]:
    return [item.strip() for item in re.split(r"[,|]", value or "") if item.strip()]


def load_name_box_counts(path: Path) -> Counter[str]:
    counts: Counter[str] = Counter()
    if not path.exists():
        return counts

    with path.open("r", encoding="utf-8-sig", newline="") as file:
        reader = csv.DictReader(file, delimiter="\t")
        if not {"name_box", "count"}.issubset(reader.fieldnames or []):
            raise SystemExit(f"{path} must contain columns: name_box, count")
        for row in reader:
            count = clean_int(row.get("count"))
            if not count:
                continue
            for name in split_name_parts(row.get("name_box")):
                counts[name] = max(counts[name], count)
    return counts


def load_local_simplified_fallbacks(character_path: Path, term_path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    conflicts: set[str] = set()

    def add(name: str, cn_name: str, *, replace: bool = False) -> None:
        name = clean_label(name)
        cn_name = clean_label(cn_name)
        if not name or not cn_name or name in conflicts:
            return
        old = result.get(name)
        if old and old != cn_name and not replace:
            conflicts.add(name)
            result.pop(name, None)
            return
        result[name] = cn_name

    if character_path.exists():
        with character_path.open("r", encoding="utf-8-sig", newline="") as file:
            reader = csv.DictReader(file, delimiter="\t")
            for row in reader:
                cn_name = clean_label(row.get("cn_name"))
                names = [clean_label(row.get("jp_name")), *split_aliases(row.get("aliases"))]
                for name in names:
                    add(name, cn_name)

    if term_path.exists():
        with term_path.open("r", encoding="utf-8-sig", newline="") as file:
            reader = csv.DictReader(file, delimiter="\t")
            for row in reader:
                add(clean_label(row.get("jp_term")), clean_label(row.get("cn_term")))

    return result


def write_map(path: Path, rows: list[MapRow]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8-sig", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=HEADER, delimiter="\t", lineterminator="\n")
        writer.writeheader()
        for row in rows:
            writer.writerow(
                {
                    "jp_name": row.jp_name,
                    "cn_name_simp": row.cn_name_simp,
                    "cn_name_trad": row.cn_name_trad,
                    "count": row.jp_count if row.jp_count else "",
                }
            )


def cache_path(cache_dir: Path | None, resource: str, suffix: str) -> Path | None:
    if cache_dir is None:
        return None
    digest = hashlib.sha1(resource.encode("utf-8")).hexdigest()[:12]
    safe = re.sub(r"[^0-9A-Za-z._-]+", "__", resource.strip("/"))[:150]
    return cache_dir / f"{safe or 'root'}.{digest}.{suffix}"


def fetch_json(url: str, *, cache_dir: Path | None, refresh_cache: bool) -> Any:
    path = cache_path(cache_dir, url, "json")
    if path and path.exists() and not refresh_cache:
        return json.loads(path.read_text(encoding="utf-8"))

    request = Request(url, headers={"Accept": "application/json", "User-Agent": USER_AGENT})
    with urlopen(request, timeout=60) as response:
        data = json.loads(response.read().decode("utf-8"))

    if path:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    return data


def load_servant_fallbacks(
    jp_region: str,
    simp_region: str,
    trad_region: str,
    *,
    cache_dir: Path | None,
    refresh_cache: bool,
) -> tuple[dict[str, str], dict[str, str], list[str]]:
    try:
        jp_rows = fetch_json(
            f"{ATLAS_BASE}/export/{jp_region}/nice_servant.json",
            cache_dir=cache_dir,
            refresh_cache=refresh_cache,
        )
        simp_rows = fetch_json(
            f"{ATLAS_BASE}/export/{simp_region}/nice_servant.json",
            cache_dir=cache_dir,
            refresh_cache=refresh_cache,
        )
        trad_rows = fetch_json(
            f"{ATLAS_BASE}/export/{trad_region}/nice_servant.json",
            cache_dir=cache_dir,
            refresh_cache=refresh_cache,
        )
    except (HTTPError, URLError, TimeoutError, json.JSONDecodeError):
        return {}, {}, ["servant fallback unavailable"]

    simp_acc: dict[str, set[str]] = defaultdict(set)
    trad_acc: dict[str, set[str]] = defaultdict(set)
    simp_by_no = {
        int(row["collectionNo"]): row
        for row in simp_rows
        if isinstance(row, dict) and clean(row.get("collectionNo")).isdigit()
    }
    trad_by_no = {
        int(row["collectionNo"]): row
        for row in trad_rows
        if isinstance(row, dict) and clean(row.get("collectionNo")).isdigit()
    }

    for jp_row in jp_rows:
        if not isinstance(jp_row, dict) or not clean(jp_row.get("collectionNo")).isdigit():
            continue
        collection_no = int(jp_row["collectionNo"])
        simp_row = simp_by_no.get(collection_no)
        trad_row = trad_by_no.get(collection_no)
        for field in SERVANT_FIELDS:
            jp_name = clean_label(jp_row.get(field))
            if not jp_name:
                continue
            if simp_row:
                simp_name = clean_label(simp_row.get(field))
                if simp_name:
                    simp_acc[jp_name].add(simp_name)
            if trad_row:
                trad_name = clean_label(trad_row.get(field))
                if trad_name:
                    trad_acc[jp_name].add(trad_name)

    conflicts = [
        f"servant simplified {name}: {' | '.join(sorted(values))}"
        for name, values in simp_acc.items()
        if len(values) > 1
    ]
    conflicts.extend(
        f"servant traditional {name}: {' | '.join(sorted(values))}"
        for name, values in trad_acc.items()
        if len(values) > 1
    )
    simp = {name: next(iter(values)) for name, values in simp_acc.items() if len(values) == 1}
    trad = {name: next(iter(values)) for name, values in trad_acc.items() if len(values) == 1}
    return simp, trad, sorted(conflicts)


def fetch_text(url: str, *, cache_dir: Path | None, refresh_cache: bool) -> str:
    path = cache_path(cache_dir, url, "txt")
    if path and path.exists() and not refresh_cache:
        return path.read_text(encoding="utf-8-sig")

    request = Request(url, headers={"Accept": "text/plain,*/*", "User-Agent": USER_AGENT})
    with urlopen(request, timeout=45) as response:
        text = response.read().decode("utf-8-sig")

    if path:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
    return text


def script_url(region: str, script_id: str) -> str:
    return f"{STATIC_BASE}/{region}/Script/{script_id[:2]}/{script_id}.txt"


def atlas_script_ids(region: str, *, cache_dir: Path | None, refresh_cache: bool) -> list[str]:
    wars = fetch_json(
        f"{ATLAS_BASE}/export/{region}/nice_war.json",
        cache_dir=cache_dir,
        refresh_cache=refresh_cache,
    )
    ids: set[str] = set()

    def walk(value: Any) -> None:
        if isinstance(value, dict):
            for key, child in value.items():
                if key == "scriptId":
                    text = clean(child)
                    if text.isdigit():
                        ids.add(text)
                walk(child)
        elif isinstance(value, list):
            for child in value:
                walk(child)

    walk(wars)
    return sorted(ids)


def read_script_ids(path: Path) -> list[str]:
    ids: list[str] = []
    with path.open("r", encoding="utf-8-sig") as file:
        for line in file:
            text = line.strip()
            if text and not text.startswith("#"):
                ids.append(text)
    return ids


def fetch_region_labels(
    region: str,
    script_ids: list[str],
    *,
    cache_dir: Path | None,
    refresh_cache: bool,
    workers: int,
) -> list[ScriptLabels]:
    results: list[ScriptLabels] = []
    missing = 0
    started = time.time()

    with ThreadPoolExecutor(max_workers=max(workers, 1)) as executor:
        future_to_id = {
            executor.submit(
                fetch_one_script_labels,
                region,
                script_id,
                cache_dir=cache_dir,
                refresh_cache=refresh_cache,
            ): script_id
            for script_id in script_ids
        }
        total = len(future_to_id)
        for done, future in enumerate(as_completed(future_to_id), start=1):
            labels = future.result()
            if labels is None:
                missing += 1
            elif labels.labels:
                results.append(labels)

            if done % 250 == 0 or done == total:
                elapsed = max(time.time() - started, 0.1)
                print(
                    f"  {region}: {done}/{total} scripts, labels={len(results)}, missing={missing}, {done / elapsed:.1f}/s",
                    file=sys.stderr,
                )

    return sorted(results, key=lambda item: item.script_id)


def fetch_one_script_labels(
    region: str,
    script_id: str,
    *,
    cache_dir: Path | None,
    refresh_cache: bool,
) -> ScriptLabels | None:
    try:
        text = fetch_text(script_url(region, script_id), cache_dir=cache_dir, refresh_cache=refresh_cache)
    except (HTTPError, URLError, TimeoutError, UnicodeDecodeError):
        return None
    labels = tuple(label for label in extract_labels(text) if label)
    return ScriptLabels(script_id=script_id, labels=labels)


def extract_labels(text: str) -> list[str]:
    return [clean_label(match.group("label")) for match in RAW_NAME_BOX_RE.finditer(text)]


def build_rows(
    jp_scripts: list[ScriptLabels],
    simp_scripts: list[ScriptLabels],
    trad_scripts: list[ScriptLabels],
) -> tuple[list[MapRow], list[str], Counter[str]]:
    simp_by_id = {item.script_id: item.labels for item in simp_scripts}
    trad_by_id = {item.script_id: item.labels for item in trad_scripts}

    jp_counts: Counter[str] = Counter()
    simp_counts: dict[str, Counter[str]] = defaultdict(Counter)
    trad_counts: dict[str, Counter[str]] = defaultdict(Counter)

    for jp_script in jp_scripts:
        simp_labels = simp_by_id.get(jp_script.script_id, ())
        trad_labels = trad_by_id.get(jp_script.script_id, ())

        for index, jp_name in enumerate(jp_script.labels):
            jp_parts = split_name_parts(jp_name)
            if not jp_parts:
                continue
            simp_parts = (
                split_parallel_parts(simp_labels[index], len(jp_parts))
                if index < len(simp_labels)
                else []
            )
            trad_parts = (
                split_parallel_parts(trad_labels[index], len(jp_parts))
                if index < len(trad_labels)
                else []
            )
            for part_index, jp_part in enumerate(jp_parts):
                jp_counts[jp_part] += 1
                if simp_parts:
                    simp_counts[jp_part][simp_parts[part_index]] += 1
                if trad_parts:
                    trad_counts[jp_part][trad_parts[part_index]] += 1

    rows: list[MapRow] = []
    conflicts: list[str] = []
    for jp_name, jp_count in jp_counts.items():
        simp_name, simp_conflict = best_value(simp_counts[jp_name])
        trad_name, trad_conflict = best_value(trad_counts[jp_name])
        if simp_conflict:
            conflicts.append(f"{jp_name} simplified: {' | '.join(sorted(simp_counts[jp_name]))}")
            simp_name = ""
        if trad_conflict:
            conflicts.append(f"{jp_name} traditional: {' | '.join(sorted(trad_counts[jp_name]))}")
            trad_name = ""
        rows.append(MapRow(jp_name=jp_name, cn_name_simp=simp_name, cn_name_trad=trad_name, jp_count=jp_count))

    rows.sort(key=lambda row: (-row.jp_count, row.jp_name))
    return rows, sorted(conflicts), jp_counts


def best_value(counter: Counter[str]) -> tuple[str, bool]:
    if not counter:
        return "", False
    common = counter.most_common()
    best_name, best_count = common[0]
    tied = [name for name, count in common if count == best_count]
    return best_name, len(tied) > 1


def merge_with_existing(
    generated_rows: list[MapRow],
    existing: dict[str, MapRow],
    conflicts: list[str],
    *,
    prune_stale: bool,
) -> tuple[list[MapRow], Changes]:
    output: list[MapRow] = []
    seen: set[str] = set()
    changes = Changes([], [], [], [], [], conflicts, [], [])

    for row in generated_rows:
        old = existing.get(row.jp_name)
        cn_name_simp = row.cn_name_simp or (old.cn_name_simp if old else "")
        cn_name_trad = row.cn_name_trad or (old.cn_name_trad if old else "")
        next_row = MapRow(row.jp_name, cn_name_simp, cn_name_trad, row.jp_count)
        output.append(next_row)
        seen.add(row.jp_name)

        if old is None:
            changes.new_rows.append(next_row)
        else:
            if row.cn_name_simp and old.cn_name_simp != row.cn_name_simp:
                changes.updated_simp.append((row.jp_name, old.cn_name_simp, row.cn_name_simp))
            if row.cn_name_trad and old.cn_name_trad != row.cn_name_trad:
                changes.updated_trad.append((row.jp_name, old.cn_name_trad, row.cn_name_trad))

        if not row.cn_name_simp:
            changes.missing_simp.append(row.jp_name)
        if not row.cn_name_trad:
            changes.missing_trad.append(row.jp_name)

    for jp_name, old in sorted(existing.items()):
        if jp_name in seen:
            continue
        changes.stale_rows.append(jp_name)
        if prune_stale:
            changes.pruned_rows.append(jp_name)
        else:
            output.append(old)

    return output, changes


def apply_fallbacks(
    rows: list[MapRow],
    changes: Changes,
    *,
    local_simp: dict[str, str],
    servant_simp: dict[str, str],
    servant_trad: dict[str, str],
    fallback_conflicts: list[str],
) -> list[MapRow]:
    output: list[MapRow] = []
    for row in rows:
        simp = "" if (row.jp_name, row.cn_name_simp) in BAD_SIMPLIFIED_MATCHES else row.cn_name_simp
        trad = "" if (row.jp_name, row.cn_name_trad) in BAD_TRADITIONAL_MATCHES else row.cn_name_trad
        if not simp:
            fallback_simp = local_simp.get(row.jp_name) or servant_simp.get(row.jp_name, "")
            if fallback_simp and (row.jp_name, fallback_simp) not in BAD_SIMPLIFIED_MATCHES:
                simp = fallback_simp
                changes.updated_simp.append((row.jp_name, row.cn_name_simp, simp))
        if not trad:
            fallback_trad = servant_trad.get(row.jp_name, "")
            if fallback_trad and (row.jp_name, fallback_trad) not in BAD_TRADITIONAL_MATCHES:
                trad = fallback_trad
                changes.updated_trad.append((row.jp_name, row.cn_name_trad, trad))
        output.append(MapRow(row.jp_name, simp, trad, row.jp_count))

    output = apply_suffix_translation_fallbacks(output, changes)
    changes.missing_simp = [row.jp_name for row in output if not row.cn_name_simp]
    changes.missing_trad = [row.jp_name for row in output if not row.cn_name_trad]
    changes.conflicts.extend(fallback_conflicts)
    changes.conflicts = sorted(set(changes.conflicts))
    return output


def apply_name_box_counts(rows: list[MapRow], counts: Counter[str]) -> list[MapRow]:
    output = [
        MapRow(
            row.jp_name,
            row.cn_name_simp,
            row.cn_name_trad,
            counts.get(row.jp_name, row.jp_count),
        )
        for row in rows
    ]
    output.sort(key=lambda row: (-row.jp_count, row.jp_name))
    return output


def load_voice_tune_order(profile_path: Path, review_path: Path) -> list[tuple[str, ...]]:
    groups: list[tuple[str, ...]] = []
    for path in (profile_path, review_path):
        if not path.exists() or path.stat().st_size == 0:
            continue
        with path.open("r", encoding="utf-8-sig", newline="") as file:
            reader = csv.DictReader(file, delimiter="\t")
            for row in reader:
                names: list[str] = []
                speaker_id = clean_label(row.get("speaker_id"))
                if speaker_id:
                    names.append(speaker_id)
                names.extend(clean_label(alias) for alias in (row.get("aliases") or "").split("|"))
                names = list(dict.fromkeys(name for name in names if name))
                if names:
                    groups.append(tuple(names))
    return groups


def apply_voice_tune_order(rows: list[MapRow], order_groups: list[tuple[str, ...]]) -> list[MapRow]:
    by_name = {row.jp_name: row for row in rows}
    used: set[str] = set()
    ordered: list[MapRow] = []

    for names in order_groups:
        for name in names:
            if name in by_name and name not in used:
                ordered.append(by_name[name])
                used.add(name)
                break

    ordered.extend(row for row in rows if row.jp_name not in used)
    return ordered


def apply_suffix_translation_fallbacks(rows: list[MapRow], changes: Changes) -> list[MapRow]:
    simp_by_jp = {row.jp_name: row.cn_name_simp for row in rows if row.cn_name_simp}
    trad_by_jp = {row.jp_name: row.cn_name_trad for row in rows if row.cn_name_trad}
    output: list[MapRow] = []

    for row in rows:
        simp = row.cn_name_simp
        trad = row.cn_name_trad
        for jp_suffix, simp_suffix, trad_suffix in TRANSLATION_SUFFIXES:
            if not row.jp_name.endswith(jp_suffix):
                continue
            base = row.jp_name[: -len(jp_suffix)]
            if not base:
                continue
            if not simp and base in simp_by_jp:
                simp = append_translated_suffix(simp_by_jp[base], simp_suffix, simplified=True)
                if (row.jp_name, simp) in BAD_SIMPLIFIED_MATCHES:
                    simp = ""
                else:
                    changes.updated_simp.append((row.jp_name, row.cn_name_simp, simp))
            if not trad and base in trad_by_jp:
                trad = append_translated_suffix(trad_by_jp[base], trad_suffix, simplified=False)
                if (row.jp_name, trad) in BAD_TRADITIONAL_MATCHES:
                    trad = ""
                else:
                    changes.updated_trad.append((row.jp_name, row.cn_name_trad, trad))
            break
        output.append(MapRow(row.jp_name, simp, trad, row.jp_count))
    return output


def append_translated_suffix(base_cn: str, suffix: str, *, simplified: bool) -> str:
    if suffix in ("的声音", "的聲音"):
        voice_words = ("声音", "语音") if simplified else ("聲音", "語音")
        if base_cn.endswith(voice_words):
            return base_cn
    return f"{base_cn}{suffix}"


def print_summary(
    *,
    script_ids: list[str],
    jp_scripts: list[ScriptLabels],
    simp_scripts: list[ScriptLabels],
    trad_scripts: list[ScriptLabels],
    rows: list[MapRow],
    existing: dict[str, MapRow],
    changes: Changes,
    wrote: bool,
    output_path: Path,
    detail_limit: int,
) -> None:
    print("JP/CN name map summary")
    print(f"  script ids: {len(script_ids)}")
    print(f"  JP scripts with labels: {len(jp_scripts)}")
    print(f"  CN scripts with labels: {len(simp_scripts)}")
    print(f"  TW scripts with labels: {len(trad_scripts)}")
    print(f"  current rows: {len(existing)}")
    print(f"  output rows: {len(rows)}")
    print()

    print(f"  new rows: {len(changes.new_rows)}")
    print(f"  updated simplified: {len(changes.updated_simp)}")
    print(f"  updated traditional: {len(changes.updated_trad)}")
    print(f"  missing simplified: {len(changes.missing_simp)}")
    print(f"  missing traditional: {len(changes.missing_trad)}")
    print(f"  conflicts: {len(changes.conflicts)}")
    print(f"  stale rows: {len(changes.stale_rows)}")
    print(f"  pruned rows: {len(changes.pruned_rows)}")
    print()

    detail("NEW_ROWS", [format_row(row) for row in changes.new_rows], detail_limit)
    detail("UPDATED_SIMPLIFIED", [f"{jp}: {old or '<blank>'} -> {new}" for jp, old, new in changes.updated_simp], detail_limit)
    detail("UPDATED_TRADITIONAL", [f"{jp}: {old or '<blank>'} -> {new}" for jp, old, new in changes.updated_trad], detail_limit)
    detail("MISSING_SIMPLIFIED", changes.missing_simp, detail_limit)
    detail("MISSING_TRADITIONAL", changes.missing_trad, detail_limit)
    detail("CONFLICTS", changes.conflicts, detail_limit)
    detail("STALE_ROWS", changes.stale_rows, detail_limit)

    if wrote:
        print(f"Wrote {len(rows)} rows to {output_path}")
    else:
        print("No files written. Re-run with --write after reviewing the summary.")


def detail(title: str, rows: list[str], limit: int) -> None:
    if not rows:
        return
    print(title)
    for row in rows[:limit]:
        print(f"  {row}")
    if len(rows) > limit:
        print(f"  ... {len(rows) - limit} more")
    print()


def format_row(row: MapRow) -> str:
    return f"{row.jp_name} ({row.jp_count}): {row.cn_name_simp or '<blank>'} / {row.cn_name_trad or '<blank>'}"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Update jp_cn_name_map.tsv from Atlas script name boxes.")
    parser.add_argument("--map-tsv", type=Path, default=DEFAULT_MAP_TSV)
    parser.add_argument("--jp-region", default="JP")
    parser.add_argument("--simp-region", default="CN")
    parser.add_argument("--trad-region", default="TW")
    parser.add_argument("--script-ids", type=Path, help="Optional newline-delimited JP script IDs.")
    parser.add_argument("--max-scripts", type=int, default=0, help="Debug limit. 0 means all scripts.")
    parser.add_argument("--workers", type=int, default=32)
    parser.add_argument("--cache-dir", type=Path, default=DEFAULT_CACHE_DIR)
    parser.add_argument("--character-names", type=Path, default=DEFAULT_CHARACTER_NAMES)
    parser.add_argument("--term-tsv", type=Path, default=DEFAULT_TERM_TSV)
    parser.add_argument("--name-box-tsv", type=Path, default=DEFAULT_NAME_BOX_TSV)
    parser.add_argument("--voice-tune-profiles", type=Path, default=DEFAULT_VOICE_TUNE_PROFILES)
    parser.add_argument("--voice-tune-review", type=Path, default=DEFAULT_VOICE_TUNE_REVIEW)
    parser.add_argument("--no-voice-tune-order", action="store_true")
    parser.add_argument("--no-local-fallback", action="store_true")
    parser.add_argument("--no-servant-fallback", action="store_true")
    parser.add_argument("--refresh-cache", action="store_true")
    parser.add_argument("--no-cache", action="store_true")
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--prune-stale", action="store_true")
    parser.add_argument("--detail-limit", type=int, default=50)
    return parser.parse_args()


def main() -> None:
    configure_output()
    args = parse_args()
    cache_dir = None if args.no_cache else args.cache_dir

    existing = read_map(args.map_tsv)
    script_ids = read_script_ids(args.script_ids) if args.script_ids else atlas_script_ids(
        args.jp_region,
        cache_dir=cache_dir,
        refresh_cache=args.refresh_cache,
    )
    if args.max_scripts > 0:
        script_ids = script_ids[: args.max_scripts]

    print(f"Loading JP scripts: {len(script_ids)}", file=sys.stderr)
    jp_scripts = fetch_region_labels(
        args.jp_region,
        script_ids,
        cache_dir=cache_dir,
        refresh_cache=args.refresh_cache,
        workers=args.workers,
    )

    needed_ids = [item.script_id for item in jp_scripts]
    print(f"Loading CN/TW scripts for JP scripts with labels: {len(needed_ids)}", file=sys.stderr)
    simp_scripts = fetch_region_labels(
        args.simp_region,
        needed_ids,
        cache_dir=cache_dir,
        refresh_cache=args.refresh_cache,
        workers=args.workers,
    )
    trad_scripts = fetch_region_labels(
        args.trad_region,
        needed_ids,
        cache_dir=cache_dir,
        refresh_cache=args.refresh_cache,
        workers=args.workers,
    )

    generated_rows, conflicts, _jp_counts = build_rows(jp_scripts, simp_scripts, trad_scripts)
    output_rows, changes = merge_with_existing(
        generated_rows,
        existing,
        conflicts,
        prune_stale=args.prune_stale,
    )

    local_simp = (
        {}
        if args.no_local_fallback
        else load_local_simplified_fallbacks(args.character_names, args.term_tsv)
    )
    servant_simp: dict[str, str] = {}
    servant_trad: dict[str, str] = {}
    fallback_conflicts: list[str] = []
    if not args.no_servant_fallback:
        servant_simp, servant_trad, fallback_conflicts = load_servant_fallbacks(
            args.jp_region,
            args.simp_region,
            args.trad_region,
            cache_dir=cache_dir,
            refresh_cache=args.refresh_cache,
        )
    output_rows = apply_fallbacks(
        output_rows,
        changes,
        local_simp=local_simp,
        servant_simp=servant_simp,
        servant_trad=servant_trad,
        fallback_conflicts=fallback_conflicts,
    )
    output_rows = apply_name_box_counts(output_rows, load_name_box_counts(args.name_box_tsv))
    if not args.no_voice_tune_order:
        output_rows = apply_voice_tune_order(
            output_rows,
            load_voice_tune_order(args.voice_tune_profiles, args.voice_tune_review),
        )

    if args.write:
        write_map(args.map_tsv, output_rows)

    print_summary(
        script_ids=script_ids,
        jp_scripts=jp_scripts,
        simp_scripts=simp_scripts,
        trad_scripts=trad_scripts,
        rows=output_rows,
        existing=existing,
        changes=changes,
        wrote=args.write,
        output_path=args.map_tsv,
        detail_limit=max(args.detail_limit, 0),
    )


if __name__ == "__main__":
    main()
