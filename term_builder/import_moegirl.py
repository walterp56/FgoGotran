"""Import high-confidence FGO/TYPE-MOON names and terms from Moegirl.

The Moegirl pages are useful as supplemental references, but they are not as
structured as Atlas/FGO wiki data. This importer is intentionally conservative:

* FGO navbox organization/person links can add missing character_name rows when
  the linked page exposes a Japanese "本名".
* TYPE-MOON navbox item/background links can add missing term rows when the page
  exposes a Japanese name.
* Music, media, works, and broad non-FGO character sections are skipped.

Run from repo root:
  python term_builder/import_moegirl.py
  python term_builder/import_moegirl.py --write
"""

from __future__ import annotations

import argparse
import csv
import re
import sys
import time
import unicodedata
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable
from urllib.parse import urljoin
from urllib.request import Request, urlopen

from lxml import html


BASE_URL = "https://zh.moegirl.org.cn"
HEADERS = {"User-Agent": "fgoGotran-term-builder/1.0"}
ROOT = Path(__file__).resolve().parent
CHARACTER_TSV = ROOT / "character_names.tsv"
TERM_TSV = ROOT / "term.tsv"

SEED_URLS = [
    "https://zh.moegirl.org.cn/Fate/Grand_Order",
    "https://zh.moegirl.org.cn/%E7%89%B9%E5%BC%82%E7%82%B9%28Fate%29",
]

SKIP_LINK_TEXT = {
    "查",
    "论",
    "编",
    "更多",
    "Fate/Grand Order",
    "TYPE-MOON 世界",
}

FGO_CHARACTER_HINTS = {
    "组 织",
    "人物",
    "迦勒底",
    "隐匿者",
    "异星",
    "魔神柱",
    "魔兽赫",
    "空想树",
    "英灵剑豪",
    "奥林波斯诸神",
    "八将神",
    "妖精国的灾厄",
    "拟似东京试炼",
    "人类恶",
    "主线剧情记录",
    "主线物语记录",
    "活动任务记录",
    "与从者的记录",
}

FGO_TERM_HINTS = {
    "组 织",
}

FGO_GROUP_LINKS = {
    "迦勒底",
    "隐匿者",
    "异星",
    "异星的使徒",
    "魔神柱",
    "魔兽赫",
    "空想树",
    "英灵剑豪",
    "奥林波斯诸神",
    "八将神",
    "妖精国的灾厄",
    "拟似东京试炼",
    "人类恶",
}

TYPE_MOON_TERM_HINTS = {
    "物品",
    "背景",
    "术语",
    "登场组织",
    "地点",
    "宝具",
    "魔术礼装",
    "概念武装",
    "固有结界",
    "其他",
}

SKIP_ROW_HINTS = {
    "从 者",
    "音 乐",
    "媒 介",
    "官方衍生",
    "音乐",
    "作品",
    "创作",
    "社区媒介",
    "创作人员",
}

FIELD_LABELS = {
    "本名",
    "别号",
    "用语名称",
    "其他表述",
    "用语出处",
    "相关条目",
    "性别",
    "发色",
    "瞳色",
    "声优",
    "萌点",
    "出身地区",
    "所属团体",
    "亲属或相关人",
    "基本资料",
    "基本信息",
}

JP_NAME_LABELS = ("本名", "原名", "日文名", "日语名", "日文名称")
TERM_NAME_LABELS = ("日文名", "日语名", "日文名称", "原名", "本名")


@dataclass(frozen=True)
class CandidateLink:
    title: str
    url: str
    kind: str
    category: str


@dataclass
class ExtractedPage:
    title: str
    jp_names: list[str]
    aliases: list[str]


def fetch_html(url: str, retries: int = 3) -> html.HtmlElement | None:
    for attempt in range(1, retries + 1):
        try:
            request = Request(url, headers=HEADERS)
            with urlopen(request, timeout=45) as response:
                data = response.read().decode("utf-8", "replace")
            return html.fromstring(data)
        except Exception as exc:  # noqa: BLE001 - network/site failures should skip.
            if attempt == retries:
                print(f"warning: failed to fetch {url}: {exc}", file=sys.stderr)
                return None
            time.sleep(attempt)
    return None


