package com.ytconverter.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.ytconverter.MainActivity
import com.ytconverter.R
import com.ytconverter.data.DownloadRecord
import com.ytconverter.data.HistoryRepository
import com.ytconverter.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/**
 * Drains [DownloadBus] one item at a time as a foreground service, so conversions
 * survive the app being backgrounded or the screen turning off.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workerLock = Any()
    private var worker: Job? = null

    @Volatile
    private var lastStartId = 0

    @Volatile
    private var lastNotificationKey: String? = null

    /** Cancel asked for while the item was between yt-dlp invocations. */
    private class UserCanceledException : Exception()

    private class JobFailure(val kind: FailureKind, message: String?) : Exception(message)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId

        when (intent?.action) {
            ACTION_CANCEL_CURRENT -> {
                DownloadBus.cancelCurrent()
                return START_NOT_STICKY
            }

            ACTION_ENQUEUE -> {
                // Started as a foreground service, so a notification is owed promptly.
                startForeground(
                    NOTIFICATION_PROGRESS,
                    buildProgressNotification(
                        getString(R.string.app_name),
                        -1f,
                        getString(R.string.engine_setup),
                    ),
                )
                ensureWorker()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureWorker() {
        synchronized(workerLock) {
            if (worker?.isActive == true) return
            worker = scope.launch { drain() }
        }
    }

    private suspend fun drain() {
        // Ids handled by this run, so the summary reports this batch rather than every
        // item still sitting in the queue from earlier runs.
        val handled = mutableSetOf<String>()

        while (true) {
            val next = DownloadBus.nextQueued()
            if (next == null) {
                // A late enqueue can land just as we finish; give it a moment.
                delay(GRACE_MILLIS)
                if (DownloadBus.nextQueued() != null) continue

                // Snapshot the start id *before* looking at the queue. If a new start
                // lands after this read, stopSelfResult sees a newer id and refuses; if
                // it landed before, then the enqueue (which always precedes the start
                // request) is already visible in the queue check below.
                val startIdSnapshot = lastStartId
                val mayStop = synchronized(workerLock) {
                    !DownloadBus.queue.value.any { it.isActive } && stopSelfResult(startIdSnapshot)
                }
                if (!mayStop) continue

                reportSummary(handled)
                stopForeground(STOP_FOREGROUND_REMOVE)
                return
            }

            handled.add(next.id)
            DownloadBus.update(next.id) {
                it.copy(
                    status = JobStatus.RUNNING,
                    phase = JobPhase.PREPARING,
                    progress = -1f,
                    etaSeconds = -1L,
                    detail = null,
                    note = null,
                    error = null,
                    errorKind = null,
                )
            }
            // The item can be removed while we were marking it running.
            if (DownloadBus.queue.value.none { it.id == next.id }) continue
            notifyQueue(force = true)
            runItem(next)
        }
    }

    private fun reportSummary(handled: Set<String>) {
        val items = DownloadBus.queue.value
        fun countOf(status: JobStatus) =
            handled.count { id -> items.firstOrNull { it.id == id }?.status == status }

        val finished = countOf(JobStatus.FINISHED)
        val failed = countOf(JobStatus.FAILED)
        if (finished > 0 || failed > 0) notifySummary(finished, failed)
    }

    private suspend fun runItem(item: QueueItem) {
        val app = applicationContext
        val settings = SettingsRepository.get(app)
        val folder = settings.folder.value
        val trimNonMusic = settings.skipNonMusic.value
        val workDir = File(app.getExternalFilesDir(null) ?: app.filesDir, WORK_DIR)

        try {
            YtDlpEngine.ensureReady(app)
            throwIfCanceled(item.id)

            var resolved = item
            if (resolved.title.isBlank()) {
                YtDlpEngine.probeSingle(app, resolved.url)?.let { info ->
                    resolved = resolved.copy(
                        title = info.title,
                        uploader = info.uploader,
                        thumbnailUrl = info.thumbnailUrl,
                        durationSeconds = info.durationSeconds,
                    )
                    DownloadBus.update(resolved.id) {
                        it.copy(
                            title = info.title,
                            uploader = info.uploader,
                            thumbnailUrl = info.thumbnailUrl,
                            durationSeconds = info.durationSeconds,
                        )
                    }
                    notifyQueue(force = true)
                }
                throwIfCanceled(item.id)
            }

            runWithRepair(resolved, folder, trimNonMusic, workDir)
        } catch (canceled: CancellationException) {
            workDir.deleteRecursively()
            markCanceled(item.id)
            throw canceled
        } catch (canceled: YoutubeDL.CanceledException) {
            workDir.deleteRecursively()
            markCanceled(item.id)
        } catch (canceled: UserCanceledException) {
            workDir.deleteRecursively()
            markCanceled(item.id)
        } catch (failure: JobFailure) {
            workDir.deleteRecursively()
            markFailed(item.id, failure.kind, failure.message)
        } catch (t: Throwable) {
            workDir.deleteRecursively()
            markFailed(item.id, YtDlpEngine.classify(t.message), t.message)
        }
    }

    /**
     * Runs the download, and on a stale-extractor failure updates yt-dlp once and
     * retries. YouTube breaks older yt-dlp builds every few months, and without this
     * the app just dies with a cryptic message until someone updates it by hand.
     */
    private suspend fun runWithRepair(
        item: QueueItem,
        folder: String,
        trimNonMusic: Boolean,
        workDir: File,
    ) {
        var repaired = false
        while (true) {
            try {
                download(item, trimNonMusic, workDir)
                publish(workDir, item, folder)
                return
            } catch (canceled: YoutubeDL.CanceledException) {
                throw canceled
            } catch (canceled: UserCanceledException) {
                throw canceled
            } catch (t: Throwable) {
                val kind = YtDlpEngine.classify(t.message)
                if (repaired || kind != FailureKind.STALE_EXTRACTOR) {
                    // An update we already ran did not help, so do not tell the user to
                    // update the engine a second time.
                    throw JobFailure(if (repaired) FailureKind.UNKNOWN else kind, t.message)
                }

                repaired = true
                DownloadBus.update(item.id) {
                    it.copy(
                        phase = JobPhase.REPAIRING,
                        note = getString(R.string.phase_repairing),
                        detail = null,
                    )
                }
                notifyQueue(force = true)

                if (!YtDlpEngine.updateEngine(applicationContext)) {
                    throw JobFailure(FailureKind.STALE_EXTRACTOR, getString(R.string.engine_repair_failed))
                }

                // The partial download is useless now; start the item over.
                workDir.deleteRecursively()
                workDir.mkdirs()
                DownloadBus.update(item.id) {
                    it.copy(phase = JobPhase.PREPARING, note = getString(R.string.engine_repaired))
                }
                notifyQueue(force = true)
                throwIfCanceled(item.id)
            }
        }
    }

    private fun download(item: QueueItem, trimNonMusic: Boolean, workDir: File) {
        if (workDir.exists()) workDir.deleteRecursively()
        workDir.mkdirs()

        val request = YoutubeDLRequest(item.url)
        request.addOption("-o", File(workDir, "%(title)s.%(ext)s").absolutePath)
        YtDlpEngine.applyAudioOptions(request, item.format, trimNonMusic)

        // Last chance to abort before the process actually exists: after this point the
        // only way out is killing it, which needs the id to be registered already.
        throwIfCanceled(item.id)

        val processId = DownloadBus.processIdFor(item.id)
        DownloadBus.setProcessId(processId)
        try {
            YoutubeDL.execute(request, processId, false) { percent, eta, line ->
                onEngineLine(item.id, percent, eta, line)
            }
        } finally {
            DownloadBus.setProcessId(null)
        }
    }

    private suspend fun publish(workDir: File, item: QueueItem, folder: String) {
        val app = applicationContext
        val produced = newestAudioFile(workDir)

        if (produced == null) {
            workDir.deleteRecursively()
            markFailed(item.id, FailureKind.UNKNOWN, null)
            return
        }

        val published = MediaPublisher.publish(app, produced, folder)
        workDir.deleteRecursively()

        if (published == null) {
            markFailed(item.id, FailureKind.UNKNOWN, null)
            return
        }

        val record = DownloadRecord(
            id = published.fileName,
            title = item.title.ifBlank { published.fileName },
            uploader = item.uploader,
            thumbnailUrl = item.thumbnailUrl,
            formatName = item.format.name,
            uri = published.uri,
            fileName = published.fileName,
            mimeType = published.mimeType,
            sizeBytes = published.sizeBytes,
            durationSeconds = item.durationSeconds,
            createdAt = System.currentTimeMillis(),
        )
        HistoryRepository.get(app).add(record)
        DownloadBus.update(item.id) {
            it.copy(
                status = JobStatus.FINISHED,
                phase = JobPhase.CONVERTING,
                progress = 1f,
                etaSeconds = -1L,
                detail = null,
                note = null,
            )
        }
        notifyQueue(force = true)
    }

    private fun markCanceled(id: String) {
        DownloadBus.update(id) {
            it.copy(status = JobStatus.CANCELED, progress = -1f, detail = null, note = null)
        }
        notifyQueue(force = true)
    }

    private fun markFailed(id: String, kind: FailureKind, message: String?) {
        DownloadBus.update(id) {
            it.copy(
                status = JobStatus.FAILED,
                progress = -1f,
                detail = null,
                note = null,
                errorKind = kind,
                error = message?.takeIf { text -> text.isNotBlank() }?.take(300),
            )
        }
        notifyQueue(force = true)
    }

    private fun throwIfCanceled(id: String) {
        if (DownloadBus.isCanceled(id)) throw UserCanceledException()
    }

    private fun newestAudioFile(dir: File): File? = dir.listFiles()
        ?.filter { MediaPublisher.isAudioFile(it) }
        ?.maxByOrNull { it.lastModified() }

    private fun onEngineLine(itemId: String, percent: Float, eta: Long, line: String) {
        val current = DownloadBus.queue.value.firstOrNull { it.id == itemId } ?: return

        val marker = CONVERT_MARKERS.any { line.startsWith(it) }
        val phase = when {
            current.phase == JobPhase.REPAIRING -> JobPhase.REPAIRING
            marker -> JobPhase.CONVERTING
            // Post-processing is terminal: yt-dlp keeps echoing the stale download
            // percentage, so never let that pull the label back.
            current.phase == JobPhase.CONVERTING -> JobPhase.CONVERTING
            line.startsWith("[download]") || percent >= 0f -> JobPhase.DOWNLOADING
            else -> current.phase
        }
        val fraction = if (percent in 0f..100f) percent / 100f else -1f

        DownloadBus.update(itemId) {
            it.copy(
                progress = fraction,
                etaSeconds = eta,
                phase = phase,
                detail = line.trim().take(120).takeIf { text -> text.isNotEmpty() },
                // A repair note should stop taking over the status line as soon as
                // real output resumes.
                note = if (marker || line.startsWith("[download]")) null else it.note,
            )
        }
        notifyQueue()
    }

    /** Throttled: only redraw the notification when the visible text would change. */
    private fun notifyQueue(force: Boolean = false) {
        // Nothing running means we are about to tear down; the summary notification
        // owns the end state, so do not overwrite it with a stale "Converting".
        val running = DownloadBus.firstRunning() ?: return
        val queued = DownloadBus.queue.value.count { it.status == JobStatus.QUEUED }
        val bucket = running.progress.takeIf { it >= 0f }?.times(100)?.roundToInt() ?: -1
        val key = "${running.id}:$bucket:$queued:${running.phase}:${running.note}"
        if (!force && key == lastNotificationKey) return
        lastNotificationKey = key

        val text = buildString {
            append(
                running.note
                    ?: when {
                        running.phase == JobPhase.REPAIRING -> getString(R.string.phase_repairing)
                        running.phase == JobPhase.CONVERTING -> getString(R.string.converting)
                        running.phase == JobPhase.PREPARING -> getString(R.string.engine_setup)
                        running.progress >= 0f -> "${(running.progress * 100).roundToInt()}%"
                        else -> getString(R.string.downloading)
                    }
            )
            if (queued > 0) append(" · +").append(queued)
        }.take(120)

        manager().notify(
            NOTIFICATION_PROGRESS,
            buildProgressNotification(running.title, running.progress, text),
        )
    }

    private fun notifySummary(saved: Int, failed: Int) {
        val text = when {
            failed == 0 -> getString(R.string.summary_saved, saved)
            saved == 0 -> getString(R.string.notif_failed)
            else -> getString(R.string.summary_mixed, saved, failed)
        }
        val content = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (failed == 0) R.drawable.ic_check else R.drawable.ic_info)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(content)
            .build()
        manager().notify(NOTIFICATION_RESULT, notification)
    }

    private fun buildProgressNotification(title: String, progress: Float, detail: String?): Notification {
        val content = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            this,
            REQUEST_CANCEL,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL_CURRENT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(detail ?: getString(R.string.engine_setup))
            .setProgress(100, (progress.coerceIn(0f, 1f) * 100).roundToInt(), progress < 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(content)
            .addAction(0, getString(R.string.cancel), cancel)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        manager().createNotificationChannel(channel)
    }

    private fun manager(): NotificationManager = getSystemService(NotificationManager::class.java)

    companion object {
        private const val CHANNEL_ID = "conversions"
        private const val NOTIFICATION_PROGRESS = 1001
        private const val NOTIFICATION_RESULT = 1002
        private const val REQUEST_OPEN = 10
        private const val REQUEST_CANCEL = 11
        private const val WORK_DIR = "work"
        private const val GRACE_MILLIS = 400L

        private const val ACTION_ENQUEUE = "com.ytconverter.action.ENQUEUE"
        private const val ACTION_CANCEL_CURRENT = "com.ytconverter.action.CANCEL_CURRENT"

        private val CONVERT_MARKERS = listOf(
            "[ExtractAudio]",
            "[ffmpeg]",
            "[Metadata]",
            "[EmbedThumbnail]",
            "[ThumbnailsConvertor]",
            "[SponsorBlock]",
            "[ModifyChapters]",
        )

        /** Starts the queue processor if it is not already running. */
        fun ensureRunning(context: Context) {
            val intent = Intent(context, DownloadService::class.java).setAction(ACTION_ENQUEUE)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
