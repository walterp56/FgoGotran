"""Append curated NPC names from Mooncell's story-character page.

The source page lists CN story-character names, but most entries do not expose
machine-readable JP names. This importer therefore uses the page as the source
of which NPC entries are in scope, and a curated JP mapping for the actual TSV
rows.
"""

from __future__ import annotations

import csv
import json
import re
import sys
import unicodedata
from pathlib import Path
from urllib.request import urlopen


ROOT = Path(__file__).resolve().parent
CHARACTER_TSV = ROOT / "character_names.tsv"
STORY_CHARACTER_RAW_URL = (
    "https://fgo.wiki/index.php?"
    "title=%E5%89%A7%E6%83%85%E8%A7%92%E8%89%B2%E4%B8%80%E8%A7%88&action=raw"
)
MAIN_CHARACTER_RAW_URL = (
    "https://fgo.wiki/index.php?"
    "title=%E7%99%BB%E5%9C%BA%E4%BA%BA%E7%89%A9&action=raw"
)


NPC_ROWS: list[tuple[str, str, list[str]]] = [
    ("卡玛佐茨", "カマソッソ", []),
    ("朱瑞鸟·红阎魔", "朱瑞鳥・紅閻魔", []),
    ("恐龙王", "恐竜王", []),
    ("伊斯卡利", "イスカリ", []),
    ("ORT", "ORT", []),
    ("斯堪的纳维亚·佩佩隆奇诺", "スカンジナビア・ペペロンチーノ", ["ペペ"]),
    ("高扬斯卡娅", "コヤンスカヤ", []),
    ("布兰卡", "ブランカ", []),
    ("威尔士的妖精", "ウェールズの妖精", []),
    ("欧若拉", "オーロラ", []),
    ("科拉尔", "コーラル", []),
    ("斯普里根", "スプリガン", []),
    ("亚铃百种·散热大公", "亜鈴百種・排熱大公", []),
    ("博格特", "ボガード", []),
    ("艾因塞尔", "エインセル", []),
    ("诺克娜蕾", "ノクナレア", []),
    ("温基", "ウィンキー", []),
    ("瓦格", "ワグ", []),
    ("罗布", "ロブ", []),
    ("霍普", "ホープ", []),
    ("店主妖精", "店主妖精", []),
    ("白狼", "白狼", []),
    ("埃克特", "エクター", []),
    ("摩耳斯", "モース", []),
    ("祭神科尔努诺斯", "祭神ケルヌンノス", []),
    ("赛法卢", "セファール", []),
    ("阿尔比恩的龙骸", "アルビオンの竜骸", []),
    ("魔犬巴格斯特", "魔犬バーゲスト", []),
    ("魔龙伏提庚", "魔竜ヴォーティガーン", []),
    ("Archer Inferno·一切烧却", "アーチャー・インフェルノ・一切焼却", []),
    ("Assassin Paraiso·一切诅咒", "アサシン・パライソ・一切呪詛", []),
    ("阿格规文", "アグラヴェイン", []),
    ("阿特拉斯", "アトラス", []),
    ("BB／GO", "BB／GO", ["BB/GO"]),
    ("豹尾神·铃鹿御前", "豹尾神・鈴鹿御前", []),
    ("柴田胜家", "柴田勝家", []),
    ("达尤", "ダユー", []),
    ("杜木兹", "ドゥムジ", []),
    ("First Lady", "ファースト・レディ", []),
    ("弗格斯·马克·罗伊〔Lily〕", "フェルグス・マック・ロイ〔リリィ〕", []),
    ("复合神性戈耳工", "複合神性ゴルゴーン", []),
    ("汉斯·克里斯蒂安·安徒生〔成年〕", "ハンス・クリスチャン・アンデルセン〔大人〕", []),
    ("韩信", "韓信", []),
    ("黑之圣杯", "黒の聖杯", []),
    ("金固", "キングゥ", []),
    ("玖贺耳之御笠", "玖賀耳之御笠", []),
    ("加拉哈德", "ギャラハッド", []),
    ("巨英雄", "巨英雄", []),
    ("Lancer Purgatorio·一切穿通", "ランサー・プルガトリオ・一切穿通", []),
    ("麦克斯韦妖", "マックスウェルの悪魔", []),
    ("弥诺陶洛斯", "ミノタウロス", []),
    ("明智光秀", "明智光秀", []),
    ("谜之女枪手", "謎の女ガンナー", []),
    ("女神伦戈米尼亚德", "女神ロンゴミニアド", []),
    ("哪吒(Berserker)", "哪吒〔バーサーカー〕", []),
    ("芹泽鸭", "芹沢鴨", []),
    ("千子村正(Alterego)", "千子村正〔アルターエゴ〕", []),
    ("杀生院祈荒〔Lily〕", "殺生院キアラ〔リリィ〕", []),
    ("岁杀神·俵藤太", "歳殺神・俵藤太", []),
    ("四元素之爱丽", "四元素アイリ", ["火のアイリ", "水のアイリ", "風のアイリ", "土のアイリ"]),
    ("天草四郎(Avenger)", "天草四郎〔アヴェンジャー〕", []),
    ("威廉·莎士比亚〔Alter〕", "ウィリアム・シェイクスピア〔オルタ〕", []),
    ("悉多", "シータ", []),
    ("夏洛克·福尔摩斯(Caster)", "シャーロック・ホームズ〔キャスター〕", []),
    ("新宿的Rider", "新宿のライダー", []),
    ("项羽(Rider)", "項羽〔ライダー〕", []),
    ("烟醉哈桑", "煙酔のハサン", []),
    ("贞德〔Alter〕(Ruler)", "ジャンヌ・ダルク〔オルタ〕〔ルーラー〕", []),
    ("佐佐木小次郎(Saber)", "佐々木小次郎〔セイバー〕", []),
    ("比利时来的人", "ベルギーから来た男", []),
    ("春日局", "春日局", []),
    ("长尾晴景", "長尾晴景", []),
    ("达尼克·普雷斯通·尤格多米雷尼亚", "ダーニック・プレストーン・ユグドミレニア", []),
    ("哈根", "ハーゲン", []),
    ("基德船长", "キャプテン・キッド", []),
    ("间桐雁夜", "間桐雁夜", []),
    ("间桐脏砚", "間桐臓硯", ["マキリ・ゾォルケン"]),
    ("克里斯蒂娜", "クリスティーヌ", []),
    ("肯尼斯·埃尔梅罗·阿奇博尔德", "ケイネス・エルメロイ・アーチボルト", []),
    ("绫", "綾", []),
    ("伦道夫·卡特", "ランドルフ・カーター", []),
    ("李尔王", "リア王", []),
    ("罗密欧与朱丽叶", "ロミオとジュリエット", []),
    ("拉维尼娅·维特利", "ラヴィニア・ウェイトリー", []),
    ("摩玖主大僧正", "摩玖主大僧正", []),
    ("麦克白", "マクベス", []),
    ("麦克斯韦", "マックスウェル", []),
    ("谟涅摩叙涅", "ムネーモシュネー", []),
    ("鸟窝头的博士", "鳥の巣頭の博士", []),
    ("皮埃尔·科雄", "ピエール・コーション", []),
    ("认真严肃的绅士", "生真面目な紳士", []),
    ("松平信纲", "松平信綱", []),
    ("太空伊什塔尔〔Lily〕", "スペース・イシュタル〔リリィ〕", []),
    ("土田御前", "土田御前", []),
    ("藤原道长", "藤原道長", []),
    ("田中新兵卫", "田中新兵衛", []),
    ("韦伯·维尔维特", "ウェイバー・ベルベット", []),
    ("无名之龟", "名もなき亀", []),
    ("玩绳结的老人", "紐を弄くる老人", []),
    ("武市瑞山", "武市瑞山", []),
    ("西杜丽", "シドゥリ", []),
    ("鵺", "鵺", []),
    ("圆脸的神父", "丸顔の神父", []),
    ("宇宙远坂时臣", "スペース遠坂時臣", []),
    ("织田信长(正牌货)", "織田信長〔本物〕", []),
    ("阿波罗", "アポロン", []),
    ("阿耳忒弥斯", "アルテミス", []),
    ("阿佛洛狄忒", "アフロディーテ", []),
    ("阿瑞斯", "アレス", []),
    ("波塞冬", "ポセイドン", []),
    ("哈得斯", "ハデス", []),
    ("赫淮斯托斯", "ヘファイストス", []),
    ("赫拉", "ヘラ", []),
    ("赫斯提亚", "ヘスティア", []),
    ("雅典娜", "アテナ", []),
    ("宙斯", "ゼウス", []),
    ("香香", "シャンシャン", []),
    ("奥菲莉娅·法姆索罗涅", "オフェリア・ファムルソローネ", ["オフェリア"]),
    ("贝里尔·伽特", "ベリル・ガット", ["ベリル"]),
    ("百貌哈桑·小哈桑", "百貌ハサン・ちびハサン", []),
    ("戴比特·泽姆·沃伊德", "デイビット・ゼム・ヴォイド", ["デイビット"]),
    ("厄喀德那", "エキドナ", []),
    ("戈尔德鲁夫·穆吉克", "ゴルドルフ・ムジーク", ["ゴルドルフ"]),
    ("芥雏子", "芥ヒナコ", ["ヒナコ"]),
    ("吉尔伽美什(人类)", "ギルガメッシュ〔人間〕", []),
    ("基尔什塔利亚·沃戴姆", "キリシュタリア・ヴォーダイム", ["キリシュタリア"]),
    ("卡多克·泽姆露普斯", "カドック・ゼムルプス", ["カドック"]),
    ("罗玛尼·阿其曼", "ロマニ・アーキマン", ["ロマン"]),
    ("玛布尔·玛金托修", "マーブル・マッキントッシュ", []),
    ("牛若丸(Berserker)", "牛若丸〔バーサーカー〕", []),
    ("所罗门(Caster)", "ソロモン〔キャスター〕", []),
    ("苏鲁特(异闻带)", "スルト〔異聞帯〕", []),
    ("马里斯比利", "マリスビリー・アニムスフィア", ["マリスビリー"]),
    ("无名的御主", "無名のマスター", []),
    ("希翁·艾尔特纳姆·索卡里斯", "シオン・エルトナム・ソカリス", ["シオン"]),
    ("伊什塔尔〔迷你〕", "イシュタル〔ミニ〕", []),
    ("异星巫女", "異星の巫女", []),
]


