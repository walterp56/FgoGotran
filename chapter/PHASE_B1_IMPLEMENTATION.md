# Chapter B1 — implementation and offline validation (2026-10-16)

## Approval boundary

The owner approved **B1 implementation and offline tests only**. This turn did not
run ADB, update either APK on the emulator, replace FGO assets, start/stop FGO or
call a translation API. Existing OCR/ruby, translation prompts, cache, voice,
runner and analytics implementations were not edited. The real analytics owner
configuration was not touched. Existing Phase A changes in the working tree were
preserved.

B1 adds executable experimental installer code and confirmation controls to the
new local APK. It is not a claim of validated Android write-back. The current
emulator still has the earlier read-only build. B2/B3 require separate approval.

## Implemented components

All implementation belongs to `:chapter`, with no dependency back on app internals
or global Hilt bindings. Only the Home entry summary wording changes outside it.

- `ChapterInstallModels`: exact operation ID, owner-profile JP package/root, game
  version/code/UID/update identity, file hashes/sizes, selected script/field, mode,
  export digest, manual playback observation and operation state. JSON is bounded
  and schema-versioned. No plaintext story or API credentials go into the journal.
- `ChapterBackupStore`: immutable originals in app-private `noBackupFilesDir`,
  confined per-operation paths, file locking, read-back hashes, independently
  stored prepared files and versioned journal publication. New baselines cannot
  overwrite an unfinished operation. Old originals are retained.
- `AndroidChapterDurableFiles`: `AtomicFile` publication, explicit file/directory
  sync and read-back. `AtomicFile` itself is not used as a lock or as a claim of
  two-file atomicity. JVM tests inject temporary-directory file semantics instead.
- `ChapterRebuildPreparer`: existing native container/script/manifest writers,
  full script binding/payload comparison, unchanged unrelated manifest rows and
  matching encrypted-file size/CRC. The marker targets only `dialogue:691` in
  opening script `0100000011`; its original/replacement text is previewed in memory.
- `ChapterTransactionEngine`: separately testable staging/commit/restore protocol,
  per-file hash classification, game-state rechecks, bundle-first/manifest-last
  replacement and exact-original restoration. Unknown files are not overwritten.
- `ChapterInstallService`/`IChapterInstallService`: a separate non-root shell
  UserService. The existing `ChapterFileService` and its read-only IPC are unchanged.
  The installer accepts bounded regular read-only input descriptors, not arbitrary
  paths or shell commands. Every caller is checked. The only child command is a
  fixed bounded `ps` process-table read; FGO is never stopped by that command.
- `ShizukuChapterInstaller`: explicit-operation binding, stable tag/version,
  non-daemon mode and unconditional connection release after the call. No polling.
- `ChapterInstallSession`/`ChapterInstallCoordinator`: durable commit intent before
  replacement, mutual exclusion, recoverable errors, explicit user confirmations
  and a feature-local scope independent of the browser ViewModel. Creation occurs
  only on Chapter entry; there is no idle task or Application initializer.
- `ChapterAssetTestCard`: backup, SAF recovery export/read-back, preparation,
  inspection, confirmed install/restore, game app-info/open actions and explicit
  manual unchanged-playback confirmation. Strings cover Simplified Chinese,
  Traditional Chinese and English. R8 keeps the new reflective UserService entry.

## Deliberately restricted test scope

- JP package `com.aniplex.fategrandorder`, primary Android user, Shizuku shell UID
  2000 only; game version `2.139.3` / code `501`.
- Only `ScriptActionEncrypt/01` physical file
  `3b6e743e6f41fb01a1037cb15169096d6a63198c.bin` and installed manifest
  `cfb1d36393fd67385e046b084b7cf7ed` may be replaced.
- Exact original snapshot is pinned:
  - bundle SHA-256 `a0af45a5439b555dd08535f724b19f3d00f45d93df380d1518438ffcd0104229`;
  - manifest SHA-256 `c35317334b5a99bd9f0ffcf09ac435706828cb0f0b9e6aaf7bffab6f5c75f7ec`.
- Other game versions, installations, snapshots and arbitrary hashed assets remain
  unsupported for writes. A game content update can invalidate this test baseline
  even without changing the APK version; failure is intentional, not an invitation
  to bypass validation.
- Write inputs are limited to 8 MiB per file. Local proof rebuilds are approximately
  2.39 MB versus the original 1.77 MB because the existing proof writer uses
  uncompressed Unity blocks. Compression optimisation is not part of this phase.

## User flow in the new build

1. Authorise Shizuku; open asset group 01. Force-stop FGO through Android App Info
   before creating a baseline. Browsing itself remains read-only.
2. Back up the matching originals. Export the recovery ZIP through the document
   picker (local-only providers requested). The app closes it, reopens it, checks
   the archive digest and records verification only after an exact read-back. A
   replacement export revokes the old verification receipt before opening its
   output, because it might overwrite the previously verified artifact.
3. Independently verify a PC copy. The install confirmation asks for operator
   confirmation of that copy and the disposable-device scope; the APK does not
   claim it can inspect the PC filesystem.
4. Prepare an unchanged native rebuild. This calls the actual writer, not just a
   copy/unchanged-byte return. All 717 script bindings and payloads must match.
