from __future__ import annotations

from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any, Awaitable, Callable

import gradio as gr
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from .control_api import create_control_router
from .errors import StudioError
from .privacy import redact_sensitive_text
from .security import LocalControlSecurityMiddleware
from .service import LocalTranslationService
from .ui import STUDIO_CSS, build_ui, studio_theme


Scope = dict[str, Any]
Receive = Callable[[], Awaitable[dict[str, Any]]]
Send = Callable[[dict[str, Any]], Awaitable[None]]


class PageLanguageMiddleware:
    """Declare each mounted page's language on the html element.

    Gradio renders one static shell with lang="en" for every mount and only
    offers client-side head injection, so the attribute is rewritten here from
    the route. Accept-Encoding is dropped for these pages because the
    compression middleware would otherwise hide the attribute; both pages are
    served over the loopback interface.
    """

    def __init__(self, app: Any, routes: dict[str, str]) -> None:
        self.app = app
        self.routes = routes

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        language = self.routes.get(scope.get("path", "")) if scope["type"] == "http" else None
        if language is None or scope.get("method") != "GET":
            await self.app(scope, receive, send)
            return

        scope = dict(scope)
        scope["headers"] = [
            (name, value) for name, value in scope.get("headers", []) if name.lower() != b"accept-encoding"
        ]

        start: dict[str, Any] | None = None
        body = bytearray()

        async def send_localized(message: dict[str, Any]) -> None:
            nonlocal start
            if message["type"] == "http.response.start":
                headers = message.get("headers", [])
                content_type = next((value for name, value in headers if name.lower() == b"content-type"), b"")
                if content_type.startswith(b"text/html"):
                    start = message
                    return
            elif message["type"] == "http.response.body" and start is not None:
                body.extend(message.get("body", b""))
                if message.get("more_body"):
                    return
                html = bytes(body).replace(b'lang="en"', f'lang="{language}"'.encode("ascii"), 1)
                headers = [
                    (name, value)
                    for name, value in start.get("headers", [])
                    if name.lower() not in {b"content-length", b"content-encoding"}
                ]
                headers.append((b"content-length", str(len(html)).encode("ascii")))
                await send({"type": "http.response.start", "status": start["status"], "headers": headers})
                await send({"type": "http.response.body", "body": html})
                return
            await send(message)

        await self.app(scope, receive, send_localized)


def create_application(
    service: LocalTranslationService,
    *,
    mount_ui: bool = True,
    allow_test_host: bool = False,
) -> FastAPI:
    @asynccontextmanager
    async def lifespan(_: FastAPI):
        await service.initialize()
        try:
            yield
        finally:
            await service.close()

    app = FastAPI(
        title="FgoGotran Local",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
        lifespan=lifespan,
    )
    allowed_hosts = ["127.0.0.1", "localhost"]
    if allow_test_host:
        allowed_hosts.append("testserver")
    app.add_middleware(LocalControlSecurityMiddleware, allowed_hosts=allowed_hosts)
    app.add_middleware(
        PageLanguageMiddleware,
        routes={"/": "zh-Hans", "/zh-Hant/": "zh-Hant", "/en/": "en"},
    )

    @app.exception_handler(StudioError)
    async def local_error_handler(_: Request, error: StudioError) -> JSONResponse:
        return JSONResponse({"error": redact_sensitive_text(str(error))}, status_code=error.status_code)

    app.include_router(create_control_router(service))
    if not mount_ui:
        return app

    icon_path = Path(__file__).resolve().parents[2] / "public" / "gotran-icon.png"
    blocked_paths = [
        service.data_directory / "config.json",
        service.data_directory / "config.json.bak",
        service.data_directory / "config.json.tmp",
        service.data_directory / "state",
    ]

    def mount_locale(locale: str, path: str, *, favicon: bool = False) -> None:
        gr.mount_gradio_app(
            app,
            build_ui(service, str(icon_path), locale),
            path=path,
            favicon_path=str(icon_path) if favicon else None,
            allowed_paths=[],
            blocked_paths=[str(item) for item in blocked_paths],
            show_error=False,
            enable_monitoring=False,
            footer_links=[],
            theme=studio_theme(),
            css=STUDIO_CSS,
        )

    # Register the prefixed mounts first: the Simplified Chinese mount below
    # lives at "/" and matches every path, so longer prefixes must come before
    # it. The header language links point at these three routes.
    mount_locale("zh-Hant", "/zh-Hant/")
    mount_locale("en", "/en/")
    mount_locale("zh-Hans", "/", favicon=True)
    return app
