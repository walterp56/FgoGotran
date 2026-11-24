# Translation Agent — Simplified Chinese (`cn`)

Read [translation-agent.md](translation-agent.md) for the shared work format,
response contract, command line, validation, and reporting. This file defines
the language policy.

## Target

Simplified Chinese (`zh-Hans`), concise natural in-game FGO localization,
neutral modern standard written Chinese.

## Deep work and open glossary

Read the shared workflow for the full analysis checklist, the three-tier
glossary, and term proposals. Locale specifics:

- The shipped glossary is advisory. Newest-chapter names and terms are often
  absent — decide them yourself from context and FGO lore, using 国服/官方简中
  惯例 (御主, 从者, 人理修复…), and record every decision in `term_proposals`
  or the chapter ledger. Consistency across batches beats later taste.
- Style baseline first: "Render idioms and indirect replies by their
  contextual meaning, preserving source-supported register and voice; avoid
  Japanese syntax." Refine the baseline rules below wherever the chapter
  demands better writing — the hard contract in the shared workflow never
  changes.
- **Self-check every batch**: after writing the batch response file, run
  `node story_builder/story-translation.mjs check 80639 cn <response file>` and
  fix every error before the next batch. Never run `apply`; the lead applies.

## Source rules (style baseline from `BASE_TRANSLATION_PROMPT` — reference, not a template)

- You are an expert Japanese-to-Chinese localizer for Fate/Grand Order.
- Translate into concise, natural in-game Simplified Chinese; keep every
  meaning, role, relationship, uncertainty, ambiguity, repetition, and
  ellipsis, and add nothing.
- Keep fragments and interrupted sentences unfinished; never summarize or
  complete the source.
- Keep register and voice only when the Japanese carries them. Use natural
  Chinese order, not Japanese syntax.
- Use glossary targets exactly for their specified source meanings.
- Preserve configured player names and explicitly protected literals verbatim,
  including kana. Translate or transliterate all other kana; common loanwords
  may use compact English.
- Foreign expressions: translate naturally or retain by context. Keep the word
  itself in language discussion or wordplay. When retaining, use clear foreign
  spelling with diacritics; if unsure, translate the meaning.
- Preserve stated references and action roles; use natural Chinese omission
  when Japanese omits them, and never infer them from speaker identity.
- Keep source sentences aligned; line breaks only when meaningful.
- Punctuation: use `……` and `───` as the minimum pause/line lengths; keep
  longer source runs and expressive punctuation order.

## Row-specific rules

- **`[%1]` = in-game Master name**: keep the token byte-identical; the game
  inserts the current player's name. Never render it as 御主, 藤丸立香, or 主人.
- **`[&male:female]` = player-gender variant**: the app picks a side from the
  player's gender setting, so the first variant is masculine and the second
  feminine. Keep the `[&…:…]` wrapper and its single colon, and translate both
  variants, e.g. `[&俺:あたし]` → `[&我:人家]`. The Master pronoun pair
  `[&彼:彼女]` → `[&他:她]`. Never drop or swap the variants.
- **Ruby** `base〈reading〉`: keep both meanings and roles; use `〈〉` only for
  ruby; drop the marker only when the translated ruby equals the translated
  base. (`RUBY_PROMPT`)
- **Choices**: Master/player replies, not narration. Preserve the sentence type;
  do not add a first-person pronoun the source does not license or expand a
  partial/attitude reply into an explanation. (`CHOICE_PROMPT`)
- **Names**: translate the complete visible label as a concise in-game label,
  never a dialogue or explanation, never a hidden identity. Use glossary
  spellings for name parts; translate roles by meaning; transliterate only
  unknown proper names in FGO/TYPE-MOON style. Preserve titles, groups, states,
  uncertainty, letters, numbers, brackets, separators, and ruby. Named groups
  use the translated name plus `们`. (`NAME_PROMPT`)
- **Second-person address** (`あなた`, `お前`, …): translate by tone and
  relationship, never keep it as Japanese or replace it with a name.
  (`ADDRESS_PRONOUN_PROMPT`)
- **Honorifics, names only**: さん→桑, くん→君, ちゃん→酱, 様→大人, 殿→阁下,
  たん→炭, てゃ→宝, 氏→氏, っち→小XX (never XX小). Kinship and ordinary words
  such as お父さん or 皆さん are not suffixes.
- `ロマン` is the character only when clearly a person; otherwise 浪漫.
- **Emphasis** `⟦…⟧`: keep the pair around the emphasized target word.
- **Line rule bars** `――…`: the source runs are FGO's `[line N]` drawn rule;
  keep the same number of bars at the same place and never translate them.

## Glossary

- `term_builder/term.tsv` → `cn_term` (places, classes, items, organizations,
  game terms).
- `term_builder/character_names.tsv` → `cn_name` for speaker labels.
- Use an entry only when its source meaning occurs; do not insert listed terms.
- Keep speaker-name targets identical across all batches of the same speaker.

## Official server alignment (mainland CN)

The target is the official **国服（简体）** localization, not generic Chinese and
not the TW build.

**Official term anchors** (from `term_builder/term.tsv`; use exactly when the
source carries the meaning): 御主, 从者, 宝具, 令咒, 魔术, 魔术师, 英灵,
灵基, 灵衣, 圣杯, 圣晶石, 概念礼装, 特异点, 异闻带, 迦勒底, 冠位指定,
唠唠叨叨, 人理修复, 灵子转移, 空想树, 隐匿者. The full list is
`term_builder/term.tsv`; speaker names are `character_names.tsv` `cn_name`.

**Official name forms** (CN server, `·` separator): 玛修·基列莱特,
阿尔托莉雅·潘德拉贡, 达·芬奇, 冲田总司, 松永弹正, 摩根. Never use TW forms
(瑪修·基利艾拉特, 阿爾托莉亞·潘德拉剛, 達·文西); `check` warns
`tw-name-form-in-cn`.

**Script**: Simplified Chinese only. A traditional character is an error
(`traditional-chars-in-cn`) — do not copy TW override spellings.

**Style**: neutral modern standard written Chinese, natural Chinese order, `·`
for foreign-name parts, keep 「」 as in the source, ……/─── for pauses, no
community slang (种火/狗粮-style nicknames are not official wording).

Same-content official text wins when it exists: Atlas `nice/CN/event/<id>` is
404 for the pilot event; when a localized CN event exists, compare its war
scripts and reuse the wording (record the provenance). Never overwrite JP
columns.

## Completion

`apply` records `ai_draft` for every merged entry. Report batch numbers,
`applied_rows`, warnings, pending rows, and open `entry_id` values; do not paste
dialogue into chat.
