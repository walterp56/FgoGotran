# Chapter Translation: isolated native-asset feature

This module is the foundation for the approved **select chapter -> translate through
an API -> rebuild assets -> install -> read inside FGO** feature. It is not an OCR
mode or a runtime memory hook.

## Current status

| Gate | Status |
| --- | --- |
| Isolated library, models, contracts and regression tests | Implemented |
| 1A: authentic story container plus matching installed manifest | Captured read-only from the authorised disposable emulator |
| 1B: native codecs | Implemented for the observed JP installed-manifest/UnityFS8/serialized22/script envelopes only |
| 1C: unchanged native rebuild and one plain-field marker rebuild | Passed offline; independently read by UnityPy |
| Phase A: Android-side non-root Shizuku read-only bridge | Implemented; verified through the APK on the authorised LDPlayer emulator |
| Native FGO playback, installation and restoration | Unchanged rebuild playback failed on file access; separately approved recovery restored originals and verified original playback; rebuilt playback remains unproven |
| Phase A: Home entry, asset/script browser and original-text viewer | Implemented and tested on the emulator |
| B1: persistent backup/export, journal, rebuild preparation, isolated installer and recovery UI | Implemented; local-file/native-byte simulation only |
| API translation, chapter-title mapping and full-chapter production jobs | Not implemented |

Latest device check: [PHASE_B2_UPDATED_DEVICE_CHECK.md](PHASE_B2_UPDATED_DEVICE_CHECK.md).
The owner updated FGO to 2.139.5. Fresh backups and offline proofs passed, but
the unchanged live test exposed a file-ownership defect: shell-private staged
files were unreadable to FGO after rename. FGO regenerated its resource index and
requested 1.36 GB; this download was not accepted. **B2 failed; bounded recovery is
now complete.** FGO recreated only the affected original story bundle, original
Fou/choice/Mash playback worked, and both saved original hashes and FGO ownership
were restored. The app journal is `RESTORED`, not playback-approved; FGO was left
stopped. A new pre-install access check blocks the unsafe rename route. No marker
or DeepSeek test ran. A safe installer and unchanged-rebuild playback must still
pass before proceeding. The older read-only/backup check is
retained in [PHASE_B2_DEVICE_CHECK.md](PHASE_B2_DEVICE_CHECK.md).

**1A–1C offline proof and 1D/Phase A read-only APK access passed for the observed
asset group. Full native installation/playback feasibility is not yet proven.**
Offline rebuilding does not establish that FGO accepts the files. The APK now reads
the observed JP files through an explicitly authorised non-root Shizuku UserService;
ordinary app-UID access remains unavailable. Do not install generated proof files
without separate backup, installation and native-playback approval.

The APK now includes two one-way-isolated features: the existing OCR approach and
this Chapter module. Home opens a thin, non-Hilt `ChapterHostActivity`, which passes
only presentation (theme/locale), not the old translator, OCR engine or repositories.
The standard protected Shizuku provider and API permission are merged into the APK.
The provider participates in process startup; there is **not** zero startup overhead.
Chapter's permission request, file-service binding, scans and native decoding occur
only after entry/user action. There is no Chapter worker, polling loop or daemon.

## Using Phase A on the test emulator

1. Start the official Shizuku app in **ADB/non-root** mode. It normally needs starting
   again after an emulator/device reboot; FgoGotran does not start it automatically.
2. Open FgoGotran Home -> **Chapter Translation (experimental)**. No OCR service,
   accessibility permission, overlay permission or translation API key is required.
3. Tap **Connect** and authorise FgoGotran in Shizuku. Root mode, secondary Android
   profiles and unverified roots are rejected.
4. Tap **Scan installed assets**, then `ScriptActionEncrypt/01`. The current fixture
   contains 717 native scripts. These groups are not chapter titles or unlock state.
5. Search for a script ID (for example `0100000011`) and open its original text.
   Speakers, dialogue, choices and native ruby annotations are shown independently
   of OCR; the raw-script view retains original commands and markers.
6. Back returns from script to group to Home. Leaving/backgrounding Chapter cancels
   its request, drops captured data and unbinds/removes the shell reader. Return,
   reconnect and scan to read again. The independent Shizuku broker remains running.

The installed Phase A emulator APK remains the read-only build. B1 adds an **Asset
test** card to the new local APK build; B1 approval did not authorise updating the
emulator or replacing FGO files. Its write operations require explicit per-operation
confirmation and are not yet validated against Android/FGO. API translation remains
unavailable. See [PHASE_A_DEVICE_CHECK.md](PHASE_A_DEVICE_CHECK.md) for actual device
evidence and [PHASE_B1_IMPLEMENTATION.md](PHASE_B1_IMPLEMENTATION.md) for the new code,
offline validation, limitations and B2/B3 approval gates.

