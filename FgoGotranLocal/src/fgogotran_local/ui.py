from __future__ import annotations

import base64
import copy
import html
import mimetypes
from pathlib import Path
from typing import Any

import gradio as gr

from .errors import StudioError
from .service import LocalTranslationService


STUDIO_CSS = """
:root {
  --fgo-ink: #132943;
  --fgo-navy: #173a61;
  --fgo-blue: #216ca6;
  --fgo-cyan: #37a7c9;
  --fgo-gold: #c79a4a;
  --fgo-line: #d9e4ee;
  --fgo-muted: #64758a;
  --fgo-success: #1f7655;
  --fgo-warning: #9a6700;
  --fgo-danger: #ad3939;
  --studio-font-sans: Inter, "Segoe UI Variable Text", "Segoe UI", "Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC", "Noto Sans SC", sans-serif;
  --studio-font-mono: "Cascadia Mono", "SFMono-Regular", Consolas, "Liberation Mono", monospace;
}
.gradio-container {
  width: min(1320px, calc(100% - 40px)) !important;
  max-width: 1320px !important;
  margin: 0 auto !important;
  padding-bottom: 42px !important;
  --font: var(--studio-font-sans);
  --font-mono: var(--studio-font-mono);
  font-family: var(--studio-font-sans) !important;
  font-size: 16px;
  line-height: 1.6;
  color: var(--fgo-ink);
  text-rendering: optimizeLegibility;
  -webkit-font-smoothing: antialiased;
}
.gradio-container button,
.gradio-container input,
.gradio-container textarea,
.gradio-container select { font-family: inherit !important; }
.gradio-container button { font-size: 1rem !important; font-weight: 650; line-height: 1.4; }
.gradio-container input,
.gradio-container textarea,
.gradio-container select { font-size: .9375rem !important; line-height: 1.5 !important; }
.gradio-container label,
.gradio-container [data-testid="block-info"] { font-size: .875rem; font-weight: 600; line-height: 1.45; }
.gradio-container h2 { font-size: 1.25rem !important; font-weight: 680; line-height: 1.35; }
.gradio-container h3 { font-size: 1.1rem !important; font-weight: 670; line-height: 1.4; }
.gradio-container p,
.gradio-container li { line-height: 1.65; }
.gradio-container button:focus-visible,
.gradio-container input:focus-visible,
.gradio-container textarea:focus-visible,
.gradio-container select:focus-visible {
  outline: 3px solid rgba(55, 167, 201, .28) !important;
  outline-offset: 2px;
}
.gradio-container code,
.code-text,
.code-field input,
.code-field textarea,
.log-field textarea {
  font-family: var(--studio-font-mono) !important;
  font-size: .875rem !important;
  font-variant-numeric: tabular-nums;
}
.studio-header {
  position: relative;
  overflow: hidden;
  border: 1px solid #cfdeea;
  border-radius: 20px;
  padding: 20px 24px;
  margin: 18px 0 16px;
  background: linear-gradient(120deg, #fbfdff 0%, #eaf4fb 70%, #fff8e9 100%);
  box-shadow: 0 12px 34px rgba(18, 48, 78, .08);
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 18px;
}
.studio-header::after {
  content: "";
  position: absolute;
  width: 180px;
  height: 180px;
  right: -68px;
  top: -86px;
  border: 1px solid rgba(199, 154, 74, .3);
  border-radius: 50%;
  box-shadow: 0 0 0 18px rgba(55, 167, 201, .06), 0 0 0 40px rgba(199, 154, 74, .04);
  pointer-events: none;
}
.studio-brand { display: flex; align-items: center; gap: 14px; }
.studio-brand img { width: 48px; height: 48px; border-radius: 13px; box-shadow: 0 5px 14px rgba(22, 59, 96, .14); }
.studio-brand h1 { margin: 0; color: var(--fgo-navy); font-size: 1.5rem; font-weight: 740; line-height: 1.25; }
.studio-brand p { margin: 3px 0 0; color: #607089; font-size: .9375rem; }
.privacy-pill {
  position: relative;
  z-index: 1;
  background: #e7f5ef;
  color: #216448;
  border: 1px solid #cce7da;
  border-radius: 999px;
  padding: 7px 12px;
  font-size: .8125rem;
  font-weight: 680;
  white-space: nowrap;
}
.studio-tabs { margin-top: 2px; }
.page-intro { margin: 10px 2px 16px; }
.page-intro .eyebrow { color: var(--fgo-blue); font-size: .75rem; font-weight: 760; letter-spacing: .12em; }
.page-intro h2 { color: var(--fgo-ink); margin: 2px 0; }
.page-intro p { color: var(--fgo-muted); margin: 0; max-width: 780px; font-size: .925rem; }
.panel-card {
  border: 1px solid var(--fgo-line) !important;
  border-radius: 17px !important;
  padding: 17px 18px !important;
  background: rgba(255, 255, 255, .96) !important;
  box-shadow: 0 8px 22px rgba(24, 54, 84, .045);
}
.panel-title { margin-bottom: 11px; }
.panel-title h3 { margin: 0 !important; color: var(--fgo-ink); }
.panel-title p { margin: 2px 0 0; color: var(--fgo-muted); font-size: .875rem; }
.status-card {
  height: 100%;
  border-radius: 17px;
  padding: 20px 21px;
  border: 1px solid var(--fgo-line);
  background: linear-gradient(145deg, #ffffff 0%, #f7fbfe 100%);
  box-shadow: 0 8px 22px rgba(24, 54, 84, .045);
}
.status-card h2 { margin: 2px 0 5px; color: var(--fgo-navy); }
.status-card p { margin: 4px 0 0; color: #5c6c80; font-size: .925rem; }
.status-heading { display: flex; justify-content: space-between; align-items: flex-start; gap: 14px; }
.status-eyebrow { color: #728096; font-size: .75rem; font-weight: 760; letter-spacing: .1em; }
.status-pill { border-radius: 999px; padding: 6px 10px; font-size: .79rem; font-weight: 760; white-space: nowrap; }
.status-ready { color: var(--fgo-success); background: #e9f6f0; }
.status-loading { color: var(--fgo-warning); background: #fff5d9; }
.status-error { color: var(--fgo-danger); background: #fff0f0; }
.status-stopped { color: #687587; background: #eef2f6; }
.metric-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; margin-top: 12px; }
.metric { border: 1px solid #e0e8f0; border-radius: 12px; padding: 12px; background: rgba(255, 255, 255, .78); }
.metric span { display: block; color: #728096; font-size: .8rem; font-weight: 620; line-height: 1.35; }
.metric strong { display: block; margin-top: 5px; color: #172d49; font-size: 1rem; font-weight: 680; overflow-wrap: anywhere; }
.workflow-strip { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; margin: 2px 0 16px; }
.workflow-step { display: flex; align-items: center; gap: 11px; border: 1px solid var(--fgo-line); border-radius: 14px; padding: 12px 14px; background: #fbfdff; }
.workflow-step span { display: grid; place-items: center; flex: 0 0 28px; height: 28px; border-radius: 50%; color: #fff; background: linear-gradient(145deg, var(--fgo-blue), var(--fgo-cyan)); font-size: .8rem; font-weight: 760; }
.workflow-step strong { color: var(--fgo-ink); font-size: .9rem; }
.workflow-step small { display: block; color: var(--fgo-muted); font-size: .78rem; line-height: 1.35; }
.action-copy { color: var(--fgo-muted); font-size: .9rem; margin: 0 0 12px; }
.inline-result { min-height: 34px; margin-top: 8px; }
.save-bar { margin-top: 13px; border-top: 1px solid #e4ebf2; padding-top: 13px; }
.checklist { margin: 0; padding-left: 1.25rem; color: var(--fgo-muted); }
.checklist li { margin: 7px 0; }
.security-note { border-left: 3px solid var(--fgo-gold); padding-left: 12px; color: var(--fgo-muted); font-size: .875rem; }
.restart-warning { color: var(--fgo-warning); font-weight: 680; }
.result-ok { color: var(--fgo-success); }
.result-error { color: var(--fgo-danger); }
@media (max-width: 850px) {
  .gradio-container { width: calc(100% - 20px) !important; font-size: 15px; }
  .studio-brand h1 { font-size: 1.25rem; }
  .metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .workflow-strip { grid-template-columns: 1fr; }
  .studio-header { align-items: flex-start; padding: 17px; }
  .privacy-pill { display: none; }
}
@media (max-width: 520px) {
  .metric-grid { grid-template-columns: 1fr; }
  .studio-brand img { width: 42px; height: 42px; }
}
"""


