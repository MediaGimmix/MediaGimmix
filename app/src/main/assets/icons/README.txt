Menu icons. Each PNG here is shown left of a menu item. Replace any file with your
own image (keep the file name; square works best, 96x96 or larger), then rebuild.
Delete a file to show no icon for that item.

Library menu
  menu_artists.png         Artists
  menu_albums.png          Albums
  menu_songs.png           Songs
  menu_playlists.png       Playlists
  menu_most_played.png     Most played
  menu_recently_added.png  Recently added
  menu_long.png            Long (over 10 minutes)
  menu_jellyfin.png        Jellyfin (placeholder: 📡, extracted from Noto Color Emoji)

Menu items (queue rows, song rows, album/playlist tiles, now playing "..." menu)
  action_play_next.png     Play next
  action_play_last.png     Play last
  action_move_up.png       Move up
  action_move_down.png     Move down
  action_remove.png        Remove
  action_play.png          Play
  action_shuffle.png       Shuffle
  action_set_image.png     Set custom image...
  action_reset_image.png   Use song cover / Use first song's cover
  action_go_album.png      Go to album
  action_go_artist.png     Go to artist
  action_show_queue.png    Show queue
  action_song_info.png     Song info

Queue headers, splash and Quick Settings tile
  header_play_next.png     Queue: "Play next" header
  header_queue.png         Queue: "Queue" header
  splash.png               Startup splash, above "Media Player" (shown at 128 dp)
  tile_media.png           "Media" Quick Settings tile. Android tints it to one colour, so only the
                           shape (transparency) matters. Deleted = Android's default tile icon.

Notification (optional)
  notification.png         "Open on wake" notification icon. White on transparent; Android tints it.
                           Without it Android's built-in play icon is used.

Transport buttons (optional; without a file the built-in Material icon is used)
  button_play.png          Play
  button_pause.png         Pause
  button_previous.png      Previous
  button_next.png          Next
  button_stop.png          Stop
  button_shuffle.png       Shuffle (shown dimmed when shuffle is off)
  button_search.png        Search (top bar, left of Settings)

Notification (optional): notification.png is the small icon of the media notification
(white on transparent works best; Android tints it). Without it Android's play icon is used.
The notification's buttons use the button_*.png files above when present.
  These are shown in their own colours, not tinted.

The placeholders are Noto Color Emoji (Apache 2.0 / OFL).
add-menu-icons.sh only creates files that are missing, so your replacements are kept.
