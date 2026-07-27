"""
Tune one Azure TTS voice profile from local reference audio.

Input is a GPT-SoVITS-style list:

    audio_path|speaker_id|language|text

The script synthesizes Azure TTS candidates for the same text, compares simple
audio/prosody features against the reference audio, and upserts the best values
into term_builder/character_voice_profiles.tsv.

This is an automatic "closest Azure profile" tuner, not voice training.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import os
import re
import shutil
import statistics
import struct
import subprocess
import sys
import tempfile
import time
import unicodedata
import urllib.error
import urllib.request
import wave
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parent
REPO_ROOT = ROOT.parent

DEFAULT_CHARACTER_PROFILES = ROOT / "character_voice_profiles.tsv"
DEFAULT_RESULTS_DIR = ROOT / "voice_tuning" / "results"
DEFAULT_CN_CANDIDATE_TEXT = "前辈，我们走吧。|我会努力成为您的力量。|请放心交给我。"
DEFAULT_EXCLUDED_VOICE_PATTERNS = ("MAI-Voice", "Flash")
DEFAULT_WINDOWS_FFMPEG = Path(r"C:\mywork\GPT-SoVITS-v2pro-20250604-nvidia50\runtime\ffmpeg.exe")
DEFAULT_REFERENCE_SCAN_COUNT = 60

CHARACTER_PROFILE_HEADER = (
    "speaker_id",
    "aliases",
    "gender",
    "jp_voice_name",
    "jp_style",
    "jp_pitch",
    "jp_rate",
    "jp_volume",
    "cn_voice_name",
    "cn_style",
    "cn_pitch",
    "cn_rate",
    "cn_volume",
)

SCORE_HEADER = (
    "score",
    "voice_name",
    "style",
    "pitch",
    "rate",
    "gender",
    "pitch_hz",
    "chars_per_second",
    "rms_db",
    "zcr",
)

FALLBACK_VOICES = {
    "ja-JP": (
        {"ShortName": "ja-JP-NanamiNeural", "Gender": "Female", "StyleList": ["chat", "cheerful", "customerservice"]},
        {"ShortName": "ja-JP-AoiNeural", "Gender": "Female", "StyleList": []},
        {"ShortName": "ja-JP-MayuNeural", "Gender": "Female", "StyleList": []},
        {"ShortName": "ja-JP-NaokiNeural", "Gender": "Male", "StyleList": []},
        {"ShortName": "ja-JP-DaichiNeural", "Gender": "Male", "StyleList": []},
        {"ShortName": "ja-JP-KeitaNeural", "Gender": "Male", "StyleList": []},
    ),
    "zh-CN": (
        {"ShortName": "zh-CN-XiaoxiaoNeural", "Gender": "Female", "StyleList": [
            "affectionate", "angry", "calm", "chat", "cheerful", "disgruntled",
            "excited", "fearful", "gentle", "sad", "serious", "sorry", "whispering",
        ]},
        {"ShortName": "zh-CN-XiaoyiNeural", "Gender": "Female", "StyleList": [
            "affectionate", "angry", "cheerful", "disgruntled", "embarrassed",
            "fearful", "gentle", "sad", "serious",
        ]},
        {"ShortName": "zh-CN-XiaohanNeural", "Gender": "Female", "StyleList": [
            "affectionate", "angry", "calm", "cheerful", "disgruntled",
            "embarrassed", "fearful", "gentle", "sad", "serious",
        ]},
        {"ShortName": "zh-CN-XiaomoNeural", "Gender": "Female", "StyleList": [
            "affectionate", "angry", "calm", "cheerful", "depressed",
            "disgruntled", "embarrassed", "fearful", "gentle", "sad", "serious",
        ]},
        {"ShortName": "zh-CN-YunxiNeural", "Gender": "Male", "StyleList": [
            "angry", "chat", "cheerful", "depressed", "disgruntled",
            "embarrassed", "fearful", "sad", "serious",
        ]},
        {"ShortName": "zh-CN-YunyeNeural", "Gender": "Male", "StyleList": [
            "angry", "calm", "cheerful", "disgruntled", "embarrassed",
            "fearful", "sad", "serious",
        ]},
        {"ShortName": "zh-CN-YunfengNeural", "Gender": "Male", "StyleList": [
            "angry", "cheerful", "depressed", "disgruntled", "fearful", "sad", "serious",
        ]},
    ),
}

DEFAULT_PITCHES = ("-8%", "-4%", "0%", "+4%", "+8%")
DEFAULT_RATES = ("0.92", "0.96", "1.00", "1.04", "1.08")
COMMON_STYLE_PRIORITY = (
    "",
    "chat",
    "gentle",
    "calm",
    "serious",
    "cheerful",
    "sad",
    "angry",
    "disgruntled",
    "excited",
)

REJECT_TEXT_PATTERNS = (
    "チーン",
    "ゲンゲンセヨ",
    "ドカーン",
    "バキューン",
)

MIDDLE_DOT_RE = re.compile(r"[\u30FB\uFF65\u00B7\u2022\u2219]")
SPACE_RE = re.compile(r"\s+")
SPEECH_CHAR_RE = re.compile(r"[\w\u3040-\u30ff\u3400-\u9fff]", re.UNICODE)
WINDOWS_ABSOLUTE_RE = re.compile(r"^([A-Za-z]):[\\/](.*)$")


@dataclass(frozen=True)
class ListRow:
    audio_path: Path
    speaker_id: str
    language: str
    text: str


@dataclass(frozen=True)
class AudioFeatures:
    duration_s: float
    rms_db: float
    zcr: float
    pitch_hz: float
    chars_per_second: float


@dataclass(frozen=True)
class ReferenceCheck:
    scanned_rows: int
    analyzed_rows: int
    duplicate_rows: int
    unreadable_rows: int
    duration_rejects: int
    pitch_rejects: int
    outlier_rejects: int
    candidates: list[tuple[ListRow, AudioFeatures]]
    selected: list[tuple[ListRow, AudioFeatures]]


@dataclass(frozen=True)
class ReferenceVoiceGuide:
    pitch_band: str
    tempo_band: str
    energy_band: str
    variation_band: str
    pitches: tuple[str, ...]
    rates: tuple[str, ...]
    styles: tuple[str, ...]


@dataclass(frozen=True)
class Candidate:
    voice_name: str
    gender: str
    style: str
    pitch: str
    rate: str


@dataclass(frozen=True)
class CandidateResult:
    candidate: Candidate
    score: float
    features: AudioFeatures


def main() -> int:
    configure_stdio()
    args = parse_args()
    azure_key = args.azure_key or os.getenv("AZURE_SPEECH_KEY") or os.getenv("AZURE_TTS_KEY")
    azure_region = args.azure_region or os.getenv("AZURE_SPEECH_REGION") or os.getenv("AZURE_TTS_REGION")

    rows = read_gpt_sovits_list(args.list, args.audio_root)
    rows = filter_rows_by_args(rows, args)
    if not rows:
        raise SystemExit("No usable rows found for this speaker/language/text filter.")
    locales = resolve_target_locales(args.locale)

    ffmpeg = find_ffmpeg(args.ffmpeg)
    samples = select_reference_samples(rows, ffmpeg, args)
    if not samples:
        raise SystemExit("No reference samples passed audio duration/feature checks.")

    target_features = average_features([features for _, features in samples])
    print(f"Selected reference samples: {len(samples)}")
    print(format_features("Target", target_features))
    guide = build_reference_voice_guide(samples, target_features)
    print_reference_voice_guide(guide, args)

    if args.dry_run and not args.list_voices:
        for locale in locales:
            candidate_rows = build_candidate_rows(samples, args, locale)
            if candidate_rows and (locale == "zh-CN" or args.candidate_text.strip()):
                print(f"Candidate text for {locale}:")
                for row in candidate_rows:
                    print(f"  {row.text}")
        return 0

    if not azure_key or not azure_region:
        raise SystemExit("Azure key/region required. Set AZURE_SPEECH_KEY and AZURE_SPEECH_REGION.")

    speaker_id = resolve_output_speaker_id(args, rows)
    for locale in locales:
        voices = load_azure_voices(azure_key, azure_region, locale)
        if args.list_voices:
            print_voice_list(locale, voices, args, guide, target_features.pitch_hz)
            continue
        candidates = build_candidates(voices, args, guide, target_features.pitch_hz)
        if not candidates:
            raise SystemExit(f"No Azure voice candidates for locale {locale}.")
        print(f"Azure candidates to synthesize ({locale}): {len(candidates)}")

        candidate_rows = build_candidate_rows(samples, args, locale)
        if not candidate_rows:
            raise SystemExit(f"No candidate text available for locale {locale}.")

        results = score_candidates(
            candidates=candidates,
            reference=target_features,
            rows=candidate_rows,
            azure_key=azure_key,
            azure_region=azure_region,
            locale=locale,
            ffmpeg=ffmpeg,
            args=args,
        )
        if not results:
            raise SystemExit(f"No candidate could be synthesized/scored for locale {locale}.")

        best = max(results, key=lambda item: item.score)
        final_results = results
        if args.fine_search:
            best, final_results = fine_search_best_candidate(
                voices=voices,
                broad_best=best,
                reference=target_features,
                rows=candidate_rows,
                azure_key=azure_key,
                azure_region=azure_region,
                locale=locale,
                ffmpeg=ffmpeg,
                args=args,
                guide=guide,
            )

        if args.write_scores:
            score_name = f"{speaker_id}_{locale}" if len(locales) > 1 else speaker_id
            write_scores(args.results_dir, score_name, final_results)
        write_character_voice_profile(
            args.out,
            speaker_id=speaker_id,
            aliases=args.aliases,
            locale=locale,
            gender=args.gender,
            candidate=best.candidate,
        )

        print(f"Best candidate ({locale}):")
        print(
            f"  speaker_id={speaker_id} voice={best.candidate.voice_name} "
            f"style={best.candidate.style or '(none)'} pitch={best.candidate.pitch} "
            f"rate={best.candidate.rate} score={best.score:.4f}"
        )
    if args.list_voices:
        return 0
    print(f"Updated character voice profile TSV: {args.out}")
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Automatically tune one Azure voice profile from GPT-SoVITS audio/text rows."
    )
    parser.add_argument("--list", type=Path, required=True, help="GPT-SoVITS list file.")
    parser.add_argument("--speaker-id", default="", help="Only use rows with this speaker_id.")
    parser.add_argument("--language", default="JA", help="Only use rows with this list language value.")
    parser.add_argument("--audio-root", type=Path, default=None, help="Base folder for relative audio paths.")
    parser.add_argument("--aliases", default="", help="Extra alias names separated by | or comma.")
    parser.add_argument("--gender", default="", help="Optional speaker gender: female, male, neutral, unknown.")
    parser.add_argument("--locale", default="both", choices=("ja-JP", "zh-CN", "both"), help="Azure locale to tune.")
    parser.add_argument(
        "--candidate-text",
        default="",
        help="Text for Azure candidates; separate multiple lines with |. zh-CN defaults to a short Mash-style CN set.",
    )
    parser.add_argument("--out", type=Path, default=DEFAULT_CHARACTER_PROFILES)
    parser.add_argument("--results-dir", type=Path, default=DEFAULT_RESULTS_DIR)
    parser.add_argument("--azure-key", default="")
    parser.add_argument("--azure-region", default="")
    parser.add_argument("--ffmpeg", default=default_ffmpeg_arg())
    parser.add_argument("--sample-count", type=int, default=20)
    parser.add_argument(
        "--reference-scan-count",
        type=int,
        default=DEFAULT_REFERENCE_SCAN_COUNT,
        help="Local reference rows to analyze before scoring. This does not call Azure.",
    )
    parser.add_argument(
        "--reference-outlier-z",
        type=float,
        default=3.5,
        help="Robust z-score cutoff for dropping unusual reference clips before scoring.",
    )
    parser.add_argument(
        "--keep-reference-duplicates",
        action="store_true",
        help="Keep duplicate reference text instead of using one clean clip per line.",
    )
    parser.add_argument(
        "--reference-details",
        action="store_true",
        help="Print every selected reference clip after the local audio check.",
    )
    parser.add_argument("--candidate-text-count", type=int, default=1)
    parser.add_argument("--max-candidates", type=int, default=40)
    parser.add_argument(
        "--max-candidates-per-voice",
        type=int,
        default=4,
        help="Limit pitch/rate/style combinations per voice before round-robin selection. Use 0 for no per-voice limit.",
    )
    parser.add_argument("--min-duration", type=float, default=1.0)
    parser.add_argument("--max-duration", type=float, default=9.0)
    parser.add_argument("--min-text-chars", type=int, default=6)
    parser.add_argument("--max-text-chars", type=int, default=80)
    parser.add_argument("--voice-names", default="", help="Comma-separated Azure voice short names.")
    parser.add_argument(
        "--exclude-voice-patterns",
        default=",".join(DEFAULT_EXCLUDED_VOICE_PATTERNS),
        help="Comma-separated substrings to skip from Azure voice names.",
    )
    parser.add_argument("--styles", default="", help="Comma-separated styles; blank uses audio-guided style priority.")
    parser.add_argument("--pitches", default="", help="Comma-separated pitch offsets; blank uses audio-guided values.")
    parser.add_argument("--rates", default="", help="Comma-separated rate multipliers; blank uses audio-guided values.")
    parser.add_argument("--dry-run", action="store_true", help="Only select/analyze reference rows.")
    parser.add_argument("--write-scores", action="store_true", help="Write candidate score TSV for inspection.")
    parser.add_argument("--keep-audio", action="store_true", help="Keep synthesized candidate audio under results-dir.")
    parser.add_argument("--list-voices", action="store_true", help="List Azure voices after filters without synthesis.")
    parser.add_argument(
        "--fine-search",
        dest="fine_search",
        action="store_true",
        default=True,
        help="Refine the broad best voice by tuning style, pitch, and rate. Enabled by default.",
    )
    parser.add_argument("--no-fine-search", dest="fine_search", action="store_false")
    parser.add_argument("--fine-style-limit", type=int, default=4, help="Maximum styles to test for the broad best voice.")
    parser.add_argument("--fine-pitch-window", type=float, default=2.0, help="Pitch percent window around broad/fine best.")
    parser.add_argument("--fine-pitch-step", type=float, default=1.0, help="Pitch percent step for fine search.")
    parser.add_argument("--fine-rate-window", type=float, default=0.02, help="Rate multiplier window around broad/fine best.")
    parser.add_argument("--fine-rate-step", type=float, default=0.01, help="Rate multiplier step for fine search.")
    parser.add_argument("--fine-max-candidates", type=int, default=40, help="Maximum pitch/rate combinations in fine search.")
    return parser.parse_args()


def default_ffmpeg_arg() -> str:
    if DEFAULT_WINDOWS_FFMPEG.exists():
        return str(DEFAULT_WINDOWS_FFMPEG)
    return "ffmpeg"


def read_gpt_sovits_list(path: Path, audio_root: Path | None) -> list[ListRow]:
    rows: list[ListRow] = []
    with path.open("r", encoding="utf-8-sig", newline="") as file:
        for line_no, raw_line in enumerate(file, start=1):
            line = raw_line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split("|", 3)
            if len(parts) != 4:
                print(f"Skip malformed row {line_no}: expected 4 columns", file=sys.stderr)
                continue
            audio_text, speaker_id, language, text = (part.strip() for part in parts)
            audio_path = audio_path_from_list(audio_text, audio_root)
            rows.append(ListRow(audio_path, speaker_id, language, text))
    return rows


def audio_path_from_list(value: str, audio_root: Path | None) -> Path:
    windows_match = WINDOWS_ABSOLUTE_RE.match(value)
    if windows_match and os.name != "nt":
        drive = windows_match.group(1).lower()
        rest = windows_match.group(2).replace("\\", "/")
        return Path("/mnt") / drive / rest

    path = Path(value)
    if not path.is_absolute() and audio_root is not None:
        return audio_root / path
    return path


def filter_rows_by_args(rows: Iterable[ListRow], args: argparse.Namespace) -> list[ListRow]:
    result: list[ListRow] = []
    for row in rows:
        if args.speaker_id and row.speaker_id != args.speaker_id:
            continue
        if args.language and row.language.upper() != args.language.upper():
            continue
        if not is_good_text(row.text, args):
            continue
        if not row.audio_path.exists():
            continue
        result.append(row)
    return result


def resolve_target_locales(locale: str) -> tuple[str, ...]:
    if locale == "both":
        return ("ja-JP", "zh-CN")
    return (locale,)


def build_candidate_rows(
    samples: list[tuple[ListRow, AudioFeatures]],
    args: argparse.Namespace,
    locale: str,
) -> list[ListRow]:
    candidate_text = args.candidate_text.strip()
    if locale == "zh-CN" and not candidate_text:
        candidate_text = DEFAULT_CN_CANDIDATE_TEXT
    if candidate_text:
        texts = [text.strip() for text in candidate_text.split("|") if text.strip()]
        return [
            ListRow(Path(), "", locale, text)
            for text in texts[: max(1, args.candidate_text_count)]
        ]
    return [row for row, _ in samples[: args.candidate_text_count]]


def is_good_text(text: str, args: argparse.Namespace) -> bool:
    cleaned = text.strip()
    if any(pattern in cleaned for pattern in REJECT_TEXT_PATTERNS):
        return False
    count = speech_char_count(cleaned)
    if count < args.min_text_chars or count > args.max_text_chars:
        return False
    return True


def select_reference_samples(
    rows: list[ListRow],
    ffmpeg: str,
    args: argparse.Namespace,
) -> list[tuple[ListRow, AudioFeatures]]:
    check = check_reference_audio(rows, ffmpeg, args)
    print_reference_check(check, args)
    return check.selected


def check_reference_audio(
    rows: list[ListRow],
    ffmpeg: str,
    args: argparse.Namespace,
) -> ReferenceCheck:
    scan_target = max(args.sample_count, args.reference_scan_count)
    candidates: list[tuple[ListRow, AudioFeatures]] = []
    seen_text_keys: set[str] = set()
    scanned_rows = 0
    analyzed_rows = 0
    duplicate_rows = 0
    unreadable_rows = 0
    duration_rejects = 0
    pitch_rejects = 0

    for row in sorted(rows, key=row_quality_key):
        if len(candidates) >= scan_target:
            break
        scanned_rows += 1
        text_key = reference_text_key(row.text)
        if text_key and text_key in seen_text_keys and not args.keep_reference_duplicates:
            duplicate_rows += 1
            continue
        try:
            features = analyze_audio(row.audio_path, row.text, ffmpeg)
        except (OSError, subprocess.SubprocessError, wave.Error, ValueError) as exc:
            print(f"Skip unreadable audio: {row.audio_path} ({exc})", file=sys.stderr)
            unreadable_rows += 1
            continue
        analyzed_rows += 1
        if features.duration_s < args.min_duration or features.duration_s > args.max_duration:
            duration_rejects += 1
            continue
        if features.pitch_hz <= 0:
            pitch_rejects += 1
            continue
        if text_key:
            seen_text_keys.add(text_key)
        candidates.append((row, features))

    filtered, outlier_rejects = filter_reference_outliers(candidates, args)
    selected = filtered[: args.sample_count]
    return ReferenceCheck(
        scanned_rows=scanned_rows,
        analyzed_rows=analyzed_rows,
        duplicate_rows=duplicate_rows,
        unreadable_rows=unreadable_rows,
        duration_rejects=duration_rejects,
        pitch_rejects=pitch_rejects,
        outlier_rejects=outlier_rejects,
        candidates=candidates,
        selected=selected,
    )


def filter_reference_outliers(
    samples: list[tuple[ListRow, AudioFeatures]],
    args: argparse.Namespace,
) -> tuple[list[tuple[ListRow, AudioFeatures]], int]:
    if len(samples) < 6:
        return samples, 0

    duration_values = [features.duration_s for _, features in samples]
    pitch_values = [features.pitch_hz for _, features in samples]
    cps_values = [features.chars_per_second for _, features in samples]
    rms_values = [features.rms_db for _, features in samples]
    zcr_values = [features.zcr for _, features in samples]

    kept: list[tuple[ListRow, AudioFeatures]] = []
    for sample in samples:
        _, features = sample
        max_z = max(
            robust_z(features.duration_s, duration_values),
            robust_z(features.pitch_hz, pitch_values),
            robust_z(features.chars_per_second, cps_values),
            robust_z(features.rms_db, rms_values),
            robust_z(features.zcr, zcr_values),
        )
        if max_z <= args.reference_outlier_z:
            kept.append(sample)

    minimum_kept = max(3, min(args.sample_count, len(samples)) // 2)
    if len(kept) < minimum_kept:
        return samples, 0
    return kept, len(samples) - len(kept)


def robust_z(value: float, values: list[float]) -> float:
    median = statistics.median(values)
    deviations = [abs(item - median) for item in values]
    mad = statistics.median(deviations)
    if mad <= 1e-9:
        return 0.0
    return abs(value - median) / (1.4826 * mad)


def reference_text_key(text: str) -> str:
    normalized = unicodedata.normalize("NFKC", text)
    return SPACE_RE.sub("", normalized.strip())


def print_reference_check(check: ReferenceCheck, args: argparse.Namespace) -> None:
    print("Reference audio check:")
    print(
        f"  scanned={check.scanned_rows} analyzed={check.analyzed_rows} "
        f"usable={len(check.candidates)} selected={len(check.selected)}"
    )
    print(
        f"  skipped duplicate={check.duplicate_rows} unreadable={check.unreadable_rows} "
        f"duration={check.duration_rejects} pitch={check.pitch_rejects} "
        f"outlier={check.outlier_rejects}"
    )
    if check.selected:
        print(format_feature_ranges("  selected", [features for _, features in check.selected]))
    if args.reference_details:
        for row, features in check.selected:
            print(f"  {row.audio_path}\t{row.text}\t{format_features('features', features)}")


def build_reference_voice_guide(
    samples: list[tuple[ListRow, AudioFeatures]],
    target: AudioFeatures,
) -> ReferenceVoiceGuide:
    feature_rows = [features for _, features in samples]
    pitch_values = [features.pitch_hz for features in feature_rows]
    cps_values = [features.chars_per_second for features in feature_rows]
    rms_values = [features.rms_db for features in feature_rows]

    pitch_band = classify_pitch_band(target.pitch_hz)
    tempo_band = classify_tempo_band(target.chars_per_second)
    energy_band = classify_energy_band(target.rms_db)
    variation_band = classify_variation_band(pitch_values, cps_values)

    return ReferenceVoiceGuide(
        pitch_band=pitch_band,
        tempo_band=tempo_band,
        energy_band=energy_band,
        variation_band=variation_band,
        pitches=guided_pitch_values(pitch_band),
        rates=guided_rate_values(tempo_band),
        styles=guided_style_values(energy_band, variation_band),
    )


def classify_pitch_band(pitch_hz: float) -> str:
    if pitch_hz >= 250:
        return "very_high"
    if pitch_hz >= 215:
        return "high"
    if pitch_hz >= 180:
        return "mid"
    if pitch_hz >= 150:
        return "low"
    return "very_low"


def classify_tempo_band(chars_per_second: float) -> str:
    if chars_per_second < 3.7:
        return "slow"
    if chars_per_second < 4.7:
        return "relaxed"
    if chars_per_second < 5.6:
        return "normal"
    if chars_per_second < 6.5:
        return "fast"
    return "very_fast"


def classify_energy_band(rms_db: float) -> str:
    if rms_db <= -21.0:
        return "soft"
    if rms_db <= -17.0:
        return "medium"
    return "strong"


def classify_variation_band(pitch_values: list[float], cps_values: list[float]) -> str:
    pitch_median = max(statistics.median(pitch_values), 1.0)
    pitch_iqr = percentile(pitch_values, 0.75) - percentile(pitch_values, 0.25)
    cps_iqr = percentile(cps_values, 0.75) - percentile(cps_values, 0.25)
    if pitch_iqr / pitch_median >= 0.25 or cps_iqr >= 1.4:
        return "expressive"
    if pitch_iqr / pitch_median <= 0.12 and cps_iqr <= 0.7:
        return "steady"
    return "dialogue"


def guided_pitch_values(pitch_band: str) -> tuple[str, ...]:
    if pitch_band == "very_high":
        return ("+8%", "+6%", "+4%", "+10%", "+2%", "0%")
    if pitch_band == "high":
        return ("+4%", "+6%", "+2%", "+8%", "0%")
    if pitch_band == "mid":
        return ("0%", "+2%", "-2%", "+4%", "-4%")
    if pitch_band == "low":
        return ("-4%", "-6%", "-2%", "-8%", "0%")
    return ("-8%", "-10%", "-6%", "-4%", "0%")


def guided_rate_values(tempo_band: str) -> tuple[str, ...]:
    if tempo_band == "slow":
        return ("0.88", "0.90", "0.92", "0.94")
    if tempo_band == "relaxed":
        return ("0.92", "0.94", "0.96", "0.98")
    if tempo_band == "normal":
        return ("0.96", "0.98", "1.00", "0.94")
    if tempo_band == "fast":
        return ("1.00", "1.02", "1.04", "0.98")
    return ("1.04", "1.06", "1.08", "1.02")


def guided_style_values(energy_band: str, variation_band: str) -> tuple[str, ...]:
    if variation_band == "expressive":
        base = ("chat", "cheerful", "gentle", "")
    elif variation_band == "steady" and energy_band == "soft":
        base = ("gentle", "calm", "chat", "")
    elif variation_band == "steady":
        base = ("calm", "chat", "gentle", "")
    elif energy_band == "soft":
        base = ("gentle", "chat", "calm", "")
    elif energy_band == "strong":
        base = ("chat", "serious", "cheerful", "")
    else:
        base = ("chat", "gentle", "calm", "")
    return unique_values(base)


def print_reference_voice_guide(guide: ReferenceVoiceGuide, args: argparse.Namespace) -> None:
    pitches = tuple(split_csv(args.pitches)) or guide.pitches
    rates = tuple(split_csv(args.rates)) or guide.rates
    styles = tuple(split_csv(args.styles)) or guide.styles
    print("Audio-guided Azure search:")
    print(
        f"  bands pitch={guide.pitch_band} tempo={guide.tempo_band} "
        f"energy={guide.energy_band} variation={guide.variation_band}"
    )
    print(f"  pitches={','.join(pitches)} rates={','.join(rates)} styles={style_list_text(styles)}")


def percentile(values: list[float], ratio: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    position = (len(ordered) - 1) * ratio
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[int(position)]
    lower_value = ordered[lower]
    upper_value = ordered[upper]
    return lower_value + (upper_value - lower_value) * (position - lower)


def style_list_text(styles: Iterable[str]) -> str:
    return ",".join(style or "(none)" for style in styles)


def row_quality_key(row: ListRow) -> tuple[int, int, str]:
    text_len = speech_char_count(row.text)
    ideal_distance = abs(text_len - 28)
    punctuation_bonus = -sum(row.text.count(mark) for mark in ("。", "、", "！", "？", "…"))
    return (ideal_distance, punctuation_bonus, str(row.audio_path))


def analyze_audio(path: Path, text: str, ffmpeg: str) -> AudioFeatures:
    with tempfile.TemporaryDirectory(prefix="fgogotran_audio_") as tmp_dir:
        wav_path = Path(tmp_dir) / "audio.wav"
        decode_to_wav(path, wav_path, ffmpeg)
        samples, sample_rate = read_wav_mono(wav_path)
    if not samples or sample_rate <= 0:
        raise ValueError("empty audio")
    duration_s = len(samples) / sample_rate
    rms = math.sqrt(sum(sample * sample for sample in samples) / len(samples))
    rms_db = 20.0 * math.log10(max(rms, 1e-9))
    zcr = zero_crossing_rate(samples, sample_rate)
    pitch_hz = estimate_pitch(samples, sample_rate)
    chars_per_second = speech_char_count(text) / max(duration_s, 0.001)
    return AudioFeatures(duration_s, rms_db, zcr, pitch_hz, chars_per_second)


def decode_to_wav(input_path: Path, output_path: Path, ffmpeg: str) -> None:
    command = [
        ffmpeg,
        "-hide_banner",
        "-loglevel",
        "error",
        "-y",
        "-i",
        str(input_path),
        "-ac",
        "1",
        "-ar",
        "16000",
        "-sample_fmt",
        "s16",
        str(output_path),
    ]
    subprocess.run(command, check=True)


def read_wav_mono(path: Path) -> tuple[list[float], int]:
    with wave.open(str(path), "rb") as wav_file:
        channels = wav_file.getnchannels()
        width = wav_file.getsampwidth()
        sample_rate = wav_file.getframerate()
        frames = wav_file.readframes(wav_file.getnframes())
    if channels != 1 or width != 2:
        raise ValueError("expected mono 16-bit wav")
    ints = struct.unpack("<" + "h" * (len(frames) // 2), frames)
    return [value / 32768.0 for value in ints], sample_rate


def zero_crossing_rate(samples: list[float], sample_rate: int) -> float:
    crossings = 0
    previous = samples[0]
    for sample in samples[1:]:
        if (previous >= 0 > sample) or (previous < 0 <= sample):
            crossings += 1
        previous = sample
    return crossings / max(len(samples) / sample_rate, 0.001)


def estimate_pitch(samples: list[float], sample_rate: int) -> float:
    frame_size = int(sample_rate * 0.04)
    hop_size = int(sample_rate * 0.03)
    if len(samples) < frame_size:
        return 0.0

    min_lag = max(1, int(sample_rate / 500))
    max_lag = min(frame_size - 2, int(sample_rate / 70))
    pitches: list[float] = []
    frame_count = 0

    for start in range(0, len(samples) - frame_size, hop_size):
        frame_count += 1
        if frame_count % 2 == 0:
            continue
        frame = samples[start : start + frame_size]
        energy = sum(sample * sample for sample in frame) / frame_size
        if energy < 0.0002:
            continue
        lag = best_autocorrelation_lag(frame, min_lag, max_lag)
        if lag:
            pitches.append(sample_rate / lag)
        if len(pitches) >= 48:
            break

    if not pitches:
        return 0.0
    return statistics.median(pitches)


def best_autocorrelation_lag(frame: list[float], min_lag: int, max_lag: int) -> int:
    frame_mean = sum(frame) / len(frame)
    centered = [sample - frame_mean for sample in frame]
    best_lag = 0
    best_score = 0.0
    for lag in range(min_lag, max_lag + 1):
        size = len(centered) - lag
        if size <= 0:
            break
        numerator = 0.0
        left_energy = 0.0
        right_energy = 0.0
        for index in range(size):
            left = centered[index]
            right = centered[index + lag]
            numerator += left * right
            left_energy += left * left
            right_energy += right * right
        denominator = math.sqrt(left_energy * right_energy)
        if denominator <= 0:
            continue
        score = numerator / denominator
        if score > best_score:
            best_score = score
            best_lag = lag
    return best_lag if best_score >= 0.28 else 0


def average_features(features: list[AudioFeatures]) -> AudioFeatures:
    return AudioFeatures(
        duration_s=statistics.mean(item.duration_s for item in features),
        rms_db=statistics.mean(item.rms_db for item in features),
        zcr=statistics.mean(item.zcr for item in features),
        pitch_hz=statistics.median(item.pitch_hz for item in features),
        chars_per_second=statistics.mean(item.chars_per_second for item in features),
    )


def load_azure_voices(azure_key: str, azure_region: str, locale: str) -> list[dict[str, object]]:
    endpoint = f"https://{azure_region}.tts.speech.microsoft.com/cognitiveservices/voices/list"
    request = urllib.request.Request(endpoint, headers={"Ocp-Apim-Subscription-Key": azure_key})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            voices = json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
        print(f"Could not fetch Azure voice list, using fallback voices: {exc}", file=sys.stderr)
        voices = list(FALLBACK_VOICES.get(locale, ()))
    return [voice for voice in voices if str(voice.get("Locale", locale)) == locale or str(voice.get("ShortName", "")).startswith(locale)]


def build_candidates(
    voices: list[dict[str, object]],
    args: argparse.Namespace,
    guide: ReferenceVoiceGuide,
    target_pitch_hz: float,
) -> list[Candidate]:
    requested_styles = split_csv(args.styles) or list(guide.styles)
    pitches = tuple(split_csv(args.pitches) or guide.pitches or DEFAULT_PITCHES)
    rates = tuple(split_csv(args.rates) or guide.rates or DEFAULT_RATES)
    buckets: list[list[Candidate]] = []

    for voice in filter_candidate_voices(voices, args, target_pitch_hz):
        voice_name = str(voice.get("ShortName") or "")
        gender = str(voice.get("Gender") or "")
        style_list = tuple(str(style) for style in voice.get("StyleList", []) if str(style))
        styles = candidate_styles(style_list, requested_styles)
        voice_candidates = build_balanced_voice_candidates(
            voice_name,
            gender,
            styles,
            pitches,
            rates,
            target_pitch_hz,
        )
        if args.max_candidates_per_voice > 0:
            voice_candidates = voice_candidates[: args.max_candidates_per_voice]
        if voice_candidates:
            buckets.append(voice_candidates)

    return round_robin_candidates(buckets, args.max_candidates)


def build_balanced_voice_candidates(
    voice_name: str,
    gender: str,
    styles: tuple[str, ...],
    pitches: tuple[str, ...],
    rates: tuple[str, ...],
    target_pitch_hz: float,
) -> list[Candidate]:
    style_order = tuple(styles or ("",))
    pitch_order = order_pitch_values(pitches, target_pitch_hz)
    rate_order = order_rate_values(rates)
    style_rank = {style: index for index, style in enumerate(style_order)}
    pitch_rank = {pitch: index for index, pitch in enumerate(pitch_order)}
    rate_rank = {rate: index for index, rate in enumerate(rate_order)}

    candidates = [
        Candidate(voice_name, gender, style, pitch, rate)
        for style in style_order
        for pitch in pitch_order
        for rate in rate_order
    ]
    return sorted(
        candidates,
        key=lambda candidate: (
            max(style_rank[candidate.style], pitch_rank[candidate.pitch], rate_rank[candidate.rate]),
            style_rank[candidate.style] + pitch_rank[candidate.pitch] + rate_rank[candidate.rate],
            style_rank[candidate.style],
            pitch_rank[candidate.pitch],
            rate_rank[candidate.rate],
        ),
    )


def order_pitch_values(values: Iterable[str], target_pitch_hz: float) -> tuple[str, ...]:
    return tuple(unique_values(values))


def pitch_order_key(value: str, target_pitch_hz: float) -> tuple[float, float, str]:
    parsed = parse_percent(value)
    if parsed is None:
        return (999.0, 999.0, value)
    priority = coarse_pitch_priority(target_pitch_hz)
    if parsed in priority:
        return (float(priority.index(parsed)), 0.0, value)
    return (float(len(priority)), abs(parsed), value)


def coarse_pitch_priority(target_pitch_hz: float) -> tuple[float, ...]:
    if target_pitch_hz >= 205:
        return (0.0, 8.0, 4.0, -4.0, -8.0)
    if target_pitch_hz <= 165:
        return (0.0, -8.0, -4.0, 4.0, 8.0)
    return (0.0, 4.0, -4.0, 8.0, -8.0)


def order_rate_values(values: Iterable[str]) -> tuple[str, ...]:
    return tuple(unique_values(values))


def rate_order_key(value: str) -> tuple[float, float, str]:
    try:
        parsed = float(value)
    except ValueError:
        return (999.0, 999.0, value)
    priority = (1.00, 0.92, 0.96, 1.04, 1.08)
    rounded = round(parsed, 2)
    if rounded in priority:
        return (float(priority.index(rounded)), 0.0, value)
    return (float(len(priority)), abs(parsed - 1.0), value)


def round_robin_candidates(buckets: list[list[Candidate]], max_candidates: int) -> list[Candidate]:
    selected: list[Candidate] = []
    position = 0
    while len(selected) < max_candidates:
        added = False
        for bucket in buckets:
            if position < len(bucket):
                selected.append(bucket[position])
                added = True
                if len(selected) >= max_candidates:
                    break
        if not added:
            break
        position += 1
    return selected


def filter_candidate_voices(
    voices: list[dict[str, object]],
    args: argparse.Namespace,
    target_pitch_hz: float,
) -> list[dict[str, object]]:
    selected_voice_names = set(split_csv(args.voice_names))
    excluded_voice_patterns = tuple(split_csv(args.exclude_voice_patterns))
    requested_gender = normalize_gender(args.gender)
    filtered: list[dict[str, object]] = []
    for voice in sorted(voices, key=lambda item: voice_sort_key(item, target_pitch_hz)):
        voice_name = str(voice.get("ShortName") or "")
        voice_gender = normalize_gender(str(voice.get("Gender") or ""))
        if not voice_name:
            continue
        if selected_voice_names and voice_name not in selected_voice_names:
            continue
        if requested_gender and not selected_voice_names and voice_gender and voice_gender != requested_gender:
            continue
        if not selected_voice_names and voice_name_matches_patterns(voice_name, excluded_voice_patterns):
            continue
        filtered.append(voice)
    return filtered


def print_voice_list(
    locale: str,
    voices: list[dict[str, object]],
    args: argparse.Namespace,
    guide: ReferenceVoiceGuide,
    target_pitch_hz: float,
) -> None:
    filtered = filter_candidate_voices(voices, args, target_pitch_hz)
    pitches = tuple(split_csv(args.pitches) or guide.pitches or DEFAULT_PITCHES)
    rates = tuple(split_csv(args.rates) or guide.rates or DEFAULT_RATES)
    requested_styles = split_csv(args.styles) or list(guide.styles)
    print(f"Azure voices ({locale}): {len(filtered)} shown / {len(voices)} available after filters")
    if args.exclude_voice_patterns and not args.voice_names:
        print(f"  excluded patterns: {args.exclude_voice_patterns}")
    for voice in filtered:
        voice_name = str(voice.get("ShortName") or "")
        gender = str(voice.get("Gender") or "")
        style_list = tuple(str(style) for style in voice.get("StyleList", []) if str(style))
        styles = candidate_styles(style_list, requested_styles)
        raw_candidate_count = len(styles) * len(pitches) * len(rates)
        if args.max_candidates_per_voice > 0:
            candidate_count = min(raw_candidate_count, args.max_candidates_per_voice)
        else:
            candidate_count = raw_candidate_count
        style_text = ",".join(style or "(none)" for style in styles) if styles else "-"
        print(f"  {voice_name}\t{gender}\tstyles={style_text}\tcandidates={candidate_count}")


def voice_sort_key(voice: dict[str, object], target_pitch_hz: float) -> tuple[int, str]:
    gender = normalize_gender(str(voice.get("Gender") or ""))
    target_gender = ""
    if target_pitch_hz >= 205:
        target_gender = "female"
    elif target_pitch_hz <= 165:
        target_gender = "male"
    gender_mismatch = 0 if not target_gender or gender == target_gender else 1
    return (gender_mismatch, str(voice.get("ShortName") or ""))


def candidate_styles(style_list: tuple[str, ...], requested_styles: list[str]) -> tuple[str, ...]:
    supported = set(style_list)
    if requested_styles:
        return tuple(style for style in requested_styles if not style or style in supported)
    ordered = []
    for style in COMMON_STYLE_PRIORITY:
        if not style or style in supported:
            ordered.append(style)
    return tuple(ordered[:4] or ("",))


def voice_name_matches_patterns(voice_name: str, patterns: Iterable[str]) -> bool:
    normalized_name = voice_name.lower()
    return any(pattern.lower() in normalized_name for pattern in patterns if pattern)


def score_candidates(
    candidates: list[Candidate],
    reference: AudioFeatures,
    rows: list[ListRow],
    azure_key: str,
    azure_region: str,
    locale: str,
    ffmpeg: str,
    args: argparse.Namespace,
) -> list[CandidateResult]:
    results: list[CandidateResult] = []
    audio_dir = args.results_dir / "candidate_audio" if args.keep_audio else None
    if audio_dir is not None:
        audio_dir.mkdir(parents=True, exist_ok=True)
    failed_voice_names: set[str] = set()

    for index, candidate in enumerate(candidates, start=1):
        if candidate.voice_name in failed_voice_names:
            continue
        feature_rows: list[AudioFeatures] = []
        for row in rows:
            try:
                audio_bytes = synthesize_azure(
                    azure_key=azure_key,
                    azure_region=azure_region,
                    locale=locale,
                    text=row.text,
                    candidate=candidate,
                )
                feature_rows.append(analyze_synthesized_audio(audio_bytes, row.text, ffmpeg, audio_dir, candidate))
            except urllib.error.HTTPError as exc:
                if exc.code >= 500:
                    failed_voice_names.add(candidate.voice_name)
                print(f"Candidate failed {candidate.voice_name}/{candidate.style}: {exc}", file=sys.stderr)
                feature_rows = []
                break
            except Exception as exc:  # noqa: BLE001 - continue scoring other Azure candidates.
                print(f"Candidate failed {candidate.voice_name}/{candidate.style}: {exc}", file=sys.stderr)
                feature_rows = []
                break
            time.sleep(0.05)
        if not feature_rows:
            continue
        features = average_features(feature_rows)
        score = score_features(reference, features, candidate)
        results.append(CandidateResult(candidate, score, features))
        print(
            f"[{index}/{len(candidates)}] score={score:.4f} "
            f"{candidate.voice_name} style={candidate.style or '-'} "
            f"pitch={candidate.pitch} rate={candidate.rate}"
        )
    return results


def fine_search_best_candidate(
    *,
    voices: list[dict[str, object]],
    broad_best: CandidateResult,
    reference: AudioFeatures,
    rows: list[ListRow],
    azure_key: str,
    azure_region: str,
    locale: str,
    ffmpeg: str,
    args: argparse.Namespace,
    guide: ReferenceVoiceGuide,
) -> tuple[CandidateResult, list[CandidateResult]]:
    print(
        f"Fine search ({locale}) from voice={broad_best.candidate.voice_name} "
        f"style={broad_best.candidate.style or '(none)'} "
        f"pitch={broad_best.candidate.pitch} rate={broad_best.candidate.rate}"
    )

    voice = find_voice_by_name(voices, broad_best.candidate.voice_name)
    style_candidates = build_fine_style_candidates(voice, broad_best.candidate, args, guide)
    style_best = broad_best
    style_results: list[CandidateResult] = [broad_best]
    if len(style_candidates) > 1:
        print(f"Fine style candidates ({locale}): {len(style_candidates)}")
        style_results = score_candidates(
            candidates=style_candidates,
            reference=reference,
            rows=rows,
            azure_key=azure_key,
            azure_region=azure_region,
            locale=locale,
            ffmpeg=ffmpeg,
            args=args,
        )
        if style_results:
            style_best = max(style_results, key=lambda item: item.score)

    pitch_rate_candidates = build_fine_pitch_rate_candidates(style_best.candidate, args)
    if not pitch_rate_candidates:
        return style_best, style_results

    print(f"Fine pitch/rate candidates ({locale}): {len(pitch_rate_candidates)}")
    pitch_rate_results = score_candidates(
        candidates=pitch_rate_candidates,
        reference=reference,
        rows=rows,
        azure_key=azure_key,
        azure_region=azure_region,
        locale=locale,
        ffmpeg=ffmpeg,
        args=args,
    )
    if not pitch_rate_results:
        return style_best, style_results
    return max(pitch_rate_results, key=lambda item: item.score), pitch_rate_results


def find_voice_by_name(voices: list[dict[str, object]], voice_name: str) -> dict[str, object] | None:
    for voice in voices:
        if str(voice.get("ShortName") or "") == voice_name:
            return voice
    return None


def build_fine_style_candidates(
    voice: dict[str, object] | None,
    broad_candidate: Candidate,
    args: argparse.Namespace,
    guide: ReferenceVoiceGuide,
) -> list[Candidate]:
    style_list = tuple(str(style) for style in (voice or {}).get("StyleList", []) if str(style))
    requested_styles = split_csv(args.styles) or list(guide.styles)
    styles = unique_values((broad_candidate.style, *candidate_styles(style_list, requested_styles)))
    limit = max(1, args.fine_style_limit)
    styles = styles[:limit]
    return [
        Candidate(
            broad_candidate.voice_name,
            broad_candidate.gender,
            style,
            broad_candidate.pitch,
            broad_candidate.rate,
        )
        for style in styles
    ]


def build_fine_pitch_rate_candidates(candidate: Candidate, args: argparse.Namespace) -> list[Candidate]:
    pitches = fine_pitch_values(candidate.pitch, args.fine_pitch_window, args.fine_pitch_step)
    rates = fine_rate_values(candidate.rate, args.fine_rate_window, args.fine_rate_step)
    candidates = unique_candidates(
        Candidate(candidate.voice_name, candidate.gender, candidate.style, pitch, rate)
        for pitch in pitches
        for rate in rates
    )
    if args.fine_max_candidates > 0:
        return candidates[: args.fine_max_candidates]
    return candidates


def fine_pitch_values(value: str, window: float, step: float) -> list[str]:
    parsed = parse_percent(value)
    if parsed is None or window <= 0 or step <= 0:
        return [value]
    return [format_percent(item) for item in center_out_range(parsed, window, step)]


def fine_rate_values(value: str, window: float, step: float) -> list[str]:
    try:
        parsed = float(value)
    except ValueError:
        return [value]
    if window <= 0 or step <= 0:
        return [format_rate(parsed)]
    values = [max(0.50, min(1.50, item)) for item in center_out_range(parsed, window, step)]
    return [format_rate(item) for item in values]


def center_out_range(center: float, window: float, step: float) -> list[float]:
    values = [center]
    steps = max(0, int(math.floor(window / step)))
    for index in range(1, steps + 1):
        values.append(center - step * index)
        values.append(center + step * index)
    return values


def parse_percent(value: str) -> float | None:
    text = value.strip()
    if not text.endswith("%"):
        return None
    try:
        return float(text[:-1])
    except ValueError:
        return None


def format_percent(value: float) -> str:
    rounded = round(value)
    if math.isclose(value, rounded, abs_tol=0.001):
        number = str(int(rounded))
    else:
        number = f"{value:.1f}".rstrip("0").rstrip(".")
    return f"+{number}%" if not number.startswith("-") else f"{number}%"


def format_rate(value: float) -> str:
    return f"{value:.2f}"


def unique_values(values: Iterable[str]) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        if value in seen:
            continue
        seen.add(value)
        result.append(value)
    return result


def unique_candidates(candidates: Iterable[Candidate]) -> list[Candidate]:
    result: list[Candidate] = []
    seen: set[tuple[str, str, str, str]] = set()
    for candidate in candidates:
        key = (candidate.voice_name, candidate.style, candidate.pitch, candidate.rate)
        if key in seen:
            continue
        seen.add(key)
        result.append(candidate)
    return result


def synthesize_azure(
    azure_key: str,
    azure_region: str,
    locale: str,
    text: str,
    candidate: Candidate,
) -> bytes:
    endpoint = f"https://{azure_region}.tts.speech.microsoft.com/cognitiveservices/v1"
    request = urllib.request.Request(
        endpoint,
        data=build_ssml(locale, text, candidate).encode("utf-8"),
        headers={
            "Ocp-Apim-Subscription-Key": azure_key,
            "X-Microsoft-OutputFormat": "audio-24khz-48kbitrate-mono-mp3",
            "User-Agent": "FgoGotranVoiceTuner",
            "Accept": "audio/mpeg",
            "Content-Type": "application/ssml+xml",
        },
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=45) as response:
        return response.read()


def build_ssml(locale: str, text: str, candidate: Candidate) -> str:
    content = f'<prosody pitch="{candidate.pitch}" rate="{normalize_rate_for_ssml(candidate.rate)}">{build_dialogue_content(text)}</prosody>'
    namespace = ""
    if candidate.style:
        namespace = ' xmlns:mstts="https://www.w3.org/2001/mstts"'
        content = f'<mstts:express-as style="{escape_xml(candidate.style)}">{content}</mstts:express-as>'
    return (
        f'<speak version="1.0" xml:lang="{locale}"{namespace}>'
        f'<voice xml:lang="{locale}" name="{escape_xml(candidate.voice_name)}">{content}</voice>'
        f'</speak>'
    )


def build_dialogue_content(text: str) -> str:
    result: list[str] = []
    index = 0
    while index < len(text):
        char = text[index]
        if char in ("…", "⋯"):
            start = index
            while index < len(text) and text[index] in ("…", "⋯"):
                index += 1
            result.append(escape_xml(text[start:index]))
            result.append('<break time="360ms"/>')
            continue
        if char in (".", "．"):
            start = index
            while index < len(text) and text[index] in (".", "．"):
                index += 1
            result.append(escape_xml(text[start:index]))
            if index - start >= 2:
                result.append('<break time="360ms"/>')
            continue
        result.append(escape_xml(char))
        if char in ("、", ",", "，"):
            result.append('<break time="120ms"/>')
        elif char in ("。", "｡"):
            result.append('<break time="220ms"/>')
        elif char in ("！", "!", "？", "?"):
            result.append('<break time="180ms"/>')
        elif char in ("\n", "\r"):
            result.append('<break time="240ms"/>')
        index += 1
    return "".join(result)


def normalize_rate_for_ssml(rate: str) -> str:
    value = rate.strip()
    if value.endswith("%") or value in {"x-slow", "slow", "medium", "fast", "x-fast", "default"}:
        return value
    try:
        multiplier = float(value)
    except ValueError:
        return value
    percent = round((multiplier - 1.0) * 100.0)
    return f"+{percent}%" if percent >= 0 else f"{percent}%"


def analyze_synthesized_audio(
    audio_bytes: bytes,
    text: str,
    ffmpeg: str,
    audio_dir: Path | None,
    candidate: Candidate,
) -> AudioFeatures:
    if audio_dir is not None:
        audio_path = audio_dir / candidate_audio_name(candidate)
        audio_path.write_bytes(audio_bytes)
        return analyze_audio(audio_path, text, ffmpeg)
    with tempfile.TemporaryDirectory(prefix="fgogotran_azure_") as tmp_dir:
        audio_path = Path(tmp_dir) / "candidate.mp3"
        audio_path.write_bytes(audio_bytes)
        return analyze_audio(audio_path, text, ffmpeg)


def score_features(reference: AudioFeatures, candidate: AudioFeatures, voice: Candidate) -> float:
    pitch_score = ratio_score(candidate.pitch_hz, reference.pitch_hz, tolerance_ratio=1.65)
    rate_score = ratio_score(candidate.chars_per_second, reference.chars_per_second, tolerance_ratio=1.55)
    energy_score = linear_score(abs(candidate.rms_db - reference.rms_db), tolerance=18.0)
    zcr_score = ratio_score(candidate.zcr + 0.01, reference.zcr + 0.01, tolerance_ratio=1.75)
    gender_score = gender_pitch_score(voice.gender, reference.pitch_hz)
    return (
        pitch_score * 0.34
        + rate_score * 0.24
        + energy_score * 0.16
        + zcr_score * 0.10
        + gender_score * 0.16
    )


def ratio_score(value: float, target: float, tolerance_ratio: float) -> float:
    if value <= 0 or target <= 0:
        return 0.0
    distance = abs(math.log(value / target))
    tolerance = math.log(tolerance_ratio)
    return clamp(1.0 - distance / tolerance)


def linear_score(distance: float, tolerance: float) -> float:
    return clamp(1.0 - distance / tolerance)


def gender_pitch_score(gender: str, target_pitch_hz: float) -> float:
    normalized = gender.lower()
    if target_pitch_hz >= 205:
        return 1.0 if normalized == "female" else 0.45
    if target_pitch_hz <= 165:
        return 1.0 if normalized == "male" else 0.45
    return 0.8


def resolve_output_speaker_id(args: argparse.Namespace, rows: list[ListRow]) -> str:
    if args.speaker_id:
        return args.speaker_id
    speaker_ids = {row.speaker_id for row in rows if row.speaker_id}
    if len(speaker_ids) == 1:
        return next(iter(speaker_ids))
    raise SystemExit("Pass --speaker-id so the script knows which character row to update.")


def write_scores(results_dir: Path, character_name: str, results: list[CandidateResult]) -> None:
    results_dir.mkdir(parents=True, exist_ok=True)
    output = results_dir / f"{safe_filename(character_name)}_azure_scores.tsv"
    with output.open("w", encoding="utf-8", newline="") as file:
        writer = csv.writer(file, delimiter="\t", lineterminator="\n")
        writer.writerow(SCORE_HEADER)
        for result in sorted(results, key=lambda item: item.score, reverse=True):
            candidate = result.candidate
            features = result.features
            writer.writerow(
                (
                    f"{result.score:.6f}",
                    candidate.voice_name,
                    candidate.style,
                    candidate.pitch,
                    candidate.rate,
                    candidate.gender,
                    f"{features.pitch_hz:.2f}",
                    f"{features.chars_per_second:.2f}",
                    f"{features.rms_db:.2f}",
                    f"{features.zcr:.2f}",
                )
            )
    print(f"Wrote score TSV: {output}")


def write_character_voice_profile(
    output: Path,
    *,
    speaker_id: str,
    aliases: str,
    locale: str,
    gender: str,
    candidate: Candidate,
) -> None:
    rows: list[dict[str, str]] = read_character_voice_profiles(output)
    row = find_character_voice_row(rows, speaker_id)
    if row is None:
        row = {column: "" for column in CHARACTER_PROFILE_HEADER}
        row["speaker_id"] = speaker_id
        rows.append(row)

    row["aliases"] = "|".join(
        merge_aliases(
            (speaker_id,),
            split_aliases(row.get("aliases", "")),
            split_aliases(aliases),
        )
    )
    if gender:
        row["gender"] = gender
    elif not row.get("gender"):
        row["gender"] = candidate.gender.lower()

    if locale == "zh-CN":
        row["cn_voice_name"] = candidate.voice_name
        row["cn_style"] = candidate.style
        row["cn_pitch"] = candidate.pitch
        row["cn_rate"] = candidate.rate
        row["cn_volume"] = row.get("cn_volume") or "100"
    else:
        row["jp_voice_name"] = candidate.voice_name
        row["jp_style"] = candidate.style
        row["jp_pitch"] = candidate.pitch
        row["jp_rate"] = candidate.rate
        row["jp_volume"] = row.get("jp_volume") or "100"

    write_character_voice_profiles(output, rows)


def read_character_voice_profiles(path: Path) -> list[dict[str, str]]:
    if path.exists():
        if path.stat().st_size == 0:
            return []
        with path.open("r", encoding="utf-8-sig", newline="") as file:
            reader = csv.DictReader(file, delimiter="\t")
            if reader.fieldnames is None:
                return []
            if tuple(reader.fieldnames) != CHARACTER_PROFILE_HEADER:
                raise SystemExit(
                    f"{path} is not in the new character voice profile format. "
                    "Expected header: " + "\t".join(CHARACTER_PROFILE_HEADER)
                )
            return [
                {column: row.get(column, "") for column in CHARACTER_PROFILE_HEADER}
                for row in reader
                if row.get("speaker_id")
            ]
    return []


def find_character_voice_row(rows: list[dict[str, str]], speaker_id: str) -> dict[str, str] | None:
    speaker_key = normalize_key(speaker_id)
    for row in rows:
        row_keys = {normalize_key(row.get("speaker_id", ""))}
        row_keys.update(normalize_key(alias) for alias in split_aliases(row.get("aliases", "")))
        if speaker_key in row_keys:
            return row
    return None


def write_character_voice_profiles(path: Path, rows: list[dict[str, str]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=CHARACTER_PROFILE_HEADER, delimiter="\t", lineterminator="\n")
        writer.writeheader()
        for row in rows:
            writer.writerow({column: row.get(column, "") for column in CHARACTER_PROFILE_HEADER})


def find_ffmpeg(ffmpeg: str) -> str:
    resolved = shutil.which(ffmpeg)
    if not resolved:
        raise SystemExit("ffmpeg is required to read mp3/audio files. Install ffmpeg or pass --ffmpeg.")
    return resolved


def speech_char_count(text: str) -> int:
    return len(SPEECH_CHAR_RE.findall(text))


def split_csv(value: str) -> list[str]:
    return [item.strip() for item in value.split(",") if item.strip()]


def normalize_gender(value: str) -> str:
    normalized = value.strip().lower()
    if normalized in {"female", "woman", "girl", "f"}:
        return "female"
    if normalized in {"male", "man", "boy", "m"}:
        return "male"
    return ""


def split_aliases(value: str) -> tuple[str, ...]:
    if not value:
        return ()
    return tuple(item.strip() for item in re.split(r"[|,，、]+", value) if item.strip())


def merge_aliases(*alias_groups: Iterable[str]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for alias in (alias for group in alias_groups for alias in group):
        clean_alias = alias.strip()
        key = normalize_key(clean_alias)
        if not key or key in seen:
            continue
        seen.add(key)
        result.append(clean_alias)
    return result


def normalize_key(value: str) -> str:
    normalized = unicodedata.normalize("NFKC", value)
    normalized = normalized.strip().strip("「」『』[]()（）【】,，.。:：;；!！?？")
    normalized = MIDDLE_DOT_RE.sub("", normalized)
    normalized = SPACE_RE.sub("", normalized)
    return normalized


def candidate_audio_name(candidate: Candidate) -> str:
    raw = f"{candidate.voice_name}_{candidate.style or 'none'}_{candidate.pitch}_{candidate.rate}"
    return f"{safe_filename(raw)}.mp3"


def safe_filename(value: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9_.-]+", "_", value.strip())
    if cleaned:
        return cleaned[:120]
    return hashlib.sha1(value.encode("utf-8")).hexdigest()[:16]


def escape_xml(value: str) -> str:
    return (
        value.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
        .replace("'", "&apos;")
    )


def clamp(value: float) -> float:
    return max(0.0, min(1.0, value))


def format_features(label: str, features: AudioFeatures) -> str:
    return (
        f"{label}: duration={features.duration_s:.2f}s "
        f"pitch={features.pitch_hz:.1f}Hz cps={features.chars_per_second:.2f} "
        f"rms={features.rms_db:.1f}dB zcr={features.zcr:.1f}"
    )


def format_feature_ranges(label: str, features: list[AudioFeatures]) -> str:
    return (
        f"{label}: "
        f"duration={range_text([item.duration_s for item in features], '.2f')}s "
        f"pitch={range_text([item.pitch_hz for item in features], '.1f')}Hz "
        f"cps={range_text([item.chars_per_second for item in features], '.2f')} "
        f"rms={range_text([item.rms_db for item in features], '.1f')}dB "
        f"zcr={range_text([item.zcr for item in features], '.1f')}"
    )


def range_text(values: list[float], spec: str) -> str:
    if not values:
        return "-"
    return (
        f"{min(values):{spec}}..{max(values):{spec}} "
        f"median={statistics.median(values):{spec}}"
    )


def configure_stdio() -> None:
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            reconfigure(encoding="utf-8")


if __name__ == "__main__":
    raise SystemExit(main())
