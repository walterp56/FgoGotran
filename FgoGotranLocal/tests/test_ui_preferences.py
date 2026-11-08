import asyncio
import json
import shutil
import subprocess
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient

from fgogotran_local.__main__ import _open_browser_when_ready
from fgogotran_local.application import create_application
from fgogotran_local.errors import ConfigError
from fgogotran_local.service import LocalTranslationService
from fgogotran_local.ui import LANGUAGE_SWITCH_JS
from fgogotran_local.ui_preferences import LANGUAGE_PATHS, UiPreferences


ROOT = Path(__file__).parents[1]
HEADERS = {"X-FGO-Control": "1"}


@pytest.mark.parametrize("language,path", LANGUAGE_PATHS.items())
def test_language_is_saved_and_reloaded_without_model_configuration(tmp_path, language, path):
    preferences = UiPreferences(tmp_path)
    assert preferences.language == "zh-Hans" and not preferences.path.exists()
    result = asyncio.run(preferences.set_language(language))
    assert result == {"language": language, "path": path}
    assert json.loads(preferences.path.read_text(encoding="utf-8")) == {"version": 1, "language": language}
    restored = UiPreferences(tmp_path)
    assert restored.language == language and restored.preferred_path == path
    assert not (tmp_path / "config.json").exists()


@pytest.mark.parametrize("raw", [
    b"not-json", b"[]", b"null", b"\xff", b"x" * 4097,
    b'{"version":true,"language":"en"}', b'{"version":2,"language":"en"}',
    b'{"version":1,"language":"unsupported"}', b'{"version":1,"language":[]}',
])
def test_invalid_preferences_fall_back_without_rewriting_any_data(tmp_path, raw):
    path = tmp_path / "state/ui_preferences.json"
    path.parent.mkdir()
    path.write_bytes(raw)
    preferences = UiPreferences(tmp_path)
    assert preferences.language == "zh-Hans" and preferences.preferred_path == "/"
    assert path.read_bytes() == raw


@pytest.mark.parametrize("language", [None, True, {}, "en-US", "../en", "https://example.com/", ""])
def test_invalid_choices_cannot_write_preferences_or_redirect_outside_the_ui(tmp_path, language):
    preferences = UiPreferences(tmp_path)
    with pytest.raises(ConfigError):
        asyncio.run(preferences.set_language(language))
    assert not preferences.path.exists() and preferences.preferred_path == "/"


def test_failed_atomic_save_preserves_previous_preference_and_removes_temporary_file(tmp_path):
    preferences = UiPreferences(tmp_path)
    asyncio.run(preferences.set_language("zh-Hant"))
    original = preferences.path.read_bytes()
    with patch("fgogotran_local.ui_preferences.os.replace", side_effect=PermissionError("busy")) as replace:
        with pytest.raises(ConfigError) as error:
            asyncio.run(preferences.set_language("en"))
    assert error.value.status_code == 503 and replace.call_count == 3
    assert preferences.language == "zh-Hant" and preferences.path.read_bytes() == original
    assert not list(preferences.path.parent.glob("*.tmp"))
    assert asyncio.run(preferences.set_language("en"))["path"] == "/en/"


def test_last_completed_choice_is_retained_and_moves_with_user_data(tmp_path):
    data = tmp_path / "custom data"
    preferences = UiPreferences(data)
    async def save():
        await asyncio.gather(*(preferences.set_language(language) for language in LANGUAGE_PATHS))
    asyncio.run(save())
    assert preferences.language == "en"
    backup = tmp_path / "restored user_data"
    shutil.copytree(data, backup)
    assert UiPreferences(backup).preferred_path == "/en/"


def test_api_checks_mutations_and_never_changes_translation_settings(tmp_path):
    service = LocalTranslationService(tmp_path)
    app = create_application(service, mount_ui=False, allow_test_host=True)
    with TestClient(app) as client:
        original = (tmp_path / "config.json").read_bytes()
        revision = service.public_config()["revision"]
        assert client.post("/api/ui-preferences/language", json={"language": "en"}).status_code == 403
        assert client.post("/api/ui-preferences/language", content="language=en", headers=HEADERS).status_code == 415
        assert client.post("/api/ui-preferences/language", json={"language": "en"},
                           headers={**HEADERS, "Origin": "https://example.com"}).status_code == 403
        assert client.post("/api/ui-preferences/language", json={"language": "unsupported"}, headers=HEADERS).status_code == 400
        assert not service.ui_preferences.path.exists()
        for language, path in LANGUAGE_PATHS.items():
            result = client.post("/api/ui-preferences/language", json={"language": language}, headers=HEADERS)
            assert result.status_code == 200 and result.json() == {"language": language, "path": path}
        assert (tmp_path / "config.json").read_bytes() == original
        assert service.public_config()["revision"] == revision
        assert not service.manager.is_running()


def test_api_reports_failed_save_in_the_current_interface_language(tmp_path, monkeypatch):
    service = LocalTranslationService(tmp_path)
    app = create_application(service, mount_ui=False, allow_test_host=True)
    def fail(_language):
        raise OSError("private path or other details must not appear")
    monkeypatch.setattr(service.ui_preferences, "_write", fail)
    with TestClient(app) as client:
        result = client.post("/api/ui-preferences/language", json={"language": "en"},
                             headers={**HEADERS, "Accept-Language": "en"})
    assert result.status_code == 503
    assert "Could not save your language choice" in result.json()["error"]
    assert "private path" not in result.text and service.ui_preferences.language == "zh-Hans"


