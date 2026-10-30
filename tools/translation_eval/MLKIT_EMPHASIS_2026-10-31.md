# ML Kit dot handling — 2026-10-31

## Current behavior: noise suppression only

This section supersedes the earlier emphasis-metadata implementation documented below. The user
approved treating dots as image noise for ML Kit, retaining ruby recognition/rendering, and keeping
Paddle's dot feature unchanged. ML Kit remains available; it is not removed or upgraded.

The 02:00–02:01 device log repeatedly reports `をのような世免が他に大う。` for the screenshot
`そのような世界が他に六つ。`, with `dots=12, symbols=13, ranges=2`. Finding annotation geometry
did not make the underlying transcription reliable. The same bad transcription existed in the
older 01:29 log before ML Kit emphasis metadata was added.

### Changes

- Remove `MlKitEmphasis` and metadata-only `MlKitEmphasisInput`, including their obsolete tests.
- `MlKitDialogueInput.prepare` cleans confirmed upper-dot noise before the single ML Kit call for
  dialogue/fixed Japanese dialogue. It keeps full input resolution and creates an owned copy only
  if necessary. The caller's image is never modified or recycled.
- The existing shared cleaner protects readable ruby, upper strokes/dakuten, body text and main-
  band punctuation. Only ML Kit opts into masking additional round components confirmed by the
  already-existing aligned-run evidence, including a dot over a short kana. Paddle's default
  masking stays unchanged: 11 components in the worlds fixture, versus ML Kit's complete 12-dot run.
- Choices retain existing `ChoiceOcrContext` noise cleanup and punctuation recovery, but no dot
  geometry is passed into ML Kit result construction.
- Every new ML Kit result has empty emphasis ranges: no emphasis markers enter translation and
  no dot decoration reaches dialogue, choices or new history. Ruby lines/bounds still pass through
  the existing formatter, translation and renderer.
- General/name/crop inputs bypass story-dialogue cleanup. No ruby prompt, renderer, Sakura
  template, retry policy, setting, or saved history record changes. There is no added recognition
  pass or paid translation request. Full-resolution cleanup adds CPU/memory work; its latency and
  recognition accuracy must be checked on hardware. Cleanup diagnostics include count and time.

### Current verification

Eleven replacement tests use native Android bitmaps and a recording `TextRecognizer` boundary:

- Real worlds/memory screenshots at 1×/2× remove 12/22 dots, preserve body pixels and an unmarked
  first row, retain resolution, and leave caller-owned images intact.
- A real ruby crop at both scales remains pixel-identical. Source: the supplied
  `Screenshot_20261003_011711_FateGO.jpg`, original crop `(316,833)` through `(2015,1052)`.
- Mixed ruby/dot/punctuation fixtures cover every supported story ink colour; default Paddle
  masking remains unchanged. Owned image cleanup is checked on success and recognizer failure.
- Japanese, Chinese and Latin provider routes invoke recognition once and export no emphasis
  ranges. Ruby text/bounds, choices, punctuation recovery and general OCR are exercised.

The recording recognizer supplies controlled text: it verifies actual input/result wiring, not
model transcription quality. No claim is made that all formerly misread words are now correct.
Targeted checks: 26 tests passed. Full verification: **1,362 tests passed, 0 failures, errors or
skips**, and `:app:assembleDebug` succeeded. Log: `temp/mlkit-noise-verified.log`. Existing local
translation response fixtures were replayed without paid evaluations. APK:
`app/build/outputs/apk/debug/app-debug.apk`. No installation or commit was performed.

## Earlier implementation (superseded)

## Evidence and cause

The device log at 01:29:13–01:29:14 shows the ML Kit Japanese route receiving a 1699×219 crop enlarged to 3398×438, then returning the worlds dialogue with no emphasis diagnostic. The same log contains a working Paddle request at 01:26:18 with `dots=3, ranges=1` and `sourceRanges=1, translatedRanges=1, renderRanges=1`.

Before this change, `MlKitOcrProvider` implemented only the generic `recognize(bitmap)` overload, so the interface's default content-kind overload discarded the dialogue/choice signal. It created `OcrTextLine` values without emphasis ranges. Separately, generic choice punctuation recovery rebuilt lines and dropped any annotation metadata. No dot ranges could reach translation through this route.

## Change

- `MlKitOcrProvider` now handles dialogue/fixed-Japanese-dialogue content kinds and the shared choice context explicitly. All three ML Kit script variants use the same integration.
- Dialogue dots are located by the existing pixel classifier on a sample capped at 1920 px on its longest side. Pixel coordinates, including verified ink-row coordinates, are projected back to ML Kit's input scale. The bitmap supplied to the recognizer is not modified or downsampled by this new analysis.
- `MlKitEmphasis` maps verified dots using only actual ML Kit `Symbol` boxes. It matches the complete non-space text, preserves original UTF-16 offsets, handles combining/supplementary characters, and excludes symbols belonging to another row. It does not turn a whole element/word box into estimated letter positions. Missing or inconsistent native anchors omit decoration without changing recognized words.
- Shared choice punctuation recovery retains ranges and remaps them when punctuation is added. Existing punctuation logic is unchanged.
- The existing translation, cache, rendering, history, and playback paths consume the resulting ranges. Prompts and ruby rendering are unchanged by this fix. General/name OCR does not run the new dot scan.
- Diagnostics report `dots`, `symbols`, `ranges`, and `extraModelRuns=0` for ML Kit requests with dot evidence.

ML Kit documents text and bounding coordinates at Symbol level in its [Android Text Recognition guide](https://developers.google.com/ml-kit/vision/text-recognition/v2/android). Runtime symbol availability/consistency is still checked rather than assumed.

There is no additional recognition pass or paid API call. There is an extra bounded pixel-analysis step for dialogue; its on-device latency has not been measured. This repair carries emphasis metadata; it does not correct existing ML Kit transcription errors in the log.

## Verification

Eight new tests cover native-symbol mapping, spaces and UTF-16 offsets, Latin letters, missing/whole-word anchors, other-row ruby, combining characters, and supplementary characters. Native Android bitmap tests verify that both real screenshot fixtures retain their 12/22 dot candidates after ML Kit's 2× enlargement and bounded geometry sampling, while the recognition bitmap stays pixel-identical. Shared choice recovery retains emphasis ranges.

These tests use controlled Symbol geometry and native bitmap operations. They do **not** run the actual ML Kit model on a device; a new device log is needed to verify the SDK's real Symbol output for the reported scene. If reliable Symbol boxes are absent, the safe behavior remains undecorated text.

Full verification: **1,353 tests passed; 0 failures, errors, or skipped tests**. Command: `:app:testDebugUnitTest :app:assembleDebug`, with existing recorded-response fixtures enabled. No paid requests were made. The initial sandbox attempt could not write Gradle's user-cache lock; the tests/build completed with approved access.

Build log: `temp/mlkit-emphasis-verified.log`. APK: `app/build/outputs/apk/debug/app-debug.apk`. No installation or commit was performed.
