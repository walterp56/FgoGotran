"""
Build app voice profile TSVs from term_builder/character_names.tsv.

This creates deterministic draft profiles for every known character name while
keeping hand-tuned speakers in term_builder/character_voice_overrides.tsv.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import sys
import unicodedata
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parent
REPO_ROOT = ROOT.parent
DEFAULT_CHARACTERS = ROOT / "character_names.tsv"
DEFAULT_OVERRIDES = ROOT / "character_voice_overrides.tsv"
DEFAULT_REVIEW = ROOT / "character_voice_profiles.tsv"
DEFAULT_MAP = REPO_ROOT / "app" / "src" / "main" / "assets" / "voice" / "character_voice_map.tsv"
DEFAULT_PROFILES = REPO_ROOT / "app" / "src" / "main" / "assets" / "voice" / "voice_profiles.tsv"
DEFAULT_ATLAS_SERVANTS = ROOT / "atlas_basic_servant.json"
DEFAULT_ATLAS_LORE = ROOT / "atlas_nice_servant_lore.json"

ATLAS_BASIC_SERVANT_URL = "https://api.atlasacademy.io/export/JP/basic_servant.json"
ATLAS_LORE_SERVANT_URL = "https://api.atlasacademy.io/export/JP/nice_servant_lore.json"
ATLAS_HEADERS = {"Accept": "application/json", "User-Agent": "fgoGotran-voice-builder/1.0"}

ALIAS_SPLIT_RE = re.compile(r"[|,，、]+")
MIDDLE_DOT_RE = re.compile(r"[\u30FB\uFF65\u00B7\u2022\u2219]")
NAME_COMPONENT_RE = re.compile(r"[\u30FB\uFF65\u00B7\u2022\u2219/\uFF0F&\uFF06=\uFF1D\s]+")
SPACE_RE = re.compile(r"\s+")
MIN_COMPONENT_ALIAS_LENGTH = 3


@dataclass(frozen=True)
class CharacterRow:
    jp_name: str
    cn_name: str
    aliases: tuple[str, ...]


@dataclass(frozen=True)
class ManualOverride:
    jp_name: str
    cn_name: str
    aliases: tuple[str, ...]
    jp_profile_id: str
    cn_profile_id: str
    jp_voice_name: str
    cn_voice_name: str
    jp_style: str
    cn_style: str
    jp_pitch: str
    cn_pitch: str
    jp_rate: str
    cn_rate: str
    voice_archetype: str
    cv_jp: str
    gender: str
    personality_tags: str
    confidence: str
    source_url: str
    notes: str


@dataclass(frozen=True)
class AtlasCharacterMetadata:
    jp_name: str
    aliases: tuple[str, ...]
    cv_jp: str
    gender: str
    class_name: str
    traits: tuple[str, ...]
    source_url: str


@dataclass(frozen=True)
class VoiceFamily:
    archetype: str
    gender: str
    jp_voice_name: str
    jp_style: str
    jp_pitch: str
    jp_rate: str
    cn_voice_name: str
    cn_style: str
    cn_pitch: str
    cn_rate: str
    personality_tags: str


@dataclass(frozen=True)
class ProfileRow:
    profile_id: str
    provider: str
    locale: str
    voice_name: str
    style: str
    pitch: str
    rate: str
    volume: str
    description: str


VOICE_FAMILIES: tuple[VoiceFamily, ...] = (
    VoiceFamily(
        "female_gentle",
        "female",
        "ja-JP-NanamiNeural",
        "chat",
        "+3%",
        "0.97",
        "zh-CN-XiaoxiaoNeural",
        "gentle",
        "+3%",
        "0.98",
        "gentle,kind,youthful",
    ),
    VoiceFamily(
        "female_bright",
        "female",
        "ja-JP-AoiNeural",
        "",
        "+2%",
        "1.04",
        "zh-CN-XiaoyiNeural",
        "cheerful",
        "+2%",
        "1.05",
        "bright,clever,energetic",
    ),
    VoiceFamily(
        "female_cool",
        "female",
        "ja-JP-NanamiNeural",
        "",
        "-2%",
        "0.96",
        "zh-CN-XiaoxiaoNeural",
        "calm",
        "-2%",
        "0.96",
        "cool,controlled,quiet",
    ),
    VoiceFamily(
        "female_noble",
        "female",
        "ja-JP-AoiNeural",
        "",
        "0%",
        "0.98",
        "zh-CN-XiaoxiaoNeural",
        "serious",
        "0%",
        "0.97",
        "noble,formal,commanding",
    ),
    VoiceFamily(
        "female_mystic",
        "female",
        "ja-JP-NanamiNeural",
        "",
        "-1%",
        "0.93",
        "zh-CN-XiaoxiaoNeural",
        "calm",
        "-1%",
        "0.94",
        "mystic,soft,measured",
    ),
    VoiceFamily(
        "female_shadow",
        "female",
        "ja-JP-NanamiNeural",
        "",
        "-4%",
        "0.93",
        "zh-CN-XiaoxiaoNeural",
        "serious",
        "-4%",
        "0.94",
        "dark,controlled,severe",
    ),
    VoiceFamily(
        "male_calm",
        "male",
        "ja-JP-NaokiNeural",
        "",
        "-3%",
        "0.94",
        "zh-CN-YunxiNeural",
        "serious",
        "-3%",
        "0.94",
        "calm,analytical,adult",
    ),
    VoiceFamily(
        "male_heroic",
        "male",
        "ja-JP-DaichiNeural",
        "",
        "-4%",
        "1.00",
        "zh-CN-YunyeNeural",
        "serious",
        "-4%",
        "0.98",
        "heroic,firm,strong",
    ),
    VoiceFamily(
        "male_gruff",
        "male",
        "ja-JP-DaichiNeural",
        "",
        "-8%",
        "0.95",
        "zh-CN-YunyeNeural",
        "disgruntled",
        "-6%",
        "0.95",
        "rough,proud,heavy",
    ),
    VoiceFamily(
        "male_bright",
        "male",
        "ja-JP-NaokiNeural",
        "",
        "-1%",
        "1.03",
        "zh-CN-YunxiNeural",
        "cheerful",
        "-1%",
        "1.03",
        "bright,quick,confident",
    ),
    VoiceFamily(
        "child_light",
        "neutral",
        "ja-JP-NanamiNeural",
        "chat",
        "+7%",
        "1.04",
        "zh-CN-XiaoyiNeural",
        "cheerful",
        "+6%",
        "1.05",
        "young,light,playful",
    ),
    VoiceFamily(
        "alter_shadow",
        "neutral",
        "ja-JP-NaokiNeural",
        "",
        "-5%",
        "0.93",
        "zh-CN-YunxiNeural",
        "serious",
        "-5%",
        "0.94",
        "dark,controlled,severe",
    ),
    VoiceFamily(
        "narrator_mystery",
        "neutral",
        "ja-JP-NaokiNeural",
        "",
        "-2%",
        "0.92",
        "zh-CN-YunxiNeural",
        "serious",
        "-2%",
        "0.92",
        "mysterious,slow,measured",
    ),
)

GENERAL_FALLBACK_FAMILIES: tuple[str, ...] = (
    "female_gentle",
    "female_bright",
    "female_cool",
    "female_noble",
    "female_mystic",
    "male_calm",
    "male_heroic",
    "male_gruff",
    "male_bright",
)

DEFAULT_PROFILE_ROWS: tuple[ProfileRow, ...] = (
    ProfileRow(
        "default_narrator",
        "azure",
        "ja-JP",
        "ja-JP-NanamiNeural",
        "chat",
        "0%",
        "1.00",
        "100",
        "Default Japanese narrator style",
    ),
    ProfileRow(
        "default_cn_narrator",
        "azure",
        "zh-CN",
        "zh-CN-XiaoxiaoNeural",
        "chat",
        "0%",
        "1.00",
        "100",
        "Default Chinese narrator style",
    ),
)

ALTER_HINTS = ("オルタ", "Alter", "alter", "黑", "暗", "魔王", "アヴェンジャー", "复仇", "復仇")
BRIGHT_HINTS = ("サンタ", "圣诞", "泳装", "水着", "アイドル", "偶像")
CHILD_HINTS = ("リリィ", "Lily", "幼", "童", "ジャック", "ナーサリー")
FEMALE_NAME_HINTS = (
    "アルトリア",
    "ネロ",
    "ジャンヌ",
    "マリー",
    "エリザベート",
    "メドゥーサ",
    "メディア",
    "アタランテ",
    "ブーディカ",
    "牛若丸",
    "マルタ",
    "清姫",
    "玉藻",
    "沖田",
    "スカサハ",
    "ナイチンゲール",
    "イシュタル",
    "エレシュキガル",
    "メルトリリス",
    "モードレッド",
    "フランケンシュタイン",
    "酒呑童子",
    "源頼光",
    "宮本武蔵",
    "葛飾北斎",
    "ワルキューレ",
    "カーマ",
    "楊貴妃",
    "卑弥呼",
    "モルガン",
    "メリュジーヌ",
    "阿尔托莉雅",
    "尼禄",
    "贞德",
    "玛丽",
    "伊丽莎白",
    "美杜莎",
    "美狄亚",
    "阿塔兰忒",
    "布狄卡",
    "玛尔达",
)
MALE_NAME_HINTS = (
    "ジークフリート",
    "カエサル",
    "ジル",
    "エミヤ",
    "ギルガメッシュ",
    "ロビンフッド",
    "アーラシュ",
    "クー・フーリン",
    "弁慶",
    "レオニダス",
    "ロムルス",
    "ゲオルギウス",
    "ティーチ",
    "アレキサンダー",
    "アンデルセン",
    "シェイクスピア",
    "メフィスト",
    "モーツァルト",
    "諸葛孔明",
    "エルメロイ",
    "佐々木小次郎",
    "ハサン",
    "ヘラクレス",
    "齐格飞",
    "恺撒",
    "吉尔",
    "卫宫",
    "吉尔伽美什",
    "罗宾汉",
    "阿拉什",
    "库·丘林",
    "列奥尼达",
    "罗穆路斯",
    "乔尔乔斯",
    "蒂奇",
    "亚历山大",
    "安徒生",
    "莎士比亚",
    "梅菲斯托",
    "莫扎特",
)
ROYAL_HINTS = ("王", "皇", "帝", "女王", "王妃", "皇帝", "ペンドラゴン", "カエサル")
MYSTIC_HINTS = ("魔術", "魔术", "マーリン", "孔明", "ソロモン", "ホームズ", "教授", "巫", "神")

MAP_HEADER = (
    "jp_name",
    "cn_name",
    "aliases",
    "jp_profile_id",
    "cn_profile_id",
    "voice_archetype",
    "cv_jp",
    "gender",
    "personality_tags",
    "confidence",
    "source_url",
    "notes",
)

PROFILE_HEADER = (
    "profile_id",
    "provider",
    "locale",
    "voice_name",
    "style",
    "pitch",
    "rate",
    "volume",
    "description",
)

REVIEW_HEADER = MAP_HEADER + (
    "jp_voice_name",
    "jp_style",
    "jp_pitch",
    "jp_rate",
    "cn_voice_name",
    "cn_style",
    "cn_pitch",
    "cn_rate",
)


def read_characters(path: Path) -> list[CharacterRow]:
    rows: list[CharacterRow] = []
    with path.open("r", encoding="utf-8-sig", newline="") as file:
        reader = csv.DictReader(file, delimiter="\t")
        for row in reader:
            jp_name = clean(row.get("jp_name"))
            cn_name = clean(row.get("cn_name"))
            if not jp_name or not cn_name:
                continue
            rows.append(CharacterRow(jp_name, cn_name, split_aliases(row.get("aliases"))))
    return rows


def read_overrides(path: Path) -> list[ManualOverride]:
    if not path.exists():
        return []
    overrides: list[ManualOverride] = []
    with path.open("r", encoding="utf-8-sig", newline="") as file:
        reader = csv.DictReader(file, delimiter="\t")
        for row in reader:
            jp_name = clean(row.get("jp_name"))
            cn_name = clean(row.get("cn_name"))
            if not jp_name or not cn_name:
                continue
            overrides.append(
                ManualOverride(
                    jp_name=jp_name,
                    cn_name=cn_name,
                    aliases=split_aliases(row.get("aliases")),
                    jp_profile_id=clean(row.get("jp_profile_id")),
                    cn_profile_id=clean(row.get("cn_profile_id")),
                    jp_voice_name=clean(row.get("jp_voice_name")) or "ja-JP-NanamiNeural",
                    cn_voice_name=clean(row.get("cn_voice_name")) or "zh-CN-XiaoxiaoNeural",
                    jp_style=clean(row.get("jp_style")),
                    cn_style=clean(row.get("cn_style")),
                    jp_pitch=clean(row.get("jp_pitch")) or "0%",
                    cn_pitch=clean(row.get("cn_pitch")) or "0%",
                    jp_rate=clean(row.get("jp_rate")) or "1.00",
                    cn_rate=clean(row.get("cn_rate")) or "1.00",
                    voice_archetype=clean(row.get("voice_archetype")) or "manual",
                    cv_jp=clean(row.get("cv_jp")),
                    gender=clean(row.get("gender")),
                    personality_tags=clean(row.get("personality_tags")),
                    confidence=clean(row.get("confidence")) or "manual",
                    source_url=clean(row.get("source_url")),
                    notes=clean(row.get("notes")),
                )
            )
    return overrides


def build_override_index(overrides: list[ManualOverride]) -> dict[str, ManualOverride]:
    index: dict[str, ManualOverride] = {}
    for override in overrides:
        for name in (override.jp_name, override.cn_name, *override.aliases):
            key = normalize_key(name)
            if key:
                index[key] = override
    return index


def download_atlas_file(url: str, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = path.with_suffix(path.suffix + ".tmp")
    request = Request(url, headers=ATLAS_HEADERS)
    try:
        with urlopen(request, timeout=180) as response, tmp_path.open("wb") as file:
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk:
                    break
                file.write(chunk)
        tmp_path.replace(path)
    except (HTTPError, URLError, TimeoutError, OSError) as exc:
        if tmp_path.exists():
            tmp_path.unlink()
        raise SystemExit(f"Failed to fetch Atlas export {url}: {exc}") from exc


def read_atlas_index(
    servant_path: Path,
    lore_path: Path,
) -> dict[str, AtlasCharacterMetadata]:
    metadata_by_collection_no: dict[int, AtlasCharacterMetadata] = {}

    for metadata in read_atlas_metadata_file(servant_path, ATLAS_BASIC_SERVANT_URL):
        collection_no = metadata_collection_no(metadata.jp_name, metadata.aliases)
        if collection_no is not None:
            metadata_by_collection_no[collection_no] = metadata

    for metadata in read_atlas_metadata_file(lore_path, ATLAS_LORE_SERVANT_URL):
        collection_no = metadata_collection_no(metadata.jp_name, metadata.aliases)
        if collection_no is None:
            continue
        existing = metadata_by_collection_no.get(collection_no)
        metadata_by_collection_no[collection_no] = merge_atlas_metadata(existing, metadata)

    return build_atlas_name_index(tuple(metadata_by_collection_no.values()))


def read_atlas_metadata_file(path: Path, source_url: str) -> list[AtlasCharacterMetadata]:
    if not path.exists():
        return []
    with path.open("r", encoding="utf-8") as file:
        rows = json.load(file)
    if not isinstance(rows, list):
        return []
    return [
        metadata
        for row in rows
        if isinstance(row, dict)
        for metadata in (atlas_metadata_from_servant(row, source_url),)
        if metadata is not None
    ]


def atlas_metadata_from_servant(row: dict[str, Any], source_url: str) -> AtlasCharacterMetadata | None:
    jp_name = clean(row.get("name")) or clean(row.get("originalName"))
    if not jp_name:
        return None

    profile = row.get("profile") if isinstance(row.get("profile"), dict) else {}
    aliases = atlas_aliases(row)
    collection_no = row.get("collectionNo")
    if isinstance(collection_no, int):
        aliases = merge_aliases(aliases, (f"atlas_collection_no:{collection_no}",))

    return AtlasCharacterMetadata(
        jp_name=jp_name,
        aliases=aliases,
        cv_jp=clean(profile.get("cv")),
        gender=atlas_gender(row),
        class_name=clean(row.get("className")),
        traits=atlas_trait_names(row),
        source_url=source_url,
    )


def atlas_aliases(row: dict[str, Any]) -> tuple[str, ...]:
    values: list[str] = []
    for key in ("originalName", "battleName", "originalBattleName", "ruby"):
        values.append(clean(row.get(key)))

    costume = row.get("costume")
    if isinstance(costume, dict):
        for costume_row in costume.values():
            if not isinstance(costume_row, dict):
                continue
            values.append(clean(costume_row.get("name")))
            values.append(clean(costume_row.get("shortName")))

    return tuple(
        alias
        for alias in unique_preserve_order(values)
        if alias and normalize_key(alias) != normalize_key(clean(row.get("name")))
    )


def atlas_trait_names(row: dict[str, Any]) -> tuple[str, ...]:
    traits = row.get("traits")
    if not isinstance(traits, list):
        return ()
    names: list[str] = []
    for trait in traits:
        if isinstance(trait, dict):
            names.append(clean(trait.get("name")))
        else:
            names.append(clean(trait))
    return tuple(alias for alias in unique_preserve_order(names) if alias)


def atlas_gender(row: dict[str, Any]) -> str:
    gender = normalize_atlas_gender(row.get("gender"))
    if gender:
        return gender
    for trait in atlas_trait_names(row):
        gender = normalize_atlas_gender(trait)
        if gender:
            return gender
    return ""


def normalize_atlas_gender(value: object) -> str:
    text = clean(value).lower()
    if not text:
        return ""
    if text in {"female", "genderfemale"} or "genderfemale" in text:
        return "female"
    if text in {"male", "gendermale"} or "gendermale" in text:
        return "male"
    if text in {"unknown", "genderunknown"} or "genderunknown" in text:
        return "unknown"
    return ""


def metadata_collection_no(jp_name: str, aliases: tuple[str, ...]) -> int | None:
    for alias in aliases:
        if not alias.startswith("atlas_collection_no:"):
            continue
        try:
            return int(alias.removeprefix("atlas_collection_no:"))
        except ValueError:
            return None
    return None


def merge_atlas_metadata(
    base: AtlasCharacterMetadata | None,
    incoming: AtlasCharacterMetadata,
) -> AtlasCharacterMetadata:
    if base is None:
        return incoming
    return AtlasCharacterMetadata(
        jp_name=base.jp_name or incoming.jp_name,
        aliases=merge_aliases(
            tuple(alias for alias in base.aliases if not alias.startswith("atlas_collection_no:")),
            tuple(alias for alias in incoming.aliases if not alias.startswith("atlas_collection_no:")),
            tuple(alias for alias in base.aliases + incoming.aliases if alias.startswith("atlas_collection_no:")),
        ),
        cv_jp=incoming.cv_jp or base.cv_jp,
        gender=incoming.gender or base.gender,
        class_name=incoming.class_name or base.class_name,
        traits=merge_aliases(base.traits, incoming.traits),
        source_url=incoming.source_url if incoming.cv_jp else base.source_url,
    )


def build_atlas_name_index(
    metadata_rows: tuple[AtlasCharacterMetadata, ...],
) -> dict[str, AtlasCharacterMetadata]:
    key_counts: dict[str, int] = {}
    names_by_metadata: dict[AtlasCharacterMetadata, tuple[str, ...]] = {}

    for metadata in metadata_rows:
        names = tuple(
            alias
            for alias in (metadata.jp_name, *metadata.aliases)
            if alias and not alias.startswith("atlas_collection_no:")
        )
        names_by_metadata[metadata] = names
        for name in names:
            key = normalize_key(name)
            if key:
                key_counts[key] = key_counts.get(key, 0) + 1

    index: dict[str, AtlasCharacterMetadata] = {}
    for metadata, names in names_by_metadata.items():
        for name in names:
            key = normalize_key(name)
            if not key:
                continue
            if key_counts.get(key, 0) == 1 or key == normalize_key(metadata.jp_name):
                index.setdefault(key, metadata)
    return index


def find_atlas_metadata(
    character: CharacterRow,
    atlas_index: dict[str, AtlasCharacterMetadata],
) -> AtlasCharacterMetadata | None:
    for name in (character.jp_name, *character.aliases):
        metadata = atlas_index.get(normalize_key(name))
        if metadata is not None:
            return metadata
    return None


def find_atlas_metadata_for_override(
    override: ManualOverride,
    atlas_index: dict[str, AtlasCharacterMetadata],
) -> AtlasCharacterMetadata | None:
    for name in (override.jp_name, *override.aliases):
        metadata = atlas_index.get(normalize_key(name))
        if metadata is not None:
            return metadata
    return None


def build_voice_data(
    characters: list[CharacterRow],
    overrides: list[ManualOverride],
    atlas_index: dict[str, AtlasCharacterMetadata] | None = None,
) -> tuple[list[dict[str, str]], list[ProfileRow], list[dict[str, str]]]:
    atlas_index = atlas_index or {}
    override_index = build_override_index(overrides)
    unique_component_aliases = build_unique_component_aliases(characters)
    map_rows: list[dict[str, str]] = []
    review_rows: list[dict[str, str]] = []
    profiles: dict[str, ProfileRow] = {row.profile_id: row for row in DEFAULT_PROFILE_ROWS}
    exact_character_keys = {normalize_key(row.jp_name) for row in characters}

    for character in characters:
        override = find_override(character, override_index)
        atlas_metadata = find_atlas_metadata(character, atlas_index)
        if override is not None:
            jp_profile = profile_from_override(override, japanese=True)
            cn_profile = profile_from_override(override, japanese=False)
            profiles.setdefault(jp_profile.profile_id, jp_profile)
            profiles.setdefault(cn_profile.profile_id, cn_profile)
            metadata = metadata_from_override(override, atlas_metadata)
            aliases = merge_aliases(
                character.aliases,
                unique_component_aliases.get(character_key(character), ()),
                (override.jp_name, override.cn_name),
                override.aliases,
            )
            jp_profile_id = override.jp_profile_id
            cn_profile_id = override.cn_profile_id
        else:
            family = infer_family(character, atlas_metadata)
            jp_profile, cn_profile = auto_profiles(character, family)
            profiles[jp_profile.profile_id] = jp_profile
            profiles[cn_profile.profile_id] = cn_profile
            metadata = metadata_from_family(family, atlas_metadata)
            aliases = merge_aliases(
                character.aliases,
                unique_component_aliases.get(character_key(character), ()),
            )
            jp_profile_id = jp_profile.profile_id
            cn_profile_id = cn_profile.profile_id

        map_row = {
            "jp_name": character.jp_name,
            "cn_name": character.cn_name,
            "aliases": "|".join(aliases),
            "jp_profile_id": jp_profile_id,
            "cn_profile_id": cn_profile_id,
            **metadata,
        }
        map_rows.append(map_row)
        review_rows.append(
            {
                **map_row,
                "jp_voice_name": profiles[jp_profile_id].voice_name,
                "jp_style": profiles[jp_profile_id].style,
                "jp_pitch": profiles[jp_profile_id].pitch,
                "jp_rate": profiles[jp_profile_id].rate,
                "cn_voice_name": profiles[cn_profile_id].voice_name,
                "cn_style": profiles[cn_profile_id].style,
                "cn_pitch": profiles[cn_profile_id].pitch,
                "cn_rate": profiles[cn_profile_id].rate,
            }
        )

    for override in overrides:
        if normalize_key(override.jp_name) in exact_character_keys:
            continue
        jp_profile = profile_from_override(override, japanese=True)
        cn_profile = profile_from_override(override, japanese=False)
        profiles.setdefault(jp_profile.profile_id, jp_profile)
        profiles.setdefault(cn_profile.profile_id, cn_profile)
        atlas_metadata = find_atlas_metadata_for_override(override, atlas_index)
        metadata = metadata_from_override(override, atlas_metadata)
        aliases = merge_aliases((override.cn_name,), override.aliases)
        map_row = {
            "jp_name": override.jp_name,
            "cn_name": override.cn_name,
            "aliases": "|".join(aliases),
            "jp_profile_id": override.jp_profile_id,
            "cn_profile_id": override.cn_profile_id,
            **metadata,
        }
        map_rows.append(map_row)
        review_rows.append(
            {
                **map_row,
                "jp_voice_name": jp_profile.voice_name,
                "jp_style": jp_profile.style,
                "jp_pitch": jp_profile.pitch,
                "jp_rate": jp_profile.rate,
                "cn_voice_name": cn_profile.voice_name,
                "cn_style": cn_profile.style,
                "cn_pitch": cn_profile.pitch,
                "cn_rate": cn_profile.rate,
            }
        )

    return map_rows, list(profiles.values()), review_rows


def find_override(character: CharacterRow, override_index: dict[str, ManualOverride]) -> ManualOverride | None:
    for name in (character.jp_name, character.cn_name, *character.aliases):
        override = override_index.get(normalize_key(name))
        if override is not None:
            return override
    return None


def build_unique_component_aliases(characters: list[CharacterRow]) -> dict[str, tuple[str, ...]]:
    owner_by_alias: dict[str, str] = {}
    duplicate_aliases: set[str] = set()

    for character in characters:
        owner = character_key(character)
        for alias in component_aliases(character):
            key = normalize_key(alias)
            if not key:
                continue
            previous_owner = owner_by_alias.get(key)
            if previous_owner is not None and previous_owner != owner:
                duplicate_aliases.add(key)
                continue
            owner_by_alias[key] = owner

    result: dict[str, list[str]] = {}
    for character in characters:
        owner = character_key(character)
        for alias in component_aliases(character):
            key = normalize_key(alias)
            if key in duplicate_aliases:
                continue
            result.setdefault(owner, []).append(alias)

    return {
        owner: tuple(unique_preserve_order(aliases))
        for owner, aliases in result.items()
    }


def component_aliases(character: CharacterRow) -> tuple[str, ...]:
    return tuple(
        unique_preserve_order(
            part
            for text in (character.jp_name, character.cn_name)
            for part in split_name_components(text)
            if is_safe_component_alias(part, text)
        )
    )


def split_name_components(text: str) -> list[str]:
    normalized = unicodedata.normalize("NFKC", text)
    return [part.strip() for part in NAME_COMPONENT_RE.split(normalized) if part.strip()]


def is_safe_component_alias(part: str, full_text: str) -> bool:
    key = normalize_key(part)
    if len(key) < MIN_COMPONENT_ALIAS_LENGTH:
        return False
    if key == normalize_key(full_text):
        return False
    return True


def character_key(character: CharacterRow) -> str:
    return f"{normalize_key(character.jp_name)}|{normalize_key(character.cn_name)}"


def profile_from_override(override: ManualOverride, *, japanese: bool) -> ProfileRow:
    if japanese:
        return ProfileRow(
            override.jp_profile_id,
            "azure",
            "ja-JP",
            override.jp_voice_name,
            override.jp_style,
            override.jp_pitch,
            override.jp_rate,
            "100",
            f"Manual Japanese profile for {override.jp_name}",
        )
    return ProfileRow(
        override.cn_profile_id,
        "azure",
        "zh-CN",
        override.cn_voice_name,
        override.cn_style,
        override.cn_pitch,
        override.cn_rate,
        "100",
        f"Manual Chinese profile for {override.cn_name}",
    )


def auto_profiles(character: CharacterRow, family: VoiceFamily) -> tuple[ProfileRow, ProfileRow]:
    token = stable_token(character.jp_name)
    jp_pitch = nudge_pitch(family.jp_pitch, stable_int(character.jp_name, "jp_pitch") % 7 - 3)
    cn_pitch = nudge_pitch(family.cn_pitch, stable_int(character.jp_name, "cn_pitch") % 7 - 3)
    jp_rate = nudge_rate(family.jp_rate, stable_int(character.jp_name, "jp_rate") % 7 - 3)
    cn_rate = nudge_rate(family.cn_rate, stable_int(character.jp_name, "cn_rate") % 7 - 3)
    return (
        ProfileRow(
            f"auto_{token}_jp",
            "azure",
            "ja-JP",
            family.jp_voice_name,
            family.jp_style,
            jp_pitch,
            jp_rate,
            "100",
            f"Auto {family.archetype} Japanese profile for {character.jp_name}",
        ),
        ProfileRow(
            f"auto_{token}_cn",
            "azure",
            "zh-CN",
            family.cn_voice_name,
            family.cn_style,
            cn_pitch,
            cn_rate,
            "100",
            f"Auto {family.archetype} Chinese profile for {character.cn_name}",
        ),
    )


def infer_family(
    character: CharacterRow,
    atlas_metadata: AtlasCharacterMetadata | None = None,
) -> VoiceFamily:
    text = " ".join((character.jp_name, character.cn_name, *character.aliases))
    if atlas_metadata is not None:
        family = family_from_atlas(character, atlas_metadata, text)
        if family is not None:
            return family
    if contains_any(text, CHILD_HINTS):
        return family_by_archetype("child_light")
    if contains_any(text, ALTER_HINTS):
        return family_by_archetype("alter_shadow")
    if contains_any(text, BRIGHT_HINTS):
        return family_by_archetype("female_bright")
    if contains_any(text, FEMALE_NAME_HINTS):
        return hinted_family(
            character,
            "female",
            ("female_gentle", "female_bright", "female_cool", "female_noble", "female_mystic"),
        )
    if contains_any(text, MALE_NAME_HINTS):
        return hinted_family(
            character,
            "male",
            ("male_calm", "male_heroic", "male_gruff", "male_bright"),
        )
    if contains_any(text, ROYAL_HINTS):
        if stable_int(character.jp_name, "royal") % 3 == 0:
            return family_by_archetype("male_heroic")
        return family_by_archetype("female_noble")
    if contains_any(text, MYSTIC_HINTS):
        if stable_int(character.jp_name, "mystic") % 2 == 0:
            return family_by_archetype("male_calm")
        return family_by_archetype("female_mystic")
    fallback = GENERAL_FALLBACK_FAMILIES[
        stable_int(character.jp_name, "family") % len(GENERAL_FALLBACK_FAMILIES)
    ]
    return family_by_archetype(fallback)


def family_from_atlas(
    character: CharacterRow,
    metadata: AtlasCharacterMetadata,
    text: str,
) -> VoiceFamily | None:
    gender = metadata.gender
    class_name = metadata.class_name.lower()
    atlas_text = " ".join((text, metadata.jp_name, *metadata.aliases, *metadata.traits))

    if contains_any(atlas_text, CHILD_HINTS):
        if gender == "male":
            return family_by_archetype("male_bright")
        return family_by_archetype("child_light")

    if contains_any(atlas_text, ALTER_HINTS):
        if gender == "female":
            return family_by_archetype("female_shadow")
        return family_by_archetype("alter_shadow")

    if gender == "female":
        if class_name in {"avenger", "berserker"}:
            return family_by_archetype("female_shadow")
        if class_name in {"caster", "assassin", "foreigner", "alterego", "moon cancer", "mooncancer", "pretender"}:
            return family_by_archetype("female_mystic")
        if class_name in {"saber", "lancer", "rider", "ruler", "shielder"}:
            return family_by_archetype("female_noble")
        if class_name == "archer":
            return family_by_archetype("female_cool")
        return hinted_family(
            character,
            "atlas_female",
            ("female_gentle", "female_bright", "female_cool", "female_noble", "female_mystic"),
        )

    if gender == "male":
        if class_name in {"avenger", "berserker"}:
            return family_by_archetype("male_gruff")
        if class_name in {"saber", "lancer", "rider", "ruler", "shielder"}:
            return family_by_archetype("male_heroic")
        if class_name in {"caster", "assassin", "foreigner", "alterego", "moon cancer", "mooncancer", "pretender"}:
            return family_by_archetype("male_calm")
        return hinted_family(
            character,
            "atlas_male",
            ("male_calm", "male_heroic", "male_gruff", "male_bright"),
        )

    return None


def hinted_family(character: CharacterRow, salt: str, archetypes: tuple[str, ...]) -> VoiceFamily:
    archetype = archetypes[stable_int(character.jp_name, salt) % len(archetypes)]
    return family_by_archetype(archetype)


def family_by_archetype(archetype: str) -> VoiceFamily:
    for family in VOICE_FAMILIES:
        if family.archetype == archetype:
            return family
    raise ValueError(f"Unknown voice family: {archetype}")


def metadata_from_override(
    override: ManualOverride,
    atlas_metadata: AtlasCharacterMetadata | None = None,
) -> dict[str, str]:
    notes = override.notes
    if atlas_metadata is not None and (not override.cv_jp or not override.gender):
        notes = append_note(notes, "atlas metadata filled blank cv/gender")
    return {
        "voice_archetype": override.voice_archetype,
        "cv_jp": override.cv_jp or (atlas_metadata.cv_jp if atlas_metadata is not None else ""),
        "gender": override.gender or (atlas_metadata.gender if atlas_metadata is not None else ""),
        "personality_tags": override.personality_tags,
        "confidence": override.confidence,
        "source_url": override.source_url or (atlas_metadata.source_url if atlas_metadata is not None else ""),
        "notes": notes,
    }


def metadata_from_family(
    family: VoiceFamily,
    atlas_metadata: AtlasCharacterMetadata | None = None,
) -> dict[str, str]:
    if atlas_metadata is None:
        return {
            "voice_archetype": family.archetype,
            "cv_jp": "",
            "gender": family.gender,
            "personality_tags": family.personality_tags,
            "confidence": "auto",
            "source_url": "",
            "notes": "auto draft; tune in term_builder/character_voice_overrides.tsv",
        }

    notes = "atlas metadata; auto voice draft; tune in term_builder/character_voice_overrides.tsv"
    if atlas_metadata.class_name:
        notes = append_note(notes, f"atlas class={atlas_metadata.class_name}")
    return {
        "voice_archetype": family.archetype,
        "cv_jp": atlas_metadata.cv_jp,
        "gender": atlas_metadata.gender or family.gender,
        "personality_tags": family.personality_tags,
        "confidence": "atlas",
        "source_url": atlas_metadata.source_url,
        "notes": notes,
    }


def append_note(notes: str, extra: str) -> str:
    if not notes:
        return extra
    if extra in notes:
        return notes
    return f"{notes}; {extra}"


def contains_any(text: str, hints: tuple[str, ...]) -> bool:
    return any(hint in text for hint in hints)


def split_aliases(value: str | None) -> tuple[str, ...]:
    if not value:
        return ()
    return tuple(unique_preserve_order(part.strip() for part in ALIAS_SPLIT_RE.split(value) if part.strip()))


def merge_aliases(*alias_groups: tuple[str, ...]) -> tuple[str, ...]:
    return tuple(unique_preserve_order(alias for group in alias_groups for alias in group if alias))


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


def normalize_key(value: str) -> str:
    normalized = unicodedata.normalize("NFKC", value)
    normalized = normalized.strip().strip("「」『』[]()（）【】,，.。:：;；!！?？")
    normalized = MIDDLE_DOT_RE.sub("", normalized)
    normalized = SPACE_RE.sub("", normalized)
    return normalized


def stable_token(value: str) -> str:
    return hashlib.sha1(value.encode("utf-8")).hexdigest()[:10]


def stable_int(value: str, salt: str) -> int:
    digest = hashlib.sha1(f"{salt}:{value}".encode("utf-8")).hexdigest()
    return int(digest[:8], 16)


def nudge_pitch(base: str, delta: int) -> str:
    numeric = int(base.rstrip("%") or "0")
    numeric = min(10, max(-10, numeric + delta))
    return f"{numeric:+d}%".replace("+0%", "0%")


def nudge_rate(base: str, delta: int) -> str:
    numeric = float(base or "1.00")
    numeric = min(1.10, max(0.90, numeric + delta * 0.01))
    return f"{numeric:.2f}"


def clean(value: object) -> str:
    if value is None:
        return ""
    return str(value).strip()


def write_tsv(path: Path, header: tuple[str, ...], rows: list[dict[str, str]] | list[ProfileRow]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as file:
        writer = csv.writer(file, delimiter="\t", lineterminator="\n")
        writer.writerow(header)
        for row in rows:
            if isinstance(row, ProfileRow):
                writer.writerow(
                    (
                        row.profile_id,
                        row.provider,
                        row.locale,
                        row.voice_name,
                        row.style,
                        row.pitch,
                        row.rate,
                        row.volume,
                        row.description,
                    )
                )
            else:
                writer.writerow(tuple(row.get(column, "") for column in header))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--characters", type=Path, default=DEFAULT_CHARACTERS)
    parser.add_argument("--overrides", type=Path, default=DEFAULT_OVERRIDES)
    parser.add_argument("--atlas-servants", type=Path, default=DEFAULT_ATLAS_SERVANTS)
    parser.add_argument("--atlas-lore", type=Path, default=DEFAULT_ATLAS_LORE)
    parser.add_argument("--voice-map", type=Path, default=DEFAULT_MAP)
    parser.add_argument("--profiles", type=Path, default=DEFAULT_PROFILES)
    parser.add_argument("--review", type=Path, default=DEFAULT_REVIEW)
    parser.add_argument(
        "--fetch-atlas",
        action="store_true",
        help="Download the small Atlas JP basic_servant export before building.",
    )
    parser.add_argument(
        "--fetch-atlas-lore",
        action="store_true",
        help="Also download the large Atlas JP nice_servant_lore export to fill CV names.",
    )
    parser.add_argument("--no-atlas", action="store_true", help="Build without Atlas metadata.")
    args = parser.parse_args()

    if args.fetch_atlas or args.fetch_atlas_lore:
        download_atlas_file(ATLAS_BASIC_SERVANT_URL, args.atlas_servants)
    if args.fetch_atlas_lore:
        download_atlas_file(ATLAS_LORE_SERVANT_URL, args.atlas_lore)

    characters = read_characters(args.characters)
    overrides = read_overrides(args.overrides)
    atlas_index = {} if args.no_atlas else read_atlas_index(args.atlas_servants, args.atlas_lore)
    map_rows, profiles, review_rows = build_voice_data(characters, overrides, atlas_index)
    write_tsv(args.voice_map, MAP_HEADER, map_rows)
    write_tsv(args.profiles, PROFILE_HEADER, profiles)
    write_tsv(args.review, REVIEW_HEADER, review_rows)

    manual_count = sum(1 for row in map_rows if row["confidence"] == "manual")
    atlas_count = sum(1 for row in map_rows if row["confidence"] == "atlas")
    cv_count = sum(1 for row in map_rows if row["cv_jp"])
    print(f"Read characters: {len(characters)}")
    print(f"Read manual overrides: {len(overrides)}")
    print(f"Read Atlas name keys: {len(atlas_index)}")
    print(f"Wrote voice map rows: {len(map_rows)} ({manual_count} manual-mapped)")
    print(f"Wrote Atlas-guided rows: {atlas_count}")
    print(f"Wrote rows with CV metadata: {cv_count}")
    print(f"Wrote voice profiles: {len(profiles)}")
    print(f"Wrote review TSV: {args.review}")


if __name__ == "__main__":
    main()
