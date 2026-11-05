import asyncio
import json
import os
import shutil
import subprocess
from pathlib import Path
from unittest.mock import patch

import httpx
import pytest
from fastapi.testclient import TestClient

from fgogotran_local.application import create_application
from fgogotran_local.errors import StudioError
from fgogotran_local.i18n import CATALOGS, activate, deactivate
from fgogotran_local.service import LocalTranslationService
from fgogotran_local.ui import _update_markup, build_ui
from fgogotran_local.update_ui import dialog_value, DIALOG_JS
from fgogotran_local.updates import (
    MANIFEST_URL, MAX_MANIFEST_BYTES, SUCCESS_COOLDOWN, FAILURE_COOLDOWN,
    UpdateManager, parse_release, download_page_url,
)
from fgogotran_local.version import version_info


def release(**changes):
    return {"versionCode": 2, "versionName": "v1.0.1", "changelog": ["Updated"],
            "changelogI18n": {"zh-Hant": ["更新"]}, **changes}


def document(value=None):
    result = {"manifestVersion": 1, "versionCode": 9999, "versionName": "Android version"}
    if value is not None:
        result["fgogotranLocal"] = value
    return result


def make_manager(tmp_path, value=None, now=None):
    calls = []
    clock = now if now is not None else [1000.0]
    def respond(request):
        calls.append(request)
        return httpx.Response(200, json=document(value))
    return UpdateManager(tmp_path, clock=lambda: clock[0], transport=httpx.MockTransport(respond)), calls, clock


def test_version_and_no_network_until_a_check(tmp_path):
    manager, calls, _ = make_manager(tmp_path)
    assert calls == [] and not manager.snapshot()["checked"]
    assert manager.snapshot()["current"] == {"versionCode": 1, "versionName": "v1.0.0"}
    result = asyncio.run(manager.check())
    assert len(calls) == 1 and str(calls[0].url) == MANIFEST_URL
    assert calls[0].headers["Cache-Control"] == "no-cache"
    assert result["checked"] and result["release"] is None and not result["available"]
    assert (tmp_path / "state/update_reminder.json").is_file()


def test_only_local_version_fields_matter_and_download_link_is_fixed(tmp_path):
    value = release(downloadUrl="https://evil.test/installer.exe", packageSha256="not-used")
    manager, _, _ = make_manager(tmp_path, value)
    result = asyncio.run(manager.check_for_prompt(active=True))
    assert result["available"] and result["showPrompt"]
    assert result["downloadPageUrl"] == "https://fgogotran.com/zh-CN/download/#local"
    assert "downloadUrl" not in result["release"]
    assert "packageSha256" not in result["release"]
    assert not hasattr(manager, "install")


def test_successful_passive_checks_and_dismissal_are_persisted_for_24_hours(tmp_path):
    async def exercise():
        manager, calls, now = make_manager(tmp_path, release())
        assert (await manager.check_for_prompt(active=False))["showPrompt"]
        await manager.reminder_action(2, "dismiss")
        assert not (await manager.check_for_prompt(active=False))["showPrompt"]
        assert len(calls) == 1
        reopened = UpdateManager(tmp_path, transport=manager.transport, clock=lambda: now[0])
        assert not (await reopened.check_for_prompt(active=False))["showPrompt"]
        assert len(calls) == 1
        now[0] += SUCCESS_COOLDOWN
        assert (await reopened.check_for_prompt(active=False))["showPrompt"]
        assert len(calls) == 2
    asyncio.run(exercise())


def test_ignored_version_blocks_only_passive_reminders_and_newer_version_returns(tmp_path):
    async def exercise():
        manager, calls, now = make_manager(tmp_path, release())
        await manager.check_for_prompt(active=False)
        await manager.reminder_action(2, "ignore")
        now[0] += SUCCESS_COOLDOWN
        assert not (await manager.check_for_prompt(active=False))["showPrompt"]
        # An active check bypasses both the fresh cooldown and ignored version.
        active = await manager.check_for_prompt(active=True)
        assert active["showPrompt"] and active["source"] == "active"
        assert len(calls) == 3
        manager.transport = httpx.MockTransport(lambda _: httpx.Response(200, json=document(release(versionCode=3, versionName="v1.0.2"))))
        now[0] += SUCCESS_COOLDOWN
        assert (await manager.check_for_prompt(active=False))["showPrompt"]
    asyncio.run(exercise())


