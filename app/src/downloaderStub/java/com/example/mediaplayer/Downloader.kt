package com.example.mediaplayer

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/**
 * Stand-in used when the app is built with -Pmg.noDownloader (or -Pmg.offline): the yt-dlp downloader and its
 * ~40 MB of Python/ffmpeg are left out. Same API as the real one (src/downloader), does nothing.
 */
@Suppress("UNUSED_PARAMETER")
class Downloader(context: Context, scope: CoroutineScope, onTracksSaved: () -> Unit) {
    val audioFolder: String? = null
    val videoFolder: String? = null
    val running = false
    val progress = -1f
    val status = ""
    val error: String? = "The downloader isn't included in this build."
    val version: String? = null

    val cookieSites: String? = null

    fun importCookies(uri: android.net.Uri) = Unit
    fun clearCookies() = Unit
    fun setFolder(video: Boolean, treeUri: String) = Unit
    fun folderLabel(treeUri: String?): String = "Not included"
    fun prepare() = Unit
    fun download(url: String, video: Boolean) = Unit
    fun cancel() = Unit
    fun updateYtDlp() = Unit
}
