package com.example.mediaplayer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * yt-dlp downloads (Settings → Library → Downloader), with Python, yt-dlp and ffmpeg bundled by
 * youtubedl-android. Files are downloaded into filesDir/ytdlp/<job>, then copied into the folder picked for
 * tracks or videos (Storage Access Framework, picked once, saved as "dl_audio_folder" / "dl_video_folder")
 * and the temporary copy is deleted. Tracks are the best audio converted to MP3 (V0) with metadata and the
 * thumbnail as cover (so Track info can edit their tags); videos are best video + audio merged to MP4. After tracks, the library does a quick scan.
 *
 * The internet switch applies: a link to the open internet is refused while it's off (a home server is fine),
 * and updating yt-dlp (from GitHub) needs it on.
 */
class Downloader(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onTracksSaved: () -> Unit,
) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var audioFolder by mutableStateOf(prefs.getString(KEY_AUDIO, null))
        private set
    var videoFolder by mutableStateOf(prefs.getString(KEY_VIDEO, null))
        private set

    var running by mutableStateOf(false)
        private set
    /** 0..100, or -1 while yt-dlp hasn't reported a percentage yet. */
    var progress by mutableFloatStateOf(-1f)
        private set
    var status by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var version by mutableStateOf<String?>(null)
        private set

    /**
     * A Netscape cookies.txt (exported from your browser) for sites that need you signed in, e.g. a media server
     * with cookie auth. Kept privately in filesDir (not backed up) and passed to yt-dlp as --cookies.
     */
    private val cookieFile = File(context.filesDir, "ytdlp_cookies.txt")

    /** Sites the imported cookies cover (never the values), or null with no cookies. */
    var cookieSites by mutableStateOf(readCookieSites())
        private set

    /** Copies a cookies.txt the user picked; refuses files that aren't in the cookies.txt format. */
    fun importCookies(uri: Uri) {
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: throw IllegalStateException("Couldn't read that file")
                    if (siteNames(text).isEmpty()) {
                        throw IllegalStateException("That isn't a cookies.txt file (Netscape format, tab-separated)")
                    }
                    cookieFile.writeText(text)
                }
            }
            result.onFailure { error = it.message }
            cookieSites = readCookieSites()
            if (result.isSuccess) status = "Cookies imported"
        }
    }

    fun clearCookies() {
        cookieFile.delete()
        cookieSites = null
        status = "Cookies removed"
    }

    private fun readCookieSites(): String? =
        if (cookieFile.isFile) siteNames(runCatching { cookieFile.readText() }.getOrDefault("")).joinToString(", ").ifEmpty { null } else null

    /** Domains in a Netscape cookies.txt (7 tab-separated fields per cookie; "#HttpOnly_" prefix allowed). */
    private fun siteNames(text: String): List<String> =
        text.lineSequence()
            .map { it.removePrefix("#HttpOnly_") }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
            .filter { it.size >= 7 }
            .map { it[0].trimStart('.') }
            .distinct()
            .toList()

    private fun isYouTube(link: String): Boolean {
        val host = runCatching { Uri.parse(link).host }.getOrNull()?.lowercase() ?: return false
        return host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
    }

    /** Null when the device has a network connection, else what to tell the user. */
    private fun noConnection(): String? {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return null
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            ?: return "Not connected to Wi-Fi (or any network). Connect and try again."
        return if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) null
        else "The current network has no connection. Check Wi-Fi and try again."
    }

    /** yt-dlp's last error line, with Android's DNS failures said plainly. */
    private fun explain(e: Exception, link: String?): String {
        val raw = e.message?.lineSequence()?.lastOrNull { it.isNotBlank() } ?: e.javaClass.simpleName
        val dns = listOf("No address associated with hostname", "Name or service not known", "Temporary failure in name resolution")
        if (dns.none { raw.contains(it, ignoreCase = true) }) return raw
        val host = link?.let { runCatching { Uri.parse(it).host }.getOrNull() } ?: "the site"
        return "Can't reach $host: the connection dropped or the name can't be found. Check Wi-Fi and try again."
    }

    private var job: Job? = null
    private val processId = "mediagimmix-download"
    @Volatile private var ready = false

    fun setFolder(video: Boolean, treeUri: String) {
        if (video) {
            videoFolder = treeUri
            prefs.edit().putString(KEY_VIDEO, treeUri).apply()
        } else {
            audioFolder = treeUri
            prefs.edit().putString(KEY_AUDIO, treeUri).apply()
        }
    }

    /** Readable folder name ("Music/Downloads") from a tree URI. */
    fun folderLabel(treeUri: String?): String {
        treeUri ?: return "Not chosen"
        val id = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(treeUri)) }.getOrNull() ?: return treeUri
        return id.substringAfter(':').ifEmpty { id }.ifEmpty { "Storage root" }
    }

    /** Loads yt-dlp (first time only: unpacks Python, takes a few seconds) and reads its version. */
    fun prepare() {
        if (ready || job?.isActive == true) return
        job = scope.launch { ensureReady() }
    }

    private suspend fun ensureReady(): Boolean {
        if (ready) return true
        status = "Loading yt-dlp…"
        return try {
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().init(context)
                FFmpeg.getInstance().init(context)
            }
            ready = true
            version = withContext(Dispatchers.IO) { runCatching { YoutubeDL.getInstance().version(context) }.getOrNull() }
            status = ""
            true
        } catch (e: Exception) {
            error = "Couldn't load yt-dlp: ${e.message}"
            status = ""
            false
        }
    }

    fun download(url: String, video: Boolean) {
        val link = url.trim()
        if (running || link.isEmpty()) return
        val folder = (if (video) videoFolder else audioFolder) ?: run {
            error = "Choose a folder for ${if (video) "videos" else "tracks"} first."
            return
        }
        noConnection()?.let {
            error = it
            return
        }
        error = null
        running = true
        progress = -1f
        job = scope.launch {
            try {
                // Internet switch: a home server passes, the open internet doesn't while it's off.
                withContext(Dispatchers.IO) { NetPolicy.check(link) }
                if (!ensureReady()) return@launch
                val tmp = File(context.filesDir, "ytdlp/${System.currentTimeMillis()}").apply { mkdirs() }
                val request = YoutubeDLRequest(link).apply {
                    addOption("-o", "${tmp.path}/%(title).150B [%(id)s].%(ext)s")
                    addOption("--no-mtime")
                    addOption("--embed-metadata")
                    if (cookieFile.isFile) addOption("--cookies", cookieFile.path)
                    // A YouTube link with &list=... means that one video, not the whole playlist.
                    if (isYouTube(link)) addOption("--no-playlist")
                    if (video) {
                        addOption("-f", "bv*+ba/b")
                        addOption("--merge-output-format", "mp4")
                    } else {
                        // Tracks: best audio converted to MP3 (VBR, best quality) so their tags can be edited later
                        // (Track info); the thumbnail becomes the cover.
                        addOption("-f", "bestaudio/best")
                        addOption("-x")
                        addOption("--audio-format", "mp3")
                        addOption("--audio-quality", "0")
                        addOption("--embed-thumbnail")
                        addOption("--convert-thumbnails", "jpg")
                    }
                }
                status = "Starting…"
                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().execute(request, processId) { pct, _, line ->
                        scope.launch {
                            if (pct >= 0f) progress = pct
                            if (line.isNotBlank()) status = line.take(160)
                        }
                    }
                }
                status = "Saving…"
                val saved = withContext(Dispatchers.IO) { copyInto(tmp, folder) }
                tmp.deleteRecursively()
                status = "Saved ${saved.size} file${if (saved.size == 1) "" else "s"} to ${folderLabel(folder)}" +
                    saved.firstOrNull()?.let { ": $it" + if (saved.size > 1) " …" else "" }.orEmpty()
                progress = 100f
                if (!video && saved.isNotEmpty()) onTracksSaved()
            } catch (e: kotlinx.coroutines.CancellationException) {
                status = "Cancelled"
                throw e
            } catch (e: Exception) {
                error = explain(e, link)
                status = ""
            } finally {
                running = false
                File(context.filesDir, "ytdlp").listFiles()?.forEach { it.deleteRecursively() }
            }
        }
    }

    fun cancel() {
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
        job?.cancel()
        running = false
        status = "Cancelled"
    }

    /** Updates yt-dlp itself from GitHub (sites change; old versions stop working). Needs internet access on. */
    fun updateYtDlp() {
        if (running) return
        if (!NetPolicy.internet) {
            error = "Internet access is off (Settings → Network)."
            return
        }
        noConnection()?.let {
            error = it
            return
        }
        error = null
        running = true
        job = scope.launch {
            try {
                if (!ensureReady()) return@launch
                status = "Updating yt-dlp…"
                val result = withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
                }
                version = withContext(Dispatchers.IO) { runCatching { YoutubeDL.getInstance().version(context) }.getOrNull() }
                status = if (result == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) "Already up to date" else "Updated"
            } catch (e: Exception) {
                error = "Update failed: ${explain(e, "https://github.com")}"
                status = ""
            } finally {
                running = false
            }
        }
    }

    /** Copies every finished file in [dir] into the SAF folder [treeUri]; returns their names. */
    private fun copyInto(dir: File, treeUri: String): List<String> {
        val tree = Uri.parse(treeUri)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val resolver = context.contentResolver
        val names = ArrayList<String>()
        dir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                val ext = file.extension.lowercase()
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                val doc = DocumentsContract.createDocument(resolver, parent, mime, file.name)
                    ?: throw IllegalStateException("Couldn't create ${file.name} in ${folderLabel(treeUri)} (allow changes to that folder?)")
                resolver.openOutputStream(doc)?.use { out -> file.inputStream().use { it.copyTo(out, 256 * 1024) } }
                names += file.name
            }
        return names
    }

    private companion object {
        const val KEY_AUDIO = "dl_audio_folder"
        const val KEY_VIDEO = "dl_video_folder"
    }
}
