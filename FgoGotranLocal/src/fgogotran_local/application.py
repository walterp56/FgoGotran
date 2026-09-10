from __future__ import annotations

from contextlib import asynccontextmanager
from pathlib import Path

import gradio as gr
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from .control_api import create_control_router
from .errors import StudioError
from .security import LocalControlSecurityMiddleware
from .service import LocalTranslationService
from .ui import STUDIO_CSS, build_ui, studio_theme


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

    @app.exception_handler(StudioError)
    async def local_error_handler(_: Request, error: StudioError) -> JSONResponse:
        return JSONResponse({"error": str(error)}, status_code=error.status_code)

    app.include_router(create_control_router(service))
    if not mount_ui:
        return app

    icon_path = Path(__file__).resolve().parents[2] / "public" / "gotran-icon.png"
    studio = build_ui(service, str(icon_path))
    blocked_paths = [
        service.data_directory / "config.json",
        service.data_directory / "config.json.bak",
        service.data_directory / "config.json.tmp",
        service.data_directory / "state",
    ]
    return gr.mount_gradio_app(
        app,
        studio,
        path="/",
        favicon_path=str(icon_path),
        allowed_paths=[],
        blocked_paths=[str(path) for path in blocked_paths],
        show_error=False,
        enable_monitoring=False,
        footer_links=[],
        theme=studio_theme(),
        css=STUDIO_CSS,
    )
