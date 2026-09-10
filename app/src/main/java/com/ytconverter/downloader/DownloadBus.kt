package com.ytconverter.downloader

import com.yausername.youtubedl_android.YoutubeDL
import com.ytconverter.data.AudioFormat
import com.ytconverter.data.DownloadRecord
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live state of the conversion that is currently running. */
data class DownloadUiState(
    val title: String,
    val uploader: String?,
    val thumbnailUrl: String?,
    val format: AudioFormat,
    /** 0f..1f, or -1f when the current phase cannot report progress. */
    val progress: Float,
    val etaSeconds: Long,
    val phase: Phase,
    val detail: String?,
) {
    enum class Phase { PREPARING, DOWNLOADING, CONVERTING }
}

sealed interface DownloadEvent {
    data class Finished(val record: DownloadRecord) : DownloadEvent
    data class Failed(val message: String) : DownloadEvent
    data object Canceled : DownloadEvent
}

/**
 * Process-wide bridge between [DownloadService] and the UI.
 *
 * The service runs in the app's own process, so a plain singleton is enough to keep
 * both ends in sync without binding or IPC.
 */
object DownloadBus {

    private val _active = MutableStateFlow<DownloadUiState?>(null)
    val active: StateFlow<DownloadUiState?> = _active.asStateFlow()

    private val _events = MutableSharedFlow<DownloadEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<DownloadEvent> = _events.asSharedFlow()

    @Volatile
    private var processId: String? = null

    fun setProcessId(id: String?) {
        processId = id
    }

    /**
     * Kills the running yt-dlp process. The library notices the dead process and
     * raises [YoutubeDL.CanceledException] in the calling coroutine.
     */
    fun cancel() {
        processId?.let { runCatching { YoutubeDL.getInstance().destroyProcessById(it) } }
    }

    fun publish(state: DownloadUiState?) {
        _active.value = state
    }

    fun emit(event: DownloadEvent) {
        _events.tryEmit(event)
    }
}
