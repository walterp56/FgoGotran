"""Localization for the FgoGotran Local control interface.

Catalogs are plain Python dicts (no packaging changes needed) and the active
locale lives in a context variable, so the service layer can emit localized
messages without threading a ``locale`` argument through every call:

    from .i18n import activate, t

    activate("zh-Hant")
    t("llama.status.ready")      # -> "已就緒"
    t("ui.test.passed", ms=123)  # -> "相容性測試通過，耗時 123 毫秒。"

    activate("en")
    t("ui.overview.started")     # -> "Service started."

The Gradio UI activates its locale at the start of every event handler; direct
calls (tests, scripts) fall back to :data:`DEFAULT_LOCALE`.

``zh-Hans`` is the source of truth; ``zh-Hant`` (Taiwan terms) and ``en``
(US English) are translated from it.
"""

from __future__ import annotations

import contextvars
from typing import Any

from . import en, zh_hans, zh_hant

DEFAULT_LOCALE = "zh-Hans"

CATALOGS: dict[str, dict[str, str]] = {
    "zh-Hans": zh_hans.MESSAGES,
    "zh-Hant": zh_hant.MESSAGES,
    "en": en.MESSAGES,
}

LOCALES: tuple[str, ...] = tuple(CATALOGS)

_active_locale: contextvars.ContextVar[str | None] = contextvars.ContextVar(
    "fgogotran_local_locale", default=None
)


def normalize_locale(value: str | None) -> str:
    """Map a tag such as ``zh-TW``, ``zh-Hant`` or ``en-US`` to a catalog name."""
    if not value:
        return DEFAULT_LOCALE
    tag = str(value).strip().lower()
    if tag.startswith("zh"):
        if any(marker in tag for marker in ("hant", "tw", "hk", "mo")):
            return "zh-Hant"
        return "zh-Hans"
    if tag.startswith("en"):
        return "en"
    return DEFAULT_LOCALE


def activate(locale: str) -> contextvars.Token[str | None]:
    """Set the locale for the current context and return a reset token."""
    resolved = locale if locale in CATALOGS else normalize_locale(locale)
    return _active_locale.set(resolved)


def deactivate(token: contextvars.Token[str | None]) -> None:
    _active_locale.reset(token)


def current() -> str:
    return _active_locale.get() or DEFAULT_LOCALE


def catalog(locale: str | None = None) -> dict[str, str]:
    return CATALOGS.get(locale or current(), CATALOGS[DEFAULT_LOCALE])


def translate(locale: str, key: str, /, **params: Any) -> str:
    """Look up ``key`` for ``locale``, falling back to the default catalog."""
    resolved = locale if locale in CATALOGS else normalize_locale(locale)
    text = CATALOGS[resolved].get(key)
    if text is None:
        text = CATALOGS[DEFAULT_LOCALE].get(key, key)
    if not params:
        return text
    try:
        return text.format(**params)
    except (KeyError, IndexError, ValueError):
        # Never let a bad placeholder break the UI; show the raw template.
        return text


def t(key: str, /, **params: Any) -> str:
    """Translate ``key`` using the active locale."""
    return translate(current(), key, **params)


def missing_keys(locale: str) -> set[str]:
    """Keys present in the default catalog but missing from ``locale``."""
    default_keys = set(CATALOGS[DEFAULT_LOCALE])
    return default_keys - set(CATALOGS.get(locale, {}))


def extra_keys(locale: str) -> set[str]:
    default_keys = set(CATALOGS[DEFAULT_LOCALE])
    return set(CATALOGS.get(locale, {})) - default_keys


__all__ = [
    "CATALOGS",
    "DEFAULT_LOCALE",
    "LOCALES",
    "activate",
    "catalog",
    "current",
    "deactivate",
    "extra_keys",
    "missing_keys",
    "normalize_locale",
    "t",
    "translate",
]