from __future__ import annotations

import base64
import copy
import functools
import html
import inspect
import mimetypes
from pathlib import Path
from typing import Any

import gradio as gr

from .errors import StudioError
from .i18n import DEFAULT_LOCALE, activate, deactivate, t
from .privacy import redact_sensitive_text
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
  --studio-font-hant: "Microsoft JhengHei UI", "Microsoft JhengHei", "PingFang TC", "Noto Sans TC", Inter, "Segoe UI Variable Text", "Segoe UI", sans-serif;
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
html[lang="zh-Hant"] .gradio-container {
  --font: var(--studio-font-hant);
  font-family: var(--studio-font-hant) !important;
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
.studio-header-actions {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 10px;
}
.studio-lang {
  display: flex;
  align-items: center;
  gap: 3px;
  padding: 3px;
  border: 1px solid var(--fgo-line);
  border-radius: 999px;
  background: rgba(255, 255, 255, .74);
}
.studio-lang .lang-link {
  display: inline-block;
  padding: 4px 11px;
  border-radius: 999px;
  color: var(--fgo-muted);
  font-size: .8125rem;
  font-weight: 680;
  text-decoration: none;
  white-space: nowrap;
}
.studio-lang .lang-link:hover { color: var(--fgo-blue); background: #eef6fb; }
.studio-lang .lang-link.is-active {
  color: #fff;
  background: linear-gradient(145deg, var(--fgo-blue), var(--fgo-cyan));
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
.metric-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; margin-top: 12px; }
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
  .studio-header-actions { gap: 8px; }
  .studio-lang .lang-link { padding: 4px 9px; }
  .privacy-pill { display: none; }
}
@media (max-width: 520px) {
  .metric-grid { grid-template-columns: 1fr; }
  .studio-brand img { width: 42px; height: 42px; }
}
"""


UI_TAB_KEYS = (
    "ui.tab.overview",
    "ui.tab.model",
    "ui.tab.test",
    "ui.tab.system",
)

# Default-locale labels, kept as a module constant for compatibility; build_ui
# resolves them per locale.
UI_TAB_LABELS = tuple(t(key) for key in UI_TAB_KEYS)

# Locale switch targets shown in the header; labels stay script-native.
LOCALE_LINKS: tuple[tuple[str, str, str], ...] = (
    ("zh-Hans", "/", "简体"),
    ("zh-Hant", "/zh-Hant/", "繁體"),
    ("en", "/en/", "English"),
)


def _language_links_markup(active: str) -> str:
    links = []
    for locale, href, label in LOCALE_LINKS:
        css = "lang-link is-active" if locale == active else "lang-link"
        links.append(f'<a class="{css}" href="{href}">{html.escape(label)}</a>')
    return f'<nav class="studio-lang" aria-label="{html.escape(t("ui.header.language"))}">' + "".join(links) + "</nav>"


def _clipboard_copy_js(label: str) -> str:
    safe_label = label.replace("\\", "\\\\").replace('"', '\\"')
    empty_message = t("ui.result.copyNothing")
    failed_message = t("ui.result.copyFailed", label=safe_label)
    return f"""
    async (value, serverMessage) => {{
      if (!value) {{
        return [serverMessage || "<span class='result-error'>{empty_message}</span>", ""];
      }}
      try {{
        try {{
          await navigator.clipboard.writeText(value);
        }} catch (clipboardError) {{
          const temporary = document.createElement("textarea");
          temporary.value = value;
          temporary.setAttribute("readonly", "");
          temporary.style.position = "fixed";
          temporary.style.opacity = "0";
          document.body.appendChild(temporary);
          let copied = false;
          try {{
            temporary.focus();
            temporary.select();
            copied = document.execCommand("copy");
          }} finally {{
            temporary.remove();
          }}
          if (!copied) throw clipboardError;
        }}
        return ["", ""];
      }} catch (error) {{
        return ["<span class='result-error'>{failed_message}</span>", ""];
      }}
    }}
    """


def build_ui(
    service: LocalTranslationService,
    icon_path: str,
    locale: str = DEFAULT_LOCALE,
) -> gr.Blocks:
    """Build the control UI for one locale.

    The locale is active while the component tree is created (labels and
    helper markup) and inside every event handler, so runtime, validation and
    log messages follow the page the user is viewing.
    """
    token = activate(locale)

    def localized_handler(fn):
        """Bind an event handler to this UI's locale."""
        if inspect.iscoroutinefunction(fn):
            @functools.wraps(fn)
            async def async_handler(*args, **kwargs):
                handler_token = activate(locale)
                try:
                    return await fn(*args, **kwargs)
                finally:
                    deactivate(handler_token)

            return async_handler

        @functools.wraps(fn)
        def sync_handler(*args, **kwargs):
            handler_token = activate(locale)
            try:
                return fn(*args, **kwargs)
            finally:
                deactivate(handler_token)

        return sync_handler

    try:
        tab_labels = tuple(t(key) for key in UI_TAB_KEYS)
        with gr.Blocks(title="FgoGotran Local", fill_width=True) as studio:
            config_state = gr.State({})
            editing_profile_state = gr.State("")
            endpoint_revealed_state = gr.State(False)
            key_revealed_state = gr.State(False)
            endpoint_copy_buffer = gr.Textbox(value="", visible=False, show_label=False)
            key_copy_buffer = gr.Textbox(value="", visible=False, show_label=False)

            gr.HTML(
                f"""
                <div class="studio-header">
                  <div class="studio-brand">
                    <img src="{_icon_data_uri(icon_path)}" alt="" />
                    <div><h1>FgoGotran Local</h1><p>{html.escape(t("ui.header.subtitle"))}</p></div>
                  </div>
                  <div class="studio-header-actions">
                    {_language_links_markup(locale)}
                    <span class="privacy-pill">{html.escape(t("ui.header.privacyPill"))}</span>
                  </div>
                </div>
                """
            )

            with gr.Tabs(elem_classes=["studio-tabs"]):
                with gr.Tab(tab_labels[0]):
                    gr.HTML(_page_intro_markup(
                        "LOCAL RUNTIME",
                        t("ui.overview.title"),
                        t("ui.overview.description"),
                    ))
                    status_html = gr.HTML()
                    gr.HTML(_workflow_markup())
                    with gr.Row(equal_height=True):
                        with gr.Column(scale=2):
                            with gr.Group(elem_classes=["panel-card"]):
                                gr.HTML(_panel_title_markup(t("ui.overview.controlTitle"), t("ui.overview.controlHint")))
                                with gr.Row():
                                    start_button = gr.Button(t("ui.overview.start"), variant="primary")
                                    restart_button = gr.Button(t("ui.overview.restart"))
                                    stop_button = gr.Button(t("ui.overview.stop"), variant="stop")
                                action_result = gr.Markdown(elem_classes=["inline-result"])
                        with gr.Column(scale=3):
                            with gr.Group(elem_classes=["panel-card"]):
                                gr.HTML(_panel_title_markup(t("ui.overview.connectionTitle"), t("ui.overview.connectionHint")))
                                endpoint_box = gr.Textbox(label="Endpoint", interactive=False, buttons=["copy"], elem_classes=["code-field"])
                                model_id_box = gr.Textbox(label="Model ID", interactive=False, buttons=["copy"], elem_classes=["code-field"])
                                api_key_box = gr.Textbox(label="API Key", interactive=False, buttons=["copy"], elem_classes=["code-field"])
                                with gr.Row():
                                    reveal_endpoint_button = gr.Button(t("ui.overview.revealEndpoint"), size="sm")
                                    reveal_key_button = gr.Button(t("ui.overview.revealApiKey"), size="sm")
                                    rotate_key_button = gr.Button(t("ui.overview.rotateApiKey"), size="sm")
                                rotate_confirmation = gr.Checkbox(label=t("ui.overview.rotateConfirm"), value=False)
                                key_result = gr.Markdown(elem_classes=["inline-result"])
                    profile_summary = gr.HTML()
                    overview_timer = gr.Timer(value=1.5, active=True)

                with gr.Tab(tab_labels[1]):
                    gr.HTML(_page_intro_markup(
                        "MODEL SETUP",
                        t("ui.model.title"),
                        t("ui.model.description"),
                    ))
                    with gr.Group(elem_classes=["panel-card"]):
                        with gr.Row():
                            profile_selector = gr.Dropdown(label=t("ui.model.currentProfile"), choices=[], interactive=True, scale=4)
                            add_profile_button = gr.Button(t("ui.model.newProfile"), size="sm", scale=1)
                            duplicate_profile_button = gr.Button(t("ui.model.duplicateProfile"), size="sm", scale=1)
                        with gr.Accordion(t("ui.model.deleteAccordion"), open=False):
                            with gr.Row():
                                delete_confirmation = gr.Checkbox(label=t("ui.model.deleteConfirm"), value=False, scale=3)
                                delete_profile_button = gr.Button(t("ui.model.deleteButton"), size="sm", variant="stop", scale=1)
                        profile_message = gr.Markdown(t("ui.model.dirtyHint"))

                    with gr.Row(equal_height=True):
                        with gr.Column(scale=1):
                            with gr.Group(elem_classes=["panel-card"]):
                                gr.HTML(_panel_title_markup(t("ui.model.runtimeTitle"), t("ui.model.runtimeHint")))
                                llama_path = gr.Textbox(
                                    label="llama-server.exe",
                                    placeholder=r"C:\path\to\llama-server.exe",
                                    elem_classes=["code-field"],
                                )
                                models_directory = gr.Textbox(
                                    label=t("ui.model.modelsDirectoryLabel"),
                                    placeholder=r"C:\models",
                                    elem_classes=["code-field"],
                                )
                                with gr.Row():
                                    model_path = gr.Dropdown(
                                        label=t("ui.model.modelLabel"),
                                        choices=[],
                                        allow_custom_value=True,
                                        interactive=True,
                                        elem_classes=["code-field"],
                                        scale=4,
                                    )
                                    refresh_models_button = gr.Button(t("ui.model.scanButton"), size="sm", scale=1)
                        with gr.Column(scale=1):
                            with gr.Group(elem_classes=["panel-card"]):
                                gr.HTML(_panel_title_markup(t("ui.model.identityTitle"), t("ui.model.identityHint")))
                                profile_display_name = gr.Textbox(label=t("ui.model.displayNameLabel"), max_lines=1)
                                profile_model_alias = gr.Textbox(label="Model ID", max_lines=1, elem_classes=["code-field"])
                                with gr.Row():
                                    inference_port = gr.Number(label="API Port", value=18080, precision=0)
                                    inference_host = gr.Dropdown(
                                        label=t("ui.model.hostLabel"),
                                        choices=[
                                            (t("ui.model.hostLan"), "0.0.0.0"),
                                            (t("ui.model.hostLocal"), "127.0.0.1"),
                                        ],
                                        value="0.0.0.0",
                                    )

                    with gr.Accordion(t("ui.model.advancedAccordion"), open=False):
                        gr.Markdown(t("ui.model.advancedHint"))
                        with gr.Row():
                            context_size = gr.Number(label="Context Size", value=8192, precision=0)
                            gpu_layers = gr.Number(label="GPU Layers", value=999, precision=0)
                        with gr.Row():
                            batch_size = gr.Number(label="Batch Size", value=512, precision=0)
                            ubatch_size = gr.Number(label="UBatch Size", value=256, precision=0)
                            cpu_threads = gr.Number(label=t("ui.model.cpuThreads"), value=0, precision=0)
                        flash_attention = gr.Dropdown(
                            label="Flash Attention",
                            choices=[(t("ui.model.flashOn"), "on"), ("Auto", "auto"), (t("ui.model.flashOff"), "off")],
                            value="on",
                        )
                        with gr.Row():
                            disable_thinking = gr.Checkbox(
                                label=t("ui.model.disableThinkingLabel"),
                                info=t("ui.model.disableThinkingInfo"),
                                value=False,
                            )
                            prompt_cache = gr.Checkbox(label="Prompt Cache", value=True)
                            metrics_enabled = gr.Checkbox(label="Metrics", value=True)
                            slots_enabled = gr.Checkbox(label="Slots", value=True)
                    with gr.Row(elem_classes=["save-bar"]):
                        save_profile_button = gr.Button(t("ui.model.saveButton"), variant="primary")

                with gr.Tab(tab_labels[2]):
                    gr.HTML(_page_intro_markup(
                        "CONNECTION TEST",
                        t("ui.test.title"),
                        t("ui.test.description"),
                    ))
                    with gr.Row(equal_height=True):
                        with gr.Column(scale=3):
                            with gr.Group(elem_classes=["panel-card"]):
                                gr.HTML(_panel_title_markup(t("ui.test.compatTitle"), t("ui.test.compatHint")))
                                test_button = gr.Button(t("ui.test.start"), variant="primary")
                                test_result = gr.Markdown(elem_classes=["inline-result"])
                        with gr.Column(scale=2):
                            with gr.Group(elem_classes=["panel-card"]):
                                gr.HTML(_panel_title_markup(t("ui.test.criteriaTitle"), t("ui.test.criteriaHint")))
                                gr.HTML(
                                    f"""
                                    <ul class="checklist">
                                      <li>{html.escape(t("ui.test.checkReady"))}</li>
                                      <li>{html.escape(t("ui.test.checkCredentials"))}</li>
                                      <li>{html.escape(t("ui.test.checkResponse"))}</li>
                                    </ul>
                                    """
                                )
                    gr.Markdown(
                        t("ui.test.firewallNote"),
                        elem_classes=["security-note"],
                    )

                with gr.Tab(tab_labels[3]):
                    gr.HTML(_page_intro_markup(
                        "SYSTEM",
                        t("ui.system.title"),
                        t("ui.system.description"),
                    ))
                    with gr.Tabs():
                        with gr.Tab(t("ui.system.diagnostics")):
                            with gr.Group(elem_classes=["panel-card"]):
                                diagnostics_button = gr.Button(t("ui.system.runDiagnostics"), variant="primary")
                                gr.Markdown(t("ui.system.diagnosticsNote"), elem_classes=["security-note"])
                                diagnostics_output = gr.Markdown()
                        with gr.Tab(t("ui.system.logs")):
                            with gr.Group(elem_classes=["panel-card"]):
                                with gr.Row():
                                    log_levels = gr.CheckboxGroup(
                                        label=t("ui.system.logLevels"),
                                        choices=["INFO", "WARN", "ERROR"],
                                        value=["INFO", "WARN", "ERROR"],
                                        scale=4,
                                    )
                                    refresh_logs_button = gr.Button(t("ui.system.refresh"), size="sm", scale=1)
                                    clear_logs_button = gr.Button(t("ui.system.clearDisplay"), size="sm", scale=1)
                                gr.Markdown(t("ui.system.logNote"), elem_classes=["security-note"])
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

            @localized_handler
            async def load_initial():
                config = service.public_config(include_sensitive=True)
                profile_id = config["activeProfile"]
                return _profile_state_result(config, profile_id, t("ui.model.loaded"))

            @localized_handler
            async def refresh_overview(endpoint_revealed: bool = False, key_revealed: bool = False):
                try:
                    status = await service.status()
                    config = service.public_config()
                    running = bool(status.get("pid"))
                    ready = status.get("state") in {"READY", "BUSY"}
                    if endpoint_revealed:
                        endpoint_value = (await service.connection_details(reveal=True))["endpoint"]
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
                    message_key = {
                        "start": "ui.overview.started",
                        "stop": "ui.overview.stopped",
                        "restart": "ui.overview.restarted",
                    }[name]
                    return _result(t(message_key), ok=True)
                except Exception as error:
                    return _error_message(error)

            @localized_handler
            async def start_action() -> str:
                return await run_action("start")

            @localized_handler
            async def stop_action() -> str:
                return await run_action("stop")

            @localized_handler
            async def restart_action() -> str:
                return await run_action("restart")

            @localized_handler
            async def run_test() -> str:
                try:
                    result = await service.test_compatibility()
                    response = html.escape(result["response"])
                    return f"{_result(t('ui.test.passed', ms=result['latencyMs']), ok=True)}\n\n" + t(
                        "ui.test.response", response=response
                    )
                except Exception as error:
                    return _error_message(error)

            @localized_handler
            async def toggle_endpoint_reveal(is_visible: bool):
                reveal = not bool(is_visible)
                if reveal:
                    connection = await service.connection_details(reveal=True)
                    return (
                        True,
                        connection["endpoint"],
                        gr.update(value=t("ui.overview.hideEndpoint")),
                    )
                status = await service.status()
                return False, status["connection"]["endpoint"], gr.update(value=t("ui.overview.revealEndpoint"))

            @localized_handler
            def toggle_key_reveal(is_visible: bool):
                reveal = not bool(is_visible)
                if reveal:
                    return True, service.api_key(), gr.update(value=t("ui.overview.hideApiKey"))
                return False, service.public_config()["apiKeyMasked"], gr.update(value=t("ui.overview.revealApiKey"))

            @localized_handler
            async def prepare_endpoint_copy():
                try:
                    connection = await service.connection_details(reveal=True)
                    endpoint = connection["endpoint"]
                    if "192.168.x.x" in endpoint:
                        return "", _result(t("ui.overview.copyNoLan"), ok=False)
                    return endpoint, ""
                except Exception as error:
                    return "", _error_message(error)

            @localized_handler
            def prepare_key_copy():
                try:
                    return service.api_key(), ""
                except Exception as error:
                    return "", _error_message(error)

            @localized_handler
            async def rotate_key(confirmed: bool):
                if not confirmed:
                    return (
                        gr.update(),
                        gr.update(),
                        gr.update(),
                        gr.update(),
                        _result(t("ui.overview.rotateNeedConfirm"), ok=False),
                        False,
                    )
                try:
                    result = await service.rotate_api_key()
                    return (
                        result["config"],
                        result["config"]["apiKeyMasked"],
                        False,
                        gr.update(value=t("ui.overview.revealApiKey")),
                        _result(t("ui.overview.rotateDone"), ok=True),
                        False,
                    )
                except Exception as error:
                    return gr.update(), gr.update(), gr.update(), gr.update(), _error_message(error), False

            @localized_handler
            async def change_profile(new_profile_id: str, config: dict, editing_id: str, *values):
                draft = _apply_profile_form(config, editing_id, values)
                if new_profile_id not in draft.get("profiles", {}):
                    new_profile_id = draft.get("activeProfile", "")
                return _profile_state_result(draft, new_profile_id, t("ui.model.editing"))

            @localized_handler
            async def add_profile(config: dict, editing_id: str, *values):
                draft = _apply_profile_form(config, editing_id, values)
                if len(draft.get("profiles", {})) >= 12:
                    return _profile_state_result(draft, editing_id, _result(t("config.error.profileLimit"), ok=False))
                number = 1
                while f"fgo-profile-{number}" in draft["profiles"]:
                    number += 1
                profile_id = f"fgo-profile-{number}"
                template = copy.deepcopy(draft["profiles"].get(editing_id) or next(iter(draft["profiles"].values())))
                template.update({
                    "displayName": t("ui.model.newProfileName", number=number),
                    "modelAlias": _unique_alias(draft, f"fgo-local-profile-{number}"),
                    "modelPath": "",
                })
                draft["profiles"][profile_id] = template
                return _profile_state_result(draft, profile_id, t("ui.model.added"))

            @localized_handler
            async def duplicate_profile(config: dict, editing_id: str, *values):
                draft = _apply_profile_form(config, editing_id, values)
                if len(draft.get("profiles", {})) >= 12:
                    return _profile_state_result(draft, editing_id, _result(t("config.error.profileLimit"), ok=False))
                number = 1
                while f"fgo-profile-{number}" in draft["profiles"]:
                    number += 1
                profile_id = f"fgo-profile-{number}"
                template = copy.deepcopy(draft["profiles"].get(editing_id) or next(iter(draft["profiles"].values())))
                template["displayName"] = t("ui.model.copyName", name=template.get("displayName", "Profile"))
                template["modelAlias"] = _unique_alias(draft, f"{template.get('modelAlias', 'fgo-local')}-copy")
                draft["profiles"][profile_id] = template
                return _profile_state_result(draft, profile_id, t("ui.model.duplicated"))

            @localized_handler
            async def delete_profile(confirmed: bool, config: dict, editing_id: str, *values):
                draft = _apply_profile_form(config, editing_id, values)
                if not confirmed:
                    return (*_profile_state_result(draft, editing_id, _result(t("ui.model.deleteNeedConfirm"), ok=False)), False)
                if len(draft.get("profiles", {})) <= 1:
                    return (*_profile_state_result(draft, editing_id, _result(t("config.error.profileRequired"), ok=False)), False)
                draft["profiles"].pop(editing_id, None)
                next_id = draft.get("activeProfile")
                if next_id not in draft["profiles"]:
                    next_id = next(iter(draft["profiles"]))
                draft["activeProfile"] = next_id
                return (*_profile_state_result(draft, next_id, t("ui.model.deleted")), False)

            @localized_handler
            async def save_profile(config: dict, editing_id: str, *values):
                draft = _apply_profile_form(config, editing_id, values)
                draft["activeProfile"] = editing_id
                try:
                    saved = await service.update_config(draft, draft.get("revision"))
                    return _profile_state_result(saved, saved["activeProfile"], _result(t("ui.model.saved"), ok=True))
                except Exception as error:
                    return _profile_state_result(draft, editing_id, _error_message(error))

            @localized_handler
            async def scan_models(directory: str, current_model: str):
                models = await service.list_models(directory)
                choices = [(item["relativePath"], item["path"]) for item in models]
                return gr.update(choices=choices, value=current_model or None)

            @localized_handler
            def render_logs(levels: list[str] | None) -> str:
                return service.formatted_logs(set(levels or []))

            @localized_handler
            def clear_log_display(levels: list[str] | None) -> str:
                service.clear_logs()
                return service.formatted_logs(set(levels or []))

            @localized_handler
            async def run_diagnostics() -> str:
                try:
                    return _diagnostics_markdown(await service.diagnostics())
                except Exception as error:
                    return _error_message(error)

            studio.load(load_initial, outputs=profile_state_outputs, api_visibility="private")
            studio.load(
                refresh_overview,
                inputs=[endpoint_revealed_state, key_revealed_state],
                outputs=overview_outputs,
                api_visibility="private",
            )
            studio.load(render_logs, inputs=[log_levels], outputs=[logs_box], api_visibility="private")
            overview_timer.tick(
                refresh_overview,
                inputs=[endpoint_revealed_state, key_revealed_state],
                outputs=overview_outputs,
                api_visibility="private",
            )
            logs_timer.tick(render_logs, inputs=[log_levels], outputs=[logs_box], api_visibility="private")

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
            copy_endpoint_event = endpoint_box.copy(
                prepare_endpoint_copy,
                outputs=[endpoint_copy_buffer, key_result],
                show_progress="hidden",
                queue=False,
                api_visibility="private",
            )
            copy_endpoint_event.then(
                fn=None,
                inputs=[endpoint_copy_buffer, key_result],
                outputs=[key_result, endpoint_copy_buffer],
                js=_clipboard_copy_js("Endpoint"),
                show_progress="hidden",
                queue=False,
                api_visibility="private",
            )
            copy_key_event = api_key_box.copy(
                prepare_key_copy,
                outputs=[key_copy_buffer, key_result],
                show_progress="hidden",
                queue=False,
                api_visibility="private",
            )
            copy_key_event.then(
                fn=None,
                inputs=[key_copy_buffer, key_result],
                outputs=[key_result, key_copy_buffer],
                js=_clipboard_copy_js("API Key"),
                show_progress="hidden",
                queue=False,
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
                outputs=[diagnostics_output],
                api_visibility="private",
            )
            refresh_logs_button.click(render_logs, inputs=[log_levels], outputs=[logs_box], api_visibility="private")
            log_levels.change(render_logs, inputs=[log_levels], outputs=[logs_box], api_visibility="private")
            clear_logs_button.click(clear_log_display, inputs=[log_levels], outputs=[logs_box], api_visibility="private")
    finally:
        deactivate(token)

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
    steps = (
        ("1", t("ui.workflow.step1Title"), t("ui.workflow.step1Hint")),
        ("2", t("ui.workflow.step2Title"), t("ui.workflow.step2Hint")),
        ("3", t("ui.workflow.step3Title"), t("ui.workflow.step3Hint")),
    )
    items = "".join(
        '<div class="workflow-step"><span>'
        + number
        + "</span><div><strong>"
        + html.escape(title)
        + "</strong><small>"
        + html.escape(hint)
        + "</small></div></div>"
        for number, title, hint in steps
    )
    return (
        '<div class="workflow-strip" aria-label="'
        + html.escape(t("ui.workflow.aria"))
        + '">'
        + items
        + "</div>"
    )


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
    metrics = status.get("metrics") or {}
    token_speed = "—" if metrics.get("predictedTokensPerSecond") is None else f"{float(metrics['predictedTokensPerSecond']):.1f} Token/s"
    requests_detail = t(
        "ui.status.requestsDetail",
        deferred=_number(metrics.get("requestsDeferred")),
        processing=_number(metrics.get("requestsProcessing")),
    )
    return f"""
    <div class="status-card">
      <div class="status-heading">
        <div><div class="status-eyebrow">LLAMA.CPP RUNTIME</div><h2>{html.escape(t("ui.status.title"))}</h2></div>
        <span class="status-pill {state_class}">{html.escape(status.get('stateLabel', state))}</span>
      </div>
      <p>{html.escape(status.get('message', ''))}</p>
      <div class="metric-grid">
        <div class="metric"><span>Profile</span><strong>{html.escape(status.get('profile', {}).get('displayName', '—'))}</strong></div>
        <div class="metric"><span>{html.escape(t("ui.status.tokenSpeed"))}</span><strong>{html.escape(token_speed)}</strong></div>
        <div class="metric"><span>{html.escape(t("ui.status.requests"))}</span><strong>{html.escape(requests_detail)}</strong></div>
      </div>
    </div>
    """


def _profile_summary_markup(status: dict) -> str:
    profile = status.get("profile") or {}
    thinking = status.get("thinking") or {}
    compatibility = status.get("compatibility") or {}
    thinking_label = thinking.get("label") or t(
        "llama.thinking.willDisable" if profile.get("disableThinking") else "llama.thinking.followModel"
    )
    thinking_method = f"　<span class='code-text'>{html.escape(thinking['method'])}</span>" if thinking.get("method") else ""
    compatibility_label = t(
        {
            "PASS": "ui.status.compat.pass",
            "FALLBACK": "ui.status.compat.fallback",
            "VERIFYING": "ui.status.compat.verifying",
            "RECOVERING": "ui.status.compat.recovering",
            "FAILED": "ui.status.compat.failed",
        }.get(str(compatibility.get("status", "PENDING")), "ui.status.compat.pending")
    )
    compatibility_detail = str(compatibility.get("message") or "")
    compatibility_latency = compatibility.get("latencyMs")
    compatibility_suffix = f"　{int(compatibility_latency)} ms" if isinstance(compatibility_latency, (int, float)) else ""
    fallback_reason = str(thinking.get("reason") or "")
    model_status = t("ui.status.modelSelected") if profile.get("modelPath") else t("ui.status.modelMissing")
    fallback_markup = f"<p class='restart-warning'>{html.escape(fallback_reason)}</p>" if fallback_reason else ""
    restart = (
        f"<p class='restart-warning'>{html.escape(t('ui.status.restartRequired'))}</p>"
        if status.get("restartRequired")
        else ""
    )
    return f"""
    <div class="status-card">
      <div class="status-eyebrow">ACTIVE PROFILE</div>
      <h2>{html.escape(profile.get('displayName', t('ui.status.currentProfile')))}</h2>
      <p><strong>{html.escape(t("ui.status.ggufLabel"))}</strong>{model_status}</p>
      <p><strong>{html.escape(t("ui.status.contextLabel"))}</strong>{_number(profile.get('contextSize'))}　<strong>{html.escape(t("ui.status.flashLabel"))}</strong>{html.escape(str(profile.get('flashAttention', 'auto')))}　<strong>{html.escape(t("ui.status.promptCacheLabel"))}</strong>{t("ui.status.on") if profile.get('promptCache') else t("ui.status.off")}</p>
      <p><strong>{html.escape(t("ui.status.thinkingLabel"))}</strong>{html.escape(thinking_label)}{thinking_method}</p>
      <p><strong>{html.escape(t("ui.status.compatibilityLabel"))}</strong>{html.escape(compatibility_label)}{html.escape(compatibility_suffix)}　{html.escape(compatibility_detail)}</p>
      {fallback_markup}
      {restart}
    </div>
    """


def _diagnostics_markdown(value: dict) -> str:
    def state(key: str) -> str:
        return t("ui.diagnostics.ok") if value.get(key) else t("ui.diagnostics.attention")

    port_status = t("ui.diagnostics.portRunning") if value.get("serverRunning") else state("portAvailable")
    return "\n".join([
        t("ui.diagnostics.title"),
        t("ui.diagnostics.pythonEnvironment", state=state("pythonEnvironmentReady")),
        t("ui.diagnostics.pythonDependencies", state=state("pythonDependenciesReady")),
        t("ui.diagnostics.configuration", state=state("configurationReady")),
        t("ui.diagnostics.llamaCpp", state=state("llamaConfigured")),
        t("ui.diagnostics.model", state=state("modelConfigured")),
        t("ui.diagnostics.runtime", state=state("runtimeReady")),
        t("ui.diagnostics.apiPort", state=port_status),
    ])


def _error_status_markup(error: Exception) -> str:
    message = redact_sensitive_text(str(error))
    return (
        "<div class='status-card'><div class='status-heading'><div><div class='status-eyebrow'>LLAMA.CPP RUNTIME</div><h2>"
        + html.escape(t("ui.status.title"))
        + "</h2></div><span class='status-pill status-error'>"
        + html.escape(t("ui.error.consoleError"))
        + "</span></div><p>"
        + html.escape(message)
        + "</p></div>"
    )


def _error_message(error: Exception) -> str:
    raw_message = str(error) if isinstance(error, StudioError) else t("ui.error.operationFailed", error=error)
    return _result(redact_sensitive_text(raw_message), ok=False)


def _result(message: str, *, ok: bool) -> str:
    """Wrap a plain message in the inline result markup used by handlers."""
    css = "result-ok" if ok else "result-error"
    return f"<span class='{css}'>{html.escape(message)}</span>"


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


def _icon_data_uri(icon_path: str) -> str:
    path = Path(icon_path)
    try:
        encoded = base64.b64encode(path.read_bytes()).decode("ascii")
    except OSError:
        return ""
    mime_type = mimetypes.guess_type(path.name)[0] or "image/png"
    return f"data:{mime_type};base64,{encoded}"
