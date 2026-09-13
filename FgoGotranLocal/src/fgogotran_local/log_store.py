from __future__ import annotations

import re
from collections import deque
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable

from .privacy import redact_sensitive_text


BEARER_PATTERN = re.compile(r"Bearer\s+[A-Za-z0-9._~+/=-]+", re.IGNORECASE)


class LogStore:
    def __init__(self, state_directory: Path | str, secret_provider: Callable[[], str], maximum: int = 500) -> None:
        self.state_directory = Path(state_directory).resolve()
        self.secret_provider = secret_provider
        self.entries: deque[dict] = deque(maxlen=maximum)
        self.next_id = 1

    def add(self, level: str, message: str) -> dict:
        clean_message = self._redact(str(message))
        entry = {
            "id": self.next_id,
            "timestamp": datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
            "level": level,
            "message": clean_message,
        }
        self.next_id += 1
        self.entries.append(entry)
        return entry

    def since(self, after: int | str = 0, *, include_sensitive: bool = False) -> dict:
        try:
            cursor = int(after)
        except (TypeError, ValueError):
            cursor = 0
        entries = [dict(entry) for entry in self.entries if entry["id"] > cursor]
        if not include_sensitive:
            for entry in entries:
                entry["message"] = redact_sensitive_text(entry["message"])
        return {
            "entries": entries,
            "cursor": self.entries[-1]["id"] if self.entries else cursor,
        }

    def clear_display(self) -> dict:
        self.entries.clear()
        self.add("INFO", "日志显示已清除。")
        return self.since(0)

    def formatted(self, levels: set[str] | None = None, *, include_sensitive: bool = False) -> str:
        selected = levels or {"INFO", "WARN", "ERROR"}
        lines = []
        for entry in self.entries:
            if entry["level"] not in selected:
                continue
            timestamp = entry["timestamp"].replace("T", " ").replace("Z", "")
            message = entry["message"] if include_sensitive else redact_sensitive_text(entry["message"])
            lines.append(f"{timestamp} {entry['level']:<5} {message}")
        return "\n".join(lines)

    def _redact(self, message: str) -> str:
        secret = self.secret_provider()
        if secret:
            message = message.replace(secret, "[API_KEY_REDACTED]")
        return BEARER_PATTERN.sub("Bearer [REDACTED]", message)[:2000]
