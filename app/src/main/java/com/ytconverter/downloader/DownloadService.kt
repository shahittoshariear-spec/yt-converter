package com.ytconverter.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.ytconverter.MainActivity
import com.ytconverter.R
import com.ytconverter.data.AudioFormat
import com.ytconverter.data.DownloadRecord
import com.ytconverter.data.HistoryRepository
import com.ytconverter.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Runs one conversion at a time as a foreground service, so it survives the app being
 * backgrounded or the screen turning off.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    @Volatile
    private var lastNotifiedBucket = Int.MIN_VALUE

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                DownloadBus.cancel()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL)
                val format = intent.getStringExtra(EXTRA_FORMAT)
                    ?.let { runCatching { AudioFormat.valueOf(it) }.getOrNull() }
                    ?: AudioFormat.MP3
                val probe = ProbeInfo(
                    title = intent.getStringExtra(EXTRA_TITLE) ?: url.orEmpty(),
                    uploader = intent.getStringExtra(EXTRA_UPLOADER),
                    thumbnailUrl = intent.getStringExtra(EXTRA_THUMBNAIL),
                    durationSeconds = intent.getLongExtra(EXTRA_DURATION, 0L),
                )

                // Started as a foreground service, so a notification is owed either way.
                startForeground(
                    NOTIFICATION_PROGRESS,
                    buildProgressNotification(probe.title, -1f, null),
                )

                if (url.isNullOrBlank() || work?.isActive == true) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }

                work = scope.launch { convert(url, format, probe) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun convert(url: String, format: AudioFormat, probe: ProbeInfo) {
        val app = applicationContext
        val folder = SettingsRepository(app).folder.value
        val workDir = File(app.getExternalFilesDir(null) ?: app.filesDir, WORK_DIR)

        try {
            DownloadBus.publish(
                DownloadUiState(
                    title = probe.title,
                    uploader = probe.uploader,
                    thumbnailUrl = probe.thumbnailUrl,
                    format = format,
                    progress = -1f,
                    etaSeconds = -1L,
                    phase = DownloadUiState.Phase.PREPARING,
                    detail = getString(R.string.engine_setup),
                )
            )

            YtDlpEngine.ensureReady(app)

            if (workDir.exists()) workDir.deleteRecursively()
            workDir.mkdirs()

            val request = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("--no-mtime")
                addOption("--newline")
                addOption("-o", File(workDir, "%(title)s.%(ext)s").absolutePath)
                addOption("-x")
                format.ytDlpAudioFormat?.let { addOption("--audio-format", it) }
                format.audioQuality?.let { addOption("--audio-quality", it) }
                addOption("--embed-metadata")
                if (format != AudioFormat.ORIGINAL) {
                    // Cover art only makes sense in the formats that support it, and
                    // ORIGINAL is meant to come through untouched.
                    addOption("--embed-thumbnail")
                    addOption("--convert-thumbnails", "jpg")
                }
            }

            val processId = "convert-${UUID.randomUUID()}"
            DownloadBus.setProcessId(processId)
            YoutubeDL.execute(request, processId, false) { percent, eta, line ->
                onEngineLine(percent, eta, line)
            }
            DownloadBus.setProcessId(null)

            publish(workDir, format, probe, folder)
        } catch (canceled: CancellationException) {
            DownloadBus.setProcessId(null)
            workDir.deleteRecursively()
            DownloadBus.publish(null)
            throw canceled
        } catch (canceled: YoutubeDL.CanceledException) {
            DownloadBus.setProcessId(null)
            workDir.deleteRecursively()
            DownloadBus.publish(null)
            DownloadBus.emit(DownloadEvent.Canceled)
            finish(getString(R.string.cancel), null)
        } catch (t: Throwable) {
            DownloadBus.setProcessId(null)
            // Post-processing can fail after the audio itself is already on disk,
            // so fall back to publishing whatever we managed to produce.
            if (newestAudioFile(workDir) != null) {
                publish(workDir, format, probe, folder)
            } else {
                workDir.deleteRecursively()
                DownloadBus.publish(null)
                DownloadBus.emit(
                    DownloadEvent.Failed(t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName)
                )
                finish(getString(R.string.notif_failed), null)
            }
        }
    }

    private suspend fun publish(workDir: File, format: AudioFormat, probe: ProbeInfo, folder: String) {
        val app = applicationContext
        val produced = newestAudioFile(workDir)

        if (produced == null) {
            workDir.deleteRecursively()
            DownloadBus.publish(null)
            DownloadBus.emit(DownloadEvent.Failed(getString(R.string.notif_failed)))
            finish(getString(R.string.notif_failed), null)
            return
        }

        val published = MediaPublisher.publish(app, produced, folder)
        workDir.deleteRecursively()
        DownloadBus.publish(null)

        if (published == null) {
            DownloadBus.emit(DownloadEvent.Failed(getString(R.string.notif_failed)))
            finish(getString(R.string.notif_failed), null)
            return
        }

        val record = DownloadRecord(
            id = published.fileName,
            title = probe.title,
            uploader = probe.uploader,
            thumbnailUrl = probe.thumbnailUrl,
            formatName = format.name,
            uri = published.uri,
            fileName = published.fileName,
            mimeType = published.mimeType,
            sizeBytes = published.sizeBytes,
            durationSeconds = probe.durationSeconds,
            createdAt = System.currentTimeMillis(),
        )
        HistoryRepository(app).add(record)
        DownloadBus.emit(DownloadEvent.Finished(record))
        finish(record.title, record.uri to record.mimeType)
    }

    private fun newestAudioFile(dir: File): File? = dir.listFiles()
        ?.filter { MediaPublisher.isAudioFile(it) }
        ?.maxByOrNull { it.lastModified() }

    private fun onEngineLine(percent: Float, eta: Long, line: String) {
        val current = DownloadBus.active.value ?: return
        val marker = CONVERT_MARKERS.any { line.startsWith(it) }
        val phase = when {
            marker -> DownloadUiState.Phase.CONVERTING
            // Post-processing is terminal: yt-dlp keeps echoing the stale download
            // percentage, so never let that pull the label back.
            current.phase == DownloadUiState.Phase.CONVERTING -> DownloadUiState.Phase.CONVERTING
            line.startsWith("[download]") || percent >= 0f -> DownloadUiState.Phase.DOWNLOADING
            else -> current.phase
        }
        val fraction = if (percent in 0f..100f) percent / 100f else -1f

        DownloadBus.publish(
            current.copy(
                progress = fraction,
                etaSeconds = eta,
                phase = phase,
                detail = line.trim().take(120).takeIf { it.isNotEmpty() },
            )
        )

        val bucket = (fraction * 100f).roundToInt()
        if (bucket != lastNotifiedBucket) {
            lastNotifiedBucket = bucket
            val state = DownloadBus.active.value ?: return
            val detail = buildString {
                when (state.phase) {
                    DownloadUiState.Phase.PREPARING -> append(getString(R.string.engine_setup))
                    DownloadUiState.Phase.CONVERTING -> append(getString(R.string.converting))
                    DownloadUiState.Phase.DOWNLOADING -> {
                        if (bucket >= 0) append("$bucket%")
                        if (state.etaSeconds > 0) {
                            if (isNotEmpty()) append(" · ")
                            append("${state.etaSeconds}s")
                        }
                    }
                }
            }.takeIf { it.isNotEmpty() }
            manager().notify(
                NOTIFICATION_PROGRESS,
                buildProgressNotification(state.title, fraction, detail),
            )
        }
    }

    /** Tears down the foreground state and posts the final, dismissable result. */
    private fun finish(title: String, open: Pair<String, String>?) {
        val intent = if (open != null) {
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(open.first), open.second)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            Intent(this, MainActivity::class.java)
        }
        val content = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (open != null) R.drawable.ic_check else R.drawable.ic_info)
            .setContentTitle(title)
            .setContentText(getString(if (open != null) R.string.notif_done else R.string.notif_failed))
            .setAutoCancel(true)
            .setContentIntent(content)
            .build()

        manager().notify(NOTIFICATION_RESULT, notification)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
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

        private const val ACTION_START = "com.ytconverter.action.START"
        private const val ACTION_CANCEL = "com.ytconverter.action.CANCEL"

        private const val EXTRA_URL = "url"
        private const val EXTRA_FORMAT = "format"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_UPLOADER = "uploader"
        private const val EXTRA_THUMBNAIL = "thumbnail"
        private const val EXTRA_DURATION = "duration"

        private val CONVERT_MARKERS = listOf(
            "[ExtractAudio]",
            "[ffmpeg]",
            "[Metadata]",
            "[EmbedThumbnail]",
            "[ThumbnailsConvertor]",
        )

        fun start(context: Context, url: String, format: AudioFormat, probe: ProbeInfo?) {
            val intent = Intent(context, DownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_FORMAT, format.name)
                .putExtra(EXTRA_TITLE, probe?.title ?: url)
                .putExtra(EXTRA_UPLOADER, probe?.uploader)
                .putExtra(EXTRA_THUMBNAIL, probe?.thumbnailUrl)
                .putExtra(EXTRA_DURATION, probe?.durationSeconds ?: 0L)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
