from pathlib import Path

import gradio as gr

from fgogotran_local.service import LocalTranslationService
from fgogotran_local.ui import (
    STUDIO_CSS,
    UI_TAB_LABELS,
    _profile_summary_markup,
    _status_markup,
    build_ui,
)


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
        if component.get("type") == "checkbox"
        and component.get("props", {}).get("label") == "强制关闭模型思考"
    )

    assert checkbox["props"]["value"] is False
    assert "跟随模型" in checkbox["props"]["info"]
    assert "自动检测兼容性" in checkbox["props"]["info"]


def test_compatibility_states_and_fallback_reason_are_visible_and_escaped():
    status = {
        "state": "VERIFYING",
        "stateLabel": "正在检查兼容性",
        "message": "正在检查",
        "profile": {},
        "thinking": {
            "label": "跟随模型默认（兼容回退）",
            "reason": "model <template> conflict",
        },
        "compatibility": {
            "status": "FALLBACK",
            "message": "自动回退成功",
            "latencyMs": 123,
        },
    }

    status_markup = _status_markup(status)
    profile_markup = _profile_summary_markup(status)

    assert "status-loading" in status_markup
    assert "已通过（使用兼容回退）" in profile_markup
    assert "123 ms" in profile_markup
    assert "model &lt;template&gt; conflict" in profile_markup
    assert "model <template> conflict" not in profile_markup