def main() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    source_cns = load_source_character_names()
    rows = list(csv.DictReader(CHARACTER_TSV.open(encoding="utf-8-sig"), delimiter="\t"))
    servant_rows = [row for row in rows if row.get("type") == "servant"]
    seen_jp = {row["jp_name"] for row in servant_rows}

    npc_rows: list[dict[str, str]] = []
    skipped_not_on_page: list[str] = []
    skipped_duplicate_jp: list[str] = []
    skipped_context_duplicate: list[str] = []
    stripped_context_rows: list[str] = []

    for cn_name, jp_name, extra_aliases in NPC_ROWS:
        if cn_name not in source_cns:
            skipped_not_on_page.append(cn_name)
            continue
        normalized_jp, normalized_cn = strip_context_suffixes(jp_name), strip_context_suffixes(cn_name)
        if normalized_jp != jp_name and normalized_jp in seen_jp:
            skipped_context_duplicate.append(jp_name)
            continue
        if normalized_jp != jp_name or normalized_cn != cn_name:
            stripped_context_rows.append(f"{jp_name}->{normalized_jp}")
        jp_name = normalized_jp
        cn_name = normalized_cn
        if jp_name in seen_jp:
            skipped_duplicate_jp.append(jp_name)
            continue
        npc_rows.append(
            {
                "jp_name": jp_name,
                "cn_name": cn_name,
                "aliases": ",".join(build_aliases(jp_name, extra_aliases)),
                "type": "npc",
            }
        )
        seen_jp.add(jp_name)

    write_character_tsv(servant_rows + npc_rows)
    print(
        json.dumps(
            {
                "servant_rows_preserved": len(servant_rows),
                "npc_rows_added": len(npc_rows),
                "source_cn_names_loaded": len(source_cns),
                "skipped_not_on_page": skipped_not_on_page,
                "skipped_duplicate_jp": skipped_duplicate_jp,
                "skipped_context_duplicate": skipped_context_duplicate,
                "stripped_context_rows": stripped_context_rows,
            },
            ensure_ascii=False,
            indent=2,
        )
    )


