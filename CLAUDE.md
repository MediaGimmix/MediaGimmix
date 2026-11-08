# MediaGimmix: Android media player (vibecoded)

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
- Navigation: bottom navbar (`NavBar` in LibraryScreen.kt, below the now playing bar, only in the Library/Queue
  view): Library, Queue, Search, Settings (assets/icons/nav_*.png) spaced evenly + the Stay on top pin at the end
- Stay on top (`StayOnTop.kt`, like Pure Music mode; "stay_on_top", default off): MainActivity.onStop -> `bringBack`
  after 400 ms, then re-checks every 1.5 s while hidden, unless asleep/locked. Brings back the app's task with
  `AppTask.moveToFront()`, so a picker running inside the task stays on top (and escaping from it gets pulled back);
  `allowLeaving()` (before every external launch) only lets a screen in another task stay up to 3 min. Uses the "Display over other apps" permission and keeps `WakeLaunchService` running (`WakeLaunch.syncService`;
  unlock also brings it back). Pin toggle (`StayOnTopToggle`, white on / black off) on the navbar, now playing and
  search, never in Settings; hidden by Settings → System → Stay on top → "Show the pin" ("stay_on_top_switch").
  Back on the main screen does nothing while it's on
- No background service for playback on purpose: the target device kills background processes. The one exception is the
  opt-in "Open on wake" `WakeLaunchService` (WakeLaunch.kt): foreground service, ACTION_USER_PRESENT receiver,
  opens the app via the "Display over other apps" permission; restarted at boot and on app start; pref "wake_launch".
  Its launch carries `EXTRA_SHOW_SPLASH` (SINGLE_TOP), and `MainActivity.onNewIntent` replays the splash
- UI: `LibraryScreen` (menu, lists), `QueueScreen`, `NowPlayingBar`, `SettingsScreen`
- `LibraryIndex.kt`: `ArtistSplitter` (delimiters + whitelist, stored in SharedPreferences "settings"),
  `LibraryGrouping` (albums = album tag + album artist, or folder when no album artist; artists = split
  track + album artist tags). Rebuilt in the background whenever songs or split settings change
- Tap a queued song to play it (QueueScreen rows, companion Queue tab, API POST /api/queue/general|next/{index}/play?id=):
  general = `PlayQueue.jumpGeneral` (return spot moves to the tapped song; play next still plays after it), play next =
  `PlayQueue.jumpNext` (rows above it are dropped). `Playback.playFromGeneral/playFromNext` push the old song to history
- Swipe to change songs (main app only, not the companion): `TrackSwiper` (TrackSwiper.kt) on the bottom bar (its
  prev/next buttons were removed; play/pause, stop, shuffle stay) and on the now playing cover (buttons kept). The
  neighbour from `PlayQueue.peekNext/peekPrevious` slides in with its cover and info; `Peek.label` says "Moving to Play
  next" / "Moving to the queue" when the next song is in the other queue. Swipe right = `Playback.previousTrack()`
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
- Media notification (`PlayerNotification.kt`, no service): media3 `MediaStyleNotificationHelper.MediaStyle(session)`,
  channel "playback", posted from `Playback.refreshNotification` (start/stop/forget/playWhenReady), ongoing only while
  playing; Android 13's media player draws the seek bar from the session. Cover: `Playback.artFor` (VM's songArt) ->
  large icon + session artwork via `replaceMediaItem` (metadata only, no restart). Buttons -> `NotificationActionReceiver`
  -> `Playback.active`. Optional assets/icons/notification.png. POST_NOTIFICATIONS asked in MainActivity
- End of the queue (`Playback.advance` with nothing left): the last song goes to history and is unloaded
  (`nowPlaying = null`), so the bottom bar and now playing screen close; queues and history are kept (unlike Stop)
- Bottom bar (`NowPlayingBar`): swipe area with 56dp cover, lowered thinner seek bar (12dp track), elapsed left and
  "remaining / length" right (labelSmall), play/pause + stop stacked on the right; no shuffle or skip buttons
