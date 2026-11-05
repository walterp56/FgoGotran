"""Software version, independent of configuration/platform schema versions."""
from __future__ import annotations

import json
from pathlib import Path

_VERSION = json.loads(Path(__file__).with_name("version.json").read_text(encoding="utf-8"))
VERSION_CODE: int = _VERSION["versionCode"]
VERSION_NAME: str = _VERSION["versionName"]
__version__ = VERSION_NAME.removeprefix("v")


def version_info() -> dict:
    return {"versionCode": VERSION_CODE, "versionName": VERSION_NAME}