def load_source_character_names() -> set[str]:
    names = set()
    names.update(load_story_character_names())
    names.update(load_main_character_names())
    return names


def load_story_character_names() -> set[str]:
    raw = urlopen(STORY_CHARACTER_RAW_URL, timeout=45).read().decode("utf-8")
    sections: dict[str, list[str]] = {}
    section: str | None = None
    for line in raw.splitlines():
        level_2 = re.match(r"^==([^=].*?[^=])==[ \t\r]*$", line)
        if level_2:
            section = re.sub(r"<[^>]+>", "", level_2.group(1)).strip()
            sections.setdefault(section, [])
            continue
        level_3 = re.match(r"^===(.+?)===[ \t\r]*$", line)
        if level_3 and section:
            name = re.sub(r"<[^>]+>", "", level_3.group(1)).strip()
            if name:
                sections.setdefault(section, []).append(name)

    keys = list(sections.keys())
    # Skip "灵衣" and "注释和链接"; include the two latest story blocks plus
    # 从者 / 人物 / 机神 / 玩偶 / 特殊.
    source_sections = keys[:2] + keys[3:8]
    names: set[str] = set()
    for source_section in source_sections:
        names.update(sections.get(source_section, []))
    return names


def load_main_character_names() -> set[str]:
    raw = urlopen(MAIN_CHARACTER_RAW_URL, timeout=45).read().decode("utf-8")
    names: set[str] = set()

    for match in re.finditer(r"\[\[([^\]|#]+)(?:#[^\]|]+)?(?:\|([^\]]+))?\]\]", raw):
        target = clean_source_name(match.group(1))
        label = clean_source_name(match.group(2) or match.group(1))
        if target:
            names.add(target)
        if label:
            names.add(label)

    for line in raw.splitlines():
        if "||" not in line:
            continue
        cells = [clean_source_name(cell) for cell in line.split("||")]
        for cell in cells[1:2]:
            if cell:
                names.add(cell)

    return names