- `Playback.stop()`: stops the player, clears general + play next queues, history (`PlayQueue.clearAll`) and `nowPlaying`, so the bottom bar hides and `LibraryScreen` closes the now playing screen; covers are cached per album (`ArtSource.FromSong.key`)
- Visualizer (VIS button on now playing, replaces the cover like LRC): `AudioAnalyzer.kt` — `AnalyzingAudioSink`
  (ForwardingAudioSink around every output sink) feeds PCM to `AudioAnalyzer` (FFT ~20 Hz bins, 48 log bands,
  bass/mid/high levels, bass-energy beat detection, spectral flux). Frames are stamped in renderer time and released
  by `poll()` when the sink's own position reaches them, so visuals match what's heard. Only runs while VIS is shown;
  no data in offload mode. `Visualizer.kt`: styles Bass bloom, Spectrum, Warp, Liquid (tap to cycle)
- App icon (both apps): the build copies assets/icons/app_icon.png (white outline of the logo, made from splash.png
  with Pillow) or else splash.png into generated res `drawable-nodpi/launcher_art.png` (task `copyLauncherArt` in
  each build.gradle.kts); res/mipmap-anydpi-v26/ic_launcher.xml = transparent background + inset art
- Quick Settings tile "Media" (`MediaTileService`, icon from optional assets/icons/tile_media.png): opens the app and collapses the shade
- Output device: `OutputDevices` (AudioOutput.kt) lists music outputs (speaker, wired, USB, BT, ...), key =
  "type|product|address" so it survives reconnects; stored as "output_device" in "settings". `Playback` tracks devices
  with an AudioDeviceCallback and calls `player.setPreferredAudioDevice` (reaches every sink, incl. through
  AnalyzingAudioSink). `NativeAudioSink.setPreferredDevice` reopens AAudio with `AAudioStreamBuilder_setDeviceId`;
  OpenSL ES can't pick a device
- Button remapping (`ButtonMapping.kt`): `HwButton` (Vol up/down, Prev, Play/pause incl. HEADSETHOOK/PLAY/PAUSE, Next;
  `column` = side in the UI) and `ButtonAction` (Normal = untouched, the default; play/pause, next, shuffle toggle, volume,
  seek ±10 s, ...). `ButtonMap` saves "button_<name>" in "settings". `Playback.handleKey` is called from
  `MainActivity.dispatchKeyEvent` (on screen; only way to get volume keys) and the MediaSession callback's
  `onMediaButtonEvent` (background). Settings page "Buttons": two columns of dropdowns split by a `DarkAccent` line.
  Add a button/action = add an enum entry (+ a branch in `Playback.perform`)
- Search: `LibrarySearch` (Search.kt; accent/case-insensitive, every query word must match, ranked exact > prefix >
  word start > other) shared by `SearchScreen` (search button left of Settings, optional assets/icons/button_search.png;
  On phone / Jellyfin chips; artists, albums, songs with "Show all"; a song plays from its album) and the API
  (/api/search[/songs|albums|artists]?q=&limit=). Artist/album opened from search: Back returns to the results (`fromSearch`)
