package com.example.mediaplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Where a cover comes from. */
sealed interface ArtSource {
    val key: String

    /**
     * A song's cover: its embedded picture, else an image file in its folder.
     * Songs on the same album share one cache entry, so a long list of songs
     * only reads each album's cover once.
     */
    data class FromSong(val song: Song) : ArtSource {
        override val key: String
            get() = LibraryGrouping.albumKey(song)?.let { "album:$it" } ?: ("song:" + song.uri)
    }

    /** A cover on a netshare server ([NetshareBackend.cover]): [backendId] + the server's art id. */
    data class FromServer(val backendId: String, val artId: String) : ArtSource {
        override val key: String get() = "srv:$backendId:$artId"
    }

    /** An image file in the app's own storage (downloaded artist pictures). */
    data class FromFile(val path: String) : ArtSource {
        override val key: String get() = "file:" + path
    }

    /** A user-chosen image (content URI). */
    data class FromImage(val uri: String) : ArtSource {
        override val key: String get() = "img:" + uri
    }
}

/**
 * Loads covers, scaled down, with a memory cache and a disk cache in the app's
 * cache folder. Songs without a cover are remembered so they aren't searched again.
 * "Clear artwork cache" in Settings resets everything.
 */
object Artwork {

    /**
     * Decoded covers in memory, limited by BYTES (not count): bitmap pixels live in native memory, outside the
     * Java heap limit, so a count-limited cache of big now-playing covers (1000 px = 4 MB each) quietly grew the
     * process until Android killed other apps and then this one. Evicted covers reload from the disk cache.
     */
    private val memory = object : LruCache<String, ImageBitmap>(MEMORY_BUDGET_KB) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            (value.asAndroidBitmap().allocationByteCount / 1024).coerceAtLeast(1)
    }
    private const val MEMORY_BUDGET_KB = 32 * 1024
    private val missing = HashSet<String>()
    private val gate = Semaphore(3)

    /** Folder image names, best first (compared without extension, ignoring case). */
    private val PREFERRED_NAMES = listOf("cover", "folder", "front", "album", "albumart", "artwork")
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp")
    private const val MAX_IMAGE_BYTES = 30L * 1024 * 1024

    fun cacheKey(source: ArtSource, sizePx: Int) = "${source.key}@$sizePx"

    fun peek(source: ArtSource, sizePx: Int): ImageBitmap? = memory.get(cacheKey(source, sizePx))

    suspend fun load(context: Context, source: ArtSource, sizePx: Int): ImageBitmap? {
        val key = cacheKey(source, sizePx)
        memory.get(key)?.let { return it }
        synchronized(missing) { if (key in missing) return null }

        val bitmap = gate.withPermit {
            withContext(Dispatchers.IO) {
                val dir = cacheDir(context)
                val name = sha1(key)
                val file = File(dir, "$name.jpg")
                val none = File(dir, "$name.none")
                when {
                    none.exists() -> null
                    file.exists() -> BitmapFactory.decodeFile(file.path)
                    else -> {
                        val fetched = runCatching { fetch(context, source, sizePx) }.getOrNull()
                        // A server cover that failed is likely a network problem: don't remember it as missing.
                        if (fetched == null) {
                            if (source !is ArtSource.FromServer) runCatching { none.createNewFile() }
                        } else {
                            runCatching {
                                FileOutputStream(file).use { fetched.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                            }
                        }
                        fetched
                    }
                }
            }
        }

        if (bitmap == null) {
            if (source !is ArtSource.FromServer) synchronized(missing) { missing.add(key) }
            return null
        }
        return bitmap.asImageBitmap().also { memory.put(key, it) }
    }

    fun clearCache(context: Context) {
        memory.evictAll()
        synchronized(missing) { missing.clear() }
        SiblingFiles.clear()
        cacheDir(context).listFiles()?.forEach { it.delete() }
    }

    private fun fetch(context: Context, source: ArtSource, sizePx: Int): Bitmap? = when (source) {
        is ArtSource.FromImage -> decodeUri(context, Uri.parse(source.uri), sizePx)
        is ArtSource.FromFile -> File(source.path).takeIf { it.length() in 1..MAX_IMAGE_BYTES }?.readBytes()?.let { decodeBytes(it, sizePx) }
        is ArtSource.FromServer ->
            Netshare.byId(source.backendId)?.cover(source.artId, sizePx)?.let { decodeBytes(it, sizePx) }
        is ArtSource.FromSong -> embedded(context, source.song, sizePx) ?: folderImage(context, source.song, sizePx)
    }

    private fun embedded(context: Context, song: Song, sizePx: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, song.uri)
            r.embeddedPicture?.let { decodeBytes(it, sizePx) }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun folderImage(context: Context, song: Song, sizePx: Int): Bitmap? {
        val images = SiblingFiles.list(context, song).filter { f ->
            f.mimeType.startsWith("image/") || f.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
        }
        if (images.isEmpty()) return null
        val best = images.minByOrNull { f ->
            val base = f.name.substringBeforeLast('.').lowercase()
            PREFERRED_NAMES.indexOf(base).let { if (it < 0) PREFERRED_NAMES.size else it }
        } ?: return null
        return decodeUri(context, best.uri, sizePx)
    }

    private fun decodeUri(context: Context, uri: Uri, sizePx: Int): Bitmap? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                total += n
                if (total > MAX_IMAGE_BYTES) return null
                buffer.write(chunk, 0, n)
            }
            buffer.toByteArray()
        } ?: return null
        return decodeBytes(bytes, sizePx)
    }

    private fun decodeBytes(bytes: ByteArray, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val longest = maxOf(decoded.width, decoded.height)
        if (longest <= sizePx) return decoded
        val scale = sizePx.toFloat() / longest
        return Bitmap.createScaledBitmap(
            decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true,
        )
    }

    private fun cacheDir(context: Context): File = File(context.cacheDir, "art").apply { mkdirs() }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** The cover for [source] at roughly [sizePx] pixels, or null while loading / when there is none. */
@Composable
fun rememberArtwork(source: ArtSource?, sizePx: Int): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val image by produceState<ImageBitmap?>(initialValue = null, source?.key, sizePx) {
        if (source == null) {
            value = null
        } else {
            value = Artwork.peek(source, sizePx)
            if (value == null) value = Artwork.load(context, source, sizePx)
        }
    }
    return image
}

/** A cover image, always cropped to fill its box; shows the title's first letter when there is no cover. */
@Composable
fun CoverImage(
    source: ArtSource?,
    title: String,
    sizePx: Int,
    modifier: Modifier = Modifier,
    corner: Dp = 8.dp,
    placeholderStyle: TextStyle = MaterialTheme.typography.headlineMedium,
) {
    val image = rememberArtwork(source, sizePx)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = title.trim().take(1).uppercase().ifEmpty { "?" },
                style = placeholderStyle,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}
