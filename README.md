# FgoGotran

FgoGotran is an Android floating translation tool for **Fate/Grand Order JP story content and battle subtitles**. It reads the current game screen with OCR, applies an FGO glossary/RAG layer, sends the recognized text to a user-configured translation API, and renders the translation above the game.

| At a glance | |
| --- | --- |
| Source content | FGO JP story dialogue, choices, speaker names, and battle subtitles |
| Interface and output | Supports Simplified Chinese, Traditional Chinese, and English; the interface can also follow the system language |
| Platform | Android 11+ |
| Translation backend | User-provided cloud API key or authenticated local OpenAI-compatible server |
| Local AI | FgoGotran Local for Windows 10/11 x64 with llama.cpp |

## Resources

- [Website](https://fgogotran.com/en/)
- [Downloads](https://github.com/walterp56/FgoGotran/releases)
- [User Guide](https://fgogotran.com/en/guide/)
- [API Guide](https://fgogotran.com/en/api-guide/)
- [Media and demos](https://fgogotran.com/en/media/)
- [FgoGotran Local guide](FgoGotranLocal/README.md)

> [!NOTE]
> On Android 11+, installation may be blocked by Google Play Protect. If needed, install [APKMirror Installer](https://play.google.com/store/apps/details?id=com.apkmirror.helper.prod) first, then install FgoGotran.

## Features

- Floating translation overlay for story dialogue, choices, and speaker names.
- Manual, semi-automatic, automatic, crop, and Battle subtitle modes.
- ML Kit and PaddleOCR engines with selectable OCR behavior.
- Online terminology database updated from the CDN.
- Glossary/RAG protection for character names, classes, Noble Phantasms, places, and other official terms.
- Translation cache with a session LOG for reviewing recognized and translated text.
- Live voice subtitles from eligible FGO playback audio.
- Optional Azure TTS read-aloud.
- Localized update checks and release notes.
- Provider presets for DeepSeek, Zhipu GLM, Alibaba Qwen, OpenAI, Gemini, Claude, and custom or local endpoints.

## Quick Start

1. Download the latest APK from [GitHub Releases](https://github.com/walterp56/FgoGotran/releases).
2. Install the APK. Use APKMirror Installer if Google Play Protect blocks installation.
3. Enable the overlay and Accessibility permissions requested by FgoGotran.
4. Configure a translation provider and API key. For a local model, see [FgoGotran Local](FgoGotranLocal/README.md).
5. Apply the recommended FGO story settings:

```text
Text Speed: MAX
Page Speed: MAX
Punctuation Wait Time: 0
```

6. Start the FgoGotran service, open FGO, and use the floating button or selected translation mode.

## Translation Modes

| Mode | Description |
| --- | --- |
| Manual | Translates when you tap the floating button. |
| Semi-auto | Translates story dialogue automatically and leaves choices for manual confirmation. |
| Full-auto | Watches the screen and refreshes translations automatically. |
| Crop | Lets you select a screen region for OCR and translation. |
| Battle subtitles | Locks onto the battle subtitle area for short in-battle dialogue. |

## Local AI with FgoGotran Local

FgoGotran Local is an optional Windows 10/11 x64 companion for running an authenticated local translation server with llama.cpp.

```powershell
cd FgoGotranLocal
.\Start-FgoGotranLocal.cmd
```

Open `http://127.0.0.1:18081`, then choose the interface language from the page header. The interface, runtime messages, validation errors, logs, and REST responses follow the selected language or `Accept-Language`. GGUF models remain user-managed. See [FgoGotranLocal/README.md](FgoGotranLocal/README.md) for setup and security details.

## Development

### Android

Open the project with Android Studio, or build a debug APK from the command line:

```powershell
.\gradlew.bat assembleDebug
```

Generate release APKs with your own signing key in Android Studio. Do not commit keystores, `key.properties`, `local.properties`, or API keys.

### Website

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

### FgoGotran Local

```powershell
cd FgoGotranLocal
.\Start-FgoGotranLocal.cmd
```

## Project Structure

```text
app/                 Android app source and localized UI resources
web/                 Localized Next.js website and static export
web/messages/        Website text catalogs
FgoGotranLocal/      Windows local-AI setup and control interface
term_builder/        Glossary TSVs and database build scripts
scripts/             APK, DB, voice, and preview release workflows
```

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

FgoGotran does not upload game screenshots. Screenshots are used locally for OCR. When translation is enabled, recognized text and the translation prompt are sent to the configured online or local translation API.

When real-time voice translation is enabled, eligible FGO playback audio is streamed to the configured Azure Speech resource for speech recognition. When AI read-aloud is enabled, translated subtitle text is sent to Azure for speech synthesis. FgoGotran does not store the captured audio. See [PRIVACY_POLICY.md](PRIVACY_POLICY.md).

Before committing, make sure the repository does not contain API keys, AWS secrets, access tokens, Android signing files, local machine paths, debug logs, virtual environments, caches, or generated release packages.

## Contributing

Issues and pull requests are welcome. Keep changes focused, avoid committing generated files or credentials, and update documentation when behavior changes.

## License

Licensed under the [Apache License 2.0](LICENSE).

## Disclaimer

FgoGotran is an unofficial helper tool for understanding FGO JP story text and battle subtitles. Fate/Grand Order and related assets belong to their respective rights holders.
