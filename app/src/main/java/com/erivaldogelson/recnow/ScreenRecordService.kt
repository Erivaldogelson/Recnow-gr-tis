package com.erivaldogelson.recnow

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenRecordService : Service() {
    private var projection: MediaProjection? = null
    private var recorderEngine: ScreenRecorderEngine? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var output: RecordingOutput? = null
    private var startedAt = 0L
    private var quality = RecordingQualities.first()
    private var recordingOptions = RecordingOptions()
    private var captureSpec = CaptureSpec(quality.landscapeWidth, quality.landscapeHeight)
    private var isStopping = false
    private var isCountdownRunning = false
    private var countdownRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            if (projection != null) {
                val elapsed = System.currentTimeMillis() - startedAt
                sendState(true, elapsed)
                updateNotification(elapsed)
                handler.postDelayed(this, 1_000L)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            ACTION_START -> startRecording(intent)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (isCountdownRunning || projection != null || recorderEngine != null || output != null) {
            stopRecording()
        }
        super.onDestroy()
    }

    private fun startRecording(intent: Intent) {
        if (isCountdownRunning || projection != null) return

        recordingOptions = intent.recordingOptions()
        quality = recordingOptions.quality
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = intent.parcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return
        }

        startCountdown(resultCode, resultData)
    }

    private fun startCountdown(resultCode: Int, resultData: Intent) {
        isCountdownRunning = true
        startAsForegroundCountdown(COUNTDOWN_SECONDS)

        var remaining = COUNTDOWN_SECONDS
        val countdown = object : Runnable {
            override fun run() {
                if (isStopping) return
                if (remaining > 0) {
                    updateCountdownNotification(remaining)
                    remaining -= 1
                    handler.postDelayed(this, 1_000L)
                    return
                }
                isCountdownRunning = false
                countdownRunnable = null
                beginRecording(resultCode, resultData)
            }
        }
        countdownRunnable = countdown
        handler.post(countdown)
    }

    private fun beginRecording(resultCode: Int, resultData: Intent) {
        startedAt = System.currentTimeMillis()
        captureSpec = resolveCaptureSpec(quality)
        output = createOutput()
        startAsForeground(0L)

        val manager = getSystemService<MediaProjectionManager>() ?: return stopRecording()
        val mediaProjection = manager.getMediaProjection(resultCode, resultData) ?: return stopRecording()
        projection = mediaProjection.apply {
            registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopRecording()
                }
            }, handler)
        }

        val activeOutput = output ?: return stopRecording()
        val activeProjection = projection ?: return stopRecording()
        recorderEngine = ScreenRecorderEngine(
            context = this,
            outputDescriptor = activeOutput.descriptor,
            spec = captureSpec,
            quality = quality,
            options = recordingOptions,
            projection = activeProjection
        ).also { it.start() }
        virtualDisplay = projection?.createVirtualDisplay(
            "RecnowCapture",
            captureSpec.width,
            captureSpec.height,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            recorderEngine?.inputSurface,
            null,
            handler
        )
        sendState(true, 0L)
        handler.post(ticker)
    }

    private fun stopRecording() {
        if (isStopping) return
        isStopping = true
        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null
        isCountdownRunning = false
        handler.removeCallbacks(ticker)
        val elapsed = if (startedAt == 0L) 0L else System.currentTimeMillis() - startedAt

        runCatching { virtualDisplay?.release() }
        runCatching { recorderEngine?.stop() }
        runCatching { recorderEngine?.release() }
        runCatching { projection?.stop() }

        recorderEngine = null
        virtualDisplay = null
        projection = null
        val finishedOutput = output
        output = null
        startedAt = 0L

        finishedOutput?.let {
            finalizeOutput(it)
        }
        sendState(false, elapsed, finishedOutput?.displayPath)
        if (finishedOutput != null) {
            showFinishedNotification(finishedOutput)
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_DETACH)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(false)
            }
            handler.postDelayed({
                showFinishedNotification(finishedOutput)
                handler.postDelayed({ showFinishedNotification(finishedOutput) }, 650L)
                stopSelf()
            }, 350L)
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startAsForegroundCountdown(remaining: Int) {
        val notification = buildCountdownNotification(remaining)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startAsForeground(elapsedMs: Long) {
        val notification = buildRecordingNotification(elapsedMs)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(elapsedMs: Long) {
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildRecordingNotification(elapsedMs))
        }
    }

    private fun updateCountdownNotification(remaining: Int) {
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildCountdownNotification(remaining))
        }
    }

    private fun buildCountdownNotification(remaining: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPendingIntent = stopPendingIntent()
        val content = getString(R.string.recording_countdown, remaining)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_record)
            .setContentTitle(getString(R.string.recording_screen))
            .setContentText(content)
            .setSubText("Recnow")
            .setColor(FERRARI_RED)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setShowWhen(false)
            .setContentIntent(openPendingIntent)
            .addAction(R.drawable.ic_notification_record, getString(R.string.stop), stopPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(getString(R.string.recording_screen))
                    .bigText("$content\n${getString(R.string.quality_label)}: ${quality.shortLabel}")
                    .setSummaryText("Recnow")
            )
            .applyLiveUpdateHints(remaining.toString())
            .build()
    }

    private fun buildRecordingNotification(elapsedMs: Long): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPendingIntent = stopPendingIntent()
        val elapsed = RecordingState(true, elapsedMs, quality.shortLabel).formattedElapsed

        val isRecPulseOn = (elapsedMs / 1_000L) % 2L == 0L
        val content = "$elapsed em ${quality.shortLabel}${recordingOptions.badgeText()}"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (isRecPulseOn) R.drawable.ic_notification_record else R.drawable.ic_notification_record_off)
            .setContentTitle(getString(R.string.recording_screen))
            .setContentText(content)
            .setSubText("Recnow")
            .setColor(FERRARI_RED)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setUsesChronometer(true)
            .setWhen(startedAt)
            .setShowWhen(true)
            .setContentIntent(openPendingIntent)
            .addAction(R.drawable.ic_notification_record, getString(R.string.stop), stopPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(getString(R.string.recording_screen))
                    .bigText(
                        "${getString(R.string.elapsed_label)}: $elapsed\n" +
                            "${getString(R.string.quality_label)}: ${quality.shortLabel}" +
                            recordingOptions.expandedOptionsText(this)
                    )
                    .setSummaryText("Recnow")
            )
            .applyLiveUpdateHints(elapsed)
            .build()
    }

    private fun stopPendingIntent(): PendingIntent {
        val stopIntent = Intent(this, ScreenRecordService::class.java).setAction(ACTION_STOP)
        return PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun NotificationCompat.Builder.applyLiveUpdateHints(elapsed: String): NotificationCompat.Builder {
        if (Build.VERSION.SDK_INT >= 36) {
            setRequestPromotedOngoing(true)
            setShortCriticalText(elapsed)
        }
        return this
    }

    private fun showFinishedNotification(output: RecordingOutput) {
        val openIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(output.uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val pendingIntent = PendingIntent.getActivity(
            this,
            2,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val keepIntent = Intent(this, RecordingActionReceiver::class.java)
            .setAction(RecordingActionReceiver.ACTION_KEEP)
            .putExtra(RecordingActionReceiver.EXTRA_URI, output.uri.toString())
        val keepPendingIntent = PendingIntent.getBroadcast(
            this,
            3,
            keepIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val deleteIntent = Intent(this, RecordingActionReceiver::class.java)
            .setAction(RecordingActionReceiver.ACTION_DELETE)
            .putExtra(RecordingActionReceiver.EXTRA_URI, output.uri.toString())
            .putExtra(RecordingActionReceiver.EXTRA_FILE_PATH, output.publicFile?.absolutePath)
        val deletePendingIntent = PendingIntent.getBroadcast(
            this,
            4,
            deleteIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_record)
            .setContentTitle(getString(R.string.screen_recorded))
            .setContentText(getString(R.string.recording_saved_actions))
            .setColor(FERRARI_RED)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_notification_record, getString(R.string.save), keepPendingIntent)
            .addAction(R.drawable.ic_notification_record, getString(R.string.view), pendingIntent)
            .addAction(R.drawable.ic_notification_record, getString(R.string.delete), deletePendingIntent)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(getString(R.string.screen_recorded))
                    .bigText(getString(R.string.finished_big_text))
                    .setSummaryText("Recnow")
            )
            .applyFinishedLiveUpdateHints()
            .build()
        runCatching {
            NotificationManagerCompat.from(this).notify(FINISHED_NOTIFICATION_ID, notification)
        }
    }

    private fun NotificationCompat.Builder.applyFinishedLiveUpdateHints(): NotificationCompat.Builder {
        if (Build.VERSION.SDK_INT >= 36) {
            setRequestPromotedOngoing(true)
            setShortCriticalText(getString(R.string.screen_recorded))
        }
        return this
    }

    private fun createOutput(): RecordingOutput {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val displayName = "Recnow_$timestamp.mp4"

        if (Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Recnow")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Nao foi possivel criar arquivo no MediaStore")
            val descriptor = contentResolver.openFileDescriptor(uri, "w")
                ?: error("Nao foi possivel abrir arquivo no MediaStore")
            return RecordingOutput(
                uri = uri,
                descriptor = descriptor,
                displayPath = "Movies/Recnow/$displayName",
                publicFile = null
            )
        }

        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "Recnow"
        )
        if (!directory.exists()) directory.mkdirs()
        val file = File(directory, displayName)
        val descriptor = ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE or
                ParcelFileDescriptor.MODE_READ_WRITE
        )
        return RecordingOutput(
            uri = FileProvider.getUriForFile(this, "$packageName.files", file),
            descriptor = descriptor,
            displayPath = file.absolutePath,
            publicFile = file
        )
    }

    private fun finalizeOutput(output: RecordingOutput) {
        runCatching { output.descriptor.close() }
        if (Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
            }
            runCatching { contentResolver.update(output.uri, values, null, null) }
        } else {
            output.publicFile?.let { file ->
                val values = android.content.ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.DATA, file.absolutePath)
                }
                runCatching { contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) }
            }
        }
    }

    private fun resolveCaptureSpec(quality: RecordingQuality): CaptureSpec {
        val displaySize = currentDisplaySize()
        val screenWidth = displaySize.x.coerceAtLeast(1)
        val screenHeight = displaySize.y.coerceAtLeast(1)
        val screenLong = maxOf(screenWidth, screenHeight).toFloat()
        val screenShort = minOf(screenWidth, screenHeight).toFloat()
        val targetLong = maxOf(quality.landscapeWidth, quality.landscapeHeight).toFloat()
        val targetShort = minOf(quality.landscapeWidth, quality.landscapeHeight).toFloat()
        val scale = minOf(targetLong / screenLong, targetShort / screenShort)
        val rawWidth = (screenWidth * scale).toInt().coerceAtLeast(2)
        val rawHeight = (screenHeight * scale).toInt().coerceAtLeast(2)
        return CaptureSpec(rawWidth.toEven(), rawHeight.toEven())
    }

    private fun currentDisplaySize(): Point {
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = getSystemService<WindowManager>()?.maximumWindowMetrics?.bounds
            if (bounds != null) return Point(bounds.width(), bounds.height())
        }

        @Suppress("DEPRECATION")
        val display = getSystemService<WindowManager>()?.defaultDisplay
        val point = Point()
        @Suppress("DEPRECATION")
        display?.getRealSize(point)
        return if (point.x > 0 && point.y > 0) point else Point(
            resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels
        )
    }

    private fun sendState(isRecording: Boolean, elapsedMs: Long, lastFilePath: String? = null) {
        val state = RecordingState(isRecording, elapsedMs, quality.shortLabel, lastFilePath)
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).putExtra(EXTRA_STATE, state.toBundle()))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService<NotificationManager>()?.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.erivaldogelson.recnow.START"
        const val ACTION_STOP = "com.erivaldogelson.recnow.STOP"
        const val ACTION_STATE = "com.erivaldogelson.recnow.STATE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_QUALITY_INDEX = "quality_index"
        const val EXTRA_AUDIO_MODE = "audio_mode"
        const val EXTRA_STATE = "state"
        private const val CHANNEL_ID = "screen_recording"
        private const val NOTIFICATION_ID = 70
        private const val COUNTDOWN_SECONDS = 3
        const val FINISHED_NOTIFICATION_ID = NOTIFICATION_ID
        private val FERRARI_RED = Color.rgb(255, 0, 0)

        fun startIntent(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            quality: RecordingQuality,
            options: RecordingOptions = RecordingOptions(
                qualityIndex = RecordingQualities.indexOf(quality).coerceAtLeast(0)
            )
        ): Intent = Intent(context, ScreenRecordService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_RESULT_CODE, resultCode)
            putExtra(EXTRA_RESULT_DATA, resultData)
            putRecordingOptions(options.copy(qualityIndex = RecordingQualities.indexOf(quality).coerceAtLeast(0)))
        }

        fun stopIntent(context: Context): Intent = Intent(context, ScreenRecordService::class.java).setAction(ACTION_STOP)
    }
}

