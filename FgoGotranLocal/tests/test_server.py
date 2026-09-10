from pathlib import Path

from fastapi.testclient import TestClient

from fgogotran_local.application import create_application
from fgogotran_local.service import LocalTranslationService, default_data_directory, resolve_data_directory


def test_absolute_data_home_is_used_and_relative_value_is_rejected():
    assert resolve_data_directory(r"D:\FgoGotranLocalData", r"C:\fallback") == Path(r"D:\FgoGotranLocalData")
    assert resolve_data_directory("relative-path", r"C:\fallback") == Path(r"C:\fallback")


def test_default_data_directory_is_next_to_project_source(tmp_path: Path):
    package_file = tmp_path / "src" / "fgogotran_local" / "service.py"

    assert default_data_directory(str(package_file)) == tmp_path / "user_data"


def test_control_server_identifies_itself_and_rejects_unmarked_mutations(tmp_path: Path):
    service = LocalTranslationService(tmp_path)
    application = create_application(service, mount_ui=False, allow_test_host=True)

    with TestClient(application) as client:
        health = client.get("/healthz")
        status = client.get("/api/status")
        blocked = client.post("/api/actions/start", json={})

    assert health.status_code == 200
    assert health.json() == {"ok": True, "service": "fgogotran-local"}
    assert status.json()["state"] == "STOPPED"
    assert blocked.status_code == 403
    assert blocked.headers["X-Frame-Options"] == "DENY"


def test_control_server_returns_config_error_for_missing_runtime(tmp_path: Path):
    service = LocalTranslationService(tmp_path)
    application = create_application(service, mount_ui=False, allow_test_host=True)

    with TestClient(application) as client:
        response = client.post(
            "/api/actions/start",
            headers={"X-FGO-Control": "1"},
            json={},
        )

    assert response.status_code == 400
    assert "llama-server" in response.json()["error"]


def test_control_server_rejects_non_object_json(tmp_path: Path):
    service = LocalTranslationService(tmp_path)
    application = create_application(service, mount_ui=False, allow_test_host=True)

    with TestClient(application) as client:
        response = client.put(
            "/api/config",
            headers={"X-FGO-Control": "1"},
            json=[],
        )

    assert response.status_code == 400
    assert response.json()["error"] == "请求内容必须是 JSON 对象。"
