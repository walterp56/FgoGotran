from __future__ import annotations

import asyncio
import hashlib
import json
import os
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


CACHE_VERSION = 1
MAX_CACHE_ENTRIES = 32


@dataclass(frozen=True)
class ChatCompatibilityResult:
    ok: bool
    kind: str
    message: str
    latency_ms: int
    content: str = ""
    finish_reason: str = ""
    completion_tokens: int | None = None
    reasoning_content: str = ""

    @property
    def forced_off_template_conflict(self) -> bool:
        return self.kind == "empty_single_token"


def classify_chat_completion(
    status_code: int,
    payload: Any,
    *,
    latency_ms: int,
) -> ChatCompatibilityResult:
    if status_code < 200 or status_code > 299:
        return ChatCompatibilityResult(
            ok=False,
            kind="http_error",
            message=f"HTTP {status_code}",
            latency_ms=latency_ms,
        )
    if not isinstance(payload, dict):
        return ChatCompatibilityResult(
            ok=False,
            kind="invalid_json",
            message="返回内容不是 JSON 对象",
            latency_ms=latency_ms,
        )
    choices = payload.get("choices")
    if not isinstance(choices, list) or not choices or not isinstance(choices[0], dict):
        return ChatCompatibilityResult(
            ok=False,
            kind="invalid_response",
            message="缺少 choices[0]",
            latency_ms=latency_ms,
        )
    choice = choices[0]
    message = choice.get("message")
    if not isinstance(message, dict):
        return ChatCompatibilityResult(
            ok=False,
            kind="invalid_response",
            message="缺少 assistant message",
            latency_ms=latency_ms,
        )
    content_value = message.get("content")
    content = content_value.strip() if isinstance(content_value, str) else ""
    reasoning_value = message.get("reasoning_content")
    reasoning_content = reasoning_value.strip() if isinstance(reasoning_value, str) else ""
    finish_reason_value = choice.get("finish_reason")
    finish_reason = finish_reason_value.strip().lower() if isinstance(finish_reason_value, str) else ""
    usage = payload.get("usage")
    token_value = usage.get("completion_tokens") if isinstance(usage, dict) else None
    completion_tokens = token_value if isinstance(token_value, int) and not isinstance(token_value, bool) else None

    if content:
        return ChatCompatibilityResult(
            ok=True,
            kind="ok",
            message="Chat Completions 返回了文本内容",
            latency_ms=latency_ms,
            content=content,
            finish_reason=finish_reason,
            completion_tokens=completion_tokens,
            reasoning_content=reasoning_content,
        )
    if finish_reason in {"stop", "eos"} and completion_tokens is not None and completion_tokens <= 1:
        return ChatCompatibilityResult(
            ok=False,
            kind="empty_single_token",
            message="模型仅生成结束 token，未返回译文",
            latency_ms=latency_ms,
            finish_reason=finish_reason,
            completion_tokens=completion_tokens,
            reasoning_content=reasoning_content,
        )
    if reasoning_content:
        return ChatCompatibilityResult(
            ok=False,
            kind="reasoning_without_content",
            message="模型只返回思考内容，没有最终译文",
            latency_ms=latency_ms,
            finish_reason=finish_reason,
            completion_tokens=completion_tokens,
            reasoning_content=reasoning_content,
        )
    return ChatCompatibilityResult(
        ok=False,
        kind="empty_content",
        message="Chat Completions 没有返回文本内容",
        latency_ms=latency_ms,
        finish_reason=finish_reason,
        completion_tokens=completion_tokens,
    )


class ThinkingCompatibilityCache:
    """Small local cache; contains model/runtime fingerprints only, never API keys."""

    def __init__(self, state_directory: Path | str) -> None:
        self.path = Path(state_directory).resolve() / "thinking-compatibility.json"
        self._lock = asyncio.Lock()

    async def get(self, runtime: dict[str, Any], control: str) -> bool | None:
        try:
            key = await asyncio.to_thread(_runtime_fingerprint, runtime, control)
        except (KeyError, OSError, TypeError, ValueError):
            return None
        async with self._lock:
            document = await asyncio.to_thread(self._read)
            entry = document["entries"].get(key)
            supported = entry.get("forceOffSupported") if isinstance(entry, dict) else None
            return supported if isinstance(supported, bool) else None

    async def put(self, runtime: dict[str, Any], control: str, supported: bool) -> None:
        try:
            key = await asyncio.to_thread(_runtime_fingerprint, runtime, control)
        except (KeyError, OSError, TypeError, ValueError):
            return
        async with self._lock:
            document = await asyncio.to_thread(self._read)
            entries = document["entries"]
            entries[key] = {
                "forceOffSupported": supported,
                "testedAt": _utc_now(),
            }
            if len(entries) > MAX_CACHE_ENTRIES:
                oldest = sorted(
                    entries,
                    key=lambda item: str(entries[item].get("testedAt", "")),
                )[: len(entries) - MAX_CACHE_ENTRIES]
                for item in oldest:
                    entries.pop(item, None)
            try:
                await asyncio.to_thread(self._write, document)
            except OSError:
                # Compatibility caching is an optimization. A read-only or
                # temporarily unavailable state directory must not block startup.
                return

    def _read(self) -> dict[str, Any]:
        try:
            value = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return {"version": CACHE_VERSION, "entries": {}}
        if not isinstance(value, dict) or value.get("version") != CACHE_VERSION:
            return {"version": CACHE_VERSION, "entries": {}}
        entries = value.get("entries")
        if not isinstance(entries, dict):
            return {"version": CACHE_VERSION, "entries": {}}
        return {"version": CACHE_VERSION, "entries": entries}

    def _write(self, value: dict[str, Any]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix(".tmp")
        temporary.write_text(
            json.dumps(value, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        try:
            os.chmod(temporary, 0o600)
        except OSError:
            pass
        os.replace(temporary, self.path)


def _runtime_fingerprint(runtime: dict[str, Any], control: str) -> str:
    parts: list[dict[str, Any]] = []
    for key in ("model", "executable"):
        path = Path(str(runtime[key])).resolve()
        stat = path.stat()
        parts.append({
            "kind": key,
            "path": str(path).casefold(),
            "size": stat.st_size,
            "mtimeNs": stat.st_mtime_ns,
        })
    encoded = json.dumps(
        {"files": parts, "control": control},
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
