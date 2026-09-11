package com.ytconverter.downloader

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

data class PublishedFile(
    val uri: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
)

/**
 * Copies a finished file into the shared `Music/` collection so it shows up in every
 * music player on the device, instead of being trapped in app-private storage.
 */
object MediaPublisher {

    private val audioExtensions =
        setOf("mp3", "flac", "m4a", "opus", "ogg", "oga", "webm", "wav", "aac")

    private val illegalPathChars = Regex("""[\u0000-\u001f\\:*?"<>|]""")

    fun isAudioFile(file: File): Boolean =
        file.isFile && file.extension.lowercase() in audioExtensions

    fun publish(
        context: Context,
        source: File,
        folder: String,
        subPath: String? = null,
        displayName: String = source.name,
        reuseExisting: Boolean = false,
    ): PublishedFile? {
        val mimeType = mimeTypeFor(source.extension)
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        val relative = buildString {
            append(Environment.DIRECTORY_MUSIC).append('/').append(folder)
            // A playlist keeps its own folder under the save location. The name comes
            // from the engine, so it is re-validated here before becoming a path.
            sanitizeRelativePath(subPath)?.let { append('/').append(it) }
        }

        // Re-downloading a playlist (the usual reason being a retry after a cancel)
        // must not leave the library with two copies of every song. An existing copy
        // of the same name in the same folder is adopted instead of written again.
        if (reuseExisting) {
            existingEntry(resolver, collection, relative, displayName)?.let { return it }
        }

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, relative)
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            // Hide the row until the bytes are fully written.
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val uri: Uri = resolver.insert(collection, values) ?: return null
        val size = source.length()

        val copied = runCatching {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } != null
        }.getOrDefault(false)

        if (!copied) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }

        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
            null,
            null,
        )
        return PublishedFile(uri.toString(), displayName, mimeType, size)
    }

    /**
     * Finds an already-committed media row with the same name in the same folder.
     *
     * Matched on [MediaStore.Audio.Media.RELATIVE_PATH] after trimming the trailing
     * separator, because MediaStore normalises stored paths to end in `/` and a raw
     * comparison against the value we passed in would miss. Pending rows are ignored:
     * only a fully written file is worth adopting.
     */
    private fun existingEntry(
        resolver: ContentResolver,
        collection: Uri,
        relative: String,
        displayName: String,
    ): PublishedFile? {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.RELATIVE_PATH,
        )
        val selection = "${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND " +
            "${MediaStore.Audio.Media.IS_PENDING} = 0"
        val wanted = relative.trimEnd('/')

        return runCatching {
            resolver.query(collection, projection, selection, arrayOf(displayName), null)
                ?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val path = cursor.getString(3).orEmpty().trimEnd('/')
                        if (!path.equals(wanted, ignoreCase = true)) continue
                        val id = cursor.getLong(0)
                        val mime = cursor.getString(1) ?: mimeTypeFor(displayName.substringAfterLast('.', ""))
                        return@use PublishedFile(
                            uri = ContentUris.withAppendedId(collection, id).toString(),
                            fileName = displayName,
                            mimeType = mime,
                            sizeBytes = cursor.getLong(2),
                        )
                    }
                    null
                }
        }.getOrNull()
    }

    /**
     * Reduces an engine-supplied relative path to something safe for `RELATIVE_PATH`:
     * no separators crept in from the name, no traversal, no blank segments.
     */
    fun sanitizeRelativePath(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val segments = raw
            .split('/', '\\')
            .map { it.replace(illegalPathChars, "").trim().trim('.', ' ') }
            .filter { it.isNotBlank() && it != ".." }
        return segments.takeIf { it.isNotEmpty() }?.joinToString("/")
    }

    fun mimeTypeFor(extension: String): String = when (extension.lowercase()) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "m4a", "mp4" -> "audio/mp4"
        "opus" -> "audio/opus"
        "ogg", "oga" -> "audio/ogg"
        "webm" -> "audio/webm"
        "wav" -> "audio/wav"
        "aac" -> "audio/aac"
        else -> "audio/*"
    }
}
