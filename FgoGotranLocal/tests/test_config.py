import asyncio
import json
from pathlib import Path

import pytest

from fgogotran_local.config_models import normalize_config
from fgogotran_local.config_store import ConfigStore, is_path_inside
from fgogotran_local.errors import ConfigError


def run(coroutine):
    return asyncio.run(coroutine)


def test_config_store_creates_private_key_and_translation_only_defaults(tmp_path: Path):
    store = ConfigStore(tmp_path / "config.json")
    public = run(store.load())

    assert store.get_secret().startswith("fgo_")
    assert len(store.get_secret()) >= 44
    assert public["activeProfile"] == "fgo-balanced"
    assert list(public["profiles"]) == ["fgo-balanced"]
    assert public["profiles"]["fgo-balanced"]["disableThinking"] is False
    assert "apiKey" not in public
    assert "voice" not in public
    assert json.loads((tmp_path / "config.json").read_text(encoding="utf-8"))["apiKey"] == store.get_secret()


def test_fresh_config_adopts_managed_llama_but_never_legacy_managed_model(tmp_path: Path):
    runtime = tmp_path / "runtime" / "llama.cpp" / "b-test-cpu"
    models = tmp_path / "models"
    runtime.mkdir(parents=True)
    models.mkdir()
    executable = runtime / "llama-server.exe"
    model = models / "managed.gguf"
    executable.write_text("test", encoding="utf-8")
    model.write_text("test", encoding="utf-8")
    manifest = {
        "version": 1,
        "llamaServerPath": str(executable),
        "modelsDirectory": str(models),
        "modelPath": str(model),
        "modelAlias": "managed-fgo-model",
    }
    manifest_path = tmp_path / "runtime" / "managed-runtime.json"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

    store = ConfigStore(tmp_path / "config.json")
    public = run(store.load())

    assert public["llamaServerPath"] == str(executable.resolve())
    assert public["modelsDirectory"] == ""
    assert public["profiles"]["fgo-balanced"]["modelPath"] == ""
    assert public["profiles"]["fgo-balanced"]["modelAlias"] == "fgo-local-v1"


def test_fresh_config_prefers_matching_platform_manifest(tmp_path: Path):
    legacy_runtime = tmp_path / "runtime" / "llama.cpp" / "legacy"
    platform_runtime = tmp_path / "runtime" / "windows-x64" / "llama.cpp" / "managed"
    legacy_runtime.mkdir(parents=True)
    platform_runtime.mkdir(parents=True)
    legacy_executable = legacy_runtime / "llama-server.exe"
    platform_executable = platform_runtime / "llama-server.exe"
    legacy_executable.write_text("legacy", encoding="utf-8")
    platform_executable.write_text("platform", encoding="utf-8")
    (tmp_path / "runtime" / "managed-runtime.json").write_text(
        json.dumps({"version": 1, "llamaServerPath": str(legacy_executable)}),
        encoding="utf-8",
    )
    (tmp_path / "runtime" / "windows-x64" / "managed-runtime.json").write_text(
        json.dumps({
            "version": 1,
            "platformId": "windows-x64",
            "llamaServerPath": str(platform_executable),
        }),
        encoding="utf-8",
    )

    store = ConfigStore(tmp_path / "config.json")
    public = run(store.load())

    assert public["llamaServerPath"] == str(platform_executable.resolve())


def test_config_ignores_manifest_for_another_platform(tmp_path: Path):
    runtime = tmp_path / "runtime" / "windows-x64" / "llama.cpp" / "foreign"
    runtime.mkdir(parents=True)
    executable = runtime / "llama-server.exe"
    executable.write_text("foreign", encoding="utf-8")
    (tmp_path / "runtime" / "windows-x64" / "managed-runtime.json").write_text(
        json.dumps({
            "version": 1,
            "platformId": "windows-arm64",
            "llamaServerPath": str(executable),
        }),
        encoding="utf-8",
    )

    store = ConfigStore(tmp_path / "config.json")
    public = run(store.load())

    assert public["llamaServerPath"] == ""


def test_managed_runtime_never_overwrites_existing_user_paths(tmp_path: Path):
    managed_runtime = tmp_path / "runtime" / "llama.cpp" / "managed"
    managed_models = tmp_path / "models"
    managed_runtime.mkdir(parents=True)
    managed_models.mkdir()
    managed_executable = managed_runtime / "llama-server.exe"
    managed_model = managed_models / "managed.gguf"
    managed_executable.write_text("managed", encoding="utf-8")
    managed_model.write_text("managed", encoding="utf-8")
    manifest = {
        "version": 1,
        "llamaServerPath": str(managed_executable),
        "modelsDirectory": str(managed_models),
        "modelPath": str(managed_model),
        "modelAlias": "managed-model",
    }
    (tmp_path / "runtime" / "managed-runtime.json").write_text(json.dumps(manifest), encoding="utf-8")

    external_root = tmp_path.parent / f"{tmp_path.name}-external"
    external_models = external_root / "models"
    external_models.mkdir(parents=True)
    external_executable = external_root / "llama-server.exe"
    external_model = external_models / "user.gguf"
    external_executable.write_text("user", encoding="utf-8")
    external_model.write_text("user", encoding="utf-8")
    config = {
        "version": 1,
        "llamaServerPath": str(external_executable),
        "modelsDirectory": str(external_models),
        "activeProfile": "fgo-balanced",
        "apiKey": "fgo_" + ("1" * 40),
        "profiles": {
            "fgo-balanced": {
                "displayName": "FGO 本地翻译",
                "modelPath": str(external_model),
                "modelAlias": "user-model",
            }
        },
    }
    (tmp_path / "config.json").write_text(json.dumps(config), encoding="utf-8")

    store = ConfigStore(tmp_path / "config.json")
    public = run(store.load())

    assert public["llamaServerPath"] == str(external_executable)
    assert public["modelsDirectory"] == str(external_models)
    assert public["profiles"]["fgo-balanced"]["modelPath"] == str(external_model)
    assert public["profiles"]["fgo-balanced"]["modelAlias"] == "user-model"


