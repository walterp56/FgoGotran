# Native Story Translation — isolated from OCR

## Current status

The product direction is **prepared translated assets → install → read inside
FGO**. Translation, source alignment and script compilation belong to developer
tooling, not the user's phone. There is no runtime memory hook or translation API
in this feature.

**Native playback has not passed. No installable native release is available.**
The latest unchanged-bundle experiment left group 01 invalidated by FGO.
Its journal/backups are retained; UI cleanup does not resolve or approve it.
See [R1 recovery and current device state](NATIVE_RECOVERY_R1_20261021.md).

## App surfaces

| Surface | Behaviour |
| --- | --- |
| Native Story Translation | Language choice, truthful unavailable/coverage status, disabled install/update, entry to recovery |
| Backup and Recovery | Inspect, export or explicitly restore saved asset-test/legacy source-pack operations; available in every build |
| Developer diagnostics | Debug-only navigation to native script browsing and fixed-pair R1 controls; not initialized by normal entry |

The normal page reads only bounded private journal summaries and the installed
JP FGO version. It does not create operation stores, import archives, bind Shizuku,
scan/decode game scripts or instantiate installer/test coordinators.

Recovery creates its helpers only on explicit entry. Shizuku connection and
inspect/restore remain explicit actions. Critical restoration may finish after
leaving the screen; backups and journal state are preserved. Unknown game bytes,
changed installation identity, or invalid interrupted-write evidence still block
writes. No automatic restoration, rebase, journal reset or proof-flag override.

The previous source-package import/selection/preview/compiler/install UI is
retired. Old private source archives, selections, backups and journal schemas are
not deleted or silently converted into native releases.

## Code placement

- `ui/ChapterScreen.kt`: small normal entry and route dispatch.
- `ui/ChapterRecoveryScreen.kt`: recovery-only controls, no prepare/install action.
- `ui/ChapterDeveloperScreen.kt` and its ViewModel: explicit debug diagnostics.
- `install/`: existing native transaction safeguards, private backups and R1
  evidence; `ChapterRecoveryOverview` is a read-only landing-page summary.
- `packinstall/`: compatible legacy journal/store plus recovery-only session.
  The old source-pack preparation/install session is now test/tool code.
- `storage/`: narrow non-root owner-profile Shizuku adapters. No arbitrary
  command/path API or writable descriptor is exposed.
- `codec/`: bounded native readers/writers, also reused by desktop preparation.
- `src/test/.../compile`, source-library/archive tooling and historical source-pack
  orchestration: offline-only, excluded from APK production sources. Existing
  regression tests still exercise their safeguards.
- `src/test/.../prepare`: R2 Atlas discovery, alignment and cumulative candidate
  generation; no device or production-installation authority.

The existing OCR/ruby/translation/cache/voice pipelines remain separate.
The thin non-Hilt app host passes presentation only. The protected Shizuku
provider remains in the APK; this is feature isolation, not zero startup cost or
an Android security sandbox. There is no native-feature polling worker.

## Developer evidence and next work

- [UI/runtime cleanup](NATIVE_UI_CLEANUP_20261021.md)
- [R0 native diagnostics](NATIVE_ACCEPTANCE_R0_20261020.md)
- [R1 acceptance history](NATIVE_ACCEPTANCE_R1_20261020.md)
- [Latest R1 recovery and unresolved invalidation](NATIVE_RECOVERY_R1_20261021.md)
- [R2 Atlas preparation and limited coverage](R2_ATLAS_PREPARATION_20261020.md)
- [Local asset library](STORY_ASSET_LIBRARY.md)
- Historical source-pack contracts/tests: [schema](STORY_LANGUAGE_PACKS.md) and
  [transactions](STORY_LANGUAGE_PACK_TRANSACTIONS.md); these are not the current
  normal-phone workflow.

The explicit desktop Gradle tasks `discoverAtlasStorySources` and
`buildNativeStoryCandidate` remain available. Their candidates are not approved
native releases. Available official CN/TW/English scripts do not cover all JP
story content; uncovered scripts remain Japanese. Full Intro/Fuyuki coverage,
translated native playback and production multi-bundle installation are unproven.

Next: separately review recovery of the captured FGO-invalidated entry, isolate
native acceptance, then implement a prepared-native-release installer. Do not
treat this cleanup as an R1 pass or an implemented R3/cloud delivery system.
