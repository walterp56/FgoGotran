# Japanese Script Preparation Agent

## Mission

Resolve a Japanese chapter name in [Atlas Academy](https://apps.atlasacademy.io/db/),
then hand off the FgoGotran Story Library TSV for that chapter. The work has two
phases with one approval gate between them. For JP events, use the
[Story Library CLI](story-library.mjs). Use the model selected for the session.

## Phase 1 — Identify the Atlas entry (read-only)

1. **Take a JP chapter name from the user.** Ask for the name if it is missing.
2. **Run the lookup.** From the FgoGotran root:

   ```powershell
   node story_builder/story-library.mjs lookup "<JP chapter name>"
   ```

   The command reads the Atlas JP event index in memory and writes nothing. It
   prints candidates with `atlas_event_id`, `type`, `name`, `war_ids`, and
   `started_at`, with `eventQuest` entries first.
3. **Choose the match.** Accept the single `eventQuest` whose title matches
   (whitespace and line breaks are ignored). `questCampaign` entries are reward
   campaigns, not chapters. If several chapter entries match, show the
   candidates and ask the user to choose. If none matches, report the query and
   distinguish a missing match from an unavailable source.
4. **Report in chat.** Give the JP title, category, Atlas event ID and war IDs,
   source link, Story Library ID (`event-<atlas-event-id>`), proposed metadata
   path, Japanese source folder, and proposed TSV path.
5. **Wait for approval.** End the phase with nothing written. The user reviews
   the IDs and replies `Approve <atlas-event-id>`.

## Phase 2 — Approve and produce the TSV

When the user approves the ID identified in Phase 1:

```powershell
node story_builder/story-library.mjs prepare <atlas-event-id>
```

`prepare` saves the Atlas event and war inventory in
`story_builder/work/atlas-event-<id>.json`, downloads one UTF-8
`<script-id>.txt` per distinct script ID from the `static.atlasacademy.io`
script URLs published in the war metadata, validates them, and publishes the
chapter output:

```text
story_builder/work/event-<id>/event-<id>.tsv
story_builder/work/event-<id>/source-inventory.json
story_builder/work/event-<id>/validation.json
```

It is idempotent: a complete output returns `status: already_prepared` without
redownloading, and incomplete output is rebuilt. The command's JSON is the
handoff record (`script_occurrences`, `distinct_script_ids`, `dialogue_rows`,
`choice_rows`, `jp_files`, and the paths).

Only run `prepare` after approval, and only for the approved event ID. If the
user approves a different ID than Phase 1 identified, treat it as a correction:
re-run the Phase 1 lookup for that ID first if its title is not already proven.

Maintenance commands (`stage`, `inventory`, `build`) exist for partial runs and
re-conversion from already staged sources; they are not part of the two-phase
flow.

## Outputs

| Artifact | Path |
| --- | --- |
| Atlas metadata + script inventory | `story_builder/work/atlas-event-<atlas-event-id>.json` |
| Japanese script sources (39 for the pilot) | `story_builder/work/sources/event-<id>/<script-id>.txt` |
| Chapter TSV | `story_builder/work/event-<id>/event-<id>.tsv` |
| Row/scene mapping and source hashes | `story_builder/work/event-<id>/source-inventory.json` |
| Build report | `story_builder/work/event-<id>/validation.json` |

The work folder is Git-ignored.

## Boundaries

- Phase 1 is read-only: no downloads are staged and no files are written.
- Phase 2 runs only after approval. One approval covers one event's Atlas
  metadata, script downloads, and TSV conversion.
- The agent runs the importer and parser and reviews ambiguous conversions, but
  translation, Android edits, device actions, and publication have their own
  scope.
- Keep raw dialogue and access tokens out of chat: report titles, IDs, counts,
  and paths only.
- If Atlas omits a script URL or a fetch fails, report the affected event and
  script ID and stop. Do not substitute another provider or hand-edit staged
  script text.
- If the parser rejects new Atlas markup, report the affected script and line
  instead of guessing its meaning.

## Completion and reporting

A complete handoff accounts for every expected script occurrence and every TSV
row. In chat, report HEAD and dirty paths, approved scope, model and harness,
Atlas revision, output paths, `jp_files`, `dialogue_rows`, `choice_rows`, and
any pending IDs. The staged `.txt` files are raw script text, so quote counts
and IDs, not their content.