5. After separate B2 authorisation, explicitly confirm Install. Before live
   replacement, staging tests fixed temporary-file write/sync/rename/read-back in
   the same game directory. Unsupported semantics abort before live replacement.
6. Verify playback inside FGO. The app does not infer playback from file hashes.
   Confirm the observation manually, force-stop FGO and restore the exact originals.
7. Only confirmed unchanged playback followed by verified restoration unlocks
   marker preparation. Preview the one-field change to `テスト表示` before the
   separately authorised B3 installation, playback and restoration test.

The export contains only original encrypted bundle/manifest bytes and the record.
It is an emergency recovery artifact; importing it into a reinstalled app is not
implemented. The current Restore action uses retained private originals. Uninstall
or clearing app data removes those private backups and does **not** restore FGO.

## Cancellation and recovery

Normal browsing retains Phase A's cancel/disconnect behaviour. Backup, preparation,
export and pre-commit staging remain cancellable. Once commit intent is about to
be published, the coordinator marks a critical section, rechecks cancellation and
uses a non-cancellable scope for commit/restore and journal completion. Ordinary
screen backgrounding does not destroy the installer mid-critical-section. Each
installer call releases its own Shizuku connection afterwards.

This does not prevent OS death, reboot, Shizuku termination or application update.
The files are **not one atomic pair**. Persistent states are:

`BACKED_UP -> PREPARED -> STAGED -> COMMITTING -> INSTALLED -> RESTORING -> RESTORED`

Failures after commit intent lead to `RECOVERY_REQUIRED` or retain the unresolved
intent if journal writing also fails. After restart, local journal loading performs
no remote work or automatic writes. The user must request inspection/recovery.

| Actual current hashes | Handling |
| --- | --- |
| Original pair | No replacement remains; explicit restore can finish cleanup/recovery |
| Expected replacement pair | Recognise the installed pair; do not claim native playback |
| Known mixture of original/replacement | Require recovery while FGO is stopped |
| Unknown/missing/changed file or installation identity | Block automatic overwrite and preserve backups |

The app's Open FGO button is disabled during an unresolved critical state. It
cannot prevent a user or another app from launching FGO externally. The game must
remain stopped during the short replacement window; the writer rechecks state
between replacements. No account files are targeted.

## Offline validation performed

- 96 Chapter tests passed in debug and release with the owner-local capture enabled
  (zero failures/errors/skips). Includes the original codec tests and new models,
  backup/export, staging/commit/restore, journal recovery and cancellation tests.
- All 973 existing app tests passed.
- Debug APK and unsigned minified R8 release APK builds passed. The installer
  service and AIDL Stub retain their reflective names in the release mapping.
  No APK was installed on any device in this phase.
- Chapter lint passed with zero errors and the same five dependency-update
  advisories as Phase A; no new lint warnings remain.
- New failure cases include wrong version/identity, corrupt originals/prepared
  bytes, insufficient space, permission failure, unsupported staging semantics,
  missing staged data, failures before/after live-file renames **in local temporary
  directories**, interrupted restore, unknown live files, failed journal publication
  before and after commit intent, journal failure after actual local replacement,
  failed export read-back/replaced export and concurrent operations.
- A fresh session with unresolved intent performs no remote inspection until asked.
  Cancellation before commit causes no replacement; cancellation inside critical
  commit still records the verified installed state. Marker unlock requires both
  the simulated manual observation and exact restoration.
- The authentic-byte test captures the pinned pair into a temporary backup store,
  prepares an unchanged rebuild, runs install/restore through temporary files,
  prepares/previews one marker, runs a second local install/restore and verifies
  the exact original bytes. All 717 script identities and unchanged payloads are
  compared. Its simulated observation is **not** a device-playback result.
- Independent UnityPy 1.25.4 verification of the regenerated native proof passed:
  718 objects, all 717 container mappings preserved, unchanged reconstruction has
  identical object bytes, and the marker changes only the selected TextAsset.
  This remains desktop verification, not FGO playback.
- Process-table tests reject malformed/header-only/non-numeric output and detect
  any game-UID process, including differently named subprocesses.
- Architectural guards keep read-only IPC unchanged and allow only the separate
  fixed installer operations. No arbitrary command/path or production fault-trigger
  IPC was added.

Normal clone tests need no game files; the two authentic-capture tests skip without
`-Pchapter.localCapture=...`. Private captures, derived story data and screenshots
remain Git-ignored. Test faults are implemented in JVM-only doubles, not live IPC.

## Still unverified / next approval

B1 did not execute the new Android `AtomicFile`/directory-sync adapter, Shizuku
installer connection, positive/negative FGO process detection or FUSE write/rename
probe on a device. Compilation/tests cannot establish these properties.

B2 must first update only the authorised disposable emulator, verify process
visibility with FGO running and stopped, verify independent emergency originals,
then explicitly approve staging and unchanged replacement/playback/restoration.
B3 marker playback comes only afterwards. Rejection, unexpected download or changed
files stops testing. No root/injection/integrity-check bypass is authorised.

Target-language glyph/layout support, API settings/client/prompt, whole-chapter
mapping, batch translation and a production installer remain later work.
