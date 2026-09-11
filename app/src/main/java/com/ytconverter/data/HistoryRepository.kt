package com.ytconverter.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * Persists the list of finished conversions as a small JSON blob.
 *
 * Deliberately a process-wide singleton: [com.ytconverter.downloader.DownloadService]
 * is what writes new records while [com.ytconverter.ui.MainViewModel] renders them, and
 * separate instances would each hold their own [MutableStateFlow] — the list would look
 * empty until a restart, and a later delete would then write the stale list back over
 * the fresh record.
 */
class HistoryRepository private constructor(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _items = MutableStateFlow(decode(prefs.getString(KEY_ITEMS, null)))
    val items: StateFlow<List<DownloadRecord>> = _items.asStateFlow()

    fun add(record: DownloadRecord) = mutate { current ->
        listOf(record) + current.filterNot { it.id == record.id }
    }

    /**
     * Adds many at once. A 300-song playlist would otherwise re-serialise and rewrite
     * the whole blob once per song.
     */
    fun addAll(records: List<DownloadRecord>) {
        if (records.isEmpty()) return
        val ids = records.mapTo(mutableSetOf()) { it.id }
        mutate { current -> records + current.filterNot { it.id in ids } }
    }

    fun remove(id: String) = mutate { current -> current.filterNot { it.id == id } }

    fun clear() = mutate { emptyList() }

    private fun mutate(transform: (List<DownloadRecord>) -> List<DownloadRecord>) {
        synchronized(this) {
            val next = transform(_items.value)
            prefs.edit().putString(KEY_ITEMS, encode(next)).apply()
            _items.value = next
        }
    }

    private fun encode(items: List<DownloadRecord>): String {
        val array = JSONArray()
        items.forEach { array.put(it.toJson()) }
        return array.toString()
    }

    private fun decode(raw: String?): List<DownloadRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(DownloadRecord::fromJson)
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val PREFS = "yt_converter_history"
        private const val KEY_ITEMS = "items"

        @Volatile
        private var instance: HistoryRepository? = null

        fun get(context: Context): HistoryRepository =
            instance ?: synchronized(this) {
                instance ?: HistoryRepository(context.applicationContext).also { instance = it }
            }
    }
}
