# FgoGotran

FgoGotran is an Android floating translation tool for reading **Fate/Grand Order JP story content and battle subtitles**.

It reads the current FGO screen with OCR, matches FGO character names and terminology from a glossary, sends the text to a user-configured AI translation API, and renders the translated speaker name, dialogue, and choices back on top of the game.

- Website: [https://fgogotran.com](https://fgogotran.com)
- Download: [GitHub Releases](https://github.com/walterp56/FgoGotran/releases)
- User Guide: [https://fgogotran.com/guide/](https://fgogotran.com/guide/)
- API Guide: [https://fgogotran.com/api-guide/](https://fgogotran.com/api-guide/)

## Features

- Designed specifically for FGO JP story reading.
- Supports manual, semi-auto, auto, and crop translation modes.
- Uses Japanese OCR to recognize story dialogue, choice text, and speaker names.
- Uses a glossary/RAG layer before AI translation to keep FGO names, official terms, and story tone more stable.
- Supports user-provided OpenAI-compatible API settings, including DeepSeek, Qwen, Alibaba Cloud Model Studio, custom endpoints, and authenticated local models on a trusted LAN.
- Downloads the latest online terminology database instead of bundling a local DB inside the APK.
- Includes a translation LOG so users can review translated speaker names, dialogue, and choices from the current session.
- Optionally captures eligible FGO playback audio and streams it to a user-configured Azure Speech resource for low-latency Japanese-to-Chinese subtitles.
- Optional battle-subtitle OCR detects the fixed BATTLE / ENEMY / TURN HUD and translates short on-screen battle dialogue independently of story translation mode.

### Battle subtitles

Enable **Settings → 翻译偏好 → 战斗字幕**, start the floating service, and open FGO JP in landscape. The feature uses the selected OCR engine and text-translation API; Azure is not required. It is off by default.

Battle detection and subtitle cropping use FGO's centered 16:9 layout. Captions appear above the original subtitle and do not intercept taps. Battle-caption typography follows the same viewport scale as FGO's native subtitle, using a 44-pixel reference size at 1920x1080. Soft OCR/API line breaks are reflowed and the background grows to the safe battle-caption width before longer translations wrap, without shrinking the text. Every confirmed OCR occurrence is queued independently of source disappearance or replacement. At most two translations run concurrently, with one API attempt per occurrence; results display in Japanese appearance order. Normally captions remain until one second after the source ends, or a newer ready caption replaces them after the minimum reading time. Late and queued captions always receive at least one second of actual visible time, even after the original window has ended. This prioritizes completeness over perfect synchronization when processing falls behind. OCR-missed lines and failed/timed-out API requests cannot be guaranteed; failures are logged and do not block later successful results.

Battle dialogue uses a dedicated independent prompt with no speaker, character, choice, ruby, or previous-scene context. It retains the shared glossary, first/second-person, honorific, action-direction, katakana, pause, source-fidelity, and FGO punctuation rules. Source punctuation is reconciled locally after translation.

Menus and LOG pause battle-caption display and its reading timer, without deleting queued results. Leaving the foreground pauses battle observation/display; returning resumes delivery. After a battle, the remaining queue can finish while the game remains foreground; automatic story translation waits for it to drain. Stopping the service or disabling the feature cancels pending delivery. Azure voice subtitles and battle subtitles are independent: either feature can be enabled or disabled without changing the other's capture, translation, visibility, timing, position, or cleanup. When both produce text, both overlays may be visible simultaneously; the draggable Azure position can be moved away from the fixed battle-caption area.

Successful battle translations are recorded immediately in LOG as speakerless Chinese/Japanese dialogue, in source occurrence order, even before their on-screen turn. They are excluded from story prompt context. LOG retains all entries without a count limit for the current floating-service run; stopping/restarting the service clears it. It is not permanent storage. The LOG panel recycles visible rows for long sessions, and adding entries does not force a user reading older records to the bottom. Text history still consumes memory as the session grows.

The detector is calibrated against the supplied battle recording, including one- and two-line dialogue and portrait cut-ins. It targets dialogue subtitles, not skill/status banners, damage numbers, or all spoken battle audio. Other game UI layouts and device OCR latency still need on-device validation.

## Installation Note

On Android 11+, or if installation is blocked by Google Play Protect, please use [APKMirror Installer](https://play.google.com/store/apps/details?id=com.apkmirror.helper.prod) to install FgoGotran. Otherwise, Android may block the Accessibility service from being enabled.

## Before Using

Recommended FGO in-game story settings:

```text
Text Speed: MAX
Page Speed: MAX
Punctuation Wait Time: 0
```

FgoGotran also requires Android overlay and Accessibility permissions. A working translation API key is required for AI translation.

For a local OpenAI-compatible server such as llama.cpp, enter the phone-reachable Chat Completions endpoint (for example, `http://192.168.3.18:18080/v1/chat/completions`). Unencrypted HTTP is accepted only for numeric private-LAN addresses and should be used only on a trusted Wi-Fi network. Keep API-key authentication enabled on the local server.

## Project Structure

```text
app/           Android app source code
web/           FgoGotran website, built with Next.js static export
local-ai-studio/  FgoGotran Local AI Studio and llama.cpp process manager
term_builder/  Glossary TSV files and database build scripts
scripts/       Helper scripts for APK, DB, and preview JSON release workflows
```

## Android Development

Open the project with Android Studio.

Build a debug APK from the command line:

```powershell
.\gradlew.bat assembleDebug
```

Generate release APKs with your own signing key in Android Studio. Do not commit keystores, `key.properties`, `local.properties`, or API keys.

## Website Development

```powershell
cd web
npm ci
npm run dev
```

Production build:

```powershell
cd web
npm run typecheck
npm run build
```

Static output:

```text
web/out
```

## Local AI Studio

FgoGotran Local AI Studio can configure, start, stop, and monitor an authenticated `llama-server` instance without exposing its control interface to the phone or LAN.

```powershell
cd local-ai-studio
npm start
```

Open `http://127.0.0.1:18081`. Setup, firewall, and security details are in [local-ai-studio/README.md](local-ai-studio/README.md).

## Terminology Database

Editable glossary sources:

```text
term_builder/character_names.tsv
term_builder/term.tsv
```

Build the database:

```powershell
python -m pip install -r term_builder\requirements.txt
python term_builder\py\ingest_atlas.py --skip-atlas
python term_builder\py\build_db.py
```

Create the release package:

```powershell
.\scripts\release-db.ps1
```

See [term_builder/README.md](term_builder/README.md) for details.

## Privacy and Security

FgoGotran does not upload game screenshots. Screenshots are used locally for OCR. When text translation is enabled, recognized text and the translation prompt are sent to the selected online or local translation API. When the optional real-time voice feature is enabled, eligible FGO playback audio is streamed to the Azure Speech resource configured by the user and is not stored by FgoGotran. See [PRIVACY_POLICY.md](PRIVACY_POLICY.md).

Before committing, make sure the repository does not contain:

- API keys, AWS secrets, or access tokens.
- Android signing files, keystores, or `key.properties`.
- Local machine paths, debug logs, or build outputs.
- Virtual environments, cache files, or generated release packages.

## Disclaimer

FgoGotran is an unofficial helper tool for understanding FGO JP story text. Fate/Grand Order and related assets belong to their respective rights holders.
