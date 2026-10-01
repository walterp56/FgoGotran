# FGO story text colours

## Phase 1: source evidence

Scanned on **2026-10-01**: **8858 / 8858 JP scripts**, **246 wars**, **46 distinct RGB tags**, **6965 tag occurrences**, **0 download errors**.

These are original game-script RGB commands extracted from assets mirrored by [Atlas Academy](https://atlasacademy.io/apps/), not a palette specification published by the FGO publisher. The [JP war export](https://api.atlasacademy.io/export/JP/nice_war.json) links the scripts. [Chaldea's script parser](https://github.com/chaldea-center/chaldea/blob/main/lib/app/modules/script/script_data.dart) interprets six-hex-digit commands as RGB text colours.

Coverage includes indexed main story, events, interludes, and related quests. Unlinked assets and separate Valentine gift scripts are not exhaustive. Untagged normal white text is not counted. Tag counts are not counts of names, visible dialogue lines, or independent screenshots; some tags can be used in other story presentations. Family labels below are research groupings, not official names. This inventory is **not** the runtime palette and does not prove every shade appears in every UI region.

| Game RGB tag | Detection family | Tags | Scripts | Example original asset |
| --- | --- | ---: | ---: | --- |
| #51FFFF | CYAN | 1782 | 60 | [asset](https://static.atlasacademy.io/JP/Script/03/0300000210.txt) |
| #FFFFFF | NEUTRAL (legacy white) | 1444 | 933 | [asset](https://static.atlasacademy.io/JP/Script/01/0100040310.txt) |
| #B86349 | BROWN | 1116 | 17 | [asset](https://static.atlasacademy.io/JP/Script/03/0300051440.txt) |
| #51D4FF | CYAN | 379 | 29 | [asset](https://static.atlasacademy.io/JP/Script/01/0100000010.txt) |
| #9BB9FF | BLUE | 354 | 18 | [asset](https://static.atlasacademy.io/JP/Script/03/0300050540.txt) |
| #93CA76 | GREEN | 347 | 6 | [asset](https://static.atlasacademy.io/JP/Script/03/0300070920.txt) |
| #DA4A67 | PINK | 241 | 6 | [asset](https://static.atlasacademy.io/JP/Script/94/9415/9415440211.txt) |
| #D9FF69 | YELLOW_GREEN | 228 | 15 | [asset](https://static.atlasacademy.io/JP/Script/04/0400070630.txt) |
| #6680FF | BLUE | 161 | 11 | [asset](https://static.atlasacademy.io/JP/Script/04/0400030311.txt) |
| #60EEFF | CYAN | 150 | 8 | [asset](https://static.atlasacademy.io/JP/Script/94/9408/9408790710.txt) |
| #FF69B4 | PINK | 107 | 2 | [asset](https://static.atlasacademy.io/JP/Script/02/0200040151.txt) |
| #E5BC7C | ORANGE | 92 | 2 | [asset](https://static.atlasacademy.io/JP/Script/94/9406/9406220210.txt) |
| #FF0000 | RED | 88 | 29 | [asset](https://static.atlasacademy.io/JP/Script/01/0100000320.txt) |
| #E8DECD | ORANGE | 48 | 1 | [asset](https://static.atlasacademy.io/JP/Script/02/0200021640.txt) |
| #EBD8EB | PURPLE | 44 | 1 | [asset](https://static.atlasacademy.io/JP/Script/02/0200021640.txt) |
| #8D90D7 | BLUE | 42 | 1 | [asset](https://static.atlasacademy.io/JP/Script/02/0200021640.txt) |
| #B8B3D1 | PURPLE | 39 | 1 | [asset](https://static.atlasacademy.io/JP/Script/02/0200021640.txt) |
| #8A7AB0 | PURPLE | 30 | 1 | [asset](https://static.atlasacademy.io/JP/Script/02/0200021640.txt) |
| #907CA1 | PURPLE | 30 | 1 | [asset](https://static.atlasacademy.io/JP/Script/02/0200021640.txt) |
| #FF143C | RED | 29 | 2 | [asset](https://static.atlasacademy.io/JP/Script/04/0400042440.txt) |
| #C3B1C6 | PURPLE | 28 | 1 | [asset](https://static.atlasacademy.io/JP/Script/02/0200021640.txt) |
| #D72727 | RED | 20 | 3 | [asset](https://static.atlasacademy.io/JP/Script/01/0100080110.txt) |
| #DE5052 | RED | 17 | 1 | [asset](https://static.atlasacademy.io/JP/Script/94/9406/9406220310.txt) |
| #FFFF00 | YELLOW | 15 | 4 | [asset](https://static.atlasacademy.io/JP/Script/94/9401/9401000110.txt) |
| #00FFFF | CYAN | 14 | 5 | [asset](https://static.atlasacademy.io/JP/Script/91/9125040121.txt) |
| #FFE3B6 | ORANGE | 14 | 1 | [asset](https://static.atlasacademy.io/JP/Script/94/9400/9400641410.txt) |
| #A1B6DF | BLUE | 12 | 2 | [asset](https://static.atlasacademy.io/JP/Script/04/0400020731.txt) |
| #BB8F69 | BROWN | 12 | 4 | [asset](https://static.atlasacademy.io/JP/Script/04/0400020660.txt) |
| #FF1E32 | RED | 11 | 3 | [asset](https://static.atlasacademy.io/JP/Script/94/9413/9413890321.txt) |
| #EED720 | YELLOW | 10 | 1 | [asset](https://static.atlasacademy.io/JP/Script/02/0200021640.txt) |
| #F4906D | ORANGE | 10 | 1 | [asset](https://static.atlasacademy.io/JP/Script/94/9400/9400641410.txt) |
| #08FA55 | GREEN | 9 | 1 | [asset](https://static.atlasacademy.io/JP/Script/03/0300131880.txt) |
| #FF4040 | RED | 9 | 3 | [asset](https://static.atlasacademy.io/JP/Script/05/0500015121.txt) |
| #3EE6FE | CYAN | 7 | 1 | [asset](https://static.atlasacademy.io/JP/Script/91/9125060130.txt) |
| #797979 | NEUTRAL | 6 | 1 | [asset](https://static.atlasacademy.io/JP/Script/91/9125020110.txt) |
| #8753D1 | PURPLE | 5 | 5 | [asset](https://static.atlasacademy.io/JP/Script/04/0400020660.txt) |
| #D95D97 | PINK | 4 | 4 | [asset](https://static.atlasacademy.io/JP/Script/04/0400020750.txt) |
| #DBADD6 | PINK | 2 | 2 | [asset](https://static.atlasacademy.io/JP/Script/04/0400020760.txt) |
| #FFD14C | YELLOW | 2 | 2 | [asset](https://static.atlasacademy.io/JP/Script/04/0400069120.txt) |
| #2AB2BF | CYAN | 1 | 1 | [asset](https://static.atlasacademy.io/JP/Script/04/0400020630.txt) |
| #62CEE4 | CYAN | 1 | 1 | [asset](https://static.atlasacademy.io/JP/Script/04/0400021630.txt) |
| #677B32 | GREEN | 1 | 1 | [asset](https://static.atlasacademy.io/JP/Script/04/0400020920.txt) |
| #8F8CD8 | BLUE | 1 | 1 | [asset](https://static.atlasacademy.io/JP/Script/04/0400020660.txt) |
| #B0209C | PINK | 1 | 1 | [asset](https://static.atlasacademy.io/JP/Script/04/0400020531.txt) |
| #E0DAC5 | ORANGE | 1 | 1 | [asset](https://static.atlasacademy.io/JP/Script/04/0400020741.txt) |
| #E7292D | RED | 1 | 1 | [asset](https://static.atlasacademy.io/JP/Script/04/0400021650.txt) |

The missing shades are not only purple/pink: the old red-dominance test could classify **brown `#B86349`**, **gold `#FFD14C`**, and **rose `#DA4A67`** as red. The old four render swatches also collapsed green and blue variants or fell back to white.

## Phase 2: approved runtime palette

- One pure-Kotlin `FgoStoryTextPalette` owns the RGB samples, name-ink families, and shared coloured-ink/red predicates.
- Dialogue and names use seven swatches. Four existing sample indices, exact legacy swatches, and their tolerances stay unchanged: white `#F5F5F0`, red `#DC0000`, cyan/blue `#50EBEB`, green/lime `#C5E35E`. The only additions are pink `#FF69B4`, purple/lavender `#8753D1`, and brown `#B86349`.
- Master choices use only white and red, including colour voting, punctuation recovery, binary enhancement, ruby base-span masks, and visual freshness checks. Cyan UI borders are still detected by their existing, separate geometry logic.
- New swatches render their exact RGB value. Existing choice red-priority remains, with a red-hue guard and approved-colour exclusion so brown/pink and warm gold/orange highlights cannot trigger that shortcut. Dim brown on a black background is also excluded; the existing dim-red fixture remains accepted.
- Dialogue emphasis-dot cleanup and punctuation masks share the seven-colour story predicate. Neutral-pixel thresholds and geometric ruby/punctuation safeguards remain.
- The three additions allow RGB distance 55 (squared distance 3025) for screenshot variation. Gold, orange, grey, olive, other individual shades, and their unused runtime families were removed. Similar pixels can still fall within an approved swatch's tolerance; this is pixel classification, not reading exact script tags.
- Votes still require the existing minimum pixel count and name winner-confidence check. Pixel colour alone is not proof of a glyph; existing OCR bounds and name-neighbour contrast checks remain necessary.
- No nameplate cyan-line detector, crop coordinates, text layout, font sizing, translation prompt, battle path, or history format was changed. The renderer/history already accept arbitrary ARGB colours.

Workflow: existing crop/capture → existing OCR and region-specific annotation/punctuation cleanup → region-specific glyph colour vote → existing render instruction → overlay/history. No new screenshot, OCR stage, translation request, scaling, or full-image scan was added. Ordinary OCR still receives the existing raw crops; limiting choice colours does not discard recognized choice text.

The existing Paddle edge-punctuation probe can accept matching pink/purple/brown edge ink only for dialogue, after supported-colour evidence in the recognized body and the existing punctuation geometry checks. Its global brightness floor is not lowered. General/choice OCR keeps the old edge-probe behaviour and the existing dialogue retry cap remains unchanged.

## Response-time impact and limitations

Colour voting now checks at most **seven story samples** or **two choice samples**, instead of the previous broad 49-entry palette. Additional-family matching checks only three samples instead of 45. RGB channels are precomputed; no per-pixel objects are created. Existing neutral/bright checks retain short-circuit paths. The dialogue edge probe adds a small vote array and foreground reference per existing probe, not another pixel-array read or scan. Compared with the original four-colour implementation, story colour checks add bounded CPU work.

There is no new unconditional OCR/model call, but a genuinely missed coloured punctuation mark can now activate the already-existing, capped edge-recognition retry. Response time is therefore not guaranteed unchanged for those recovered cases. Phone latency has not been measured.

Each classified region still renders one dominant colour. This change does **not** preserve multiple differently coloured words inside a translated line; that would require text-span alignment, not just a palette update. Compression, transparency, dark art matching a source colour, and future unlisted tags can still need screenshot validation. Do not broaden the cyan UI-line predicate to solve text-colour failures.

## Verification

- `testDebugUnitTest assembleDebug --offline` passed: **262 tests in 38 suites**, zero failures/errors/skips. `git diff --check` passed.
- Regression fixtures cover the seven approved story swatches, white/red-only choice voting and punctuation recovery, compressed and dim-colour rejection, exact legacy render colours, existing cyan thresholds, background rejection, ruby-dot separation, emphasis-dot removal, main-glyph preservation, standalone pauses, and colour-matched edge evidence.
- No live phone run or latency measurement is claimed.

Reproduce the research using Python 3.10+:

```text
python -B scripts/research-fgo-story-colours.py --output build/fgo-story-colours/jp_palette.json
```

The report stores only colour metadata and source URLs, never story text. Source index SHA-256: `e124e7f750dec4737b1f84d2574e4e152f1667c3593343744aa1e0274cbc0ce7`.

