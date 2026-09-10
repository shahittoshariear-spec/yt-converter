package com.ytconverter.downloader

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

    fun isAudioFile(file: File): Boolean =
        file.isFile && file.extension.lowercase() in audioExtensions

    fun publish(context: Context, source: File, folder: String): PublishedFile? {
        val mimeType = mimeTypeFor(source.extension)
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, source.name)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$folder")
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
        return PublishedFile(uri.toString(), source.name, mimeType, size)
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