## Code and data boundary

- The existing screen-recognition, ruby, translation, overlay, cache and voice code
  is unchanged. The Chapter module must not import any of those implementations.
- `verifyChapterDependencies` rejects other-project dependencies and recognition, speech
  and global dependency-injection libraries. `ChapterIsolationTest` checks source
  references, the exact protected-provider allowlist, narrow read-only AIDL surface
  and thin non-Hilt host. Chapter never depends back on `:app`.
- This is architectural isolation, **not** an Android security/process sandbox.
  The eventual APK will still share dependency resolution, permissions and memory.
- No credentials, current app settings, live glossary database or analytics objects
  are read. Connection settings, prompts, glossary snapshots and jobs will be owned
  independently by Chapter if their later phases are approved.
- The normal phone was not modified. Shizuku permission is requested only by the
  Chapter Connect action, not by Home or the existing Start Service flow.

## Foundation contracts

- `ChapterAssets.kt`: canonical relative asset paths, SHA-256 fingerprints, defensively
  owned original bytes, immutable baseline snapshots and complete declared
  chapter/script/asset bindings. These bindings are supplied by verified mapping;
  they are not inferred from a filename or a player's account progress.
- `NativeAssetContracts.kt`: opaque versioned codec locators, decoded source/text
  identities, native ruby/placeholder ranges and branch identities. UTF-16 ranges
  describe decoded Kotlin strings, **not** offsets in proprietary asset bytes.
  Container/script/manifest codec interfaces keep their narrow contracts.
- `codec/`: independently implemented, bounded readers/encoders for the observed
  manifest wrapper and five-column installed rows, JP asset wrapper, UnityFS v8,
  serialized-file v22 TextAssets and native line scripts. Byte-preserving no-op
  encoding retains original compressed streams; a separate actual reconstruction
  exercises the Unity writer and is independently checked, rather than claiming
  that copying original bytes proves a rebuild.
- `NativeTextPatch.kt`: exact field identity plus expected text SHA-256 and one plain
  replacement literal. The proof patcher refuses speaker/choice/ruby/placeholder
  fields and command-bearing dialogue; it is not a full translation compiler.
- `ChapterStorage.kt`: read-only capability/read contracts. The initial
  `UnavailableChapterStorage` returns an unavailable result without accessing FGO.
  The Android adapter is `storage/ShizukuChapterStorage.kt`; the privileged AIDL
  exposes UID/root validation, bounded read-only file descriptors, fingerprints and
  process teardown only. No write/delete/install/arbitrary-command API exists.
- `ChapterBrowser.kt` and `ui/`: feature-owned composition root, cancellable UI work,
  real installed-manifest/group/script navigation and original-text viewing. CPU
  decoding runs off the main thread; cancellation is checked between stages, not
  inside every synchronous codec operation. No existing app network/cache is used.
- `install/`: B1 immutable original backups, verified recovery export, versioned
  journal, native rebuild preparation and single-operation coordinator. The pure
  transaction engine is exercised only on temporary local files in B1 tests.
- `storage/ChapterInstallService.kt` and its separate AIDL/client: fixed-pair test
  write operations, caller/UID/game identity checks, process inspection, bounded
  read-only input descriptors and confined staging. The existing reader remains
  read-only. No arbitrary command/path or live writable-descriptor API is exposed.

Paths are only relative identifiers. The current adapter independently fixes/verifies
the owner-profile JP root, accepts only its manifest and canonical hash filenames,
checks caller UID, rejects symlinks, opens regular files read-only, bounds reads and
compares before/local/after SHA-256/size. Bundle size/CRC and the manifest snapshot
must still match. This is read consistency, not a multi-file installation transaction.

## Asset feasibility findings (2026-10-16)

An earlier bounded local inventory found no named authentic pair. Subsequent
read-only ADB capture found an installed pair on the user-authorised test emulator.
APK-extraction metadata was not treated as story data. No root, private service,
account/session response collection, game launch/stop or device replacement was used.

### Observed sample and measured proof

- Test emulator: `emulator-5554`, Android 14 / SDK 34, JP FGO
  `com.aniplex.fategrandorder`, version `2.139.3` / code `501`.
- Installed manifest: `files/data/d713/cfb1d36393fd67385e046b084b7cf7ed`.
  SHA-256 `c35317334b5a99bd9f0ffcf09ac435706828cb0f0b9e6aaf7bffab6f5c75f7ec`.
