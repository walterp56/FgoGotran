import asyncio
import hashlib
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

from fgogotran_local.config_store import ConfigStore
from fgogotran_local.updates import UpdateManager
import httpx

ROOT = Path(__file__).parents[1]


def test_default_model_directory_is_inside_data_and_existing_keys_and_external_models_survive(tmp_path):
    async def exercise():
        store = ConfigStore(tmp_path / "user_data/config.json")
        config = await store.load()
        assert config["modelsDirectory"] == str(tmp_path / "user_data/models")
        assert Path(config["modelsDirectory"]).is_dir()
        raw = json.loads(store.config_path.read_text(encoding="utf-8"))
        key = raw["apiKey"]
        external = tmp_path / "external-models"
        external.mkdir()
        model = external / "example.gguf"
        model.write_bytes(b"model-not-to-be-moved")
        raw["modelsDirectory"] = str(external)
        raw["profiles"][raw["activeProfile"]]["modelPath"] = str(model)
        store.config_path.write_text(json.dumps(raw), encoding="utf-8")
        reopened = ConfigStore(store.config_path)
        config = await reopened.load()
        assert config["modelsDirectory"] == str(external)
        assert config["profiles"][config["activeProfile"]]["modelPath"] == str(model)
        assert reopened.get_secret() == key
        assert model.read_bytes() == b"model-not-to-be-moved"
    asyncio.run(exercise())


def test_backing_up_and_restoring_only_user_data_keeps_models_runtime_key_and_reminders(tmp_path):
    async def exercise():
        program = tmp_path / "FgoGotranLocal"
        data, backup = program / "user_data", tmp_path / "user_data_backup"
        store = ConfigStore(data / "config.json")
        await store.load()
        raw = json.loads(store.config_path.read_text(encoding="utf-8"))
        model = data / "models/example.gguf"
        model.write_bytes(b"model")
        runtime = data / "runtime/windows-x64/llama.cpp/llama-server.exe"
        runtime.parent.mkdir(parents=True)
        runtime.write_bytes(b"runtime")
        raw["llamaServerPath"] = str(runtime)
        raw["profiles"][raw["activeProfile"]]["modelPath"] = str(model)
        store.config_path.write_text(json.dumps(raw), encoding="utf-8")
        def respond(_):
            return httpx.Response(200, json={"manifestVersion": 1, "fgogotranLocal": {"versionCode": 2, "versionName": "v1.0.1"}})
        manager = UpdateManager(data, clock=lambda: 1000, transport=httpx.MockTransport(respond))
        await manager.check_for_prompt(active=False)
        await manager.reminder_action(2, "ignore")
        shutil.copytree(data, backup)
        retired = tmp_path / "retired_user_data"
        assert data.resolve().is_relative_to(tmp_path.resolve()) and retired.resolve().is_relative_to(tmp_path.resolve())
        data.rename(retired)
        (program / "program-version.txt").write_text("new program")
        shutil.copytree(backup, data)
        reopened = ConfigStore(data / "config.json")
        await reopened.load()
        assert json.loads((data / "config.json").read_text(encoding="utf-8")) == raw
        assert reopened.get_secret() == raw["apiKey"]
        assert model.read_bytes() == b"model" and runtime.read_bytes() == b"runtime"
        restored = UpdateManager(data, clock=lambda: 1001, transport=httpx.MockTransport(respond))
        assert restored.record["ignoredVersionCode"] == 2
        assert not (await restored.check_for_prompt(active=False))["showPrompt"]
    asyncio.run(exercise())


