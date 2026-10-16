"""Independent offline verification using pinned UnityPy; never accesses a device.

Install UnityPy==1.25.4 in a disposable/local environment, not in the Android APK.
Reads only original.unity and patched.unity under the ignored proof directory.
"""
import argparse
import collections
import json
from pathlib import Path

import UnityPy
from importlib.metadata import version


def verify(directory: Path) -> dict:
    if version("UnityPy") != "1.25.4":
        raise ValueError("Use the reviewed/pinned UnityPy 1.25.4 verifier")
    original = UnityPy.load(str(directory / "original.unity"))
    patched = UnityPy.load(str(directory / "patched.unity"))
    no_op = UnityPy.load(str(directory / "no-op.unity"))
    before = {obj.path_id: obj for obj in original.objects}
    after = {obj.path_id: obj for obj in patched.objects}
    rebuilt = {obj.path_id: obj for obj in no_op.objects}
    if before.keys() != after.keys():
        raise ValueError("Native object identities changed")
    if before.keys() != rebuilt.keys() or any(obj.type != rebuilt[key].type or obj.get_raw_data() != rebuilt[key].get_raw_data() for key, obj in before.items()):
        raise ValueError("Unchanged native reconstruction changed object bytes")
    changed = []
    for path_id, obj in before.items():
        other = after[path_id]
        if obj.type != other.type:
            raise ValueError("Native object class changed")
        if obj.get_raw_data() != other.get_raw_data():
            if obj.type.name != "TextAsset":
                raise ValueError("A non-script object changed")
            first = obj.parse_as_object()
            second = other.parse_as_object()
            if first.m_Name != second.m_Name:
                raise ValueError("Script name changed")
            changed.append({"path_id": path_id, "script": first.m_Name})
    if len(changed) != 1 or changed[0]["script"] != "0100000011":
        raise ValueError("Expected exactly the selected script object to change")
    mapping_before = {name: obj.path_id for name, obj in original.container.items()}
    mapping_after = {name: obj.path_id for name, obj in patched.container.items()}
    if mapping_before != mapping_after:
        raise ValueError("AssetBundle script path mapping changed")
    if mapping_before != {name: obj.path_id for name, obj in no_op.container.items()}:
        raise ValueError("Unchanged native reconstruction changed container mappings")
    return {
        "verifier": "UnityPy 1.25.4 (desktop only)",
        "objects": len(before),
        "classes": dict(collections.Counter(obj.type.name for obj in before.values())),
        "container_mappings_preserved": len(mapping_before),
        "changed_objects": changed,
        "all_other_object_bytes_identical": True,
        "unchanged_reconstruction_all_object_bytes_identical": True,
        "native_game_playback": "NOT TESTED",
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("proof_directory", type=Path)
    args = parser.parse_args()
    print(json.dumps(verify(args.proof_directory), indent=2))