UI_TAB_LABELS = ("总览", "模型设置", "连接测试", "系统")


def build_ui(service: LocalTranslationService, icon_path: str) -> gr.Blocks:
    with gr.Blocks(title="FgoGotran Local", fill_width=True) as studio:
        config_state = gr.State({})
        editing_profile_state = gr.State("")
        endpoint_revealed_state = gr.State(False)
        key_revealed_state = gr.State(False)
        diagnostics_sensitive_state = gr.State(False)
        logs_sensitive_state = gr.State(False)

        gr.HTML(
            f"""
            <div class="studio-header">
              <div class="studio-brand">
                <img src="{_icon_data_uri(icon_path)}" alt="" />
                <div><h1>FgoGotran Local</h1><p>本地翻译模型设置与运行控制</p></div>
              </div>
              <span class="privacy-pill">管理页面仅限本机</span>
            </div>
            """
        )

        with gr.Tabs(elem_classes=["studio-tabs"]):
            with gr.Tab(UI_TAB_LABELS[0]):
                gr.HTML(_page_intro_markup(
                    "LOCAL RUNTIME",
                    "本地翻译服务",
                    "完成模型设置后，在这里启动 llama-server，并把连接信息填入 FgoGotran。",
                ))
                status_html = gr.HTML()
                gr.HTML(_workflow_markup())
                with gr.Row(equal_height=True):
                    with gr.Column(scale=2):
                        with gr.Group(elem_classes=["panel-card"]):
                            gr.HTML(_panel_title_markup("运行控制", "模型首次加载可能需要一些时间。"))
                            with gr.Row():
                                start_button = gr.Button("启动服务", variant="primary")
                                restart_button = gr.Button("重新启动")
                                stop_button = gr.Button("停止", variant="stop")
                            action_result = gr.Markdown(elem_classes=["inline-result"])
                    with gr.Column(scale=3):
                        with gr.Group(elem_classes=["panel-card"]):
                            gr.HTML(_panel_title_markup("FgoGotran 连接信息", "敏感信息默认隐藏；手机与电脑需连接同一个可信 Wi-Fi。"))
                            endpoint_box = gr.Textbox(label="Endpoint", interactive=False, buttons=["copy"], elem_classes=["code-field"])
                            model_id_box = gr.Textbox(label="Model ID", interactive=False, buttons=["copy"], elem_classes=["code-field"])
                            api_key_box = gr.Textbox(label="API Key", interactive=False, buttons=["copy"], elem_classes=["code-field"])
                            with gr.Row():
                                reveal_endpoint_button = gr.Button("显示 Endpoint", size="sm")
                                reveal_key_button = gr.Button("显示 API Key", size="sm")
                                rotate_key_button = gr.Button("更换 API Key", size="sm")
                            rotate_confirmation = gr.Checkbox(label="确认更换；手机中的旧 Key 将立即失效", value=False)
                            key_result = gr.Markdown(elem_classes=["inline-result"])
                profile_summary = gr.HTML()
                overview_timer = gr.Timer(value=1.5, active=True)

            with gr.Tab(UI_TAB_LABELS[1]):
                gr.HTML(_page_intro_markup(
                    "MODEL SETUP",
                    "模型设置",
                    "llama.cpp 可由启动器自动准备或手动选择；GGUF 模型始终由用户下载，程序不会移动模型文件。",
                ))
                with gr.Group(elem_classes=["panel-card"]):
                    with gr.Row():
                        profile_selector = gr.Dropdown(label="当前 Profile", choices=[], interactive=True, scale=4)
                        add_profile_button = gr.Button("新建", size="sm", scale=1)
                        duplicate_profile_button = gr.Button("复制", size="sm", scale=1)
                    with gr.Accordion("删除当前 Profile", open=False):
                        with gr.Row():
                            delete_confirmation = gr.Checkbox(label="确认删除", value=False, scale=3)
                            delete_profile_button = gr.Button("删除 Profile", size="sm", variant="stop", scale=1)
                    profile_message = gr.Markdown("修改后的设置需要保存；运行中的模型通常需要重新启动。")

                with gr.Row(equal_height=True):
                    with gr.Column(scale=1):
                        with gr.Group(elem_classes=["panel-card"]):
                            gr.HTML(_panel_title_markup("运行文件", "必须保留 llama.cpp 压缩包中的 DLL。"))
                            llama_path = gr.Textbox(
                                label="llama-server.exe",
                                placeholder=r"C:\path\to\llama-server.exe",
                                elem_classes=["code-field"],
                            )
                            models_directory = gr.Textbox(
                                label="GGUF 模型文件夹",
                                placeholder=r"C:\models",
                                elem_classes=["code-field"],
                            )
                            with gr.Row():
                                model_path = gr.Dropdown(
                                    label="GGUF 模型",
                                    choices=[],
                                    allow_custom_value=True,
                                    interactive=True,
                                    elem_classes=["code-field"],
                                    scale=4,
                                )
                                refresh_models_button = gr.Button("扫描 GGUF", size="sm", scale=1)
                    with gr.Column(scale=1):
                        with gr.Group(elem_classes=["panel-card"]):
                            gr.HTML(_panel_title_markup("服务身份与网络", "Model ID 必须与 Android 应用中的设置一致。"))
                            profile_display_name = gr.Textbox(label="Profile 名称", max_lines=1)
                            profile_model_alias = gr.Textbox(label="Model ID", max_lines=1, elem_classes=["code-field"])
                            with gr.Row():
                                inference_port = gr.Number(label="API Port", value=18080, precision=0)
                                inference_host = gr.Dropdown(
                                    label="网络访问",
                                    choices=[
                                        ("可信局域网 + 本机", "0.0.0.0"),
                                        ("仅限本机", "127.0.0.1"),
                                    ],
                                    value="0.0.0.0",
                                )

                with gr.Accordion("Advanced Settings · 推理参数", open=False):
                    gr.Markdown("不了解这些参数时保留默认值。显存不足时先减小 Context Size。")
                    with gr.Row():
                        context_size = gr.Number(label="Context Size", value=8192, precision=0)
                        gpu_layers = gr.Number(label="GPU Layers", value=999, precision=0)
                    with gr.Row():
                        batch_size = gr.Number(label="Batch Size", value=512, precision=0)
                        ubatch_size = gr.Number(label="UBatch Size", value=256, precision=0)
                        cpu_threads = gr.Number(label="CPU Threads（0 = Auto）", value=0, precision=0)
                    flash_attention = gr.Dropdown(
                        label="Flash Attention",
                        choices=[("开启", "on"), ("Auto", "auto"), ("关闭", "off")],
                        value="on",
                    )
                    with gr.Row():
                        disable_thinking = gr.Checkbox(
                            label="强制关闭模型思考",
                            info="默认不启用，即跟随模型。启用后会自动检测兼容性；不兼容时安全回退到模型默认。",
                            value=False,
                        )
                        prompt_cache = gr.Checkbox(label="Prompt Cache", value=True)
                        metrics_enabled = gr.Checkbox(label="Metrics", value=True)
                        slots_enabled = gr.Checkbox(label="Slots", value=True)
                with gr.Row(elem_classes=["save-bar"]):
                    save_profile_button = gr.Button("保存并设为当前 Profile", variant="primary")

            with gr.Tab(UI_TAB_LABELS[2]):
                gr.HTML(_page_intro_markup(
                    "CONNECTION TEST",
                    "连接测试",
                    "确认模型已完成加载，并能通过 OpenAI Chat Completions 格式返回译文。",
                ))
                with gr.Row(equal_height=True):
                    with gr.Column(scale=3):
                        with gr.Group(elem_classes=["panel-card"]):
                            gr.HTML(_panel_title_markup("API 兼容性测试", "测试只验证连接和响应格式，不代表最终翻译质量。"))
                            test_button = gr.Button("开始测试", variant="primary")
                            test_result = gr.Markdown(elem_classes=["inline-result"])
                    with gr.Column(scale=2):
                        with gr.Group(elem_classes=["panel-card"]):
                            gr.HTML(_panel_title_markup("通过条件", "三项均正常后再连接手机。"))
                            gr.HTML(
                                """
                                <ul class="checklist">
                                  <li>llama-server 状态为“已就绪”</li>
                                  <li>API Key、Model ID 和请求格式正确</li>
                                  <li>模型返回非空中文文本</li>
                                </ul>
                                """
                            )
                gr.Markdown(
                    "Windows 首次询问防火墙权限时，只允许**专用网络**。不要在公共 Wi-Fi 或路由器端口转发中开放此服务。",
                    elem_classes=["security-note"],
                )

            with gr.Tab(UI_TAB_LABELS[3]):
                gr.HTML(_page_intro_markup(
                    "SYSTEM",
                    "系统",
                    "检查运行环境并查看 llama-server 日志。日志不会保存翻译提示词或游戏对白。",
                ))
                with gr.Tabs():
                    with gr.Tab("环境诊断"):
                        with gr.Group(elem_classes=["panel-card"]):
                            with gr.Row():
                                diagnostics_button = gr.Button("运行诊断", variant="primary")
                                diagnostics_privacy_button = gr.Button("显示敏感信息")
                            gr.Markdown("本机路径和局域网地址默认隐藏；显示状态不会保存。", elem_classes=["security-note"])
                            diagnostics_output = gr.Markdown()
                    with gr.Tab("运行日志"):
                        with gr.Group(elem_classes=["panel-card"]):
                            with gr.Row():
                                log_levels = gr.CheckboxGroup(
                                    label="显示级别",
                                    choices=["INFO", "WARN", "ERROR"],
                                    value=["INFO", "WARN", "ERROR"],
                                    scale=4,
                                )
                                refresh_logs_button = gr.Button("刷新", size="sm", scale=1)
                                logs_privacy_button = gr.Button("显示敏感信息", size="sm", scale=1)
                                clear_logs_button = gr.Button("清除显示", size="sm", scale=1)
                            gr.Markdown("IP 和本机路径默认隐藏；API Key 无论何时都不会显示。", elem_classes=["security-note"])
                            logs_box = gr.Textbox(
                                label="Runtime Log",
                                lines=22,
                                interactive=False,
                                autoscroll=True,
                                buttons=["copy"],
                                elem_classes=["log-field"],
                            )
                            logs_timer = gr.Timer(value=1.5, active=True)

        profile_fields = [
            llama_path,
            models_directory,
            profile_display_name,
            profile_model_alias,
            model_path,
            inference_port,
            inference_host,
            context_size,
            gpu_layers,
            batch_size,
            ubatch_size,
            cpu_threads,
            flash_attention,
            disable_thinking,
            prompt_cache,
            metrics_enabled,
            slots_enabled,
        ]
        profile_state_outputs = [
            config_state,
            editing_profile_state,
            profile_selector,
            *profile_fields,
            profile_message,
        ]

        async def load_initial():
            config = service.public_config(include_sensitive=True)
            profile_id = config["activeProfile"]
            return _profile_state_result(config, profile_id, "设置已加载。")

        async def refresh_overview(endpoint_revealed: bool = False, key_revealed: bool = False):
            try:
                status = await service.status()
                config = service.public_config()
                running = bool(status.get("pid"))
                ready = status.get("state") in {"READY", "BUSY"}
                if endpoint_revealed:
                    sensitive_status = await service.status(include_sensitive=True)
                    endpoint_value = sensitive_status["connection"]["endpoint"]
                else:
                    endpoint_value = status["connection"]["endpoint"]
                key_value = service.api_key() if key_revealed else config["apiKeyMasked"]
                return (
                    _status_markup(status),
                    endpoint_value,
                    status["connection"]["modelAlias"],
                    key_value,
                    gr.update(interactive=not running),
                    gr.update(interactive=running),
                    gr.update(interactive=running),
                    gr.update(interactive=ready),
                    gr.update(interactive=not running),
                    _profile_summary_markup(status),
                )
            except Exception as error:
                return (
                    _error_status_markup(error),
                    "",
                    "",
                    "",
                    gr.update(interactive=False),
                    gr.update(interactive=False),
                    gr.update(interactive=False),
                    gr.update(interactive=False),
                    gr.update(interactive=False),
                    "",
                )

        overview_outputs = [
            status_html,
            endpoint_box,
            model_id_box,
            api_key_box,
            start_button,
            restart_button,
            stop_button,
            test_button,
            rotate_key_button,
            profile_summary,
        ]

        async def run_action(name: str) -> str:
            try:
                if name == "start":
                    await service.start()
                elif name == "stop":
                    await service.stop()
                elif name == "restart":
                    await service.restart()
                return f"<span class='result-ok'>{_action_label(name)}操作已完成。</span>"
            except Exception as error:
                return _error_message(error)

        async def start_action() -> str:
            return await run_action("start")

        async def stop_action() -> str:
            return await run_action("stop")

        async def restart_action() -> str:
            return await run_action("restart")

        async def run_test() -> str:
            try:
                result = await service.test_compatibility()
                response = html.escape(result["response"])
                return f"<span class='result-ok'>兼容性测试通过，耗时 {result['latencyMs']} 毫秒。</span>\n\n返回：`{response}`"
            except Exception as error:
                return _error_message(error)

        async def toggle_endpoint_reveal(is_visible: bool):
            reveal = not bool(is_visible)
            if reveal:
                status = await service.status(include_sensitive=True)
                return (
                    True,
                    status["connection"]["endpoint"],
                    gr.update(value="隐藏 Endpoint"),
                )
            status = await service.status()
            return False, status["connection"]["endpoint"], gr.update(value="显示 Endpoint")

        def toggle_key_reveal(is_visible: bool):
            reveal = not bool(is_visible)
            if reveal:
                return True, service.api_key(), gr.update(value="隐藏 API Key")
            return False, service.public_config()["apiKeyMasked"], gr.update(value="显示 API Key")

        async def rotate_key(confirmed: bool):
            if not confirmed:
                return (
                    gr.update(),
                    gr.update(),
                    gr.update(),
                    gr.update(),
                    "<span class='result-error'>请先勾选确认更换 API Key。</span>",
                    False,
                )
            try:
                result = await service.rotate_api_key()
                return (
                    result["config"],
                    result["config"]["apiKeyMasked"],
                    False,
                    gr.update(value="显示 API Key"),
                    "<span class='result-ok'>API Key 已更换。点击“显示 API Key”后复制到手机。</span>",
                    False,
                )
            except Exception as error:
                return gr.update(), gr.update(), gr.update(), gr.update(), _error_message(error), False

        async def change_profile(new_profile_id: str, config: dict, editing_id: str, *values):
            draft = _apply_profile_form(config, editing_id, values)
            if new_profile_id not in draft.get("profiles", {}):
                new_profile_id = draft.get("activeProfile", "")
            return _profile_state_result(draft, new_profile_id, "正在编辑，尚未保存。")

        async def add_profile(config: dict, editing_id: str, *values):
            draft = _apply_profile_form(config, editing_id, values)
            if len(draft.get("profiles", {})) >= 12:
                return _profile_state_result(draft, editing_id, "<span class='result-error'>最多只能创建 12 个 Profile。</span>")
            number = 1
            while f"fgo-profile-{number}" in draft["profiles"]:
                number += 1
            profile_id = f"fgo-profile-{number}"
            template = copy.deepcopy(draft["profiles"].get(editing_id) or next(iter(draft["profiles"].values())))
            template.update({
                "displayName": f"新 Profile {number}",
                "modelAlias": _unique_alias(draft, f"fgo-local-profile-{number}"),
                "modelPath": "",
            })
            draft["profiles"][profile_id] = template
            return _profile_state_result(draft, profile_id, "新 Profile 已建立，保存后写入设置。")

        async def duplicate_profile(config: dict, editing_id: str, *values):
            draft = _apply_profile_form(config, editing_id, values)
            if len(draft.get("profiles", {})) >= 12:
                return _profile_state_result(draft, editing_id, "<span class='result-error'>最多只能创建 12 个 Profile。</span>")
            number = 1
            while f"fgo-profile-{number}" in draft["profiles"]:
                number += 1
            profile_id = f"fgo-profile-{number}"
            template = copy.deepcopy(draft["profiles"].get(editing_id) or next(iter(draft["profiles"].values())))
            template["displayName"] = f"{template.get('displayName', 'Profile')} 副本"
            template["modelAlias"] = _unique_alias(draft, f"{template.get('modelAlias', 'fgo-local')}-copy")
            draft["profiles"][profile_id] = template
            return _profile_state_result(draft, profile_id, "Profile 已复制，保存后写入设置。")

        async def delete_profile(confirmed: bool, config: dict, editing_id: str, *values):
            draft = _apply_profile_form(config, editing_id, values)
            if not confirmed:
                return (*_profile_state_result(draft, editing_id, "<span class='result-error'>请先确认删除。</span>"), False)
            if len(draft.get("profiles", {})) <= 1:
                return (*_profile_state_result(draft, editing_id, "<span class='result-error'>至少需要一个 Profile。</span>"), False)
            draft["profiles"].pop(editing_id, None)
            next_id = draft.get("activeProfile")
            if next_id not in draft["profiles"]:
                next_id = next(iter(draft["profiles"]))
            draft["activeProfile"] = next_id
            return (*_profile_state_result(draft, next_id, "Profile 已从草稿删除，保存后生效。"), False)

        async def save_profile(config: dict, editing_id: str, *values):
            draft = _apply_profile_form(config, editing_id, values)
            draft["activeProfile"] = editing_id
            try:
                saved = await service.update_config(draft, draft.get("revision"))
                return _profile_state_result(saved, saved["activeProfile"], "<span class='result-ok'>设置已保存。</span>")
            except Exception as error:
                return _profile_state_result(draft, editing_id, _error_message(error))

        async def scan_models(directory: str, current_model: str):
            models = await service.list_models(directory)
            choices = [(item["relativePath"], item["path"]) for item in models]
            return gr.update(choices=choices, value=current_model or None)

        def render_logs(levels: list[str] | None, include_sensitive: bool = False) -> str:
            return service.formatted_logs(set(levels or []), include_sensitive=bool(include_sensitive))

        def clear_log_display(levels: list[str] | None, include_sensitive: bool) -> str:
            service.clear_logs()
            return render_logs(levels, include_sensitive)

        def toggle_logs_privacy(levels: list[str] | None, is_visible: bool):
            reveal = not bool(is_visible)
            return (
                reveal,
                gr.update(value="隐藏敏感信息" if reveal else "显示敏感信息"),
                render_logs(levels, reveal),
            )

        async def run_diagnostics(include_sensitive: bool = False) -> str:
            try:
                return _diagnostics_markdown(await service.diagnostics(include_sensitive=bool(include_sensitive)))
            except Exception as error:
                return _error_message(error)

        async def toggle_diagnostics_privacy(is_visible: bool):
            reveal = not bool(is_visible)
            return (
                reveal,
                gr.update(value="隐藏敏感信息" if reveal else "显示敏感信息"),
                await run_diagnostics(reveal),
            )

        studio.load(load_initial, outputs=profile_state_outputs, api_visibility="private")
        studio.load(
            refresh_overview,
            inputs=[endpoint_revealed_state, key_revealed_state],
            outputs=overview_outputs,
            api_visibility="private",
        )
        studio.load(render_logs, inputs=[log_levels, logs_sensitive_state], outputs=[logs_box], api_visibility="private")
        overview_timer.tick(
            refresh_overview,
            inputs=[endpoint_revealed_state, key_revealed_state],
            outputs=overview_outputs,
            api_visibility="private",
        )
        logs_timer.tick(render_logs, inputs=[log_levels, logs_sensitive_state], outputs=[logs_box], api_visibility="private")

        start_button.click(start_action, outputs=[action_result], concurrency_id="llama-control", concurrency_limit=1, api_visibility="private")
        stop_button.click(stop_action, outputs=[action_result], concurrency_id="llama-control", concurrency_limit=1, api_visibility="private")
        restart_button.click(restart_action, outputs=[action_result], concurrency_id="llama-control", concurrency_limit=1, api_visibility="private")
        test_button.click(run_test, outputs=[test_result], concurrency_id="llama-control", concurrency_limit=1, api_visibility="private")
        reveal_endpoint_button.click(
            toggle_endpoint_reveal,
            inputs=[endpoint_revealed_state],
            outputs=[endpoint_revealed_state, endpoint_box, reveal_endpoint_button],
            api_visibility="private",
        )
        reveal_key_button.click(
            toggle_key_reveal,
            inputs=[key_revealed_state],
            outputs=[key_revealed_state, api_key_box, reveal_key_button],
            api_visibility="private",
        )
        rotate_key_button.click(
            rotate_key,
            inputs=[rotate_confirmation],
            outputs=[
                config_state,
                api_key_box,
                key_revealed_state,
                reveal_key_button,
                key_result,
                rotate_confirmation,
            ],
            concurrency_id="llama-control",
            concurrency_limit=1,
            api_visibility="private",
        )

        profile_inputs = [config_state, editing_profile_state, *profile_fields]
        profile_selector.input(change_profile, inputs=[profile_selector, *profile_inputs], outputs=profile_state_outputs, api_visibility="private")
        add_profile_button.click(add_profile, inputs=profile_inputs, outputs=profile_state_outputs, api_visibility="private")
        duplicate_profile_button.click(duplicate_profile, inputs=profile_inputs, outputs=profile_state_outputs, api_visibility="private")
        delete_profile_button.click(
            delete_profile,
            inputs=[delete_confirmation, *profile_inputs],
            outputs=[*profile_state_outputs, delete_confirmation],
            api_visibility="private",
        )
        save_profile_button.click(
            save_profile,
            inputs=profile_inputs,
            outputs=profile_state_outputs,
            concurrency_id="llama-control",
            concurrency_limit=1,
            api_visibility="private",
        )
        refresh_models_button.click(scan_models, inputs=[models_directory, model_path], outputs=[model_path], api_visibility="private")
        diagnostics_button.click(
            run_diagnostics,
            inputs=[diagnostics_sensitive_state],
            outputs=[diagnostics_output],
            api_visibility="private",
        )
        diagnostics_privacy_button.click(
            toggle_diagnostics_privacy,
            inputs=[diagnostics_sensitive_state],
            outputs=[diagnostics_sensitive_state, diagnostics_privacy_button, diagnostics_output],
            api_visibility="private",
        )
        refresh_logs_button.click(render_logs, inputs=[log_levels, logs_sensitive_state], outputs=[logs_box], api_visibility="private")
        log_levels.change(render_logs, inputs=[log_levels, logs_sensitive_state], outputs=[logs_box], api_visibility="private")
        logs_privacy_button.click(
            toggle_logs_privacy,
            inputs=[log_levels, logs_sensitive_state],
            outputs=[logs_sensitive_state, logs_privacy_button, logs_box],
            api_visibility="private",
        )
        clear_logs_button.click(clear_log_display, inputs=[log_levels, logs_sensitive_state], outputs=[logs_box], api_visibility="private")

    studio.queue(default_concurrency_limit=4, max_size=32)
    return studio


