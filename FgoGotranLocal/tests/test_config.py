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