def test_failure_is_silent_passively_and_retry_cooldown_is_one_hour(tmp_path):
    async def exercise():
        manager, calls, now = make_manager(tmp_path)
        def fail(request):
            calls.append(request)
            return httpx.Response(503)
        manager.transport = httpx.MockTransport(fail)
        result = await manager.check_for_prompt(active=False)
        assert not result["showPrompt"] and result["isError"]
        now[0] += FAILURE_COOLDOWN - 1
        assert not (await manager.check_for_prompt(active=False))["showPrompt"]
        assert len(calls) == 1
        now[0] += 1
        await manager.check_for_prompt(active=False)
        assert len(calls) == 2
        with pytest.raises(StudioError):
            await manager.check_for_prompt(active=True)
        assert len(calls) == 3
    asyncio.run(exercise())


def test_concurrent_active_and_passive_callers_share_one_network_request(tmp_path):
    async def exercise():
        calls, gate = [], asyncio.Event()
        async def handler(request):
            calls.append(request)
            await gate.wait()
            return httpx.Response(200, json=document(release()))
        manager = UpdateManager(tmp_path, transport=httpx.MockTransport(handler))
        passive = asyncio.create_task(manager.check_for_prompt(active=False))
        active = asyncio.create_task(manager.check_for_prompt(active=True))
        for _ in range(20):
            if calls:
                break
            await asyncio.sleep(.01)
        assert len(calls) == 1
        gate.set()
        _, result = await asyncio.gather(passive, active)
        assert result["showPrompt"] and len(calls) == 1
    asyncio.run(exercise())


def test_no_version_or_current_version_never_prompts(tmp_path):
    for index, value in enumerate((None, release(versionCode=1))):
        manager, _, _ = make_manager(tmp_path / str(index), value)
        assert not asyncio.run(manager.check_for_prompt(active=True))["showPrompt"]


@pytest.mark.parametrize("body", [b"not json", b"[1]", b"x" * (MAX_MANIFEST_BYTES + 1)],
                         ids=["invalid-json", "non-object", "too-large"])
def test_invalid_manifest_keeps_fixed_website_entry(tmp_path, body):
    manager = UpdateManager(tmp_path, transport=httpx.MockTransport(lambda _: httpx.Response(200, content=body)))
    with pytest.raises(StudioError):
        asyncio.run(manager.check())
    assert manager.snapshot()["downloadPageUrl"] == download_page_url()
    assert CATALOGS["zh-Hans"]["update.currentLabel"] in _update_markup(manager.snapshot())


@pytest.mark.parametrize("changes", [{"versionCode": True}, {"versionCode": -1}, {"versionName": "bad"},
    {"platform": "linux"}, {"changelog": "text"}, {"changelogI18n": {"en": "text"}}])
def test_invalid_version_metadata_is_rejected(changes):
    with pytest.raises(ValueError):
        parse_release(document(release(**changes)))


def test_corrupt_preferences_and_clock_rollback_do_not_disable_version_checks(tmp_path):
    path = tmp_path / "state/update_reminder.json"
    path.parent.mkdir()
    path.write_text("broken")
    manager, calls, now = make_manager(tmp_path, release())
    asyncio.run(manager.check_for_prompt(active=False))
    now[0] -= 100
    assert asyncio.run(manager.check_for_prompt(active=False))["showPrompt"]
    assert len(calls) == 2


def test_failed_ignore_save_does_not_pretend_version_was_ignored(tmp_path):
    async def exercise():
        manager, _, _ = make_manager(tmp_path, release())
        await manager.check_for_prompt(active=True)
        with patch.object(manager, "_save_sync", side_effect=OSError("disk")):
            with pytest.raises(StudioError):
                await manager.reminder_action(2, "ignore")
        assert manager.record.get("ignoredVersionCode", 0) == 0
        assert json.loads(manager.path.read_text(encoding="utf-8")).get("ignoredVersionCode", 0) == 0
    asyncio.run(exercise())


