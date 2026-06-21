# FGO Terminology Builder

Builds the terminology database that is published to the CDN and downloaded by
the Android app.

## Editable TSV Files

Edit these files when adding or correcting translations:

The TSV files are saved as UTF-8 with BOM so Excel can open Japanese and
Chinese text correctly. If Excel asks during save, keep the file as UTF-8 text;
do not save as ANSI/Big5/CP950, or Japanese text may become `??`.

### `character_names.tsv`

Used for deterministic name-box translation. Exact JP name or alias matches are rendered directly without calling the LLM.

Columns:

```tsv
jp_name	cn_name	aliases
ホームズ	福尔摩斯	シャーロック・ホームズ
```

`aliases` is optional and comma-separated.

Keep full character names here. `build_db.py` automatically adds component
records for separator-based names, so `マシュ・キリエライト` keeps the full
official row and also becomes searchable as `マシュ` and `キリエライト` in the
published database.

### `term.tsv`

Used for terminology RAG and exact term matches in dialogue/choices.

Columns:

```tsv
jp_term	cn_term	category	aliases
オリュンポス	奥林波斯	place	
```

`category` examples: `place`, `game_term`, `class`, `item`, `organization`.

`build_db.py` also adds component records for separator-based terms when the JP
and CN text split into the same number of parts. For example,
`近未来観測レンズ・シバ -> 近未来观测透镜·示巴` also makes `シバ -> 示巴`
available in the generated terms table. Curated TSV rows still win over
generated component rows.

## Build

From the repo root:

```powershell
C:\Users\user\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe term_builder\py\ingest_atlas.py --skip-atlas
C:\Users\user\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe term_builder\py\build_db.py
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
release/cdn/db/zh-Hans/releases/2026.06.10.1/fgo_terms.db
release/cdn/db/zh-Hans/releases/2026.06.10.1/fgo_terms.db.sha256
```

Upload the versioned `releases/...` files first, then upload
`latest/manifest.json` last. The app should check:

```text
https://cdn.fgogotran.com/db/zh-Hans/latest/manifest.json
```

To publish directly to S3 and invalidate CloudFront in the safe order:

```powershell
.\scripts\release-db.ps1 -S3Uri s3://YOUR_BUCKET -CloudFrontDistributionId YOUR_DISTRIBUTION_ID
```

The script uploads the versioned DB and checksum first, uploads
`db/zh-Hans/latest/manifest.json` last, waits for the CloudFront invalidation,
then verifies the live manifest content version.

## Runtime DB Tables

`build_db.py` creates one SQLite DB with two tables:

- `character_names`: `jp_name`, `cn_name`, `aliases`
- `terms`: `jp_term`, `cn_term`, `category`, `aliases`

The APK does not include this DB. The app downloads the latest verified package
from `https://cdn.fgogotran.com/db/zh-Hans/latest/manifest.json`.
