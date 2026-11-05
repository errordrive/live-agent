# Live Agent — voice-first autonomous AI agent for Android

A voice-first Android agent that plans a task, operates the phone, verifies
the result, and reports back. Built on Gemini Live (`gemini-3.8-live`, free
tier), with an AccessibilityService for phone control, editable skills,
routines, background execution, and a built-in terminal + Termux integration.

## What it does

- **Voice:** real-time Gemini Live conversation with interruption, live
  transcript, 10 voices, Bangla/English, personas.
- **Phone control:** intents/API first, AccessibilityService fallback,
  screenshot + vision fallback. Open apps, Chrome research, write notes,
  YouTube play, timers, screen lock.
- **Named tasks:** research in Chrome, write a script in the note app,
  play a movie/song on YouTube, stop after 30 minutes and lock the screen,
  run commands in Termux or the built-in terminal.
- **Background:** foreground service + WorkManager timers and routines.
- **Skills:** editable JSON skills in-app, no rebuild needed. Editable system
  prompt with a locked hard-rules block (confirmation for messages,
  deletions, shell commands).
- **Safety:** plan preview for risky steps, undo where possible, full audit
  log, long-term memory with view/edit/forget, encrypted on-device key storage.

## Build (local)

```bash
export JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true" TMPDIR=~/tmp \
  JAVA_HOME=~/tools/jdk-17.0.11+9 ANDROID_HOME=~/tools/android-sdk \
  GRADLE_USER_HOME=/home/hatch/.gradle
./gradlew assembleDebug --no-daemon
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Build (cloud)

Every push to `main` builds the debug APK via
`.github/workflows/build-apk.yml` (Actions → Artifacts,
`live-agent-debug-apk`, kept 30 days).

## First run

1. Install the APK, allow restricted settings.
2. Enter your Gemini API key in the Live tab (stored encrypted on-device only).
3. Enable the AccessibilityService from the guided Settings screen.
4. Allow battery-optimisation exemption for long timers.

## Honest limits

- The AccessibilityService must be enabled manually in Settings; no app can
  enable it for itself.
- This is a sideloaded APK — this category of app is not Play Store eligible.
- Accessibility control can break when another app redesigns its screens;
  the vision fallback reduces this, it does not erase it.
- Phone runtime testing was not done in CI; test on a real device.
