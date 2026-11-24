# Translation Agent — Traditional Chinese (`tw`)

Read [translation-agent.md](translation-agent.md) for the shared work format,
response contract, command line, validation, and reporting. This file defines
the language policy.

## Target

Traditional Chinese (`zh-Hant`, `繁體中文`), concise natural in-game FGO
localization, neutral modern standard written Chinese. TW is an independent
locale, not a mechanical character conversion of the CN draft.

## Deep work and open glossary

Read the shared workflow for the full analysis checklist, the three-tier
glossary, and term proposals. Locale specifics:

- The shipped glossary and the reviewed overrides are advisory. Newest-chapter
  names and terms are often absent — decide them yourself from context and FGO
  lore, following 繁中官方慣例 (御主, 從者, middle-dot names such as
  阿爾托莉亞·潘德拉剛), and record every decision in `term_proposals` or the
  chapter ledger. Consistency across batches beats later taste.
- Style baseline first: "Render idioms and indirect replies by their
  contextual meaning, preserving source-supported register and voice; avoid
  Japanese syntax." Refine the baseline rules below wherever the chapter
  demands better writing — the hard contract in the shared workflow never
  changes.
- **Self-check every batch**: after writing the batch response file, run
  `node story_builder/story-translation.mjs check 80639 tw <response file>` and
  fix every error before the next batch. Never run `apply`; the lead applies.

## Source rules (style baseline from `BASE_TRANSLATION_PROMPT` — reference, not a template)

The Chinese base prompt applies with the target name `繁體中文`
(`BASE_TRANSLATION_PROMPT` in `PromptBuilder.kt`; general Traditional profiles
also use the `Traditional Chinese` label):

- Expert Japanese-to-Chinese localizer; keep every meaning, role, relationship,
  uncertainty, ambiguity, repetition, and ellipsis; add nothing.
- Fragments stay unfinished; never summarize or complete.
- Keep register/voice only when the Japanese carries it; natural Chinese order.
- Glossary targets are mandatory for their specified meaning.
- Preserve configured player names and explicitly protected literals verbatim.
  Translate or transliterate all other kana; no kana in the output.
- Preserve stated references and action roles; never infer omitted subjects,
  possession, or relationships from the speaker.
- Punctuation: `……` and `───` as minimum pause/line lengths; preserve longer
  runs and expressive punctuation order.
- Use Traditional characters throughout (這/們/嗎/裡/說/來…), not Simplified
  forms; keep Taiwanese/HK-compatible FGO register rather than mainland slang.

## Row-specific rules

- **`[%1]` = in-game Master name**: keep the token byte-identical; the game
  inserts the current player's name. Never render it as 御主, 藤丸立香, or 主人.
- **`[&male:female]` = player-gender variant**: the app picks a side from the
  player's gender setting, so the first variant is masculine and the second
  feminine. Keep the `[&…:…]` wrapper and its single colon, and translate both
  variants, e.g. `[&俺:あたし]` → `[&我:人家]`. The Master pronoun pair
  `[&彼:彼女]` → `[&他:她]`. Never drop or swap the variants.
- **Ruby** `base〈reading〉`: keep both roles, `〈〉` only for ruby, drop the
  marker only when translated ruby equals translated base.
- **Choices**: player replies, elliptical, preserve sentence type, no added
  first person absent from the source.
- **Names**: concise in-game labels, never explanations; use the glossary and
  the reviewed Traditional overrides; named groups use `們`; `っち`→`小XX`.
- **Honorifics, names only**: さん→桑, くん→君, ちゃん→醬, 様→大人, 殿→閣下,
  たん→炭, てゃ→寶, 氏→氏, っち→小XX. Kinship/ordinary words are not suffixes.
- `ロマン` is the person only when clearly a character; otherwise 浪漫.
- **Emphasis** `⟦…⟧`: keep the pair around the emphasized target word.
- **Line rule bars** `――…`: the source runs are FGO's `[line N]` drawn rule;
  keep the same number of bars at the same place and never translate them.

## Glossary and Traditional exceptions

- `term_builder/term.tsv` provides the CN reference (`cn_term`) and English
  target; render the term naturally in Traditional Chinese.
- `app/src/main/assets/translation/traditional_glossary_overrides.tsv`
  (`jp_source`, `cn_reference`, `zh_hant`) holds reviewed Traditional spellings
  for character names and terms. When the batch glossary note says
  `reviewed zh-Hant override`, use `target` exactly; otherwise use the CN
  reference and apply natural Traditional rendering.
- `term_builder/character_names.tsv` → `cn_name` is the CN form; use the
  reviewed override when present, else the established TW form.
- Keep the same speaker-name rendering across all batches.
- Read-only check available if glossary or overrides change:
  `python -B term_builder/py/check_traditional_glossary.py --check`.

## Official server alignment (Taiwan TW)

The target is the official **繁中版** localization (Taiwan), not a character
conversion of the CN draft.

**Official term anchors** (Traditional; same sources): 御主, 從者, 寶具, 令咒,
魔術, 魔術師, 英靈, 靈基, 靈衣, 聖杯, 聖晶石, 概念禮裝, 特異點, 異聞帶,
迦勒底, 冠位指定, 嘮嘮叨叨, 人理修復, 靈子轉移, 空想樹, 隱匿者.
TW-specific spellings from `traditional_glossary_overrides.tsv` win over the CN
reference form whenever present.

**Official name forms** (reviewed TW overrides, middle-dot style):
瑪修·基利艾拉特, 阿爾托莉亞·潘德拉剛, 達·文西, 沖田·J·總司, 奧伯隆,
伊絲塔, 斯卡薩哈, 庫·夫林, 梵谷, 瑪莉·安東尼. Use 們 for plurals. Never use
mainland forms (玛修·基列莱特, 阿尔托莉雅·潘德拉贡, 达·芬奇); `check` warns
`cn-name-form-in-tw`.

**Script**: Traditional Chinese only; a Simplified character is an error
(`simplified-chars-in-tw`).

**Never mainland terms.** The 2019 TW build was flooded with 中國用語 (寶具值 for
NP, 消耗 for COST, 行動 for card selection) and players rejected it — keep TW
platform wording and 繁中 register. Community slang (狗糧/農具-style nicknames)
is not official wording.

Same-content official text wins when it exists: Atlas `nice/TW/event/<id>` is
404 for the pilot event; when available, compare TW war scripts and record the
provenance. Never overwrite JP columns. References for the TW-only requirement:
[GameApps](https://www.gameapps.hk/news/35642/Fate-Grand-Order/),
[4Gamers](https://www.4gamers.com.tw/news/detail/40528/fgo-traditional-chinese-version-190926-update),
[esportstw](https://esportstw.com/news/52705/),
[udn](https://game.udn.com/game/story/10453/4557325).

## Completion

`apply` records `ai_draft` per merged entry. Report batch numbers,
`applied_rows`, warnings, pending rows, and open `entry_id` values; do not paste
dialogue into chat.
