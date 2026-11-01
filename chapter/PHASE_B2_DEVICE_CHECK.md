# Chapter B2 device check — partial, blocked by FGO update

Date: 2026-10-16, Hong Kong time. Scope: the owner-approved disposable LDPlayer
`emulator-5554` only. The owner authorised the native-playback gates followed by
at most three small DeepSeek requests, but API testing remains gated on successful
native playback and exact-original restoration.

## Result

**B2 is not complete.** Unmodified FGO cannot reach gameplay: its own startup
screen requires version **2.139.5** and identifies the installed version as
**2.139.3**. This was observed before replacing any game asset. It is not evidence
that FGO rejected a rebuilt asset; no rebuilt asset was installed.

The FgoGotran debug APK was updated in place without clearing settings. Chapter's
read-only browser, original capture, running/stopped guard, private backup,
AtomicFile metadata publication and SAF export/read-back were exercised. The new
write service ran as shell UID 2000 for read-only environment/hash inspection.
Its staging/write/rename probe, live replacement and restoration remain untested.

## Observations and fixes

1. Backing up while the original FGO process (UID 10075) was running correctly
   failed with `Force stop JP FGO before creating the baseline`. After explicitly
   force-stopping only that game package, backup succeeded. Both private original
   files match the PC capture and live pair.
2. Rapid installer RPCs originally failed with `Installer disconnected`. Device
   logs showed an old UserService teardown affecting the next bind. The Shizuku
   API caches client connections by tag; `remove=true` alone leaves that cache
   until an asynchronous death callback. The client now first detaches/clears the
   cached callbacks with `remove=false`, then tears down the remote service with
   `remove=true`. Running-state rejection and the subsequent multi-RPC stopped
   backup succeeded with this fix. No sleeps, daemon or automatic RPC retries were
   added. The read-only browser service was not changed.
3. The first document-picker export produced an empty file and no verification
   receipt: foreground journal reload could occupy the coordinator's work slot
   and silently discard the explicit activity-result action. A small, feature-local
   operation launcher now lets an explicit action cancel and join a lifecycle-only
   reload before taking the session mutex. It still refuses overlapping explicit
   actions, does not queue duplicate installation requests, and preserves the
   existing critical-section background behaviour. Three coroutine regression
   tests cover this ordering, duplicate-action exclusion and critical cancellation.
   The repeated device export produced a full ZIP and a matching app receipt.

The connection-cache behaviour was checked against primary upstream source:
[ShizukuServiceConnections](https://github.com/RikkaApps/Shizuku-API/blob/master/api/src/main/java/rikka/shizuku/ShizukuServiceConnections.java),
[ShizukuServiceConnection](https://github.com/RikkaApps/Shizuku-API/blob/master/api/src/main/java/rikka/shizuku/ShizukuServiceConnection.java),
and [unbindUserService](https://github.com/RikkaApps/Shizuku-API/blob/master/api/src/main/java/rikka/shizuku/Shizuku.java).
The actual device exercises above are the compatibility evidence for this build;
the source guard is not presented as a simulated Binder integration test.

## Backup evidence

Operation ID: `6f49075893724312a519ad96cbf8a7f8`.
Final journal state: `BACKED_UP`, no rebuild mode, `playbackConfirmed=false`,
`unchangedVerified=false`. No installation or restoration intent was published.

| Item | Bytes | SHA-256 |
| --- | ---: | --- |
| Original bundle `3b6e743e6f41fb01a1037cb15169096d6a63198c.bin` | 1,772,416 | `a0af45a5439b555dd08535f724b19f3d00f45d93df380d1518438ffcd0104229` |
| Original manifest `cfb1d36393fd67385e046b084b7cf7ed` | 741,211 | `c35317334b5a99bd9f0ffcf09ac435706828cb0f0b9e6aaf7bffab6f5c75f7ec` |
| Verified recovery ZIP | 2,335,286 | `786816a1bdc66cda0d01e9ff1883ad1ce6c2ca5cbdaaf7ce0997fcf6ca731fd1` |

The verified ZIP is in emulator Downloads as
`FgoGotran-recovery-6f490758 (1).zip`. Its independent PC copy is
`chapter/local-output/device-b2-20261016/recovery-verified.zip`. Both original asset
entries were independently hashed on the PC and match the table above. The ZIP
digest matches the app's recorded export receipt. Only the failed zero-byte export
and its empty PC copy were removed; the verified recovery package is retained.

Local, Git-ignored screenshots:

- `chapter/local-output/device-b2-20261016/original-start.png`: required-update
  screen from unmodified FGO.
- `chapter/local-output/device-b2-20261016/backup-export-verified.png`: successful
  backup and verified-export UI.

No API key or app-preference file was read/exported. No DeepSeek request was sent,
so API spend from this test is zero. No normal phone was connected or modified.
Existing OCR/ruby, prompts, translation/cache, voice and analytics implementations
and the owner-only analytics switch were not changed.

## Validation

- 99 Chapter tests passed in debug and release, with the authentic capture enabled:
  zero failures, errors or skipped tests. Includes the three new launcher tests.
- Chapter debug lint passed: zero errors and the same five existing dependency
  advisories.
- Debug APK built and installed in place on the disposable emulator; app-private
  backup survived the second update.
- Existing app regression suite: 973 tests passed during this run. Chapter remains
  isolated through its source/dependency/IPC architectural guards.
- Final live hashes remain the original pair above; no `.fgogotran-*` staging or
  lock file was created in the game directory. FGO was left force-stopped.

## Next gate

Ask the owner before updating the disposable FGO installation and establishing a
new supported baseline. Do not merely relax the pinned 2.139.3/version/hash guards
or install its proof files into 2.139.5. Keep this old recovery package separate:
it is not a recovery package for a newer installation.

After an authorised update: capture the new manifest/asset pair read-only, inspect
the native format/mapping, independently verify a fresh unchanged reconstruction,
then scope the necessary policy/backup-baseline migration. Only then resume B2
unchanged playback/restoration, B3 marker playback/restoration and the isolated
one-line DeepSeek test. No root, injection or game-version/integrity bypass is
part of this route.
