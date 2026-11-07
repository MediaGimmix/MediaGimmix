package com.example.mediaplayer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement
import android.net.Uri

/**
 * Stores the media folders, the results of the last scan, and per-song stats.
 *
 * - `folders` and `song_stats` (date added, play counts) are user data and are kept.
 * - `songs`, `playlists`, `playlist_entries` and `meta` are a cache that every
 *   scan rewrites.
 * Call from a background thread.
 */
class LibraryDb(context: Context) : SQLiteOpenHelper(context, "library.db", null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE folders (uri TEXT PRIMARY KEY)")
        createLibraryTables(db)
        createStatsTable(db)
        createCustomArtTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) createCustomArtTable(db)
        if (oldVersion < 2) {
            createStatsTable(db)
            // Existing songs: use the file's modification time as the date added.
            db.execSQL(
                """INSERT OR IGNORE INTO song_stats (uri, added, plays, last_played)
                   SELECT uri, CASE WHEN modified > 0 THEN modified ELSE CAST(strftime('%s', 'now') AS INTEGER) * 1000 END, 0, 0
                   FROM songs"""
            )
        }
    }

    private fun createLibraryTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE songs (
                uri TEXT PRIMARY KEY, root_uri TEXT NOT NULL, file_name TEXT NOT NULL,
                folder_path TEXT NOT NULL, mime TEXT NOT NULL, size INTEGER NOT NULL,
                modified INTEGER NOT NULL, title TEXT, artist TEXT, album TEXT,
                album_artist TEXT, duration INTEGER NOT NULL, track INTEGER NOT NULL,
                disc INTEGER NOT NULL, year INTEGER NOT NULL, sort INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE playlists (
                uri TEXT PRIMARY KEY, root_uri TEXT NOT NULL, name TEXT NOT NULL,
                folder_path TEXT NOT NULL, missing INTEGER NOT NULL, sort INTEGER NOT NULL)"""
        )
        db.execSQL(
            "CREATE TABLE playlist_entries (playlist_uri TEXT NOT NULL, position INTEGER NOT NULL, song_uri TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX playlist_entries_by_playlist ON playlist_entries (playlist_uri, position)")
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)")
    }

    private fun createStatsTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS song_stats (
                uri TEXT PRIMARY KEY, added INTEGER NOT NULL,
                plays INTEGER NOT NULL DEFAULT 0, last_played INTEGER NOT NULL DEFAULT 0)"""
        )
    }

    // ---- folders ------------------------------------------------------------

    fun folders(): List<String> =
        readableDatabase.rawQuery("SELECT uri FROM folders ORDER BY rowid", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    fun addFolder(uri: String) {
        writableDatabase.insertWithOnConflict(
            "folders", null, ContentValues().apply { put("uri", uri) }, SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    /** Removes a folder and everything found in it (play stats are kept). */
    fun removeFolder(uri: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val args = arrayOf(uri)
            db.delete("playlist_entries", "playlist_uri IN (SELECT uri FROM playlists WHERE root_uri = ?)", args)
            db.delete("playlists", "root_uri = ?", args)
            db.delete("songs", "root_uri = ?", args)
            db.delete("folders", "uri = ?", args)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---- loading --------------------------------------------------------------

    fun loadSongs(): List<Song> =
        readableDatabase.rawQuery(
            """SELECT s.uri, s.root_uri, s.file_name, s.folder_path, s.mime, s.size, s.modified,
                      s.title, s.artist, s.album, s.album_artist, s.duration, s.track, s.disc,
                      s.year, COALESCE(st.added, 0)
               FROM songs s LEFT JOIN song_stats st ON st.uri = s.uri
               ORDER BY s.sort""",
            null,
        ).use { c ->
            val list = ArrayList<Song>(c.count)
            while (c.moveToNext()) {
                list.add(
                    Song(
                        uri = Uri.parse(c.getString(0)),
                        rootUri = c.getString(1),
                        fileName = c.getString(2),
                        folderPath = c.getString(3),
                        mimeType = c.getString(4),
                        sizeBytes = c.getLong(5),
                        lastModified = c.getLong(6),
                        title = if (c.isNull(7)) null else c.getString(7),
                        artist = if (c.isNull(8)) null else c.getString(8),
                        album = if (c.isNull(9)) null else c.getString(9),
                        albumArtist = if (c.isNull(10)) null else c.getString(10),
                        durationMs = c.getLong(11),
                        trackNumber = c.getInt(12),
                        discNumber = c.getInt(13),
                        year = c.getInt(14),
                        dateAdded = c.getLong(15),
                    )
                )
            }
            list
        }

    fun loadPlaylists(songsByUri: Map<String, Song>): List<Playlist> {
        val db = readableDatabase
        val entries = HashMap<String, MutableList<Song>>()
        db.rawQuery("SELECT playlist_uri, song_uri FROM playlist_entries ORDER BY playlist_uri, position", null)
            .use { c ->
                while (c.moveToNext()) {
                    val song = songsByUri[c.getString(1)] ?: continue
                    entries.getOrPut(c.getString(0)) { mutableListOf() }.add(song)
                }
            }
        return db.rawQuery("SELECT uri, root_uri, name, folder_path, missing FROM playlists ORDER BY name COLLATE NOCASE", null)
            .use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val uri = c.getString(0)
                        add(
                            Playlist(
                                uri = Uri.parse(uri),
                                rootUri = c.getString(1),
                                name = c.getString(2),
                                folderPath = c.getString(3),
                                songs = entries[uri].orEmpty(),
                                missing = c.getInt(4),
                            )
                        )
                    }
                }
            }
    }

    /** Play counts for every song that has been played at least once, keyed by song URI. */
    fun loadPlayStats(): Map<String, PlayStat> =
        readableDatabase.rawQuery("SELECT uri, plays, last_played FROM song_stats WHERE plays > 0", null).use { c ->
            val map = HashMap<String, PlayStat>(c.count)
            while (c.moveToNext()) map[c.getString(0)] = PlayStat(c.getInt(1), c.getLong(2))
            map
        }

    fun recordPlay(uri: String, time: Long) {
        val db = writableDatabase
        val updated = db.compileStatement(
            "UPDATE song_stats SET plays = plays + 1, last_played = ? WHERE uri = ?"
        ).use { st ->
            st.bindLong(1, time)
            st.bindString(2, uri)
            st.executeUpdateDelete()
        }
        if (updated == 0) {
            db.insertWithOnConflict(
                "song_stats", null,
                ContentValues().apply {
                    put("uri", uri)
                    put("added", time)
                    put("plays", 1)
                    put("last_played", time)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
        }
    }

    private fun createCustomArtTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS custom_art (key TEXT PRIMARY KEY, image_uri TEXT NOT NULL)")
    }

    // ---- custom covers --------------------------------------------------------

    fun loadCustomArt(): Map<String, String> =
        readableDatabase.rawQuery("SELECT key, image_uri FROM custom_art", null).use { c ->
            val map = HashMap<String, String>()
            while (c.moveToNext()) map[c.getString(0)] = c.getString(1)
            map
        }

    fun setCustomArt(key: String, imageUri: String?) {
        if (imageUri == null) {
            writableDatabase.delete("custom_art", "key = ?", arrayOf(key))
        } else {
            writableDatabase.insertWithOnConflict(
                "custom_art", null,
                ContentValues().apply { put("key", key); put("image_uri", imageUri) },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    // ---- single changes between scans ------------------------------------------

    /** A song's file was deleted. */
    fun deleteSong(uri: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("songs", "uri = ?", arrayOf(uri))
            db.delete("playlist_entries", "song_uri = ?", arrayOf(uri))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun appendPlaylistEntry(playlistUri: String, songUri: String, position: Int) {
        writableDatabase.insert(
            "playlist_entries", null,
            ContentValues().apply {
                put("playlist_uri", playlistUri)
                put("position", position)
                put("song_uri", songUri)
            },
        )
    }

    fun insertPlaylist(p: Playlist) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict(
                "playlists", null,
                ContentValues().apply {
                    put("uri", p.uri.toString())
                    put("root_uri", p.rootUri)
                    put("name", p.name)
                    put("folder_path", p.folderPath)
                    put("missing", p.missing)
                    put("sort", 0)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            p.songs.forEachIndexed { i, s -> appendPlaylistEntry(p.uri.toString(), s.uri.toString(), i) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun meta(key: String): String? =
        readableDatabase.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    // ---- saving ---------------------------------------------------------------

    /** Replaces the stored scan results in one transaction; new songs get a date added. */
    fun saveLibrary(songs: List<Song>, playlists: List<Playlist>, subdirs: Int, full: Boolean) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            db.delete("songs", null, null)
            db.delete("playlists", null, null)
            db.delete("playlist_entries", null, null)

            db.compileStatement(
                """INSERT OR REPLACE INTO songs (uri, root_uri, file_name, folder_path, mime, size,
                       modified, title, artist, album, album_artist, duration, track, disc, year, sort)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
            ).use { st ->
                songs.forEachIndexed { i, s ->
                    st.clearBindings()
                    st.bindString(1, s.uri.toString())
                    st.bindString(2, s.rootUri)
                    st.bindString(3, s.fileName)
                    st.bindString(4, s.folderPath)
                    st.bindString(5, s.mimeType)
                    st.bindLong(6, s.sizeBytes)
                    st.bindLong(7, s.lastModified)
                    st.bindNullable(8, s.title)
                    st.bindNullable(9, s.artist)
                    st.bindNullable(10, s.album)
                    st.bindNullable(11, s.albumArtist)
                    st.bindLong(12, s.durationMs)
                    st.bindLong(13, s.trackNumber.toLong())
                    st.bindLong(14, s.discNumber.toLong())
                    st.bindLong(15, s.year.toLong())
                    st.bindLong(16, i.toLong())
                    st.executeInsert()
                }
            }

            // First time a song is seen: its file modification time (when it landed on
            // the device) becomes the date added. Existing rows are left alone.
            db.compileStatement(
                "INSERT OR IGNORE INTO song_stats (uri, added, plays, last_played) VALUES (?, ?, 0, 0)"
            ).use { st ->
                for (s in songs) {
                    st.clearBindings()
                    st.bindString(1, s.uri.toString())
                    st.bindLong(2, if (s.lastModified in 1..now) s.lastModified else now)
                    st.executeInsert()
                }
            }

            db.compileStatement(
                "INSERT OR REPLACE INTO playlists (uri, root_uri, name, folder_path, missing, sort) VALUES (?, ?, ?, ?, ?, ?)"
            ).use { st ->
                playlists.forEachIndexed { i, p ->
                    st.clearBindings()
                    st.bindString(1, p.uri.toString())
                    st.bindString(2, p.rootUri)
                    st.bindString(3, p.name)
                    st.bindString(4, p.folderPath)
                    st.bindLong(5, p.missing.toLong())
                    st.bindLong(6, i.toLong())
                    st.executeInsert()
                }
            }

            db.compileStatement(
                "INSERT INTO playlist_entries (playlist_uri, position, song_uri) VALUES (?, ?, ?)"
            ).use { st ->
                for (p in playlists) {
                    val playlistUri = p.uri.toString()
                    p.songs.forEachIndexed { i, s ->
                        st.clearBindings()
                        st.bindString(1, playlistUri)
                        st.bindLong(2, i.toLong())
                        st.bindString(3, s.uri.toString())
                        st.executeInsert()
                    }
                }
            }

            putMeta(db, KEY_SUBDIRS, subdirs.toString())
            putMeta(db, KEY_LAST_SCAN, now.toString())
            putMeta(db, KEY_LAST_SCAN_FULL, if (full) "1" else "0")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun putMeta(db: SQLiteDatabase, key: String, value: String) {
        db.insertWithOnConflict(
            "meta", null,
            ContentValues().apply { put("key", key); put("value", value) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun SQLiteStatement.bindNullable(index: Int, value: String?) {
        if (value == null) bindNull(index) else bindString(index, value)
    }

    companion object {
        private const val VERSION = 3
        const val KEY_SUBDIRS = "subdirs"
        const val KEY_LAST_SCAN = "last_scan"
        const val KEY_LAST_SCAN_FULL = "last_scan_full"
    }
}
