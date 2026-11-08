import asyncio
import hashlib
from html.parser import HTMLParser
import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock

import httpx
import pytest
from fastapi.testclient import TestClient
from markdown_it import MarkdownIt

from fgogotran_local.application import create_application
from fgogotran_local.config_models import ProfileConfig
from fgogotran_local.config_store import ConfigStore
from fgogotran_local.connection_test import connection_example, language_warning_key
from fgogotran_local.errors import StudioError
from fgogotran_local.i18n import activate, deactivate
from fgogotran_local.llama_manager import LlamaManager
from fgogotran_local.log_store import LogStore
from fgogotran_local.service import LocalTranslationService
from fgogotran_local.ui import _connection_example_markup, build_ui


ROOT = Path(__file__).resolve().parents[2]
FIXTURE = Path(__file__).parents[1] / "src/fgogotran_local/connection_prompts.json"
ANSWERS = {"zh-Hans": "御主，我们回迦勒底吧。", "zh-Hant": "御主，我們回迦勒底吧。",
           "en": "Master, let's return to Chaldea."}


def test_export_is_current_and_all_targets_use_android_example():
    value = json.loads(FIXTURE.read_text(encoding="utf-8"))
    assert value["schemaVersion"] == 1 and set(value["requests"]) == set(ANSWERS)
    for relative, expected in value["sourceSha256"].items():
        actual = hashlib.sha256((ROOT / relative).read_text(encoding="utf-8").encode("utf-8")).hexdigest()
        assert actual == expected, f"Regenerate Local prompts after changing {relative} (see README)"
    for target in ANSWERS:
        case = connection_example(target)
        assert case["sourceText"] == "マスター、カルデアに戻りましょう。"
        assert [item["role"] for item in case["messages"]] == ["system", "user"]
        assert case["sourceText"] in case["messages"][1]["content"]
        assert ("カルデア->Chaldea" if target == "en" else "カルデア->迦勒底") in case["messages"][1]["content"]
        assert case["promptVersion"]
    case["messages"][0]["content"] = "modified"
    assert connection_example("en")["messages"][0]["content"] != "modified"


@pytest.mark.parametrize("locale,label", [("zh-Hans", "简体中文"), ("zh-Hant", "繁體中文"), ("en", "English")])
def test_example_card_shows_source_and_target(locale, label):
    token = activate(locale)
    try:
        markup = _connection_example_markup(locale)
    finally:
        deactivate(token)
    assert label in markup and connection_example(locale)["sourceText"] in markup


@pytest.mark.parametrize("locale", list(ANSWERS))
def test_result_renders_punctuation_and_html_as_literal_text(tmp_path, locale):
    response = "Master, let's return to Chaldea. A & B, `go`, <tag> and </code><script>test</script>"
    service = LocalTranslationService(tmp_path)
    service.test_compatibility = AsyncMock(return_value={"response": response, "latencyMs": 307, "warning": ""})
    studio = build_ui(service, str(Path(__file__).parents[1] / "public/gotran-icon.png"), locale)
    callback = next(block.fn for block in studio.fns.values() if block.fn and block.fn.__name__ == "run_test")
    markup = asyncio.run(callback())
    class CodeText(HTMLParser):
        def __init__(self):
            super().__init__(convert_charrefs=True)
            self.in_code = False
            self.text = ""
            self.tags = []
        def handle_starttag(self, tag, attrs):
            self.tags.append(tag)
            if tag == "code":
                self.in_code = True
        def handle_endtag(self, tag):
            if tag == "code":
                self.in_code = False
        def handle_data(self, data):
            if self.in_code:
                self.text += data
    parsed = CodeText()
    parsed.feed(MarkdownIt("commonmark", {"html": True}).render(markup))
    assert parsed.text == response
    assert "script" not in parsed.tags and "tag" not in parsed.tags
    assert "307" in markup and "`&#" not in markup
    service.test_compatibility.assert_awaited_once_with(locale)


@pytest.mark.parametrize("target,answer,warning", [
    *((target, answer, "") for target, answer in ANSWERS.items()),
    ("en", "御主，回迦勒底吧。", "ui.test.warningLanguage"),
    ("zh-Hant", "Master, let's return to Chaldea.", "ui.test.warningLanguage"),
    ("en", "マスター、カルデアに戻りましょう。", "ui.test.warningJapanese"),
    ("en", "!?", "ui.test.warningLanguage"),
    # This valid wording has no simplified/traditional distinction: no exact-answer rule.
    ("zh-Hant", "御主，回迦勒底吧。", ""),
])
def test_language_checks_are_advisory_not_exact_answer_matching(target, answer, warning):
    assert language_warning_key(answer, target) == warning


