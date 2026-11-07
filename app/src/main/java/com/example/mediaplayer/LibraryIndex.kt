package com.example.mediaplayer

/** An album: songs sharing an album tag and album artist (see [LibraryGrouping.albumKey]). */
data class AlbumEntry(
    val key: String,
    val title: String,
    val artist: String,
    val year: Int,
    val songs: List<Song>,
)

/** One artist after splitting tags. [key] is the lower-cased name. */
data class ArtistEntry(
    val key: String,
    val name: String,
    val songs: List<Song>,
    val albumCount: Int,
)

/**
 * Splits an artist tag into separate artists.
 *
 * Delimiters are matched exactly as written (spaces count) but ignoring case,
 * longest first. Whitelisted names are protected before splitting, so
 * "Simon & Garfunkel" stays whole even with " & " as a delimiter, while
 * "Simon & Garfunkel & Friend" still splits into "Simon & Garfunkel" and "Friend".
 */
class ArtistSplitter(delimiters: List<String>, whitelist: List<String>) {

    private val delimiters = delimiters.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.sortedByDescending { it.length }
    private val whitelist = whitelist.map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
        .sortedByDescending { it.length }

    fun split(tag: String?): List<String> {
        if (tag.isNullOrBlank()) return emptyList()

        // 1. Swap whitelisted names for placeholder tokens.
        var text: String = tag
        val kept = ArrayList<String>()
        for (name in whitelist) {
            var from = 0
            while (true) {
                val i = text.indexOf(name, from, ignoreCase = true)
                if (i < 0) break
                val token = "${kept.size}"
                kept.add(text.substring(i, i + name.length))
                text = text.substring(0, i) + token + text.substring(i + name.length)
                from = i + token.length
            }
        }

        // 2. Split on every delimiter.
        var parts = listOf(text)
        for (d in delimiters) parts = parts.flatMap { splitIgnoreCase(it, d) }

        // 3. Put the whitelisted names back, tidy up, drop duplicates.
        val seen = HashSet<String>()
        return parts.map { restore(it, kept).trim() }.filter { it.isNotEmpty() && seen.add(it.lowercase()) }
    }

    private fun splitIgnoreCase(s: String, d: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        while (true) {
            val i = s.indexOf(d, start, ignoreCase = true)
            if (i < 0) break
            out.add(s.substring(start, i))
            start = i + d.length
        }
        out.add(s.substring(start))
        return out
    }

    private fun restore(s: String, kept: List<String>): String {
        if (kept.isEmpty() || s.indexOf('') < 0) return s
        return TOKEN.replace(s) { m -> kept.getOrNull(m.groupValues[1].toInt()) ?: m.value }
    }

    companion object {
        private val TOKEN = Regex("(\\d+)")
        val DEFAULT_DELIMITERS = listOf(";", " / ", " feat. ", " ft. ", " featuring ", " & ", ", ")
    }
}

/** Builds the album and artist lists from the song list. */
object LibraryGrouping {

    /**
     * Songs are on the same album when the album tag matches (ignoring case)
     * and the album artist tag matches. Without an album artist tag, songs in
     * the same folder are grouped, so compilations without that tag stay together.
     * Songs without an album tag are on no album.
     */
    fun albumKey(s: Song): String? {
        val album = s.album?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val owner = s.albumArtist?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()
            ?: ("\u0001" + s.rootUri + "/" + s.folderPath.lowercase())
        return album.lowercase() + "\u0000" + owner
    }

    private val trackOrder = compareBy<Song>({ it.discNumber }, { it.trackNumber }, { it.fileName.lowercase() })

    fun albums(songs: List<Song>): List<AlbumEntry> {
        val groups = LinkedHashMap<String, MutableList<Song>>()
        for (s in songs) {
            val k = albumKey(s) ?: continue
            groups.getOrPut(k) { mutableListOf() }.add(s)
        }
        return groups.map { (k, list) ->
            val sorted = list.sortedWith(trackOrder)
            val first = sorted.first()
            val artist = first.albumArtist?.trim()?.takeIf { it.isNotEmpty() } ?: run {
                val names = sorted.mapNotNull { it.artist }.distinctBy { it.lowercase() }
                when (names.size) {
                    0 -> "Unknown artist"
                    1 -> names[0]
                    else -> "Various artists"
                }
            }
            AlbumEntry(
                key = k,
                title = first.album.orEmpty().trim(),
                artist = artist,
                year = sorted.maxOf { it.year },
                songs = sorted,
            )
        }.sortedWith(compareBy({ it.title.lowercase() }, { it.artist.lowercase() }))
    }

    /** Artists from the split track artist and album artist tags. */
    fun artists(songs: List<Song>, splitter: ArtistSplitter): List<ArtistEntry> {
        class Acc(val name: String) {
            val songs = ArrayList<Song>()
        }

        val byKey = HashMap<String, Acc>()
        for (s in songs) {
            val seen = HashSet<String>()
            for (name in splitter.split(s.artist) + splitter.split(s.albumArtist)) {
                val k = name.lowercase()
                if (!seen.add(k)) continue
                byKey.getOrPut(k) { Acc(name) }.songs.add(s)
            }
        }

        val order = compareBy<Song>(
            { it.album?.lowercase() ?: "￿" }, // untagged songs last
            { it.discNumber },
            { it.trackNumber },
            { it.fileName.lowercase() },
        )
        return byKey.map { (k, acc) ->
            val sorted = acc.songs.sortedWith(order)
            ArtistEntry(
                key = k,
                name = acc.name,
                songs = sorted,
                albumCount = sorted.mapNotNullTo(HashSet()) { albumKey(it) }.size,
            )
        }.sortedBy { it.name.lowercase() }
    }
}