def visible_text(element: html.HtmlElement) -> str:
    parts = element.xpath(".//*[not(self::script or self::style)]/text()|./text()")
    return clean_space(" ".join(parts))


def clean_space(value: str) -> str:
    return re.sub(r"\s+", " ", value or "").strip()


def has_any(text: str, needles: Iterable[str]) -> bool:
    return any(needle in text for needle in needles)


def collect_links(seed_urls: list[str]) -> list[CandidateLink]:
    links: dict[str, CandidateLink] = {}
    for seed_url in seed_urls:
        doc = fetch_html(seed_url)
        if doc is None:
            continue
        for table in doc.xpath('//table[contains(concat(" ", normalize-space(@class), " "), " navbox ")]'):
            nav_text = visible_text(table)
            is_type_moon = "TYPE-MOON" in nav_text
            section = ""
            for row in table.xpath(".//tr"):
                # Container rows include the whole nested navbox and are too broad.
                if row.xpath(".//tr"):
                    continue
                row_text = visible_text(row)
                if not row_text:
                    continue
                if is_type_moon:
                    if "☽ 物品" in row_text or "☽ 背景" in row_text:
                        section = "typemoon_term"
                        continue
                    if "☽ " in row_text:
                        section = "skip"
                        continue
                    kind = "term" if section == "typemoon_term" else ""
                    category = infer_term_category(row_text)
                else:
                    if "─── 组 织 ───" in row_text or "─── 人 物 ───" in row_text:
                        section = "fgo_character"
                        continue
                    if "─── 从 者 ───" in row_text or "─── 音 乐 ───" in row_text or "─── 媒 介 ───" in row_text:
                        section = "skip"
                        continue
                    kind = "character" if section == "fgo_character" else ""
                    category = "game_term"
                if not kind:
                    continue
                for anchor in row.xpath(".//a[@href]"):
                    title = clean_space(anchor.text_content())
                    href = anchor.get("href") or ""
                    if not should_consider_link(title, href):
                        continue
                    url = urljoin(BASE_URL, href)
                    # Red links are edit URLs; skip them.
                    if "action=edit" in url or "redlink=1" in url:
                        continue
                    link_kind = "term" if title in FGO_GROUP_LINKS else kind
                    links.setdefault(url, CandidateLink(title, url, link_kind, category))
    return sorted(links.values(), key=lambda item: (item.kind, item.title))


def infer_term_category(row_text: str) -> str:
    if "宝具" in row_text:
        return "noble_phantasm"
    if "魔术礼装" in row_text or "概念武装" in row_text or "物品" in row_text:
        return "item"
    if "登场组织" in row_text:
        return "organization"
    if "地点" in row_text:
        return "place"
    return "game_term"


def should_consider_link(title: str, href: str) -> bool:
    if not title or title in SKIP_LINK_TEXT:
        return False
    if href.startswith("#") or href.startswith("//"):
        return False
    if href.startswith("http") and "zh.moegirl.org.cn" not in href:
        return False
    if title.startswith("File:") or title.startswith("模板:"):
        return False
    return True


def extract_page(url: str, fallback_title: str, kind: str) -> ExtractedPage | None:
    doc = fetch_html(url)
    if doc is None:
        return None
    title = page_title(doc) or fallback_title
    text_lines = page_lines(doc)
    labels = JP_NAME_LABELS if kind == "character" else TERM_NAME_LABELS
    names = extract_labeled_names(text_lines, labels)
    aliases = extract_aliases(text_lines)
    if not names:
        names = extract_parenthesized_jp(text_lines[:80])
    names = unique([normalize_jp_name(name) for name in names if is_plausible_jp_name(name)])
    aliases = unique([normalize_jp_name(alias) for alias in aliases if is_plausible_alias(alias)])
    if not names:
        return None
    return ExtractedPage(title=title, jp_names=names, aliases=aliases)


def page_title(doc: html.HtmlElement) -> str:
    h1 = doc.xpath('string(//h1[@id="firstHeading"])')
    if h1.strip():
        return clean_space(h1)
    title = doc.xpath("string(//title)")
    return clean_space(title.split(" - 萌娘百科")[0])