- Network switches (`NetPolicy.kt`, Settings page "Network"; "net_internet", "net_local_api", both default on):
  internet off = `NetPolicy.check` throws before any request to a non-local address (Jellyfin HTTP in `Jellyfin.open`,
  ExoPlayer streams in Playback's ResolvingDataSource, artist pictures); local = loopback/private/link-local/CGNAT/ULA, so a
  home Jellyfin keeps working. Local requests off = web control can't start (and stops), API answers 403
- Artist pictures (`ArtistImages.kt`): Deezer artist search (no key), exact normalized name match only, saved in
  filesDir/artist_images/<sha1(normalized name)>.jpg (.none = no picture, retried after 30 days). `LibraryViewModel`
  fetches while on Wi-Fi (NetworkCallback, ACCESS_NETWORK_STATE) and internet is on, after artist lists change.
  `artistArt`: custom ("artist:<key>" in custom_art, long-press artist) > downloaded > newest album's cover by year tag.
  Shown in artist lists (`PictureRow`), artist pages, search; API /api/artists/{id}/art
- Shuffle is saved as "shuffle" in "settings" (`Playback.onShuffleChanged`), restored at startup
- Startup splash: `Splash.kt` overlay in `MainActivity` (optional assets/icons/splash.png + name; fade in, hold, slow fade out; never blocks touches).
  Times in `SplashConfig.kt` (edit + rebuild; all 0 = no splash); Settings → Startup switch "splash_enabled" (default on). The system launch screen is made plain black in
  res/values-v31/themes.xml so it blends in
- Settings: categories (`SettingsCategory`) -> pages (`SettingsPage`). Audio: Audio, Buttons. Network: Network switches,
  Jellyfin, Web control. Library: Library, Artist names, Artwork cache, Artist artwork. System: Startup, YubiKey, Stay on top.
  BackHandler goes page -> category -> list. The user names the category for new settings; page names are ours
- About (`AboutInfo`, under the category list): version, BuildConfig.GIT_COMMIT ("+" = uncommitted changes) and
  BUILD_TIME (set in app/build.gradle.kts), device, Android, output mode, library counts, network switches, Media3, Kotlin
- Jellyfin (`Jellyfin.kt`): separate library section (`LibrarySection.JELLYFIN`, openKey paths "albums/<key>" etc.).
  Sign-in via /Users/AuthenticateByName, only the token is stored (prefs "jellyfin"); library cached in
  filesDir/jellyfin_library.json, auto-synced at start when > 12 h old. Songs are `Song`s with rootUri "jellyfin" and
  uri `<server>/Audio/<id>/stream?static=true` (original files); `Jellyfin.authorize` adds the Authorization header in
  Playback's ResolvingDataSource. Covers: `ArtSource.FromJellyfin` (album Primary image); lyrics: /Audio/<id>/Lyrics.
  No play reporting, no downloads. Delete / local playlists are disabled for Jellyfin songs. INTERNET permission +
  usesCleartextTraffic (plain-http home servers)
- Web control (`WebControl.kt` + `WebServer.kt`, no libraries; owned by `LibraryViewModel.web`): NEVER on at startup
  (running state is not saved, by the user's rule); port, access key and "require key" are saved ("web_port",
  "web_key", "web_require_key"). JSON API under /api/ (key via X-Api-Key or ?key=; ?source=jellyfin for the Jellyfin
  library; ids = first 16 hex of SHA-1 of the song uri / "album:<key>" / "artist:<key>"); endpoint list in the KDoc at the top of
  WebControl.kt. API only: the Bootstrap web panel was removed (the user uses scrcpy); non-/api/ paths 404. Library/player access is marshalled to the main thread (`onMain`). Settings page "Web control"
- Bluetooth control (`BluetoothControl.kt`, owned by `LibraryViewModel.bluetooth`): the same API over classic
  Bluetooth RFCOMM (service UUID b0d63ac8-81d4-4769-90ba-e4f52e0da7a8, shared with the companion's BluetoothLink.kt).
  `WebServer.serveStream` serves HTTP/1.1 text with keep-alive on the socket; `WebControl.handleApi(req, viaBluetooth)`.
  Own switch (Settings → Network → Bluetooth control); off at startup unless "Start with the app" ("bt_autostart",
  default off, `startIfWanted()` in the VM init) is on AND the kill switch allows it. Separate policy switch
  "Bluetooth requests" (`NetPolicy.bluetoothApi`, "net_bluetooth_api") next to "Local network requests". All kill
  switches (net_internet, net_local_api, net_bluetooth_api) are saved and always win. BLUETOOTH_CONNECT
  asked when switching on. Same access key as web control
- YubiKey vault (`SecurityKeys.kt`, yubikit-android 3.0.1 — newest without a Kotlin 2.2 stdlib; no Google deps):
  `TokenVault` stores the Jellyfin token plain (prefs "jellyfin") until a key is registered, then AES-GCM encrypted
  (prefs "vault") with a random vault key; each `TrustedKey` keeps a random challenge + the vault key wrapped with
  HKDF-SHA256(HMAC-SHA1 response, challenge). `YubiKeyUsb` (USB only) registers keys (optionally programming slot 1/2
  with a fresh HMAC secret, touch optional) and unlocks when a trusted key is plugged in. `Jellyfin.signedIn` vs
  `account` (null while locked). Settings page "YubiKey": status, Lock now, trusted keys (rename, de-trust), register.
  android:allowBackup="false"

## Companion app ("MediaGimmix Remote", module `companion/`)
Build `./gradlew :companion:assembleDebug`, install on your phone: `adb -s <phone serial> install -r
companion/build/outputs/apk/debug/companion-debug.apk` (the JM21 is picked with `adb -t <transport id>`).
- Talks only to the player's web control API (`Api.kt`, HttpURLConnection + org.json, no libraries). Must never fetch
  whole library lists: search, playlists and single albums/artists/playlists only
- `Connection` (prefs "connection": host, port, key), `RemoteViewModel` polls /api/status every 2 s while resumed
- Tabs (`Section` in MainActivity.kt) are icons (assets/icons/tab_<name>.png, else Material icons): Player (no seek
  bar, on purpose), Playlists, Queue, Search, Settings
- Last.fm (`LastFm.kt`, `Scrobbler.kt`): user's own API key + shared secret; auth.getMobileSession once, only the
  session key is stored (prefs "lastfm"; password never saved). `Scrobbler.onStatus` is fed by every status poll
  (RemoteViewModel.apply + RemoteService.show): now playing at start, scrobble at min(half, 4 min) for songs > 30 s,
  failed scrobbles queued ("pending", retried with the next, dropped after 14 days). Only the companion talks to Last.fm
- Queue tab (`QueueScreen`): submenus Queue (played / now playing / coming up) and Play next, from /api/queue
  (48 back, 99 ahead, generalStart = index of its first general song), refreshed every 4 s; tapping a coming-up or
  play-next song plays it (while listening on the phone: `LocalPlayer.playQueued`, player jumps with keepPaused)
- Playlists/Search (`PlaylistsScreen`, `SearchScreen`, shared `SongListPage` in Browse.kt): "Most results" setting
  (prefs "settings" search_limit, default 100) caps search per kind and the playlist list; songs inside a
  playlist/album/artist load 100 at a time ("Load more"). Tapping a song plays it with ?playlist=/album=/artist=
  context; On the player / Jellyfin chips add ?source=jellyfin. Covers via `RemoteCover` (memory LruCache)
- Connection over Wi-Fi (HTTP) or Bluetooth (`BluetoothLink`: one RFCOMM link kept open, requests serialized, 15 s
  watchdog, reconnect once; paired device picked in Settings via `BluetoothDevices`, a collapsed name + MAC with
  Change/Choose that expands the list). `Api.raw` picks the transport. While the player can't be reached, the app
  (`refreshNow` loop) and the notification service retry every 5 s (service shows "Looking for …")
- "Listen on this phone" (`LocalPlayer.kt`, framework MediaPlayer): pauses the player, downloads the current song
  (/api/songs/{id}/download, streamed to filesDir/listen_here — not cacheDir, Android empties caches when storage is
  low) and plays it from the same spot. Player's queue stays in charge: Next/Prev POST player/next|prev?keepPaused=true
  (+force=true for prev) and download the new song while the old one keeps playing; the next song is pre-downloaded
  (cancelled with `BluetoothLink.abort()` when the link is needed). Seek bar on the Player tab and in the notification
  (session gets duration + ACTION_SEEK_TO in local mode). "Back to the player" = POST player/seek?ms= + play.
  `Storage`: songs only download if 300 MB stays free (sizeBytes from the API up front, else Content-Length);
  refused before pausing the player or moving its queue. Files deleted on stop/hand back and at app start
- Media notification (`RemoteService.kt`), no seek bar while the player plays: foreground service (mediaPlayback) started by
  `RemoteViewModel.apply` while the app is open and the player has a song; platform MediaSession with no duration
  and no ACTION_SEEK_TO; polls /api/status every 3 s; stops when the player stops, after 60 s unreachable, or the ✕
  custom action. Settings switch "Media notification" (prefs "settings" notification, default on)
- Icons: Material paths (`MediaIcons.kt`, copied from the app) overridable by companion/src/main/assets/icons/button_*.png
