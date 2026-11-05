import asyncio
import json
from pathlib import Path
from types import SimpleNamespace

from fgogotran_local.compatibility import (
    ChatCompatibilityResult,
    ThinkingCompatibilityCache,
    classify_chat_completion,
)
from fgogotran_local.config_store import ConfigStore
from fgogotran_local.llama_manager import LlamaManager
from fgogotran_local.log_store import LogStore


def test_chat_compatibility_accepts_nonempty_assistant_content():
    result = classify_chat_completion(
        200,
        {
            "choices": [
                {
                    "message": {"content": "欢迎来到迦勒底。"},
                    "finish_reason": "stop",
                }
            ],
            "usage": {"completion_tokens": 8},
        },
        latency_ms=321,
    )

    assert result.ok is True
    assert result.kind == "ok"
    assert result.content == "欢迎来到迦勒底。"
    assert result.latency_ms == 321


def test_one_token_empty_response_is_the_narrow_forced_off_conflict_signal():
    result = classify_chat_completion(
        200,
        {
            "choices": [
                {
                    "message": {"content": "", "reasoning_content": ""},
                    "finish_reason": "stop",
                }
            ],
            "usage": {"completion_tokens": 1},
        },
        latency_ms=20,
    )

    assert result.ok is False
    assert result.kind == "empty_single_token"
    assert result.forced_off_template_conflict is True


def test_reasoning_only_response_does_not_trigger_forced_off_recovery():
    result = classify_chat_completion(
        200,
        {
            "choices": [
                {
                    "message": {"content": "", "reasoning_content": "需要翻译。"},
                    "finish_reason": "length",
                }
            ],
            "usage": {"completion_tokens": 128},
        },
        latency_ms=900,
    )

    assert result.ok is False
    assert result.kind == "reasoning_without_content"
    assert result.forced_off_template_conflict is False


def test_http_and_invalid_responses_are_reported_without_recovery():
    http_result = classify_chat_completion(503, {}, latency_ms=1)
    invalid_result = classify_chat_completion(200, {"choices": []}, latency_ms=2)

    assert http_result.kind == "http_error"
    assert http_result.forced_off_template_conflict is False
    assert invalid_result.kind == "invalid_response"
    assert invalid_result.forced_off_template_conflict is False


def test_thinking_compatibility_cache_is_private_and_fingerprint_scoped(tmp_path: Path):
    model = tmp_path / "model.gguf"
    executable = tmp_path / "llama-server.exe"
    model.write_bytes(b"model-v1")
    executable.write_bytes(b"runtime-v1")
    runtime = {"model": str(model), "executable": str(executable), "apiKey": "must-not-leak"}
    cache = ThinkingCompatibilityCache(tmp_path / "state")

    async def exercise() -> tuple[bool | None, bool | None]:
        await cache.put(runtime, "reasoning", False)
        same = await cache.get(runtime, "reasoning")
        model.write_bytes(b"model-v2")
        changed = await cache.get(runtime, "reasoning")
        return same, changed

    same, changed = asyncio.run(exercise())
    raw = cache.path.read_text(encoding="utf-8")
    document = json.loads(raw)

    assert same is False
    assert changed is None
    assert document["version"] == 1
    assert "must-not-leak" not in raw
    assert str(model) not in raw
    assert str(executable) not in raw


def test_startup_probe_recovers_only_the_forced_off_end_token_case(tmp_path: Path, monkeypatch):
    manager = LlamaManager(
        ConfigStore(tmp_path / "config.json"),
        tmp_path / "state",
        LogStore(tmp_path / "state", lambda: "secret"),
    )
    manager.process = SimpleNamespace(pid=42, returncode=None)
    manager.running_profile = {
        "host": "127.0.0.1",
        "port": 18080,
        "modelAlias": "translation-model",
        "metrics": False,
        "slots": False,
        "disableThinking": True,
    }
    manager.running_api_key = "secret"
    manager.running_runtime = {"model": "model.gguf", "executable": "llama-server.exe"}
    manager.running_thinking_control = "reasoning"
    scheduled: list[tuple[str, object]] = []
    cached: list[tuple[str, bool]] = []

    async def fake_get(_url):
        return SimpleNamespace(is_success=True, status_code=200)

    async def fake_probe(_profile, _api_key):
        return ChatCompatibilityResult(
            ok=False,
            kind="empty_single_token",
            message="模型仅生成结束 token，未返回译文",
            latency_ms=10,
            finish_reason="stop",
            completion_tokens=1,
        )

    async def fake_put(_runtime, control, supported):
        cached.append((control, supported))

    monkeypatch.setattr(manager._client, "get", fake_get)
    monkeypatch.setattr(manager, "_probe_chat_compatibility", fake_probe)
    monkeypatch.setattr(manager._compatibility_cache, "put", fake_put)
    monkeypatch.setattr(
        manager,
        "_schedule_thinking_fallback",
        lambda reason, process: scheduled.append((reason, process)),
    )

    async def exercise() -> None:
        await manager.poll()
        await manager._client.aclose()

    asyncio.run(exercise())

    assert manager.state == "RECOVERING"
    assert manager._compatibility["status"] == "RECOVERING"
    assert cached == [("reasoning", False)]
    assert len(scheduled) == 1


