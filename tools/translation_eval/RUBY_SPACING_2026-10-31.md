# Ruby separator parsing and styled display — 2026-10-31

## Report and cause

The supplied device log at 02:43:52 contains `After I saw her 〈Olga Marie〉 vanish...`.
`RubyRenderText` required a word character immediately before `〈`, so a single intervening space
left the notation inline. Git attributes that guard to `687f22c` (2026-10-27); an old test explicitly
expected `本文 〈讀音〉` to remain literal. The recent English word-dot change did not introduce it.

## Approved render-only change

- Recognize a complete reading after horizontal separators: ordinary/repeated/full-width spaces,
  tabs, Unicode space separators including NBSP/narrow NBSP, and adjacent zero-width separators.
  Real line/paragraph boundaries and punctuation still stop an orphan reading from attaching to
  the previous sentence. Empty/nested/incomplete readings remain literal.
- Anchor English to the closest preceding Latin token, including curly contractions, compounds
  and combining accents. Keep Chinese/Japanese base inference and right-endpoint alignment.
- A separated marker gets display-only boundary cleanup: preserve a necessary English/mixed-script
  word gap, remove an unnecessary gap before punctuation or between CJK text, and retain all
  internal reading text such as `Olga Marie` and `Sirius Light`.
- Already tight markers preserve following spacing exactly. This is essential because the existing
  `RubySpanSpacing` fallback uses intentional spaces before a subsequent base to prevent long
  adjacent readings from overlapping.
- Record source-offset edits alongside ruby annotations. Remap later emphasis ranges through those
  edits. `RubyTextView` applies the same edits to its styled text after decoding emphasis controls,
  preserving colour/quote spans and matching the parser's body offsets.
- A second reading without a new base stays literal, rather than reusing the previous owner or
  causing an invalid base-start interval.

No API representation, prompt, OCR, cache payload, saved history, translation retry, ruby font ratio,
alignment, dialogue geometry or renderer fitting policy changes. Bilingual Japanese source remains
inline and unnormalized. Existing English word dots and ML Kit's noise-only policy are retained.

## Verification

Twelve parser regressions include the exact logged two-row response, Unicode/repeated separators,
English word spacing, Chinese inference, punctuation, repeated owners, UTF-16 boundaries, orphan
limits, malformed/adjacent readings, emphasis remapping, wrapping/truncation and inline-original mode.

Four native graphics regressions use the actual bundled fonts. All three translation targets in
mono/bilingual dialogue and choices produce pixel-identical output for tight/spaced forms, with
visible ruby. They also verify complete long/adjacent reading coverage during joint fitting, correct
playback owner offsets, and an attached native history view's ruby painting and styled spans.
The existing fitting, original-row, ruby endpoint and word-dot suites also pass. No renderer change
was needed after the parser and styled-text fix.

**1,383 tests passed; 0 failures, errors or skips. Debug APK built.**
Command: `:app:testDebugUnitTest :app:assembleDebug`, with existing local response-replay fixtures.
Log: `temp/ruby-spacing-verified.log`. Native previews: `app/build/reports/ruby-spacing/`.
These are native local render tests, not on-device captures. No paid API call, installation or commit.
