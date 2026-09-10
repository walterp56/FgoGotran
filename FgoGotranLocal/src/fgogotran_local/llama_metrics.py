from __future__ import annotations

import re


METRIC_PATTERN = re.compile(r"^([^\s{]+)(?:\{[^}]*\})?\s+(-?\d+(?:\.\d+)?(?:e[+-]?\d+)?)$", re.IGNORECASE)


def empty_metrics() -> dict:
    return {
        "promptTokensPerSecond": None,
        "predictedTokensPerSecond": None,
        "requestsProcessing": 0,
        "requestsDeferred": 0,
        "promptTokensTotal": None,
    }


def parse_prometheus_metrics(text: str) -> dict:
    values: dict[str, float] = {}
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        match = METRIC_PATTERN.fullmatch(line)
        if match:
            values[match.group(1)] = float(match.group(2))
    return {
        "promptTokensPerSecond": _metric(values, "llamacpp:prompt_tokens_seconds", "llamacpp_prompt_tokens_seconds"),
        "predictedTokensPerSecond": _metric(values, "llamacpp:predicted_tokens_seconds", "llamacpp_predicted_tokens_seconds"),
        "requestsProcessing": _metric(values, "llamacpp:requests_processing", "llamacpp_requests_processing") or 0,
        "requestsDeferred": _metric(values, "llamacpp:requests_deferred", "llamacpp_requests_deferred") or 0,
        "promptTokensTotal": _metric(values, "llamacpp:prompt_tokens_total", "llamacpp_prompt_tokens_total"),
    }


def _metric(values: dict[str, float], *names: str):
    for name in names:
        if name in values:
            return values[name]
    return None

