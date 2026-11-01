# Phase A: read-only APK access proof — 2026-10-16

Scope approved by owner: update FgoGotran and install/start official Shizuku on
the disposable LDPlayer installation only. No ordinary phone was changed. No FGO
asset replacement, paid API request or account/session collection was authorised
or performed.

## Device and software

- ADB serial: `emulator-5554`; Android 14 / SDK 34; primary Android user.
- JP FGO: `com.aniplex.fategrandorder`, version `2.139.3` (code `501`).
- FgoGotran: updated in place using the local debug APK; existing settings preserved.
- Official Shizuku manager: `v13.6.0.r1086.2650830c` from
  https://github.com/RikkaApps/Shizuku/releases/tag/v13.6.0
  (the official Shizuku download page links to this repository).
- Downloaded APK SHA-256:
  `6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f`.
- `apksigner verify --print-certs` succeeded; signer certificate SHA-256:
  `268b5590e868fb08bae7e0ac413564cd1ff88f5ccff74af9dbd0dc918e30db30`.
  This records the downloaded release identity; GitHub supplied no independent
  asset digest. No APK/source from an unofficial mirror was used.
- Official manager's displayed ADB startup executable was used. `shizuku_server`
  and `com.fgogotran:chapter_read` were observed as **shell UID 2000**, not root.

## Verified through the actual UI

1. Home -> Chapter Translation (experimental) opened the separate screen.
2. Connect displayed the standard Shizuku consent dialog. Owner-authorised access
   was granted on this emulator only. Reader connection reached “Connected · Read-only”.
3. Scan read the installed manifest through the APK's UserService, not a desktop
   fixture or an ADB file injection. It showed:
   - `ScriptActionEncrypt/01`: 1,772,416 bytes;
   - `ScriptActionEncrypt/Common`: 156,384 bytes.
4. Opening `01` decoded **717 native scripts**. Script-ID search for `0100000011`
   returned the actual script. Both that script and `0100072070` opened successfully.
5. Original Japanese dialogue, speaker fields and choices were visible. Original
   inline command text remains visible (for example native line-break tags); this
   is an inspection viewer, not the future translated in-game renderer.
6. Back returned to the searchable script list. Backgrounding the screen released
   the reader: its dedicated process exited with code 0. The independently started
   Shizuku broker stayed running by design.
7. Before and after UI browsing, both original device file hashes remained:
   - manifest `cfb1d36393fd67385e046b084b7cf7ed`:
     `c35317334b5a99bd9f0ffcf09ac435706828cb0f0b9e6aaf7bffab6f5c75f7ec`;
   - `3b6e743e6f41fb01a1037cb15169096d6a63198c.bin`:
     `a0af45a5439b555dd08535f724b19f3d00f45d93df380d1518438ffcd0104229`.

Local UI screenshots and authentic bytes stay under Git-ignored `local-output/`
and `local-fixtures/`. No story text or binary capture is committed.

## Build and regression validation

- 39 Chapter tests passed in both debug and release, including the opt-in real
  capture/profile/browser test; zero failures, errors or skips in that run.
- All 973 existing app tests passed; no existing OCR/ruby/prompt/voice/cache or
  runner implementation was edited.
- Debug APK and minified R8 release APK builds passed. Release remains unsigned
  by this task and was not installed; device checks used the debug APK.
- Chapter debug lint: zero errors; five dependency-update advisories. AndroidX
  and coroutine versions intentionally match the existing app rather than upgrading
  shared runtime libraries during this feature integration.
- Architecture tests retain the no-app-import/no-global-DI dependency boundary and
  explicitly allow only the protected Shizuku transport and narrow read-only IPC.

## Remaining gates

- This proves one real **read-only** JP device route. It does not prove support
  for every phone/Android version, root mode, additional users or other FGO servers.
- Group `01` is not a chapter name or a proof of account unlock/progress.
- Native no-op installation, marker playback and exact-original restoration are
  still required before a real translated line is installed.
- API settings/client/prompt/jobs remain Chapter-owned future work, not a call into
  the existing OCR translator. No whole-chapter translation/installer is claimed.
- Release R8 compilation is a packaging check, not release-device playback proof.
