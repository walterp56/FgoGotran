"""Production-built Android example requests, with advisory language checks."""
from __future__ import annotations

import json
import re
from functools import lru_cache
from importlib.resources import files

from .i18n import normalize_locale


@lru_cache(maxsize=1)
def _fixture() -> dict:
    return json.loads(files("fgogotran_local").joinpath("connection_prompts.json").read_text(encoding="utf-8"))


def connection_example(locale: str) -> dict:
    target = normalize_locale(locale)
    fixture = _fixture()
    return {
        "sourceText": fixture["sourceText"], "targetLanguage": target,
        "promptVersion": fixture["requests"][target]["promptVersion"],
        # Return fresh messages so one request cannot alter later probes.
        "messages": [dict(message) for message in fixture["requests"][target]["messages"]],
    }


def language_warning_key(content: str, target: str) -> str:
    """Script checks are hints, never an exact-answer or translation-quality score."""
    if re.search(r"[\u3041-\u3096\u30a1-\u30fa]", content):
        return "ui.test.warningJapanese"
    han = bool(re.search(r"[\u3400-\u9fff]", content))
    if target == "en" and (han or not re.search(r"[A-Za-z]", content)):
        return "ui.test.warningLanguage"
    if target != "en" and not han:
        return "ui.test.warningLanguage"
    return ""
