package com.ytconverter.downloader

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Lightweight metadata read from a link before the media is fetched. */
data class ProbeInfo(
    val title: String,
    val uploader: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long,
)

/**
 * Thin wrapper over the bundled yt-dlp/FFmpeg.
 *
 * [ensureReady] unpacks the Python runtime and FFmpeg on first use, which takes a
 * couple of seconds, so it is always called off the main thread.
 */
object YtDlpEngine {

    @Volatile
    private var ready = false

    fun ensureReady(appContext: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            YoutubeDL.getInstance().init(appContext)
            FFmpeg.getInstance().init(appContext)
            ready = true
        }
    }

    suspend fun ensureReadyAsync(context: Context) = withContext(Dispatchers.IO) {
        ensureReady(context.applicationContext)
    }

    /** Reads title/thumbnail/duration without downloading the media itself. */
    suspend fun probe(context: Context, url: String): ProbeInfo? = withContext(Dispatchers.IO) {
        ensureReady(context.applicationContext)
        val request = YoutubeDLRequest(url).apply {
            addOption("--dump-single-json")
            addOption("--no-playlist")
            addOption("--skip-download")
            addOption("--no-warnings")
        }
        val response = YoutubeDL.execute(request, null, false, null)
        parseProbe(response.out)
    }

    private fun parseProbe(output: String): ProbeInfo? {
        val root = extractJson(output) ?: return null
        // A playlist link still yields entries; the first one is what we convert.
        val video = if (root.has("entries")) {
            root.optJSONArray("entries")?.optJSONObject(0) ?: root
        } else {
            root
        }
        val title = video.optString("title").takeIf { it.isNotBlank() } ?: return null
        return ProbeInfo(
            title = title,
            uploader = video.optString("uploader").takeIf { it.isNotBlank() }
                ?: video.optString("channel").takeIf { it.isNotBlank() },
            thumbnailUrl = video.optString("thumbnail").takeIf { it.isNotBlank() },
            durationSeconds = video.optDouble("duration", 0.0).toLong(),
        )
    }

    /** yt-dlp can print noise around its JSON, so slice out the outer object. */
    private fun extractJson(output: String): JSONObject? {
        val start = output.indexOf('{')
        val end = output.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(output.substring(start, end + 1)) }.getOrNull()
    }
}
