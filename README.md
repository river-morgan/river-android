# River Android

A native Kotlin Android test harness for the River voice bridge.

The goal is empirical testing, not polish: verify which Android/Google/headphone/background paths work on Erik's actual phone.

## Current MVP

- App name: **River**
- Native Kotlin, no Compose/AppCompat dependency
- Voice capture via Android `RecognizerIntent`
- Transient audio focus request before capture/TTS
- TTS acknowledgement
- Foreground service + persistent notification with **Ask River** action
- Quick Settings tile: **Ask River**
- Launcher shortcut
- Deep link: `river://ask?text=hello`
- Android share target for `text/plain`
- Optional Hermes endpoint POST:
  - JSON body: `{ "source": "river-android", "text": "..." }`
  - Optional bearer token stored locally on the phone only

## Build

```bash
ANDROID_HOME=/path/to/android-sdk ./gradlew assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## First phone test matrix

1. Install APK.
2. Open River and grant microphone/notification permissions.
3. Tap **Speak test acknowledgement**.
4. Play podcast/YouTube audio.
5. Tap **Start River voice session** and check whether media ducks/pauses and resumes.
6. Add the **Ask River** Quick Settings tile and test from notification shade.
7. Test persistent notification action.
8. Test share sheet into River from another app.
9. Test deep link: `river://ask?text=test`.
10. Try Google/Gemini phrases:
    - “Hey Google, open River”
    - “Hey Google, ask River”
    - “Hey Google, tell River”
    - “Hey Google, send a message to River”

## Notes

This is deliberately native Android first because the hard questions are native: audio focus, foreground service behavior, permissions, lock screen, Assistant intents, and headset behavior. Flutter/Shorebird may become useful later for faster UI/app-flow iteration, but it is not the bottleneck for this first harness.
