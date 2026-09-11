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
                    itemIndex = 0,
                    itemTotal = 0,
                    savedCount = 0,
                    detail = null,
                    note = null,
                    caveat = null,
                    error = null,
                    errorKind = null,
                )
            }
            // The item can be removed while we were marking it running.
            if (DownloadBus.queue.value.none { it.id == next.id }) continue
            notifyQueue(force = true)
            // One bad item must not take the whole worker down: without this an escaped
            // exception would leave the foreground notification up until the next
            // enqueue. runItem already guarantees a terminal status; this is only about
            // keeping the drain alive. Cancellation still propagates as normal.
            try {
                runItem(next)
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (t: Throwable) {
                DownloadBus.update(next.id) { current ->
                    if (current.status == JobStatus.RUNNING) {
                        current.copy(status = JobStatus.FAILED)
                    } else {
                        current
                    }
                }
            }
        }
    }

    private fun reportSummary(handled: Set<String>) {
        val items = DownloadBus.queue.value
        val mine = items.filter { it.id in handled }

        // Count songs, not queue entries: one playlist item can be 376 files.
        val savedFiles = mine.sumOf { it.savedCount }
        val failures = mine.count { it.status == JobStatus.FAILED && it.savedCount == 0 }
        if (savedFiles > 0 || failures > 0) notifySummary(savedFiles, failures)
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
            // A playlist deliberately skips this probe. `probeSingle` passes
            // `--no-playlist`, so it would fetch the selected video's details rather
            // than the playlist's, and sweeping every entry for metadata first would
            // defeat the point of a single-run playlist. The engine reports the real
            // title and song count on its own header line instead.
            if (resolved.title.isBlank() && resolved.kind != JobKind.PLAYLIST) {
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
            // A cancel two thirds of the way through a long playlist should keep the
            // songs that already finished, not throw an hour of work away. Wrapped in
            // runCatching because a throw from inside a catch clause is not seen by its
            // siblings, which would leave the item stuck with no terminal status.
            if (item.kind == JobKind.PLAYLIST) runCatching { publish(item, workDir, folder) }
            workDir.deleteRecursively()
            markCanceled(item.id)
        } catch (canceled: UserCanceledException) {
            if (item.kind == JobKind.PLAYLIST) runCatching { publish(item, workDir, folder) }
            workDir.deleteRecursively()
            markCanceled(item.id)
        } catch (failure: JobFailure) {
            workDir.deleteRecursively()
            markFailed(item.id, failure.kind, failure.message)
        } catch (t: Throwable) {
            workDir.deleteRecursively()
            markFailed(item.id, YtDlpEngine.classify(t.message), t.message)
        } finally {
            // Safety net: an escaped exception used to leave the item RUNNING forever,
            // which also stopped the service from ever tearing down.
            DownloadBus.update(item.id) { current ->
                if (current.status == JobStatus.RUNNING) {
                    current.copy(status = JobStatus.FAILED)
                } else {
                    current
                }
            }
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
                val saved = publish(item, workDir, folder)
                if (saved == 0) throw JobFailure(FailureKind.UNKNOWN, null)
                return
            } catch (canceled: YoutubeDL.CanceledException) {
                throw canceled
            } catch (canceled: UserCanceledException) {
                throw canceled
            } catch (t: Throwable) {
                val kind = YtDlpEngine.classify(t.message)
                if (repaired || kind != FailureKind.STALE_EXTRACTOR) {
                    // A playlist may have spent an hour on 300 songs before one bad entry
                    // stopped it. Hand over what finished and treat that as success with
                    // a caveat: yt-dlp exits non-zero whenever `--ignore-errors` skipped
                    // anything, so a single dead video would otherwise mark 375 saved
                    // songs as a failure.
                    if (item.kind == JobKind.PLAYLIST) {
                        val saved = publish(item, workDir, folder)
                        if (saved > 0) {
                            DownloadBus.update(item.id) { current ->
                                // Keep a caveat the hand-over already set (songs it could
                                // not save); only add the stopped-early note if it did not.
                                if (current.caveat != null) current
                                else current.copy(caveat = getString(R.string.playlist_partial, saved))
                            }
                            return
                        }
                    }
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

        val isPlaylist = item.kind == JobKind.PLAYLIST
        val template = if (isPlaylist) {
            // A folder per playlist, with the index kept so album order survives on disk.
            // `playlist_autonumber` is the field meant for this: it is padded to the
            // width of the whole playlist, so "002" sorts before "010". The video id is
            // appended so cover art can be recovered later; [displayNameFor] strips it
            // again for the name the media library sees.
            "%(playlist_title)s/%(playlist_autonumber)s - %(title)s [%(id)s].%(ext)s"
        } else {
            "%(title)s.%(ext)s"
        }

        val request = YoutubeDLRequest(item.url)
        request.addOption("-o", File(workDir, template).absolutePath)
        YtDlpEngine.applyAudioOptions(request, item.format, trimNonMusic, playlist = isPlaylist)

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

    /**
     * Hands whatever the engine produced to the media library, and reports how many
     * files landed. A playlist run produces many; a partial hand-over is still partial
     * success, and the caller decides what that means.
     */
    private suspend fun publish(item: QueueItem, workDir: File, folder: String): Int =
        if (item.kind == JobKind.PLAYLIST) publishPlaylist(item, workDir, folder)
        else publishSingle(item, workDir, folder)

    private suspend fun publishSingle(item: QueueItem, workDir: File, folder: String): Int {
        val app = applicationContext
        val produced = newestAudioFile(workDir)

        if (produced == null) {
            workDir.deleteRecursively()
            markFailed(item.id, FailureKind.UNKNOWN, null)
            return 0
        }

        val published = MediaPublisher.publish(app, produced, folder)
        workDir.deleteRecursively()

        if (published == null) {
            markFailed(item.id, FailureKind.UNKNOWN, null)
            return 0
        }

        HistoryRepository.get(app).add(
            DownloadRecord(
                // The media URI, not the file name: two playlists can both legitimately
                // contain "001 - Intro.mp3", and history dedupes on this id.
                id = published.uri,
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
        )
        DownloadBus.update(item.id) {
            it.copy(
                status = JobStatus.FINISHED,
                phase = JobPhase.CONVERTING,
                progress = 1f,
                etaSeconds = -1L,
                detail = null,
                note = null,
                savedCount = 1,
            )
        }
        notifyQueue(force = true)
        return 1
    }

    private suspend fun publishPlaylist(item: QueueItem, workDir: File, folder: String): Int {
        val app = applicationContext
        // Only hand over files that already carry the requested extension. A cancel or
        // an `--ignore-errors` skip can leave the pre-conversion container behind, and
        // publishing that would put a webm in the library labelled as an MP3. ORIGINAL
        // keeps whatever the source was, so it accepts any audio extension.
        val wantedExtension = item.format.targetExtension?.lowercase()
        val files = workDir.walkTopDown()
            .filter {
                it.isFile && MediaPublisher.isAudioFile(it) &&
                    (wantedExtension == null || it.extension.lowercase() == wantedExtension)
            }
            .sortedBy { it.path }
            .toList()

        val records = mutableListOf<DownloadRecord>()
        var skipped = 0

        for (file in files) {
            val relative = runCatching { file.relativeTo(workDir) }.getOrNull()
            val subFolder = relative?.parentFile?.path?.takeIf { it.isNotBlank() && it != "." }
            val (displayName, videoId) = displayNameFor(file)

            // One unsaveable file must not abort the batch: the rest of the album still
            // has to land, and every success needs its history row written before its
            // source file is removed.
            val published = runCatching {
                MediaPublisher.publish(
                    context = app,
                    source = file,
                    folder = folder,
                    subPath = subFolder,
                    displayName = displayName,
                    reuseExisting = true,
                )
            }.getOrNull()

            if (published == null) {
                skipped++
                continue
            }

            records += DownloadRecord(
                id = published.uri,
                title = displayName.substringBeforeLast('.'),
                uploader = item.uploader,
                thumbnailUrl = videoId?.takeIf { it.length == 11 }
                    ?.let { "https://i.ytimg.com/vi/$it/mqdefault.jpg" },
                formatName = item.format.name,
                uri = published.uri,
                fileName = published.fileName,
                mimeType = published.mimeType,
                sizeBytes = published.sizeBytes,
                durationSeconds = 0L,
                createdAt = System.currentTimeMillis(),
            )

            // Delete as we go, so a throw part-way through leaves only the files that
            // have not been published yet instead of inserting every row twice.
            file.delete()

            // Show the hand-over progressing, or a big playlist looks frozen while the
            // files are copied. Throttled: this runs once per file.
            DownloadBus.update(item.id) {
                it.copy(savedCount = records.size, phase = JobPhase.CONVERTING, detail = null)
            }
            if (records.size % NOTIFY_EVERY == 0) notifyQueue(force = true)
        }

        if (records.isEmpty()) {
            workDir.deleteRecursively()
            return 0
        }

        runCatching { HistoryRepository.get(app).addAll(records) }
        workDir.deleteRecursively()
        val saved = records.size
        DownloadBus.update(item.id) {
            it.copy(
                status = JobStatus.FINISHED,
                phase = JobPhase.CONVERTING,
                progress = 1f,
                etaSeconds = -1L,
                detail = null,
                note = null,
                savedCount = saved,
                caveat = if (skipped > 0) {
                    getString(R.string.playlist_skipped, skipped)
                } else {
                    it.caveat
                },
            )
        }
        notifyQueue(force = true)
        return saved
    }

    /**
     * Pulls the video id back out of the name the playlist template produced, so the
     * media library gets a clean file name while the app list still gets cover art.
     * Only the trailing bracket token is stripped, and that token is always ours.
     */
    private fun displayNameFor(file: File): Pair<String, String?> {
        val stem = file.nameWithoutExtension
        val match = TRAILING_ID.find(stem) ?: return file.name to null
        val id = match.groupValues[1]
        val clean = stem.removeRange(match.range).trim().ifBlank { stem }
        return "$clean.${file.extension}" to id
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
        // Post-processing is terminal for a single video: yt-dlp keeps echoing the stale
        // download percentage, so never let that pull the label back. A playlist must be
        // allowed to fall back to DOWNLOADING for the next song, though.
        val convertingIsSticky = current.kind == JobKind.SINGLE
        val phase = when {
            current.phase == JobPhase.REPAIRING -> JobPhase.REPAIRING
            marker -> JobPhase.CONVERTING
            convertingIsSticky && current.phase == JobPhase.CONVERTING -> JobPhase.CONVERTING
            line.startsWith("[download]") || percent >= 0f -> JobPhase.DOWNLOADING
            else -> current.phase
        }
        val fraction = if (percent in 0f..100f) percent / 100f else -1f

        val header = PLAYLIST_HEADER.find(line)
        val playlistName = header?.groupValues?.get(1)?.trim()?.take(120)
        val headerTotal = header?.groupValues?.get(2)?.toIntOrNull()
        val playlistItem = PLAYLIST_ITEM.find(line)?.let { match ->
            val index = match.groupValues[1].toIntOrNull() ?: 0
            val total = match.groupValues[2].toIntOrNull() ?: 0
            index to total
        }

        DownloadBus.update(itemId) { item ->
            item.copy(
                progress = fraction,
                etaSeconds = eta,
                phase = phase,
                title = playlistName?.takeIf { item.title.isBlank() } ?: item.title,
                itemIndex = playlistItem?.first ?: item.itemIndex,
                // The header knows the size before the first song starts, so the bar has
                // a denominator from the outset.
                itemTotal = playlistItem?.second ?: headerTotal ?: item.itemTotal,
                detail = line.trim().take(120).takeIf { text -> text.isNotEmpty() },
                // A repair note should stop taking over the status line as soon as
                // real output resumes.
                note = if (marker || line.startsWith("[download]")) null else item.note,
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
        val bucket = running.displayProgress.takeIf { it >= 0f }?.times(100)?.roundToInt() ?: -1
        val key = "${running.id}:$bucket:$queued:${running.phase}:${running.note}:${running.savedCount}"
        if (!force && key == lastNotificationKey) return
        lastNotificationKey = key

        val text = buildString {
            append(
                running.note
                    ?: if (running.kind == JobKind.PLAYLIST && running.itemIndex > 0) {
                        getString(R.string.playlist_song_of, running.itemIndex, running.itemTotal)
                    } else {
                        when {
                            running.phase == JobPhase.REPAIRING -> getString(R.string.phase_repairing)
                            running.phase == JobPhase.CONVERTING -> getString(R.string.converting)
                            running.phase == JobPhase.PREPARING -> getString(R.string.engine_setup)
                            running.progress >= 0f -> "${(running.progress * 100).roundToInt()}%"
                            else -> getString(R.string.downloading)
                        }
                    }
            )
            if (running.kind == JobKind.PLAYLIST && running.savedCount > 0) {
                append(" · ")
                append(getString(R.string.playlist_saved, running.savedCount))
            }
            if (queued > 0) append(" · +").append(queued)
        }.take(120)

        manager().notify(
            NOTIFICATION_PROGRESS,
            buildProgressNotification(running.title, running.displayProgress, text),
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

        /**
         * yt-dlp's playlist header is `[<extractor>] Playlist <title>: Downloading <n>
         * items of <m>`, with the `of <m>` suffix only when the total is known.
         *
         * `.+` is greedy because playlist titles routinely contain colons, so it lands
         * on the last `: Downloading <digits> items`, which is the real delimiter.
         * Verified against the yt-dlp shipped in the library, which does not emit the
         * `Downloading playlist:` wording at all.
         */
        private val PLAYLIST_HEADER = Regex("""Playlist (.+): Downloading (\d+) items""")

        /** Same count as the header, repeated once per entry. */
        private val PLAYLIST_ITEM = Regex("""Downloading item (\d+) of (\d+)""")

        /** Notification updates are throttled to every Nth file during a hand-over. */
        private const val NOTIFY_EVERY = 10

        /** Trailing `[videoId]` that the playlist output template appends. */
        private val TRAILING_ID = Regex("""\s*\[([^\[\]]+)]$""")

        /** Starts the queue processor if it is not already running. */
        fun ensureRunning(context: Context) {
            val intent = Intent(context, DownloadService::class.java).setAction(ACTION_ENQUEUE)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
