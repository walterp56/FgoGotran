# ML Kit emphasis metadata repair — 2026-10-31

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
