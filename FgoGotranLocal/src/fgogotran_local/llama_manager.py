from __future__ import annotations

import asyncio
import os
import re
from contextlib import suppress
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Awaitable, Callable

import httpx

from .config_store import ConfigStore
from .errors import StudioError
from .llama_arguments import (
    ThinkingControl,
    build_llama_server_args,
    detect_thinking_control,
    runtime_profile_changed,
)
from .llama_metrics import empty_metrics, parse_prometheus_metrics
from .log_store import LogStore


POLL_INTERVAL_SECONDS = 1.5
HEALTH_TIMEOUT_SECONDS = 1.8
STOP_TIMEOUT_SECONDS = 6.0
HELP_TIMEOUT_SECONDS = 8.0

STATE_LABELS = {
    "STOPPED": "已停止",
    "STARTING": "正在启动",
    "LOADING": "正在加载模型",
    "READY": "已就绪",
    "BUSY": "正在翻译",
    "ERROR": "错误",
}


class LlamaManager:
    def __init__(self, config_store: ConfigStore, state_directory: Path | str, logs: LogStore) -> None:
        self.config_store = config_store
        self.state_directory = Path(state_directory).resolve()
        self.logs = logs
        self.process: asyncio.subprocess.Process | None = None
        self.state = "STOPPED"
        self.message = "请先设置 llama-server 和 GGUF 模型。"
        self.started_at: str | None = None
        self.running_profile: dict[str, Any] | None = None
        self.running_thinking_control: ThinkingControl | None = None
        self.running_api_key: str | None = None
        self.last_exit: dict[str, Any] | None = None
        self.last_request_at: str | None = None
        self.metrics = empty_metrics()
        self.last_prompt_tokens: float | None = None
        self.stopping = False
        self._operation_lock = asyncio.Lock()
        self._poll_lock = asyncio.Lock()
        self._poll_task: asyncio.Task | None = None
        self._watch_task: asyncio.Task | None = None
        self._output_tasks: list[asyncio.Task] = []
        self._client = httpx.AsyncClient(timeout=HEALTH_TIMEOUT_SECONDS)

    async def start_background(self) -> None:
        if self._poll_task is None or self._poll_task.done():
            self._poll_task = asyncio.create_task(self._poll_loop(), name="llama-health-poll")
        self.logs.add("INFO", "控制台已就绪，服务器尚未启动。")

    def snapshot(self) -> dict[str, Any]:
        config = self.config_store.get_raw_config()
        profile = config.profiles[config.active_profile].model_dump(by_alias=True)
        effective_profile = self.running_profile if self.is_running() and self.running_profile else profile
        return {
            "state": self.state,
            "stateLabel": STATE_LABELS.get(self.state, self.state),
            "message": self.message,
            "pid": self.process.pid if self.is_running() else None,
            "startedAt": self.started_at,
            "restartRequired": bool(self.is_running() and runtime_profile_changed(self.running_profile, profile)),
            "lastExit": self.last_exit,
            "lastRequestAt": self.last_request_at,
            "metrics": dict(self.metrics),
            "profile": {"id": config.active_profile, **profile},
            "runningProfile": dict(self.running_profile) if self.running_profile else None,
            "thinking": _thinking_status(
                effective_profile,
                running=self.is_running(),
                control=self.running_thinking_control,
            ),
        }

    async def start(self) -> dict[str, Any]:
        return await self._exclusive(self._start_unlocked)

    async def stop(self) -> dict[str, Any]:
        return await self._exclusive(self._stop_unlocked)

    async def restart(self) -> dict[str, Any]:
        async def operation() -> dict[str, Any]:
            await self._stop_unlocked()
            return await self._start_unlocked()

        return await self._exclusive(operation)

    async def test_compatibility(self) -> dict[str, Any]:
        if not self.is_running() or self.state not in {"READY", "BUSY"}:
            raise StudioError("请先启动模型并等待模型就绪。", 409)
        profile = self.running_profile
        api_key = self.running_api_key
        if not profile or not api_key:
            raise StudioError("无法获取当前 Profile。", 409)
        started = asyncio.get_running_loop().time()
        self.logs.add("INFO", "正在运行 FgoGotran Chat Completions 兼容性测试。")
        try:
            response = await self._client.post(
                f"{_local_origin(profile)}/v1/chat/completions",
                headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
                json={
                    "model": profile["modelAlias"],
                    "messages": [
                        {"role": "system", "content": "Translate FGO Japanese into concise Traditional Chinese. Return translation only."},
                        {"role": "user", "content": "Source:\nカルデアへようこそ。"},
                    ],
                    "max_tokens": 128,
                    "temperature": 0.3,
                },
                timeout=45.0,
            )
        except httpx.HTTPError as error:
            raise StudioError(f"兼容性测试失败：{error}") from error
        if not response.is_success:
            raise StudioError(f"兼容性测试失败：HTTP {response.status_code} {_safe_snippet(response.text)}")
        try:
            content = response.json().get("choices", [])[0].get("message", {}).get("content", "").strip()
        except (ValueError, IndexError, AttributeError) as error:
            raise StudioError("兼容性测试返回了无效 JSON。") from error
        if not content:
            raise StudioError("兼容性测试没有返回文本内容。")
        latency_ms = round((asyncio.get_running_loop().time() - started) * 1000)
        self.last_request_at = _utc_now()
        self.logs.add("INFO", f"兼容性测试通过，耗时 {latency_ms} 毫秒。")
        return {"ok": True, "latencyMs": latency_ms, "response": content[:200]}

    async def poll(self) -> None:
        if not self.is_running() or self.stopping or self._poll_lock.locked():
            return
        profile = self.running_profile
        api_key = self.running_api_key
        if not profile or not api_key:
            return
        async with self._poll_lock:
            try:
                health = await self._client.get(f"{_local_origin(profile)}/health")
                if not health.is_success:
                    self.state = "LOADING"
                    self.message = f"模型正在加载（健康状态 {health.status_code}）。"
                    return
                tasks = []
                if profile.get("metrics"):
                    tasks.append(self._poll_metrics(profile, api_key))
                if profile.get("slots"):
                    tasks.append(self._poll_slots(profile, api_key))
                if tasks:
                    await asyncio.gather(*tasks, return_exceptions=True)
                self.state = "BUSY" if self.metrics["requestsProcessing"] > 0 else "READY"
                self.message = "正在处理翻译请求。" if self.state == "BUSY" else "模型已加载，可供 FgoGotran 使用。"
            except httpx.HTTPError:
                self.state = "LOADING"
                self.message = "llama-server 已启动，正在等待 Health Check…"

    def is_running(self) -> bool:
        return self.process is not None and self.process.returncode is None

    async def shutdown(self) -> None:
        if self._poll_task:
            self._poll_task.cancel()
            with suppress(asyncio.CancelledError):
                await self._poll_task
            self._poll_task = None
        if self.is_running():
            with suppress(Exception):
                await self.stop()
        await self._client.aclose()

    async def _start_unlocked(self) -> dict[str, Any]:
        if self.is_running():
            raise StudioError("llama-server 已在运行中。", 409)
        runtime = await self.config_store.validate_runtime_files()
        profile = runtime["profile"].model_dump(by_alias=True)
        thinking_control: ThinkingControl | None = None
        if runtime["profile"].disable_thinking:
            help_text = await _llama_help_output(runtime["executable"])
            thinking_control = detect_thinking_control(help_text)
            if thinking_control is None:
                raise StudioError(
                    "当前 llama-server 不支持关闭模型思考。请更新 llama.cpp，或取消勾选“关闭模型思考”。"
                )
        api_key = self.config_store.get_secret()
        await asyncio.to_thread(self.state_directory.mkdir, parents=True, exist_ok=True)
        key_file = self.state_directory / "api-keys.txt"
        await asyncio.to_thread(key_file.write_text, f"{api_key}\n", encoding="utf-8")
        with suppress(OSError):
            await asyncio.to_thread(os.chmod, key_file, 0o600)
        args = build_llama_server_args(runtime, str(key_file), thinking_control)

        self.state = "STARTING"
        self.message = "正在启动 llama-server…"
        self.stopping = False
        self.last_exit = None
        self.metrics = empty_metrics()
        self.last_prompt_tokens = None
        self.logs.add("INFO", f"正在启动 {profile['displayName']}（{profile['modelAlias']}）。")
        if thinking_control == "reasoning":
            self.logs.add("INFO", "模型思考已通过 --reasoning off 关闭。")
        elif thinking_control == "chat-template-kwargs":
            self.logs.add("WARN", "当前 llama-server 使用旧版兼容参数关闭模型思考；建议更新 llama.cpp。")
        try:
            process = await asyncio.create_subprocess_exec(
                runtime["executable"],
                *args,
                cwd=str(Path(runtime["executable"]).parent),
                stdin=asyncio.subprocess.DEVNULL,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                creationflags=_hidden_process_flags(),
            )
        except OSError as error:
            await self._delete_key_file()
            self.state = "ERROR"
            self.message = f"无法启动 llama-server：{error}"
            self.logs.add("ERROR", self.message)
            raise StudioError(self.message) from error
        self.process = process
        self.started_at = _utc_now()
        self.running_profile = profile
        self.running_thinking_control = thinking_control
        self.running_api_key = api_key
        self._output_tasks = [
            asyncio.create_task(self._capture_output(process.stdout), name="llama-stdout"),
            asyncio.create_task(self._capture_output(process.stderr), name="llama-stderr"),
        ]
        self._watch_task = asyncio.create_task(self._watch_process(process), name="llama-process-watch")
        self.state = "LOADING"
        self.message = "进程已启动，正在等待模型加载…"
        await self.poll()
        return self.snapshot()

    async def _stop_unlocked(self) -> dict[str, Any]:
        process = self.process
        if process is None or process.returncode is not None:
            self.process = None
            self.state = "STOPPED"
            self.message = "llama-server 已停止。"
            return self.snapshot()
        self.stopping = True
        self.message = "正在停止 llama-server…"
        self.logs.add("INFO", "正在停止 llama-server。")
        with suppress(ProcessLookupError):
            process.terminate()
        try:
            await asyncio.wait_for(process.wait(), timeout=STOP_TIMEOUT_SECONDS)
        except asyncio.TimeoutError:
            self.logs.add("WARN", "正常停止超时，正在强制结束受管理进程。")
            process.kill()
            with suppress(asyncio.TimeoutError):
                await asyncio.wait_for(process.wait(), timeout=2.0)
        if self._watch_task:
            with suppress(asyncio.CancelledError):
                await self._watch_task
        return self.snapshot()

    async def _watch_process(self, process: asyncio.subprocess.Process) -> None:
        code = await process.wait()
        if self._output_tasks:
            await asyncio.gather(*self._output_tasks, return_exceptions=True)
        await self._delete_key_file()
        if self.process is not process:
            return
        self.last_exit = {"code": code, "signal": None, "at": _utc_now()}
        self.process = None
        self.started_at = None
        self.running_profile = None
        self.running_thinking_control = None
        self.running_api_key = None
        if self.stopping or code == 0:
            self.state = "STOPPED"
            self.message = "llama-server 已停止。"
            self.logs.add("INFO", "llama-server 已停止。")
        else:
            self.state = "ERROR"
            self.message = f"llama-server 意外退出（代码 {code}）。"
            self.logs.add("ERROR", self.message)
        self.stopping = False

    async def _capture_output(self, stream: asyncio.StreamReader | None) -> None:
        if stream is None:
            return
        while True:
            line = await stream.readline()
            if not line:
                return
            message = line.decode("utf-8", errors="replace").strip()
            if message:
                self.logs.add(_infer_log_level(message), message)

    async def _poll_metrics(self, profile: dict[str, Any], api_key: str) -> None:
        response = await self._client.get(
            f"{_local_origin(profile)}/metrics",
            headers={"Authorization": f"Bearer {api_key}"},
        )
        if not response.is_success:
            return
        metrics = parse_prometheus_metrics(response.text)
        self.metrics.update(metrics)
        prompt_total = metrics["promptTokensTotal"]
        if prompt_total is not None:
            if self.last_prompt_tokens is not None and prompt_total > self.last_prompt_tokens:
                self.last_request_at = _utc_now()
            self.last_prompt_tokens = prompt_total

    async def _poll_slots(self, profile: dict[str, Any], api_key: str) -> None:
        response = await self._client.get(
            f"{_local_origin(profile)}/slots",
            headers={"Authorization": f"Bearer {api_key}"},
        )
        if not response.is_success:
            return
        try:
            slots = response.json()
        except ValueError:
            return
        if isinstance(slots, list):
            self.metrics["requestsProcessing"] = sum(
                1 for slot in slots if isinstance(slot, dict) and slot.get("is_processing")
            )

    async def _poll_loop(self) -> None:
        while True:
            await asyncio.sleep(POLL_INTERVAL_SECONDS)
            await self.poll()

    async def _exclusive(self, action: Callable[[], Awaitable[dict[str, Any]]]) -> dict[str, Any]:
        if self._operation_lock.locked():
            raise StudioError("另一项服务器操作仍在进行中。", 409)
        async with self._operation_lock:
            return await action()

    async def _delete_key_file(self) -> None:
        key_file = self.state_directory / "api-keys.txt"
        with suppress(OSError):
            await asyncio.to_thread(key_file.unlink, missing_ok=True)


