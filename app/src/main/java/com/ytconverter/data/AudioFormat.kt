package com.ytconverter.data

import androidx.annotation.StringRes
import com.ytconverter.R

/**
 * The audio outputs the converter can produce.
 *
 * [ytDlpAudioFormat] is handed straight to yt-dlp's `--audio-format`; `null` means
 * "leave the source container alone", which is what [ORIGINAL] does.
 */
enum class AudioFormat(
    @param:StringRes val labelRes: Int,
    @param:StringRes val blurbRes: Int,
    val ytDlpAudioFormat: String?,
    val audioQuality: String?,
) {
    MP3(
        labelRes = R.string.format_mp3,
        blurbRes = R.string.format_mp3_note,
        ytDlpAudioFormat = "mp3",
        audioQuality = "320K",
    ),
    FLAC(
        labelRes = R.string.format_flac,
        blurbRes = R.string.format_flac_note,
        ytDlpAudioFormat = "flac",
        audioQuality = "0",
    ),
    ORIGINAL(
        labelRes = R.string.format_original,
        blurbRes = R.string.format_original_note,
        ytDlpAudioFormat = null,
        audioQuality = null,
    );

    val shortName: String
        get() = when (this) {
            MP3 -> "MP3"
            FLAC -> "FLAC"
            ORIGINAL -> "SRC"
        }

    /**
     * Extension a finished file should carry, or null when the source container is
     * kept as-is. Used to avoid handing over a pre-conversion leftover (for example a
     * `.webm` still sitting in the work directory after an interrupted ffmpeg run)
     * as if it were the requested format.
     */
    val targetExtension: String?
        get() = ytDlpAudioFormat
}
