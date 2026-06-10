# FGO Terminology Builder

Builds the bundled terminology database used by the Android app.

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
bundled database.

### `term.tsv`

Used for terminology RAG and exact term matches in dialogue/choices.

Columns:

```tsv
jp_term	cn_term	category	aliases
オリュンポス	奥林波斯	place	
```

`category` examples: `place`, `game_term`, `class`, `item`, `organization`.

## Build

From the repo root:

```powershell
C:\Users\user\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe term_builder\ingest_atlas.py --skip-atlas
C:\Users\user\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe term_builder\build_db.py
```

Then rebuild the APK:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat assembleDebug
```

Output:

```text
app/src/main/assets/db/fgo_terms.db
app/build/outputs/apk/debug/app-debug.apk
```

## CDN Release Package

After `build_db.py` finishes, create the files for `cdn.fgogotran.com`:

```powershell
C:\Users\user\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe term_builder\package_release.py --content-version 2026.06.10.1
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

## Runtime DB Tables

`build_db.py` creates one SQLite asset with two tables:

- `character_names`: `jp_name`, `cn_name`, `aliases`
- `terms`: `jp_term`, `cn_term`, `category`, `aliases`

The app refreshes its copied runtime DB automatically when the bundled asset changes.
