# Media Player (+ Player Remote)

An offline-first Android music player written in Kotlin and Jetpack Compose, built for a
**FiiO JM21** digital audio player (Android 13, no Google services), plus **Player Remote**, a
companion app that controls it from a phone over Wi-Fi or Bluetooth.

The project was "vibecoded": written together with an AI coding assistant (Claude). `CLAUDE.md`
holds the architecture notes it works from, and they're also the best map of the code.

No Google libraries or services at runtime, no analytics, no telemetry. The only network
traffic is to servers you set up (Jellyfin), Deezer for artist pictures (optional, can be
switched off) and Last.fm in the companion (only if you sign in).

## Media Player (the main app, `app/`)

**Library**
- Pick music folders (Storage Access Framework); quick and full scans into a local SQLite cache
- Albums, artists, songs, playlists (`.m3u`, `.m3u8`, `.pls`), most played, recently added, long songs
- Artist-name splitting with your own delimiters (`feat.`, `&`, `,` ...) plus a whitelist of names never to split
- Search across songs, albums and artists (accent- and case-insensitive)
- Covers from embedded art or `cover.jpg`/`folder.jpg`, custom covers per album, playlist and artist
- Artist pictures from Deezer on Wi-Fi (exact name match), else the newest album's cover
- Synced (`.lrc`) and plain lyrics, from sidecar files or embedded tags
- Jellyfin as a separate library section (original-file streaming, no transcoding)

**Playback**
- A visible, editable queue: the general queue (shuffled order shown) plus a separate *Play next* queue
- *Play next* / *Play last*, reorder, remove, tap any queued song to jump to it
- Swipe the mini player or the cover to change songs, with a preview of what's coming
- Output modes like Poweramp: AudioTrack 16-bit, Hi-Res (32-bit float), offload, **OpenSL ES** and
  **AAudio** (native, via JNI), plus output device selection
- Remappable volume and media buttons
- Visualizer (bass/mids/beat reactive, synced to what you hear)
- Media notification with seek bar, Quick Settings tile, startup splash
- *Open on wake* and *Stay on top* (a Pure Music-style mode that keeps the player in front)

**Control and network**
- Web control: a JSON API on your local network (off at every start; access key; list limits)
- The same API over classic Bluetooth (RFCOMM) for the companion app
- Kill switches for internet access, local-network requests and Bluetooth requests
- Optional YubiKey (USB, HMAC challenge-response) lock for the Jellyfin sign-in

## Player Remote (the companion app, `companion/`)

- Player screen (no seek bar while the player plays), playlists, search (capped results), queue and *Play next*
- Connects over Wi-Fi or Bluetooth to a paired player, and keeps retrying every 5 s while it's out of reach
- Media notification with controls
- **Listen on this phone**: pauses the player, downloads the current song temporarily and plays it on the phone
  with a seek bar; next/previous follow the player's queue; "Back to the player" hands the position back.
  Refuses downloads that wouldn't leave 300 MB free
- Last.fm scrobbling (bring your own API key; your password is only used once and never stored)

## Building

You need:
- JDK 17
- Android SDK with platform 35
- NDK **27.3.13750724** and CMake **3.22.1** (for the native OpenSL ES / AAudio output; built for `arm64-v8a` only)

Point Gradle at your SDK with a `local.properties` (`sdk.dir=/path/to/Android/Sdk`) or `ANDROID_HOME`.
The repo's `.gitignore` expects an optional `env.sh` that exports `JAVA_HOME`, `ANDROID_HOME` and `PATH`
for your machine.

```bash
./gradlew :app:assembleDebug          # the player
./gradlew :companion:assembleDebug    # Player Remote
```

Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk` (and
`companion/build/outputs/apk/debug/companion-debug.apk` on the phone).

## Using the companion

1. On the player: Settings → Network → **Web control** (Wi-Fi) or **Bluetooth control** (pair the devices
   first in Android's Bluetooth settings). Both use the same access key, shown on those pages.
2. In Player Remote → Settings: choose Wi-Fi (address + port) or Bluetooth (pick the paired player), enter the key.

The API's endpoints are listed at the top of `app/src/main/java/com/example/mediaplayer/WebControl.kt`.

## Icons and artwork

Every icon is a replaceable PNG in `app/src/main/assets/icons/` (and `companion/src/main/assets/icons/`),
listed in the README there; drop in your own with the same name and rebuild. No artwork in this repo is
AI-generated. The icons are Google's **Material Symbols** (Apache License 2.0) on a green circle; older
placeholders are **Noto Color Emoji** (Apache 2.0 / SIL OFL). `icon-backups/` keeps previous icon versions.
The app icon is the outline of the startup logo (`splash.png` / `app_icon.png`).

## Other files

- `CLAUDE.md`: architecture notes for the AI assistant (and for you)
- `add-*.sh`, `SETUP-NOTES.txt`: setup scripts from the project's early days, kept for history; not needed to build

## License

No license has been chosen yet.
