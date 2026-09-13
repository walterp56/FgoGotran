from __future__ import annotations

from fastapi import APIRouter, Request

from .errors import ConfigError
from .privacy import redact_sensitive_payload
from .security import require_safe_mutation
from .service import LocalTranslationService


def create_control_router(service: LocalTranslationService) -> APIRouter:
    router = APIRouter()

    @router.get("/healthz", include_in_schema=False)
    async def healthz() -> dict:
        return {"ok": True, "service": "fgogotran-local"}

    @router.get("/api/status", include_in_schema=False)
    async def status() -> dict:
        return await service.status()

    @router.get("/api/config", include_in_schema=False)
    async def config() -> dict:
        return {"config": service.public_config()}

    @router.get("/api/key", include_in_schema=False)
    async def key() -> dict:
        return {"apiKeyMasked": service.public_config()["apiKeyMasked"]}

    @router.get("/api/models", include_in_schema=False)
    async def models() -> dict:
        return {"models": await service.public_models()}

    @router.get("/api/logs", include_in_schema=False)
    async def logs(after: int = 0) -> dict:
        return service.log_entries(after)

    @router.put("/api/config", include_in_schema=False)
    async def update_config(request: Request) -> dict:
        body = await require_safe_mutation(request)
        candidate = body.get("config", body)
        if not isinstance(candidate, dict):
            raise ConfigError("config 必须是 JSON 对象。")
        revision = body.get("revision") or candidate.get("revision")
        return {"config": await service.update_config(candidate, revision)}

    @router.post("/api/actions/start", include_in_schema=False)
    async def start(request: Request) -> dict:
        await require_safe_mutation(request)
        return {"status": redact_sensitive_payload(await service.start())}

    @router.post("/api/actions/stop", include_in_schema=False)
    async def stop(request: Request) -> dict:
        await require_safe_mutation(request)
        return {"status": redact_sensitive_payload(await service.stop())}

    @router.post("/api/actions/restart", include_in_schema=False)
    async def restart(request: Request) -> dict:
        await require_safe_mutation(request)
        return {"status": redact_sensitive_payload(await service.restart())}

    @router.post("/api/actions/test", include_in_schema=False)
    async def test(request: Request) -> dict:
        await require_safe_mutation(request)
        return {"result": await service.test_compatibility()}

    @router.post("/api/actions/rotate-key", include_in_schema=False)
    async def rotate_key(request: Request) -> dict:
        await require_safe_mutation(request)
        return await service.rotate_api_key()

    @router.delete("/api/logs", include_in_schema=False)
    async def clear_logs(request: Request) -> dict:
        await require_safe_mutation(request)
        return service.clear_logs()

    return router
