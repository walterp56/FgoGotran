# FgoGotran Story Library Design

## Scope

Story Library is a new feature inside FgoGotran. A user selects a story,
downloads its database, and taps **Apply**. Story Mode matches text from OCR
against the active database and renders the selected language in the overlay.
The existing Chapter feature has its own workflow and is outside this design.

The first pack is the full JP event **真冬の水着スペシャル ぐだぐだぷにぷにウォーズ！
～史上最低の爆弾娘～**. Its Story Library ID is `event-80639` and its Atlas event
ID is `80639`. Cover the prologue through the epilogue and any event-linked
bonus or raid scenes that contain unique story dialogue. Validate the full pack
offline and test only one accessible section in FGO. The event ID and schedule
are verified. Atlas's JP war metadata resolves this event to 39 script
occurrences and 39 distinct script IDs; `stage` confirms that inventory on each
run. JP script text comes from the raw script URLs that Atlas publishes.
[Atlas event index](https://apps.atlasacademy.io/db/JP/events) ·
[Official event schedule](https://news.fate-go.jp/2026/10/2026gudaguda/)

JP is the source. The target locales are Simplified Chinese (`zh-Hans`),
Traditional Chinese (`zh-Hant`), and English (`en`). Check official regional
text in Atlas CN, TW, and NA, respectively. Measure coverage per target locale
from the actual source inventory.

## Data and catalog

| Artifact | Contract |
| --- | --- |
| Chapter TSV | UTF-8 editable source named `<story-library-id>.tsv`. The pilot file is `event-80639.tsv`. One logical row represents a complete Japanese dialogue box or choice button. |
| Chapter SQLite database | One read-only multilingual pack per chapter. Store chapter metadata, the Japanese entries and search keys, and translations keyed by entry ID and locale. |
| Catalog TSV and generated manifest | Stable chapter ID, localized titles, category, latest flag, pack version, download path, SHA-256, and status and coverage per locale. The app reads the generated manifest. |
| Private build records | `source-inventory.json` holds source hashes, per-row entry IDs and scene structure, and translation provenance. The validation report records unresolved cases and check results. Keep these files in private build storage with version-control exclusions. |

Chapter TSV header, shown as tab-separated columns.

```tsv
jp_name	jp_dialog	cn_name	cn_dialog	tw_name	tw_dialog	en_name	en_dialog	type	script
```

- The Japanese agent fills `jp_name`, `jp_dialog`, and `type` and leaves the
  six target-language fields empty. `type` is `dialogue` or `choice`. Narration
  uses `dialogue` with an empty name. Choices also use an empty name.
- `script` identifies the subchapter (one Atlas 劇情文本 id, e.g. `9416400221`).
  The inventory remains the authority: `build-story-db.mjs` and
  `story-translation.mjs` fail when a row's `script` differs from its
  `source-inventory.json` counterpart, so a reordered or hand-edited table can
  never silently join another scene. The column is the last one so every
  existing column index stays valid.
- In `source-inventory.json`, map each logical data row, numbered from one
  after the header, to its stable `entry_id`, quest, phase, script and slot,
  scene and position, and any branch, choice group, and choice slot. Preserve
  separate occurrences of a shared script. An opening script may have empty
  quest and phase fields when no quest owns it.
- Derive `entry_id` from the script occurrence and logical entry locator. Keep
  it stable for repeat builds of the same source. Record a fingerprint of
  the Japanese fields and `type` with each row mapping. Agents merge by entry
  ID, and the builder emits TSV rows in the inventory's story order. When
  upstream changes the structure, verify the mapping before carrying forward
  translations.
- Use tab-delimited quoting for embedded tabs, quotes, and line breaks.
  Preserve intentional line breaks and punctuation. The builder or loader
  converts `base〈reading〉` and `⟦emphasis⟧` to the overlay's annotation
  representation. Preserve player-name and gender controls as templates.
- Store target status (`official`, `ai_draft`, `ai_reviewed`, or `missing`)
  and review provenance in the build metadata. Derive coverage and completion
  separately for each target locale.

## Build and agent handoffs

The Japanese agent works in two phases with one approval gate. Run both
commands from the FgoGotran root with Node.js.

**Phase 1 — identify the chapter (read-only).** Given a JP chapter name:

```powershell
node story_builder/story-library.mjs lookup "<JP chapter name>"
```

`lookup` reads the Atlas JP event index in memory, writes nothing, and prints
candidate `atlas_event_id`, `type`, `name`, `war_ids`, and `started_at` values
with `eventQuest` entries first. The agent confirms the single `eventQuest`
match, reports the Atlas event ID, Story Library ID, and proposed paths, then
waits for the user's `Approve <atlas-event-id>`.

**Phase 2 — approve and build the TSV.** After approval:

```powershell
node story_builder/story-library.mjs prepare 80639
```

`prepare` saves the Atlas event and war inventory in `work/atlas-event-80639.json`,
downloads one UTF-8 `<script-id>.txt` per distinct script ID from the
`static.atlasacademy.io` script URLs published in the war metadata into
`work/sources/event-80639/`, and publishes `work/event-80639/event-80639.tsv`,
`source-inventory.json`, and `validation.json`. It is idempotent
(`status: already_prepared`) and rebuilds incomplete output. The work folder is
Git-ignored, and the command's JSON is the handoff record.

Maintenance commands: `stage` stops after the source handoff, `inventory`
writes metadata only, and `build 80639 <local-source-dir>` re-converts
already-staged sources without downloading. Do not source JP script text from
any provider other than Atlas.

The converter handles the line-based speaker, dialogue, choice, ruby, and
template syntax exercised by the fixtures, plus the markup Atlas publishes in
the JP raw script: `[#base:reading]` ruby, `[#word]` emphasis, `[#rrggbb]`,
`[-]`, `[line N]`, `[r]`, `[sr]`, `[&male:female]` and `[%N]` templates, inline
stage commands, `=spot[...]` speaker metadata, and `[backlogStart]` narration
boxes. `[line N]` is a draw command, not a character: the game paints a rule N
character-widths long, so the table stores it as N `―` characters inline
(`[line 3]『禁門』だ。` → `―――『禁門』だ。`). Normalization removes the bar
characters again, so the exact `jp_norm`/`jp_hash` stay unchanged and OCR that
does or does not capture the drawn rule hashes the same. Unsupported visible
syntax and missing files stop the build. Errors identify the affected script or
line without showing dialogue.
[Atlas API documentation](https://api.atlasacademy.io/docs)

## Translation stage

Three locale agents fill the TSV target columns from the canonical Japanese
rows, one locale per agent: [Simplified Chinese](translation-agent-cn.md),
[Traditional Chinese](translation-agent-tw.md), and
[English](translation-agent-en.md). All three share
[the translation workflow](translation-agent.md), which distills the app's
translation prompts (`PromptBuilder.kt`, `TranslationGlossaryBuilder.kt`) and the
`term_builder` glossary. When one locale is split across parallel workers, each
worker also reads [the worker brief](translation-worker-brief.md) and the
orchestrator keeps the speaker-name and term tables that bind them.

```powershell
node story_builder/story-translation.mjs export 80639 cn
# translate the batches under work/event-80639/translation/cn/, saving responses
node story_builder/story-translation.mjs check 80639 cn   # read-only validation
node story_builder/story-translation.mjs apply 80639 cn story_builder/work/event-80639/translation/cn/responses
node story_builder/story-translation.mjs status 80639
```

`export` writes `manifest.json` and `batch-XXX.json` in story order as compact
rows (`format: "compact-v1"`: `entry_id`, `type`, `jp_name`, `jp_dialog`, and
indexes into a batch-level glossary — about 190 bytes/row instead of ~710 for
the older object-per-row shape), plus scene ranges, the pending entry IDs, and
the previous six rows for continuity. A batch never straddles a subchapter: it
is one `script` (or one equal part of it when it exceeds `MAX_BATCH_SIZE = 60`
rows), it lists that id in `scripts`, and a split scene adds
`script_part: [part, parts]`. `previous_context` therefore only ever reaches
back inside the same scene; a batch that opens a scene starts with none. The
pilot pack (4,521 rows, 39 scripts) exports as 94 batches instead of 114 fixed
40-row windows, none of which splits a scene across workers. `--pretty` indents
the same fields for human reading. The locale agents work offline and deep: translation
quality is the only budget, the app prompt (`PromptBuilder.kt`) is a style
reference rather than a template, and the shipped glossary is advisory because
newest chapters introduce terms the DB does not know yet. New terms are decided
from context and lore and recorded as `term_proposals` in responses. `check`
runs the same validator as `apply` without writing the TSV; it reports per-batch
errors, warnings, cross-batch term and speaker-name conflicts, source anomalies
(private-use glyphs), and dropped-ruby rows, so agents can self-check each batch
before handing off. `apply` validates responses (entry IDs, `[%N]` Master-name
tokens, `[&male:female]` gender variants, ruby, emphasis, kana, coverage), merges
them into `cn_*`, `tw_*`, or `en_*`, records `ai_draft` status with model and
harness in `translation/<locale>/applied.json`, appends `term_proposals` to
`term-proposals.json`, merges accepted terms into the per-locale chapter ledger
(`translation/<locale>/terms.json`, first decision per source wins), and
refreshes every locale manifest so sequential cross-locale applies do not go
stale. `[%1]` is the in-game Master name slot and must stay byte-identical;
`[&male:female]` keeps its wrapper with both variants translated. `status`
reports coverage per locale. A rebuild (`prepare`) regenerates an empty-target
TSV, so re-apply responses after one. When Atlas has the localized event,
official regional wording outranks drafts and is recorded as provenance.

### Official server fit

Each locale agent must produce text that fits its official server build, not a
generic translation: mainland **CN** (Simplified, 御主/从者/宝具…), Taiwan **TW**
(Traditional, 御主/從者/寶具…, reviewed
`traditional_glossary_overrides.tsv` spellings, no mainland 中國用語), and **NA**
(official `Altria Pendragon`, `Mash Kyrielight`, Magecraft/Noble Phantasm, Title
Case NPs). The in-repo `term_builder` glossary and the TW override table are the
mandatory anchors; newest-chapter terms are extrapolated in the same style and
recorded as `term_proposals`. `check`/`apply` enforce this: wrong-script targets
are errors (`traditional-chars-in-cn`, `simplified-chars-in-tw`) and the other
server's reviewed name forms warn (`tw-name-form-in-cn`,
`cn-name-form-in-tw`). Research and agent-design references are listed in
[the translation workflow](translation-agent.md#references).

**Five specialist agents, or six including the coordinator.**

| Count | Role | Responsibilities |
| ---: | --- | --- |
| 1 | [Japanese script preparation agent](japanese-script-preparation-agent.md) | Run the two-phase flow: identify the Atlas event ID read-only, then after approval stage the Atlas JP scripts and hand off the validated TSV and source inventory. |
| 3 | [CN](translation-agent-cn.md), [TW](translation-agent-tw.md), and [EN](translation-agent-en.md) language agents | Review official alignments and translate missing rows using the canonical JP TSV and the project glossary. Work by scene and locale with checkpoints keyed by entry ID; responses merge through `story-translation.mjs`. |
| 1 | Independent reviewer | Check the JP conversion and target meaning, terminology, speaker identity, scene order, choices, and markup. Return issues by entry ID. |

The Japanese agent runs the importer and parser, reviews ambiguous conversions,
and hands off a validated TSV and source inventory. The language agents can
then work in parallel. Deterministic tools merge approved entries by ID,
validate the multilingual TSV, and generate SQLite and catalog artifacts. The
coordinator verifies the handoffs and reports counts, IDs, and issue types to
the owner. Official translations keep their wording. Generated translations
carry their review state. [Project glossary format](../term_builder/README.md)

## Story Library packs (CDN)

The translated TSV is packaged as one SQLite database per chapter and published
for the app's Story Library page: a menu of chapters, one download per selected
chapter, and offline rendering instead of API translation. This is separate from
`story_assets/` and from the app's native-asset `story/` chapter folder, which
replaces FGO game assets and is a different feature.

**`story_builder/catalog.tsv` is the menu mapping.** One row per chapter:

| Column | Meaning |
| --- | --- |
| `chapter_id` | Story Library ID, e.g. `event-80639` (also the chapter folder name) |
| `jp_title` `cn_title` `tw_title` `en_title` | Menu titles per locale (single line, no tabs) |
| `category` | `Main Story`, `Events`, or `Interludes` |
| `latest` | `✓` when the chapter belongs to the newest set (several rows may be ✓); blank otherwise |
| `status` | `finished` or `progressing` |
| `order` | Menu sort key, newest first (use the event start date) |

`status: finished` is enforced against the data: the builder fails if any locale
is incomplete. While an event releases day by day, keep `progressing` and bump
the content version as new scripts are added.

```powershell
# 1. Build the chapter database from the applied TSV (writes work/<id>/<id>.db)
node story_builder/build-story-db.mjs event-80639
node story_builder/build-story-db.mjs event-80639 --check        # validate only

# 2. Package it and regenerate the menu catalog
node story_builder/package-story-release.mjs event-80639 --version 2026.11.24.1
node story_builder/package-story-release.mjs event-80639 --check   # verify published files

# 3. Or build + package + optionally publish in one step
.\scripts\release-story.ps1 -ChapterId event-80639
.\scripts\release-story.ps1 -S3Uri s3://YOUR_BUCKET -CloudFrontDistributionId YOUR_DISTRIBUTION_ID
```

Output mirrors the terminology DB release:

```text
release/cdn/story/<chapter-id>/releases/<version>/story.db
release/cdn/story/<chapter-id>/releases/<version>/story.db.sha256
release/cdn/story/<chapter-id>/latest.json
release/cdn/story/latest/catalog.json        # the app menu mapping
release/cdn/story/latest/announcements.json  # the Story Library announcement board
```

Upload the versioned chapter files first, then `story/<id>/latest.json`, then
`story/latest/announcements.json`, then `story/latest/catalog.json` last. The app
compares `contentVersion` with the installed pack: a newer version is what the
**Update** button re-downloads after a day-by-day sub-chapter release.

### Announcement board

`story_builder/announcements.json` is the board source: an array of
`{id, title, body, date, level}`, where `title`/`body` are either a plain string
or `{"en", "zh-Hans", "zh-Hant"}` and `level` is `info`, `success`, or `warning`.
The app shows the entries at the top of Story Library, in file order, and a
failure to fetch keeps the previous board rather than hiding it.

```powershell
# rewrite catalog.json + announcements.json only; no chapter database is touched
.\scripts\release-story.ps1 -CatalogOnly
.\scripts\release-story.ps1 -CatalogOnly -S3Uri s3://YOUR_BUCKET -CloudFrontDistributionId YOUR_DISTRIBUTION_ID

# the same two files by hand
node story_builder/package-story-release.mjs --catalog-only
node story_builder/package-story-release.mjs --check      # names a stale board
```

`--check` fails when the published `announcements.json` no longer lists the same
ids as the source, so an edited board is never silently skipped at upload time.
A normal chapter release also regenerates both files.

Database schema (`PRAGMA user_version = 1`), one file holding all three locales:

```text
meta(key,value)        chapter ids and titles, category, latest, status, schema,
                       normalization, entry counts, coverage per locale,
                       source TSV/inventory SHA-256, content_version
scripts(script_id, quest_id, phase, script_slot, first_seq, last_seq)
entries(entry_id, seq, script_id, scene_id, position, type,
        branch_id, choice_group, choice_slot,
        jp_name, jp_dialog, jp_norm, jp_hash,
        cn_name, cn_dialog, tw_name, tw_dialog, en_name, en_dialog)
script_openings(script_id, seq, jp_norm)     -- wrong-chapter detection
```

`seq` is the canonical story order, so the app can follow the flow entry by
entry; `jp_norm`/`jp_hash` use the same normalization contract as the app
(`fgogotran-ocr-v2`: male gender side, `%N` name token, ruby readings dropped,
NFKC, no spaces, punctuation, or bar characters `― — ─ ━`), so an exact lookup
is one hash comparison, with scene/position as the fallback and
`script_openings` for “this is not the selected chapter”. The app never falls
back to the API while a chapter pack is applied; that switch is app-side.

The app also folds the dash-width family (`～`, `〜`, `~`), the horizontal-bar
family (`—`, `―`, `ー`), the long-vowel mark OCR reads as `一`, and a small
katakana promoted to its full-size form (`スラニョっ` captured as `スラニヨっ`)
onto one approximate lookup key. That key is app-side only: stored `jp_hash`
values are unchanged, so packs built before the rule keep matching exactly, and
an approximate hit is rendered exactly like an exact one. Hiragana is not
folded; small katakana are the capture artifact that matters. Speaker plates
use the same fold on `jp_name`, because the short names that need it most
(`ノッブ`, `カッツ`, `マシュ`) are below the approximate lookup's minimum.

The same small-katakana fold (`app/src/main/java/com/fgogotran/text/KanaFold.kt`)
keys the character-name and term lookups, so a plate read as `ノツブ` still
reaches the stored `ノッブ` on the dialogue, suffix, and glossary paths. The
glossary repair restores the stored spelling inside a matched form as well
(`ジヤンヌ・ダルク` → `ジャンヌ・ダルク`); a repair changes the request, so it
carries its own cache policy (`ocr-glossary-v2`).

## Android flow

1. **Select.** Story Library shows **Main Story**, **Events**, and **Interludes**.
   **Newest** filters the same catalog. Each view uses the same `chapter_id`.
   For the first event, that ID points to a versioned file such as
   `story/event-80639/v1/story.db`.
2. **Download and apply.** Verify the checksum, supported schema, source region,
   chapter ID, and version before **Apply** activates the staged pack. **Update**
   checks the catalog and activates the replacement only after validation.
   Keep the active pack usable during an incomplete update.
3. **Select the translation source.** Route the complete Story Mode scene at
   its entry point. Dialogue, names, choices, retries, and translation-provider
   voice hints follow the selected mode. The API provider is reachable only in
   **API** mode. **Chapter database** mode resolves the scene locally.
4. **Render.** Pass local hits to the existing overlay and history. Preserve
   Accessibility OCR, MediaProjection voice behavior, and the LOG's fresh
   `「 」` frame around verbatim speaker dialogue. Leave the original visible
   on a miss. Distinguish missing target coverage from repeated complete-frame
   misses that suggest the user should check the selected chapter.

[Story Mode entry point](../app/src/main/java/com/fgogotran/accessibility/FgoAccessibilityService.kt) ·
[Overlay emphasis codec](../app/src/main/java/com/fgogotran/text/EmphasisText.kt)

## Matching and performance

- Index `(type, normalized_jp)` for exact lookup in the active pack. Use a
  shared, versioned normalization contract in the builder and app. Normalize
  known OCR formatting differences and preserve meaning-bearing punctuation.
  Match player-name and gender variants explicitly.
- A stored `[%1]`/`[%N]` slot survives normalization as a `%N` token, but the
  screen shows the filled name, so both the exact hash and the Dice score miss
  (`カルデア学院からきた[%1]でーす` scored 0.73 against the 0.78 choice
  threshold). Reduce both sides to the slot-free template instead: the capture
  with the configured player name removed, the row with its `%N` tokens removed.
  Exact template first, then the normal approximate rule, and never across line
  types. No pack rebuild is involved; the template comes from `jp_norm`.
- A frame carries both plates and a matched row carries both answers, so a line
  that resolved also decides its speaker. A damaged or unknown short plate must
  never leave Japanese, or another character, next to a line that came from the
  pack; the plate decides only when the line itself misses. The OCR speaker is
  already evidence inside the approximate lookup and the exact-duplicate rule.
- A frame only matches its own kind. Punctuation is not part of the key, so
  `だって……、` (dialogue) and `だって……！` (choice) share one hash; the dialogue
  lookup, the flow, the scene window, the `[%N]` template index, and the
  approximate index all exclude `type = 'choice'`, and a choice frame is matched
  as a choice before any dialogue fallback. Otherwise a button's wording could
  appear in a dialogue box, or the reverse.
- Index `(scene_id, position)` for nearby entries. Wait for a complete box.
  Use the OCR speaker when reliable, recent confirmed dialogue, and the ordered
  choice group to resolve duplicates. A distinctive exact match can re-establish
  the position after a skip or replay.
- After an exact miss, score character-bigram sets (Dice) against `jp_norm` with
  the folded key. Thresholds: dialogue 0.72, choice 0.78, speaker name 0.75; keys
  shorter than four code points must match the folded key exactly. A candidate
  wins only when it beats every rival by 0.04, or when all close rivals render
  the same line; recent `script_id`/`seq` breaks real duplicates. Otherwise the
  original text stays.
- While a script is confirmed, story order is the stronger prior: score the next
  six dialogue entries (choices excluded) with OCR coverage as well as Dice. A
  half-read next line is accepted at 0.45; skipping the immediate next line needs
  0.60 and a 0.10 lead over any rival that renders differently. A miss leaves the
  position untouched, so the next good line re-syncs instead of shifting the flow.
- Lines shorter than four code points carry too few bigrams to score. Inside the
  window an exact folded short line wins wherever it appears in the window;
  otherwise the immediate next line may absorb a single glyph error (the captured
  `ほ一ん` against the source `ほーん`). Two-character lines still need an exact
  match, and short lines never use this rule outside a confirmed script.
- Abstain when multiple candidates remain plausible. Short lines need strong
  context. Reset progress when the chapter, pack version, or source mode changes.
  The **Tolerant OCR matching** switch in Story Library turns the approximate
  stages off; they are on by default.

Measured on the built `event-80639` pack (4,170 dialogue rows, 213 choices) on a
JVM: the bigram index builds in ~50 ms, each whole-pack lookup scans in ~1 ms,
and 137 single-character OCR corruptions resolved 96.4% with zero wrong
translations. Simulated against the scene window: a half-read next line resolved
98.4%, one dropped character in three resolved 98.6%, a 12-line chain with one
damaged line held 99.1% with zero wrong text, and unrelated text inside a foreign
script window matched 0.35%. Short lines (four code points or fewer) with one
substituted character resolved 96.6% with zero wrong text and no foreign-script
accept. Android device latency is still to be measured.
[SQLite query planner](https://www.sqlite.org/queryplanner.html)

## Acceptance and delivery

1. **Full-event offline check.** Account for all expected script occurrences.
   Validate TSV round-tripping, IDs, branches, choice order, template controls,
   annotations, SQLite integrity, and per-locale coverage. Exercise exact hits,
   duplicates, missing locales, and ambiguity with synthetic fixtures. Mark a
   target language complete only after verified event coverage and review.
2. **One-section live check.** Use the regular apply-to-overlay flow with an API
   key configured. Check 10–20 complete boxes if the section permits. Record
   correct matches, misses, wrong matches, and OCR-to-overlay latency. Require
   correct output for the expected lines, zero wrong matches, and zero
   translation-provider requests. Use a synthetic choice fixture if the live
   section has no choices.
3. **Delivery.** Start with a local pack. For an approved public release,
   confirm redistribution rights, upload the immutable pack first, and publish
   the catalog last. A staged chapter update reports coverage of unlocked,
   reviewed sections by locale.
   [CDN release pattern](../term_builder/README.md#cdn-release-package) ·
   [FGO scenario guidelines](https://static.fate-go.jp/guidelines/)

This is a design document. Running the agent, implementing the feature, and
publishing are separate actions.
