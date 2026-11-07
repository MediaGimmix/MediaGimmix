package com.example.mediaplayer

import java.text.Normalizer

/**
 * Library search, shared by the search screen and the API (/api/search).
 *
 * Case- and accent-insensitive ("beyonce" finds "Beyoncé"). Every word of the
 * query must appear somewhere in the item (title, artist, album...), in any
 * order. Results are ranked: whole-name match, then name starts with the
 * query, then a word in the name starts with it, then anything else; ties
 * keep library order.
 */
object LibrarySearch {

    class Results(val songs: List<Song>, val albums: List<AlbumEntry>, val artists: List<ArtistEntry>) {
        val isEmpty get() = songs.isEmpty() && albums.isEmpty() && artists.isEmpty()
    }

    fun all(
        query: String,
        songs: List<Song>,
        albums: List<AlbumEntry>,
        artists: List<ArtistEntry>,
        limit: Int = Int.MAX_VALUE,
    ) = Results(songs(query, songs, limit), albums(query, albums, limit), artists(query, artists, limit))

    fun songs(query: String, songs: List<Song>, limit: Int = Int.MAX_VALUE): List<Song> =
        rank(query, songs, limit, name = { it.displayTitle }) { s ->
            listOfNotNull(s.displayTitle, s.artist, s.albumArtist, s.album, s.fileName)
        }

    fun albums(query: String, albums: List<AlbumEntry>, limit: Int = Int.MAX_VALUE): List<AlbumEntry> =
        rank(query, albums, limit, name = { it.title }) { a -> listOf(a.title, a.artist) }

    fun artists(query: String, artists: List<ArtistEntry>, limit: Int = Int.MAX_VALUE): List<ArtistEntry> =
        rank(query, artists, limit, name = { it.name }) { a -> listOf(a.name) }

    /** Lower case without accents or extra spaces. */
    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(MARKS, "")
            .lowercase()
            .replace(SPACES, " ")
            .trim()

    private fun <T> rank(
        query: String,
        items: List<T>,
        limit: Int,
        name: (T) -> String,
        fields: (T) -> List<String>,
    ): List<T> {
        val q = normalize(query)
        if (q.isEmpty() || limit <= 0) return emptyList()
        val words = q.split(' ')
        return items.asSequence()
            .mapNotNull { item ->
                val haystack = normalize(fields(item).joinToString(" "))
                if (words.all { it in haystack }) item to score(normalize(name(item)), q) else null
            }
            .sortedBy { it.second } // stable: same score keeps library order
            .take(limit)
            .map { it.first }
            .toList()
    }

    private fun score(name: String, q: String): Int = when {
        name == q -> 0
        name.startsWith(q) -> 1
        name.contains(" $q") -> 2
        name.contains(q) -> 3
        else -> 4 // matched through artist/album/file name, or words split up
    }

    private val MARKS = Regex("\\p{M}+")
    private val SPACES = Regex("\\s+")
}
