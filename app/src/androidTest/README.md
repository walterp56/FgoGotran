# Dialogue playback verification

The tests render local fixtures on Android. They do not call OCR, translation APIs,
voice services or start the FGO runner. Fixtures cover all three translation targets,
single/bilingual display, ruby, long text, visible names/choices, Unicode character
boundaries, native ImageView density conversion and tap completion.

Use a dedicated emulator/test device for Gradle's `connectedDebugAndroidTest` task.
Its installation cleanup can uninstall the tested app and remove local data. Do not
use that task on an installation whose settings or history need to be retained.

For an already configured device, build the two APKs, install with replacement, and
invoke the runner directly. These commands do not uninstall or clear app data:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class com.fgogotran.overlay.DialoguePlaybackRenderTest com.fgogotran.test/androidx.test.runner.AndroidJUnitRunner
```

Only update a compatible, debug-signed installation. Never uninstall a differently
signed installation to work around an installation error.

The renderer also leaves `complete.png` and `partial.png` in the app's
`cache/dialogue-playback/` directory for visual inspection. They are local fixtures,
not captures of the user's game or settings.

Timing: the unobstructed 0.5 reference sample shows approximately five Japanese
characters per second. The remaining speeds use proportional multipliers. The
punctuation levels use 0/100/200/300 ms display pauses, independently of speed.
MAX removes character delay while retaining the selected punctuation pauses;
MAX with pause level 0 still presents the complete text immediately.
Fast-forwarded/tapped recordings do not establish FGO's private pause timers, so
these values are not claimed to reproduce every original scripted pause exactly.