def test_atomic_preferences_save_retries_a_brief_windows_file_lock(tmp_path):
    async def exercise():
        manager, _, _ = make_manager(tmp_path, release())
        original, attempts = os.replace, []
        def replace(source, target):
            attempts.append(target)
            if len(attempts) < 3:
                raise PermissionError("temporary file lock")
            return original(source, target)
        with patch("fgogotran_local.updates.os.replace", side_effect=replace):
            await manager.check_for_prompt(active=True)
        assert len(attempts) >= 3
        assert json.loads(manager.path.read_text(encoding="utf-8"))["release"]["versionCode"] == 2
        assert list(manager.path.parent.glob("*.tmp")) == []
    asyncio.run(exercise())


def test_reminder_api_security_and_user_configuration_are_preserved(tmp_path):
    service = LocalTranslationService(tmp_path)
    service.updates.transport = httpx.MockTransport(lambda _: httpx.Response(200, json=document(release())))
    with TestClient(create_application(service, mount_ui=False, allow_test_host=True)) as client:
        config = (tmp_path / "config.json").read_bytes()
        assert client.post("/api/updates/check", json={}).status_code == 403
        result = client.post("/api/updates/check", json={}, headers={"X-FGO-Control": "1"}).json()
        assert result["showPrompt"] and result["source"] == "active"
        assert client.post("/api/updates/reminder", json={"versionCode": 2, "action": "ignore"}).status_code == 403
        assert client.post("/api/updates/reminder", headers={"X-FGO-Control": "1"}, json={"versionCode": 2, "action": "ignore"}).status_code == 200
        assert client.post("/api/updates/reminder", headers={"X-FGO-Control": "1"}, json={"versionCode": 999, "action": "ignore"}).status_code == 409
        assert client.post("/api/updates/install", json={}, headers={"X-FGO-Control": "1"}).status_code == 404
        assert config == (tmp_path / "config.json").read_bytes()
        assert not service.manager.is_running()


def test_update_ui_and_dialog_in_all_locales(tmp_path):
    icon = Path(__file__).parents[1] / "public/gotran-icon.png"
    async def exercise(locale):
        service = LocalTranslationService(tmp_path / locale)
        malicious_notes = document(release(changelog=["<script>bad</script>"], changelogI18n={}))
        service.updates.transport = httpx.MockTransport(lambda _: httpx.Response(200, json=malicious_notes))
        studio = build_ui(service, str(icon), locale)
        components = studio.get_config_file()["components"]
        download = next(item["props"] for item in components
                        if item.get("type") == "button" and item["props"].get("elem_id") == "local-download-page")
        assert download["link"] == download_page_url(locale)
        assert download["link_target"] == "_self"
        assert download["value"] == CATALOGS[locale]["update.download"]
        callbacks = {fn.fn.__name__: fn.fn for fn in studio.fns.values() if fn.fn}
        token = activate(locale)
        try:
            stages = [value async for value in callbacks["check_update"]()]
            assert stages[0][0]["interactive"] is False
            assert stages[-1][0]["interactive"] is True
            dialog = stages[-1][2]
            assert dialog["showPrompt"] and dialog["source"] == "active"
            assert dialog["downloadPageUrl"] == download_page_url(locale)
            assert dialog["labels"]["title"] == CATALOGS[locale]["update.dialogTitle"]
            assert "<script>" not in stages[-1][1]
            assert CATALOGS[locale]["update.currentLabel"] in stages[-1][1]
            assert "innerHTML" not in DIALOG_JS and "li.textContent" in DIALOG_JS
            # New tabs/page refreshes do not show a second passive dialog.
            _, passive = await callbacks["check_update_passive"]()
            assert not passive.get("showPrompt")
            service.updates.transport = httpx.MockTransport(lambda _: httpx.Response(503))
            stages = [value async for value in callbacks["check_update"]()]
            assert CATALOGS[locale]["update.checkFailed"] in stages[-1][1]
            assert CATALOGS[locale]["update.currentLabel"] in stages[-1][1]
            assert download["link"] == download_page_url(locale)
        finally:
            deactivate(token)
    for locale in CATALOGS:
        asyncio.run(exercise(locale))


