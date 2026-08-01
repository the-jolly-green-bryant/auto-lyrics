# Auto Lyrics

<p align="center">
  Synchronized lyrics for Android and Android Auto—matched to whatever is playing.
</p>

<p align="center">
  <img alt="Android" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Target SDK" src="https://img.shields.io/badge/target_API-36-3DDC84">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white">
  <a href="https://github.com/the-jolly-green-bryant/auto-lyrics/actions/workflows/build.yml"><img alt="Build" src="https://github.com/the-jolly-green-bryant/auto-lyrics/actions/workflows/build.yml/badge.svg"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-all_rights_reserved-blue"></a>
</p>

Auto Lyrics follows the active Android media session, finds the best available
lyrics, and keeps the current line—or word—aligned with playback. It works as a
phone companion and as a sideloaded Android Auto media app.

## Highlights

- **Player-agnostic playback tracking** through Android media sessions
- **Karaoke, synchronized, and plain lyrics** with graceful fallback behavior
- **Resilient provider fallback**: SyncLRC first, then LRCLIB
- **Android Auto integration** with browse-tree lyrics and a fast-updating
  now-playing subtitle
- **Performance Mode** for an immersive, large-format phone display
- **Practical sync controls**, including manual offsets and speech-assisted alignment
- **Transport proxying** for play, pause, and seek controls
- **Adaptive presentation** using colors extracted from album art

## How it works

```text
Active media session
        │
        ▼
   MediaTracker ──────► SyncLRC ──► LRCLIB fallback
        │                    │
        │               LRC / ELRC parser
        │                    │
        ├──────────────► Phone UI
        └──────────────► Android Auto MediaBrowserService
```

`MediaListenerService` observes active sessions and feeds normalized metadata and
playback position into a singleton `MediaTracker`. Lyrics are cached, parsed into
timestamped lines and words, and exposed as a `StateFlow` to the phone and car
surfaces.

## Getting started

### Requirements

- A current stable Android Studio release
- Android SDK 36
- JDK 17
- A physical device running Android 8.0 (API 26) or newer

### Build and install

```bash
git clone https://github.com/the-jolly-green-bryant/auto-lyrics.git
cd auto-lyrics
./gradlew installDebug
```

Then complete the one-time phone setup:

1. Open Auto Lyrics on the phone.
2. Select **Grant notification access** and enable Auto Lyrics. This is how the
   app discovers the active media session; it does not read message content.
3. Start playback in Spotify or another Android media player.
4. Return to Auto Lyrics and confirm that the track title and lyrics appear.

No lyrics-provider API key is required.

## Run the debug app in Android Auto

Android Auto normally hides media apps installed outside a trusted store. For a
local debug build, enable Android Auto's separate developer mode and allow unknown
sources:

1. Install the debug build with `./gradlew installDebug`.
2. On the phone, open **Settings → Connected devices → Connection preferences →
   Android Auto**. The exact path varies by device; searching Settings for
   “Android Auto” is usually fastest.
3. Scroll to **About**, tap **Version and permission info** ten times, and accept
   **Allow development settings?**
4. Open the overflow menu, choose **Developer settings**, then enable
   **Unknown sources**.
5. Reconnect Android Auto. Open the launcher/customize screen and enable **Auto
   Lyrics** if it is not already visible.
6. Start music on the phone before opening Auto Lyrics in the car. Auto Lyrics is
   a companion media browser—it displays and controls the active player's session
   rather than playing its own catalog.

Do not troubleshoot this with Android's ordinary **Developer options** alone;
Android Auto developer mode is a separate setting.

### Test without a car using Desktop Head Unit

Install **Android Auto Desktop Head Unit Emulator** from Android Studio's **SDK
Manager → SDK Tools**. Then choose one connection:

**USB accessory mode (DHU 2.x, recommended)**

```bash
cd "$ANDROID_HOME/extras/google/auto"
chmod +x ./desktop-head-unit   # macOS/Linux, once
./desktop-head-unit --usb
```

**ADB tunnel**

1. In Android Auto's developer settings, select **Start head unit server**.
2. Connect and unlock the phone, then run:

```bash
adb forward tcp:5277 tcp:5277
"$ANDROID_HOME/extras/google/auto/desktop-head-unit"
```

If the app is absent, verify all four prerequisites: the debug APK is installed,
notification access is granted, **Unknown sources** is enabled inside Android
Auto, and Android Auto was reconnected after installation. See Google's
[Desktop Head Unit guide](https://developer.android.com/training/cars/testing/dhu)
for platform-specific USB and emulator troubleshooting.

## Quality checks

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The unit suite covers timestamp parsing, karaoke word timing, and noisy media
metadata cleanup. Android Lint checks the manifest, resources, SDK behavior, and
common correctness issues.

## Project structure

```text
app/src/main/java/com/autolyrics/
├── auto/       Android Auto browser service and boot receiver
├── lyrics/     provider clients, parsing, normalization, and caching
├── media/      media-session discovery and playback tracking
├── model/      shared state and domain models
└── util/       audio sync and album-color extraction
```

See [DEVELOPMENT.md](DEVELOPMENT.md) for the detailed architecture, file map,
preference keys, release behavior, and implementation notes.

## Releases

GitHub Actions runs tests, Android Lint, and a debug build for pull requests and
pushes to `main`. Version tags such as `v2.0.0` additionally create a signed
GitHub release containing the versioned APK and its SHA-256 checksum. Development
artifacts remain attached to their workflow runs for 14 days.

## Known constraints

- Lyrics depend on provider coverage and may be synchronized, plain text, or absent.
- Player compatibility depends on the quality of metadata exposed through its media
  session.
- Google Play distribution would require review under Android Auto's supported app
  categories; the current workflow is intended for sideloading.
- Android Auto deliberately limits text-heavy experiences while driving. The car
  surface uses media-browser and now-playing templates rather than a free-form
  scrolling phone UI.

## License

Published for transparency and portfolio purposes only. All rights are reserved;
see [LICENSE](LICENSE).
