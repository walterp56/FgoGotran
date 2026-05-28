# FGO Terminology Builder

Builds the bundled RAG glossary used by the Android app.

## Sources

- Atlas Academy API: structured JP/CN game data when matching IDs exist.
- `mooncell_terms.tsv`: curated Mooncell CN terms.

## TSV Format

`mooncell_terms.tsv` must be UTF-8 tab-separated:

```tsv
jp_name	cn_name	category	aliases	source
カルデア	迦勒底	place	Chaldea	mooncell
```

`aliases` is optional and comma-separated.

## Build

```powershell
cd C:\mywork\fgoGotran
python term_builder\ingest_atlas.py
python term_builder\build_db.py
```

Offline / Mooncell TSV only:

```powershell
python term_builder\ingest_atlas.py --skip-atlas
python term_builder\build_db.py
```

Output:

```text
app/src/main/assets/db/fgo_terms.db
```

The Android app copies this DB on first launch.
