from __future__ import annotations

import asyncio
import os
import socket
import sys
import time
from pathlib import Path
from typing import Any

from . import __version__
from .config_store import ConfigStore
from .errors import ConfigError, StudioError
from .llama_manager import LlamaManager
from .log_store import LogStore
from .privacy import MASKED_VALUE, redact_connection, redact_sensitive_payload
from .system_monitor import SystemMonitor


class LocalTranslationService:
    def __init__(self, data_directory: Path | str) -> None:
        self.data_directory = Path(data_directory).resolve()
        platform_id = os.environ.get("FGO_LOCAL_PLATFORM_ID", "windows-x64")
        self.config_store = ConfigStore(self.data_directory / "config.json", platform_id=platform_id)
        self.logs: LogStore | None = None
        self.manager: LlamaManager | None = None
        self.monitor = SystemMonitor()
        self.started_at = time.monotonic()
        self._auto_start_task: asyncio.Task[None] | None = None

    async def initialize(self) -> None:
        await self.config_store.load()
        self.logs = LogStore(self.data_directory / "state", self.config_store.get_secret)
        self.manager = LlamaManager(self.config_store, self.data_directory / "state", self.logs)
        await self.manager.start_background()
        if os.environ.get("FGO_LOCAL_AUTO_START_MODEL") == "1":
            self._auto_start_task = asyncio.create_task(self._auto_start_model(), name="llama-auto-start")

    async def close(self) -> None:
        if self._auto_start_task and not self._auto_start_task.done():
            self._auto_start_task.cancel()
            try:
                await self._auto_start_task
            except asyncio.CancelledError:
                pass
        if self.manager:
            await self.manager.shutdown()

    async def _auto_start_model(self) -> None:
        await asyncio.sleep(0)
        try:
            await self._manager().start()
            self._logs().add("INFO", "已按当前 Profile 自动启动 llama-server。")
        except (ConfigError, StudioError) as error:
            self._logs().add("WARN", f"自动启动已跳过：{error}")
        except Exception as error:  # Keep the local control interface available for recovery.
            self._logs().add("ERROR", f"自动启动失败：{error}")

    async def status(self, *, include_sensitive: bool = False) -> dict[str, Any]:
        manager = self._manager()
        runtime, system = manager.snapshot(), await self.monitor.snapshot()
        connection_profile = runtime.get("runningProfile") or runtime["profile"]
        lan_address = next(iter(system["lanAddresses"]), None)
        connection_host = lan_address if connection_profile["host"] == "0.0.0.0" else "127.0.0.1"
        display_host = connection_host or "192.168.x.x"
        result = {
            **runtime,
            "uptimeSeconds": int(time.monotonic() - self.started_at),
            "gpu": system["gpu"],
            "memory": system["memory"],
            "connection": {
                "lanAddress": lan_address,
                "lanAccessible": connection_profile["host"] == "0.0.0.0" and bool(lan_address),
                "modelAlias": connection_profile["modelAlias"],
                "endpoint": f"http://{display_host}:{connection_profile['port']}/v1/chat/completions",
                "health": f"http://{display_host}:{connection_profile['port']}/health",
            },
        }
        if include_sensitive:
            return result
        safe = redact_sensitive_payload(result)
        safe["connection"] = redact_connection(result["connection"])
        return safe

    def public_config(self, *, include_sensitive: bool = False) -> dict[str, Any]:
        config = self.config_store.public_config()
        return config if include_sensitive else redact_sensitive_payload(config)

    def api_key(self) -> str:
        return self.config_store.get_secret()

    async def update_config(self, candidate: dict[str, Any], revision: str | None) -> dict[str, Any]:
        config = await self.config_store.update(candidate, revision)
        self._logs().add("INFO", "设置已保存；重新启动 llama-server 后应用更改。")
        return config

    async def rotate_api_key(self) -> dict[str, Any]:
        if self._manager().is_running():
            raise ConfigError("更换 API Key 前请先停止 llama-server。", 409)
        api_key = await self.config_store.rotate_api_key()
        self._logs().add("INFO", "API Key 已更换；重新连接前请在 FgoGotran 更新 Key。")
        return {"apiKey": api_key, "config": self.config_store.public_config()}

    async def list_models(self, directory: str | None = None) -> list[dict[str, str]]:
        return await self.config_store.list_models(directory)

    async def public_models(self) -> list[dict[str, str]]:
        return redact_sensitive_payload(await self.list_models())

    def log_entries(self, after: int | str = 0, *, include_sensitive: bool = False) -> dict[str, Any]:
        return self._logs().since(after, include_sensitive=include_sensitive)

    def formatted_logs(self, levels: set[str] | None = None, *, include_sensitive: bool = False) -> str:
        return self._logs().formatted(levels, include_sensitive=include_sensitive)

    def clear_logs(self) -> dict[str, Any]:
        return self._logs().clear_display()

    async def start(self) -> dict[str, Any]:
        return await self._manager().start()

    async def stop(self) -> dict[str, Any]:
        return await self._manager().stop()

    async def restart(self) -> dict[str, Any]:
        return await self._manager().restart()

    async def test_compatibility(self) -> dict[str, Any]:
        return await self._manager().test_compatibility()

    async def diagnostics(self, *, include_sensitive: bool = False) -> dict[str, Any]:
        config = self.config_store.get_raw_config()
        system = await self.monitor.snapshot()
        runtime_validation = "通过"
        try:
            await self.config_store.validate_runtime_files()
        except ConfigError as error:
            runtime_validation = str(error)
        result = {
            "version": __version__,
            "pythonVersion": sys.version.split()[0],
            "dataDirectory": str(self.data_directory),
            "llamaServerPath": config.llama_server_path or "尚未设置",
            "modelsDirectory": config.models_directory or "尚未设置",
            "runtimeValidation": runtime_validation,
            "inferencePort": config.profiles[config.active_profile].port,
            "portAvailable": await asyncio.to_thread(_port_available, config.profiles[config.active_profile].port),
            "lanAddresses": system["lanAddresses"],
            "gpu": system["gpu"],
            "memory": system["memory"],
        }
        if include_sensitive:
            return result
        safe = redact_sensitive_payload(result)
        safe["lanAddresses"] = [MASKED_VALUE for _ in result["lanAddresses"]]
        return safe

    def _manager(self) -> LlamaManager:
        if self.manager is None:
            raise StudioError("本地翻译服务尚未初始化。", 503)
        return self.manager

    def _logs(self) -> LogStore:
        if self.logs is None:
            raise StudioError("本地翻译服务尚未初始化。", 503)
        return self.logs


def resolve_data_directory(value: str | None, fallback: Path | str) -> Path:
    candidate = str(value or "").strip()
    if candidate and Path(candidate).is_absolute():
        return Path(candidate).resolve()
    return Path(fallback).resolve()


def default_data_directory(package_file: str) -> Path:
    return Path(package_file).resolve().parents[2] / "user_data"


def configured_data_directory(package_file: str) -> Path:
    return resolve_data_directory(os.environ.get("FGO_LOCAL_HOME"), default_data_directory(package_file))


def _port_available(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.settimeout(0.4)
        return probe.connect_ex(("127.0.0.1", port)) != 0
