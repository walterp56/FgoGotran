import ast
import re
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from fgogotran_local.application import create_application
from fgogotran_local.i18n import (
    CATALOGS,
    DEFAULT_LOCALE,
    LOCALES,
    activate,
    deactivate,
    extra_keys,
    missing_keys,
    normalize_locale,
    t,
    translate,
)
from fgogotran_local.log_store import LogStore
from fgogotran_local.service import LocalTranslationService
from fgogotran_local.ui import build_ui


PACKAGE_ROOT = Path(__file__).parents[1] / "src" / "fgogotran_local"
PLACEHOLDER = re.compile(r"\{(\w+)\}")
CJK = re.compile(r"[\u4e00-\u9fff]")
TRANSLATED_LOCALES = ("zh-Hant", "en")
LANGUAGE_ROUTES = {"/": "zh-Hans", "/zh-Hant/": "zh-Hant", "/en/": "en"}
ALLOWED_CHINESE_LITERALS = {
    # Header language switcher labels stay script-native on purpose.
    "简体",
    "繁體",
}


def test_all_language_catalogs_are_available():
    assert DEFAULT_LOCALE == "zh-Hans"
    assert LOCALES == ("zh-Hans", "zh-Hant", "en")


@pytest.mark.parametrize("locale", TRANSLATED_LOCALES)
def test_catalogs_share_the_same_keys(locale: str):
    assert missing_keys(locale) == set()
    assert extra_keys(locale) == set()


@pytest.mark.parametrize("locale", TRANSLATED_LOCALES)
def test_catalog_placeholders_match_between_locales(locale: str):
    simplified = CATALOGS["zh-Hans"]
    translated = CATALOGS[locale]

    mismatched = {
        key: (sorted(PLACEHOLDER.findall(text)), sorted(PLACEHOLDER.findall(translated[key])))
        for key, text in simplified.items()
        if sorted(PLACEHOLDER.findall(text)) != sorted(PLACEHOLDER.findall(translated[key]))
    }

    assert mismatched == {}


@pytest.mark.parametrize("locale", TRANSLATED_LOCALES)
def test_translated_catalogs_are_not_copies_of_the_simplified_text(locale: str):
    for key in ("ui.tab.model", "ui.status.title", "ui.system.logs"):
        assert CATALOGS[locale][key] != CATALOGS["zh-Hans"][key]

    assert translate("zh-Hans", "ui.tab.model") == "模型设置"
    assert translate("zh-Hant", "ui.tab.model") == "模型設定"
    assert translate("en", "ui.tab.model") == "Model settings"


def test_english_catalog_keeps_the_agreed_capitalization():
    tabs = [CATALOGS["en"][key] for key in ("ui.tab.overview", "ui.tab.model", "ui.tab.test", "ui.tab.system")]
    labels = [
        CATALOGS["en"]["ui.overview.start"],
        CATALOGS["en"]["ui.model.saveButton"],
        CATALOGS["en"]["ui.system.clearDisplay"],
    ]

    assert tabs == ["Overview", "Model settings", "Connection test", "System"]
    assert labels == ["Start service", "Save and use as active profile", "Clear display"]


def test_english_catalog_contains_no_han_characters():
    offenders = [key for key, text in CATALOGS["en"].items() if CJK.search(text)]

    assert offenders == []


def test_normalize_locale_accepts_browser_style_tags():
    assert normalize_locale(None) == "zh-Hans"
    assert normalize_locale("zh") == "zh-Hans"
    assert normalize_locale("zh-CN") == "zh-Hans"
    assert normalize_locale("zh-Hant") == "zh-Hant"
    assert normalize_locale("zh-TW") == "zh-Hant"
    assert normalize_locale("zh-HK") == "zh-Hant"
    assert normalize_locale("en") == "en"
    assert normalize_locale("en-US") == "en"
    assert normalize_locale("en-GB") == "en"
    # Anything that is not Chinese or English keeps the project default.
    assert normalize_locale("ja-JP") == "zh-Hans"
    assert normalize_locale("ko-KR") == "zh-Hans"


def test_activation_only_affects_the_current_context():
    assert t("ui.tab.system") == "系统"

    for locale, expected in (("zh-Hant", "系統"), ("en", "System")):
        token = activate(locale)
        try:
            assert t("ui.tab.system") == expected
        finally:
            deactivate(token)

    assert t("ui.tab.system") == "系统"


def test_unknown_keys_and_missing_placeholders_never_crash():
    assert translate("en", "missing.key") == "missing.key"
    assert translate("en", "ui.test.passed") == "Compatibility test passed in {ms} ms."
    assert translate("zh-Hant", "ui.test.passed") == "相容性測試通過，耗時 {ms} 毫秒。"


