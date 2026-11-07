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
- HARD RULE: never generate artwork or audio. Use extracted emojis (Noto Color Emoji) or borrowed art as placeholders.
  Every image slot must load a user-replaceable file (assets/icons/, listed in its README); never overwrite existing icon files
- No Google dependencies at runtime (the target device runs without Google services)
- Test device: FiiO JM21 (Android 13)

<!-- architecture -->
## Architecture (as of the library database step)
- `LibraryViewModel`: library state (folders, songs, playlists, stats), scans, owns `Playback`
- `LibraryDb`: SQLite (SQLiteOpenHelper, no Room). `folders` is user data; songs/playlists/meta are a scan cache
- `LibraryScanner` + `MediaScanner`: SAF folder walk (DocumentsContract), tags via MediaMetadataRetriever, quick vs full scans
- `PlaylistParser`: .m3u/.m3u8/.pls decoding and entry-to-song matching
- `Playback` + `PlayQueue`: ExoPlayer holds only the current song; general queue + play next queue + history;
  MediaSession with a ForwardingPlayer routes hardware next/prev/play to the queue logic
- No background service for playback on purpose: the target device kills background processes. The one exception is the
  opt-in "Open on wake" `WakeLaunchService` (WakeLaunch.kt): foreground service, ACTION_USER_PRESENT receiver,
  opens the app via the "Display over other apps" permission; restarted at boot and on app start; pref "wake_launch".
  Its launch carries `EXTRA_SHOW_SPLASH` (SINGLE_TOP), and `MainActivity.onNewIntent` replays the splash
- UI: `LibraryScreen` (menu, lists), `QueueScreen`, `NowPlayingBar`, `SettingsScreen`
- `LibraryIndex.kt`: `ArtistSplitter` (delimiters + whitelist, stored in SharedPreferences "settings"),
  `LibraryGrouping` (albums = album tag + album artist, or folder when no album artist; artists = split
  track + album artist tags). Rebuilt in the background whenever songs or split settings change
- `Playback.playAll(list, shuffled)`: Play all / Shuffle buttons; clears both queues and the history
- DB v2: `song_stats` (date added, plays, last played) is user data that scans never wipe.
  Date added = file mtime when a scan first sees the song. A play counts once half the song
  (max 4 min) has played (`Playback.checkPlayCount`, polled every 2 s)
- Smart lists in `LibraryViewModel`: most played (top 100), recently added (newest 250), long (> 10 min)
- Theme: `Theme.kt` (accent #1A6E5C; dark scheme uses lighter `secondary` for highlighted text)
- Covers: `Artwork.kt` (embedded picture, else cover/folder/front image via `SiblingFiles`; memory + disk
  cache in cacheDir/art). Custom covers: DB v3 `custom_art` (keys "album:<key>", "playlist:<uri>")
- `NowPlayingScreen.kt`: full-screen player, lyrics view, add-to-playlist and delete dialogs
- `Lyrics.kt`: sidecar .lrc/.txt, else embedded (Media3 MetadataRetriever: ID3 USLT, Vorbis LYRICS, MP4)
- `PlaylistWriter.kt`: append to .m3u/.m3u8, create .m3u8; needs write permission on the folder tree
- `MediaIcons.kt`: play/pause/skip vectors (Material Icons paths; icons-core lacks them; icons-extended avoided on purpose).
  `TransportIcon` uses assets/icons/button_<play|pause|previous|next|stop|shuffle>.png instead when present
- Menu icons: PNGs in app/src/main/assets/icons/ (`AppIcons.kt`: menu_<section>.png, action_<item>.png, header_play_next/header_queue.png for the queue headers; actions matched
  by dropdown label). The user replaces them with their own; scripts must never overwrite existing icon files
- Audio output: `AudioOutput.kt` `OutputMode` (AudioTrack 16-bit, Hi-Res = float output via
  `DefaultRenderersFactory.setEnableAudioFloatOutput`, Offload via `AudioOffloadPreferences`), stored as
  "output_mode" in "settings". `Playback.changeOutputMode` rebuilds the ExoPlayer, keeps song/position/queues and
  calls `session.setPlayer`. `Playback.outputInfo` (from an AnalyticsListener) shows file format, decoder, AudioTrack
  format and Android 13's `getDirectPlaybackSupport` hint in Settings
- Native output modes (OpenSL ES, AAudio): `NativeAudioSink.kt` is an ExoPlayer `AudioSink` over JNI to
  `app/src/main/cpp/native_audio.cpp` (ring buffer filled by the playback thread, drained by the audio callback;
  position = frames rendered). PCM 16/24/32/float, mono/stereo, no speed change. AAudio tries exclusive + low latency,
  falls back to shared. NDK 27.3.13750724 + CMake 3.22.1; native code built for arm64-v8a only
- `Playback.stop()`: stops the player, clears general + play next queues, history (`PlayQueue.clearAll`) and `nowPlaying`, so the bottom bar hides and `LibraryScreen` closes the now playing screen; covers are cached per album (`ArtSource.FromSong.key`)
- Visualizer (VIS button on now playing, replaces the cover like LRC): `AudioAnalyzer.kt` — `AnalyzingAudioSink`
  (ForwardingAudioSink around every output sink) feeds PCM to `AudioAnalyzer` (FFT ~20 Hz bins, 48 log bands,
  bass/mid/high levels, bass-energy beat detection, spectral flux). Frames are stamped in renderer time and released
  by `poll()` when the sink's own position reaches them, so visuals match what's heard. Only runs while VIS is shown;
  no data in offload mode. `Visualizer.kt`: styles Bass bloom, Spectrum, Warp, Liquid (tap to cycle)
- Quick Settings tile "Media" (`MediaTileService`, icon from optional assets/icons/tile_media.png): opens the app and collapses the shade
- Output device: `OutputDevices` (AudioOutput.kt) lists music outputs (speaker, wired, USB, BT, ...), key =
  "type|product|address" so it survives reconnects; stored as "output_device" in "settings". `Playback` tracks devices
  with an AudioDeviceCallback and calls `player.setPreferredAudioDevice` (reaches every sink, incl. through
  AnalyzingAudioSink). `NativeAudioSink.setPreferredDevice` reopens AAudio with `AAudioStreamBuilder_setDeviceId`;
  OpenSL ES can't pick a device
- Shuffle is saved as "shuffle" in "settings" (`Playback.onShuffleChanged`), restored at startup
- Startup splash: `Splash.kt` overlay in `MainActivity` (optional assets/icons/splash.png + name; fade in, hold, slow fade out; never blocks touches).
  Times in `SplashConfig.kt` (edit + rebuild; all 0 = no splash). The system launch screen is made plain black in
  res/values-v31/themes.xml so it blends in
