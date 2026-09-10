package com.ytconverter.ui

import android.app.Application
import android.content.ClipboardManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.youtubedl_android.YoutubeDL
import com.ytconverter.R
import com.ytconverter.data.AudioFormat
import com.ytconverter.data.DownloadRecord
import com.ytconverter.data.HistoryRepository
import com.ytconverter.data.SettingsRepository
import com.ytconverter.downloader.DownloadBus
import com.ytconverter.downloader.DownloadEvent
import com.ytconverter.downloader.DownloadService
import com.ytconverter.downloader.DownloadUiState
import com.ytconverter.downloader.YtDlpEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class EngineStatus { IDLE, UPDATING, UPDATED, FAILED }

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val history = HistoryRepository(application)
    private val settings = SettingsRepository(application)

    private val _url = MutableStateFlow("")
    val url: StateFlow<String> = _url.asStateFlow()

    private val _format = MutableStateFlow(AudioFormat.MP3)
    val format: StateFlow<AudioFormat> = _format.asStateFlow()

    private val _engineStatus = MutableStateFlow(EngineStatus.IDLE)
    val engineStatus: StateFlow<EngineStatus> = _engineStatus.asStateFlow()

    private val _messages = MutableSharedFlow<String>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val active: StateFlow<DownloadUiState?> = DownloadBus.active
    val items: StateFlow<List<DownloadRecord>> = history.items
    val folder: StateFlow<String> = settings.folder

    private var startJob: Job? = null

    init {
        viewModelScope.launch {
            DownloadBus.events.collect { event ->
                when (event) {
                    is DownloadEvent.Finished -> emit(text(R.string.notif_done))
                    is DownloadEvent.Failed -> emit(text(R.string.notif_failed))
                    DownloadEvent.Canceled -> Unit
                }
            }
        }
    }

    fun onUrlChange(value: String) {
        _url.value = value
    }

    fun onFormatChange(value: AudioFormat) {
        _format.value = value
    }

    fun clearUrl() {
        _url.value = ""
    }

    fun pasteFromClipboard() {
        clipboardText()?.let { _url.value = it }
    }

    /** Offers the clipboard link on cold start, but only if it looks like a video. */
    fun prefillFromClipboardIfLink() {
        if (_url.value.isNotBlank()) return
        val text = clipboardText() ?: return
        if (looksLikeVideoLink(text)) _url.value = text
    }

    fun start() {
        val link = normalize(_url.value)
        if (link == null) {
            emit(text(R.string.bad_url))
            return
        }
        if (DownloadBus.active.value != null || startJob?.isActive == true) {
            emit(text(R.string.busy))
            return
        }

        val chosen = _format.value
        DownloadBus.publish(
            DownloadUiState(
                title = link,
                uploader = null,
                thumbnailUrl = null,
                format = chosen,
                progress = -1f,
                etaSeconds = -1L,
                phase = DownloadUiState.Phase.PREPARING,
                detail = null,
            )
        )

        startJob = viewModelScope.launch {
            try {
                // A quick metadata read makes the progress card meaningful straight away.
                val probe = withContext(Dispatchers.IO) {
                    runCatching { YtDlpEngine.probe(app, link) }.getOrNull()
                }
                if (!isActive) return@launch
                if (probe != null) {
                    DownloadBus.active.value?.let { current ->
                        DownloadBus.publish(
                            current.copy(
                                title = probe.title,
                                uploader = probe.uploader,
                                thumbnailUrl = probe.thumbnailUrl,
                            )
                        )
                    }
                }
                DownloadService.start(app, link, chosen, probe)
            } catch (canceled: CancellationException) {
                DownloadBus.publish(null)
                throw canceled
            }
        }
    }

    fun cancel() {
        startJob?.cancel()
        startJob = null
        DownloadBus.cancel()
        DownloadBus.publish(null)
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

    fun updateEngine() {
        if (_engineStatus.value == EngineStatus.UPDATING) return
        _engineStatus.value = EngineStatus.UPDATING
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    YtDlpEngine.ensureReady(app)
                    YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
                }.isSuccess
            }
            _engineStatus.value = if (ok) EngineStatus.UPDATED else EngineStatus.FAILED
        }
    }

    private fun emit(message: String) {
        _messages.tryEmit(message)
    }

    private fun text(resId: Int): String = app.getString(resId)

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

    private val app: Application get() = getApplication()

    private companion object {
        val VIDEO_HOSTS = listOf(
            "youtube.com",
            "youtu.be",
            "vimeo.com",
            "dailymotion.com",
            "soundcloud.com",
        )
    }
}