def studio_theme():
    return gr.themes.Soft(
        primary_hue="blue",
        secondary_hue="sky",
        neutral_hue="slate",
        radius_size="md",
    )


def _page_intro_markup(eyebrow: str, title: str, description: str) -> str:
    return f"<div class='page-intro'><div class='eyebrow'>{html.escape(eyebrow)}</div><h2>{html.escape(title)}</h2><p>{html.escape(description)}</p></div>"


def _panel_title_markup(title: str, description: str) -> str:
    return f"<div class='panel-title'><h3>{html.escape(title)}</h3><p>{html.escape(description)}</p></div>"


def _workflow_markup() -> str:
    return """
    <div class="workflow-strip" aria-label="设置流程">
      <div class="workflow-step"><span>1</span><div><strong>准备文件</strong><small>下载 llama.cpp 与 GGUF</small></div></div>
      <div class="workflow-step"><span>2</span><div><strong>保存并启动</strong><small>等待状态变为已就绪</small></div></div>
      <div class="workflow-step"><span>3</span><div><strong>连接手机</strong><small>填写 Endpoint、Model ID 与 Key</small></div></div>
    </div>
    """


def _profile_state_result(config: dict, profile_id: str, message: str) -> tuple:
    choices = [(profile["displayName"], item_id) for item_id, profile in config["profiles"].items()]
    profile = config["profiles"][profile_id]
    return (
        config,
        profile_id,
        gr.update(choices=choices, value=profile_id),
        config.get("llamaServerPath", ""),
        config.get("modelsDirectory", ""),
        profile.get("displayName", ""),
        profile.get("modelAlias", ""),
        gr.update(value=profile.get("modelPath") or None),
        profile.get("port", 18080),
        profile.get("host", "0.0.0.0"),
        profile.get("contextSize", 8192),
        profile.get("gpuLayers", 999),
        profile.get("batchSize", 512),
        profile.get("ubatchSize", 256),
        profile.get("threads", 0),
        profile.get("flashAttention", "on"),
        profile.get("disableThinking", False),
        profile.get("promptCache", True),
        profile.get("metrics", True),
        profile.get("slots", True),
        message,
    )


