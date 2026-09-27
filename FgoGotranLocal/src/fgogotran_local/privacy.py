from __future__ import annotations

import copy
import re
from typing import Any
from urllib.parse import urlsplit, urlunsplit

from .i18n import DEFAULT_LOCALE, t, translate


MASKED_VALUE = "••••••"
# Default-locale spellings kept as module constants for API compatibility;
# the runtime redaction helpers below resolve them for the active locale.
MASKED_PATH = translate(DEFAULT_LOCALE, "privacy.pathHidden")
MASKED_DEVICE = translate(DEFAULT_LOCALE, "privacy.deviceHidden")

IPV4_PATTERN = re.compile(
    r"(?<![\d.])"
    r"(?:25[0-5]|2[0-4]\d|1\d{2}|[1-9]?\d)\."
    r"(?:25[0-5]|2[0-4]\d|1\d{2}|[1-9]?\d)\."
    r"(?:25[0-5]|2[0-4]\d|1\d{2}|[1-9]?\d)\."
    r"(?:25[0-5]|2[0-4]\d|1\d{2}|[1-9]?\d)"
    r"(?![\d.])"
)
IPV6_PATTERN = re.compile(
    r"(?:"
    r"\[[0-9A-Fa-f:]*:[0-9A-Fa-f:]*\]|"
    r"(?<![0-9A-Fa-f:])(?:"
    r"(?:[0-9A-Fa-f]{1,4}:){7}[0-9A-Fa-f]{1,4}|"
    r"(?:[0-9A-Fa-f]{1,4}:){0,7}::(?:[0-9A-Fa-f]{1,4}:?){0,7}"
    r")(?![0-9A-Fa-f:])"
    r")",
    re.IGNORECASE,
)
QUOTED_WINDOWS_PATH_PATTERN = re.compile(r"(?P<quote>[\"'])(?:[A-Za-z]:[\\/])[^\"'\r\n]+(?P=quote)")
UNQUOTED_WINDOWS_PATH_PATTERN = re.compile(r"(?<![\w])(?:[A-Za-z]:[\\/])[^\"'<>|\r\n]*")
UNC_PATH_PATTERN = re.compile(r"(?<!\\)\\\\[^\"'<>|\r\n]+")
UNIX_HOME_PATTERN = re.compile(r"(?<!\w)/(?:home|Users)/[^\"'\r\n]+")
DEVICE_DETAIL_LINE_PATTERN = re.compile(
    r"^.*(?:"
    r"NVIDIA\s+(?:GeForce|RTX|GTX|Quadro|Tesla)|"
    r"AMD\s+(?:Radeon|Ryzen)|"
    r"Intel(?:\(R\))?.*(?:CPU|Processor)|"
    r"Microsoft Windows\s*\[Version"
    r").*$",
    re.IGNORECASE | re.MULTILINE,
)

PATH_FIELD_NAMES = {
    "dataDirectory",
    "llamaServerPath",
    "modelsDirectory",
    "modelPath",
    "executable",
    "model_root",
    "model",
    "path",
}


def mask_endpoint(value: str | None) -> str:
    text = str(value or "").strip()
    if not text:
        return text
    try:
        parsed = urlsplit(text)
        if not parsed.scheme or not parsed.hostname:
            return redact_sensitive_text(text)
        port = f":{parsed.port}" if parsed.port is not None else ""
        return urlunsplit((parsed.scheme, f"{MASKED_VALUE}{port}", parsed.path, parsed.query, parsed.fragment))
    except ValueError:
        return redact_sensitive_text(text)


def redact_sensitive_text(value: str) -> str:
    masked_path = t("privacy.pathHidden")
    masked_device = t("privacy.deviceHidden")
    text = str(value)
    text = DEVICE_DETAIL_LINE_PATTERN.sub(masked_device, text)
    text = QUOTED_WINDOWS_PATH_PATTERN.sub(lambda match: f"{match.group('quote')}{masked_path}{match.group('quote')}", text)
    text = UNC_PATH_PATTERN.sub(masked_path, text)
    text = UNQUOTED_WINDOWS_PATH_PATTERN.sub(masked_path, text)
    text = UNIX_HOME_PATTERN.sub(masked_path, text)
    text = IPV6_PATTERN.sub(MASKED_VALUE, text)
    return IPV4_PATTERN.sub(MASKED_VALUE, text)


def redact_sensitive_payload(value: Any, *, field_name: str | None = None) -> Any:
    if isinstance(value, dict):
        return {
            key: redact_sensitive_payload(item, field_name=str(key))
            for key, item in value.items()
        }
    if isinstance(value, list):
        return [redact_sensitive_payload(item, field_name=field_name) for item in value]
    if isinstance(value, tuple):
        return tuple(redact_sensitive_payload(item, field_name=field_name) for item in value)
    if isinstance(value, str):
        if field_name in PATH_FIELD_NAMES and _looks_like_absolute_path(value):
            return t("privacy.pathHidden")
        return redact_sensitive_text(value)
    return copy.deepcopy(value)


def redact_connection(connection: dict[str, Any]) -> dict[str, Any]:
    safe = redact_sensitive_payload(connection)
    if connection.get("lanAddress"):
        safe["lanAddress"] = MASKED_VALUE
    if connection.get("endpoint"):
        safe["endpoint"] = mask_endpoint(connection["endpoint"])
    if connection.get("health"):
        safe["health"] = mask_endpoint(connection["health"])
    return safe


def _looks_like_absolute_path(value: str) -> bool:
    text = str(value or "").strip()
    return bool(re.match(r"^[A-Za-z]:[\\/]", text) or text.startswith("\\\\") or text.startswith("/"))
