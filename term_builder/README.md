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
app/src/main/assets/voice/character_voice_map.tsv
app/src/main/assets/voice/voice_profiles.tsv
term_builder/character_voice_profiles.tsv
```

`character_voice_map.tsv` keeps the app-required columns first: `jp_name`, `cn_name`, `aliases`, `jp_profile_id`, `cn_profile_id`. Extra columns are for review and tuning. Manual overrides always win. Atlas metadata guides only the generated draft rows; tune important characters in `character_voice_overrides.tsv` after listening tests.

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
