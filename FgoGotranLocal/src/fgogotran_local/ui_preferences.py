"""Small, persistent control-interface preferences, separate from model settings."""
from __future__ import annotations

import asyncio
import json
import os
import tempfile
import time
from pathlib import Path

from .errors import ConfigError
from .i18n import DEFAULT_LOCALE, t


LANGUAGE_PATHS = {"zh-Hans": "/", "zh-Hant": "/zh-Hant/", "en": "/en/"}
MAX_PREFERENCE_BYTES = 4096


class UiPreferences:
    def __init__(self, data_directory: Path) -> None:
        self.path = Path(data_directory) / "state" / "ui_preferences.json"
        self.language = self._load()
        self._lock = asyncio.Lock()

    @property
    def preferred_path(self) -> str:
        return LANGUAGE_PATHS.get(self.language, LANGUAGE_PATHS[DEFAULT_LOCALE])

    def _load(self) -> str:
        try:
            with self.path.open("rb") as source:
                raw = source.read(MAX_PREFERENCE_BYTES + 1)
            if len(raw) > MAX_PREFERENCE_BYTES:
                return DEFAULT_LOCALE
            value = json.loads(raw.decode("utf-8"))
            if (isinstance(value, dict) and type(value.get("version")) is int
                and value["version"] == 1 and isinstance(value.get("language"), str)
                and value["language"] in LANGUAGE_PATHS):
                return value["language"]
        except (OSError, ValueError, UnicodeError):
            pass
        return DEFAULT_LOCALE

    async def set_language(self, language: object) -> dict[str, str]:
        if not isinstance(language, str) or language not in LANGUAGE_PATHS:
            raise ConfigError(t("ui.language.invalid"))
        async with self._lock:
            try:
                await asyncio.to_thread(self._write, language)
            except OSError as error:
                raise ConfigError(t("ui.language.saveFailed"), 503) from error
            self.language = language
            return {"language": language, "path": self.preferred_path}

    def _write(self, language: str) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        handle, name = tempfile.mkstemp(prefix="ui_preferences-", suffix=".tmp", dir=self.path.parent)
        temporary = Path(name)
        try:
            with os.fdopen(handle, "w", encoding="utf-8") as destination:
                json.dump({"version": 1, "language": language}, destination, indent=2)
                destination.write("\n")
                destination.flush()
                os.fsync(destination.fileno())
            for attempt in range(3):
                try:
                    os.replace(temporary, self.path)
                    break
                except PermissionError:
                    if attempt == 2:
                        raise
                    time.sleep(0.05 * (attempt + 1))
        finally:
            try:
                temporary.unlink(missing_ok=True)
            except OSError:
                pass
