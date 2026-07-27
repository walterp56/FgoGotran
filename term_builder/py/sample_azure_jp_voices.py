"""Generate Azure Japanese voice samples for manual profile selection.

This helper is for choosing jp_voice_name/jp_style/jp_pitch/jp_rate by ear.
It writes mp3 files and an index TSV under term_builder/voice_tuning.
"""

from __future__ import annotations

import argparse
import csv
import os
import sys
import time
import urllib.error
from pathlib import Path

import tune_azure_voice_profile as tuner


DEFAULT_OUTPUT_DIR = tuner.ROOT / "voice_tuning" / "jp_voice_samples"
DEFAULT_TEXT = "先輩、行きましょう。私は全力でサポートします。"
DEFAULT_EXCLUDED_PATTERNS = ",".join(tuner.DEFAULT_EXCLUDED_VOICE_PATTERNS)
INDEX_HEADER = (
    "voice_name",
    "gender",
    "style",
    "pitch",
    "rate",
    "status",
    "file",
    "text",
)


def main() -> int:
    configure_stdio()
    args = parse_args()

    azure_key = args.azure_key or os.getenv("AZURE_SPEECH_KEY") or os.getenv("AZURE_TTS_KEY")
    azure_region = args.azure_region or os.getenv("AZURE_SPEECH_REGION") or os.getenv("AZURE_TTS_REGION")
    voices = load_voices(args, azure_key, azure_region)
    voices = filter_voices(voices, args)
    samples = build_samples(voices, args)

    if args.list_only:
        print(f"Japanese Azure voices: {len(voices)}")
        for voice in voices:
            print_voice(voice, args)
        return 0

    if not azure_key or not azure_region:
        raise SystemExit("Azure key/region required. Set AZURE_SPEECH_KEY and AZURE_SPEECH_REGION.")

    args.out.mkdir(parents=True, exist_ok=True)
    index_rows: list[dict[str, str]] = []
    print(f"Generating Japanese voice samples: {len(samples)}")
    print(f"Output: {args.out}")

    for index, candidate in enumerate(samples, start=1):
        output = sample_output_path(args.out, candidate)
        print(
            f"[{index}/{len(samples)}] {candidate.voice_name} "
            f"style={candidate.style or '-'} pitch={candidate.pitch} rate={candidate.rate}"
        )
        status = "ok"
        try:
            audio = tuner.synthesize_azure(
                azure_key=azure_key,
                azure_region=azure_region,
                locale="ja-JP",
                text=args.text,
                candidate=candidate,
            )
            output.write_bytes(audio)
        except urllib.error.HTTPError as exc:
            status = f"http_{exc.code}"
            print(f"  failed: {exc}", file=sys.stderr)
        except Exception as exc:  # noqa: BLE001 - keep sampling other voices.
            status = "failed"
            print(f"  failed: {exc}", file=sys.stderr)

        index_rows.append(index_row(candidate, status, output, args.text))
        time.sleep(max(0.0, args.delay))

    write_index(args.out / "index.tsv", index_rows)
    print(f"Wrote index TSV: {args.out / 'index.tsv'}")
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate Japanese Azure TTS samples for manual listening.")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--text", default=DEFAULT_TEXT)
    parser.add_argument("--azure-key", default="")
    parser.add_argument("--azure-region", default="")
    parser.add_argument("--gender", default="all", choices=("all", "female", "male"))
    parser.add_argument("--voice-names", default="", help="Comma-separated Azure voice ShortName values.")
    parser.add_argument("--styles", default="none", help="Comma-separated styles. Use none for no style.")
    parser.add_argument("--pitches", default="0%")
    parser.add_argument("--rates", default="1.00")
    parser.add_argument("--max-voices", type=int, default=0, help="0 means no voice limit.")
    parser.add_argument("--max-samples", type=int, default=40)
    parser.add_argument("--delay", type=float, default=0.08)
    parser.add_argument("--exclude-voice-patterns", default=DEFAULT_EXCLUDED_PATTERNS)
    parser.add_argument("--list-only", action="store_true")
    parser.add_argument("--fallback-only", action="store_true", help="Use built-in JP voice list without calling Azure list API.")
    return parser.parse_args()


