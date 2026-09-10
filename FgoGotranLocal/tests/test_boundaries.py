from pathlib import Path

from fgogotran_local.config_models import default_config


ROOT = Path(__file__).parents[1]


def test_distribution_has_one_user_facing_cmd():
    assert [path.name for path in ROOT.glob("*.cmd")] == ["Start-FgoGotranLocal.cmd"]
    launcher = (ROOT / "Start-FgoGotranLocal.cmd").read_text(encoding="utf-8")
    assert 'set "LOCAL_ROOT=%~dp0."' in launcher
    assert '"%LOCAL_ROOT%\\scripts\\windows\\start.ps1"' in launcher


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
