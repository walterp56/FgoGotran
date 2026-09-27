from __future__ import annotations

import re
from collections import deque
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable

from .i18n import t
from .privacy import redact_sensitive_text


BEARER_PATTERN = re.compile(r"Bearer\s+[A-Za-z0-9._~+/=-]+", re.IGNORECASE)


class LogStore:
    def __init__(self, state_directory: Path | str, secret_provider: Callable[[], str], maximum: int = 500) -> None:
        self.state_directory = Path(state_directory).resolve()
        self.secret_provider = secret_provider
        self.entries: deque[dict] = deque(maxlen=maximum)
        self.next_id = 1

    def add(
        self,
        level: str,
        message: str,
        *,
        key: str | None = None,
        params: dict | None = None,
    ) -> dict:
        """Append a log entry.

        ``key``/``params`` keep the message translatable at display time so a
        shared log buffer can be rendered in the viewer's language.
        """
        clean_message = self._redact(str(message))
        entry = {
            "id": self.next_id,
            "timestamp": datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
            "level": level,
            "message": clean_message,
        }
        if key:
            entry["messageKey"] = key
            if params:
                entry["messageParams"] = dict(params)
        self.next_id += 1
        self.entries.append(entry)
        return entry

    def since(self, after: int | str = 0) -> dict:
        try:
            cursor = int(after)
        except (TypeError, ValueError):
            cursor = 0
        entries = [self._localized(entry) for entry in self.entries if entry["id"] > cursor]
        return {
            "entries": entries,
            "cursor": self.entries[-1]["id"] if self.entries else cursor,
        }

    def clear_display(self) -> dict:
        self.entries.clear()
        self.add("INFO", t("log.displayCleared"), key="log.displayCleared")
        return self.since(0)

    def formatted(self, levels: set[str] | None = None) -> str:
        selected = levels or {"INFO", "WARN", "ERROR"}
        lines = []
        for entry in self.entries:
            if entry["level"] not in selected:
                continue
            timestamp = entry["timestamp"].replace("T", " ").replace("Z", "")
            lines.append(f"{timestamp} {entry['level']:<5} {self._display(entry)}")
        return "\n".join(lines)

    def _display(self, entry: dict) -> str:
        """Render an entry in the active locale (re-redacting any parameters)."""
        key = entry.get("messageKey")
        if not key:
            return entry["message"]
        return self._redact(t(key, **entry.get("messageParams", {})))

    def _localized(self, entry: dict) -> dict:
        localized = dict(entry)
        if entry.get("messageKey"):
            localized["message"] = self._display(entry)
        return localized

    def _redact(self, message: str) -> str:
        secret = self.secret_provider()
        if secret:
            message = message.replace(secret, "[API_KEY_REDACTED]")
        message = BEARER_PATTERN.sub("Bearer [REDACTED]", message)
        return redact_sensitive_text(message)[:2000]
