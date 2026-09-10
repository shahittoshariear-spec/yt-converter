package com.ytconverter.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User preferences: currently just the folder files are written into. */
class SettingsRepository(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _folder = MutableStateFlow(
        prefs.getString(KEY_FOLDER, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_FOLDER
    )
    val folder: StateFlow<String> = _folder.asStateFlow()

    fun setFolder(raw: String) {
        val clean = sanitize(raw)
        prefs.edit().putString(KEY_FOLDER, clean).apply()
        _folder.value = clean
    }

    /** Keeps the value safe to use as a single path segment under `Music/`. */
    fun sanitize(raw: String): String {
        val cleaned = raw
            .replace(Regex("""[/\\:*?"<>|]"""), "")
            .replace(Regex("""[\u0000-\u001f]"""), "")
            .trim()
            .take(48)
        return cleaned.ifBlank { DEFAULT_FOLDER }
    }

    companion object {
        const val DEFAULT_FOLDER = "YT Converter"
        private const val PREFS = "yt_converter_settings"
        private const val KEY_FOLDER = "folder"
    }
}
