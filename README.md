# Auto Lyrics

<p align="center">
  Synchronized lyrics for Android and Android Auto—matched to whatever is playing.
</p>

<p align="center">
  <img alt="Android" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-1.9-7F52FF?logo=kotlin&logoColor=white">
  <a href="https://github.com/the-jolly-green-bryant/auto-lyrics/actions/workflows/build.yml"><img alt="Build" src="https://github.com/the-jolly-green-bryant/auto-lyrics/actions/workflows/build.yml/badge.svg"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-MIT-blue"></a>
</p>

Auto Lyrics follows the active Android media session, finds the best available
lyrics, and keeps the current line—or word—aligned with playback. It works as a
phone companion and as a sideloaded Android Auto media app.

## Highlights

- **Player-agnostic playback tracking** through Android media sessions
- **Karaoke, synchronized, and plain lyrics** with graceful fallback behavior
- **Two lyrics providers**: SyncLRC first, then LRCLIB
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

- Android Studio Hedgehog or newer
- Android SDK 34
- JDK 17
- A physical device running Android 8.0 (API 26) or newer

### Build and install

```bash
git clone https://github.com/the-jolly-green-bryant/auto-lyrics.git
cd auto-lyrics
./gradlew installDebug
```

Then:

1. Open Auto Lyrics on the phone.
2. Grant notification access when prompted.
3. Start playback in a media app.
4. Open Auto Lyrics on the phone or in Android Auto.

No lyrics-provider API key is required.

## Android Auto setup

Sideloaded car apps require Android Auto developer mode:

1. Open Android Auto settings on the phone.
2. Tap **Version** ten times to enable developer mode.
3. Open **Developer settings** from the overflow menu.
4. Enable **Unknown sources**.
5. Reconnect the phone to the car or Desktop Head Unit.

For emulator testing, install the Android Auto Desktop Head Unit from Android
Studio's SDK Tools and follow the
[official DHU setup](https://developer.android.com/training/cars/testing/dhu).

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

GitHub Actions builds an APK for pull requests and pushes to `main`. Successful
`main` builds also publish a prerelease containing the versioned APK and its
SHA-256 checksum. Download the latest artifact from
[Releases](https://github.com/the-jolly-green-bryant/auto-lyrics/releases).

## Known constraints

- Lyrics depend on provider coverage and may be synchronized, plain text, or absent.
- Player compatibility depends on the quality of metadata exposed through its media
  session.
- Google Play distribution would require review under Android Auto's supported app
  categories; the current workflow is intended for sideloading.

## License

Released under the [MIT License](LICENSE).