def page_lines(doc: html.HtmlElement) -> list[str]:
    lines: list[str] = []
    for text in doc.xpath("//*[not(self::script or self::style)]/text()"):
        line = clean_space(text)
        if line:
            lines.append(line)
    return lines


def extract_labeled_names(lines: list[str], labels: Iterable[str]) -> list[str]:
    names: list[str] = []
    label_set = tuple(labels)
    for index, line in enumerate(lines[:160]):
        matched_label = next((label for label in label_set if line.startswith(label)), "")
        if not matched_label:
            continue
        remainder = clean_space(line[len(matched_label):])
        tokens = [remainder] if remainder else []
        for following in lines[index + 1 : index + 24]:
            if following in FIELD_LABELS or any(following.startswith(label) for label in FIELD_LABELS):
                break
            tokens.append(following)
        field_value = " ".join(tokens)
        names.extend(split_name_candidates(field_value))
    return names


def split_name_candidates(value: str) -> list[str]:
    value = value.strip(" ：:;；、")
    if not value:
        return []
    value = re.sub(r"\[[0-9]+\]", "", value)
    result: list[str] = []
    # Full kanji names with furigana, e.g. 藤丸（ふじまる） 立香（りつか）.
    ruby_pattern = r"(?:[\u3400-\u9fff]+[（(][\u3040-\u30ffー・\s]+[）)]\s*){1,6}"
    for match in re.finditer(ruby_pattern, value):
        result.append(match.group(0))

    # Katakana names, optionally with a known Japanese prefix used by FGO pages.
    kana_pattern = (
        r"(?:魔神|魔獣赫|空想樹|亜種空想樹|贋作空想樹)?"
        r"[\u30a0-\u30ff]"
        r"[\u30a0-\u30ff・ー－=＝ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ0-9A-Za-z]*"
        r"(?:[・ー－=＝][\u30a0-\u30ff0-9A-Za-z]+)*"
    )
    for match in re.finditer(kana_pattern, value):
        result.append(match.group(0))
    return result


def extract_parenthesized_jp(lines: list[str]) -> list[str]:
    names: list[str] = []
    joined = "\n".join(lines)
    for match in re.finditer(r"[（(]([^()（）]{2,80})[）)]", joined):
        value = match.group(1)
        if has_kana(value):
            names.append(value)
    return names


def extract_aliases(lines: list[str]) -> list[str]:
    aliases: list[str] = []
    for index, line in enumerate(lines[:120]):
        if not line.startswith("别号"):
            continue
        candidates = [clean_space(line[2:])]
        candidates.extend(lines[index + 1 : index + 4])
        for candidate in candidates:
            aliases.extend(extract_jp_tokens(candidate))
    return aliases


def extract_jp_tokens(value: str) -> list[str]:
    tokens: list[str] = []
    for match in re.finditer(r"[\u3040-\u30ffA-Za-z0-9・＝=ー－ｰ（）()〔〕\[\]／/]+", value):
        token = match.group(0).strip("()（）[]〔〕,，、")
        if is_plausible_alias(token):
            tokens.append(token)
    return tokens


def normalize_jp_name(value: str) -> str:
    value = unicodedata.normalize("NFKC", value)
    # Remove ruby readings from forms like 藤丸（ふじまる） 立香（りつか）.
    value = re.sub(r"[（(][\u3040-\u30ffー・\s]+[）)]", "", value)
    value = value.replace("·", "・")
    value = clean_space(value)
    value = re.sub(r"\s+", "", value)
    return value.strip("：:;；,，、。")


def is_plausible_jp_name(value: str) -> bool:
    raw_value = value
    value = normalize_jp_name(value)
    if len(value) < 2 or len(value) > 60:
        return False
    if "点击" in value or "加载" in value or "译名" in value or "别名" in value:
        return False
    if re.fullmatch(r"[A-Za-z0-9 ._+-]+", value):
        return False
    if re.fullmatch(r"[\u3040-\u309fー]+", value):
        return False
    # Require kana in the source. Pure CJK rows on CN pages are often Chinese
    # translations, and are safer to curate manually in character_names.tsv.
    return has_kana(raw_value)


