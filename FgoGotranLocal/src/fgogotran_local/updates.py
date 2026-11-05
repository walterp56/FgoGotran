"""Passive reminders and active version checks; website-only downloads.

Only small reminder preferences are persisted in user_data/state. Packages
are downloaded manually from the website; translation is never stopped.
"""
from __future__ import annotations

import asyncio
import json
import re
import os
import tempfile
import time
from pathlib import Path

import httpx

from .errors import StudioError
from .i18n import t, current as current_locale
from .version import VERSION_CODE, version_info

MANIFEST_URL = "https://cdn.fgogotran.com/app/android/latest/manifest.json"
DOWNLOAD_BASE_URL = "https://fgogotran.com"
MAX_MANIFEST_BYTES = 1024 * 1024
SUCCESS_COOLDOWN = 24 * 60 * 60
FAILURE_COOLDOWN = 60 * 60


def download_page_url(locale: str | None = None) -> str:
    language = locale or current_locale()
    route = {"zh-Hans": "zh-CN", "zh-Hant": "zh-TW", "en": "en"}.get(language, "zh-CN")
    return f"{DOWNLOAD_BASE_URL}/{route}/download/#local"


def parse_release(document: dict) -> dict | None:
    if not isinstance(document, dict) or type(document.get("manifestVersion")) is not int or document["manifestVersion"] != 1:
        raise ValueError("Unsupported manifest")
    value = document.get("fgogotranLocal")
    if value is None:
        return None
    if not isinstance(value, dict):
        raise ValueError("Invalid Local manifest")
    code, name = value.get("versionCode"), value.get("versionName")
    if (type(code) is not int or code < 1 or not isinstance(name, str)
        or not re.fullmatch(r"v\d+\.\d+\.\d+", name)
        or value.get("platform", "windows-x64") != "windows-x64"
        ):
        raise ValueError("Invalid Local release")
    notes, localized = value.get("changelog", []), value.get("changelogI18n", {})
    if (not isinstance(notes, list) or not isinstance(localized, dict)
        or len(localized) > 20):
        raise ValueError("Invalid release notes")
    for entries in [notes, *localized.values()]:
        if (not isinstance(entries, list) or len(entries) > 100
            or any(not isinstance(note, str) or len(note) > 4000 for note in entries)):
            raise ValueError("Invalid release notes")
    result = {"versionCode": code, "versionName": name, "changelog": notes, "changelogI18n": localized}
    date = value.get("releaseDate", "")
    if isinstance(date, str) and len(date) <= 64:
        result["releaseDate"] = date
    return result