def test_startup_probe_does_not_restart_for_a_generic_empty_response(tmp_path: Path, monkeypatch):
    manager = LlamaManager(
        ConfigStore(tmp_path / "config.json"),
        tmp_path / "state",
        LogStore(tmp_path / "state", lambda: "secret"),
    )
    manager.process = SimpleNamespace(pid=43, returncode=None)
    manager.running_profile = {
        "host": "127.0.0.1",
        "port": 18080,
        "modelAlias": "translation-model",
        "metrics": False,
        "slots": False,
        "disableThinking": False,
    }
    manager.running_api_key = "secret"
    scheduled: list[tuple[str, object]] = []

    async def fake_get(_url):
        return SimpleNamespace(is_success=True, status_code=200)

    async def fake_probe(_profile, _api_key):
        return ChatCompatibilityResult(
            ok=False,
            kind="empty_content",
            message="Chat Completions 没有返回文本内容",
            latency_ms=10,
        )

    monkeypatch.setattr(manager._client, "get", fake_get)
    monkeypatch.setattr(manager, "_probe_chat_compatibility", fake_probe)
    monkeypatch.setattr(
        manager,
        "_schedule_thinking_fallback",
        lambda reason, process: scheduled.append((reason, process)),
    )

    async def exercise() -> None:
        await manager.poll()
        await manager._client.aclose()

    asyncio.run(exercise())

    assert manager.state == "ERROR"
    assert manager._compatibility["status"] == "FAILED"
    assert scheduled == []


def test_recovery_task_stops_then_starts_once_with_model_default(tmp_path: Path, monkeypatch):
    manager = LlamaManager(
        ConfigStore(tmp_path / "config.json"),
        tmp_path / "state",
        LogStore(tmp_path / "state", lambda: "secret"),
    )
    process = SimpleNamespace(pid=45, returncode=None)
    manager.process = process
    manager.state = "RECOVERING"
    calls: list[object] = []

    async def fake_stop():
        calls.append("stop")
        return {}

    async def fake_start(*, force_model_default=False, fallback_reason_key=""):
        calls.append(("start", force_model_default, fallback_reason_key))
        return {}

    monkeypatch.setattr(manager, "_stop_unlocked", fake_stop)
    monkeypatch.setattr(manager, "_start_unlocked", fake_start)

    async def exercise() -> None:
        manager._schedule_thinking_fallback("template conflict", process)
        task = manager._recovery_task
        assert task is not None
        await task
        await manager._client.aclose()

    asyncio.run(exercise())

    assert calls == ["stop", ("start", True, "template conflict")]
    assert manager._fallback_attempted is True
    assert manager._recovery_task is None


def test_stale_probe_cannot_overwrite_state_after_process_stops(tmp_path: Path, monkeypatch):
    manager = LlamaManager(
        ConfigStore(tmp_path / "config.json"),
        tmp_path / "state",
        LogStore(tmp_path / "state", lambda: "secret"),
    )
    process = SimpleNamespace(pid=44, returncode=None)
    manager.process = process
    manager.running_profile = {
        "host": "127.0.0.1",
        "port": 18080,
        "modelAlias": "translation-model",
        "metrics": False,
        "slots": False,
        "disableThinking": True,
    }
    manager.running_api_key = "secret"
    manager.running_runtime = {"model": "model.gguf", "executable": "llama-server.exe"}
    manager.running_thinking_control = "reasoning"
    scheduled: list[tuple[str, object]] = []

    async def fake_get(_url):
        return SimpleNamespace(is_success=True, status_code=200)

    async def fake_probe(_profile, _api_key):
        process.returncode = 0
        manager.process = None
        manager.state = "STOPPED"
        manager.message = "llama-server 已停止。"
        return ChatCompatibilityResult(
            ok=False,
            kind="empty_single_token",
            message="模型仅生成结束 token，未返回译文",
            latency_ms=10,
            finish_reason="stop",
            completion_tokens=1,
        )

    monkeypatch.setattr(manager._client, "get", fake_get)
    monkeypatch.setattr(manager, "_probe_chat_compatibility", fake_probe)
    monkeypatch.setattr(
        manager,
        "_schedule_thinking_fallback",
        lambda reason, process: scheduled.append((reason, process)),
    )

    async def exercise() -> None:
        await manager.poll()
        await manager._client.aclose()

    asyncio.run(exercise())

    assert manager.state == "STOPPED"
    assert scheduled == []


def test_queued_recovery_does_not_restart_after_user_stop_wins(tmp_path: Path, monkeypatch):
    manager = LlamaManager(
        ConfigStore(tmp_path / "config.json"),
        tmp_path / "state",
        LogStore(tmp_path / "state", lambda: "secret"),
    )
    process = SimpleNamespace(pid=46, returncode=None)
    manager.process = process
    manager.state = "RECOVERING"
    calls: list[str] = []

    async def fake_stop():
        calls.append("stop")
        return {}

    async def fake_start(*, force_model_default=False, fallback_reason_key=""):
        calls.append("start")
        return {}

    monkeypatch.setattr(manager, "_stop_unlocked", fake_stop)
    monkeypatch.setattr(manager, "_start_unlocked", fake_start)

    async def exercise() -> None:
        await manager._operation_lock.acquire()
        manager._schedule_thinking_fallback("template conflict", process)
        task = manager._recovery_task
        assert task is not None
        manager.process = None
        manager.state = "STOPPED"
        process.returncode = 0
        manager._operation_lock.release()
        await task
        await manager._client.aclose()

    asyncio.run(exercise())

    assert calls == []
    assert manager.state == "STOPPED"
