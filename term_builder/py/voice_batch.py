"""Clean resumable batch runner for FGO Chinese voice profile review.

This script keeps all long-run state in term_builder/voice_build_batch.
It does deterministic bookkeeping only. Codex still makes the evidence-backed
voice decisions by processing generated pack prompts.
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import shutil
import sys
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Iterable


SCRIPT_DIR = Path(__file__).resolve().parent
TERM_BUILDER = SCRIPT_DIR.parent
REPO_ROOT = TERM_BUILDER.parent

WORK_DIR = TERM_BUILDER / "voice_build_batch"
AGENT_FILE = REPO_ROOT / ".agents" / "voice-profile-builder.md"
NAME_MAP = TERM_BUILDER / "jp_cn_name_map.tsv"
PRIOR_PROFILES = TERM_BUILDER / "character_voice_profiles_cn.tsv"
FINAL_OUT = TERM_BUILDER / "character_voice_profiles_cn.tsv"

PROFILE_HEADER = (
    "speaker_id",
    "aliases",
    "voice_type",
    "cn_voice_name",
    "cn_style",
    "cn_pitch",
    "cn_rate",
    "cn_volume",
)

NEEDS_REVIEW_HEADER = (
    "speaker_id",
    "aliases",
    "voice_type",
    "candidate_cn_voice_name",
    "candidate_cn_style",
    "candidate_cn_pitch",
    "candidate_cn_rate",
    "candidate_cn_volume",
    "speaker_total_count",
    "tier",
    "review_reason",
    "suggested_action",
    "sources",
)

IDENTITY_HEADER = (
    "speaker_id",
    "aliases",
    "speaker_total_count",
    "tier",
    "identity_status",
    "prior_voice_type",
    "prior_cn_voice_name",
    "prior_cn_style",
    "prior_cn_pitch",
    "prior_cn_rate",
    "sources",
    "notes",
)

MULTI_HEADER = (
    "raw_multi_label",
    "speaker_total_count",
    "tier",
    "component_labels",
    "component_speaker_ids",
    "match_status",
    "review_reason",
    "suggested_action",
    "sources",
)

VOICE_USAGE_HEADER = ("cn_voice_name", "count", "speaker_ids")
DECISION_CACHE_HEADER = ("speaker_id", "voice_type", "cn_voice_name", "confidence", "reason", "sources")

MULTI_SPLIT_RE = re.compile(r"[＆/／]")
COLOR_TAG_RE = re.compile(r"\[[0-9A-Fa-f]{3,8}\]|\[-\]")
SPOT_RE = re.compile(r"=spot\[[^\]]+\]")
TRAILING_MARK_RE = re.compile(r"[？?。．.、,，\s]+$")
HIGH_RISK_GENERIC_RE = re.compile(r"^[？?]+$|^男$|^女$|ナレー|兵士|市民|村人|町人|民衆|群衆|観客|人々")


@dataclass(frozen=True)
class Profile:
    speaker_id: str
    aliases: str
    voice_type: str
    cn_voice_name: str
    cn_style: str
    cn_pitch: str
    cn_rate: str
    cn_volume: str


@dataclass(frozen=True)
class NameGroup:
    name: str
    total: int


def main() -> int:
    configure_stdio()
    args = parse_args()
    if args.command == "init":
        init_workspace(args)
    elif args.command == "status":
        show_status()
    elif args.command == "next-pack":
        next_pack(args)
    elif args.command == "validate":
        ok = validate(print_report=True)
        return 0 if ok else 2
    elif args.command == "final":
        build_final(args)
    else:
        raise SystemExit(f"Unknown command: {args.command}")
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Voice profile batch runner.")
    sub = parser.add_subparsers(dest="command", required=True)

    init = sub.add_parser("init", help="Reset/create term_builder/voice_build_batch.")
    init.add_argument("--batch-size", type=int, default=20)
    init.add_argument("--batches", type=int, default=1)
    init.add_argument("--name-map", type=Path, default=NAME_MAP)
    init.add_argument("--prior-profiles", type=Path, default=PRIOR_PROFILES)
    init.add_argument("--agent", type=Path, default=AGENT_FILE)
    init.add_argument("--keep", action="store_true", help="Do not reset existing voice_build_batch.")

    pack = sub.add_parser("next-pack", help="Create the next manual Codex prompt.")
    pack.add_argument("--batch-size", type=int, default=0)
    pack.add_argument("--batches", type=int, default=0)

    sub.add_parser("status", help="Show current progress.")
    sub.add_parser("validate", help="Validate checkpoint TSVs.")

    final = sub.add_parser("final", help="Build final profile TSV from approved checkpoints.")
    final.add_argument("--out", type=Path, default=FINAL_OUT)
    final.add_argument("--force", action="store_true")

    return parser.parse_args()


def init_workspace(args: argparse.Namespace) -> None:
    if WORK_DIR.exists() and not args.keep:
        assert_safe_work_dir(WORK_DIR)
        shutil.rmtree(WORK_DIR)
    (WORK_DIR / "prompts").mkdir(parents=True, exist_ok=True)
    (WORK_DIR / "logs").mkdir(parents=True, exist_ok=True)

    name_groups = read_name_groups(args.name_map)
    prior_profiles = read_profiles(args.prior_profiles)
    alias_index = build_alias_index(prior_profiles)
    identity_rows, multi_rows = build_identity(name_groups, alias_index)

    write_json(
        path("config.json"),
        {
            "created_at": now(),
            "agent": str(args.agent.resolve()),
            "name_map": str(args.name_map.resolve()),
            "prior_profiles": str(args.prior_profiles.resolve()),
            "batch_size": args.batch_size,
            "batches_per_pack": args.batches,
        },
    )
    write_tsv(path("identity_resolver.tsv"), IDENTITY_HEADER, identity_rows)
    write_tsv(path("multi_speaker_split.tsv"), MULTI_HEADER, multi_rows)
    write_tsv(path("auto_ok_high.tsv"), PROFILE_HEADER, [])
    write_tsv(path("auto_ok_low.tsv"), PROFILE_HEADER, [])
    write_tsv(path("needs_review.tsv"), NEEDS_REVIEW_HEADER, [])
    write_tsv(path("voice_usage.tsv"), VOICE_USAGE_HEADER, [])
    write_tsv(path("decision_cache.tsv"), DECISION_CACHE_HEADER, [])
    path("run_notes.md").write_text("# Run Notes\n\n", encoding="utf-8", newline="\n")
    write_progress("READY")
    print(f"Initialized: {WORK_DIR}")
    print("Next: python term_builder\\py\\voice_batch.py next-pack")


def show_status() -> None:
    require_workspace()
    identity = read_tsv(path("identity_resolver.tsv"))
    high = read_tsv(path("auto_ok_high.tsv"))
    low = read_tsv(path("auto_ok_low.tsv"))
    review = read_tsv(path("needs_review.tsv"))
    multi = read_tsv(path("multi_speaker_split.tsv"))
    pending = pending_rows()
    progress = read_json(path("progress.json"))

    print(f"workspace: {WORK_DIR}")
    print(f"status: {progress.get('status', 'UNKNOWN')}")
    print(f"identity_rows: {len(identity)}")
    print(f"auto_ok_high: {len(high)}")
    print(f"auto_ok_low: {len(low)}")
    print(f"needs_review: {len(review)}")
    print(f"multi_speaker_split: {len(multi)}")
    print(f"pending_agent_rows: {len(pending)}")
    if pending:
        print("next_pending: " + ", ".join(row["speaker_id"] for row in pending[:20]))


def next_pack(args: argparse.Namespace) -> None:
    require_workspace()
    config = read_json(path("config.json"))
    batch_size = args.batch_size or int(config.get("batch_size") or 25)
    batches = args.batches or int(config.get("batches_per_pack") or 20)
    total_limit = batch_size * batches
    pending = pending_rows()[:total_limit]
    if not pending:
        print("No pending agent rows. Run validate, then final.")
        return

    pack_no = next_pack_number()
    prompt = build_pack_prompt(pack_no, pending, batch_size, batches)
    prompt_path = WORK_DIR / "prompts" / f"pack_{pack_no:04d}.md"
    prompt_path.write_text(prompt, encoding="utf-8", newline="\n")
    write_progress("PACK_PROMPT_READY", last_prompt=str(prompt_path), pack_no=pack_no)
    print(f"Wrote: {prompt_path}")
    print("Paste this prompt into Codex.")


def validate(*, print_report: bool) -> bool:
    require_workspace()
    errors: list[str] = []
    warnings: list[str] = []
    schemas = {
        "identity_resolver.tsv": IDENTITY_HEADER,
        "multi_speaker_split.tsv": MULTI_HEADER,
        "auto_ok_high.tsv": PROFILE_HEADER,
        "auto_ok_low.tsv": PROFILE_HEADER,
        "needs_review.tsv": NEEDS_REVIEW_HEADER,
        "voice_usage.tsv": VOICE_USAGE_HEADER,
        "decision_cache.tsv": DECISION_CACHE_HEADER,
    }
    for name, header in schemas.items():
        errors.extend(validate_tsv(path(name), header))

    final_rows = read_tsv(path("auto_ok_high.tsv")) + read_tsv(path("auto_ok_low.tsv"))
    review_ids = {row.get("speaker_id", "") for row in read_tsv(path("needs_review.tsv"))}
    seen: set[str] = set()
    for row in final_rows:
        sid = row.get("speaker_id", "")
        aliases = row.get("aliases", "")
        if not sid or not row.get("voice_type") or not row.get("cn_voice_name"):
            errors.append(f"Blank required profile field: {sid or '(blank speaker_id)'}")
        if sid in seen:
            errors.append(f"Duplicate final speaker_id: {sid}")
        seen.add(sid)
        if sid in review_ids:
            errors.append(f"Speaker in both final checkpoint and needs_review: {sid}")
        if "[%1]" in sid or "[%1]" in aliases:
            errors.append(f"Placeholder in final checkpoint: {sid}")
        if has_multi_separator(sid):
            errors.append(f"Combined multi-speaker row in final checkpoint: {sid}")

    pending = pending_rows()
    if pending:
        warnings.append(f"Pending agent rows remain: {len(pending)}")

    if print_report:
        print(f"errors: {len(errors)}")
        for item in errors[:80]:
            print(f"ERROR: {item}")
        if len(errors) > 80:
            print(f"ERROR: ... {len(errors) - 80} more")
        print(f"warnings: {len(warnings)}")
        for item in warnings:
            print(f"WARNING: {item}")
    return not errors


def build_final(args: argparse.Namespace) -> None:
    ok = validate(print_report=False)
    pending = pending_rows()
    if pending and not args.force:
        raise SystemExit(f"Refusing final build: pending_agent_rows={len(pending)}")
    if not ok and not args.force:
        raise SystemExit("Refusing final build: validation failed. Run validate.")
    rows = read_tsv(path("auto_ok_high.tsv")) + read_tsv(path("auto_ok_low.tsv"))
    write_tsv(args.out.resolve(), PROFILE_HEADER, rows)
    write_progress("FINAL_WRITTEN", final_out=str(args.out.resolve()))
    print(f"Wrote final TSV: {args.out.resolve()}")
    print(f"Rows: {len(rows)}")


def build_pack_prompt(
    pack_no: int,
    rows: list[dict[str, str]],
    batch_size: int,
    batches: int,
) -> str:
    config = read_json(path("config.json"))
    mini_batches = [rows[index : index + batch_size] for index in range(0, len(rows), batch_size)]
    mini_batches = mini_batches[:batches]
    notes = safe_read_text(path("run_notes.md"), max_chars=6000)
    voice_usage = read_tsv(path("voice_usage.tsv"))[:80]
    decision_cache = read_tsv(path("decision_cache.tsv"))[:80]

    parts: list[str] = [
        f"Use {config['agent']}.",
        "",
        f"Process this voice build pack: {pack_no:04d}.",
        "Manual pack mode: process this pack completely, then stop with a compact summary.",
        "This pack contains multiple mini-batches. Do not stop after the first mini-batch.",
        "",
        "Allowed output paths only:",
        str(path("auto_ok_high.tsv")),
        str(path("auto_ok_low.tsv")),
        str(path("needs_review.tsv")),
        str(path("multi_speaker_split.tsv")),
        str(path("voice_usage.tsv")),
        str(path("decision_cache.tsv")),
        str(path("run_notes.md")),
        str(path("progress.json")),
        "",
        "Do not write final character_voice_profiles_cn.tsv.",
        "Do not generate the next pack prompt.",
        "",
        "After EACH mini-batch:",
        "1. Upsert checkpoint rows by speaker_id.",
        "2. Update voice_usage.tsv and decision_cache.tsv.",
        "3. Update run_notes.md with compact memory for future packs.",
        "4. Update progress.json with pack, mini_batch, processed count, and timestamp.",
        "5. Continue to the next mini-batch.",
        "",
        "Rules:",
        "- Goal: find a Chinese Azure voice that feels character-like for the speaker: similar in age impression, gender/voice role, personality, story role, and speaking tone.",
        "- Fail closed. Do not guess voice_type.",
        "- Rebuild the full chain fresh: identity -> personality -> voice_type -> Azure voice -> style -> pitch -> rate.",
        "- Do not use prior or past app profile voice choices as evidence or hints.",
        "- If evidence is missing, write needs_review.",
        "- Split labels with ＆, /, or ／ into component speakers; do not create combined final profile rows.",
        "- No placeholder [%1] row may enter auto_ok_high or auto_ok_low.",
        "- Do not default to zh-CN. For Tier S/A/B, compare suitable non-zh-CN Chinese-locale candidates and explain why the selected locale wins.",
        "- Track voice and locale reuse. If choices converge on repeated zh-CN voices for unrelated high-count speakers, diversify or write needs_review.",
        "",
        "Pack final response format:",
        "summary",
        "mini-batches processed",
        "auto_ok_high added/updated",
        "auto_ok_low added/updated",
        "needs_review added/updated",
        "blockers",
        "checkpoint files written",
        "",
        "Profile TSV schema:",
        "\t".join(PROFILE_HEADER),
        "",
        "Needs-review TSV schema:",
        "\t".join(NEEDS_REVIEW_HEADER),
        "",
        "Current compact memory from run_notes.md:",
        "```text",
        notes.strip(),
        "```",
        "",
        "Current voice usage preview:",
        "```tsv",
        tsv_preview(VOICE_USAGE_HEADER, voice_usage),
        "```",
        "",
        "Current decision cache preview:",
        "```tsv",
        tsv_preview(DECISION_CACHE_HEADER, decision_cache),
        "```",
    ]

    for number, batch in enumerate(mini_batches, start=1):
        parts.extend(
            [
                "",
                f"## Mini-Batch {number}/{len(mini_batches)}",
                "",
                "```tsv",
                batch_table(batch),
                "```",
            ]
        )
    return "\n".join(parts) + "\n"


def batch_table(rows: list[dict[str, str]]) -> str:
    header = (
        "speaker_id",
        "aliases",
        "speaker_total_count",
        "tier",
        "identity_status",
    )
    lines = ["\t".join(header)]
    for row in rows:
        lines.append(
            "\t".join(
                clean_tsv_field(value)
                for value in (
                    row["speaker_id"],
                    row["aliases"],
                    row["speaker_total_count"],
                    row["tier"],
                    row["identity_status"],
                )
            )
        )
    return "\n".join(lines)


def build_identity(
    name_groups: list[NameGroup],
    alias_index: dict[str, Profile],
) -> tuple[list[dict[str, str]], list[dict[str, str]]]:
    speaker_acc: dict[str, dict[str, object]] = {}
    multi_rows: list[dict[str, str]] = []

    for group in name_groups:
        if has_multi_separator(group.name):
            multi_rows.append(classify_multi(group, alias_index))
            continue

        profile = alias_index.get(clean_component(group.name))
        if profile:
            sid = profile.speaker_id
            status = "prior_candidate"
            sources = "local:prior_profiles|local:jp_cn_name_map.tsv"
        else:
            sid = group.name
            status = "generic_context_safe" if is_safe_generic(group.name, group.total) else "unknown"
            sources = "local:jp_cn_name_map.tsv"

        acc = speaker_acc.setdefault(
            sid,
            {
                "speaker_id": sid,
                "aliases": {},
                "speaker_total_count": 0,
                "identity_status": status,
                "prior_profile": profile,
                "sources": sources,
            },
        )
        aliases = acc["aliases"]
        assert isinstance(aliases, dict)
        aliases[group.name] = int(aliases.get(group.name, 0)) + group.total
        acc["speaker_total_count"] = int(acc["speaker_total_count"]) + group.total
        if acc["identity_status"] != "prior_candidate" and status == "prior_candidate":
            acc["identity_status"] = "prior_candidate"
            acc["prior_profile"] = profile
            acc["sources"] = sources

    rows: list[dict[str, str]] = []
    for acc in speaker_acc.values():
        profile = acc["prior_profile"]
        assert profile is None or isinstance(profile, Profile)
        aliases = acc["aliases"]
        assert isinstance(aliases, dict)
        total = int(acc["speaker_total_count"])
        ordered_aliases = sorted(aliases, key=lambda item: (-int(aliases[item]), item))
        rows.append(
            {
                "speaker_id": str(acc["speaker_id"]),
                "aliases": "|".join(ordered_aliases),
                "speaker_total_count": str(total),
                "tier": tier(total),
                "identity_status": str(acc["identity_status"]),
                "prior_voice_type": profile.voice_type if profile else "",
                "prior_cn_voice_name": profile.cn_voice_name if profile else "",
                "prior_cn_style": profile.cn_style if profile else "",
                "prior_cn_pitch": profile.cn_pitch if profile else "",
                "prior_cn_rate": profile.cn_rate if profile else "",
                "sources": str(acc["sources"]),
                "notes": "",
            }
        )
    rows.sort(key=lambda row: (-int(row["speaker_total_count"]), row["speaker_id"]))
    multi_rows.sort(key=lambda row: (-int(row["speaker_total_count"]), row["raw_multi_label"]))
    return rows, multi_rows


def classify_multi(group: NameGroup, alias_index: dict[str, Profile]) -> dict[str, str]:
    components = split_multi_label(group.name)
    matched: list[str] = []
    unmatched: list[str] = []
    for component in components:
        if component == "[%1]":
            matched.append("")
            unmatched.append(component)
            continue
        profile = alias_index.get(component)
        if profile:
            matched.append(profile.speaker_id)
        else:
            matched.append("")
            unmatched.append(component)
    status = "multi_speaker_split" if components and not unmatched else "multi_speaker_unmatched"
    return {
        "raw_multi_label": group.name,
        "speaker_total_count": str(group.total),
        "tier": tier(group.total),
        "component_labels": "|".join(components),
        "component_speaker_ids": "|".join(matched),
        "match_status": status,
        "review_reason": "" if status == "multi_speaker_split" else "Unmatched components: " + "|".join(unmatched),
        "suggested_action": (
            "Route to matched component profiles if app supports multi-speaker playback; do not create a combined voice profile."
            if status == "multi_speaker_split"
            else "Resolve unmatched components or leave the combined label out of final app TSV."
        ),
        "sources": "local:prior_profiles|local:jp_cn_name_map.tsv",
    }


def read_name_groups(source: Path) -> list[NameGroup]:
    totals: dict[str, int] = {}
    order: list[str] = []
    with source.open("r", encoding="utf-8-sig", newline="") as file:
        reader = csv.DictReader(file, delimiter="\t")
        if not {"jp_name", "count"}.issubset(reader.fieldnames or []):
            raise SystemExit(f"{source} must contain columns: jp_name, count")
        for row in reader:
            name = clean_name(row.get("jp_name") or "")
            if not name:
                continue
            if name not in totals:
                order.append(name)
            totals[name] = totals.get(name, 0) + parse_int(row.get("count"))
    return [NameGroup(name, totals[name]) for name in order]


def read_profiles(source: Path) -> list[Profile]:
    if not source.exists():
        return []
    rows = read_tsv(source)
    profiles: list[Profile] = []
    for row in rows:
        sid = row.get("speaker_id", "")
        if not sid:
            continue
        profiles.append(
            Profile(
                speaker_id=sid,
                aliases=row.get("aliases", ""),
                voice_type=row.get("voice_type") or row.get("gender", ""),
                cn_voice_name=row.get("cn_voice_name", ""),
                cn_style=row.get("cn_style", ""),
                cn_pitch=row.get("cn_pitch", ""),
                cn_rate=row.get("cn_rate", ""),
                cn_volume=row.get("cn_volume") or "100",
            )
        )
    return profiles


def build_alias_index(profiles: Iterable[Profile]) -> dict[str, Profile]:
    index: dict[str, Profile] = {}
    for profile in profiles:
        for alias in (profile.speaker_id, *split_aliases(profile.aliases)):
            key = clean_component(alias)
            if key and key not in index:
                index[key] = profile
    return index


def pending_rows() -> list[dict[str, str]]:
    identity = read_tsv(path("identity_resolver.tsv"))
    processed = processed_speaker_ids()
    return [row for row in identity if row.get("speaker_id", "") not in processed]


def processed_speaker_ids() -> set[str]:
    processed: set[str] = set()
    for filename in ("auto_ok_high.tsv", "auto_ok_low.tsv", "needs_review.tsv"):
        for row in read_tsv(path(filename)):
            sid = row.get("speaker_id", "")
            if sid:
                processed.add(sid)
    return processed


def next_pack_number() -> int:
    numbers: list[int] = []
    for prompt in (WORK_DIR / "prompts").glob("pack_*.md"):
        match = re.search(r"pack_(\d+)\.md$", prompt.name)
        if match:
            numbers.append(int(match.group(1)))
    return max(numbers or [0]) + 1


def tsv_preview(header: tuple[str, ...], rows: list[dict[str, str]]) -> str:
    lines = ["\t".join(header)]
    for row in rows:
        lines.append("\t".join(clean_tsv_field(row.get(column, "")) for column in header))
    return "\n".join(lines)


def validate_tsv(source: Path, header: tuple[str, ...]) -> list[str]:
    if not source.exists():
        return [f"Missing file: {source}"]
    lines = source.read_text(encoding="utf-8-sig").splitlines()
    if not lines:
        return [f"Empty file: {source}"]
    errors: list[str] = []
    actual = tuple(lines[0].split("\t"))
    if actual != header:
        errors.append(f"Bad header in {source.name}: {actual}")
    for line_no, line in enumerate(lines[1:], start=2):
        if len(line.split("\t")) != len(header):
            errors.append(f"{source.name}:{line_no} wrong column count")
    return errors


def write_progress(status: str, **extra: object) -> None:
    progress = {
        "status": status,
        "updated_at": now(),
        "identity_rows": len(read_tsv(path("identity_resolver.tsv"))) if path("identity_resolver.tsv").exists() else 0,
        "auto_ok_high": len(read_tsv(path("auto_ok_high.tsv"))) if path("auto_ok_high.tsv").exists() else 0,
        "auto_ok_low": len(read_tsv(path("auto_ok_low.tsv"))) if path("auto_ok_low.tsv").exists() else 0,
        "needs_review": len(read_tsv(path("needs_review.tsv"))) if path("needs_review.tsv").exists() else 0,
        "pending_agent_rows": len(pending_rows()) if path("identity_resolver.tsv").exists() else 0,
        **extra,
    }
    write_json(path("progress.json"), progress)


def read_tsv(source: Path) -> list[dict[str, str]]:
    if not source.exists() or source.stat().st_size == 0:
        return []
    with source.open("r", encoding="utf-8-sig", newline="") as file:
        reader = csv.DictReader(file, delimiter="\t")
        return [{key: row.get(key, "") for key in (reader.fieldnames or [])} for row in reader]


def write_tsv(target: Path, header: tuple[str, ...], rows: Iterable[dict[str, str]]) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_suffix(target.suffix + ".tmp")
    with tmp.open("w", encoding="utf-8", newline="") as file:
        writer = csv.DictWriter(file, delimiter="\t", lineterminator="\n", fieldnames=header)
        writer.writeheader()
        for row in rows:
            writer.writerow({column: clean_tsv_field(row.get(column, "")) for column in header})
    tmp.replace(target)


def read_json(source: Path) -> dict[str, object]:
    return json.loads(source.read_text(encoding="utf-8"))


def write_json(target: Path, value: dict[str, object]) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


def safe_read_text(source: Path, *, max_chars: int) -> str:
    if not source.exists():
        return ""
    return source.read_text(encoding="utf-8-sig")[:max_chars]


def split_aliases(value: str) -> list[str]:
    return [item.strip() for item in value.split("|") if item.strip()]


def split_multi_label(value: str) -> list[str]:
    return [clean_component(item) for item in MULTI_SPLIT_RE.split(value) if clean_component(item)]


def has_multi_separator(value: str) -> bool:
    return bool(MULTI_SPLIT_RE.search(value))


def clean_component(value: str) -> str:
    text = clean_name(value)
    text = COLOR_TAG_RE.sub("", text)
    text = SPOT_RE.sub("", text)
    return TRAILING_MARK_RE.sub("", text).strip()


def clean_name(value: object) -> str:
    return str(value or "").replace("\ufeff", "").strip()


def clean_tsv_field(value: object) -> str:
    return str(value or "").replace("\t", " ").replace("\r", " ").replace("\n", " ").strip()


def parse_int(value: object) -> int:
    try:
        return int(str(value or "0").strip())
    except ValueError:
        return 0


def tier(total: int) -> str:
    if total >= 5000:
        return "S"
    if total >= 1000:
        return "A"
    if total >= 300:
        return "B"
    if total >= 50:
        return "C"
    return "D"


def is_safe_generic(name: str, total: int) -> bool:
    return total < 300 and bool(HIGH_RISK_GENERIC_RE.search(name))


def path(name: str) -> Path:
    return WORK_DIR / name


def require_workspace() -> None:
    if not path("config.json").exists():
        raise SystemExit("No voice_build_batch workspace. Run init first.")


def assert_safe_work_dir(target: Path) -> None:
    resolved = target.resolve()
    expected = WORK_DIR.resolve()
    if resolved != expected:
        raise SystemExit(f"Refusing to reset unexpected path: {resolved}")


def now() -> str:
    return datetime.now().isoformat(timespec="seconds")


def configure_stdio() -> None:
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            reconfigure(encoding="utf-8")


if __name__ == "__main__":
    raise SystemExit(main())
