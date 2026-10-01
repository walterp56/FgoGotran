"""Inventory RGB tags in public JP FGO story scripts without storing story text.

Atlas Academy mirrors the game's script assets. This is game-data evidence, not
an official published palette specification. The JSON contains only colours,
counts, source URLs, and scan coverage; never dialogue or spoilers.
"""

from __future__ import annotations

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import hashlib
import http.client
import json
from pathlib import Path
import re
import threading
from urllib.request import Request, urlopen


INDEX_URL = "https://api.atlasacademy.io/export/JP/nice_war.json"
SCRIPT_HOST = "static.atlasacademy.io"
RGB_TAG = re.compile(rb"\[([0-9a-fA-F]{6})\]")
CLIENT = threading.local()


def script_inventory(wars: list[dict]) -> dict[str, set[int]]:
    inventory: dict[str, set[int]] = {}
    for war in wars:
        links = [war.get("startScript")]
        for spot in war.get("spots", []):
            for quest in spot.get("quests", []):
                for phase in quest.get("phaseScripts", []):
                    links.extend(phase.get("scripts", []))
        for link in links:
            url = (link or {}).get("script", "")
            if url.startswith(f"https://{SCRIPT_HOST}/JP/Script/"):
                inventory.setdefault(url, set()).add(war["id"])
    return inventory


def scan_script(url: str) -> tuple[str, Counter, str | None]:
    path = url.removeprefix(f"https://{SCRIPT_HOST}")
    error = None
    for _ in range(2):
        try:
            connection = getattr(CLIENT, "connection", None)
            if connection is None:
                connection = http.client.HTTPSConnection(SCRIPT_HOST, timeout=20)
                CLIENT.connection = connection
            connection.request("GET", path, headers={"User-Agent": "FgoGotran-colour-research/1"})
            response = connection.getresponse()
            data = response.read()
            if response.status != 200:
                raise OSError(f"HTTP {response.status}")
            return url, Counter(tag.decode("ascii").upper() for tag in RGB_TAG.findall(data)), None
        except (OSError, http.client.HTTPException) as exc:
            error = str(exc)
            if getattr(CLIENT, "connection", None) is not None:
                CLIENT.connection.close()
            CLIENT.connection = None
    return url, Counter(), error


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--workers", type=int, default=4, choices=range(1, 5))
    parser.add_argument("--max-scripts", type=int, default=0, help="0 scans the complete war index")
    args = parser.parse_args()
    request = Request(INDEX_URL, headers={"User-Agent": "FgoGotran-colour-research/1"})
    with urlopen(request, timeout=60) as response:
        index_bytes = response.read()
    wars = json.loads(index_bytes)
    inventory = script_inventory(wars)
    urls = sorted(inventory)
    if args.max_scripts > 0:
        # Even spacing retains coverage across the entire index for bounded scans.
        size = min(args.max_scripts, len(urls))
        urls = [urls[i * len(urls) // size] for i in range(size)]
    total_tags = Counter()
    script_counts = Counter()
    examples: dict[str, list[dict]] = {}
    errors = []
    completed = 0
    print(f"JP index: {len(wars)} wars, {len(inventory)} unique scripts; scanning {len(urls)}", flush=True)
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = [pool.submit(scan_script, url) for url in urls]
        for future in as_completed(futures):
            url, tags, error = future.result()
            completed += 1
            if error:
                errors.append({"url": url, "error": error})
            else:
                total_tags.update(tags)
                script_counts.update(tags.keys())
                for colour in tags:
                    refs = examples.setdefault(colour, [])
                    if len(refs) < 3:
                        refs.append({"url": url, "war_ids": sorted(inventory[url])})
            if completed % 250 == 0:
                print(f"Scanned {completed}/{len(urls)}; RGB codes={len(total_tags)}; errors={len(errors)}", flush=True)
    report = {
        "scanned_at_utc": datetime.now(timezone.utc).isoformat(),
        "source_index": INDEX_URL,
        "index_sha256": hashlib.sha256(index_bytes).hexdigest(),
        "region": "JP",
        "scope": "Scripts linked by the nice_war export: main story, events, interludes and related quests. Unlinked assets and separate Valentine gift scripts are not exhaustive here.",
        "war_count": len(wars),
        "indexed_script_count": len(inventory),
        "requested_script_count": len(urls),
        "successful_script_count": len(urls) - len(errors),
        "errors": sorted(errors, key=lambda item: item["url"]),
        "colours": [
            {
                "hex": f"#{colour}",
                "rgb": [int(colour[i:i + 2], 16) for i in (0, 2, 4)],
                "tag_count": count,
                "script_count": script_counts[colour],
                "examples": sorted(examples[colour], key=lambda item: item["url"]),
            }
            for colour, count in sorted(total_tags.items(), key=lambda item: (-item[1], item[0]))
        ],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"successful": report["successful_script_count"], "errors": len(errors), "colours": [{"hex": item["hex"], "tags": item["tag_count"]} for item in report["colours"]]}), flush=True)


if __name__ == "__main__":
    main()
