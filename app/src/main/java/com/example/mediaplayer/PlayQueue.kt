package com.example.mediaplayer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The two queues plus a play history.
 *
 * [general] is the main list, in the order it will play.
 * [next] is the "play next" list: it plays before the general queue continues.
 *
 * [returnIndex] is the spot in [general] that is held while [next] plays: the
 * index of the last track that counted as played from the general queue. When
 * [next] is empty, playback continues with general[returnIndex + 1].
 * -1 means nothing has been played from the general queue yet.
 *
 * [currentFromGeneral] is true when the song playing now is general[returnIndex]
 * (it was reached by walking the general queue, or by the tap that filled it).
 *
 * Lists are immutable and replaced on every change, which keeps Compose
 * updates simple and is cheap enough for a few thousand songs.
 * Use from the main thread only.
 */
class PlayQueue {

    var general by mutableStateOf<List<Song>>(emptyList())
        private set
    var next by mutableStateOf<List<Song>>(emptyList())
        private set
    var returnIndex by mutableIntStateOf(-1)
        private set
    var currentFromGeneral by mutableStateOf(false)
        private set

    /** Songs played before the current one, oldest first. Not shown in the UI. */
    private val history = ArrayDeque<Song>()

    val hasNext: Boolean
        get() = next.isNotEmpty() || (returnIndex + 1) in general.indices

    // ---- playback flow ------------------------------------------------------

    /** Removes and returns the next song to play, or null if both queues are used up. */
    fun takeNext(): Song? {
        val queued = next.firstOrNull()
        if (queued != null) {
            next = next.drop(1)
            currentFromGeneral = false
            return queued
        }
        val i = returnIndex + 1
        if (i in general.indices) {
            returnIndex = i
            currentFromGeneral = true
            return general[i]
        }
        return null
    }

    fun pushHistory(song: Song) {
        history.addLast(song)
        while (history.size > MAX_HISTORY) history.removeFirst()
    }

    fun clearHistory() = history.clear()

    /** Replaces everything: [songs] become the general queue; play next and history are cleared. */
    fun replaceAll(songs: List<Song>) {
        general = songs
        next = emptyList()
        returnIndex = -1
        currentFromGeneral = false
        history.clear()
    }

    /** Stop: empties the general queue, the play next queue and the history. */
    fun clearAll() = replaceAll(emptyList())

    /** The current song was started from outside the queues (a tap in the library). */
    fun markCurrentFromOutside() {
        currentFromGeneral = false
    }

    /**
     * Steps back one song and returns it, or null when there is nothing before
     * [current]. The current song is "un-played" so that Next returns to it:
     * if it holds the general spot the spot moves back one, otherwise it goes
     * back to the top of the play next queue.
     *
     * The previous song comes from the history; with no history it is the
     * song before the spot in the general queue.
     */
    fun stepBack(current: Song?): Song? {
        val prev = history.removeLastOrNull()
            ?: if (currentFromGeneral && returnIndex >= 1) general[returnIndex - 1] else return null

        if (current != null) {
            if (currentFromGeneral && general.getOrNull(returnIndex)?.uri == current.uri) {
                returnIndex -= 1
            } else {
                next = listOf(current) + next
            }
        }
        currentFromGeneral = general.getOrNull(returnIndex)?.uri == prev.uri
        return prev
    }

    /**
     * Tap on an idle or empty general queue: fill it with [library] (already in
     * display order), put the return position on [tapped], then apply shuffle.
     * With shuffle on, [tapped] goes to the top and everything else is shuffled
     * below it, with the return position on the top.
     */
    fun fill(library: List<Song>, tapped: Song, shuffle: Boolean) {
        next = emptyList()
        val i = library.indexOfFirst { it.uri == tapped.uri }
        if (shuffle) {
            general = listOf(tapped) + library.filterIndexed { index, _ -> index != i }.shuffled()
            returnIndex = 0
            currentFromGeneral = true
        } else {
            general = library
            returnIndex = i
            currentFromGeneral = i >= 0
        }
    }

    /** Shuffle turned on: the track holding the spot moves to the top, the rest is shuffled. */
    fun shuffleGeneral() {
        val spot = general.getOrNull(returnIndex)
        if (spot != null) {
            general = listOf(spot) + general.filterIndexed { index, _ -> index != returnIndex }.shuffled()
            returnIndex = 0
        } else {
            general = general.shuffled()
            returnIndex = -1
            currentFromGeneral = false
        }
    }

    // ---- edit: general queue ------------------------------------------------

    fun removeGeneral(index: Int) {
        if (index !in general.indices) return
        general = general.toMutableList().also { it.removeAt(index) }
        if (index == returnIndex) currentFromGeneral = false
        // The spot moves up if something at or before it was removed.
        if (index <= returnIndex) returnIndex -= 1
    }

    fun moveGeneral(from: Int, to: Int) {
        if (from !in general.indices || to !in general.indices || from == to) return
        val list = general.toMutableList()
        list.add(to, list.removeAt(from))
        general = list
        returnIndex = if (from == returnIndex) {
            to
        } else {
            var r = returnIndex
            if (from < r) r--
            if (to <= r) r++
            r
        }
    }

    // ---- edit: play next queue ----------------------------------------------

    fun addNextFirst(song: Song) {
        next = listOf(song) + next
    }

    fun addNextLast(song: Song) {
        next = next + song
    }

    fun removeNext(index: Int) {
        if (index !in next.indices) return
        next = next.toMutableList().also { it.removeAt(index) }
    }

    fun moveNext(from: Int, to: Int) {
        if (from !in next.indices || to !in next.indices || from == to) return
        val list = next.toMutableList()
        list.add(to, list.removeAt(from))
        next = list
    }

    fun clearNext() {
        next = emptyList()
    }

    /** Removes every occurrence of the song with [uri] from both queues and the history. */
    fun removeSong(uri: android.net.Uri) {
        next = next.filter { it.uri != uri }
        history.removeAll { it.uri == uri }
        if (general.none { it.uri == uri }) return
        var r = returnIndex
        val kept = ArrayList<Song>(general.size)
        general.forEachIndexed { i, s ->
            if (s.uri == uri) {
                if (i == returnIndex) currentFromGeneral = false
                if (i <= returnIndex) r--
            } else {
                kept.add(s)
            }
        }
        general = kept
        returnIndex = r
    }

    private companion object {
        const val MAX_HISTORY = 500
    }
}
