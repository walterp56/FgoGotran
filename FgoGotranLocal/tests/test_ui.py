from pathlib import Path

import gradio as gr

from fgogotran_local.service import LocalTranslationService
from fgogotran_local.ui import STUDIO_CSS, UI_TAB_LABELS, build_ui


def test_gradio_ui_builds_without_starting_a_model(tmp_path: Path):
    icon = Path(__file__).parents[1] / "public" / "gotran-icon.png"
    studio = build_ui(LocalTranslationService(tmp_path), str(icon))

    assert isinstance(studio, gr.Blocks)


def test_ui_has_translation_only_primary_navigation(tmp_path: Path):
    icon = Path(__file__).parents[1] / "public" / "gotran-icon.png"
    studio = build_ui(LocalTranslationService(tmp_path), str(icon))
    config = studio.get_config_file()
    tab_labels = [
        component["props"]["label"]
        for component in config["components"]
        if component.get("type") == "tabitem"
    ]

    assert tuple(tab_labels[:4]) == UI_TAB_LABELS
    assert tab_labels[4:] == ["环境诊断", "运行日志"]
    assert "语音" not in " ".join(tab_labels)


def test_typography_is_offline_and_supports_chinese_and_paths():
    assert "Microsoft YaHei UI" in STUDIO_CSS
    assert "PingFang SC" in STUDIO_CSS
    assert "Noto Sans SC" in STUDIO_CSS
    assert "Cascadia Mono" in STUDIO_CSS
    assert "@font-face" not in STUDIO_CSS
    assert "url(" not in STUDIO_CSS


def test_model_settings_explain_when_disable_thinking_takes_effect(tmp_path: Path):
    icon = Path(__file__).parents[1] / "public" / "gotran-icon.png"
    studio = build_ui(LocalTranslationService(tmp_path), str(icon))
    config = studio.get_config_file()
    checkbox = next(
        component
        for component in config["components"]
        if component.get("type") == "checkbox" and component.get("props", {}).get("label") == "关闭模型思考"
    )

    assert checkbox["props"]["value"] is False
    assert "默认关闭" in checkbox["props"]["info"]
    assert "重新启动" in checkbox["props"]["info"]
