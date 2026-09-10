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
from .system_monitor import SystemMonitor


class LocalTranslationService:
    def __init__(self, data_directory: Path | str) -> None:
        self.data_directory = Path(data_directory).resolve()
        self.config_store = ConfigStore(self.data_directory / "config.json")
        self.logs: LogStore | None = None
        self.manager: LlamaManager | None = None
        self.monitor = SystemMonitor()
        self.started_at = time.monotonic()

    async def initialize(self) -> None:
        await self.config_store.load()
        self.logs = LogStore(self.data_directory / "state", self.config_store.get_secret)
        self.manager = LlamaManager(self.config_store, self.data_directory / "state", self.logs)
        await self.manager.start_background()

    async def close(self) -> None:
        if self.manager:
            await self.manager.shutdown()

    async def status(self) -> dict[str, Any]:
        manager = self._manager()
        runtime, system = manager.snapshot(), await self.monitor.snapshot()
        connection_profile = runtime.get("runningProfile") or runtime["profile"]
        lan_address = next(iter(system["lanAddresses"]), None)
        connection_host = lan_address if connection_profile["host"] == "0.0.0.0" else "127.0.0.1"
        display_host = connection_host or "192.168.x.x"
        return {
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

    def public_config(self) -> dict[str, Any]:
        return self.config_store.public_config()

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

    def log_entries(self, after: int | str = 0) -> dict[str, Any]:
        return self._logs().since(after)

    def formatted_logs(self, levels: set[str] | None = None) -> str:
        return self._logs().formatted(levels)

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

    async def diagnostics(self) -> dict[str, Any]:
        config = self.config_store.get_raw_config()
        system = await self.monitor.snapshot()
        runtime_validation = "通过"
        try:
            await self.config_store.validate_runtime_files()
        except ConfigError as error:
            runtime_validation = str(error)
        return {
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
