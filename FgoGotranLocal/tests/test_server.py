import json
from pathlib import Path

from fastapi.testclient import TestClient

from fgogotran_local.application import create_application
from fgogotran_local.config_models import default_config
from fgogotran_local.log_store import LogStore
from fgogotran_local.privacy import MASKED_PATH, MASKED_VALUE, mask_endpoint, redact_sensitive_payload
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
        masked_key = client.get("/api/key")
        secret = service.api_key()
        blocked = client.post("/api/actions/start", json={})

    assert health.status_code == 200
    assert health.json() == {"ok": True, "service": "fgogotran-local"}
    assert status.json()["state"] == "STOPPED"
    assert "apiKey" not in masked_key.json()
    assert secret not in masked_key.text
    assert masked_key.json()["apiKeyMasked"] == "••••••••••••"
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


def test_control_status_masks_lan_address_and_endpoint(tmp_path: Path):
    service = LocalTranslationService(tmp_path)

    async def fake_system_snapshot():
        return {
            "gpu": None,
            "memory": {"usedMiB": 1024, "totalMiB": 2048},
            "lanAddresses": ["192.168.50.23"],
        }

    service.monitor.snapshot = fake_system_snapshot
    application = create_application(service, mount_ui=False, allow_test_host=True)

    with TestClient(application) as client:
        response = client.get("/api/status")

    assert response.status_code == 200
    assert "192.168.50.23" not in response.text
    assert response.json()["connection"]["endpoint"].endswith(":18080/v1/chat/completions")


def test_read_only_config_and_status_mask_local_paths(tmp_path: Path):
    config = default_config().model_dump(by_alias=True)
    config["llamaServerPath"] = r"C:\Users\Alice\llama.cpp\llama-server.exe"
    config["modelsDirectory"] = r"D:\Private Models"
    config["profiles"][config["activeProfile"]]["modelPath"] = r"D:\Private Models\fgo.gguf"
    (tmp_path / "config.json").write_text(json.dumps(config), encoding="utf-8")
    service = LocalTranslationService(tmp_path)
    application = create_application(service, mount_ui=False, allow_test_host=True)

    with TestClient(application) as client:
        config_response = client.get("/api/config")
        status_response = client.get("/api/status")

    assert "Alice" not in config_response.text
    assert "Private Models" not in config_response.text
    assert "Alice" not in status_response.text
    assert "Private Models" not in status_response.text
    assert config_response.json()["config"]["llamaServerPath"] == MASKED_PATH


def test_sensitive_payload_masks_network_and_paths_but_keeps_connection_settings():
    source = {
        "profile": {
            "modelAlias": "fgo-local-qwen",
            "modelPath": r"C:\Users\Alice\Models\fgo.gguf",
            "port": 18080,
        },
        "connection": {
            "endpoint": "http://192.168.10.24:18080/v1/chat/completions",
            "lanAddress": "192.168.10.24",
        },
    }

    safe = redact_sensitive_payload(source)

    assert safe["profile"]["modelAlias"] == "fgo-local-qwen"
    assert safe["profile"]["port"] == 18080
    assert safe["profile"]["modelPath"] == MASKED_PATH
    assert "192.168.10.24" not in str(safe)
    assert MASKED_VALUE in safe["connection"]["endpoint"]


def test_endpoint_mask_keeps_port_and_openai_path():
    masked = mask_endpoint("http://10.0.0.18:18080/v1/chat/completions")

    assert masked == f"http://{MASKED_VALUE}:18080/v1/chat/completions"


def test_logs_always_remove_key_and_only_reveal_network_and_paths_on_request(tmp_path: Path):
    secret = "fgo_test_secret_123456789"
    logs = LogStore(tmp_path, lambda: secret)
    private_path = r"C:\Users\Alice\Private Models\fgo.gguf"
    logs.add("INFO", f"Endpoint http://192.168.1.9:18080 model {private_path} key {secret}")

    masked = logs.formatted()
    revealed = logs.formatted(include_sensitive=True)

    assert "192.168.1.9" not in masked
    assert "Alice" not in masked
    assert "Private Models" not in masked
    assert secret not in masked
    assert "192.168.1.9" in revealed
    assert private_path in revealed
    assert secret not in revealed
    assert "[API_KEY_REDACTED]" in revealed
