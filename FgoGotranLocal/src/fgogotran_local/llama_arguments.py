from __future__ import annotations

import json
import re
from typing import Any, Literal


ThinkingControl = Literal["reasoning", "chat-template-kwargs"]


def detect_thinking_control(help_text: str) -> ThinkingControl | None:
    """Return the best supported llama-server switch for disabling thinking."""
    if re.search(r"(?:^|\s)--reasoning(?:\s|=|$)", help_text):
        return "reasoning"
    if re.search(r"(?:^|\s)--chat-template-kwargs(?:\s|=|$)", help_text):
        return "chat-template-kwargs"
    return None


def build_llama_server_args(
    runtime: dict[str, Any],
    key_file: str,
    thinking_control: ThinkingControl | None = None,
) -> list[str]:
    profile = runtime["profile"]
    args = [
        "--model", runtime["model"],
        "--alias", profile.model_alias,
        "--host", profile.host,
        "--port", str(profile.port),
        "--ctx-size", str(profile.context_size),
        "--parallel", "1",
        "--batch-size", str(profile.batch_size),
        "--ubatch-size", str(profile.ubatch_size),
        "--n-gpu-layers", str(profile.gpu_layers),
        "--flash-attn", profile.flash_attention,
        "--api-key-file", key_file,
        "--cors-origins", "localhost",
        "--no-cors-credentials",
        "--offline",
        "--log-colors", "off",
        "--log-timestamps",
        "--verbosity", "3",
    ]
    if profile.threads > 0:
        args.extend(["--threads", str(profile.threads)])
    if profile.disable_thinking:
        if thinking_control == "reasoning":
            args.extend(["--reasoning", "off"])
        elif thinking_control == "chat-template-kwargs":
            args.extend(["--chat-template-kwargs", json.dumps({"enable_thinking": False}, separators=(",", ":"))])
        else:
            raise ValueError("关闭模型思考已启用，但没有可用的 llama-server 控制参数。")
    args.append("--cache-prompt" if profile.prompt_cache else "--no-cache-prompt")
    if profile.metrics:
        args.append("--metrics")
    if profile.slots:
        args.append("--slots")
    args.append("--ui" if profile.web_ui else "--no-ui")
    return args


RUNTIME_PROFILE_KEYS = (
    "modelPath",
    "modelAlias",
    "host",
    "port",
    "contextSize",
    "gpuLayers",
    "batchSize",
    "ubatchSize",
    "threads",
    "flashAttention",
    "disableThinking",
    "promptCache",
    "metrics",
    "slots",
    "webUi",
)


def runtime_profile_changed(running_profile: dict | None, configured_profile: dict | None) -> bool:
    if not running_profile or not configured_profile:
        return True
    return any(running_profile.get(key) != configured_profile.get(key) for key in RUNTIME_PROFILE_KEYS)
