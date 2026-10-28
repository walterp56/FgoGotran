"""Explicitly authorized, bounded DeepSeek first-response collector; key stays in memory."""
from __future__ import annotations

import argparse
import getpass
import json
import random
import time
import urllib.error
import urllib.request
from pathlib import Path

import evaluate as ev

ENDPOINT = "https://api.deepseek.com/v1/chat/completions"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


def post(payload, key, timeout):
    request = urllib.request.Request(ENDPOINT, data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
                                     headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
    opener = urllib.request.build_opener(NoRedirect)
    started = time.perf_counter()
    try:
        with opener.open(request, timeout=timeout) as response:
            data = json.load(response)
            return response.status, data, (time.perf_counter() - started) * 1000
    except urllib.error.HTTPError as error:
        # No raw server body, request headers, or exception repr is retained.
        return error.code, {}, (time.perf_counter() - started) * 1000
    except (urllib.error.URLError, TimeoutError, OSError):
        return 0, {}, (time.perf_counter() - started) * 1000


def collect(request, variant, model, parameters, trial, key, timeout, transport=post):
    status, data, elapsed = transport({"model": model, "messages": request["messages"], **parameters}, key, timeout)
    choices = data.get("choices") or []
    choice = choices[0] if choices else {}
    content = choice.get("message", {}).get("content")
    usage = data.get("usage") or {}
    attempt = {"response": content if isinstance(content, str) else "", "elapsed_ms": elapsed,
               "http_status": status, "finish_reason": choice.get("finish_reason"),
               "input_tokens": usage.get("prompt_tokens"), "output_tokens": usage.get("completion_tokens"),
               "provider_cache_hit_tokens": usage.get("prompt_cache_hit_tokens"),
               "provider_cache_miss_tokens": usage.get("prompt_cache_miss_tokens"),
               "reported_model": data.get("model")}
    return {"id": f"{variant}:{request['id']}:trial-{trial}", "request_id": request["id"], "variant": variant,
            "prompt_sha256": request["prompt_sha256"], "backend": "deepseek", "model": model,
            "parameters": parameters, "trial": trial, "cache_status": "bypassed",
            "latency_scope": "backend_request_sequence", "attempts": [attempt]}


def schedule(exports, trials, seed, case_ids=None):
    request_ids = set.intersection(*(set(export["requests"]) for export in exports.values()))
    ev.require(all(set(export["requests"]) == request_ids for export in exports.values()), "A/B exports must have equal request sets")
    ev.require(len({export["data"]["fixture_sha256"] for export in exports.values()}) == 1, "A/B fixtures must match")
    if case_ids:
        available = {request["case_id"] for export in exports.values() for request in export["requests"].values()}
        ev.require(set(case_ids) <= available, "Unknown selected case")
        request_ids = {key for key in request_ids if next(iter(exports.values()))["requests"][key]["case_id"] in case_ids}
    randomizer = random.Random(seed)
    jobs = []
    for trial in range(1, trials + 1):
        ordered = sorted(request_ids)
        randomizer.shuffle(ordered)
        for request_id in ordered:
            variants = list(exports)
            randomizer.shuffle(variants)
            jobs.extend((variant, exports[variant]["requests"][request_id], trial) for variant in variants)
    return jobs


def checkpoint(path, document):
    temporary = path.with_name(path.name + ".checkpoint")
    temporary.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--exports", type=Path, nargs="+", required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--model", default="deepseek-v4-flash")
    parser.add_argument("--trials", type=int, default=1)
    parser.add_argument("--max-calls", type=int, default=180)
    parser.add_argument("--timeout", type=float, default=45)
    parser.add_argument("--seed", type=int, default=20261028)
    parser.add_argument("--case-ids", nargs="+")
    args = parser.parse_args(argv)
    try:
        ev.require(args.model in {"deepseek-v4-flash", "deepseek-flash"}, "Use the App Flash model or its documented Flash alias")
        ev.require(1 <= args.trials <= 5 and 1 <= args.max_calls <= 300, "Trials/call cap exceed the bounded evaluation limits")
        ev.require(0 < args.timeout <= 120, "Timeout must be between 0 and 120 seconds")
        ev.require(not args.out.exists(), "Choose a new output path; the collector does not overwrite existing runs")
        exports = ev.load_exports(args.exports)
        jobs = schedule(exports, args.trials, args.seed, args.case_ids)
        ev.require(0 < len(jobs) <= args.max_calls, f"Planned {len(jobs)} calls exceed cap {args.max_calls}")
        key = getpass.getpass("DeepSeek test key (not saved): ").strip()
        ev.require(bool(key), "A testing key is required")
        parameters = {"max_tokens": 256, "temperature": 1.0, "thinking": {"type": "disabled"}}
        document = {"schema_version": 1, "fixture_sha256": next(iter(exports.values()))["data"]["fixture_sha256"],
                    "collector": "Explicit first-response HTTP collector; no App repair, cache, OCR or rendering simulation",
                    "endpoint": ENDPOINT, "planned_calls": len(jobs), "runs": []}
        args.out.parent.mkdir(parents=True, exist_ok=True)
        # Reserve without clobbering another process's file, then checkpoint after each call.
        with args.out.open("x", encoding="utf-8") as stream:
            stream.write(json.dumps(document))
        failures = 0
        for index, (variant, request, trial) in enumerate(jobs, 1):
            run = collect(request, variant, args.model, parameters, trial, key, args.timeout)
            document["runs"].append(run)
            checkpoint(args.out, document)
            attempt = run["attempts"][0]
            print(json.dumps({"completed": index, "planned": len(jobs), "request_id": request["id"],
                              "variant": variant, "status": attempt["http_status"],
                              "elapsed_ms": round(attempt["elapsed_ms"]), "finish_reason": attempt["finish_reason"]}), flush=True)
            failures = failures + 1 if attempt["http_status"] != 200 else 0
            ev.require(attempt["http_status"] not in {401, 402, 403}, "Authentication, balance or permission failure; stopped immediately")
            ev.require(failures < 3, "Three consecutive HTTP/network failures; stopped without automatic retries")
        key = ""
        return 0
    except (ValueError, KeyError, TypeError, OSError) as error:
        print(f"Stopped: {type(error).__name__}: {error}")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
