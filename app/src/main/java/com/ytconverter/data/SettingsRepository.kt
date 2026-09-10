package com.ytconverter.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User preferences: where files land, and whether to trim non-music sections.
 *
 * A process-wide singleton so the service reads (and sees changes to) the same values
 * the settings UI writes.
 */
class SettingsRepository private constructor(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _folder = MutableStateFlow(
        prefs.getString(KEY_FOLDER, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_FOLDER
    )
    val folder: StateFlow<String> = _folder.asStateFlow()

    private val _skipNonMusic = MutableStateFlow(prefs.getBoolean(KEY_SKIP_NON_MUSIC, false))
    val skipNonMusic: StateFlow<Boolean> = _skipNonMusic.asStateFlow()

    fun setFolder(raw: String) {
        val clean = sanitize(raw)
        prefs.edit().putString(KEY_FOLDER, clean).apply()
        _folder.value = clean
    }

    fun setSkipNonMusic(value: Boolean) {
        prefs.edit().putBoolean(KEY_SKIP_NON_MUSIC, value).apply()
        _skipNonMusic.value = value
    }

    /**
     * Keeps the value safe to use as a single path segment under `Music/`.
     *
     * Leading/trailing dots and spaces go too: `..` would escape the Music directory,
     * and trailing dots are rejected by some filesystems.
     */
    fun sanitize(raw: String): String {
        val cleaned = raw
            .replace(Regex("""[/\\:*?"<>|]"""), "")
            .replace(Regex("""[\u0000-\u001f]"""), "")
            .trim()
            .trim('.', ' ')
            .take(48)
            .trim('.', ' ')
        return cleaned.ifBlank { DEFAULT_FOLDER }
    }

    companion object {
        const val DEFAULT_FOLDER = "YT Converter"
        private const val PREFS = "yt_converter_settings"
        private const val KEY_FOLDER = "folder"
        private const val KEY_SKIP_NON_MUSIC = "skipNonMusic"

        @Volatile
        private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
    }
}
