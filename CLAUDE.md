# Android media player (vibecoded)

Kotlin + Jetpack Compose app. Build with `source env.sh && ./gradlew assembleDebug`.
Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## Target features
- Visible, mutable now-playing queue (shuffled order must be visible)
- "Play next" and "Play last"
- Lists: albums, artists, playlists, most played, recently added
- Artist-name splitting via user-defined delimiters, for album artists and
  track artists, plus a whitelist of names that must never be split
- Audio output paths similar to Poweramp: high-res audio, OpenSL ES,
  AudioTrack, etc.
- Lyrics: tracked (synced) and untracked (unsynced)

## Constraints
- No Google dependencies at runtime (the target device runs without Google services)
- Test device: FiiO JM21 (Android 13)
