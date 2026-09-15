import asyncio
from pathlib import Path
from unittest.mock import AsyncMock

import gradio as gr

from fgogotran_local.service import LocalTranslationService
from fgogotran_local.ui import (
    STUDIO_CSS,
    UI_TAB_LABELS,
    _profile_summary_markup,
    _error_message,
    _error_status_markup,
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


def test_sensitive_values_are_hidden_until_the_user_reveals_them(tmp_path: Path):
    icon = Path(__file__).parents[1] / "public" / "gotran-icon.png"
    studio = build_ui(LocalTranslationService(tmp_path), str(icon))
    config = studio.get_config_file()
    labels = [component.get("props", {}).get("label") for component in config["components"]]
    button_values = [
        component.get("props", {}).get("value")
        for component in config["components"]
        if component.get("type") == "button"
    ]

    assert labels.count("Endpoint") == 1
    assert labels.count("API Key") == 1
    assert "完整 Endpoint" not in labels
    assert "完整 API Key" not in labels
    assert "显示 Endpoint" in button_values
    assert "显示 API Key" in button_values
    assert "复制 Endpoint" not in button_values
    assert "复制 API Key" not in button_values
    assert "显示敏感信息" not in button_values
    sensitive_components = {
        component["props"]["label"]: component
        for component in config["components"]
        if component.get("type") == "textbox"
        and component.get("props", {}).get("label") in {"Endpoint", "API Key"}
    }
    for component in sensitive_components.values():
        field = component["props"]
        assert field.get("value") in {None, ""}
        assert field.get("buttons") == ["copy"]
    copy_buffers = {
        component["id"]
        for component in config["components"]
        if component.get("type") == "textbox"
        and component.get("props", {}).get("visible") is False
        and component.get("props", {}).get("show_label") is False
        and component.get("props", {}).get("value") == ""
    }
    copy_events = [
        dependency
        for dependency in config["dependencies"]
        if "navigator.clipboard.writeText" in str(dependency.get("js", ""))
    ]
    assert len(copy_buffers) == 2
    assert len(copy_events) == 2
    assert {dependency["inputs"][0] for dependency in copy_events} == copy_buffers
    assert all(dependency["outputs"][1] == dependency["inputs"][0] for dependency in copy_events)
    copy_preparations = [
        dependency
        for dependency in config["dependencies"]
        if dependency.get("api_name") in {"prepare_endpoint_copy", "prepare_key_copy"}
    ]
    assert {
        (target[0], target[1])
        for dependency in copy_preparations
        for target in dependency["targets"]
    } == {
        (sensitive_components["Endpoint"]["id"], "copy"),
        (sensitive_components["API Key"]["id"], "copy"),
    }
    serialized = str(config)
    assert "navigator.clipboard.writeText" in serialized
    assert "document.execCommand" in serialized
    assert "GPU / VRAM" not in serialized
    assert "系统内存" not in serialized
    assert "设备信息和 API Key 始终不会显示" in serialized


def test_hidden_sensitive_fields_copy_real_values_without_revealing_them(tmp_path: Path):
    icon = Path(__file__).parents[1] / "public" / "gotran-icon.png"
    service = LocalTranslationService(tmp_path)
    studio = build_ui(service, str(icon))
    callbacks = {
        block_function.fn.__name__: block_function.fn
        for block_function in studio.fns.values()
        if block_function.fn is not None
    }

    async def exercise_hidden_paths():
        await service.initialize()
        try:
            service.connection_details = AsyncMock(
                return_value={"endpoint": "http://192.168.3.18:18080/v1/chat/completions"}
            )
            copied_key = callbacks["prepare_key_copy"]()
            return (
                await callbacks["refresh_overview"](False, False),
                await callbacks["toggle_endpoint_reveal"](True),
                callbacks["toggle_key_reveal"](True),
                await callbacks["prepare_endpoint_copy"](),
                copied_key,
                service.api_key(),
                await callbacks["rotate_key"](True),
            )
        finally:
            await service.close()

    overview, hidden_endpoint, hidden_key, copied_endpoint, copied_key, key_before_rotation, rotated_key = asyncio.run(
        exercise_hidden_paths()
    )

    assert "••••••" in overview[1]
    assert overview[3] == "••••••••••••"
    assert "••••••" in hidden_endpoint[1]
    assert hidden_key[1] == "••••••••••••"
    assert copied_endpoint[0] == "http://192.168.3.18:18080/v1/chat/completions"
    assert "••••••" not in copied_endpoint[0]
    assert copied_endpoint[1] == ""
    assert copied_key[0] == key_before_rotation
    assert copied_key[0] != "••••••••••••"
    assert copied_key[1] == ""
    assert rotated_key[1] == "••••••••••••"


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
    assert "GPU" not in status_markup
    assert "VRAM" not in status_markup


def test_ui_error_messages_redact_local_paths_and_network_addresses():
    error = RuntimeError(r"Failed at C:\Users\Alice\Models\fgo.gguf on 192.168.1.20")

    for markup in (_error_message(error), _error_status_markup(error)):
        assert "Alice" not in markup
        assert "192.168.1.20" not in markup
        assert "路径已隐藏" in markup
