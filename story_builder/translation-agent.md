# Translation Agent — shared workflow

Three locale agents use this workflow: **cn**, **tw**, and **en**. Their locale
playbooks are [translation-agent-cn.md](translation-agent-cn.md),
[translation-agent-tw.md](translation-agent-tw.md), and
[translation-agent-en.md](translation-agent-en.md). Read the locale playbook for
language policy and this file for the work format.

## Position in the pipeline

1. The Japanese script preparation agent produces the canonical JP TSV with
   `prepare` (see [README.md](README.md#build-and-agent-handoffs)).
2. The three translation agents fill their locale columns from that canonical
   TSV. They never change Japanese columns, row order, `entry_id`, or `type`.
3. The independent reviewer checks the merged TSV by entry ID.
4. Deterministic tools merge approved entries and generate SQLite and catalog
   artifacts.

## Working principles

**Quality is the only budget.** This is offline batch work: latency and token
count do not matter, translation quality does. Read, analyze, and self-review
as much as the text needs. Never rush a batch to completion.

**The app prompt is a reference, not a template.** The rules below and in your
locale playbook are distilled from the app's translation prompts
(`app/src/main/java/com/fgogotran/translation/PromptBuilder.kt`, versions
`jp-cn-fgo-target-v102-punctuation` / `jp-en-fgo-target-v10-punctuation`).
Treat them as a strong style baseline for the game. Two kinds of rules:

- **Hard technical contract** — required by the app's renderer, never
  negotiable: `[%1]`/`[%N]` tokens, `[&male:female]` controls, ruby `〈〉`,
  emphasis `⟦⟧`, no leftover kana, one row per box/choice, unchanged `type`,
  `entry_id`, and row order.
- **Style reference** — guidance the app prompt gives, which you may refine
  when the source or the chapter demands better writing. Deviations are fine;
  the reviewer must be able to see *why* from your term proposals and reports.

**Priority order when the source is difficult.** The repo's own prompt
evaluation measured quality as meaning 0.55, terminology 0.20, voice 0.15, and
naturalness 0.10. Keep the source meaning first, then established terminology,
then voice/register, then phrasing. A known winning instruction: *"Render
idioms and indirect replies by their contextual meaning, preserving
source-supported register and voice; avoid Japanese syntax."*

## Official server alignment

The target text must read like the official server build of that locale, not like
a generic translation.

1. **Same-content official text wins.** When Atlas has the localized event or war
   (`nice/<CN|TW|NA>/event/<id>` and its war scripts), compare the matching scene
   and reuse its wording; it outranks every draft. Record that provenance.
2. **The repo's official lexicon is mandatory for listed meanings.**
   `term_builder/term.tsv` (CN, EN), `term_builder/character_names.tsv` (CN, EN),
   and `app/src/main/assets/translation/traditional_glossary_overrides.tsv`
   (reviewed TW spellings) encode official-server terms and names. Use those
   targets exactly when the source carries that meaning.
3. **Extrapolate official style for new content.** Newest-chapter terms are not
   listed yet; choose targets that could pass for the official server: same
   naming conventions, punctuation, register, and name order. Record them as
   `term_proposals`; `apply` writes them into the chapter ledger.
4. **Literary polish comes last.** Never polish away meaning, roles, or the
   server's established terminology.

**Never mix servers.** CN must be Simplified Chinese with mainland official
terms; TW must be Traditional Chinese with Taiwan official terms (reviewed
overrides included); EN must use NA official spellings. Mixing scripts or using
the other server's name forms is a localization defect: `check`/`apply` reject it
(`traditional-chars-in-cn`, `simplified-chars-in-tw`) or warn
(`tw-name-form-in-cn`, `cn-name-form-in-tw`).

**Do not trust a glossary row blindly.** The shipped glossary is community-curated:
if a target mixes scripts, looks machine-generated, or contradicts the official
server, flag it through `term_proposals` with the correction and use the official
form. Community nickname terms (种火/狗粮/农具-style slang) are never official
wording and must not appear in the TSV.

### References

- In-repo official terminology and name spellings: `term_builder/term.tsv`,
  `term_builder/character_names.tsv`,
  `app/src/main/assets/translation/traditional_glossary_overrides.tsv`.
- TW must stay Taiwan-side, not mainland: the 2019 TW client incident where
  mainland terms appeared — [GameApps](https://www.gameapps.hk/news/35642/Fate-Grand-Order/),
  [4Gamers](https://www.4gamers.com.tw/news/detail/40528/fgo-traditional-chinese-version-190926-update).
  Official TW name forms (瑪修．基利艾拉特, 阿爾托莉亞．潘德拉剛):
  [esportstw](https://esportstw.com/news/52705/),
  [udn](https://game.udn.com/game/story/10453/4557325).
- CN official terms (御主, 从者, 宝具, 令咒, 迦勒底):
  [Baidu Baike 命运-冠位指定](https://wapbaike.baidu.com/item/%E5%91%BD%E8%BF%90-%E5%86%A0%E4%BD%8D%E6%8C%87%E5%AE%9A/18910471).
- NA official spellings: official NA uses `Altria`, not the fan spelling
  "Artoria" ([PTT TypeMoon](https://pttcomics.com/TypeMoon/1PMSi1sI)), and
  `Mash Kyrielight` is the official NA name.
- Agent-design references: RenpyBox dispatches a terminology table,
  do-not-translate list, and character cards per batch and returns entries that
  ignore the glossary ([renpybox](https://github.com/Dclef/renpybox)); FGO Scene
  Translator keeps scene commands, `＠name` speaker lines, and
  `[r]`/`[%1]`/`[&a:b]` tags verbatim
  ([fgo-scene-translator](https://github.com/D1siDe/fgo-scene-translator)); an
  LLM translation-QA design fixes string IDs, glossary, and surrounding context
  in a strict JSON contract
  ([j-gamelqa](https://raw.githubusercontent.com/liguozheng-ac/j-gamelqa/refs/heads/main/docs/interview_deep_dive.md)),
  and a Cursor-written FGO story translator shows the same scene/tag structure
  ([fgo_translator](https://github.com/securityCFS/fgo_translator)).

## Open glossary — the DB will be stale

The shipped glossary (`term_builder/term.tsv`, `character_names.tsv`, the TW
overrides) is an advisory baseline. The newest chapters introduce names, Noble
Phantasms, places, and terms that are **not in it yet**. Do not force a row to
fit an existing entry, and never leave kana because "there is no entry".

Three tiers, in priority order:

1. **Chapter ledger** — `work/event-<id>/translation/<locale>/terms.json`, a
   JSON array of `{"source", "target", "category", "note"}`. Decisions already
   made for this chapter. The export merges them into every batch with the
   note `chapter glossary`, overriding the shipped DB. It is binding: reuse
   the same target everywhere. `apply` maintains it automatically from your
   `term_proposals` (first decision per source wins; a later conflicting target
   is reported instead of overwriting). You do not need to hand-edit it.
2. **Shipped glossary** — use the exact target when the source occurrence has
   the listed meaning. Do not insert an entry merely because it is listed.
3. **New or uncertain terms** — the agent decides, because the agent has the
   context the DB lacks:
   - Identify every proper noun and game term in the batch, including aliases,
     nicknames, and one-off wordplay.
   - Use the whole chapter context (`previous_context`, scene structure,
     speaker), FGO/TYPE-MOON lore, and the locale's official style (CN 官方
     簡中慣例, TW 繁中慣例, NA official spelling) to pick or create the target.
   - Transliterate only when that is the locale's convention for a new proper
     noun; otherwise translate by meaning. Never invent a character identity.
   - Record the decision as a `term_proposals` entry in the response file. If
     you later change your mind, later batches must follow the **first applied**
     target for the same source — consistency beats taste.
   - No manual ledger edit is required: accepted proposals are merged into
     `terms.json` by `apply`, and the reviewer later promotes reviewed entries
     into `term_builder`.

## Work format

The CLI is `story_builder/story-translation.mjs`. Run it from the FgoGotran root.

When one locale is translated by several workers in parallel (chunks of batches
handed to sub-agents), the orchestrator reads the full playbooks and every
worker reads this file's markup contract plus
[translation-worker-brief.md](translation-worker-brief.md) — the brief is a
compact restatement, not a replacement for locale policy. The orchestrator owns
the speaker-name and term tables and merges each chunk's decisions between
waves; workers never invent a different target for a name the table already
fixes.

```powershell
# 1. Create batches (writes work/event-<id>/translation/<locale>/batch-XXX.json + manifest.json)
node story_builder/story-translation.mjs export 80639 cn

# 2. Translate each batch and save one response JSON per batch under
#    work/event-80639/translation/cn/responses/
#    (any file name ending in .json is read)

# 3. Self-check (read-only, no TSV write). Run it per batch while you work,
#    and again on the whole directory before handing off.
node story_builder/story-translation.mjs check 80639 cn
node story_builder/story-translation.mjs check 80639 cn work/event-80639/translation/cn/responses/batch-001.json

# 4. Validate and merge the responses into the cn columns of the TSV
node story_builder/story-translation.mjs apply 80639 cn story_builder/work/event-80639/translation/cn/responses

# 5. Coverage report for all locales
node story_builder/story-translation.mjs status 80639
```

## Self-check loop (required)

`check` runs the same validator as `apply` but never writes the TSV. After
writing a batch response file, run:

```powershell
node story_builder/story-translation.mjs check 80639 <locale> <batch response file>
```

and fix every `error` before starting the next batch. `check` reports, per
batch file: entries, errors, warnings, missing required rows, cross-batch
term conflicts, speaker-name conflicts, and source anomalies (private-use
glyphs in the Japanese source). A clean check means `apply` will not fail, so
never hand off a response set that has not passed `check`. Warning-only results
are acceptable but must be listed in your report (especially dropped-ruby entry
IDs, which the reviewer samples).

A batch file is **compact JSON** (`format: "compact-v1"`), about three times
smaller than the old one-object-per-row shape, so reading a batch is cheap:

```text
{event_id, story_library_id, locale, batch, batch_count, source_tsv_sha256,
 format, fields, scripts, script_part?, scenes, pending, controls?, glossary,
 previous_context, existing?, entries}
```

- `entries` holds one row per box/button in story order, a 5-tuple in `fields`
  order: `[entry_id, type, jp_name, jp_dialog, glossary_indexes]`.
  `glossary_indexes` point into the batch-level `glossary` array
  (`{source, target, category, note}`); a term is listed once per batch, not once
  per row, and still means "use this target when the source carries the meaning".
- `pending` is `"all"` when every row still needs both fields; otherwise it is
  the list of `entry_id`s that need work. Do not rewrite a filled field.
- `existing` appears only on a resumed or partially filled chapter and holds the
  already translated `{name, dialog}` per entry.
- `scripts` is the subchapter id (one Atlas 劇情文本, e.g. `9416400221`) this
  batch belongs to — a batch never mixes two, so the batch is one scene.
- `script_part` appears only when the scene needed more than one batch, as
  `[part, parts]`; translate it as part of that scene, not as a standalone scene.
- `scenes` gives scene ranges as `[scene_id, first_entry_number,
  last_entry_number]`, 1-based inside the batch.
- `controls` appears only when the batch contains control tokens, as counts such
  as `{"master_name": 2, "gender_control": 1, "ruby": 5}`; handle them with the
  hard markup rules below.
- `previous_context` is the last six rows of the **same scene**, as
  `[entry_id, jp_name, jp_dialog]`, for continuity only; it is empty when the
  batch opens the scene.
- Earlier and later entries in the batch are scene context. Do not import
  content from other batches.
- `--pretty` re-emits the same fields indented for human reading; agents do not
  need it.

Response file for one batch:

```json
{
  "event_id": 80639,
  "locale": "cn",
  "translations": [
    { "entry_id": "<exact entry_id from the batch>", "name": "<target name>", "dialog": "<target dialogue>" }
  ],
  "term_proposals": [
    { "source": "<JP term>", "target": "<your target>", "category": "place|character|item|game_term|organization|...", "note": "<reasoning>" }
  ]
}
```

- `translations` must contain one entry per listed `entry_id` you translated;
  no invented IDs and no duplicates. `name` is non-empty exactly when
  `jp_name` is non-empty; `dialog` is one complete box or choice button.
- `term_proposals` is optional: every new or contested term decision in this
  batch, with its category and reasoning. `apply` records proposals in
  `translation/<locale>/term-proposals.json` (`status: proposed`), merges the
  accepted ones into the chapter ledger (first decision per source wins;
  conflicting later targets are reported), and the reviewer later promotes
  reviewed entries into `term_builder`.
- The fields contain only the translation: no notes, labels, or explanations.

## Deep work: per-batch analysis before writing

Do all of this before translating the batch, and re-check before writing the
response file:

1. **Scene and speaker.** What scene (`scene_id`) is this, who is speaking, to
   whom, in what situation? Read `previous_context` and the surrounding rows.
2. **Term audit.** List every proper noun, NP/skill name, place, item, and
   recurring expression. For each: shipped glossary? chapter ledger? new?
   Read the glossary files themselves when a name matters:
   `term_builder/character_names.tsv` (`jp_name`, `cn_name`, `en_name`,
   `gender`, `aliases`) for people, `term_builder/term.tsv` for terms, and
   `app/src/main/assets/translation/traditional_glossary_overrides.tsv` for
   reviewed zh-Hant spellings. The batch glossary only carries the rows that
   matched that row's text and is capped, so an absent target is not evidence
   that the term is new.
3. **Register and voice.** Which honorifics, sentence endings, and pronouns
   carry tone? Which ones are just speech habit? Render voice when the
   Japanese carries it; do not invent personality.
4. **Meaning risks.** Mark rows with ambiguity, implied subjects, benefactive/
   causative direction, irony, wordplay, or repetition for effect. Decide from
   context; if the context does not decide, choose the most natural reading and
   put the `entry_id` in your report.
5. **Per-row decisions.** Translate row by row, then reread the batch as a
   conversation and fix inconsistencies (names, terms, tone, reference chains).
6. **Self-review against the hard contract.** Every `[%1]` present and
   untouched, `[&…:…]` wrappers intact with both variants translated, ruby and
   emphasis balanced, no kana, no invented speakers, no merged rows.
7. **Write the response file**, including `term_proposals` for every new or
   contested term, then run `check` on that batch file and fix every error
   before starting the next batch. `apply` belongs to the lead; do not run it.

## Hard markup rules

- **`[%1]` = in-game Master name.** `[%1]` (and any `[%N]`) is the placeholder
  where the game inserts the player's Master name. Keep the token
  byte-identical. Never replace it with a name or title such as Fujimaru,
  Master, 藤丸立香, or 御主; the game fills in the current player's name at
  render time. Keep the same number of tokens as the source and do not invent
  new ones.
- **`[&male:female]` = player-gender variant.** The app resolves this control
  from the player's gender setting, so the two sides are meaning-bearing: the
  **first variant is masculine and the second is feminine**. Keep the `[&…:…]`
  wrapper and its single colon and translate **both** variant texts, e.g.
  `[&俺:あたし]` → `[&我:人家]`. The Master pronoun pair `[&彼:彼女]` has a
  fixed rendering: CN/TW `[&他:她]`; EN `[&He:She]` or `[&he:she]` matching
  the sentence case (initial or standalone → capitalized; mid-sentence →
  lowercase). Never drop a variant, swap the two sides, reorder the colon,
  nest brackets, or add text outside the variants.
- **`[&…:…]` is not only a pronoun pair.** Check every wrapper against the
  speaker and the scene before translating it; the common shapes are
  ① player pronouns (`彼/彼女`), ② the player's nickname plus
  honorific (`未来クン/未来チャン` → `未来君/未来酱`, `Mirai-kun/Mirai-chan` —
  the honorific follows the **player's** gender, not the speaker's),
  ③ the player's own speech style in a Master line (interjections
  `ああ/ええ`, sentence endings `だ/の`, copula and verb forms), and
  ④ gendered adjectives or verb endings. The wrapper must not grow: keep
  plural markers, particles, and trailing words outside it exactly where the
  source puts them (`[&未来クン:未来チャン]たち` → CN `[&未来君:未来酱]他们`,
  EN `[&Mirai-kun:Mirai-chan] and the others`). Adding a plural or a noun
  inside one side silently makes that variant say something the source does
  not.
- **Ruby** `base〈reading〉`: preserve both meanings and their roles. Translate
  the base and the reading as the target language requires; use `〈〉` only for
  ruby. If the translated ruby equals the translated base, output the base only.
  Never invent ruby. Most ruby in a story chapter is plain furigana on an
  ordinary word (`私〈わたくし〉`, `庇〈かば〉`, `埴輪〈はにわ〉`,
  `青城村〈あおぎむら〉`): translate the word normally and drop the pair — on a
  furigana-heavy chapter it is normal for almost every ruby row to be dropped.
  Keep `〈〉` only when the reading carries a second meaning the author uses,
  e.g. `県〈エリア〉`, `星〈ここ〉`, `天使〈天子〉`, `奇兵隊〈オーバーブースト〉`,
  and report which rows you kept. A reading that is an **identity homophone**
  (`忌与〈イヨ〉` for `壱与`) belongs to the plot: keep the base spelling
  distinct, keep the ruby if the target script can show it, and flag in
  `term_proposals` when the target cannot (EN loses the contrast, so
  `忌与` → `Iyo` reads exactly like `壱与`).
- **Kana used as an object.** When the dialogue treats a kana as a written
  character or a letter rather than as a word (`『ね』は『ね』でも…`, a
  character-count gag), render the glyph as an object in the target script
  (`『ne』`, `a/na/ma`) instead of translating its meaning; flag the row.
- **Emphasis** `⟦word⟧`: keep the marker pair around the emphasized word. If
  target word order changes, move the pair rather than dropping it.
- **Line rule bars** `――…` (U+2015): these render FGO's `[line N]` drawn rule,
  N bars long. Keep exactly the same number of bars at the same place in the
  sentence; do not translate, shorten, merge, or drop them.
- **Size marker** `⟬L⟭` / `⟬S⟭`: if one ever shows up at the head of
  `jp_dialog`, it is the source's font-size metadata (the game's `[f large]` /
  `[f small]`). It is not text: never translate it, never copy it into a target
  column, and never move it. Export normally removes it before you see the row.
- Leave **no Japanese kana** in target fields, including inside gender variants.
- Never merge or split rows, add speaker labels, or change `type`.

## Quality rules (app prompt contract as baseline)

- Keep every meaning, role, relationship, uncertainty, ambiguity, repetition,
  and ellipsis; add nothing. Keep fragments and interrupted sentences
  unfinished; never summarize or complete the source.
- Use glossary targets exactly for their listed source meaning. A missing or
  doubtful term is an opportunity to decide and record, not to approximate.
- Speaker names use the glossary character-name target and stay consistent
  across batches.
- Choices are Master/player replies: elliptical, no first-person pronoun that
  the source does not license, no expansion into explanations.
- Unnamed rows stay unnamed; never invent a speaker.
- Preserve stated references and action direction; do not infer omitted
  subjects, possession, or relationships from speaker identity.

## Validation is authoritative

`check` and `apply` share one validator. `check` never writes the TSV, so use it
after every batch; `apply` (lead only) is all-or-nothing per locale: a single
invalid row rejects the whole response set, exactly as one unbalanced ruby pair
once blocked an entire locale. Both reject: unknown or duplicate entry IDs,
locale mismatch, missing/empty dialogue, choice with a name, changed `[%N]`
Master-name tokens (`master-name-placeholder-changed`), malformed or missing
`[&…:…]` gender controls (`gender-controls-changed`,
`gender-control-malformed`), unbalanced ruby or emphasis markers, remaining kana
(including untranslated gender variants), using the wrong official server's
script (`traditional-chars-in-cn`, `simplified-chars-in-tw`), or an untranslated
row. They warn on dropped/invented ruby, changed emphasis count, suspiciously
long dialogue, Han characters in English, Chinese dialogue with no Han, and the
other server's reviewed name forms (`tw-name-form-in-cn`, `cn-name-form-in-tw`).
`check` additionally flags cross-batch term conflicts, speaker-name conflicts,
and `source-private-use-glyph` anomalies; the term/speaker conflicts are errors,
so resolve them (use the first accepted target, report a change of mind) before
handing off.

Fix the response and re-run `check`. Never edit the TSV by hand. If a batch is
ambiguous, translate what is clear, report the affected `entry_id` values, and
flag source anomalies instead of guessing their meaning.

Two caveats when reading `check` output: `missing_from_required` counts the whole
chapter, so a single-batch check legitimately reports thousands of missing rows;
and the `errors`/`warnings` arrays are truncated at 50 entries, so run the check
per batch file when a report needs complete lists.

## File hygiene on this machine

- Write response JSON as UTF-8 without a BOM. On Windows, never create it with
  PowerShell redirection: `>`, `Out-File`, and `Set-Content` write UTF-16LE with
  a BOM, and `JSON.parse` then fails during `check` although the file looks fine
  in an editor.
- The console code page mangles CJK: `Get-Content`, `Select-String`, or an echo
  can show mojibake for a perfectly valid file. Read files with a UTF-8 aware
  tool, and run analysis with a `node` script that names `utf8`.
- Keep scratch analysis inside the locale folder and out of `responses/`; the
  validator reads every `*.json` in that directory.

## Checkpoints and status

- Work one batch at a time and translate every entry in it; partial coverage is
  only possible through the lead's `apply ... --allow-partial`, not through a
  half-finished handoff. A batch is done when its response file passes `check`.
- `apply` records `status: ai_draft` per entry in
  `work/event-<id>/translation/<locale>/applied.json`, with model and harness,
  appends `term_proposals` to `term-proposals.json`, merges accepted terms into
  `terms.json`, and refreshes every locale manifest so sequential applies do not
  go stale.
- `apply` is idempotent per row: a second run keeps already translated rows
  (`already-translated-row-kept`) and reports `applied_rows: 0`, which is not a
  failure. Use `--replace` only when you intend to overwrite existing text.
- Report per locale: batch numbers completed, `check` result (errors/warnings),
  `applied_rows`, pending rows from `status`, new/contested terms and their
  targets, dropped-ruby counts, and any open `entry_id` values. Keep dialogue
  out of chat.
- Official regional text, when Atlas has the localized event, outranks drafts;
  record its source. Review provenance belongs in the build metadata.

## Boundaries

- No Android edits, device actions, publication, or catalogue changes.
- Do not modify JP columns, row order, `entry_id`, or `type`.
- Do not run `prepare`/`build` after translation without re-applying: a rebuild
  regenerates an empty-target TSV.
- Public release still requires redistribution review and owner approval.
