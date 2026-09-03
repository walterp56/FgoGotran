# FgoGotran

FgoGotran is an Android floating translation tool for reading **Fate/Grand Order JP story content**.

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