def manager_for_test(tmp_path, provider):
    manager = LlamaManager(ConfigStore(tmp_path / "config.json"), tmp_path / "state",
                           LogStore(tmp_path / "state", lambda: "test-only-key"), provider)
    manager.running_profile = ProfileConfig(modelAlias="test-model", metrics=False, slots=False).model_dump(by_alias=True)
    manager.running_api_key = "test-only-key"
    manager.process = SimpleNamespace(returncode=None, pid=12345)
    manager.state = "READY"
    return manager


@pytest.mark.parametrize("locale", list(ANSWERS))
def test_manual_and_startup_send_one_production_request_in_selected_language(tmp_path, locale):
    async def run():
        selected = [locale]
        manager = manager_for_test(tmp_path, lambda: selected[0])
        await manager._client.aclose()
        calls = []
        def respond(request):
            if request.url.path == "/health":
                return httpx.Response(200, json={"ok": True})
            calls.append(json.loads(request.content))
            return httpx.Response(200, json={"choices": [{"message": {"content": ANSWERS[locale]}}]})
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            manager._client = client
            # Startup poll reads the saved preference even without a page/request locale context.
            await manager.poll()
            assert manager.state == "READY" and manager._compatibility["status"] == "PASS"
            await manager.poll()
            assert len(calls) == 1  # No extra inference on subsequent health polls.
            selected[0] = "en" if locale != "en" else "zh-Hans"
            result = await manager.test_compatibility(locale)
        assert len(calls) == 2
        for body in calls:
            assert body["messages"] == connection_example(locale)["messages"]
            assert body["model"] == "test-model" and body["max_tokens"] == 128
        assert result["targetLanguage"] == locale and result["sourceText"] == connection_example(locale)["sourceText"]
        assert result["response"] == ANSWERS[locale] and result["warning"] == ""
    asyncio.run(run())


def test_wrong_language_warns_without_retry_restart_or_changing_ready_state(tmp_path):
    async def run():
        selected = ["en"]
        manager = manager_for_test(tmp_path, lambda: selected[0])
        await manager._client.aclose()
        calls = []
        def respond(request):
            calls.append(json.loads(request.content))
            selected[0] = "zh-Hant"  # A switch while the request is in flight.
            return httpx.Response(200, json={"choices": [{"message": {"content": ANSWERS["zh-Hans"]}}]})
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            manager._client = client
            result = await manager.test_compatibility()
        assert result["ok"] and result["targetLanguage"] == "en" and result["warning"]
        assert manager.state == "READY" and len(calls) == 1
        assert calls[0]["messages"] == connection_example("en")["messages"]
    asyncio.run(run())


@pytest.mark.parametrize("kind", ["http", "empty", "thinking", "timeout"])
def test_protocol_failures_still_fail_without_extra_requests(tmp_path, kind):
    async def run():
        manager = manager_for_test(tmp_path, lambda: "en")
        await manager._client.aclose()
        calls = []
        def respond(request):
            calls.append(request)
            if kind == "timeout":
                raise httpx.ReadTimeout("mock timeout", request=request)
            message = {"content": "", "reasoning_content": "Thinking" if kind == "thinking" else ""}
            return httpx.Response(401 if kind == "http" else 200, json={"choices": [{"message": message}]})
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            manager._client = client
            with pytest.raises(StudioError):
                await manager.test_compatibility()
        assert len(calls) == 1 and manager.state == "READY"
    asyncio.run(run())


def test_control_api_uses_accept_language_or_saved_preference(tmp_path):
    service = LocalTranslationService(tmp_path)
    asyncio.run(service.ui_preferences.set_language("zh-Hant"))
    calls = []
    async def test(locale):
        calls.append(locale)
        return {"ok": True, "targetLanguage": locale}
    service.test_compatibility = test
    with TestClient(create_application(service, mount_ui=False, allow_test_host=True)) as client:
        for header, expected in [(None, "zh-Hant"), ("en-US", "en"), ("zh-CN", "zh-Hans"), ("zh-TW", "zh-Hant")]:
            headers = {"X-FGO-Control": "1"}
            if header:
                headers["Accept-Language"] = header
            response = client.post("/api/actions/test", json={}, headers=headers)
            assert response.status_code == 200 and response.json()["result"]["targetLanguage"] == expected
    assert calls == ["zh-Hant", "en", "zh-Hans", "zh-Hant"]
