package com.ytconverter.downloader

import androidx.compose.runtime.Immutable
import com.yausername.youtubedl_android.YoutubeDL
import com.ytconverter.data.AudioFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the engine is doing for a single queue item. */
enum class JobPhase { PREPARING, DOWNLOADING, CONVERTING, REPAIRING }

enum class JobStatus { QUEUED, RUNNING, FINISHED, FAILED, CANCELED }

/**
 * One conversion. Items are created with an empty [title] when they are queued in
 * bulk, and the service fills the metadata in just before it starts on them, so
 * pasting twenty links does not mean twenty metadata requests up front.
 */
@Immutable
data class QueueItem(
    val id: String,
    val url: String,
    val format: AudioFormat,
    val title: String = "",
    val uploader: String? = null,
    val thumbnailUrl: String? = null,
    val durationSeconds: Long = 0L,
    val status: JobStatus = JobStatus.QUEUED,
    val phase: JobPhase = JobPhase.PREPARING,
    /** 0f..1f, or -1f when the phase cannot report progress. */
    val progress: Float = -1f,
    val etaSeconds: Long = -1L,
    val detail: String? = null,
    /** Short status line such as "Engine updated, retrying". */
    val note: String? = null,
    /**
     * Cancellation has to survive the worker's own transitions: killing the yt-dlp
     * process only bites while it is actually running, so the flag rides along on the
     * item and is checked between phases.
     */
    val cancelRequested: Boolean = false,
    val errorKind: FailureKind? = null,
    val error: String? = null,
) {
    val isRunning: Boolean get() = status == JobStatus.RUNNING
    val isActive: Boolean get() = status == JobStatus.QUEUED || status == JobStatus.RUNNING
}

/**
 * Process-wide queue shared between [DownloadService] and the UI.
 *
 * State lives in memory only: if the process is killed the queue is lost, which is
 * acceptable because finished files are already in the media library and history.
 */
object DownloadBus {

    private val _queue = MutableStateFlow<List<QueueItem>>(emptyList())
    val queue: StateFlow<List<QueueItem>> = _queue.asStateFlow()

    @Volatile
    private var processId: String? = null

    fun enqueue(items: List<QueueItem>) {
        if (items.isNotEmpty()) _queue.update { it + items }
    }

    fun update(id: String, transform: (QueueItem) -> QueueItem) {
        _queue.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    fun remove(id: String) {
        _queue.update { list -> list.filterNot { it.id == id } }
    }

    fun clearFinished() {
        _queue.update { list -> list.filter { it.isActive } }
    }

    fun nextQueued(): QueueItem? = _queue.value.firstOrNull { it.status == JobStatus.QUEUED }

    fun firstRunning(): QueueItem? = _queue.value.firstOrNull { it.status == JobStatus.RUNNING }

    /**
     * Flags the item so the worker aborts it at the next phase boundary.
     *
     * Note this is a field on the item rather than a side set: the worker rewrites the
     * item on every status change, and a separate set would have to be kept in step
     * with those rewrites (and cleared at exactly the right moment).
     */
    fun requestCancel(id: String) {
        update(id) { it.copy(cancelRequested = true) }
    }

    /**
     * True when the item was flagged, or when it is no longer in the queue at all.
     *
     * The second case matters: the worker can already be holding an item that the UI
     * has just removed, and that must abort rather than quietly finish and publish.
     */
    fun isCanceled(id: String): Boolean {
        val item = _queue.value.firstOrNull { it.id == id } ?: return true
        return item.cancelRequested
    }

    /** Drops everything not yet started; whatever is running is killed separately. */
    fun cancelQueued() {
        _queue.update { list -> list.filterNot { it.status == JobStatus.QUEUED } }
    }

    fun setProcessId(id: String?) {
        processId = id
    }

    fun processIdFor(itemId: String): String = "job-$itemId"

    /**
     * Kills the running process only when it belongs to [id].
     *
     * Cancelling one item must never disturb another, so this checks ownership first
     * rather than reaching for whatever is currently executing.
     */
    fun cancelProcessFor(id: String) {
        val current = processId ?: return
        if (current == processIdFor(id)) {
            runCatching { YoutubeDL.getInstance().destroyProcessById(current) }
        }
    }

    /**
     * Kills the running yt-dlp process. The library notices the dead process and
     * raises [YoutubeDL.CanceledException] in the calling coroutine.
     */
    fun cancelCurrent() {
        processId?.let { runCatching { YoutubeDL.getInstance().destroyProcessById(it) } }
    }
}
