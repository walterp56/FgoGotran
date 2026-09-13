from __future__ import annotations

import asyncio
import hashlib
import json
import os
import re
import shutil
from collections import deque
from pathlib import Path
from typing import Any

from .config_models import LocalConfig, default_config, generate_api_key, normalize_config
from .errors import ConfigError


MAX_MODEL_RESULTS = 250
MAX_MODEL_SCAN_ENTRIES = 10_000
MAX_MANAGED_MANIFEST_BYTES = 64 * 1024
DEFAULT_PLATFORM_ID = "windows-x64"
PLATFORM_ID_PATTERN = re.compile(r"^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$")


class ConfigStore:
    def __init__(self, config_path: Path | str, platform_id: str = DEFAULT_PLATFORM_ID) -> None:
        self.config_path = Path(config_path).resolve()
        normalized_platform_id = str(platform_id).strip().lower()
        if not PLATFORM_ID_PATTERN.fullmatch(normalized_platform_id):
            raise ConfigError("本地平台标识无效。", 500)
        self.platform_id = normalized_platform_id
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
                candidate, managed_changed = self._apply_managed_defaults(raw)
                config = normalize_config(candidate)
                self._config = config
                self._revision = self._revision_of(config)
                if missing_key or managed_changed:
                    await self._persist(config, create_backup=True)
            else:
                candidate, _ = self._apply_managed_defaults(default_config().model_dump(by_alias=True))
                config = normalize_config(candidate)
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

    def _apply_managed_defaults(self, raw: Any) -> tuple[Any, bool]:
        if not isinstance(raw, dict):
            return raw, False
        manifest = self._load_managed_manifest()
        if manifest is None:
            return raw, False

        data_root = self.config_path.parent.resolve()
        candidate = json.loads(json.dumps(raw))
        changed = False

        llama = self._managed_file(manifest.get("llamaServerPath"), data_root)
        if (
            llama
            and llama.name.lower() in {"llama-server", "llama-server.exe"}
            and not str(candidate.get("llamaServerPath", "")).strip()
        ):
            candidate["llamaServerPath"] = str(llama)
            changed = True

        return candidate, changed

    def _load_managed_manifest(self) -> dict[str, Any] | None:
        runtime_root = self.config_path.parent / "runtime"
        candidates = (
            runtime_root / self.platform_id / "managed-runtime.json",
            runtime_root / "managed-runtime.json",
        )
        for manifest_path in candidates:
            try:
                if manifest_path.is_symlink() or not manifest_path.is_file():
                    continue
                if manifest_path.stat().st_size > MAX_MANAGED_MANIFEST_BYTES:
                    continue
                manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                continue
            if not isinstance(manifest, dict) or manifest.get("version") != 1:
                continue
            platform_id = str(manifest.get("platformId", "")).strip()
            if platform_id and platform_id != self.platform_id:
                continue
            return manifest
        return None

    @staticmethod
    def _managed_file(value: Any, data_root: Path) -> Path | None:
        path = ConfigStore._managed_path(value, data_root)
        return path if path and path.is_file() else None

    @staticmethod
    def _managed_path(value: Any, data_root: Path) -> Path | None:
        text = str(value or "").strip()
        if not text:
            return None
        path = Path(text)
        if not path.is_absolute():
            return None
        try:
            resolved = path.resolve(strict=True)
        except OSError:
            return None
        return resolved if is_path_inside(data_root, resolved) else None


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