def _apply_profile_form(config: dict, profile_id: str, values: tuple) -> dict:
    draft = copy.deepcopy(config or {})
    profiles = draft.setdefault("profiles", {})
    if profile_id not in profiles or len(values) != 17:
        return draft
    (
        llama_path,
        models_directory,
        display_name,
        model_alias,
        model_path,
        port,
        host,
        context_size,
        gpu_layers,
        batch_size,
        ubatch_size,
        threads,
        flash_attention,
        disable_thinking,
        prompt_cache,
        metrics,
        slots,
    ) = values
    draft["llamaServerPath"] = str(llama_path or "").strip()
    draft["modelsDirectory"] = str(models_directory or "").strip()
    profiles[profile_id].update({
        "displayName": str(display_name or "").strip(),
        "modelAlias": str(model_alias or "").strip(),
        "modelPath": str(model_path or "").strip(),
        "port": _integer(port),
        "host": host,
        "contextSize": _integer(context_size),
        "gpuLayers": _integer(gpu_layers),
        "batchSize": _integer(batch_size),
        "ubatchSize": _integer(ubatch_size),
        "threads": _integer(threads),
        "flashAttention": flash_attention,
        "disableThinking": bool(disable_thinking),
        "promptCache": bool(prompt_cache),
        "metrics": bool(metrics),
        "slots": bool(slots),
    })
    return draft


