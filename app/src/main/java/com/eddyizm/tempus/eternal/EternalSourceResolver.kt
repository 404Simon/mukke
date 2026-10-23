package com.eddyizm.tempus.eternal

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import com.eddyizm.tempus.util.DownloadUtil
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "EternalSourceResolver"

/**
 * Turns the currently playing [MediaItem] into a local file the native engine can decode.
 *
 * A downloaded track can be read in place. Anything else (content provider, stream, transcode)
 * is copied through the same [DownloadUtil.getCacheDataSourceFactory] the player reads with, so
 * the download cache, streaming cache, auth headers, client certificates and server-side
 * transcoding keep working exactly as they do for playback.
 */
@UnstableApi
object EternalSourceResolver {

    /** A file the engine can open. [temporary] files are owned by the caller and must be deleted. */
    data class ResolvedAudio(val file: File, val temporary: Boolean)

    /**
     * Resolves [mediaItem] to a readable file, reporting copy progress as
     * (bytes read, content length or -1 when unknown).
     *
     * A temporary file is deleted if the copy fails or [cancelled] is set. The caller owns a
     * successfully returned temporary file and must delete it after `EternalEngine.prepare`.
     */
    fun resolve(
        context: Context,
        mediaItem: MediaItem,
        cancelled: AtomicBoolean = AtomicBoolean(false),
        onProgress: (bytesRead: Long, contentLength: Long) -> Unit = { _, _ -> },
    ): ResolvedAudio {
        val uri = mediaItem.localConfiguration?.uri
            ?: mediaItem.requestMetadata.mediaUri
            ?: throw IOException("Media item has no playable URI")

        val scheme = uri.scheme?.lowercase(Locale.ROOT)

        // Downloaded files outside the Media3 cache (external storage, file:// downloads) are
        // already local; opening them directly avoids a pointless full copy.
        if (scheme == "file") {
            val file = uri.path?.let(::File)
            if (file != null && file.isFile && file.canRead()) {
                return ResolvedAudio(file, temporary = false)
            }
            Log.w(TAG, "Local file is not readable, falling back to the data source: $uri")
        }

        if (cancelled.get()) throw CancellationException("Eternal source resolution cancelled")

        val suffix = suffixFor(mediaItem, uri)
        val directory = File(context.cacheDir, CACHE_DIRECTORY)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Cannot create eternal cache directory $directory")
        }

        val tempFile = File(directory, "${UUID.randomUUID()}.$suffix")
        val dataSource = DownloadUtil.getCacheDataSourceFactory(context).createDataSource()

        try {
            val contentLength = dataSource.open(DataSpec.Builder().setUri(uri).build())
            var bytesRead = 0L

            FileOutputStream(tempFile).use { output ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    if (cancelled.get()) {
                        throw CancellationException("Eternal source resolution cancelled")
                    }
                    val read = dataSource.read(buffer, 0, buffer.size)
                    if (read == C.RESULT_END_OF_INPUT) break
                    if (read <= 0) throw IOException("Data source returned $read bytes")
                    output.write(buffer, 0, read)
                    bytesRead += read
                    onProgress(bytesRead, contentLength)
                }
                output.flush()
            }

            if (cancelled.get()) throw CancellationException("Eternal source resolution cancelled")
            Log.d(TAG, "Resolved $uri to $tempFile ($bytesRead bytes)")
            return ResolvedAudio(tempFile, temporary = true)
        } catch (e: CancellationException) {
            if (!tempFile.delete() && tempFile.exists()) {
                Log.w(TAG, "Could not delete partial eternal file $tempFile")
            }
            Log.d(TAG, "Eternal source resolution cancelled for $uri")
            throw e
        } catch (t: Throwable) {
            if (!tempFile.delete() && tempFile.exists()) {
                Log.w(TAG, "Could not delete partial eternal file $tempFile")
            }
            Log.w(TAG, "Failed to resolve eternal source $uri", t)
            throw t
        } finally {
            try {
                dataSource.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close the eternal data source", e)
            }
        }
    }

    /**
     * The `format` query parameter is the authoritative answer when Tempus asked the server to
     * transcode: the bytes on the wire are that format, even though the item's `suffix` still
     * describes the source file (`.flac` stream returning MP3, for example). Without it, the
     * server is serving the source or deciding for itself, so the item's own suffix is the best
     * hint. The URI extension is only a last resort.
     */
    private fun suffixFor(mediaItem: MediaItem, uri: Uri): String {
        runCatching { uri.getQueryParameter("format") }
            .getOrNull()
            ?.let(::sanitizeSuffix)
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        val extras = mediaItem.mediaMetadata.extras
        sanitizeSuffix(extras?.getString("suffix")).let { if (it.isNotEmpty()) return it }
        sanitizeSuffix(extras?.getString("transcodedSuffix")).let { if (it.isNotEmpty()) return it }

        // A server path segment without a dot ("stream", "download") is not an extension.
        val segment = uri.lastPathSegment
        if (segment != null && segment.contains('.')) {
            sanitizeSuffix(segment.substringAfterLast('.')).let { if (it.isNotEmpty()) return it }
        }
        return "audio"
    }

    private fun sanitizeSuffix(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.lowercase(Locale.ROOT)
            .replace(SUFFIX_UNSAFE, "")
            .take(MAX_SUFFIX_LENGTH)
    }

    private const val CACHE_DIRECTORY = "eternal"
    private const val COPY_BUFFER_SIZE = 64 * 1024
    private const val MAX_SUFFIX_LENGTH = 8
    private val SUFFIX_UNSAFE = Regex("[^a-z0-9]")
}
