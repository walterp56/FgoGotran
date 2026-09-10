from fgogotran_local.config_models import ProfileConfig
import pytest

from fgogotran_local.llama_arguments import build_llama_server_args, detect_thinking_control, runtime_profile_changed
from fgogotran_local.llama_metrics import parse_prometheus_metrics


def test_llama_arguments_keep_one_authenticated_translation_slot():
    profile = ProfileConfig(
        displayName="FGO Local",
        modelPath=r"C:\models\fgo.gguf",
        modelAlias="fgo-local-v1",
        host="0.0.0.0",
        port=18080,
        contextSize=8192,
        gpuLayers=999,
        batchSize=512,
        ubatchSize=256,
        flashAttention="on",
        disableThinking=True,
        promptCache=True,
        metrics=True,
        slots=True,
        webUi=False,
    )
    args = build_llama_server_args(
        {"model": r"C:\models\fgo.gguf", "profile": profile},
        r"C:\state\api-keys.txt",
        "reasoning",
    )

    assert args[:4] == ["--model", r"C:\models\fgo.gguf", "--alias", "fgo-local-v1"]
    assert args[args.index("--parallel") + 1] == "1"
    assert args[args.index("--host") + 1] == "0.0.0.0"
    assert "--api-key-file" in args
    assert "--offline" in args
    assert "--no-ui" in args
    assert "--cache-prompt" in args
    assert args[args.index("--reasoning") + 1] == "off"
    assert "--chat-template-kwargs" not in args


def test_thinking_control_prefers_new_reasoning_switch_and_falls_back_to_legacy():
    both = "  --chat-template-kwargs JSON\n  --reasoning [on|off|auto]\n"
    legacy = "  --chat-template-kwargs JSON\n"

    assert detect_thinking_control(both) == "reasoning"
    assert detect_thinking_control(legacy) == "chat-template-kwargs"
    assert detect_thinking_control("--model FILE\n") is None


def test_legacy_thinking_control_is_only_used_when_selected():
    profile = ProfileConfig(disableThinking=True)
    args = build_llama_server_args(
        {"model": r"C:\models\fgo.gguf", "profile": profile},
        r"C:\state\api-keys.txt",
        "chat-template-kwargs",
    )

    assert args[args.index("--chat-template-kwargs") + 1] == '{"enable_thinking":false}'
    assert "--reasoning" not in args


def test_disabling_thinking_requires_a_supported_runtime_switch():
    profile = ProfileConfig(disableThinking=True)

    with pytest.raises(ValueError, match="没有可用"):
        build_llama_server_args(
            {"model": r"C:\models\fgo.gguf", "profile": profile},
            r"C:\state\api-keys.txt",
        )


def test_model_default_does_not_require_a_thinking_control_switch():
    profile = ProfileConfig(disableThinking=False)

    args = build_llama_server_args(
        {"model": r"C:\models\fgo.gguf", "profile": profile},
        r"C:\state\api-keys.txt",
    )

    assert "--reasoning" not in args
    assert "--chat-template-kwargs" not in args


def test_prometheus_parser_accepts_current_metric_names():
    metrics = parse_prometheus_metrics(
        """
llamacpp:prompt_tokens_seconds 31.5
llamacpp:predicted_tokens_seconds 48.25
llamacpp:requests_processing 1
llamacpp:requests_deferred 2
llamacpp:prompt_tokens_total 120
"""
    )

    assert metrics["predictedTokensPerSecond"] == 48.25
    assert metrics["requestsProcessing"] == 1.0
    assert metrics["requestsDeferred"] == 2.0


def test_display_name_change_does_not_require_model_restart():
    running = ProfileConfig().model_dump(by_alias=True)
    running["displayName"] = "旧名称"

    renamed = {**running, "displayName": "新名称"}
    resized = {**running, "contextSize": 4096}

    assert runtime_profile_changed(running, renamed) is False
    assert runtime_profile_changed(running, resized) is True
