package com.example.mediaplayer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class ScanResult(
    val songs: List<Song>,
    val playlists: List<Playlist>,
    val subdirs: Int,
    val errors: List<String>,
)

/**
 * A whole library scan over all media folders.
 *
 * Quick scan: files whose size and modification time are unchanged keep their
 * stored tags; only new or changed files are read. Full scan: every file's
 * tags are read again. Playlists are always decoded again (they are small).
 *
 * If a folder can't be read, its previously stored songs and playlists are kept.
 */
object LibraryScanner {

    /** How many files have their tags read at the same time. */
    private const val TAG_READERS = 4

    suspend fun scan(
        context: Context,
        folders: List<String>,
        previousSongs: List<Song>,
        previousPlaylists: List<Playlist>,
        full: Boolean,
        report: (status: String, progress: Float?) -> Unit,
    ): ScanResult {
        val resolver = context.contentResolver
        val audio = mutableListOf<MediaScanner.Found>()
        val playlistFiles = mutableListOf<MediaScanner.Found>()
        val seenDocs = HashSet<String>() // the same file reached through two folders counts once
        val failedRoots = mutableSetOf<String>()
        val errors = mutableListOf<String>()
        var subdirs = 0

        // 1. List files in every folder.
        for (root in folders) {
            val label = folderLabel(root)
            report("Listing $label...", null)
            try {
                val before = audio.size
                val listing = MediaScanner.list(resolver, Uri.parse(root)) { n ->
                    report("Listing $label... ${before + n} songs found", null)
                }
                listing.audio.filterTo(audio) { seenDocs.add(it.docId) }
                listing.playlists.filterTo(playlistFiles) { seenDocs.add(it.docId) }
                subdirs += listing.subdirs
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedRoots += root
                errors += "$label: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        // 2. Reuse stored tags where possible, read the rest.
        val existing = if (full) emptyMap() else previousSongs.associateBy { it.uri.toString() }
        val reused = ArrayList<Song>()
        val toRead = ArrayList<MediaScanner.Found>()
        for (f in audio) {
            val old = existing[f.uri.toString()]
            if (old != null && old.sizeBytes == f.sizeBytes && old.lastModified == f.lastModified) {
                reused.add(old.copy(fileName = f.name, folderPath = f.folderPath, rootUri = f.rootUri))
            } else {
                toRead.add(f)
            }
        }

        val total = toRead.size
        val done = AtomicInteger()
        val semaphore = Semaphore(TAG_READERS)
        if (total > 0) report("Reading tags 0 / $total", 0f)
        val read = coroutineScope {
            toRead.map { f ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        ensureActive()
                        val song = MediaScanner.readSong(context, f)
                        val n = done.incrementAndGet()
                        if (n % 10 == 0 || n == total) report("Reading tags $n / $total", n.toFloat() / total)
                        song
                    }
                }
            }.awaitAll()
        }

        val kept = previousSongs.filter { it.rootUri in failedRoots }
        val songs = (reused + read + kept).sortedWith(
            compareBy<Song>({ it.rootUri }, { it.folderPath.lowercase() }, { it.fileName.lowercase() })
        )

        // 3. Decode playlists against the new song list.
        report("Reading ${playlistFiles.size} playlists...", null)
        val index = SongIndex(songs)
        val playlists = playlistFiles.map { PlaylistParser.load(resolver, it, index) } +
            previousPlaylists.filter { it.rootUri in failedRoots }

        return ScanResult(
            songs = songs,
            playlists = playlists.sortedBy { it.name.lowercase() },
            subdirs = subdirs,
            errors = errors,
        )
    }
}

/** Human-readable folder name, e.g. "primary:Music". */
fun folderLabel(treeUri: String): String =
    runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(treeUri)) }.getOrDefault(treeUri)
