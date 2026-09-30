# FgoGotran Privacy Policy

Last updated: 2026-09-30

FgoGotran is a Fate/Grand Order Japanese-to-Chinese/English translation overlay. This policy describes what the app accesses and how that data is used.

## Data The App Accesses

- Accessibility events from FGO while the user has enabled the FgoGotran accessibility service.
- Current FGO screen images while the user has started the translation service, used for OCR.
- OCR text recognized from FGO story dialogue and choices.
- FGO playback audio, only when the user explicitly enables real-time voice translation and starts the translation service. Android exposes this through the `RECORD_AUDIO` permission, but FgoGotran configures playback capture rather than a microphone source.
- User settings stored locally on the device, including selected interface language, translation target language, translation provider, API endpoint, API key, Master name and gender, OCR engine, translation cache preference, voice settings, and glossary update metadata.
- Locally stored translation cache entries when caching is enabled, containing source and translated text used for repeated translations.
- Locally stored diagnostic records with event metadata and short text previews, used to troubleshoot failures. These records can be cleared in the app.
- Limited analytics events described in the Analytics section below.
- Network status, used only to decide whether online translation or glossary updates can run.

## How Data Is Used

- Accessibility events are used to detect FGO window changes and user taps so the app can refresh translation overlays.
- Screen images are processed for OCR to find Japanese FGO story text. They are not uploaded as images by FgoGotran.
- OCR text is translated and displayed as an overlay above FGO.
- When real-time voice translation is enabled, eligible FGO playback audio is streamed to the configured Azure Speech resource for Japanese-to-Chinese/English recognition and translation. Audio is not saved by FgoGotran.
- When AI read-aloud is enabled, translated subtitle text is sent to the configured Azure Speech resource for speech synthesis.
- If the user uses an online or local translation provider, the OCR text and translation prompt are sent to the selected or configured translation API endpoint.
- The player name is used locally to preserve the user's FGO Master name in translations.
- API keys are stored locally on the device and are sent only to the selected translation provider as required for translation requests.
- The glossary updater may contact `https://cdn.fgogotran.com/db/zh-Hans/latest/manifest.json` and download a verified glossary database package.
- The version checker may contact `https://cdn.fgogotran.com/app/android/latest/manifest.json` to compare the installed app version with the latest public version.

## Data The App Does Not Access Intentionally

FgoGotran is not designed to read contacts, SMS, call logs, passwords, banking apps, emails, photos, microphone audio, location, or browser history. The optional real-time voice feature captures only playback audio that Android and the source app permit to be captured.

## Data Sharing

FgoGotran does not sell user data and does not use data for advertising.

When text translation is enabled, recognized dialogue text is shared with the translation provider or local server selected by the user. When real-time voice translation or AI read-aloud is enabled, FGO playback audio or translated subtitle text is shared with the Azure Speech resource configured by the user. Online providers may process data according to their own privacy policies. Users can choose a custom OpenAI-compatible text endpoint in the app settings.

Custom endpoints may use unencrypted HTTP only when addressed by a numeric private-network IP. HTTP does not protect translation text or API credentials from other devices able to observe that local network, so it should be used only with a trusted Wi-Fi network and an authenticated local server. Public and hostname-based custom endpoints must use HTTPS.

## Analytics

FgoGotran may send limited app-usage events to `https://cdn.fgogotran.com/api/app-events`. These events contain a randomly generated installation ID, app version, Android version, selected translation target language, translation mode, provider type, and selected game server. They do not contain OCR text, translated text, Master names, API keys, screen images, or audio. This data is used to measure active installations and understand feature usage. FgoGotran does not sell this data or use it for advertising.

## Permissions

- Accessibility Service: used only for FGO translation automation, OCR capture, overlay refresh, and forwarding user taps from the translation overlay to FGO.
- Display over other apps: used to show the floating translation button, menu, and translated text above FGO.
- Internet and network state: used for online or local-network translation and glossary updates.
- Record audio: used only by the optional Android playback-capture API for real-time FGO voice translation; FgoGotran does not select a microphone source.
- Foreground service and notification permission: used to keep the user-visible translation service running.
- Battery settings shortcut: optional; helps users find Android battery settings for stable overlay behavior during gameplay.

## User Control

Users can disable real-time voice translation in the app, revoke record-audio permission, disable the accessibility service, overlay permission, notification permission, or battery optimization exemption from Android system settings. Users can also remove API keys and change translation providers in the app settings. Users can clear the translation cache and diagnostic records in the app settings. Clearing app data also removes the locally stored analytics installation ID.

## Contact

For privacy questions, contact the app maintainer or the distribution channel where you received FgoGotran.
