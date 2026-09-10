from __future__ import annotations

import re
import secrets
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator, model_validator

from .errors import ConfigError


PROFILE_ID_PATTERN = re.compile(r"^[a-z0-9][a-z0-9-]{0,39}$")
MODEL_ALIAS_PATTERN = re.compile(r"^[A-Za-z0-9._:-]+$")


def generate_api_key() -> str:
    return f"fgo_{secrets.token_urlsafe(32)}"


def _clean_text(value: Any, label: str, minimum: int, maximum: int) -> str:
    text = str(value or "").strip()
    if not minimum <= len(text) <= maximum or any(character in text for character in "\r\n\0"):
        raise ValueError(f"{label}无效。")
    return text


def _clean_path(value: Any, label: str) -> str:
    text = str(value or "").strip()
    if len(text) > 1024 or any(character in text for character in "\r\n\0"):
        raise ValueError(f"{label}无效。")
    return text


class ProfileConfig(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="ignore")

    display_name: str = Field(alias="displayName", default="FGO 本地翻译")
    model_path: str = Field(alias="modelPath", default="")
    model_alias: str = Field(alias="modelAlias", default="fgo-local-v1")
    host: Literal["127.0.0.1", "0.0.0.0"] = "0.0.0.0"
    port: int = Field(default=18080, ge=1024, le=65535)
    context_size: int = Field(alias="contextSize", default=8192, ge=512, le=262144)
    gpu_layers: int = Field(alias="gpuLayers", default=999, ge=0, le=999)
    batch_size: int = Field(alias="batchSize", default=512, ge=32, le=4096)
    ubatch_size: int = Field(alias="ubatchSize", default=256, ge=32, le=4096)
    threads: int = Field(default=0, ge=0, le=256)
    flash_attention: Literal["on", "off", "auto"] = Field(alias="flashAttention", default="on")
    disable_thinking: bool = Field(alias="disableThinking", default=False)
    prompt_cache: bool = Field(alias="promptCache", default=True)
    metrics: bool = True
    slots: bool = True
    web_ui: bool = Field(alias="webUi", default=False)

    @field_validator("display_name", mode="before")
    @classmethod
    def validate_display_name(cls, value: Any) -> str:
        return _clean_text(value, "Profile 名称", 1, 64)

    @field_validator("model_path", mode="before")
    @classmethod
    def validate_model_path(cls, value: Any) -> str:
        return _clean_path(value, "模型路径")

    @field_validator("model_alias", mode="before")
    @classmethod
    def validate_model_alias(cls, value: Any) -> str:
        alias = _clean_text(value, "Model ID", 1, 80)
        if not MODEL_ALIAS_PATTERN.fullmatch(alias):
            raise ValueError("Model ID 包含不支持的字符。")
        return alias

    @field_validator("host", mode="before")
    @classmethod
    def normalize_host(cls, value: Any) -> str:
        if value not in {"127.0.0.1", "0.0.0.0"}:
            raise ValueError("网络访问地址无效。")
        return value

    @field_validator("flash_attention", mode="before")
    @classmethod
    def normalize_flash_attention(cls, value: Any) -> str:
        return value if value in {"on", "off", "auto"} else "auto"

    @model_validator(mode="after")
    def validate_batch_sizes(self) -> "ProfileConfig":
        if self.ubatch_size > self.batch_size:
            raise ValueError("UBatch Size 不能大于 Batch Size。")
        return self


class LocalConfig(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="ignore")

    version: Literal[1] = 1
    llama_server_path: str = Field(alias="llamaServerPath", default="")
    models_directory: str = Field(alias="modelsDirectory", default="")
    active_profile: str = Field(alias="activeProfile", default="fgo-balanced")
    api_key: str = Field(alias="apiKey", default_factory=generate_api_key)
    profiles: dict[str, ProfileConfig]

    @field_validator("llama_server_path", mode="before")
    @classmethod
    def validate_llama_path(cls, value: Any) -> str:
        return _clean_path(value, "llama-server 路径")

    @field_validator("models_directory", mode="before")
    @classmethod
    def validate_models_directory(cls, value: Any) -> str:
        return _clean_path(value, "模型文件夹")

    @field_validator("active_profile", mode="before")
    @classmethod
    def validate_active_profile(cls, value: Any) -> str:
        profile_id = str(value or "").strip()
        if not PROFILE_ID_PATTERN.fullmatch(profile_id):
            raise ValueError("Profile ID 只能包含小写英文字母、数字和连字符。")
        return profile_id

    @field_validator("api_key", mode="before")
    @classmethod
    def validate_api_key(cls, value: Any) -> str:
        secret = str(value or "").strip()
        if not secret:
            return generate_api_key()
        if not 24 <= len(secret) <= 256 or any(character.isspace() or character == "\0" for character in secret):
            raise ValueError("API Key 无效。")
        return secret

    @field_validator("profiles", mode="before")
    @classmethod
    def validate_profiles_shape(cls, value: Any) -> Any:
        if not isinstance(value, dict) or not value:
            raise ValueError("至少需要一个 Profile。")
        if len(value) > 12:
            raise ValueError("最多只能创建 12 个 Profile。")
        for profile_id in value:
            if not PROFILE_ID_PATTERN.fullmatch(str(profile_id)):
                raise ValueError("Profile ID 只能包含小写英文字母、数字和连字符。")
        return value

    @model_validator(mode="after")
    def validate_active_profile_exists(self) -> "LocalConfig":
        if self.active_profile not in self.profiles:
            raise ValueError("当前 Profile 不存在。")
        return self


def default_config() -> LocalConfig:
    return LocalConfig(
        profiles={
            "fgo-balanced": ProfileConfig(displayName="FGO 本地翻译", modelAlias="fgo-local-v1")
        }
    )


def normalize_config(value: Any) -> LocalConfig:
    if not isinstance(value, dict):
        raise ConfigError("设置必须是 JSON 对象。")
    candidate = dict(value)
    candidate["version"] = 1
    try:
        return LocalConfig.model_validate(candidate)
    except ValidationError as error:
        first = error.errors(include_url=False)[0]
        message = str(first.get("ctx", {}).get("error") or first.get("msg") or "设置无效。")
        if message.startswith("Value error, "):
            message = message.removeprefix("Value error, ")
        raise ConfigError(message) from error


def profile_defaults(**overrides: Any) -> dict[str, Any]:
    return ProfileConfig(**overrides).model_dump(by_alias=True)
