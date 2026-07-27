"""Synthesize one test line from character_voice_profiles.tsv with Azure TTS."""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

import tune_azure_voice_profile as tuner


SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parent
DEFAULT_PROFILE = ROOT / "character_voice_profiles.tsv"
DEFAULT_OUTPUT_DIR = ROOT / "voice_tuning" / "test_outputs"

DEFAULT_TEXT = {
    "ja-JP": "先輩、行きましょう。",
    "zh-CN": "前辈，我们走吧。",
}


def main() -> int:
    configure_stdio()
    args = parse_args()

    rows = tuner.read_character_voice_profiles(args.profile)
    if not rows:
        raise SystemExit(f"No voice profiles found in {args.profile}")

    row = select_row(rows, args.speaker_id)
    prefix = "cn" if args.locale == "zh-CN" else "jp"
    voice_name = row.get(f"{prefix}_voice_name", "").strip()
    if not voice_name:
        raise SystemExit(f"No {prefix}_voice_name for speaker: {row['speaker_id']}")

    candidate = tuner.Candidate(
        voice_name=voice_name,
        gender=row.get("gender", ""),
        style=row.get(f"{prefix}_style", "").strip(),
        pitch=row.get(f"{prefix}_pitch", "").strip() or "0%",
        rate=row.get(f"{prefix}_rate", "").strip() or "1.0",
    )
    text = args.text or DEFAULT_TEXT[args.locale]
    output = resolve_output(args.out, row["speaker_id"], args.locale)

    print("Selected TSV voice profile:")
    print(f"  speaker_id={row['speaker_id']}")
    print(f"  aliases={row.get('aliases', '')}")
    print(f"  locale={args.locale}")
    print(f"  voice={candidate.voice_name}")
    print(f"  style={candidate.style or '(none)'}")
    print(f"  pitch={candidate.pitch}")
    print(f"  rate={candidate.rate}")
    print(f"  text={text}")

    if args.print_ssml:
        print()
        print(tuner.build_ssml(args.locale, text, candidate))

    if args.dry_run:
        print()
        print("Dry run only. Remove --dry-run to synthesize audio.")
        return 0

    azure_key = args.azure_key or os.getenv("AZURE_SPEECH_KEY") or os.getenv("AZURE_TTS_KEY")
    azure_region = args.azure_region or os.getenv("AZURE_SPEECH_REGION") or os.getenv("AZURE_TTS_REGION")
    if not azure_key or not azure_region:
        raise SystemExit("Azure key/region required. Set AZURE_SPEECH_KEY and AZURE_SPEECH_REGION.")

    audio = tuner.synthesize_azure(azure_key, azure_region, args.locale, text, candidate)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(audio)
    print()
    print(f"Wrote test audio: {output}")
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Test one Azure voice profile from character_voice_profiles.tsv.")
    parser.add_argument("--profile", type=Path, default=DEFAULT_PROFILE)
    parser.add_argument("--speaker-id", default="", help="Speaker id or alias. Defaults to first TSV row.")
    parser.add_argument("--locale", default="ja-JP", choices=("ja-JP", "zh-CN"))
    parser.add_argument("--text", default="")
    parser.add_argument("--out", type=Path, default=None)
    parser.add_argument("--azure-key", default="")
    parser.add_argument("--azure-region", default="")
    parser.add_argument("--print-ssml", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args()


def select_row(rows: list[dict[str, str]], speaker_id: str) -> dict[str, str]:
    if not speaker_id:
        return rows[0]
    row = tuner.find_character_voice_row(rows, speaker_id)
    if row is None:
        raise SystemExit(f"No TSV row matched speaker id or alias: {speaker_id}")
    return row


def resolve_output(path: Path | None, speaker_id: str, locale: str) -> Path:
    if path is not None:
        return path
    safe_speaker = tuner.safe_filename(speaker_id)
    return DEFAULT_OUTPUT_DIR / f"{safe_speaker}_{locale}.mp3"


def configure_stdio() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8")


if __name__ == "__main__":
    raise SystemExit(main())
