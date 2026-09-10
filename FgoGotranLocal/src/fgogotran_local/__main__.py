from __future__ import annotations

import asyncio
import os
import threading
import time
import urllib.request
import webbrowser

import uvicorn

from .application import create_application
from .service import LocalTranslationService, configured_data_directory


CONTROL_HOST = "127.0.0.1"
DEFAULT_CONTROL_PORT = 18081


def main() -> None:
    if os.name == "nt":
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    port = _parse_port(os.environ.get("FGO_LOCAL_CONTROL_PORT"), DEFAULT_CONTROL_PORT)
    service = LocalTranslationService(configured_data_directory(__file__))
    application = create_application(service)
    local_url = f"http://{CONTROL_HOST}:{port}"
    print(f"FgoGotran Local：{local_url}", flush=True)
    if os.environ.get("FGO_LOCAL_OPEN_BROWSER") == "1":
        threading.Thread(
            target=_open_browser_when_ready,
            args=(f"{local_url}/healthz", local_url),
            daemon=True,
        ).start()
    uvicorn.run(
        application,
        host=CONTROL_HOST,
        port=port,
        access_log=False,
        log_level="warning",
    )


def _parse_port(value: str | None, fallback: int) -> int:
    try:
        parsed = int(value or "")
    except ValueError:
        return fallback
    return parsed if 1024 <= parsed <= 65535 else fallback


def _open_browser_when_ready(health_url: str, local_url: str) -> None:
    for _ in range(80):
        try:
            with urllib.request.urlopen(health_url, timeout=0.5) as response:
                if response.status == 200:
                    webbrowser.open(local_url)
                    return
        except OSError:
            time.sleep(0.25)


if __name__ == "__main__":
    main()