- Manifest mapping: `ScriptActionEncrypt/01` ->
  `3b6e743e6f41fb01a1037cb15169096d6a63198c.bin` (1,772,416 bytes).
  SHA-256 `a0af45a5439b555dd08535f724b19f3d00f45d93df380d1518438ffcd0104229`.
- ScriptFileList metadata was captured alongside it, not inferred from filenames.
- The observed manifest is Base64/UTF-8 BOM, Rijndael-256/CBC/PKCS7, gzip and inverted
  text. Its leading CRC covers the following decoded body. Installed row size and
  CRC were verified against the **encrypted physical file**, not guessed Unity CRCs.
- Observed bundle: UnityFS v8, Unity `6000.0.68f1`, serialized file v22. Metadata uses
  LZ4HC and the data block uses end-marked LZMA. All 717 TextAssets are retained,
  including nonnumeric `WarEpilogue108`; names are not restricted to quest IDs.
- All 717 script envelopes round-tripped unchanged. The current parser identified
  65,398 fields, 2,376 native ruby spans and 2,857 choice markers. These are parsed
  field counts, **not** unique scenes/users or a completeness claim for all FGO syntax.
- Native ruby is supplied in script tags such as `[#base:reading]`; it never passes
  through the existing OCR/ruby implementation. Scripts used bzip2 as well as gzip.
- Both an actual unchanged native reconstruction and a one-field `テスト表示`
  reconstruction were built. Target: script `0100000011`, field `dialogue:691`.
- Independent UnityPy 1.25.4 read all 718 objects (717 TextAssets + 1 AssetBundle).
  The marker rebuild changes exactly the selected TextAsset; every other object byte
  and all 717 AssetBundle path mappings remain identical. The unchanged rebuild has
  identical bytes for **all** object bodies and identical path mappings.
- Changed proof bundles deliberately use uncompressed Unity blocks. Compression
  layout/padding and therefore physical hashes differ; object identities and opaque
  metadata/object content are preserved. This is a correctness proof, not a claim
  of optimal installed size or native playback acceptance.

`01` is an asset group containing many scripts, **not** proof of one chapter or of
the account's unlocked/completed story. Chapter catalogue mapping remains later work.
Opaque commands and native marker bytes are preserved verbatim; the line reader is
not yet a complete branch/control-flow or full-chapter translation compiler.

### TranslateFGO is an installer, not the missing public builder

Local reference: `C:/mywork/temp_git/translatefgo`, commit
`cc2c4e5c281601a929754e2e423dd45098a41790`.

