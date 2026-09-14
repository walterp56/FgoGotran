import json
from pathlib import Path

from fgogotran_local.config_models import default_config


ROOT = Path(__file__).parents[1]


def test_distribution_has_one_user_facing_cmd():
    assert [path.name for path in ROOT.glob("*.cmd")] == ["Start-FgoGotranLocal.cmd"]
    launcher = (ROOT / "Start-FgoGotranLocal.cmd").read_text(encoding="utf-8")
    assert 'set "LOCAL_ROOT=%~dp0."' in launcher
    assert '"%LOCAL_ROOT%\\scripts\\windows\\start.ps1"' in launcher


def test_startup_uses_verified_private_bootstrap_sources():
    start = (ROOT / "scripts" / "windows" / "start.ps1").read_text(encoding="utf-8")
    platform_root = ROOT / "platforms" / "windows-x64"
    platform = json.loads((platform_root / "platform.json").read_text(encoding="utf-8"))
    setup = (platform_root / "setup.ps1").read_text(encoding="utf-8")
    runtime = (platform_root / "runtime-setup.ps1").read_text(encoding="utf-8")

    assert "$runtimeSetupScript" in start
    assert "platforms\\windows-x64" in start
    assert platform["id"] == "windows-x64"
    assert platform["architecture"] == "x64"
    assert platform["pythonSetup"] == "setup.ps1"
    assert platform["runtimeSetup"] == "runtime-setup.ps1"
    assert platform["doctor"] == "doctor.ps1"
    assert platform["backendPriority"] == ["cuda", "cpu"]
    assert not (ROOT / "scripts" / "windows" / "setup.ps1").exists()
    assert not (ROOT / "scripts" / "windows" / "doctor.ps1").exists()
    assert not (ROOT / "scripts" / "windows" / "runtime-setup.ps1").exists()
    assert "FGO_LOCAL_AUTO_START_MODEL" in start
    assert "FGO_LOCAL_PLATFORM_ID" in start
    assert "Resolve-PlatformScript" in start
    assert "https://www.python.org/ftp/python/3.13.15/python-3.13.15-amd64.exe" in setup
    assert "Get-AuthenticodeSignature" in setup
    assert "edec09c4853aeae9ac36efb8c9f95b6b8e2fee65eee56d9767a8b7c69c574403" in setup
    assert "System.Text.UTF8Encoding($false)" in setup
    assert "https://api.github.com/repos/ggml-org/llama.cpp/releases" in runtime
    assert "$releaseResponse | ForEach-Object { $_ }" in runtime
    assert "CUDA(?: UMD)? Version" in runtime
    assert "Get-FileHash" in runtime
    assert "System.Text.UTF8Encoding($false)" in runtime
    assert "IsPathFullyQualified" not in runtime
    assert "LlamaPathStale" in runtime
    assert "Update-StaleLlamaConfigPath" in runtime
    assert "config.llamaServerPath = $ReplacementPath" in runtime
    assert "model settings were left unchanged" in runtime
    assert "FGO_LOCAL_DOWNLOAD_DEFAULT_MODEL" not in runtime
    assert "huggingface.co" not in runtime
    assert "Install-DefaultModel" not in runtime
    assert "modelPath" not in runtime
    assert "gguf" not in runtime.lower()


def test_translation_package_has_no_voice_runtime_modules():
    source_root = ROOT / "src" / "fgogotran_local"
    names = {path.name for path in source_root.glob("*.py")}
    combined_source = "\n".join(path.read_text(encoding="utf-8") for path in source_root.glob("*.py"))

    assert not {"gpt_sovits.py", "voice_inference.py", "voice_models.py", "voice_store.py"} & names
    assert "gpt_sovits" not in combined_source
    assert "VoiceInferenceService" not in combined_source
    assert "voice" not in default_config().model_dump(by_alias=True)


def test_large_external_runtime_files_are_gitignored():
    ignore = (ROOT / ".gitignore").read_text(encoding="utf-8")

    assert "*.gguf" in ignore
    assert "llama.cpp/" in ignore
    assert ".venv/" in ignore
    assert "user_data/" in ignore


def test_startup_diagnostics_do_not_print_computer_inventory():
    doctor = (ROOT / "platforms" / "windows-x64" / "doctor.ps1").read_text(encoding="utf-8")
    runtime = (ROOT / "platforms" / "windows-x64" / "runtime-setup.ps1").read_text(encoding="utf-8")

    assert "Project:" not in doctor
    assert "User data:" not in doctor
    assert "--query-gpu=name" not in doctor
    assert "NVIDIA GPU" not in doctor
    assert "Report 'OK' 'Configuration' $configPath" not in doctor
    assert "{ $llamaPath }" not in doctor
    assert "{ $modelPath }" not in doctor
    assert "not found at '$staleDisplay'" not in runtime
    assert "kept: $staleDisplay" not in runtime
