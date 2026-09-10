package com.ytconverter.ui

import android.app.Application
import android.content.ClipboardManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ytconverter.R
import com.ytconverter.data.AudioFormat
import com.ytconverter.data.DownloadRecord
import com.ytconverter.data.HistoryRepository
import com.ytconverter.data.SettingsRepository
import com.ytconverter.downloader.DownloadBus
import com.ytconverter.downloader.DownloadService
import com.ytconverter.downloader.JobPhase
import com.ytconverter.downloader.JobStatus
import com.ytconverter.downloader.MediaInfo
import com.ytconverter.downloader.QueueItem
import com.ytconverter.downloader.YtDlpEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class EngineStatus { IDLE, UPDATING, UPDATED, FAILED }

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val history = HistoryRepository.get(application)
    private val settings = SettingsRepository.get(application)

    private val _url = MutableStateFlow("")
    val url: StateFlow<String> = _url.asStateFlow()

    private val _format = MutableStateFlow(AudioFormat.MP3)
    val format: StateFlow<AudioFormat> = _format.asStateFlow()

    private val _resolving = MutableStateFlow(false)
    val resolving: StateFlow<Boolean> = _resolving.asStateFlow()

    private val _engineStatus = MutableStateFlow(EngineStatus.IDLE)
    val engineStatus: StateFlow<EngineStatus> = _engineStatus.asStateFlow()

    private val _messages = MutableSharedFlow<String>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** True when the link is a watch URL that also carries a playlist id. */
    val playlistHint: StateFlow<Boolean> = _url
        .map { isCollectionLink(it) && it.contains("v=", ignoreCase = true) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val queue: StateFlow<List<QueueItem>> = DownloadBus.queue
    val items: StateFlow<List<DownloadRecord>> = history.items
    val folder: StateFlow<String> = settings.folder
    val trimNonMusic: StateFlow<Boolean> = settings.skipNonMusic

    fun onUrlChange(value: String) {
        _url.value = value
    }

    fun onFormatChange(value: AudioFormat) {
        _format.value = value
    }

    fun clearUrl() {
        _url.value = ""
    }

    /**
     * Pulls every link off the clipboard. One link just fills the field so the format
     * can be chosen; several are queued straight away, which is the whole point of
     * batch mode.
     */
    fun pasteFromClipboard() {
        val text = clipboardText()
        val links = text
            ?.let { raw -> LINKS.findAll(raw).map { cleanLink(it.value) }.filter { it.isNotEmpty() }.distinct().toList() }
            .orEmpty()
        when {
            links.isEmpty() -> emit(text(R.string.no_links))
            links.size == 1 -> _url.value = links.first()
            else -> {
                val chosen = _format.value
                enqueue(links.map { queueItem(it, chosen, null) })
                _url.value = ""
                emit(text(R.string.enqueued_many, links.size))
            }
        }
    }

    fun prefillFromClipboardIfLink() {
        if (_url.value.isNotBlank()) return
        val link = clipboardText()?.let { LINKS.find(it)?.value }?.let(::cleanLink) ?: return
        if (looksLikeVideoLink(link)) _url.value = link
    }

    fun start() {
        val link = normalize(_url.value) ?: run {
            emit(text(R.string.bad_url))
            return
        }
        // A `watch?v=..&list=..` URL defaults to the single video; the playlist is an
        // explicit extra at the user's request.
        resolveAndEnqueue(link, expandCollection = isCollectionLink(link) && !link.contains("v=", true))
    }

    fun startWithPlaylist() {
        val link = normalize(_url.value) ?: run {
            emit(text(R.string.bad_url))
            return
        }
        resolveAndEnqueue(link, expandCollection = true)
    }

    private fun resolveAndEnqueue(link: String, expandCollection: Boolean) {
        if (_resolving.value) return
        _resolving.value = true
        viewModelScope.launch {
            try {
                val chosen = _format.value
                val items = withContext(Dispatchers.IO) {
                    if (expandCollection) {
                        val expanded = runCatching { YtDlpEngine.expandCollection(app, link) }
                            .getOrDefault(emptyList())
                            .map { queueItem(it.url, chosen, it) }
                        // A failed expansion still queues the original link, so the
                        // queue explains what went wrong instead of the tap doing nothing.
                        expanded.ifEmpty { listOf(queueItem(link, chosen, null)) }
                    } else {
                        val info = runCatching { YtDlpEngine.probeSingle(app, link) }.getOrNull()
                        listOf(queueItem(link, chosen, info))
                    }
                }

                enqueue(items)
                _url.value = ""
                emit(
                    if (items.size == 1) text(R.string.enqueued_one)
                    else text(R.string.enqueued_many, items.size)
                )
            } finally {
                _resolving.value = false
            }
        }
    }

    fun cancelItem(id: String) {
        val item = queue.value.firstOrNull { it.id == id } ?: return
        if (item.isRunning) {
            // Flag it and kill the process: the flag handles the gaps between yt-dlp
            // invocations, the process kill handles the download itself.
            DownloadBus.requestCancel(id)
            DownloadBus.cancelCurrent()
        } else {
            // Removing is enough — the worker treats "no longer in the queue" as
            // canceled, so an item it already picked up still aborts. The process kill
            // covers the gap where the worker had already spawned yt-dlp for it.
            DownloadBus.remove(id)
            DownloadBus.cancelProcessFor(id)
        }
    }

    fun cancelAll() {
        DownloadBus.cancelQueued()
        queue.value.filter { it.isRunning }.forEach { DownloadBus.requestCancel(it.id) }
        DownloadBus.cancelCurrent()
    }

    fun clearFinished() {
        DownloadBus.clearFinished()
    }

    fun retry(id: String) {
        DownloadBus.update(id) {
            it.copy(
                status = JobStatus.QUEUED,
                phase = JobPhase.PREPARING,
                progress = -1f,
                etaSeconds = -1L,
                detail = null,
                note = null,
                cancelRequested = false,
                error = null,
                errorKind = null,
            )
        }
        DownloadService.ensureRunning(app)
    }

    fun delete(record: DownloadRecord) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { app.contentResolver.delete(Uri.parse(record.uri), null, null) }
            }
            history.remove(record.id)
            emit(text(R.string.removed))
        }
    }

    fun clearAll() {
        val snapshot = items.value
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                snapshot.forEach { record ->
                    runCatching { app.contentResolver.delete(Uri.parse(record.uri), null, null) }
                }
            }
            history.clear()
        }
    }

    fun setFolder(name: String) {
        settings.setFolder(name)
    }

    fun setTrimNonMusic(value: Boolean) {
        settings.setSkipNonMusic(value)
    }

    fun updateEngine() {
        if (_engineStatus.value == EngineStatus.UPDATING) return
        _engineStatus.value = EngineStatus.UPDATING
        viewModelScope.launch {
            val ok = YtDlpEngine.updateEngine(app)
            _engineStatus.value = if (ok) EngineStatus.UPDATED else EngineStatus.FAILED
        }
    }

    private fun enqueue(items: List<QueueItem>) {
        if (items.isEmpty()) return
        DownloadBus.enqueue(items)
        DownloadService.ensureRunning(app)
    }

    private fun queueItem(url: String, format: AudioFormat, info: MediaInfo?) = QueueItem(
        id = UUID.randomUUID().toString(),
        url = url,
        format = format,
        title = info?.title.orEmpty(),
        uploader = info?.uploader,
        thumbnailUrl = info?.thumbnailUrl,
        durationSeconds = info?.durationSeconds ?: 0L,
    )

    /** Trims punctuation that often trails a link in shared or wrapped text. */
    private fun cleanLink(raw: String): String =
        raw.trimEnd(')', ']', '}', '>', ',', ';', '"', '\'', '.')

    private fun emit(message: String) {
        _messages.tryEmit(message)
    }

    private fun text(resId: Int, vararg args: Any): String = app.getString(resId, *args)

    private fun clipboardText(): String? {
        val clipboard = app.getSystemService(ClipboardManager::class.java) ?: return null
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0)
            ?.coerceToText(app)
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    /** Adds a scheme when missing and rejects anything that cannot be a link. */
    private fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.contains(' ')) return null
        val withScheme =
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
            else "https://$trimmed"
        val host = runCatching { Uri.parse(withScheme).host }.getOrNull() ?: return null
        if (!host.contains('.')) return null
        return withScheme
    }

    private fun looksLikeVideoLink(raw: String): Boolean {
        val normalized = normalize(raw) ?: return false
        val host = runCatching { Uri.parse(normalized).host }.getOrNull()?.lowercase() ?: return false
        return VIDEO_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private fun isCollectionLink(raw: String): Boolean {
        val value = raw.lowercase()
        return COLLECTION_MARKERS.any { value.contains(it) }
    }

    private val app: Application get() = getApplication()

    private companion object {
        val LINKS = Regex("""https?://\S+""")

        val VIDEO_HOSTS = listOf(
            "youtube.com",
            "youtu.be",
            "vimeo.com",
            "dailymotion.com",
            "soundcloud.com",
        )

        val COLLECTION_MARKERS = listOf(
            "list=",
            "/playlist",
            "/channel/",
            "/user/",
            "/c/",
            "/@",
            "/videos",
            "/sets/",
        )
    }
}
