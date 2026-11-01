"""Independent, read-only R2 proof verification with owner-local UnityPy==1.25.4.

Reads only fixed .unity proof pairs named by a bounded local verification plan.
Does not read a device, install, download, execute native code or assert FGO acceptance.
"""
import argparse
import hashlib
import json
import re
from importlib.metadata import version
from pathlib import Path

import UnityPy


def unique_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate verification-plan key")
        result[key] = value
    return result


def verify(plan_file: Path) -> dict:
    if version("UnityPy") != "1.25.4":
        raise ValueError("Use pinned UnityPy 1.25.4")
    if plan_file.is_symlink() or not 0 < plan_file.stat().st_size <= 1024 * 1024:
        raise ValueError("Invalid verification plan")
    plan = json.loads(plan_file.read_text(encoding="utf-8"), object_pairs_hook=unique_keys)
    if set(plan) != {"type", "schema_version", "candidate_sha256", "pairs"} or plan["type"] != "fgogotran-native-offline-verification" or plan["schema_version"] != 1:
        raise ValueError("Unsupported verification plan")
    if not re.fullmatch(r"[0-9a-f]{64}", plan["candidate_sha256"]) or len(plan["pairs"]) > 16:
        raise ValueError("Invalid verification bounds")
    reports = []
    seen = set()
    for pair in plan["pairs"]:
        if set(pair) != {"asset_filename", "original", "rebuilt", "original_sha256", "rebuilt_sha256", "changed_scripts"}:
            raise ValueError("Unexpected proof metadata")
        if not re.fullmatch(r"[0-9a-f]{40}\.bin", pair["asset_filename"]) or pair["asset_filename"] in seen:
            raise ValueError("Duplicate/invalid proof asset")
        seen.add(pair["asset_filename"])
        environments = []
        for key, suffix in (("original", "original"), ("rebuilt", "rebuilt")):
            relative = pair[key]
            if not re.fullmatch(r"(?:CN|TW|NA)/" + re.escape(pair["asset_filename"]) + r"\." + suffix + r"\.unity", relative):
                raise ValueError("Unconfined proof path")
            candidate = plan_file.parent / relative
            resolved = candidate.resolve(strict=True)
            if candidate.is_symlink() or not resolved.is_relative_to(plan_file.parent.resolve()) or not 0 < resolved.stat().st_size <= 64 * 1024 * 1024:
                raise ValueError("Linked/out-of-range proof input")
            data = resolved.read_bytes()
            if hashlib.sha256(data).hexdigest() != pair[key + "_sha256"]:
                raise ValueError("Proof bytes differ from plan")
            environments.append(UnityPy.load(data))
        before, after = environments
        a = {obj.path_id: obj for obj in before.objects}
        b = {obj.path_id: obj for obj in after.objects}
        if a.keys() != b.keys():
            raise ValueError("Object identities changed")
        changed = []
        for path_id, obj in a.items():
            other = b[path_id]
            if obj.type != other.type:
                raise ValueError("Object type changed")
            if obj.get_raw_data() != other.get_raw_data():
                if obj.type.name != "TextAsset":
                    raise ValueError("Non-script object changed")
                first = obj.parse_as_object()
                second = other.parse_as_object()
                if first.m_Name != second.m_Name:
                    raise ValueError("Script identity changed")
                changed.append(first.m_Name)
        expected = pair["changed_scripts"]
        if not 0 < len(expected) <= 256 or len(set(expected)) != len(expected) or sorted(changed) != sorted(expected):
            raise ValueError("Changed scripts differ from complete alignment plan")
        mapping = lambda env: {name: obj.path_id for name, obj in env.container.items()}
        if mapping(before) != mapping(after):
            raise ValueError("Container mappings changed")
        reports.append({"asset": pair["asset_filename"], "objects": len(a), "container_mappings": len(mapping(before)),
                        "changed_scripts": sorted(changed), "all_other_object_bytes_identical": True})
    return {"verifier": "UnityPy 1.25.4", "candidate_sha256": plan["candidate_sha256"], "bundles": reports,
            "result": "OFFLINE_OBJECT_CHECKS_PASSED" if reports else "NO_TRANSLATED_BUNDLES_TO_VERIFY",
            "native_game_playback": "NOT TESTED", "installable": False}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("verification_plan", type=Path)
    print(json.dumps(verify(parser.parse_args().verification_plan), indent=2, ensure_ascii=False))
