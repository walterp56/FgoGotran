"""Offline general-prompt review. No HTTP client, provider SDK or credential reader."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import random
import statistics
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent
TARGETS = ["zh-Hans", "zh-Hant", "en"]
DIMENSIONS = ["meaning", "terminology", "voice", "naturalness"]
MODES = {"dialogue", "summon", "name", "choices", "dialogue_rows"}
SECRET_KEYS = {"api_key", "apikey", "authorization", "access_token", "secret", "password"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def write(path, value):
    destination = Path(path)
    require(not destination.exists(), f"Output already exists: {destination}; use a new path")
    destination.parent.mkdir(parents=True, exist_ok=True)
    # Exclusive creation also prevents overwriting a file created since the existence check.
    with destination.open("x", encoding="utf-8", newline="\n") as stream:
        stream.write(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def schema(data):
    require(isinstance(data, dict) and data.get("schema_version") == 1, "Expected schema_version=1")


def nonempty(value):
    return isinstance(value, str) and bool(value.strip())


def number(value):
    return type(value) in (int, float) and math.isfinite(value) and value >= 0


def check_cases(path):
    data = read(path)
    schema(data)
    require(data.get("scope") == "general_non_sakura", "Only general non-Sakura fixtures are supported")
    require(data.get("targets") == TARGETS, "Targets must be zh-Hans, zh-Hant and en, in that order")
    require(nonempty(data.get("provenance")), "Fixture provenance is required")
    require(isinstance(data.get("cases"), list) and data["cases"], "Cases must not be empty")
    ids = set()
    for case in data["cases"]:
        case_id = case.get("id")
        require(nonempty(case_id) and case_id not in ids, f"Invalid or duplicate case id: {case_id}")
        ids.add(case_id)
        require(case.get("mode") in MODES, f"Unknown mode in {case_id}")
        if case["mode"] in {"choices", "dialogue_rows"}:
            require(isinstance(case.get("rows"), list) and case["rows"] and
                    all(nonempty(row) for row in case["rows"]), f"Missing rows: {case_id}")
        else:
            require(nonempty(case.get("source")), f"Missing source: {case_id}")
        require(isinstance(case.get("checks"), list) and case["checks"] and
                all(nonempty(check) for check in case["checks"]), f"Missing review checks: {case_id}")
        require(case.get("player_gender", "male") in {"male", "female"}, f"Invalid player gender: {case_id}")
        for entry in case.get("glossary", []):
            require(nonempty(entry.get("source")), f"Missing glossary source: {case_id}")
            require(set(entry.get("targets", {})) == set(TARGETS) and
                    all(nonempty(v) for v in entry["targets"].values()), f"Incomplete glossary targets: {case_id}")
        for previous in case.get("previous", []):
            require(nonempty(previous.get("source")) and nonempty(previous.get("speaker")),
                    f"Incomplete previous context: {case_id}")
            require(set(previous.get("translations", {})) == set(TARGETS) and
                    all(nonempty(v) for v in previous["translations"].values()), f"Incomplete previous targets: {case_id}")
    return data


def check_evidence(path, cases):
    data = read(path)
    schema(data)
    require(data.get("scope") == "general_non_sakura", "Evidence must be general-only")
    ids = set()
    known_cases = {case["id"] for case in cases["cases"]}
    for item in data["items"]:
        require(nonempty(item.get("id")) and item["id"] not in ids, "Duplicate or missing evidence id")
        ids.add(item["id"])
        require(item.get("status") in {"needs_source_verification", "proposed", "approved", "rejected"},
                f"Invalid evidence status: {item['id']}")
        for field in ("current_artifact", "key", "applicability", "shared_impact"):
            require(nonempty(item.get(field)), f"Missing {field}: {item['id']}")
        require(item.get("targets") and set(item["targets"]) <= set(TARGETS), "Invalid evidence targets")
        refs = item.get("positive_case_ids", []) + item.get("counterexample_case_ids", [])
        require(set(refs) <= known_cases, f"Unknown evidence case: {item['id']}")
        if item["status"] in {"proposed", "approved"}:
            require(nonempty(item.get("proposed_value")), "A proposed change needs exact candidate wording")
            require(item.get("source_evidence") and all(
                nonempty(source.get("japanese")) and nonempty(source.get("locator")) and
                source.get("url", "").startswith(("https://", "http://"))
                for source in item["source_evidence"]), "A proposed change needs locatable Japanese evidence")
            require(item.get("positive_case_ids") and item.get("counterexample_case_ids"),
                    "A proposed change needs both positive and counterexample cases")
        if item["status"] == "approved":
            require(nonempty(item.get("reviewer")) and nonempty(item.get("decision")),
                    "Approval requires a reviewer and decision")
    return data


def prompt_hash(messages):
    material = "\x1e".join(message["role"] + "\x1f" + message["content"] for message in messages)
    return hashlib.sha256(material.encode("utf-8")).hexdigest()


def load_exports(paths):
    exports = {}
    for path in paths:
        data = read(path)
        schema(data)
        require(data.get("scope") == "general_non_sakura", "Export must be general-only")
        variant = data.get("variant")
        require(nonempty(variant) and variant not in exports, "Each export needs a unique variant")
        requests = {}
        for request in data["requests"]:
            require(request["id"] not in requests, "Duplicate exported request")
            messages = request["messages"]
            require([message["role"] for message in messages] == ["system", "user"], "Expected system/user pair")
            require(all(nonempty(message["content"]) for message in messages), "Empty prompt message")
            require(prompt_hash(messages) == request["prompt_sha256"], "Export prompt hash mismatch")
            require(request["target_language"] in TARGETS, "Unsupported exported target")
            requests[request["id"]] = request
        require(requests, "Export has no requests")
        exports[variant] = {"data": data, "requests": requests}
    return exports


def load_runs(path, exports):
    data = read(path)
    schema(data)
    require(isinstance(data.get("runs"), list) and data["runs"], "No recorded runs")
    ids, identities = set(), set()
    for run in data["runs"]:
        require(nonempty(run.get("id")) and run["id"] not in ids, "Duplicate or missing run id")
        ids.add(run["id"])
        export = exports.get(run.get("variant"))
        require(export is not None, f"Missing export for variant: {run.get('variant')}")
        require(data.get("fixture_sha256") == export["data"]["fixture_sha256"], "Fixture hash mismatch")
        request = export["requests"].get(run.get("request_id"))
        require(request is not None and run.get("prompt_sha256") == request["prompt_sha256"],
                f"Unknown request or mismatched prompt hash: {run['id']}")
        require(nonempty(run.get("backend")) and nonempty(run.get("model")), "Backend/model are required")
        require("sakura" not in (run["backend"] + " " + run["model"]).lower(), "Sakura runs are excluded")
        require(isinstance(run.get("parameters"), dict), "Record the effective non-secret model parameters")
        parameter_keys = {str(key).lower() for key in run["parameters"]}
        require(not (parameter_keys & SECRET_KEYS), "Do not store credentials in evaluation parameters")
        require(type(run.get("trial")) is int and run["trial"] >= 1, "Trial must be a positive integer")
        identity = (run["variant"], *pair_key(run))
        require(identity not in identities, "Duplicate case/model/parameter/trial run; increment trial")
        identities.add(identity)
        require(run.get("cache_status") in {"bypassed", "hit", "unknown"}, "Record cache_status explicitly")
        require(run.get("latency_scope") in {"backend_request_sequence", "app_end_to_end"}, "Unknown latency scope")
        attempts = run.get("attempts")
        require(isinstance(attempts, list) and attempts, "Record at least one attempt")
        for attempt in attempts:
            require(isinstance(attempt.get("response"), str), "Each attempt needs raw response content")
            for key in ("elapsed_ms", "input_tokens", "output_tokens"):
                value = attempt.get(key)
                require(value is None or number(value), f"Invalid {key}")
                if key.endswith("tokens") and value is not None:
                    require(type(value) is int, f"{key} must be an actual integer token count")
        require(run.get("end_to_end_ms") is None or number(run["end_to_end_ms"]), "Invalid end-to-end time")
    return data["runs"]


def pair_key(run):
    return (run["request_id"], run["backend"], run["model"], canonical(run["parameters"]), run["trial"],
            run["cache_status"], run["latency_scope"])


def blind_packet(runs, exports, responses_path, seed):
    ordered = list(runs)
    random.Random(seed).shuffle(ordered)
    entries, mapping, fingerprints = [], {}, {}
    for index, run in enumerate(ordered, 1):
        blind_id = f"review-{index:04d}"
        request = exports[run["variant"]]["requests"][run["request_id"]]
        entries.append({"blind_id": blind_id, "case_id": request["case_id"],
                        "target_language": request["target_language"], "mode": request["mode"],
                        "input": request["input"], "checks": request["checks"],
                        "response": run["attempts"][-1]["response"],
                        "scores": dict.fromkeys(DIMENSIONS), "critical_failure": None, "notes": ""})
        mapping[blind_id] = run["id"]
        fingerprints[blind_id] = review_content_hash(entries[-1])
    packet = {"schema_version": 1, "entries": entries}
    key = {"schema_version": 1, "responses_sha256": digest(responses_path), "mapping": mapping,
           "content_sha256": fingerprints}
    return packet, key


def review_content_hash(entry):
    immutable = {key: value for key, value in entry.items() if key not in {"scores", "critical_failure", "notes"}}
    return hashlib.sha256(canonical(immutable).encode("utf-8")).hexdigest()


def percentile(values, fraction):
    ordered = sorted(values)
    position = (len(ordered) - 1) * fraction
    lower = math.floor(position)
    upper = math.ceil(position)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def distribution(values):
    return {"n": len(values), "p50": percentile(values, .5) if values else None,
            "p95": percentile(values, .95) if values else None}


def checked_reviews(review_path, key_path, responses_path, runs):
    review, key = read(review_path), read(key_path)
    schema(review)
    schema(key)
    require(key["responses_sha256"] == digest(responses_path), "Blind key belongs to a different response file")
    require(set(key["mapping"].values()) == {run["id"] for run in runs} and
            len(set(key["mapping"].values())) == len(key["mapping"]), "Blind key must map each run exactly once")
    entries = review["entries"]
    require(len(entries) == len(key["mapping"]) and {entry["blind_id"] for entry in entries} == set(key["mapping"]),
            "Review entries must match the blind key exactly")
    result = {}
    for entry in entries:
        require(review_content_hash(entry) == key["content_sha256"].get(entry["blind_id"]),
                "Review source/response was edited; only scores, critical_failure and notes are editable")
        scores = entry["scores"]
        require(set(scores) == set(DIMENSIONS), "Unknown or missing review dimension")
        if all(value is None for value in scores.values()) and entry["critical_failure"] is None:
            continue
        require(all(type(value) is int and 0 <= value <= 4 for value in scores.values()) and
                type(entry["critical_failure"]) is bool, "Finish every score (0–4) and critical flag, or leave all pending")
        result[key["mapping"][entry["blind_id"]]] = entry
    return result


def make_report(runs, exports, validation_path, responses_path, reviews):
    validation = read(validation_path)
    schema(validation)
    require(validation["responses_sha256"] == digest(responses_path), "Contract validation belongs to different responses")
    contracts = {item["id"]: item for item in validation["runs"]}
    require(len(contracts) == len(validation["runs"]) and set(contracts) == {run["id"] for run in runs},
            "Contract validation must cover each run exactly once")
    groups = defaultdict(list)
    pairs = defaultdict(dict)
    for run in runs:
        contract = contracts[run["id"]]
        require(len(contract["attempts"]) == len(run["attempts"]) and
                all(type(item.get("valid")) is bool for item in contract["attempts"]), "Invalid attempt validation")
        require(type(contract.get("final_valid")) is bool and
                contract["final_valid"] == contract["attempts"][-1]["valid"], "Invalid final contract flag")
        request = exports[run["variant"]]["requests"][run["request_id"]]
        group = (run["variant"], run["backend"], run["model"], request["target_language"],
                 canonical(run["parameters"]), run["cache_status"], run["latency_scope"])
        groups[group].append(run)
        pairs[pair_key(run)][run["variant"]] = run
    summary = []
    for group, members in sorted(groups.items()):
        timings, tokens = [], {"input_tokens": [], "output_tokens": []}
        scored = [reviews[run["id"]] for run in members if run["id"] in reviews]
        for run in members:
            if run["latency_scope"] == "app_end_to_end":
                time = run.get("end_to_end_ms")
            else:
                durations = [attempt.get("elapsed_ms") for attempt in run["attempts"]]
                time = sum(durations) if all(value is not None for value in durations) else None
            if time is not None:
                timings.append(time)
            for name in tokens:
                counts = [attempt.get(name) for attempt in run["attempts"]]
                if all(value is not None for value in counts):
                    tokens[name].append(sum(counts))
        summary.append({"variant": group[0], "backend": group[1], "model": group[2], "target_language": group[3],
                        "parameters": json.loads(group[4]), "cache_status": group[5], "latency_scope": group[6],
                        "runs": len(members), "reviewed": len(scored), "pending": len(members) - len(scored),
                        "critical_failures": sum(item["critical_failure"] for item in scored),
                        "final_contract_failures": sum(not contracts[run["id"]]["final_valid"] for run in members),
                        "runs_with_repair": sum(len(run["attempts"]) > 1 for run in members),
                        "quality_means": {name: statistics.mean(item["scores"][name] for item in scored)
                                          if scored else None for name in DIMENSIONS},
                        "latency_ms": distribution(timings),
                        "tokens_including_repairs": {name: distribution(values) for name, values in tokens.items()}})
    variants = set(exports)
    complete = [pair for pair in pairs.values() if len(variants) >= 2 and set(pair) == variants and
                all(run["id"] in reviews for run in pair.values())]
    paired = {variant: {"critical_failures": sum(reviews[pair[variant]["id"]]["critical_failure"] for pair in complete),
                        "quality_means": {name: statistics.mean(reviews[pair[variant]["id"]]["scores"][name]
                                                                 for pair in complete) if complete else None
                                          for name in DIMENSIONS}} for variant in sorted(variants)}
    return {"schema_version": 1, "groups": summary, "fully_reviewed_matched_trials": len(complete),
            "paired_quality": paired, "automatic_winner": None,
            "limits": ["Structural validity is not translation quality.",
                       "Means for unequal case sets are descriptive; use fully matched trials for comparison.",
                       "No latency is inferred from prompt length; missing measurements remain null.",
                       "Small samples do not establish p95 or model superiority; no significance claim is made."]}


def compare_prompts(baseline, candidate):
    exports = load_exports([baseline, candidate])
    old, new = list(exports.values())
    require(old["data"]["fixture_sha256"] == new["data"]["fixture_sha256"], "Cannot compare different fixture sets")
    require(set(old["requests"]) == set(new["requests"]), "Cannot compare unequal request sets")
    rows = []
    for request_id, before in old["requests"].items():
        after = new["requests"][request_id]
        lengths = lambda request: {message["role"] + "_chars": len(message["content"]) for message in request["messages"]}
        a, b = lengths(before), lengths(after)
        a["utf8_bytes"] = sum(len(message["content"].encode("utf-8")) for message in before["messages"])
        b["utf8_bytes"] = sum(len(message["content"].encode("utf-8")) for message in after["messages"])
        rows.append({"request_id": request_id, "changed": before["prompt_sha256"] != after["prompt_sha256"],
                     "baseline": a, "candidate": b, "delta": {key: b[key] - a[key] for key in a}})
    return {"schema_version": 1, "requests": rows,
            "limits": "Characters/UTF-8 bytes are size proxies, not model tokens, prompt-noise scores, quality or latency."}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    check = commands.add_parser("check", help="Validate local fixtures and the review ledger")
    check.add_argument("--cases", type=Path, default=ROOT / "cases.json")
    check.add_argument("--evidence", type=Path, default=ROOT / "evidence.json")
    blind = commands.add_parser("blind", help="Prepare a version-hidden human review packet")
    report = commands.add_parser("report", help="Summarize recorded outputs and completed human review")
    for command in (blind, report):
        command.add_argument("--exports", type=Path, nargs="+", required=True)
        command.add_argument("--responses", type=Path, required=True)
        command.add_argument("--out", type=Path, required=True)
        command.add_argument("--key", type=Path, required=True)
    blind.add_argument("--seed", type=int, default=20261028)
    report.add_argument("--review", type=Path, required=True)
    report.add_argument("--validation", type=Path, required=True)
    compare = commands.add_parser("compare-prompts", help="Compare actual exported prompt lengths")
    compare.add_argument("--baseline", type=Path, required=True)
    compare.add_argument("--candidate", type=Path, required=True)
    compare.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "check":
            cases = check_cases(args.cases)
            evidence = check_evidence(args.evidence, cases)
            print(f"OK: {len(cases['cases'])} synthetic cases, {len(TARGETS)} targets, {len(evidence['items'])} evidence items; no API calls")
        elif args.command == "compare-prompts":
            write(args.out, compare_prompts(args.baseline, args.candidate))
        else:
            exports = load_exports(args.exports)
            runs = load_runs(args.responses, exports)
            if args.command == "blind":
                require(args.out.resolve() != args.key.resolve(), "Review packet and private key need different paths")
                require(not args.out.exists() and not args.key.exists(), "Choose unused output and key paths")
                packet, key = blind_packet(runs, exports, args.responses, args.seed)
                write(args.key, key)
                write(args.out, packet)
            else:
                reviews = checked_reviews(args.review, args.key, args.responses, runs)
                write(args.out, make_report(runs, exports, args.validation, args.responses, reviews))
        return 0
    except (ValueError, KeyError, TypeError, OSError) as error:
        print(f"Error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