def test_homepage_restores_choice_without_loops_and_keeps_api_and_explicit_routes(tmp_path):
    service = LocalTranslationService(tmp_path)
    app = create_application(service, allow_test_host=True)
    with TestClient(app) as client:
        assert client.get("/", headers={"Accept-Language": "en"}).status_code == 200
        for language, path in LANGUAGE_PATHS.items():
            assert client.post("/api/ui-preferences/language", json={"language": language}, headers=HEADERS).status_code == 200
            response = client.get("/", follow_redirects=False)
            if path == "/":
                assert response.status_code == 200
            else:
                assert response.status_code == 307 and response.headers["location"] == path
                assert response.headers["cache-control"] == "no-store"
                query = client.get("/?__theme=dark", follow_redirects=False)
                assert query.headers["location"] == path + "?__theme=dark"
            assert f'lang="{language}"' in client.get("/").text
        assert client.get("/healthz").json()["ok"] is True
        assert client.get("/api/status").status_code == 200
        assert client.get("/config", follow_redirects=False).status_code == 200
        assert client.get("/zh-Hant/").status_code == 200
        assert service.ui_preferences.language == "en"  # Visiting a route does not save a preference.
        assert client.get("/", headers={"host": "example.com"}, follow_redirects=False).status_code == 400
        assert client.post("/api/ui-preferences/language", json={"language": "zh-Hans"}, headers=HEADERS).status_code == 200
        assert client.get("/", follow_redirects=False).status_code == 200


def test_fresh_service_and_browser_launch_reopen_the_saved_interface(tmp_path):
    asyncio.run(UiPreferences(tmp_path).set_language("zh-Hant"))
    # New service simulates a full Local restart, rather than retaining an in-memory preference.
    app = create_application(LocalTranslationService(tmp_path), allow_test_host=True)
    with TestClient(app) as client:
        with patch("fgogotran_local.__main__.urllib.request.urlopen") as health, \
             patch("fgogotran_local.__main__.webbrowser.open") as browser:
            health.return_value.__enter__.return_value = SimpleNamespace(status=200)
            _open_browser_when_ready("http://127.0.0.1:18081/healthz", "http://127.0.0.1:18081")
            browser.assert_called_once_with("http://127.0.0.1:18081")
        response = client.get("/")
        assert response.url.path == "/zh-Hant/" and 'lang="zh-Hant"' in response.text


@pytest.mark.skipif(not shutil.which("node"), reason="Offline language-switch event checks use Node")
def test_switch_saves_before_navigation_and_preserves_page_on_failure(tmp_path):
    harness = r'''
const vm=require('node:vm'), assert=require('node:assert/strict');
function mount(mode='ok') {
  const requests=[], navigation=[]; let finish;
  const buttons=[['zh-Hans','/'],['zh-Hant','/zh-Hant/'],['en','/en/']].map(([language,path])=>({
    dataset:{uiLanguage:language,uiPath:path},handlers:{},disabled:false,
    addEventListener(name,fn){this.handlers[name]=fn;},focus(){this.focused=true;}
  }));
  const nav={dataset:{language:'zh-Hant'},querySelectorAll(){return buttons;}};
  const status={dataset:{saving:'Saving',saveError:'Save failed'},hidden:true,classList:{add(){},remove(){}}};
  const context={element:{querySelector:s=>s==='.studio-lang'?nav:status},
    window:{location:{assign:path=>navigation.push(path)}},
    fetch:async(url,options)=>{
      requests.push({url,...options});
      if(mode==='pending') await new Promise(resolve=>{finish=resolve;});
      if(mode==='throw') throw Error('offline');
      const value=JSON.parse(options.body);
      return {ok:mode!=='fail',json:async()=>({language:value.language,path:mode==='bad-path'?'https://example.com':buttons.find(b=>b.dataset.uiLanguage===value.language).dataset.uiPath})};
    }};
  vm.runInNewContext(SOURCE,context);
  return {buttons,status,requests,navigation,finish:()=>finish()};
}
(async()=>{
 for(const selected of [0,1,2]) {
   const m=mount();await m.buttons[selected].handlers.click();
   assert.equal(m.requests.length,1);assert.equal(m.requests[0].url,'/api/ui-preferences/language');
   assert.equal(m.requests[0].headers['X-FGO-Control'],'1');
   assert.equal(m.requests[0].headers['Accept-Language'],'zh-Hant');
   assert.equal(m.navigation[0],m.buttons[selected].dataset.uiPath);
 }
 const pending=mount('pending');const task=pending.buttons[2].handlers.click();
 assert.equal(pending.navigation.length,0);assert.ok(pending.buttons.every(b=>b.disabled));
 await pending.buttons[0].handlers.click();assert.equal(pending.requests.length,1);
 pending.finish();await task;assert.equal(pending.navigation[0],'/en/');
 for(const mode of ['fail','throw','bad-path']) {
   const m=mount(mode);await m.buttons[2].handlers.click();
   assert.equal(m.navigation.length,0);assert.equal(m.status.hidden,false);
   assert.equal(m.status.textContent,'Save failed');assert.ok(m.buttons.every(b=>!b.disabled));
   assert.equal(m.buttons[2].focused,true);
   await m.buttons[0].handlers.click();assert.equal(m.requests.length,2);
 }
 console.log('Language switch checks passed (offline stubs only)');
})().catch(error=>{console.error(error);process.exitCode=1;});
'''.replace("SOURCE", json.dumps(LANGUAGE_SWITCH_JS))
    result = subprocess.run(["node", "-e", harness], capture_output=True, text=True, timeout=20)
    assert result.returncode == 0, result.stderr