def _local_origin(profile: dict[str, Any]) -> str:
    return f"http://127.0.0.1:{profile['port']}"


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _safe_snippet(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip()[:180]


def _infer_log_level(message: str) -> str:
    lower = message.lower()
    if "error" in lower or "failed" in lower:
        return "ERROR"
    if "warn" in lower:
        return "WARN"
    return "INFO"


def _hidden_process_flags() -> int:
    if os.name != "nt":
        return 0
    import subprocess

    return subprocess.CREATE_NO_WINDOW


async def _llama_help_output(executable: str) -> str:
    try:
        process = await asyncio.create_subprocess_exec(
            executable,
            "--help",
            stdin=asyncio.subprocess.DEVNULL,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.STDOUT,
            creationflags=_hidden_process_flags(),
        )
    except OSError as error:
        raise StudioError(f"无法检查 llama-server 功能：{error}") from error
    try:
        output, _ = await asyncio.wait_for(process.communicate(), timeout=HELP_TIMEOUT_SECONDS)
    except asyncio.TimeoutError as error:
        with suppress(ProcessLookupError):
            process.kill()
        with suppress(Exception):
            await process.wait()
        raise StudioError("检查 llama-server 功能超时；请确认已完整解压 llama.cpp。") from error
    return output.decode("utf-8", errors="replace")


def _thinking_status(
    profile: dict[str, Any],
    *,
    running: bool,
    control: ThinkingControl | None,
) -> dict[str, Any]:
    if not profile.get("disableThinking"):
        return {"disabled": False, "method": None, "label": "跟随模型默认"}
    if not running:
        return {"disabled": True, "method": None, "label": "将在启动时关闭"}
    if control == "reasoning":
        return {"disabled": True, "method": "--reasoning off", "label": "已关闭"}
    if control == "chat-template-kwargs":
        return {
            "disabled": True,
            "method": '--chat-template-kwargs {"enable_thinking":false}',
            "label": "已关闭（旧版兼容参数）",
        }
    return {"disabled": True, "method": None, "label": "无法确认"}
