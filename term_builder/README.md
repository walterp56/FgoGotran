# FGO Terminology Builder

This folder contains the editable glossary sources and scripts used to build the terminology database downloaded by the Android app.

## Editable TSV Files

Edit these files when adding or correcting translations:

```text
term_builder/character_names.tsv
term_builder/term.tsv
```

The TSV files are saved as UTF-8 with BOM so spreadsheet tools can open Japanese and Chinese text correctly. If Excel asks during save, keep the file as UTF-8 text. Do not save as ANSI, Big5, or CP950, or Japanese text may become corrupted.

## `character_names.tsv`

Used for deterministic speaker-name translation. Exact Japanese names and aliases are rendered directly without calling the LLM.

Columns:

```tsv
jp_name	cn_name	aliases
FULL_JP_NAME	OFFICIAL_CN_NAME	ALIAS_1,ALIAS_2
```

`aliases` is optional and comma-separated.

Keep full character names here. `build_db.py` automatically adds component records for separator-based names, so a full official row can also become searchable through its name parts. Curated TSV rows still win over generated component rows.

## `jp_cn_name_map.tsv`

Optional helper map for visible FGO speaker/name-box labels. It is generated
from Atlas Academy JP/CN/TW script speaker labels, not servant roster names.

Columns:

```tsv
jp_name	cn_name_simp	cn_name_trad	count
JP_NAME_BOX_LABEL	SIMPLIFIED_CN_NAME	TRADITIONAL_CN_NAME	JP_NAME_BOX_COUNT
```

Dry-run and review changes:

```powershell
python term_builder\py\update_jp_cn_name_map.py
```

Write the TSV and its metadata sidecar after reviewing the printed update
summary:

```powershell
python term_builder\py\update_jp_cn_name_map.py --write
```

The updater counts JP name-box labels from Atlas JP scripts, sorts output rows
by that JP count, and aligns CN/TW labels by the same script id plus speaker
occurrence index. If a CN/TW label is missing or ambiguous, it reports that in
the dry-run summary instead of guessing.

The default source discovers script ids from Atlas `nice_war`, downloads static
script text files, and caches Atlas responses under
`term_builder/atlas_cache/jp_cn_name_map`.

For faster API testing, limit the number of discovered scripts:

```powershell
python term_builder\py\update_jp_cn_name_map.py --max-scripts 20
```

Use more or fewer parallel fetch workers depending on your network:

```powershell
python term_builder\py\update_jp_cn_name_map.py --workers 24
```

Force a fresh Atlas download with `--refresh-cache`, or disable cache reads and
writes with `--no-cache`.

The visible TSV intentionally stays four columns. `count` is the JP name-box
count used to keep high-impact voice-review rows near the top.

## `term.tsv`

Used for terminology RAG and exact term matches in dialogue or choice text.

Columns:

```tsv
jp_term	cn_term	category	aliases
JP_TERM	CN_TERM	place	ALIAS_1,ALIAS_2
```

`category` examples:

```text
place
game_term
class
item
organization
```

`build_db.py` can also add component records for separator-based terms when the Japanese and Chinese text split into the same number of parts. Curated TSV rows still win over generated component rows.

## Build

From the repo root:

```powershell
python -m pip install -r term_builder\requirements.txt
python term_builder\py\ingest_atlas.py --skip-atlas
python term_builder\py\build_db.py
```

Output:

```text
term_builder/fgo_terms.db
```

## CDN Release Package

After `build_db.py` finishes, create the files for `cdn.fgogotran.com`:

```powershell
.\scripts\release-db.ps1
```

Output:

```text
release/cdn/db/zh-Hans/latest/manifest.json
release/cdn/db/zh-Hans/releases/VERSION/fgo_terms.db
release/cdn/db/zh-Hans/releases/VERSION/fgo_terms.db.sha256
```

Upload the versioned `releases/...` files first, then upload `latest/manifest.json` last.

The app checks:

```text
https://cdn.fgogotran.com/db/zh-Hans/latest/manifest.json
```

To publish directly to S3 and invalidate CloudFront in the safe order:

```powershell
.\scripts\release-db.ps1 -S3Uri s3://YOUR_BUCKET -CloudFrontDistributionId YOUR_DISTRIBUTION_ID
```

The script uploads the versioned DB and checksum first, uploads `db/zh-Hans/latest/manifest.json` last, waits for the CloudFront invalidation, then verifies the live manifest content version.

## Voice CDN Release Package

Voice CDN releases package the reviewed runtime voice TSVs together so the
profile table and JP/CN/TW name map cannot update out of sync.

Sources:

```text
term_builder/voice_tune/character_voice_profiles_cn.tsv
term_builder/jp_cn_name_map.tsv
```

Create the files for `cdn.fgogotran.com`:

```powershell
.\scripts\release-voice.ps1
```

Output:

```text
release/cdn/voice/zh/latest/manifest.json
release/cdn/voice/zh/releases/VERSION/voice_data.zip
release/cdn/voice/zh/releases/VERSION/voice_data.zip.sha256
```

Upload the versioned `releases/...` files first, then upload
`latest/manifest.json` last.

The app checks:

```text
https://cdn.fgogotran.com/voice/zh/latest/manifest.json
```

To publish directly to S3 and invalidate CloudFront in the safe order:

```powershell
.\scripts\release-voice.ps1 -S3Uri s3://YOUR_BUCKET -CloudFrontDistributionId YOUR_DISTRIBUTION_ID
```