@pytest.mark.parametrize("locale", ["en", "zh-Hant"])
def test_streamed_update_callback_activates_its_locale_without_caller_context(tmp_path, locale):
    service = LocalTranslationService(tmp_path)
    service.updates.transport = httpx.MockTransport(lambda _: httpx.Response(200, json=document(release())))
    icon = Path(__file__).parents[1] / "public/gotran-icon.png"
    studio = build_ui(service, str(icon), locale)
    callback = next(fn.fn for fn in studio.fns.values() if fn.fn and fn.fn.__name__ == "check_update")
    async def exercise():
        stages = [value async for value in callback()]
        assert stages[0][0]["value"] == CATALOGS[locale]["update.checking"]
        assert stages[-1][2]["labels"]["title"] == CATALOGS[locale]["update.dialogTitle"]
        assert stages[-1][2]["downloadPageUrl"] == download_page_url(locale)
    asyncio.run(exercise())


@pytest.mark.skipif(not shutil.which("node"), reason="Offline JavaScript event tests need Node")
def test_dialog_events_offline_without_a_browser_or_network(tmp_path):
    manager, _, _ = make_manager(tmp_path, release())
    status = asyncio.run(manager.check_for_prompt(active=False))
    payload = dialog_value(status)
    # Plain JS stubs test wiring only. This does not launch a browser or assert
    # actual layout, native focus behavior or rendering.
    harness = r'''
const vm=require('node:vm'), assert=require('node:assert/strict');
class Element {
  constructor(){this.handlers={};this.open=false;this.children=[];}
  addEventListener(name,fn){this.handlers[name]=fn;}
  setAttribute(name,value){this[name]=value;}
  replaceChildren(){this.children=[];}
  appendChild(child){this.children.push(child);}
  showModal(){this.open=true;}
  close(){this.open=false;}
  focus(){this.focused=true;}
  getBoundingClientRect(){return {left:0,right:500,top:0,bottom:500};}
}
const selectors=['dialog','.close-dialog','.dismiss-update','.ignore-version','.download-update',
  '.save-error','#update-dialog-title','.current-version','.latest-version','.release-date','.notes-title','ul'];
const nodes=Object.fromEntries(selectors.map(name=>[name,new Element()]));
let watcher,fail=false; const requests=[];
const context={element:{querySelector:selector=>nodes[selector]},props:{value:PAYLOAD},
  document:{createElement:()=>new Element()},watch:(key,fn)=>{watcher=fn;},
  fetch:async(url,options)=>{requests.push({url,...options});return {ok:!fail};}};
vm.createContext(context); vm.runInContext(SOURCE,context);
(async()=>{
  assert.equal(nodes.dialog.open,true);
  assert.equal(nodes['.ignore-version'].hidden,false);
  assert.equal(nodes['.download-update'].target,'_self');
  assert.equal(nodes['.download-update'].href,context.props.value.downloadPageUrl);
  fail=true;await nodes['.ignore-version'].handlers.click();
  assert.equal(nodes.dialog.open,true);assert.ok(nodes['.save-error'].textContent);
  fail=false;await nodes['.ignore-version'].handlers.click();
  assert.equal(nodes.dialog.open,false);
  assert.equal(JSON.parse(requests.at(-1).body).action,'ignore');
  assert.equal(requests.at(-1).headers['X-FGO-Control'],'1');
  context.props.value={...context.props.value,source:'active'};watcher();
  assert.equal(nodes['.ignore-version'].hidden,true);
  let prevented=false;nodes.dialog.handlers.cancel({preventDefault(){prevented=true;}});
  assert.equal(prevented,true);assert.equal(nodes.dialog.open,false);
  assert.equal(JSON.parse(requests.at(-1).body).action,'dismiss');
  watcher();nodes['.download-update'].handlers.click();
  assert.equal(nodes.dialog.open,false);
  assert.equal(requests.at(-1).keepalive,true);
  assert.equal(JSON.parse(requests.at(-1).body).versionCode,2);
  // Gradio may replace the HTML subtree before calling the value watcher.
  for(const selector of selectors) nodes[selector]=new Element();
  watcher();assert.equal(nodes.dialog.open,true);
  assert.equal(nodes['.ignore-version'].hidden,true);
  nodes['.dismiss-update'].handlers.click();assert.equal(nodes.dialog.open,false);
  console.log('Dialog event logic passed (offline stubs only)');
})().catch(error=>{console.error(error);process.exitCode=1;});
'''
    harness = harness.replace("PAYLOAD", json.dumps(payload, ensure_ascii=True)).replace("SOURCE", json.dumps(DIALOG_JS))
    completed = subprocess.run(["node", "-e", harness], capture_output=True, text=True, timeout=20)
    assert completed.returncode == 0, completed.stderr