def _unique_alias(config: dict, base: str) -> str:
    normalized = base[:72]
    aliases = {profile.get("modelAlias") for profile in config.get("profiles", {}).values()}
    candidate = normalized
    number = 2
    while candidate in aliases:
        candidate = f"{normalized[:74]}-{number}"
        number += 1
    return candidate


def _status_markup(status: dict) -> str:
    state = status.get("state", "STOPPED")
    state_class = "status-ready" if state in {"READY", "BUSY"} else "status-loading" if state in {"STARTING", "LOADING", "VERIFYING", "RECOVERING"} else "status-error" if state == "ERROR" else "status-stopped"
    gpu = status.get("gpu") or {}
    metrics = status.get("metrics") or {}
    gpu_memory = "无法获取"
    if gpu.get("memoryTotalMiB") is not None:
        gpu_memory = f"{_number(gpu.get('memoryUsedMiB'))} / {_number(gpu.get('memoryTotalMiB'))} MiB"
    token_speed = "—" if metrics.get("predictedTokensPerSecond") is None else f"{float(metrics['predictedTokensPerSecond']):.1f} Token/s"
    return f"""
    <div class="status-card">
      <div class="status-heading">
        <div><div class="status-eyebrow">LLAMA.CPP RUNTIME</div><h2>本地翻译服务器</h2></div>
        <span class="status-pill {state_class}">{html.escape(status.get('stateLabel', state))}</span>
      </div>
      <p>{html.escape(status.get('message', ''))}</p>
      <div class="metric-grid">
        <div class="metric"><span>Profile</span><strong>{html.escape(status.get('profile', {}).get('displayName', '—'))}</strong></div>
        <div class="metric"><span>GPU / VRAM</span><strong>{html.escape(gpu_memory)}</strong></div>
        <div class="metric"><span>推理速度</span><strong>{html.escape(token_speed)}</strong></div>
        <div class="metric"><span>请求</span><strong>等待 {_number(metrics.get('requestsDeferred'))} · 处理中 {_number(metrics.get('requestsProcessing'))}</strong></div>
      </div>
    </div>
    """


