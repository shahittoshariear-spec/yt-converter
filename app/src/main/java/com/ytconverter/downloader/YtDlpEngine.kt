package com.ytconverter.downloader

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.ytconverter.data.AudioFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Metadata for one video, resolved either by probing or by expanding a playlist. */
data class MediaInfo(
    val url: String,
    val title: String,
    val uploader: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long,
)

/** Why a conversion failed, in terms the UI can act on. */
enum class FailureKind { STALE_EXTRACTOR, AGE_RESTRICTED, NETWORK, UNAVAILABLE, UNKNOWN }

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

    /** Reads title/thumbnail/duration for a single video without downloading it. */
    suspend fun probeSingle(context: Context, url: String): MediaInfo? = withContext(Dispatchers.IO) {
        ensureReady(context.applicationContext)
        val request = YoutubeDLRequest(url).apply {
            addOption("--dump-single-json")
            addOption("--no-playlist")
            addOption("--skip-download")
            addOption("--no-warnings")
        }
        val response = YoutubeDL.execute(request, null, false, null)
        val root = extractJson(response.out.orEmpty()) ?: return@withContext null
        parseInfo(root)
    }

    /**
     * Expands a playlist/channel into individual videos.
     *
     * `--flat-playlist` keeps this cheap: it skips per-video extraction, at the cost
     * of not returning thumbnails, which [parseInfo] rebuilds from the video id. The
     * per-line `--dump-json` form is deliberate rather than `--dump-single-json`: it
     * is what lets the library's `--ignore-errors` handling tolerate one dead entry in
     * a playlist instead of failing the whole expansion.
     */
    suspend fun expandCollection(context: Context, url: String): List<MediaInfo> =
        withContext(Dispatchers.IO) {
            ensureReady(context.applicationContext)
            val request = YoutubeDLRequest(url).apply {
                addOption("--dump-json")
                addOption("--flat-playlist")
                addOption("--skip-download")
                addOption("--no-warnings")
                addOption("--ignore-errors")
            }
            val response = YoutubeDL.execute(request, null, false, null)
            response.out.orEmpty().lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("{") }
                .mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
                .mapNotNull(::parseInfo)
                .toList()
        }

    /** Pulls the newest yt-dlp release from GitHub. Returns false on any failure. */
    suspend fun updateEngine(context: Context): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            ensureReady(context.applicationContext)
            YoutubeDL.getInstance()
                .updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.STABLE)
        }.isSuccess
    }

    /** Adds the audio-extraction options shared by every queued job. */
    fun applyAudioOptions(
        request: YoutubeDLRequest,
        format: AudioFormat,
        trimNonMusic: Boolean,
    ) {
        request.addOption("--no-playlist")
        request.addOption("--newline")
        request.addOption("--no-mtime")
        request.addOption("-x")
        format.ytDlpAudioFormat?.let { request.addOption("--audio-format", it) }
        format.audioQuality?.let { request.addOption("--audio-quality", it) }
        request.addOption("--embed-metadata")
        if (format != AudioFormat.ORIGINAL) {
            // Cover art only makes sense in the formats that support it, and
            // ORIGINAL is meant to come through untouched.
            request.addOption("--embed-thumbnail")
            request.addOption("--convert-thumbnails", "jpg")
        }
        if (trimNonMusic) {
            request.addOption(
                "--sponsorblock-remove",
                "sponsor,selfpromo,interaction,intro,outro",
            )
        }
    }

    /**
     * Maps yt-dlp's error output onto something the UI can explain and act on.
     *
     * Order matters: the bot-check message also contains "sign in", but it is usually
     * fixed by updating yt-dlp, whereas an age gate is not.
     */
    fun classify(message: String?): FailureKind {
        val text = message.orEmpty().lowercase()
        fun has(vararg needles: String) = needles.any { text.contains(it) }

        return when {
            has("not a bot", "nsig", "unable to extract", "failed to extract",
                "no video formats", "http error 403", "signature") -> FailureKind.STALE_EXTRACTOR

            has("age-restricted", "age restricted", "confirm your age", "members-only",
                "members only", "login required", "sign in to confirm") ->
                FailureKind.AGE_RESTRICTED

            has("unable to connect", "connection", "timed out", "timeout", "network",
                "temporary failure in name resolution", "ssl", "certificate") ->
                FailureKind.NETWORK

            has("video unavailable", "private video", "removed by the uploader",
                "does not exist", "not available in your country", "no longer available") ->
                FailureKind.UNAVAILABLE

            else -> FailureKind.UNKNOWN
        }
    }

    private fun parseInfo(json: JSONObject): MediaInfo? {
        val id = json.optString("id").takeIf { it.isNotBlank() }
        val title = json.optString("title").takeIf { it.isNotBlank() } ?: id ?: return null
        val pageUrl = json.optString("webpage_url")

        // The YouTube-specific fallbacks below would invent a wrong link for anyone
        // else's content, so gate them on the extractor actually being YouTube.
        val extractor = json.optString("extractor_key")
            .ifBlank { json.optString("ie_key") }
            .ifBlank { json.optString("extractor") }
        val isYouTube = extractor.contains("youtube", ignoreCase = true) ||
            YOUTUBE_HOST.containsMatchIn(pageUrl)

        val url = sequenceOf("webpage_url", "url", "original_url")
            .map { json.optString(it) }
            .firstOrNull { it.startsWith("http") }
            ?: id?.takeIf { isYouTube }?.let { "https://www.youtube.com/watch?v=$it" }
            ?: return null

        return MediaInfo(
            url = url,
            title = title,
            uploader = json.optString("uploader").takeIf { it.isNotBlank() }
                ?: json.optString("channel").takeIf { it.isNotBlank() },
            thumbnailUrl = explicitThumbnail(json)
                ?: if (isYouTube) youtubeThumbnail(id) else null,
            durationSeconds = json.optDouble("duration", 0.0).toLong(),
        )
    }

    /**
     * `thumbnail` is a plain URL but `thumbnails` is an array, and passing the array
     * to `optString` would just return its serialised form.
     */
    private fun explicitThumbnail(json: JSONObject): String? {
        json.optString("thumbnail").takeIf { it.startsWith("http") }?.let { return it }
        val array = json.optJSONArray("thumbnails") ?: return null
        for (index in array.length() - 1 downTo 0) {
            val url = array.optJSONObject(index)?.optString("url").orEmpty()
            if (url.startsWith("http")) return url
        }
        return null
    }

    private fun youtubeThumbnail(id: String?): String? {
        if (id == null || id.length != 11) return null
        if (!id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
        return "https://i.ytimg.com/vi/$id/mqdefault.jpg"
    }
    /** yt-dlp can be chatty, so slice out the outermost JSON object. */
    private fun extractJson(output: String): JSONObject? {
        val start = output.indexOf('{')
        val end = output.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(output.substring(start, end + 1)) }.getOrNull()
    }

    /** Matches only a real YouTube host, not a URL that merely mentions it. */
    private val YOUTUBE_HOST = Regex(
        """^https?://([a-z0-9-]+\.)*(youtube\.com|youtu\.be|youtube-nocookie\.com)/""",
        RegexOption.IGNORE_CASE,
    )
}