The script uploads the versioned ZIP and checksum first, uploads
`voice/zh/latest/manifest.json` last, waits for the CloudFront invalidation,
then verifies the live manifest content version.

## Runtime DB Tables

`build_db.py` creates one SQLite DB with two tables:

- `character_names`: `jp_name`, `cn_name`, `aliases`
- `terms`: `jp_term`, `cn_term`, `category`, `aliases`

The APK does not include this DB. The app downloads the latest verified package from:

```text
https://cdn.fgogotran.com/db/zh-Hans/latest/manifest.json
```

## AI Voice Profiles

AI voice speaker routing is generated from `character_names.tsv` so every known character can resolve to a stable draft voice profile.

Edit the manual override file for important speakers:

```text
term_builder/character_voice_overrides.tsv
```

Build the app voice assets from the repo root:

```powershell
python term_builder\py\build_voice_profiles.py
```

Atlas Academy can be used as the source for Servant gender/class metadata, which improves the generated draft voice family:

```powershell
python term_builder\py\build_voice_profiles.py --fetch-atlas
```

To also fill Japanese CV names in the review TSV, fetch the larger lore export:

```powershell
python term_builder\py\build_voice_profiles.py --fetch-atlas-lore
```

Output:

```text
app/src/main/assets/voice/character_voice_profiles_cn.tsv
term_builder/character_voice_profiles.tsv
```

`character_voice_profiles_cn.tsv` is the app runtime file. It uses one row per speaker with `speaker_id`, `aliases`, `gender`, and the `cn_voice_*` Azure values. `term_builder/character_voice_profiles.tsv` remains the review/tuning file with JP and CN fields. Manual overrides always win. Atlas metadata guides only the generated draft rows; tune important characters in `character_voice_overrides.tsv` after listening tests.

## Resumable AI Voice Profile Agent Runs

Use one clean workspace for long Codex voice-review tasks:

```text
term_builder/voice_build_batch/
```

The helper splits multi-speaker labels such as `マシュ＆ダ・ヴィンチ`, generates pack prompts with multiple mini-batches, validates checkpoint TSVs, and only builds the final TSV after the checkpoints are safe.

On this machine, use the bundled Python runtime if `python` is not on `PATH`:

```powershell
& "C:\Users\user\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe" term_builder\py\voice_batch.py status
```

Start or reset the workspace:

```powershell
python term_builder\py\voice_batch.py init
```

Check progress:

```powershell
python term_builder\py\voice_batch.py status
```

Generate the next Codex pack prompt. By default, each manual pack contains 20 speakers:

```powershell
python term_builder\py\voice_batch.py next-pack
```

Generated pack prompts show only `speaker_id`, aliases, count, tier, and identity status. Old app TSV voice choices are not shown to the agent.

Manual quality loop:

```text
next-pack -> paste prompt into Codex -> inspect changed checkpoint TSVs -> status -> next-pack
```

Use a larger pack only after the first few manual runs look good:

```powershell
python term_builder\py\voice_batch.py next-pack --batch-size 20 --batches 5
```

Open the generated prompt:

```text
term_builder/voice_build_batch/prompts/pack_0001.md
```

Paste it into Codex. The prompt tells Codex to process all mini-batches in the pack, checkpoint after every mini-batch, update memory files, and never write the final app TSV.

Validate before building:

```powershell
python term_builder\py\voice_batch.py validate
```

Build the final TSV only after validation is clean and no rows are pending:

```powershell
python term_builder\py\voice_batch.py final
```

The final TSV is built only from `auto_ok_high.tsv + auto_ok_low.tsv`. `needs_review.tsv`, placeholder labels, and combined multi-speaker labels are never included automatically.

## Atlas Official Voice Audio

Atlas servant lore exposes the official FGO character voice MP3 URLs. These files are copyrighted game assets, so keep them local for research/testing and do not commit them or bundle them into releases.

Create a manifest first:

```powershell
python term_builder\py\download_atlas_voice_audio.py --dry-run
```

Download a small sample:

```powershell
python term_builder\py\download_atlas_voice_audio.py --limit-characters 1
```

Download all matched `character_names.tsv` audio:

```powershell
python term_builder\py\download_atlas_voice_audio.py
```

Useful filters:

```powershell
python term_builder\py\download_atlas_voice_audio.py --include-types home
python term_builder\py\download_atlas_voice_audio.py --limit-assets 100
python term_builder\py\download_atlas_voice_audio.py --fetch-atlas-lore
python term_builder\py\download_atlas_voice_audio.py --workers 16
```

Output:

```text
term_builder/atlas_voice_audio/manifest.tsv
term_builder/atlas_voice_audio/unmatched.tsv
term_builder/atlas_voice_audio/failed.tsv
term_builder/atlas_voice_audio/audio/JP_NAME__CN_NAME__SERVANT_ID/VOICE_TYPE/VOICE_ID__LINE_NAME.mp3
```

By default the audio folder uses character names. To keep Atlas's raw `ChrVoice_*` layout instead, add `--folder-style atlas`.

Downloads are resumable. Existing non-empty MP3 files are skipped unless `--overwrite` is set.
Missing remote files are reported as warnings and do not stop the run; add `--strict` if you want any failed MP3 to make the command fail.

Rows that cannot be matched safely are written to `unmatched.tsv`. If a short local speaker name needs a specific servant ID, create `term_builder/character_audio_overrides.tsv` with:

```tsv
jp_name	svt_id	aliases
アルトリア・オルタ	100200	アルトリアオルタ
```

Use comma-separated `svt_id` values when one TSV speaker should intentionally download several Atlas servant variants.
