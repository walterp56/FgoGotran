from __future__ import annotations

from collections.abc import Iterable

from fastapi import Request
from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.responses import JSONResponse, Response

from .errors import ConfigError


JSON_BODY_LIMIT = 64 * 1024


class LocalControlSecurityMiddleware(BaseHTTPMiddleware):
    def __init__(self, app, allowed_hosts: Iterable[str]) -> None:
        super().__init__(app)
        self.allowed_hosts = {host.casefold() for host in allowed_hosts}

    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        hostname = (request.url.hostname or "").casefold()
        if hostname not in self.allowed_hosts:
            response = JSONResponse({"error": "无效的主机地址。"}, status_code=400)
            apply_security_headers(response)
            return response
        response = await call_next(request)
        apply_security_headers(response)
        return response


def apply_security_headers(response: Response) -> None:
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Referrer-Policy"] = "no-referrer"
    response.headers["Permissions-Policy"] = "camera=(), microphone=(), geolocation=()"
    response.headers["Cross-Origin-Resource-Policy"] = "same-origin"
    response.headers["Cache-Control"] = "no-store"


async def require_safe_mutation(request: Request) -> dict:
    if request.headers.get("X-FGO-Control") != "1":
        raise ConfigError("缺少本地控制 Header。", 403)
    content_type = request.headers.get("Content-Type", "").casefold()
    if not content_type.startswith("application/json"):
        raise ConfigError("Content-Type 必须是 JSON。", 415)
    origin = request.headers.get("Origin")
    if origin:
        allowed_origins = {
            f"http://127.0.0.1:{request.url.port or 80}",
            f"http://localhost:{request.url.port or 80}",
        }
        if origin not in allowed_origins:
            raise ConfigError("不允许跨来源控制请求。", 403)
    content_length = request.headers.get("Content-Length")
    if content_length:
        try:
            if int(content_length) > JSON_BODY_LIMIT:
                raise ConfigError("请求内容过大。", 413)
        except ValueError as error:
            raise ConfigError("Content-Length 无效。") from error
    body = await request.body()
    if len(body) > JSON_BODY_LIMIT:
        raise ConfigError("请求内容过大。", 413)
    if not body:
        return {}
    try:
        value = await request.json()
    except ValueError as error:
        raise ConfigError("请求内容必须是有效 JSON。") from error
    if not isinstance(value, dict):
        raise ConfigError("请求内容必须是 JSON 对象。")
    return value