- The [README](https://github.com/rayshift/translatefgo/blob/cc2c4e5c281601a929754e2e423dd45098a41790/README.md#contributing)
  says bundle creation, translation and the API are private.
- [RestfulAPI.cs](https://github.com/rayshift/translatefgo/blob/cc2c4e5c281601a929754e2e423dd45098a41790/RayshiftTranslateFGO/Services/RestfulAPI.cs#L226)
  delegates asset-list rewriting to a remote service.
- Its file installation and uninstall code do not establish an atomic multi-file
  transaction or exact-original backup restoration for this feature.

### Public leads, not verified production dependencies

- [Atlas Academy API](https://github.com/atlasacademy/fgo-game-data-api): useful
  chapter/quest/script catalogue relationships and script data. A script ID or text
  export is not yet a proven physical installed-object mapping or reversible binary.
- [FGOAssetsModifyTool](https://github.com/hexstr/FGOAssetsModifyTool): archived public
  source with asset/script transforms and AssetStorage decoding. Its
  [Program.cs](https://github.com/hexstr/FGOAssetsModifyTool/blob/master/FGOAssetsModifyTool/Program.cs)
  distinguishes the server asset-list format from the local game format. No complete
  modern container rebuild plus installed-manifest update was verified here. Review
  license and current-format compatibility before importing any code. Its runtime
  replacement branch does not prove stock-unrooted native asset installation.
- [nextgenfs](https://github.com/rayshift/nextgenfs): public Shizuku/AIDL storage-access
  source. This does not prove device compatibility, process-memory access, safe
  interrupted installation or rollback. It is not included in this module.

No private service was called and no unlicensed reference binaries/source were
vendored. Pinned UnityPy 1.25.4 and its dependencies were installed only under ignored
`local-output/python-deps` for independent desktop verification; they are not Android
dependencies. Reviewed JVM dependencies are listed in `THIRD_PARTY.md`.
Authentic and derived game bytes stay in Git-ignored local fixture/output directories.
Only local copies were rebuilt. No FGO device asset was modified.

## Next proof and approval gates

1. 1D / Phase A: completed read-only on the explicitly authorised disposable
   emulator. The ordinary `run-as` denial remains valid: the successful route is a
   separate non-root shell UserService, not a change to ordinary Android permissions.
2. 1E / Phase B: separately approve exact emulator file replacements after verifying FGO is
   stopped, hashes still match, independent original backups exist, and restoration
   is ready. Native no-op playback comes before marker playback, followed by exact
   original restoration and original-dialogue playback. A download/rejection is a
   failed compatibility test, not permission to add root/injection/bypass mechanisms.
   B1 code/offline tests are now implemented; B2 unchanged playback and B3 marker
   playback/restoration are still unperformed. B1 does not establish Android FUSE
   write, directory-sync or rename compatibility.
3. Phase C/D: afterwards, test native API translation, full chapter/quest mapping, syntax
   coverage, dependency packaging on Android and a production installer in later
   approvals. The current proof does not enable any of these automatically.

Only after the native playback gates pass should a small API-translated sample be
tested. A whole-chapter API job and automatic installer remain later phases.

Original backups must be protected separately from disposable translation cache.
Uninstalling the eventual app must not be presented as restoring FGO: app removal
can delete its local backups while modified game files remain.

## Verification

Using the same JDK as the existing app (Android Studio JBR):

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :chapter:testDebugUnitTest :chapter:assembleDebug :chapter:lintDebug
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Normal tests use synthetic strings/bytes. `LocalChapterFixtureTest` is explicitly
opt-in and otherwise skipped. The captured original hashes are checked before use.
Raw assets, decoded story text, owner-local research files and generated proof
packages are never committed. The independently verified public format parameters
are packaged in `FgoJpFormatProfile`; they are not credentials. Generated proof output
is not approved for installation into FGO.

The earlier foundation verification (21 tests) is superseded by the following
native-proof verification on 2026-10-16 (historical, before Phase A app integration):

- 33 Chapter tests passed in both debug and release, including the opt-in authentic
  fixture proof (zero failures/errors/skips in that run).
- 973 existing app tests passed on a fresh test-task run (zero failures/errors/skips).
- Chapter debug/release library assembly and debug lint passed; lint reported no issues.
- Existing app debug APK assembly passed. No APK was installed on a phone.
- App debug runtime dependency inspection found no Chapter dependency.
- No existing app source, settings, manifest or runtime dependency declarations changed.

No native fixture is required to clone/build the project. The optional authentic
fixture test remains intentionally skipped without the local capture property.
Current Phase A validation is recorded in `PHASE_A_DEVICE_CHECK.md`.

### Reproducing the owner-local proof

`tools/Capture-ChapterSample.ps1` reads only the fixed JP external asset directory.
It requires FGO to be stopped, validates filenames/size limits and before/local/after
hashes, and writes a unique capture with `capture.json` plus a filename-only inventory.
It cannot push, delete, stop FGO or install anything. Select only an authorised test
emulator; do not collect account files.

```powershell
.\chapter\tools\Capture-ChapterSample.ps1 -Serial emulator-5554 -Adb '<absolute adb.exe path>' `
  -AssetFile @('3b6e743e6f41fb01a1037cb15169096d6a63198c.bin', '2b3c7a4706632dd61f9e7e840fedd8e368a81633.bin')
```

The current owner-only envelope profile is in `chapter/local-fixtures/jp-envelope.properties`:
`stage_key`, `stage_iv`, `asset_key`, `asset_iv` (64 hex characters each). These are
format parameters from the reviewed public research, not account credentials. No
auto-discovery of this desktop research file is installed. The Android profile now
contains the matching verified public parameters. A fresh clone intentionally does
not contain the ignored capture, desktop research file or original game assets.

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :chapter:testDebugUnitTest :chapter:testReleaseUnitTest `
  '-Pchapter.localCapture=C:\mywork\FgoGotran\chapter\local-fixtures\20261016T083519732Z-1dc56549'
```

Output: `chapter/local-output/<capture-name>/proof/`. The no-op file set is separate
under `proof/no-op/`. `proof.txt` explicitly records offline-only status and hashes.
For an independent reader, install UnityPy==1.25.4 into a local disposable Python
environment and run `tools/Verify-UnityProof.py <proof-directory>`; no device access
or file writes occur in that verifier. Desktop verification is not Android execution.

The read-only device bridge is verified. The new write bridge, native FGO playback,
physical interruption/durability and device restoration remain unverified.