class UpdateManager:
    def __init__(self, data_directory: Path, *, transport=None, clock=time.time) -> None:
        self.path = Path(data_directory) / "state" / "update_reminder.json"
        self.transport, self.clock = transport, clock
        self.lock = asyncio.Lock()
        self.operation: asyncio.Task | None = None
        self.record = self._load()
        self.release = self.record.get("release")
        self.checked = bool(self.record.get("lastSuccessAt"))
        self.error_key = ""

    def _load(self) -> dict:
        empty = {"version": 1}
        try:
            if self.path.stat().st_size > MAX_MANIFEST_BYTES:
                return empty
            value = json.loads(self.path.read_text(encoding="utf-8"))
            if not isinstance(value, dict) or value.get("version") != 1:
                return empty
            clean = dict(empty)
            for name in ("lastCheckAt", "lastFailedAt", "lastSuccessAt", "lastPromptedAt"):
                number = value.get(name, 0)
                if type(number) in (int, float) and 0 <= number < 1e12:
                    clean[name] = number
            for name in ("ignoredVersionCode", "lastPromptedVersionCode"):
                number = value.get(name, 0)
                if type(number) is int and 0 <= number < 2**63:
                    clean[name] = number
            if value.get("release"):
                clean["release"] = parse_release({"manifestVersion": 1, "fgogotranLocal": value["release"]})
            return clean
        except (OSError, ValueError, TypeError):
            return empty

    def _save_sync(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        descriptor, name = tempfile.mkstemp(prefix="update-reminder-", suffix=".tmp", dir=self.path.parent)
        try:
            with os.fdopen(descriptor, "w", encoding="utf-8") as output:
                json.dump(self.record, output, ensure_ascii=False)
                output.flush()
                os.fsync(output.fileno())
            # Windows scanners/readers can briefly deny rename of a freshly
            # written file. Retry the atomic replacement in this worker thread.
            for attempt in range(5):
                try:
                    os.replace(name, self.path)
                    break
                except PermissionError:
                    if attempt == 4:
                        raise
                    time.sleep(.02 * 2**attempt)
        finally:
            Path(name).unlink(missing_ok=True)

    async def _save(self) -> None:
        try:
            await asyncio.to_thread(self._save_sync)
        except OSError:
            raise StudioError(t("update.saveFailed"), 500) from None

    def snapshot(self) -> dict:
        return {"current": version_info(), "checked": self.checked,
                "available": bool(self.release and self.release["versionCode"] > VERSION_CODE),
                "release": self.release, "downloadPageUrl": download_page_url(),
                "lastCheckAt": self.record.get("lastCheckAt", 0),
                "isChecking": bool(self.operation and not self.operation.done()),
                "isError": bool(self.error_key)}

    def _within(self, name: str, duration: float) -> bool:
        timestamp = self.record.get(name, 0)
        return timestamp > 0 and 0 <= self.clock() - timestamp < duration

    def _cooldown(self) -> bool:
        if self.record.get("lastFailedAt", 0) >= self.record.get("lastCheckAt", 0) > 0:
            return self._within("lastFailedAt", FAILURE_COOLDOWN)
        return self._within("lastCheckAt", SUCCESS_COOLDOWN)

    async def check(self, *, force: bool = True) -> dict:
        async with self.lock:
            task = self.operation
            if task is None or task.done():
                if not force and self._cooldown():
                    return {**self.snapshot(), "skipped": True}
                task = self.operation = asyncio.create_task(self._fetch())
        # A cancelled browser request does not cancel another caller's check.
        success = await asyncio.shield(task)
        if not success:
            raise StudioError(t(self.error_key or "update.checkFailed"), 502)
        return {**self.snapshot(), "skipped": False}

    async def _fetch(self) -> bool:
        attempted = self.clock()
        try:
            async with httpx.AsyncClient(timeout=15, transport=self.transport) as client:
                async with client.stream("GET", MANIFEST_URL, headers={"Cache-Control": "no-cache"}) as response:
                    response.raise_for_status()
                    body = bytearray()
                    async for chunk in response.aiter_bytes():
                        body.extend(chunk)
                        if len(body) > MAX_MANIFEST_BYTES:
                            raise ValueError("Manifest too large")
            release = parse_release(json.loads(body))
        except (httpx.HTTPError, ValueError, TypeError, KeyError):
            async with self.lock:
                self.record.update(lastCheckAt=attempted, lastFailedAt=self.clock())
                self.error_key = "update.checkFailed"
                try:
                    await self._save()
                except StudioError:
                    self.error_key = "update.saveFailed"
            return False
        async with self.lock:
            self.release, self.checked, self.error_key = release, True, ""
            self.record.update(lastCheckAt=attempted, lastFailedAt=0, lastSuccessAt=self.clock(), release=release)
            try:
                await self._save()
            except StudioError:
                self.error_key = "update.saveFailed"
                return False
        return True

    async def check_for_prompt(self, *, active: bool) -> dict:
        try:
            result = await self.check(force=active)
        except StudioError:
            if active:
                raise
            return {**self.snapshot(), "showPrompt": False, "source": "passive"}
        async with self.lock:
            show = result["available"] and (active or (
                not result.get("skipped")
                and self.release["versionCode"] > self.record.get("ignoredVersionCode", 0)
                and not (self.record.get("lastPromptedVersionCode") == self.release["versionCode"]
                         and self._within("lastPromptedAt", SUCCESS_COOLDOWN))
            ))
            if show:
                self.record.update(lastPromptedAt=self.clock(), lastPromptedVersionCode=self.release["versionCode"])
                try:
                    await self._save()
                except StudioError:
                    if active:
                        raise
                    show = False
            return {**self.snapshot(), "showPrompt": bool(show), "source": "active" if active else "passive"}

    async def reminder_action(self, code: int, action: str) -> dict:
        async with self.lock:
            if (type(code) is not int or not self.release or not VERSION_CODE < code <= self.release["versionCode"]
                or action not in {"dismiss", "ignore"}):
                raise StudioError(t("update.invalidReminder"), 409)
            previous = dict(self.record)
            self.record.update(lastPromptedAt=self.clock(), lastPromptedVersionCode=code)
            if action == "ignore":
                self.record["ignoredVersionCode"] = max(code, self.record.get("ignoredVersionCode", 0))
            try:
                await self._save()
            except StudioError:
                self.record = previous
                raise
        return {"ok": True}

    async def close(self) -> None:
        if self.operation and not self.operation.done():
            self.operation.cancel()
            try:
                await self.operation
            except asyncio.CancelledError:
                pass


