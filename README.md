# FgoGotran

FgoGotran is an Android floating translation tool for reading **Fate/Grand Order JP story content and battle subtitles**. It can produce Simplified Chinese, Traditional Chinese, or English translations, and its interface can follow the system language or use a manually selected language.

It reads the current FGO screen with OCR, matches FGO character names and terminology from a glossary, sends the text to a user-configured AI translation API, and renders the translated speaker name, dialogue, and choices back on top of the game.

- Website: [https://fgogotran.com/en/](https://fgogotran.com/en/)
- Download: [GitHub Releases](https://github.com/walterp56/FgoGotran/releases)
- User Guide: [https://fgogotran.com/en/guide/](https://fgogotran.com/en/guide/)
- API Guide: [https://fgogotran.com/en/api-guide/](https://fgogotran.com/en/api-guide/)
- Simplified Chinese: [https://fgogotran.com/zh-CN/](https://fgogotran.com/zh-CN/)
- Traditional Chinese: [https://fgogotran.com/zh-TW/](https://fgogotran.com/zh-TW/)

## Features

- Designed specifically for FGO JP story reading, with translation output in Simplified Chinese, Traditional Chinese, or English.
- Application UI languages include System default, Simplified Chinese, Traditional Chinese, and English; update reminders follow the selected UI language.
- Supports manual, semi-auto, auto, crop, and dedicated BATTLE subtitle modes for short in-battle dialogue.
- Uses OCR to recognize FGO JP story dialogue, choice text, and speaker names.
- Uses a glossary/RAG layer with JP/CN/EN names and terminology to keep FGO names, official terms, and story tone stable across target languages.
- Supports user-provided API settings for DeepSeek, Zhipu GLM, Alibaba Qwen China/International, OpenAI, Google Gemini, Anthropic Claude, custom endpoints, and authenticated local models on a trusted LAN.
- Includes FgoGotran Local, a guided Windows x64 setup and control interface in Simplified Chinese, Traditional Chinese, and English for authenticated local AI translation with llama.cpp.
- Downloads the latest online terminology database instead of bundling a local DB inside the APK.
- Includes a translation LOG so users can review translated speaker names, dialogue, and choices from the current session.
- Optionally captures eligible FGO playback audio and streams it to a user-configured Azure Speech resource for low-latency live subtitles.
- Optionally reads translated subtitles aloud with Azure TTS in Chinese or English.

### Battle subtitles

Select `Battle` in the English UI or `BATTLE字幕` in the Chinese UI from the floating menu during FGO JP battles. It uses the selected OCR engine and translation API to display translated dialogue above the original subtitle and record it in LOG. Switch back to a story translation mode after the battle.

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

For a local OpenAI-compatible server such as llama.cpp, enter the phone-reachable Chat Completions endpoint (for example, `http://<PC-LAN-IP>:18080/v1/chat/completions`). Unencrypted HTTP is accepted only for numeric private-LAN addresses and should be used only on a trusted Wi-Fi network. Keep API-key authentication enabled on the local server.

## Project Structure

```text
app/                 Android app source and localized UI resources
web/                 Localized Next.js website and static export
web/messages/        zh-CN, zh-TW, and en website text catalogs
FgoGotranLocal/      Tri-language Windows local-AI setup and control interface
term_builder/        Glossary TSVs, including English terms, and DB build scripts
scripts/             APK, DB, voice, and preview release workflows
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
npm run check:messages
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

The static export includes `/zh-CN/`, `/zh-TW/`, and `/en/` routes.

## FgoGotran Local

FgoGotran Local provides a guided Windows 10/11 x64 setup for authenticated local AI translation with llama.cpp. It can prepare verified Python and llama.cpp components, prefers NVIDIA CUDA with a CPU fallback, and provides a local interface for configuring and monitoring the server. GGUF models remain user-managed.

```powershell
cd FgoGotranLocal
.\Start-FgoGotranLocal.cmd
```

- [http://127.0.0.1:18081/](http://127.0.0.1:18081/) - Simplified Chinese
- [http://127.0.0.1:18081/zh-Hant/](http://127.0.0.1:18081/zh-Hant/) - Traditional Chinese
- [http://127.0.0.1:18081/en/](http://127.0.0.1:18081/en/) - English

The interface, runtime messages, validation errors, logs, and REST responses follow the selected language or `Accept-Language`. Setup and security guidance is available in [FgoGotranLocal/README.md](FgoGotranLocal/README.md).

## Terminology Database

Editable glossary sources:

```text
term_builder/character_names.tsv
term_builder/term.tsv
```

The TSVs include English name and term columns used by the English translation target.

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

FgoGotran does not upload game screenshots. Screenshots are used locally for OCR. When text translation is enabled, recognized text and the translation prompt are sent to the selected online or local translation API. When real-time voice translation is enabled, eligible FGO playback audio is streamed to the configured Azure Speech resource for speech recognition. When AI read-aloud is enabled, translated subtitle text is sent to Azure for speech synthesis. FgoGotran does not store the captured audio. See [PRIVACY_POLICY.md](PRIVACY_POLICY.md).

Before committing, make sure the repository does not contain:

- API keys, AWS secrets, or access tokens.
- Android signing files, keystores, or `key.properties`.
- Local machine paths, debug logs, or build outputs.
- Virtual environments, cache files, or generated release packages.

## Disclaimer

FgoGotran is an unofficial helper tool for understanding FGO JP story text and battle subtitles. Fate/Grand Order and related assets belong to their respective rights holders.