def test_runtime_log_messages_follow_the_active_locale(tmp_path: Path):
    logs = LogStore(tmp_path / "state", lambda: "secret")
    logs.add("INFO", t("llama.log.consoleReady"), key="llama.log.consoleReady")

    def render(locale: str) -> str:
        token = activate(locale)
        try:
            return logs.formatted()
        finally:
            deactivate(token)

    assert "控制台已就绪，服务器尚未启动。" in render("zh-Hans")
    assert "控制台已就緒，伺服器尚未啟動。" in render("zh-Hant")
    assert "Console ready; the server has not started yet." in render("en")


def test_english_ui_builds_with_english_labels(tmp_path: Path):
    icon = Path(__file__).parents[1] / "public" / "gotran-icon.png"
    studio = build_ui(LocalTranslationService(tmp_path), str(icon), "en")
    config = studio.get_config_file()
    tab_labels = [
        component["props"]["label"]
        for component in config["components"]
        if component.get("type") == "tabitem"
    ]
    serialized = str(config)

    assert tuple(tab_labels[:4]) == ("Overview", "Model settings", "Connection test", "System")
    assert tab_labels[4:] == ["Software updates", "Environment diagnostics", "Runtime log"]
    assert "Local translation model setup and runtime control" in serialized
    assert 'class="studio-lang"' in serialized
    assert "/en/" in serialized


def test_traditional_ui_builds_with_traditional_labels(tmp_path: Path):
    icon = Path(__file__).parents[1] / "public" / "gotran-icon.png"
    studio = build_ui(LocalTranslationService(tmp_path), str(icon), "zh-Hant")
    config = studio.get_config_file()
    tab_labels = [
        component["props"]["label"]
        for component in config["components"]
        if component.get("type") == "tabitem"
    ]
    serialized = str(config)

    assert tuple(tab_labels[:4]) == ("總覽", "模型設定", "連線測試", "系統")
    assert tab_labels[4:] == ["軟體更新", "環境診斷", "執行日誌"]
    assert "本地翻譯模型設定與執行控制" in serialized
    assert 'class="studio-lang"' in serialized
    assert "/zh-Hant/" in serialized


@pytest.mark.parametrize("href,locale", list(LANGUAGE_ROUTES.items()))
def test_header_switcher_marks_the_current_page(tmp_path: Path, href: str, locale: str):
    icon = Path(__file__).parents[1] / "public" / "gotran-icon.png"
    studio = build_ui(LocalTranslationService(tmp_path), str(icon), locale)
    config = studio.get_config_file()
    header = next(
        component["props"]["value"]
        for component in config["components"]
        if component.get("type") == "html" and "studio-header" in str(component.get("props", {}).get("value", ""))
    )
    nav = header[header.index("<nav") : header.index("</nav>")]

    assert nav.count("lang-link") == 3
    assert f'class="lang-link is-active" data-ui-language="{locale}" data-ui-path="{href}" aria-pressed="true"' in nav


def test_application_serves_all_language_routes(tmp_path: Path):
    application = create_application(LocalTranslationService(tmp_path), allow_test_host=True)
    with TestClient(application) as client:
        pages = {path: client.get(path) for path in LANGUAGE_ROUTES}

    assert all(response.status_code == 200 for response in pages.values())
    assert "本地翻译模型设置与运行控制" in pages["/"].text
    assert "本地翻譯模型設定與執行控制" in pages["/zh-Hant/"].text
    assert "Local translation model setup and runtime control" in pages["/en/"].text
    for response in pages.values():
        assert "/zh-Hant/" in response.text
        assert "/en/" in response.text
        assert "English" in response.text


@pytest.mark.parametrize("path,language", list(LANGUAGE_ROUTES.items()))
def test_each_page_declares_its_document_language(tmp_path: Path, path: str, language: str):
    application = create_application(LocalTranslationService(tmp_path), allow_test_host=True)
    compressible = {"accept-encoding": "br, gzip"}
    with TestClient(application) as client:
        response = client.get(path, headers=compressible)

    assert f'lang="{language}"' in response.text


def test_control_api_errors_follow_accept_language(tmp_path: Path):
    application = create_application(LocalTranslationService(tmp_path), allow_test_host=True)
    with TestClient(application) as client:
        english = client.post("/api/actions/start", json={}, headers={"Accept-Language": "en-US"})
        simplified = client.post("/api/actions/start", json={}, headers={"Accept-Language": "zh-CN"})

    assert english.status_code == 403
    assert english.json()["error"] == "Missing local control header."
    assert simplified.json()["error"] == "缺少本地控制 Header。"


def test_no_hardcoded_chinese_outside_the_catalogs():
    offenders = []
    for path in sorted(PACKAGE_ROOT.rglob("*.py")):
        relative = path.relative_to(PACKAGE_ROOT)
        if relative.parts[0] == "i18n":
            continue
        tree = ast.parse(path.read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            if (
                isinstance(node, ast.Constant)
                and isinstance(node.value, str)
                and CJK.search(node.value)
                and node.value not in ALLOWED_CHINESE_LITERALS
            ):
                offenders.append(f"{relative}:{node.lineno}: {node.value[:60]!r}")

    assert offenders == []
