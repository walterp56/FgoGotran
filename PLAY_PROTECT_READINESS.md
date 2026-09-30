# Play Protect / Google Play Readiness Notes

This checklist is for reducing false positives and making FgoGotran reviewable. It does not guarantee Play Protect approval.

## What Was Added In-App

- Prominent accessibility disclosure before opening Android accessibility settings.
- Explicit, localized affirmative consent button before opening accessibility settings.
- Prominent overlay permission disclosure before opening overlay settings.
- Accessibility service metadata now says the app is not an accessibility tool and describes its narrow FGO translation purpose.
- Accessibility description no longer says "silent screenshot"; it explains user-started screen capture for OCR.
- Accessibility service no longer declares window-content retrieval or interactive-window retrieval.
- Manifest no longer declares `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; users can still open battery settings manually from the app.
- In-app APK downloading/installing is not implemented. The settings page can check the latest public version and open the download page; Play builds should update through Google Play.
- The optional real-time voice setting explains why Android labels playback capture as an “audio recording” permission and states that FgoGotran does not select a microphone source.

## Play Console Declaration Suggested Answers

AccessibilityService API:

- Is this an accessibility tool? No.
- Purpose: App functionality.
- Core feature requiring AccessibilityService: Translate visible Fate/Grand Order JP story dialogue into the selected target language by detecting FGO window/tap events, taking user-started screenshots for OCR, displaying translated overlays, and forwarding user taps from the translated overlay to FGO.
- Does the app collect/share data using accessibility capabilities? Yes, if online translation is used. The app recognizes visible FGO dialogue text and sends that text to the selected translation API provider for translation.
- Data types: app activity / user-generated visible game text. Do not claim contacts, SMS, location, financial data, or passwords unless future code adds them.
- Disclosure video: show opening FgoGotran, tapping start service, reading the accessibility disclosure, tapping the affirmative consent button, enabling the accessibility service, returning to FGO, and using translation.

Foreground service special use:

- Purpose: User-visible FGO translation overlay service.
- Description: Keeps the floating translation button and overlay active while the user is playing FGO.

Data Safety:

- Data shared with third parties: OCR dialogue text and translation prompts sent to the selected text provider; playback audio or translated subtitle text sent to the configured Azure Speech resource for live voice translation or AI read-aloud; limited analytics events sent to `https://cdn.fgogotran.com/api/app-events` with a random installation ID, app and Android versions, target language, translation mode, provider type, and game server.
- Data collected locally: API settings, selected UI and target languages, Master name and gender, translation cache and diagnostic records, analytics installation ID, glossary metadata, and voice settings.
- Data not collected: contacts, SMS, call logs, precise location, microphone audio, banking credentials, browser history, or OCR text, translated text, Master names, API keys, screen images, and audio in analytics. Playback audio and microphone audio must be described separately in the Play form.
- Security: API keys are stored locally and sent only to the selected translation provider.

## Release Path

1. Use the same package name and signing key for every release.
2. Prefer Android App Bundle (`.aab`) upload to Play Console internal testing or closed testing.
3. Provide the privacy policy URL. This repository file can be hosted with GitHub Pages or another HTTPS page.
4. Record a short disclosure video for AccessibilityService review.
5. Keep app description narrow: "FGO Japanese-to-Chinese/English translation overlay" rather than broad "screen automation" wording.
6. If Play Protect flags a release, submit a false-positive review with the signed APK/AAB, privacy policy, source link if public, and the disclosure video.

## Risk That Remains

FgoGotran still uses a sensitive combination: AccessibilityService, overlay permission, screenshot/OCR behavior, optional playback-audio capture, gesture forwarding, and Internet. That is legitimate for this app, but it can still be flagged by automated scanners, especially when sideloaded or signed by a new key with little reputation.
