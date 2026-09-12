from __future__ import annotations

import asyncio
import os
import re
from contextlib import suppress
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Awaitable, Callable

import httpx

from .compatibility import (
    ChatCompatibilityResult,
    ThinkingCompatibilityCache,
    classify_chat_completion,
)
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
    "VERIFYING": "正在检查兼容性",
    "RECOVERING": "正在应用兼容回退",
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
        self.running_thinking_fallback = False
        self.running_thinking_fallback_reason = ""
        self.running_api_key: str | None = None
        self.running_runtime: dict[str, Any] | None = None
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
        self._compatibility_pid: int | None = None
        self._compatibility = _compatibility_status("PENDING", "模型尚未启动。")
        self._recovery_task: asyncio.Task | None = None
        self._fallback_attempted = False
        self._compatibility_cache = ThinkingCompatibilityCache(self.state_directory)
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
                fallback=self.running_thinking_fallback,
                fallback_reason=self.running_thinking_fallback_reason,
            ),
            "compatibility": dict(self._compatibility),
        }

    async def start(self) -> dict[str, Any]:
        async def operation() -> dict[str, Any]:
            self._fallback_attempted = False
            return await self._start_unlocked()

        return await self._exclusive(operation)

    async def stop(self) -> dict[str, Any]:
        return await self._exclusive(self._stop_unlocked)

    async def restart(self) -> dict[str, Any]:
        async def operation() -> dict[str, Any]:
            await self._stop_unlocked()
            self._fallback_attempted = False
            return await self._start_unlocked()

        return await self._exclusive(operation)

    async def test_compatibility(self) -> dict[str, Any]:
        if not self.is_running() or self.state not in {"READY", "BUSY"}:
            raise StudioError("请先启动模型并等待模型就绪。", 409)
        profile = self.running_profile
        api_key = self.running_api_key
        if not profile or not api_key:
            raise StudioError("无法获取当前 Profile。", 409)
        self.logs.add("INFO", "正在运行 FgoGotran Chat Completions 兼容性测试。")
        result = await self._probe_chat_compatibility(profile, api_key)
        if not result.ok:
            raise StudioError(f"兼容性测试失败：{result.message}")
        self.last_request_at = _utc_now()
        self.logs.add("INFO", f"兼容性测试通过，耗时 {result.latency_ms} 毫秒。")
        return {"ok": True, "latencyMs": result.latency_ms, "response": result.content[:200]}

    async def _probe_chat_compatibility(
        self,
        profile: dict[str, Any],
        api_key: str,
    ) -> ChatCompatibilityResult:
        started = asyncio.get_running_loop().time()
        try:
            response = await self._client.post(
                f"{_local_origin(profile)}/v1/chat/completions",
                headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
                json={
                    "model": profile["modelAlias"],
                    "messages": [
                        {"role": "system", "content": "你是一个日中翻译模型，只返回中文译文。"},
                        {"role": "user", "content": "将下面的日文文本翻译成中文：カルデアへようこそ。"},
                    ],
                    "max_tokens": 128,
                    "temperature": 0.1,
                    "top_p": 0.3,
                },
                timeout=45.0,
            )
        except httpx.HTTPError as error:
            latency_ms = round((asyncio.get_running_loop().time() - started) * 1000)
            return ChatCompatibilityResult(
                ok=False,
                kind="request_error",
                message=f"请求失败：{error}",
                latency_ms=latency_ms,
            )
        latency_ms = round((asyncio.get_running_loop().time() - started) * 1000)
        try:
            payload = response.json()
        except ValueError:
            payload = None
        result = classify_chat_completion(response.status_code, payload, latency_ms=latency_ms)
        if not result.ok and result.kind == "http_error":
            return ChatCompatibilityResult(
                ok=False,
                kind=result.kind,
                message=f"{result.message} {_safe_snippet(response.text)}".strip(),
                latency_ms=result.latency_ms,
            )
        return result

    async def poll(self) -> None:
        if (
            not self.is_running()
            or self.stopping
            or self._poll_lock.locked()
            or (self._recovery_task is not None and not self._recovery_task.done())
        ):
            return
        process = self.process
        if process is None:
            return
        profile = self.running_profile
        api_key = self.running_api_key
        if not profile or not api_key:
            return
        async with self._poll_lock:
            try:
                health = await self._client.get(f"{_local_origin(profile)}/health")
                if not self._is_current_process(process):
                    return
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
                if not self._is_current_process(process):
                    return
                if self._compatibility_pid != process.pid:
                    self.state = "VERIFYING"
                    self.message = "模型已加载，正在检查 Chat Completions 兼容性…"
                    self._compatibility = _compatibility_status(
                        "VERIFYING",
                        "正在发送短请求并检查响应格式。",
                    )
                    result = await self._probe_chat_compatibility(profile, api_key)
                    if not self._is_current_process(process):
                        return
                    self._compatibility_pid = process.pid
                    if result.ok:
                        if self.running_thinking_control and self.running_runtime:
                            await self._compatibility_cache.put(
                                self.running_runtime,
                                self.running_thinking_control,
                                True,
                            )
                            if not self._is_current_process(process):
                                return
                        status = "FALLBACK" if self.running_thinking_fallback else "PASS"
                        message = (
                            self.running_thinking_fallback_reason
                            if self.running_thinking_fallback
                            else "Chat Completions 返回格式正常。"
                        )
                        self._compatibility = _compatibility_status(
                            status,
                            message,
                            latency_ms=result.latency_ms,
                        )
                        self.logs.add(
                            "INFO",
                            f"启动兼容性检测通过，耗时 {result.latency_ms} 毫秒。",
                        )
                    elif (
                        result.forced_off_template_conflict
                        and self.running_thinking_control is not None
                        and self.running_runtime is not None
                        and not self._fallback_attempted
                    ):
                        await self._compatibility_cache.put(
                            self.running_runtime,
                            self.running_thinking_control,
                            False,
                        )
                        if not self._is_current_process(process):
                            return
                        reason = "强制关闭思考后模型只生成结束 token，已改用模型默认。"
                        self._compatibility = _compatibility_status("RECOVERING", reason)
                        self.state = "RECOVERING"
                        self.message = reason
                        self.logs.add("WARN", reason)
                        self._schedule_thinking_fallback(reason, process)
                        return
                    else:
                        self._compatibility = _compatibility_status(
                            "FAILED",
                            result.message,
                            latency_ms=result.latency_ms,
                        )
                        self.state = "ERROR"
                        self.message = f"模型已加载，但兼容性检测失败：{result.message}"
                        self.logs.add("ERROR", self.message)
                        return
                if self._compatibility.get("status") == "FAILED":
                    self.state = "ERROR"
                    return
                if not self._is_current_process(process):
                    return
                self.state = "BUSY" if self.metrics["requestsProcessing"] > 0 else "READY"
                if self.state == "BUSY":
                    self.message = "正在处理翻译请求。"
                elif self.running_thinking_fallback:
                    self.message = "模型已加载，可供 FgoGotran 使用；思考模式已兼容回退。"
                else:
                    self.message = "模型已加载，可供 FgoGotran 使用。"
            except httpx.HTTPError:
                if not self._is_current_process(process):
                    return
                self.state = "LOADING"
                self.message = "llama-server 已启动，正在等待 Health Check…"

    def is_running(self) -> bool:
        return self.process is not None and self.process.returncode is None

    def _is_current_process(self, process: asyncio.subprocess.Process) -> bool:
        return self.process is process and process.returncode is None and not self.stopping

    async def shutdown(self) -> None:
        if self._poll_task:
            self._poll_task.cancel()
            with suppress(asyncio.CancelledError):
                await self._poll_task
            self._poll_task = None
        if self._recovery_task and not self._recovery_task.done():
            self._recovery_task.cancel()
            with suppress(asyncio.CancelledError):
                await self._recovery_task
            self._recovery_task = None
        if self.is_running():
            with suppress(Exception):
                await self.stop()
        await self._client.aclose()

    def _schedule_thinking_fallback(
        self,
        reason: str,
        expected_process: asyncio.subprocess.Process,
    ) -> None:
        if self._recovery_task is not None and not self._recovery_task.done():
            return
        self._fallback_attempted = True

        async def recover() -> None:
            try:
                async with self._operation_lock:
                    if not self._is_current_process(expected_process) or self.state != "RECOVERING":
                        return
                    await self._stop_unlocked()
                    await self._start_unlocked(
                        force_model_default=True,
                        fallback_reason=reason,
                    )
            except asyncio.CancelledError:
                raise
            except Exception as error:
                self.state = "ERROR"
                self.message = f"思考模式兼容回退失败：{error}"
                self._compatibility = _compatibility_status("FAILED", self.message)
                self.logs.add("ERROR", self.message)
            finally:
                self._recovery_task = None

        self._recovery_task = asyncio.create_task(recover(), name="llama-thinking-fallback")

    async def _start_unlocked(
        self,
        *,
        force_model_default: bool = False,
        fallback_reason: str = "",
    ) -> dict[str, Any]:
        if self.is_running():
            raise StudioError("llama-server 已在运行中。", 409)
        runtime = await self.config_store.validate_runtime_files()
        profile = runtime["profile"].model_dump(by_alias=True)
        thinking_control: ThinkingControl | None = None
        requested_disable_thinking = runtime["profile"].disable_thinking
        cached_incompatible = False
        if requested_disable_thinking and not force_model_default:
            help_text = await _llama_help_output(runtime["executable"])
            thinking_control = detect_thinking_control(help_text)
            if thinking_control is None:
                raise StudioError(
                    "当前 llama-server 不支持强制关闭模型思考。请更新 llama.cpp，或取消勾选“强制关闭模型思考”。"
                )
            cached_support = await self._compatibility_cache.get(runtime, thinking_control)
            cached_incompatible = cached_support is False
            if cached_incompatible:
                fallback_reason = "此模型与 llama-server 组合已知不兼容强制关闭思考，已使用模型默认。"
                thinking_control = None
        apply_disable_thinking = requested_disable_thinking and thinking_control is not None and not force_model_default
        api_key = self.config_store.get_secret()
        await asyncio.to_thread(self.state_directory.mkdir, parents=True, exist_ok=True)
        key_file = self.state_directory / "api-keys.txt"
        await asyncio.to_thread(key_file.write_text, f"{api_key}\n", encoding="utf-8")
        with suppress(OSError):
            await asyncio.to_thread(os.chmod, key_file, 0o600)
        args = build_llama_server_args(
            runtime,
            str(key_file),
            thinking_control,
            apply_disable_thinking=apply_disable_thinking,
        )

        self.state = "STARTING"
        self.message = "正在启动 llama-server…"
        self.stopping = False
        self.last_exit = None
        self.metrics = empty_metrics()
        self.last_prompt_tokens = None
        self._compatibility_pid = None
        self._compatibility = _compatibility_status("PENDING", "等待模型加载后检查兼容性。")
        self.logs.add("INFO", f"正在启动 {profile['displayName']}（{profile['modelAlias']}）。")
        if thinking_control == "reasoning":
            self.logs.add("INFO", "模型思考已通过 --reasoning off 关闭。")
        elif thinking_control == "chat-template-kwargs":
            self.logs.add("WARN", "当前 llama-server 使用旧版兼容参数关闭模型思考；建议更新 llama.cpp。")
        elif requested_disable_thinking and (force_model_default or cached_incompatible):
            self.logs.add("WARN", fallback_reason)
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
        self.running_thinking_fallback = requested_disable_thinking and not apply_disable_thinking
        self.running_thinking_fallback_reason = fallback_reason if self.running_thinking_fallback else ""
        self.running_api_key = api_key
        self.running_runtime = runtime
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
            self._clear_running_state()
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
        self._clear_running_state()
        if self.stopping or code == 0:
            self.state = "STOPPED"
            self.message = "llama-server 已停止。"
            self.logs.add("INFO", "llama-server 已停止。")
        else:
            self.state = "ERROR"
            self.message = f"llama-server 意外退出（代码 {code}）。"
            self.logs.add("ERROR", self.message)
        self.stopping = False

    def _clear_running_state(self) -> None:
        self.running_profile = None
        self.running_thinking_control = None
        self.running_thinking_fallback = False
        self.running_thinking_fallback_reason = ""
        self.running_api_key = None
        self.running_runtime = None
        self._compatibility_pid = None
        self._compatibility = _compatibility_status("PENDING", "模型尚未启动。")

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
    fallback: bool = False,
    fallback_reason: str = "",
) -> dict[str, Any]:
    if not profile.get("disableThinking"):
        return {
            "requestedDisabled": False,
            "disabled": False,
            "method": None,
            "label": "跟随模型默认",
            "fallback": False,
            "reason": "",
        }
    if not running:
        return {
            "requestedDisabled": True,
            "disabled": False,
            "method": None,
            "label": "将在启动时尝试关闭",
            "fallback": False,
            "reason": "",
        }
    if fallback:
        return {
            "requestedDisabled": True,
            "disabled": False,
            "method": None,
            "label": "跟随模型默认（兼容回退）",
            "fallback": True,
            "reason": fallback_reason,
        }
    if control == "reasoning":
        return {
            "requestedDisabled": True,
            "disabled": True,
            "method": "--reasoning off",
            "label": "已关闭",
            "fallback": False,
            "reason": "",
        }
    if control == "chat-template-kwargs":
        return {
            "requestedDisabled": True,
            "disabled": True,
            "method": '--chat-template-kwargs {"enable_thinking":false}',
            "label": "已关闭（旧版兼容参数）",
            "fallback": False,
            "reason": "",
        }
    return {
        "requestedDisabled": True,
        "disabled": False,
        "method": None,
        "label": "无法确认",
        "fallback": False,
        "reason": "",
    }


def _compatibility_status(
    status: str,
    message: str,
    *,
    latency_ms: int | None = None,
) -> dict[str, Any]:
    return {
        "status": status,
        "message": message,
        "latencyMs": latency_ms,
    }