fun Intent.putRecordingOptions(options: RecordingOptions): Intent = apply {
    putExtra(ScreenRecordService.EXTRA_QUALITY_INDEX, options.qualityIndex)
    putExtra(ScreenRecordService.EXTRA_AUDIO_MODE, options.audioMode.name)
}

fun Intent.recordingOptions(): RecordingOptions = RecordingOptions(
    qualityIndex = getIntExtra(ScreenRecordService.EXTRA_QUALITY_INDEX, 1),
    audioMode = runCatching {
        AudioMode.valueOf(getStringExtra(ScreenRecordService.EXTRA_AUDIO_MODE) ?: AudioMode.NONE.name)
    }.getOrDefault(AudioMode.NONE)
)

private fun RecordingOptions.badgeText(): String {
    val labels = buildList {
        if (audioMode != AudioMode.NONE) add(audioMode.badgeLabel())
    }
    return if (labels.isEmpty()) "" else " · ${labels.joinToString()}"
}

private fun RecordingOptions.expandedOptionsText(context: Context): String {
    val labels = buildList {
        if (audioMode != AudioMode.NONE) add(audioMode.label(context))
    }
    return if (labels.isEmpty()) "" else "\n${context.getString(R.string.selected_options_label)}: ${labels.joinToString()}"
}

fun AudioMode.label(context: Context): String = when (this) {
    AudioMode.NONE -> context.getString(R.string.audio_none)
    AudioMode.MICROPHONE -> context.getString(R.string.microphone)
    AudioMode.MEDIA -> context.getString(R.string.media_audio)
    AudioMode.MEDIA_AND_MICROPHONE -> context.getString(R.string.media_and_microphone)
}

private fun AudioMode.badgeLabel(): String = when (this) {
    AudioMode.NONE -> ""
    AudioMode.MICROPHONE -> "mic"
    AudioMode.MEDIA -> "media"
    AudioMode.MEDIA_AND_MICROPHONE -> "media+mic"
}

data class CaptureSpec(
    val width: Int,
    val height: Int
)

private data class RecordingOutput(
    val uri: Uri,
    val descriptor: ParcelFileDescriptor,
    val displayPath: String,
    val publicFile: File?
) {
    val fileDescriptor: java.io.FileDescriptor
        get() = descriptor.fileDescriptor
}

private fun Int.toEven(): Int = if (this % 2 == 0) this else this - 1

private inline fun <reified T : android.os.Parcelable> Intent.parcelableExtra(key: String): T? {
    return if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }
}
