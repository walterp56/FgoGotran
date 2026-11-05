# Manual OneDrive releases and the shared version manifest

Local ZIPs are hosted on OneDrive, not CDN. Only the existing small version
manifest is published to CDN: `app/android/latest/manifest.json`. Its top-level
Android fields stay intact; the optional `fgogotranLocal` section contains:

```json
{
  "fgogotranLocal": {
    "versionCode": 1,
    "versionName": "v1.0.0",
    "platform": "windows-x64",
    "downloadMode": "manual",
    "changelog": ["Release notes"],
    "changelogI18n": {
      "zh-Hans": ["Release notes"],
      "zh-Hant": ["Release notes"],
      "en": ["Release notes"]
    }
  }
}
```

This snippet is only the Local section, not a replacement for the Android
manifest. The reader and publication tool accept version metadata and release
notes without a download link, ZIP checksum or package size. The website owns
the OneDrive link; no OneDrive account, token or Graph integration is added to
the application.

The packaging tool also supplies optional `downloadUrl`, `packageSha256` and
`packageSize` fields to identify the reviewed ZIP. If present during publication,
they must be a public HTTPS OneDrive sharing link, a lowercase SHA-256 digest
and a positive integer size. Set OneDrive access to anyone with the link and
allow downloads; do not use a temporary authenticated URL.

At the same version code, release notes may be corrected and a version-only
entry may gain its first ZIP details. An existing checksum or package size
cannot change; a notes-only refresh preserves them. Use a new version code for
different program files. A higher version without ZIP details does not inherit
the previous version's artifact information.

Local reads version/name/release notes, not the manifest's OneDrive URL.
It always directs to the official localized download page's `#local` section.
The page owns the actual OneDrive link. Both active and passive checks show a
dialog first; navigation requires the user's **Go to download page** action.

Passive checks run when the control page opens: success has a 24-hour cooldown,
failure a one-hour cooldown. No periodic polling is added. Passive failures do
not pop up. **Not now** suppresses repeat prompts for 24 hours; **Skip this
version** suppresses passive prompts for that version, but a higher version can
prompt again. Active checks bypass cooldown and ignored-version preferences.
Simultaneous active/passive checks share one network request. Preferences and
cached release notes live in `user_data/state/update_reminder.json`.

The always-available website link works when metadata checks fail or no Local
section is published. No package downloads, installers or service restarts are
performed by the version checker. All new managed settings, default models,
runtimes, Python environments, caches and state live inside user_data.

## Prepare locally

1. Increase `versionCode` and set `versionName` in
   `src/fgogotran_local/version.json`. Python's package version is derived.
2. Review localized release notes and test the changes.
3. Run from the repository root:

```powershell
python term_builder/py/package_local_release.py --output release/local-preparation --changelog "Local release notes" --changelog-hant "Traditional Chinese release notes" --changelog-en "English release notes"
```

Output contains `packages/<version>/FgoGotranLocal-<version>-windows-x64.zip`,
its SHA-256 file and a merged `app/android/latest/manifest.json`. The base is
`--shared-manifest <file>`, an existing output manifest, or a read-only fetch
of the current CDN manifest. No upload occurs during packaging.

The ZIP excludes user_data, .venv, model/runtime files, private configuration,
logs, bytecode and tests. Its file inventory is for package validation only;
no automatic updater is included. Use `--download-url <public-OneDrive-link>`
to select a different public file/folder link. By default it reuses the public
folder already linked on the website. The program itself always opens the
official website, so changing the OneDrive sharing link does not change its
navigation destination.

## Publish after owner approval

1. Upload the reviewed ZIP and checksum to OneDrive manually.
2. Confirm the public sharing link allows downloading without signing in.
3. Only then publish the small shared manifest with the same packaging command
   plus `--publish-manifest-s3 s3://EXISTING_BUCKET[/prefix]`.

There is no Local ZIP upload option. `--publish-manifest-s3` sends only the
shared JSON object, using the existing bucket and manifest path. It re-reads
the S3 origin, preserves Android data and uses an ETag conditional write to
avoid losing concurrent Android releases. Android's release tool likewise
preserves the Local section. Never publish a stale whole manifest with an
unconditional `aws s3 cp`. A recent AWS CLI supporting conditional writes is
required. No new CDN service, distribution or update-feed object is created.

## User update instructions

Fully close Local, back up user_data, extract the new program ZIP to the
original installation path, and restore the original user_data before first
launch. Verify settings/model/translation before removing the backup. ZIPs do
not contain user_data. Existing external models/runtimes must stay at their
configured paths; they are not part of this backup. New managed Python
environments live under user_data/runtime; the legacy root .venv is preserved
but is not required by the new launcher. Merely stopping llama-server does not
close the Local control service: close its console before replacing files.
Development checkouts should use their normal Git workflow.
