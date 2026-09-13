from __future__ import annotations

import asyncio
import hashlib
import json
import os
import shutil
from collections import deque
from pathlib import Path
from typing import Any

from .config_models import LocalConfig, default_config, generate_api_key, normalize_config
from .errors import ConfigError


MAX_MODEL_RESULTS = 250
MAX_MODEL_SCAN_ENTRIES = 10_000


class ConfigStore:
    def __init__(self, config_path: Path | str) -> None:
        self.config_path = Path(config_path).resolve()
        self._config: LocalConfig | None = None
        self._revision = ""
        self._lock = asyncio.Lock()

    async def load(self) -> dict[str, Any]:
        async with self._lock:
            if self.config_path.exists():
                try:
                    raw = json.loads(await asyncio.to_thread(self.config_path.read_text, encoding="utf-8"))
                except (OSError, json.JSONDecodeError) as error:
                    raise ConfigError(f"无法读取设置：{error}") from error
                missing_key = not str(raw.get("apiKey", "")).strip() if isinstance(raw, dict) else False
                config = normalize_config(raw)
                self._config = config
                self._revision = self._revision_of(config)
                if missing_key:
                    await self._persist(config, create_backup=True)
            else:
                config = default_config()
                self._config = config
                await self._persist(config, create_backup=False)
            return self.public_config()

    def public_config(self) -> dict[str, Any]:
        config = self._require_loaded()
        safe = config.model_dump(by_alias=True)
        api_key = safe.pop("apiKey")
        safe["apiKeyMasked"] = self._mask_secret(api_key)
        safe["revision"] = self._revision
        return safe

    def get_secret(self) -> str:
        return self._require_loaded().api_key

    def get_active_profile(self) -> object:
        config = self._require_loaded()
        return config.profiles[config.active_profile].model_copy(deep=True)

    def get_raw_config(self) -> LocalConfig:
        return self._require_loaded().model_copy(deep=True)

    async def update(self, candidate: dict[str, Any], expected_revision: str | None) -> dict[str, Any]:
        async with self._lock:
            current = self._require_loaded()
            if not expected_revision or expected_revision != self._revision:
                raise ConfigError("设置已在另一个窗口中更改，请重新加载后再试。", 409)
            next_value = dict(candidate)
            next_value.update({"version": 1, "apiKey": current.api_key})
            config = normalize_config(next_value)
            await self._persist(config, create_backup=True)
            self._config = config
            return self.public_config()

    async def rotate_api_key(self) -> str:
        async with self._lock:
            current = self._require_loaded()
            config = current.model_copy(update={"api_key": generate_api_key()}, deep=True)
            await self._persist(config, create_backup=True)
            self._config = config
            return config.api_key

    async def validate_runtime_files(self) -> dict[str, Any]:
        config = self._require_loaded()
        profile = config.profiles[config.active_profile].model_copy(deep=True)
        return await asyncio.to_thread(self._validate_runtime_files_sync, config, profile)

    async def list_models(self, directory: str | None = None) -> list[dict[str, str]]:
        root_text = str(directory if directory is not None else self._require_loaded().models_directory).strip()
        return await asyncio.to_thread(self._list_models_sync, root_text)

    async def _persist(self, config: LocalConfig, create_backup: bool) -> None:
        serialized = json.dumps(config.model_dump(by_alias=True), ensure_ascii=False, indent=2) + "\n"
        temporary = self.config_path.with_name(f"{self.config_path.name}.tmp")
        backup = self.config_path.with_name(f"{self.config_path.name}.bak")

        def write() -> None:
            self.config_path.parent.mkdir(parents=True, exist_ok=True)
            if create_backup and self.config_path.exists():
                shutil.copy2(self.config_path, backup)
            temporary.write_text(serialized, encoding="utf-8")
            try:
                os.chmod(temporary, 0o600)
            except OSError:
                pass
            os.replace(temporary, self.config_path)

        await asyncio.to_thread(write)
        self._revision = self._revision_of(config)

    @staticmethod
    def _validate_runtime_files_sync(config: LocalConfig, profile: object) -> dict[str, Any]:
        executable = ConfigStore._required_absolute_path(config.llama_server_path, "llama-server 可执行文件")
        model_root = ConfigStore._required_absolute_path(config.models_directory, "模型文件夹")
        model = ConfigStore._required_absolute_path(profile.model_path, "GGUF 模型")
        try:
            executable = executable.resolve(strict=True)
        except OSError as error:
            raise ConfigError(f"无法访问 llama-server 可执行文件：{error}") from error
        try:
            model_root = model_root.resolve(strict=True)
        except OSError as error:
            raise ConfigError(f"无法访问模型文件夹：{error}") from error
        try:
            model = model.resolve(strict=True)
        except OSError as error:
            raise ConfigError(f"无法访问 GGUF 模型：{error}") from error
        if not executable.is_file():
            raise ConfigError("llama-server 路径不是文件。")
        if executable.name.lower() not in {"llama-server", "llama-server.exe"}:
            raise ConfigError("可执行文件名称必须是 llama-server 或 llama-server.exe。")
        if not model_root.is_dir():
            raise ConfigError("模型文件夹路径不是文件夹。")
        if not model.is_file() or model.suffix.lower() != ".gguf":
            raise ConfigError("模型必须是现有的 .gguf 文件。")
        if not is_path_inside(model_root, model):
            raise ConfigError("所选模型必须位于已设置的模型文件夹内。")
        return {
            "executable": str(executable),
            "model_root": str(model_root),
            "model": str(model),
            "profile": profile,
        }

    @staticmethod
    def _list_models_sync(root_text: str) -> list[dict[str, str]]:
        if not root_text:
            return []
        root = Path(root_text)
        if not root.is_absolute():
            return []
        try:
            real_root = root.resolve(strict=True)
        except OSError:
            return []
        if not real_root.is_dir():
            return []
        models: list[dict[str, str]] = []
        pending: deque[Path] = deque([real_root])
        scanned_entries = 0
        while pending and len(models) < MAX_MODEL_RESULTS and scanned_entries < MAX_MODEL_SCAN_ENTRIES:
            directory = pending.popleft()
            try:
                entries = list(directory.iterdir())
            except OSError:
                continue
            for entry in entries:
                scanned_entries += 1
                if len(models) >= MAX_MODEL_RESULTS or scanned_entries > MAX_MODEL_SCAN_ENTRIES:
                    break
                try:
                    if entry.is_symlink():
                        continue
                    if entry.is_dir():
                        pending.append(entry)
                    elif entry.is_file() and entry.suffix.lower() == ".gguf":
                        models.append({
                            "path": str(entry),
                            "relativePath": str(entry.relative_to(real_root)),
                        })
                except OSError:
                    continue
        return sorted(models, key=lambda item: item["relativePath"].casefold())

    @staticmethod
    def _required_absolute_path(value: str, label: str) -> Path:
        text = str(value or "").strip()
        if not text:
            raise ConfigError(f"请先设置{label}。")
        path = Path(text)
        if not path.is_absolute():
            raise ConfigError(f"{label}必须使用绝对路径。")
        return path

    @staticmethod
    def _revision_of(config: LocalConfig) -> str:
        canonical = json.dumps(config.model_dump(by_alias=True), ensure_ascii=False, separators=(",", ":"))
        return hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:16]

    @staticmethod
    def _mask_secret(secret: str) -> str:
        return "••••••••••••" if secret else ""

    def _require_loaded(self) -> LocalConfig:
        if self._config is None:
            raise ConfigError("设置尚未加载。", 500)
        return self._config


def is_path_inside(root_path: Path | str, candidate_path: Path | str) -> bool:
    root = Path(root_path).resolve()
    candidate = Path(candidate_path).resolve()
    if candidate == root:
        return False
    try:
        candidate.relative_to(root)
        return True
    except ValueError:
        return False
