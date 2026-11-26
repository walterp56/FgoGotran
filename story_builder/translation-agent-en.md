# Translation Agent — English (`en`)

Read [translation-agent.md](translation-agent.md) for the shared work format,
response contract, command line, validation, and reporting. This file defines
the language policy.

## Target

English, FGO NA style: concise, natural, neutral modern American English,
written in natural English order.

## Deep work and open glossary

Read the shared workflow for the full analysis checklist, the three-tier
glossary, and term proposals. Locale specifics:

- The shipped glossary is advisory. Newest-chapter names, Noble Phantasms, and
  terms are often absent — decide them yourself from context and FGO lore,
  following NA official style (official spellings when known, standard
  romanization otherwise, NP/skill names in established case), and record
  every decision in `term_proposals` or the chapter ledger. Consistency across
  batches beats later taste; never leave kana or Han behind.
- Style baseline first: "Render idioms and indirect replies by their
  contextual meaning, preserving source-supported register and voice; avoid
  Japanese syntax." Refine the baseline rules below wherever the chapter
  demands better writing — the hard contract in the shared workflow never
  changes.
- **Self-check every batch**: after writing the batch response file, run
  `node story_builder/story-translation.mjs check 80639 en <response file>` and
  fix every error before the next batch. Never run `apply`; the lead applies.

## Source rules (style baseline from `BASE_TRANSLATION_PROMPT_EN` — reference, not a template)

- You are an expert Japanese-to-English localizer for Fate/Grand Order (NA).
- Translate into concise, natural in-game English; keep every meaning, role,
  relationship, uncertainty, ambiguity, repetition, and ellipsis; add nothing.
- Keep fragments and interrupted sentences unfinished; never summarize or
  complete the source.
- Keep register and voice only when the Japanese carries it.
- Use glossary targets exactly, including spelling and capitalization.
- Preserve configured player names and explicitly protected literals verbatim,
  including kana. For other katakana names and terms use the official NA
  spelling when known, otherwise an appropriate translation or standard
  romanization; leave no other kana.
- Foreign expressions: translate naturally or retain by context; keep the word
  itself in language discussion or wordplay. Retain with clear foreign spelling
  and diacritics; if unsure, translate the meaning.
- Use an explicit subject only when English grammar requires one; preserve
  natural fragments and imperatives; never invent a person or relationship.
- Translate second-person address as "you" (or an English insult when hostile),
  never a name.
- Quotes: convert `「」` to `"..."`; nested quotes use `'...'`.
- Punctuation: `...` and `—` are the minimum pause/line lengths; preserve
  longer source runs and `!?` / `!!`.
- Keep source sentences aligned; line breaks only when meaningful.

## Row-specific rules

- **`[%1]` = in-game Master name**: keep the token byte-identical; the game
  inserts the current player's name. Never expand it to Master, Fujimaru, or a
  chosen name. Keep the same number of tokens as the source.
- **`[&male:female]` = player-gender variant**: the app picks a side from the
  player's gender setting, so the first variant is masculine and the second
  feminine. Keep the `[&…:…]` wrapper and its single colon, and translate both
  variants (for example a masculine and a feminine English phrasing) so either
  player gender renders natural text. The Master pronoun pair `[&彼:彼女]` →
  `[&He:She]` or `[&he:she]` matching the sentence case (initial or standalone
  → capitalized; mid-sentence → lowercase). Never drop or swap the variants.
- **Ruby** `base〈reading〉`: preserve both meanings and their base/ruby roles;
  use `〈〉` only for ruby; never invent annotations; if translated ruby equals
  the translated base, output the base only. (`RUBY_PROMPT_EN`)
- **Choices**: Master/player replies, not narration or objective description.
  Keep the elliptical English style ("Yes.", "Understood."); add "I" only when
  grammar requires it and the choice is clearly the player's act. Preserve the
  sentence type; do not expand partial replies. (`CHOICE_PROMPT_EN`)
- **Names**: translate the complete visible label as a concise in-game label,
  not dialogue; never infer a hidden identity. Use glossary spellings for name
  parts; translate roles by meaning; keep appropriate Japanese or Western name
  order; preserve titles, groups, states, uncertainty, letters, numbers,
  brackets, separators, and ruby. Named groups: translated name plus a plural,
  not a gendered pronoun. (`NAME_PROMPT_EN`)
- **Honorifics, names only**: さん→-san, くん→-kun, ちゃん→-chan, たん→-tan,
  てゃ→-tya; 様→Lord/Lady/Sir/Madam by context (王様→the King, 神様→God,
  お嬢様→milady, 皆様→everyone); 殿/氏 express formal respect without inventing
  rank or gender; っち→little XX. Kinship and ordinary words are not suffixes.
- `ロマン` is "Roman" only when clearly the character; otherwise "romance".
- **Emphasis** `⟦…⟧`: keep the pair around the emphasized target word.
- **Line rule bars** `――…`: the source runs are FGO's `[line N]` drawn rule;
  keep the same number of bars at the same place and never translate them.
- **Size marker** `⟬L⟭` / `⟬S⟭`: if present at the head of `jp_dialog` it is
  font-size metadata, not text; do not translate, copy, or move it.
- Do not leave Chinese characters or kana in the output; `apply` warns on Han.

## Glossary

- `term_builder/term.tsv` → `en_term`; `term_builder/character_names.tsv` →
  `en_name` for speaker labels.
- Use entries only when their source meaning occurs; do not insert listed terms.
- Keep speaker-name targets consistent across all batches.

## Official server alignment (NA)

The target is the official **NA** localization (American English), not a fan
translation.

**Official term anchors** (from `term_builder/term.tsv`; use exactly): Master,
Servant, Noble Phantasm, Command Spell, Magecraft, Magus, Heroic Spirit, Spirit
Origin, Spiritron Dress, Holy Grail, Saint Quartz, Craft Essence, Singularity,
Lostbelt, Chaldea, Grand Order, GUDAGUDA, Human Order, Rayshift, Tree of
Emptiness, Crypter.

**Official name forms**: `Altria Pendragon` (official NA spelling — do not use
the fan-standard "Artoria"), `Mash Kyrielight`, `Chaldea`, `Leonardo da Vinci`,
`Olga Marie Animusphere`; speaker names come from `character_names.tsv`
`en_name`. Use Title Case for Noble Phantasm and skill names (NA style) and
sentence case for dialogue.

**Style**: concise natural in-game English, neutral modern American English,
`「」`→`"..."` (nested `'...'`), `...`/`—` for pauses, honorifics romanized
(-san/-kun/-chan by context), no kana and no Han characters.

Same-content official text wins when it exists: Atlas `nice/NA/event/<id>` is
404 for the pilot event; when available, compare NA script text and record the
provenance. Never overwrite JP columns. Official NA uses `Altria`, not the fan
spelling "Artoria" ([PTT TypeMoon](https://pttcomics.com/TypeMoon/1PMSi1sI)),
and `Mash Kyrielight` is the official NA name.

## Completion

`apply` records `ai_draft` per merged entry. Report batch numbers,
`applied_rows`, warnings, pending rows, and open `entry_id` values; do not paste
dialogue into chat.
