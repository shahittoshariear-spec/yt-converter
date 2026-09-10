package com.ytconverter.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/** Persists the list of finished conversions as a small JSON blob. */
class HistoryRepository(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _items = MutableStateFlow(decode(prefs.getString(KEY_ITEMS, null)))
    val items: StateFlow<List<DownloadRecord>> = _items.asStateFlow()

    fun add(record: DownloadRecord) = mutate { current ->
        listOf(record) + current.filterNot { it.id == record.id }
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

    private companion object {
        const val PREFS = "yt_converter_history"
        const val KEY_ITEMS = "items"
    }
}