def _profile_summary_markup(status: dict) -> str:
    profile = status.get("profile") or {}
    thinking = status.get("thinking") or {}
    compatibility = status.get("compatibility") or {}
    thinking_label = thinking.get("label") or ("将在启动时尝试关闭" if profile.get("disableThinking") else "跟随模型默认")
    thinking_method = f"　<span class='code-text'>{html.escape(thinking['method'])}</span>" if thinking.get("method") else ""
    compatibility_label = {
        "PASS": "已通过",
        "FALLBACK": "已通过（使用兼容回退）",
        "VERIFYING": "正在检查",
        "RECOVERING": "正在回退",
        "FAILED": "失败",
        "PENDING": "等待检查",
    }.get(str(compatibility.get("status", "PENDING")), "等待检查")
    compatibility_detail = str(compatibility.get("message") or "")
    compatibility_latency = compatibility.get("latencyMs")
    compatibility_suffix = f"　{int(compatibility_latency)} ms" if isinstance(compatibility_latency, (int, float)) else ""
    fallback_reason = str(thinking.get("reason") or "")
    fallback_markup = f"<p class='restart-warning'>{html.escape(fallback_reason)}</p>" if fallback_reason else ""
    restart = "<p class='restart-warning'>设置已更改，需要重新启动 llama-server。</p>" if status.get("restartRequired") else ""
    return f"""
    <div class="status-card">
      <div class="status-eyebrow">ACTIVE PROFILE</div>
      <h2>{html.escape(profile.get('displayName', '当前 Profile'))}</h2>
      <p><strong>GGUF：</strong><span class="code-text">{html.escape(profile.get('modelPath') or '尚未选择')}</span></p>
      <p><strong>Context：</strong>{_number(profile.get('contextSize'))}　<strong>Flash Attention：</strong>{html.escape(str(profile.get('flashAttention', 'auto')))}　<strong>Prompt Cache：</strong>{'开启' if profile.get('promptCache') else '关闭'}</p>
      <p><strong>模型思考：</strong>{html.escape(thinking_label)}{thinking_method}</p>
      <p><strong>Chat 兼容性：</strong>{html.escape(compatibility_label)}{html.escape(compatibility_suffix)}　{html.escape(compatibility_detail)}</p>
      {fallback_markup}
      {restart}
    </div>
    """