def is_plausible_alias(value: str) -> bool:
    value = normalize_jp_name(value)
    if len(value) < 2 or len(value) > 40:
        return False
    return bool(re.search(r"[\u3040-\u30ffA-Za-z]", value))


def has_kana(value: str) -> bool:
    return bool(re.search(r"[\u3040-\u30ff]", value))


def unique(values: Iterable[str]) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        if value and value not in seen:
            seen.add(value)
            result.append(value)
    return result


def load_tsv(path: Path) -> tuple[list[dict[str, str]], list[str]]:
    with path.open("r", encoding="utf-8-sig", newline="") as file:
        reader = csv.DictReader(file, delimiter="\t")
        return list(reader), list(reader.fieldnames or [])


def write_tsv(path: Path, rows: list[dict[str, str]], fieldnames: list[str]) -> None:
    with path.open("w", encoding="utf-8-sig", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=fieldnames, delimiter="\t", lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def merge_aliases(existing: str, additions: Iterable[str], jp_name: str) -> str:
    aliases = [alias.strip() for alias in existing.split(",") if alias.strip()]
    for alias in additions:
        alias = alias.strip()
        if alias and alias != jp_name and alias not in aliases:
            aliases.append(alias)
    return ",".join(aliases)


def append_candidates(
    links: list[CandidateLink],
    limit: int,
    dry_run: bool,
) -> tuple[int, int, int]:
    char_rows, char_fields = load_tsv(CHARACTER_TSV)
    term_rows, term_fields = load_tsv(TERM_TSV)
    char_by_jp = {row["jp_name"]: row for row in char_rows}
    term_by_jp = {row["jp_term"]: row for row in term_rows}

    added_chars = 0
    added_terms = 0
    scanned = 0
    for link in links[:limit]:
        scanned += 1
        extracted = extract_page(link.url, link.title, link.kind)
        if not extracted:
            continue
        for jp_name in extracted.jp_names:
            aliases = unique([compact_name_alias(jp_name), *extracted.aliases])
            if link.kind == "character":
                if jp_name in char_by_jp:
                    row = char_by_jp[jp_name]
                    row["aliases"] = merge_aliases(row.get("aliases", ""), aliases, jp_name)
                    continue
                row = {
                    "jp_name": jp_name,
                    "cn_name": extracted.title,
                    "aliases": merge_aliases("", aliases, jp_name),
                    "type": "npc",
                }
                char_by_jp[jp_name] = row
                char_rows.append(row)
                added_chars += 1
                print(f"character + {jp_name} => {extracted.title}")
            else:
                if jp_name in term_by_jp:
                    row = term_by_jp[jp_name]
                    row["aliases"] = merge_aliases(row.get("aliases", ""), aliases, jp_name)
                    continue
                row = {
                    "jp_term": jp_name,
                    "cn_term": extracted.title,
                    "category": link.category,
                    "aliases": merge_aliases("", aliases, jp_name),
                }
                term_by_jp[jp_name] = row
                term_rows.append(row)
                added_terms += 1
                print(f"term + {jp_name} => {extracted.title} [{link.category}]")

    if not dry_run:
        write_tsv(CHARACTER_TSV, char_rows, char_fields)
        write_tsv(TERM_TSV, term_rows, term_fields)
    return scanned, added_chars, added_terms


def compact_name_alias(jp_name: str) -> str:
    alias = re.sub(r"[・=＝\s]", "", jp_name)
    return alias if alias != jp_name else ""


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--limit", type=int, default=220)
    parser.add_argument("--write", action="store_true", help="write extracted rows; default is report-only")
    args = parser.parse_args()

    links = collect_links(SEED_URLS)
    print(f"Collected {len(links)} candidate links")
    dry_run = not args.write
    scanned, added_chars, added_terms = append_candidates(links, args.limit, dry_run)
    mode = "dry-run " if dry_run else ""
    print(f"{mode}scanned={scanned} added_characters={added_chars} added_terms={added_terms}")


if __name__ == "__main__":
    main()