def load_voices(args: argparse.Namespace, azure_key: str, azure_region: str) -> list[dict[str, object]]:
    if args.fallback_only:
        return list(tuner.FALLBACK_VOICES["ja-JP"])
    if azure_key and azure_region:
        return tuner.load_azure_voices(azure_key, azure_region, "ja-JP")
    if args.list_only:
        print("Azure key/region not set; showing built-in fallback voices only.", file=sys.stderr)
        return list(tuner.FALLBACK_VOICES["ja-JP"])
    return list(tuner.FALLBACK_VOICES["ja-JP"])


def filter_voices(voices: list[dict[str, object]], args: argparse.Namespace) -> list[dict[str, object]]:
    requested_names = set(tuner.split_csv(args.voice_names))
    excluded_patterns = tuple(tuner.split_csv(args.exclude_voice_patterns))
    requested_gender = "" if args.gender == "all" else args.gender
    selected: list[dict[str, object]] = []

    for voice in sorted(voices, key=lambda item: str(item.get("ShortName") or "")):
        voice_name = str(voice.get("ShortName") or "")
        voice_gender = tuner.normalize_gender(str(voice.get("Gender") or ""))
        if not voice_name:
            continue
        if requested_names and voice_name not in requested_names:
            continue
        if requested_gender and voice_gender != requested_gender:
            continue
        if not requested_names and tuner.voice_name_matches_patterns(voice_name, excluded_patterns):
            continue
        selected.append(voice)
        if args.max_voices > 0 and len(selected) >= args.max_voices:
            break
    return selected


def build_samples(voices: list[dict[str, object]], args: argparse.Namespace) -> list[tuner.Candidate]:
    requested_styles = parse_styles(args.styles)
    pitches = tuple(tuner.split_csv(args.pitches) or ("0%",))
    rates = tuple(tuner.split_csv(args.rates) or ("1.00",))
    samples: list[tuner.Candidate] = []

    for voice in voices:
        voice_name = str(voice.get("ShortName") or "")
        gender = str(voice.get("Gender") or "")
        supported_styles = tuple(str(style) for style in voice.get("StyleList", []) if str(style))
        styles = supported_candidate_styles(requested_styles, supported_styles)
        for style in styles:
            for pitch in pitches:
                for rate in rates:
                    samples.append(tuner.Candidate(voice_name, gender, style, pitch, rate))
                    if args.max_samples > 0 and len(samples) >= args.max_samples:
                        return samples
    return samples


def parse_styles(value: str) -> tuple[str, ...]:
    styles: list[str] = []
    for item in tuner.split_csv(value):
        normalized = item.strip()
        if normalized.lower() in {"none", "-", "blank", "default"}:
            normalized = ""
        styles.append(normalized)
    return tuple(tuner.unique_values(styles) or [""])


def supported_candidate_styles(requested_styles: tuple[str, ...], supported_styles: tuple[str, ...]) -> tuple[str, ...]:
    supported = set(supported_styles)
    styles = [style for style in requested_styles if not style or style in supported]
    return tuple(styles or [""])


def print_voice(voice: dict[str, object], args: argparse.Namespace) -> None:
    voice_name = str(voice.get("ShortName") or "")
    gender = str(voice.get("Gender") or "")
    style_list = tuple(str(style) for style in voice.get("StyleList", []) if str(style))
    styles = supported_candidate_styles(parse_styles(args.styles), style_list)
    print(f"  {voice_name}\t{gender}\tstyles={style_text(styles)}")


def style_text(styles: tuple[str, ...]) -> str:
    return ",".join(style or "(none)" for style in styles)


def sample_output_path(output_dir: Path, candidate: tuner.Candidate) -> Path:
    style = candidate.style or "none"
    raw = f"{candidate.voice_name}__style-{style}__pitch-{candidate.pitch}__rate-{candidate.rate}"
    return output_dir / f"{tuner.safe_filename(raw)}.mp3"


def index_row(candidate: tuner.Candidate, status: str, output: Path, text: str) -> dict[str, str]:
    return {
        "voice_name": candidate.voice_name,
        "gender": candidate.gender,
        "style": candidate.style,
        "pitch": candidate.pitch,
        "rate": candidate.rate,
        "status": status,
        "file": str(output),
        "text": text,
    }


def write_index(path: Path, rows: list[dict[str, str]]) -> None:
    with path.open("w", encoding="utf-8", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=INDEX_HEADER, delimiter="\t", lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def configure_stdio() -> None:
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            reconfigure(encoding="utf-8")


if __name__ == "__main__":
    raise SystemExit(main())
