"""
Fetch FGO terminology data from Atlas Academy API.

Produces a JSON file with JP→CN term mappings for:
- Servants (name + noble phantasms + skills)
- Craft Essences
- Items / Materials
"""

import json
import requests
import sys
import time

ATLAS_BASE = "https://api.atlasacademy.io"
HEADERS = {"Accept": "application/json"}

def fetch_json(endpoint: str, retries: int = 3) -> dict | list:
    url = f"{ATLAS_BASE}{endpoint}"
    for attempt in range(retries):
        try:
            resp = requests.get(url, headers=HEADERS, timeout=30)
            resp.raise_for_status()
            return resp.json()
        except Exception as e:
            if attempt == retries - 1:
                print(f"Failed to fetch {url}: {e}", file=sys.stderr)
                return [] if "all" in endpoint else {}
            time.sleep(1)
    return []


def main():
    terms = []  # list of {jp_name, cn_name, category, aliases}

    print("Fetching CN region servants...")
    cn_servants_raw = fetch_json("/export/CN/servant/all.json")
    cn_servants = {}
    if isinstance(cn_servants_raw, list):
        for s in cn_servants_raw:
            sid = s.get("collectionNo") or s.get("id")
            cn_servants[sid] = s.get("name", "")

    print(f"  -> {len(cn_servants)} CN servant names loaded")

    print("Fetching JP region servants...")
    jp_servants_raw = fetch_json("/export/JP/servant/all.json")
    jp_servants = []
    if isinstance(jp_servants_raw, list):
        jp_servants = jp_servants_raw

    print(f"  -> {len(jp_servants)} JP servants loaded")

    for s in jp_servants:
        sid = s.get("collectionNo") or s.get("id")
        jp_name = s.get("name", "")
        cn_name = cn_servants.get(sid, "")
        if jp_name and cn_name and jp_name != cn_name:
            aliases = []
            # Add ruby-free name if present
            ruby = s.get("ruby", "")
            if ruby and ruby != jp_name:
                aliases.append(ruby)
            terms.append({
                "jp_name": jp_name,
                "cn_name": cn_name,
                "category": "servant",
                "aliases": json.dumps(aliases, ensure_ascii=False)
            })

        # Noble Phantasms
        for np in s.get("noblePhantasms", []):
            np_jp = np.get("originalName", "") or np.get("name", "")
            np_cn = np.get("name", "")
            if np_jp and np_cn and np_jp != np_cn:
                terms.append({
                    "jp_name": np_jp,
                    "cn_name": np_cn,
                    "category": "noble_phantasm",
                    "aliases": "[]"
                })

        # Skills
        for sk in s.get("skills", []):
            sk_jp = sk.get("originalName", "") or sk.get("name", "")
            sk_cn = sk.get("name", "")
            if sk_jp and sk_cn and sk_jp != sk_cn:
                terms.append({
                    "jp_name": sk_jp,
                    "cn_name": sk_cn,
                    "category": "skill",
                    "aliases": "[]"
                })

    print(f"  -> {len(terms)} terms extracted from servants")

    print("Fetching Craft Essences...")
    ce_raw = fetch_json("/export/JP/nice_CE.json")
    if isinstance(ce_raw, list):
        for ce in ce_raw:
            ce_jp = ce.get("originalName", "") or ce.get("name", "")
            ce_cn = ce.get("name", "")
            if ce_jp and ce_cn and ce_jp != ce_cn:
                terms.append({
                    "jp_name": ce_jp,
                    "cn_name": ce_cn,
                    "category": "craft_essence",
                    "aliases": "[]"
                })
    print(f"  -> {len(terms)} total terms after Craft Essences")

    print("Fetching Items...")
    items_raw = fetch_json("/export/JP/nice_item.json")
    if isinstance(items_raw, list):
        for item in items_raw:
            item_jp = item.get("originalName", "") or item.get("name", "")
            item_cn = item.get("name", "")
            if item_jp and item_cn and item_jp != item_cn:
                terms.append({
                    "jp_name": item_jp,
                    "cn_name": item_cn,
                    "category": "item",
                    "aliases": "[]"
                })
    print(f"  -> {len(terms)} total terms after Items")

    # Add common FGO gameplay terms (hardcoded)
    common_terms = [
        {"jp_name": "マスター", "cn_name": "御主", "category": "game_term", "aliases": "[]"},
        {"jp_name": "サーヴァント", "cn_name": "从者", "category": "game_term", "aliases": "[]"},
        {"jp_name": "宝具", "cn_name": "宝具", "category": "game_term", "aliases": "[]"},
        {"jp_name": "令呪", "cn_name": "令咒", "category": "game_term", "aliases": "[]"},
        {"jp_name": "聖晶石", "cn_name": "圣晶石", "category": "game_term", "aliases": "[]"},
        {"jp_name": "カルデア", "cn_name": "迦勒底", "category": "location", "aliases": "[]"},
        {"jp_name": "人理", "cn_name": "人理", "category": "game_term", "aliases": "[]"},
        {"jp_name": "特異点", "cn_name": "特异点", "category": "game_term", "aliases": "[]"},
        {"jp_name": "異聞帯", "cn_name": "异闻带", "category": "game_term", "aliases": "[]"},
        {"jp_name": "霊基", "cn_name": "灵基", "category": "game_term", "aliases": "[]"},
        {"jp_name": "概念礼装", "cn_name": "概念礼装", "category": "game_term", "aliases": "[]"},
        {"jp_name": "絆", "cn_name": "羁绊", "category": "game_term", "aliases": "[]"},
        {"jp_name": "魔術回路", "cn_name": "魔术回路", "category": "game_term", "aliases": "[]"},
        {"jp_name": "聖杯", "cn_name": "圣杯", "category": "game_term", "aliases": "[]"},
        {"jp_name": "聖杯戦争", "cn_name": "圣杯战争", "category": "game_term", "aliases": "[]"},
        {"jp_name": "クラス", "cn_name": "职阶", "category": "game_term", "aliases": "[]"},
        {"jp_name": "セイバー", "cn_name": "剑士", "category": "class", "aliases": "[]"},
        {"jp_name": "アーチャー", "cn_name": "弓兵", "category": "class", "aliases": "[]"},
        {"jp_name": "ランサー", "cn_name": "枪兵", "category": "class", "aliases": "[]"},
        {"jp_name": "ライダー", "cn_name": "骑兵", "category": "class", "aliases": "[]"},
        {"jp_name": "キャスター", "cn_name": "魔术师", "category": "class", "aliases": "[]"},
        {"jp_name": "アサシン", "cn_name": "暗杀者", "category": "class", "aliases": "[]"},
        {"jp_name": "バーサーカー", "cn_name": "狂战士", "category": "class", "aliases": "[]"},
        {"jp_name": "ルーラー", "cn_name": "裁定者", "category": "class", "aliases": "[]"},
        {"jp_name": "アヴェンジャー", "cn_name": "复仇者", "category": "class", "aliases": "[]"},
        {"jp_name": "アルターエゴ", "cn_name": "他人格", "category": "class", "aliases": "[]"},
        {"jp_name": "ムーンキャンサー", "cn_name": "月之癌", "category": "class", "aliases": "[]"},
        {"jp_name": "フォーリナー", "cn_name": "降临者", "category": "class", "aliases": "[]"},
        {"jp_name": "プリテンダー", "cn_name": "伪装者", "category": "class", "aliases": "[]"},
        {"jp_name": "シールダー", "cn_name": "盾兵", "category": "class", "aliases": "[]"},
    ]
    terms.extend(common_terms)

    # Deduplicate by jp_name, keeping first occurrence
    seen = set()
    unique_terms = []
    for t in terms:
        if t["jp_name"] not in seen:
            seen.add(t["jp_name"])
            unique_terms.append(t)

    print(f"\nTotal unique terms: {len(unique_terms)}")

    output_path = "fgo_terms.json"
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(unique_terms, f, ensure_ascii=False, indent=2)
    print(f"Saved to {output_path}")


if __name__ == "__main__":
    main()
