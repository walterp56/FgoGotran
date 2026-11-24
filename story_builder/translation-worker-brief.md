# Translation worker brief (`compact-v1` batches)

A compact restatement for a worker that translates a **range of batches** for one
locale. Read it together with the locale playbook
([cn](translation-agent-cn.md), [tw](translation-agent-tw.md),
[en](translation-agent-en.md)); the full shared workflow is
[translation-agent.md](translation-agent.md). If this brief and a playbook
disagree, the playbook wins — and tell the orchestrator.

## The job

One response JSON per batch, written to
`work/event-<id>/translation/<locale>/responses/batch-XXX.json`, and every batch
must pass `check` before you start the next one. `apply` belongs to the lead:
never run it.

```powershell
node story_builder/story-translation.mjs check <event> <locale> <response file>
```

## Batch format (`format: "compact-v1"`)

```text
{event_id, story_library_id, locale, batch, batch_count, source_tsv_sha256,
 format, fields, scenes, pending, controls?, glossary, previous_context,
 existing?, entries}
```

- `entries`: story-order rows, a 5-tuple in `fields` order —
  `[entry_id, type, jp_name, jp_dialog, glossary_indexes]`.
- `glossary_indexes` point into the batch-level `glossary`
  (`{source, target, category, note}`). A term appears once per batch; use the
  target when the Japanese row carries that meaning.
- `pending`: `"all"` when every row needs both fields, else the `entry_id`s that
  need work. Never rewrite a filled field; on a resumed chapter `existing` shows
  what is already there.
- `scenes`: `[scene_id, first_entry_number, last_entry_number]`, 1-based in the
  batch.
- `controls`: present only when tokens exist, as counts — handle them with the
  markup contract below.
- `previous_context`: last six rows of the previous batch, for continuity only.

## Response file

```json
{
  "event_id": 80639,
  "locale": "cn",
  "translations": [
    { "entry_id": "<exact id from the batch>", "name": "<target name>", "dialog": "<target text>" }
  ],
  "term_proposals": [
    { "source": "<JP term>", "target": "<your target>", "category": "character|place|item|game_term|organization", "note": "<why>" }
  ]
}
```

- One item per `entry_id` you translated; no invented IDs, no duplicates, no
  extra keys needed.
- `name` is non-empty exactly when `jp_name` is non-empty; choices never get a
  name. `dialog` is one complete box or choice button.
- Fields hold the translation only — no notes, labels, romaji, or explanations.

## Markup contract (a violation rejects the whole locale)

- `[%N]`: in-game Master name. Byte-identical, same count. Never 御主 / 藤丸立香 /
  Master / Fujimaru.
- `[&male:female]`: player-gender control, resolved from the player's gender.
  First side masculine, second feminine. It is **not only pronouns**: it also
  wraps the player's nickname plus honorific (`未来クン/未来チャン` →
  `未来君/未来酱`, `Mirai-kun/Mirai-chan`; the honorific follows the *player's*
  gender), the player's speech style in a Master line (`ああ/ええ`, `だ/の`), and
  gendered verb forms. Keep the wrapper from growing: plural markers, particles,
  and trailing words stay outside exactly as in the source.
- Ruby `base〈reading〉`: use `〈〉` only for ruby, never invent it. Most ruby is
  furigana on an ordinary word (`私〈わたくし〉`, `埴輪〈はにわ〉`) — translate the
  word and drop the pair. Keep it only when the reading adds a meaning
  (`県〈エリア〉`, `天使〈天子〉`). An identity homophone (`忌与〈イヨ〉` for
  壱与) is plot: keep the distinct base spelling and flag it in `term_proposals`.
- Kana used as a written object (`『ね』`) is a glyph, not a word: render the
  glyph (`『ne』`), do not translate its meaning.
- `⟦…⟧`: keep the emphasis pair around the emphasized word.
- `――…` (U+2015): this is FGO's `[line N]` drawn rule; keep the exact bar
  count and position, and never translate it.
- No kana anywhere in the target, including inside gender variants.
- Never merge or split rows, invent a speaker, or change `type` / `entry_id`.
- Keep fragments and interrupted sentences unfinished; keep `……` and `───`;
  add no meaning and drop none.

## Locale alignment

- **cn**: Simplified only, mainland official terms (御主, 从者, 宝具, 令咒,
  迦勒底, 特异点, 异闻带), `·` between foreign-name parts, official name forms
  such as 玛修·基列莱特, 松永弹正. Traditional characters are an error.
- **tw**: Traditional (zh-Hant) only, official zh-Hant terms and the reviewed
  spellings in `app/src/main/assets/translation/traditional_glossary_overrides.tsv`
  (`reviewed zh-Hant override` in a glossary note means copy it exactly); never
  mainland 中國用語 or Simplified forms. Simplified characters are an error.
- **en**: NA official spellings — `Altria` (never Artoria), `Mash Kyrielight`,
  `Chaldea`; Title Case for Noble Phantasms and skills, sentence case dialogue;
  `「」` → `"..."` with nested `'...'`; no kana and no Han characters.

## Names and terms

- The authoritative files are `term_builder/character_names.tsv`
  (`jp_name`, `cn_name`, `en_name`, `gender`, `aliases`), `term_builder/term.tsv`,
  and the zh-Hant overrides above. The batch glossary only carries rows that
  matched that row and is capped, so a missing target is not proof the term is
  new.
- If the orchestrator gives you a speaker-name table or a chapter term table,
  they are **binding**: use those targets exactly.
- A name or term you must decide: pick one target in the locale's official
  convention, record it in `term_proposals`, and say so in your report. First
  accepted target wins for the whole chapter; never silently change one.
- Do not trust a glossary row blindly: mixed scripts, machine-like wording, or
  a form that contradicts the official server gets the official form plus a
  `term_proposals` correction. Community slang is never official wording.

## File hygiene (Windows)

- Write response JSON as UTF-8 without BOM. Do **not** create it with PowerShell
  `>`, `Out-File`, or `Set-Content`: those write UTF-16LE with a BOM and
  `JSON.parse` fails in `check`.
- `Get-Content`, `Select-String`, and echoes can show mojibake for a valid file
  (console code page). Read with a UTF-8 aware tool; run analysis with a `node`
  script that names `utf8`.
- Keep scratch files out of `responses/`: the validator reads every `*.json`
  there.

## Boundaries

- Never run `apply`, `export`, `prepare`, `stage`, or `build`.
- Never edit the chapter TSV, `source-inventory.json`, batch files, manifests, or
  another locale's folder. Write only under
  `work/event-<id>/translation/<locale>/`.
- `check` truncates its error/warning arrays at 50 and reports
  `missing_from_required` for the whole chapter — a single-batch check is still
  `ok: true` with thousands "missing".

## Report back (summary only, no dialogue text)

- batches completed and the `check` result per batch (`ok`, errors, warnings);
- `term_proposals` you added (source → target @ category);
- kept-ruby and dropped-ruby counts, plus the kept `entry_id`s;
- open, ambiguous, or blocked `entry_id`s and why;
- anything unfinished.