def test_managed_manifest_cannot_adopt_paths_outside_data_directory(tmp_path: Path):
    outside = tmp_path.parent / f"{tmp_path.name}-outside"
    outside.mkdir()
    executable = outside / "llama-server.exe"
    models = outside / "models"
    models.mkdir()
    model = models / "outside.gguf"
    executable.write_text("outside", encoding="utf-8")
    model.write_text("outside", encoding="utf-8")
    runtime = tmp_path / "runtime"
    runtime.mkdir()
    (runtime / "managed-runtime.json").write_text(
        json.dumps(
            {
                "version": 1,
                "llamaServerPath": str(executable),
                "modelsDirectory": str(models),
                "modelPath": str(model),
                "modelAlias": "outside-model",
            }
        ),
        encoding="utf-8",
    )

    store = ConfigStore(tmp_path / "config.json")
    public = run(store.load())

    assert public["llamaServerPath"] == ""
    assert public["modelsDirectory"] == ""
    assert public["profiles"]["fgo-balanced"]["modelPath"] == ""


def test_config_store_uses_selected_platform_manifest(tmp_path: Path):
    executable = tmp_path / "runtime" / "windows-arm64" / "llama-server.exe"
    executable.parent.mkdir(parents=True)
    executable.write_text("arm64", encoding="utf-8")
    manifest = {
        "version": 1,
        "platformId": "windows-arm64",
        "llamaServerPath": str(executable),
    }
    (executable.parent / "managed-runtime.json").write_text(json.dumps(manifest), encoding="utf-8")

    store = ConfigStore(tmp_path / "config.json", platform_id="windows-arm64")
    public = run(store.load())

    assert public["llamaServerPath"] == str(executable)


def test_config_store_rejects_unsafe_platform_id(tmp_path: Path):
    with pytest.raises(ConfigError, match="平台标识无效"):
        ConfigStore(tmp_path / "config.json", platform_id="..\\outside")


def test_config_store_rejects_stale_revision(tmp_path: Path):
    store = ConfigStore(tmp_path / "config.json")
    public = run(store.load())

    with pytest.raises(ConfigError) as captured:
        run(store.update(public, "stale-revision"))

    assert captured.value.status_code == 409


def test_config_store_persists_user_profile(tmp_path: Path):
    store = ConfigStore(tmp_path / "config.json")
    config = run(store.load())
    profile = dict(config["profiles"][config["activeProfile"]])
    profile["displayName"] = "我的本地翻译"
    profile["modelAlias"] = "my-fgo-local-v1"
    config["activeProfile"] = "my-profile"
    config["profiles"] = {"my-profile": profile}

    updated = run(store.update(config, config["revision"]))

    assert updated["activeProfile"] == "my-profile"
    assert updated["profiles"]["my-profile"]["displayName"] == "我的本地翻译"
    assert (tmp_path / "config.json.bak").exists()


def test_runtime_validation_accepts_gguf_inside_model_root(tmp_path: Path):
    models = tmp_path / "models"
    models.mkdir()
    executable = tmp_path / "llama-server.exe"
    executable.write_text("test", encoding="utf-8")
    model = models / "fgo-model.gguf"
    model.write_text("test", encoding="utf-8")

    store = ConfigStore(tmp_path / "config.json")
    config = run(store.load())
    config["llamaServerPath"] = str(executable)
    config["modelsDirectory"] = str(models)
    config["profiles"][config["activeProfile"]]["modelPath"] = str(model)
    run(store.update(config, config["revision"]))

    runtime = run(store.validate_runtime_files())

    assert runtime["model"] == str(model)
    assert is_path_inside(models, model)
    assert not is_path_inside(models, tmp_path)


def test_model_scan_skips_non_gguf_files(tmp_path: Path):
    models = tmp_path / "models"
    nested = models / "nested"
    nested.mkdir(parents=True)
    (models / "one.gguf").write_text("", encoding="utf-8")
    (nested / "two.GGUF").write_text("", encoding="utf-8")
    (models / "ignored.bin").write_text("", encoding="utf-8")
    store = ConfigStore(tmp_path / "config.json")
    run(store.load())

    found = run(store.list_models(str(models)))

    assert [item["relativePath"].replace("\\", "/") for item in found] == ["nested/two.GGUF", "one.gguf"]


def test_invalid_network_host_is_rejected():
    value = {
        "version": 1,
        "activeProfile": "fgo-balanced",
        "apiKey": "fgo_" + ("1" * 32),
        "profiles": {
            "fgo-balanced": {
                "displayName": "FGO 本地翻译",
                "modelAlias": "fgo-local-v1",
                "host": "unexpected-host",
            }
        },
    }

    with pytest.raises(ConfigError, match="网络访问地址无效"):
        normalize_config(value)