def clean_source_name(text: str) -> str:
    text = re.sub(r"<[^>]+>", "", text)
    text = re.sub(r"\{\{Heimu\|([^{}]*)\}\}", r"\1", text)
    text = re.sub(r"\{\{ruby\|([^|{}]+)\|[^{}]*\}\}", r"\1", text)
    text = re.sub(r"\{\{[^{}]*\}\}", "", text)
    text = text.replace("'''", "").replace("''", "")
    text = text.replace("<br>", "\n").replace("<br/>", "\n").replace("<br />", "\n")
    text = re.sub(r"\[[^\]]+\]", "", text)
    text = text.split("\n", 1)[0]
    return text.strip()


def strip_context_suffixes(name: str) -> str:
    previous = None
    while previous != name:
        previous = name
        name = re.sub(r"\s*(?:〔[^〕]+〕|（[^）]+）|\([^)]*\))\s*$", "", name).strip()
    return name


def build_aliases(jp_name: str, extra_aliases: list[str]) -> list[str]:
    aliases: list[str] = []
    candidates = [
        unicodedata.normalize("NFKC", jp_name),
        jp_name.replace("・", ""),
        jp_name.replace("・", "·"),
        jp_name.replace("／", "/"),
        jp_name.replace("〔", "(").replace("〕", ")"),
    ]
    candidates.extend(extra_aliases)
    for candidate in candidates:
        if candidate and candidate != jp_name and candidate not in aliases:
            aliases.append(candidate)
    return aliases


def write_character_tsv(rows: list[dict[str, str]]) -> None:
    with CHARACTER_TSV.open("w", encoding="utf-8-sig", newline="") as file:
        writer = csv.DictWriter(
            file,
            fieldnames=["jp_name", "cn_name", "aliases", "type"],
            delimiter="\t",
            lineterminator="\n",
        )
        writer.writeheader()
        writer.writerows(rows)


if __name__ == "__main__":
    main()
