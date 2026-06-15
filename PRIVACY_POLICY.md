# FgoGotran Privacy Policy

Last updated: 2026-06-16

FgoGotran is a Fate/Grand Order Japanese-to-Chinese translation overlay. This policy describes what the app accesses and how that data is used.

## Data The App Accesses

- Accessibility events from FGO while the user has enabled the FgoGotran accessibility service.
- Current FGO screen images while the user has started the translation service, used for OCR.
- OCR text recognized from FGO story dialogue and choices.
- User settings stored locally on the device, including selected translation provider, API endpoint, API key, player name, translation cache preference, and glossary update metadata.
- Network status, used only to decide whether online translation or glossary updates can run.

## How Data Is Used

- Accessibility events are used to detect FGO window changes and user taps so the app can refresh translation overlays.
- Screen images are processed for OCR to find Japanese FGO story text. They are not uploaded as images by FgoGotran.
- OCR text is translated and displayed as an overlay above FGO.
- If the user uses an online translation provider, the OCR text and translation prompt are sent to the selected or configured translation API endpoint.
- The player name is used locally to preserve the user's FGO Master name in translations.
- API keys are stored locally on the device and are sent only to the selected translation provider as required for translation requests.
- The glossary updater may contact `https://cdn.fgogotran.com/db/zh-Hans/latest/manifest.json` and download a verified glossary database package.

## Data The App Does Not Access Intentionally

FgoGotran is not designed to read contacts, SMS, call logs, passwords, banking apps, emails, photos, microphone audio, location, or browser history.

## Data Sharing

FgoGotran does not sell user data and does not use data for advertising.

When online translation is enabled, recognized dialogue text is shared with the translation provider selected by the user. That provider may process data according to its own privacy policy. Users can choose a custom OpenAI-compatible endpoint in the app settings.

## Permissions

- Accessibility Service: used only for FGO translation automation, OCR capture, overlay refresh, and forwarding user taps from the translation overlay to FGO.
- Display over other apps: used to show the floating translation button, menu, and translated text above FGO.
- Internet and network state: used for online translation and glossary updates.
- Foreground service and notification permission: used to keep the user-visible translation service running.
- Battery settings shortcut: optional; helps users find Android battery settings for stable overlay behavior during gameplay.

## User Control

Users can disable the accessibility service, overlay permission, notification permission, or battery optimization exemption from Android system settings. Users can also remove API keys and change translation providers in the app settings.

## Contact

For privacy questions, contact the app maintainer or the distribution channel where you received FgoGotran.