def _diagnostics_markdown(value: dict) -> str:
    gpu = value.get("gpu") or {}
    addresses = ", ".join(value.get("lanAddresses") or []) or "未检测到"
    port_status = "可用" if value.get("portAvailable") else "已被占用（服务器运行时属于正常现象）"
    return "\n".join([
        "### 诊断结果",
        f"- FgoGotranLocal：`{value['version']}`",
        f"- Python：`{value['pythonVersion']}`",
        f"- 数据目录：`{value['dataDirectory']}`",
        f"- llama-server：`{value['llamaServerPath']}`",
        f"- 模型目录：`{value['modelsDirectory']}`",
        f"- Runtime 验证：{value['runtimeValidation']}",
        f"- API Port `{value['inferencePort']}`：{port_status}",
        f"- 局域网地址：{addresses}",
        f"- GPU：{gpu.get('name', '无法获取')}，VRAM {_number(gpu.get('memoryUsedMiB'))}/{_number(gpu.get('memoryTotalMiB'))} MiB",
        f"- 系统内存：{_number(value['memory'].get('usedMiB'))}/{_number(value['memory'].get('totalMiB'))} MiB",
    ])


def _error_status_markup(error: Exception) -> str:
    return f"<div class='status-card'><div class='status-heading'><div><div class='status-eyebrow'>LLAMA.CPP RUNTIME</div><h2>本地翻译服务器</h2></div><span class='status-pill status-error'>控制台错误</span></div><p>{html.escape(str(error))}</p></div>"


def _error_message(error: Exception) -> str:
    message = str(error) if isinstance(error, StudioError) else f"操作失败：{error}"
    return f"<span class='result-error'>{html.escape(message)}</span>"


def _integer(value: Any) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


def _number(value: Any) -> str:
    if value is None:
        return "—"
    try:
        number = float(value)
        return f"{number:,.0f}" if number.is_integer() else f"{number:,.1f}"
    except (TypeError, ValueError):
        return html.escape(str(value))


def _action_label(name: str) -> str:
    return {"start": "启动", "stop": "停止", "restart": "重新启动"}.get(name, name)


def _icon_data_uri(icon_path: str) -> str:
    path = Path(icon_path)
    try:
        encoded = base64.b64encode(path.read_bytes()).decode("ascii")
    except OSError:
        return ""
    mime_type = mimetypes.guess_type(path.name)[0] or "image/png"
    return f"data:{mime_type};base64,{encoded}"