@pytest.mark.skipif(os.name != "nt", reason="Actual Windows Python bootstrap")
def test_real_setup_reuses_data_environment_and_preserves_legacy_venv_and_custom_data(tmp_path):
    program, data = tmp_path / "program with spaces", tmp_path / "custom user_data"
    (program / "src").mkdir(parents=True)
    legacy = program / ".venv"
    legacy.mkdir()
    (legacy / "keep.txt").write_text("legacy preserved")
    for name in ("requirements.txt", "pyproject.toml"):
        (program / name).write_bytes((ROOT / name).read_bytes())
    text = "".join((program / name).read_bytes().decode("utf-8") + "\n---\n"
                   for name in ("requirements.txt", "pyproject.toml"))
    digest = hashlib.sha256(text.encode()).hexdigest().upper()
    environment = data / "runtime/windows-x64/environments" / digest
    # Isolated environment only; installed project dependencies are read from
    # the existing test environment without any package/network installation.
    subprocess.run([sys.executable, "-m", "venv", "--without-pip", str(environment)], check=True, capture_output=True, timeout=45)
    (environment / ".fgogotran-local-dependencies").write_text("v2:" + digest, encoding="utf-8")
    (data / "models").mkdir()
    (data / "models/keep.gguf").write_bytes(b"user model")
    (data / "config.json").write_text('{"apiKey":"test-key-preserved"}', encoding="utf-8")
    site_packages = ROOT / ".venv/Lib/site-packages"
    env = {**os.environ, "PYTHONPATH": os.pathsep.join((str(ROOT / "src"), str(site_packages))),
           "PYTHONPYCACHEPREFIX": str(data / "cache/pycache"), "FGO_LOCAL_AUTO_SETUP": "0"}
    result = subprocess.run(["powershell.exe", "-NoProfile", "-File", str(ROOT / "platforms/windows-x64/setup.ps1"),
                             "-ProjectRoot", str(program), "-DataDirectory", str(data), "-Quiet"],
                            env=env, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=60)
    assert result.returncode == 0, result.stderr[-2000:]
    assert str(environment / "Scripts/python.exe") in result.stdout
    assert (environment / ".fgogotran-local-home").read_text(encoding="utf-8") == str(environment)
    assert (environment / "Lib/site-packages/fgogotran-local-src.pth").read_text(encoding="utf-8") == str(program / "src")
    assert (legacy / "keep.txt").read_text() == "legacy preserved"
    assert (data / "models/keep.gguf").read_bytes() == b"user model"
    assert json.loads((data / "config.json").read_text())["apiKey"] == "test-key-preserved"
    assert not (program / "user_data").exists()


@pytest.mark.skipif(os.name != "nt", reason="Windows managed-path preservation")
def test_preserving_incompatible_environment_is_bounded_to_custom_runtime_directory(tmp_path):
    runtime = tmp_path / "custom data/runtime/windows-x64"
    old = runtime / "environments/old"
    old.mkdir(parents=True)
    (old / "keep").write_text("keep")
    outside = tmp_path / "outside"
    outside.mkdir()
    script = tmp_path / "check.ps1"
    script.write_text('''param([string]$Setup,[string]$Runtime,[string]$Target,[string]$Outside)
$tokens=$null; $errors=$null
$ast=[System.Management.Automation.Language.Parser]::ParseFile($Setup,[ref]$tokens,[ref]$errors)
foreach($name in @('Move-Aside','Write-Step')) {
  $node=$ast.Find({param($item) $item -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $item.Name -eq $name},$true)
  Invoke-Expression $node.Extent.Text
}
$runtimeRoot=$Runtime; $Quiet=$true
Move-Aside $Target 'environment'
try { Move-Aside $Outside 'unrelated'; throw 'outside move was allowed' }
catch { if ($_.Exception.Message -notlike '*outside the managed runtime directory*') { throw } }
''', encoding="utf-8")
    subprocess.run(["powershell.exe", "-NoProfile", "-File", str(script), "-Setup", str(ROOT / "platforms/windows-x64/setup.ps1"),
                    "-Runtime", str(runtime), "-Target", str(old), "-Outside", str(outside)], check=True, capture_output=True, timeout=20)
    assert not old.exists() and outside.exists()
    backups = list(old.parent.glob("old.incompatible-*"))
    assert len(backups) == 1 and (backups[0] / "keep").read_text() == "keep"
