package com.novelreader.reader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.novelreader.R
import java.io.File

/** Mantiene los controles de M5 disponibles en segundo plano y en la pantalla de bloqueo. */
class AudioPlaybackService : Service() {
    companion object {
        const val ACTION_COMMAND = "com.novelreader.action.AUDIO_COMMAND"
        const val ACTION_PLAY_PAUSE = "com.novelreader.action.AUDIO_PLAY_PAUSE"
        const val ACTION_PREVIOUS = "com.novelreader.action.AUDIO_PREVIOUS"
        const val ACTION_NEXT = "com.novelreader.action.AUDIO_NEXT"
        const val ACTION_STOP = "com.novelreader.action.AUDIO_STOP"
        private const val ACTION_UPDATE = "com.novelreader.action.AUDIO_UPDATE"
        const val EXTRA_COMMAND = "audio_command"
        private const val EXTRA_TITLE = "audio_title"
        private const val EXTRA_TEXT = "audio_text"
        private const val EXTRA_PLAYING = "audio_playing"
        private const val EXTRA_PROGRESS = "audio_progress"
        private const val EXTRA_BOOK_ID = "audio_book_id"
        private const val EXTRA_BOOK_PATH = "audio_book_path"
        private const val EXTRA_COVER_PATH = "audio_cover_path"

        private const val CHANNEL_ID = "reading_audio"
        private const val NOTIFICATION_ID = 4203

        fun start(
            context: Context,
            title: String,
            text: String = "Voz M5",
            playing: Boolean = true,
            progress: Int = 0,
            bookId: String = "",
            bookPath: String = "",
            coverPath: String? = null
        ) {
            val intent = Intent(context, AudioPlaybackService::class.java)
                .setAction(ACTION_UPDATE)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_PLAYING, playing)
                .putExtra(EXTRA_PROGRESS, progress.coerceIn(0, 100))
                .putExtra(EXTRA_BOOK_ID, bookId)
                .putExtra(EXTRA_BOOK_PATH, bookPath)
                .putExtra(EXTRA_COVER_PATH, coverPath)
            ContextCompat.startForegroundService(context, intent)
        }

        fun update(
            context: Context,
            title: String,
            text: String,
            playing: Boolean,
            progress: Int = 0,
            bookId: String = "",
            bookPath: String = "",
            coverPath: String? = null
        ) = start(context, title, text, playing, progress, bookId, bookPath, coverPath)

        fun stop(context: Context) {
            context.stopService(Intent(context, AudioPlaybackService::class.java))
        }
    }

    private var title = "NovelReader"
    private var text = "Voz M5"
    private var playing = false
    private var progress = 0
    private var bookId = ""
    private var bookPath = ""
    private var coverPath: String? = null
    private var mediaSession: MediaSessionCompat? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        mediaSession = MediaSessionCompat(this, "NovelReaderAudio").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = sendCommand(ACTION_PLAY_PAUSE)
                override fun onPause() = sendCommand(ACTION_PLAY_PAUSE)
                override fun onSkipToNext() = sendCommand(ACTION_NEXT)
                override fun onSkipToPrevious() = sendCommand(ACTION_PREVIOUS)
                override fun onStop() = sendCommand(ACTION_STOP)
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE, ACTION_PREVIOUS, ACTION_NEXT, ACTION_STOP -> {
                sendCommand(intent.action!!)
                if (intent.action == ACTION_STOP) stopSelf()
            }
            ACTION_UPDATE -> {
                title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "NovelReader" }
                text = intent.getStringExtra(EXTRA_TEXT).orEmpty().ifBlank { "Voz M5" }
                playing = intent.getBooleanExtra(EXTRA_PLAYING, false)
                progress = intent.getIntExtra(EXTRA_PROGRESS, 0).coerceIn(0, 100)
                bookId = intent.getStringExtra(EXTRA_BOOK_ID).orEmpty()
                bookPath = intent.getStringExtra(EXTRA_BOOK_PATH).orEmpty()
                coverPath = intent.getStringExtra(EXTRA_COVER_PATH)
                updateMediaSession()
                startForegroundCompat(notification())
            }
            else -> startForegroundCompat(notification())
        }
        return START_NOT_STICKY
    }

    private fun sendCommand(command: String) {
        sendBroadcast(Intent(ACTION_COMMAND).setPackage(packageName).putExtra(EXTRA_COMMAND, command))
    }

    private fun updateMediaSession() {
        val bitmap = coverBitmap()
        mediaSession?.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, text)
                .apply { if (bitmap != null) putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap) }
                .build()
        )
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_STOP
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    progress.toLong(),
                    if (playing) 1f else 0f
                )
                .build()
        )
    }

    private fun notification(): Notification {
        val openIntent = if (bookId.isNotBlank() && bookPath.isNotBlank()) {
            val readerActivity = if (bookPath.endsWith(".pdf", ignoreCase = true)) {
                PdfReaderActivity::class.java
            } else {
                ReaderActivity::class.java
            }
            PendingIntent.getActivity(
                this,
                4204,
                Intent(this, readerActivity)
                    .putExtra(ReaderActivity.EXTRA_BOOK_ID, bookId)
                    .putExtra(ReaderActivity.EXTRA_BOOK_PATH, bookPath)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                PendingIntent.getActivity(this, 4204, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }
        }
        val previous = serviceAction(ACTION_PREVIOUS, 4205)
        val playPause = serviceAction(ACTION_PLAY_PAUSE, 4206)
        val next = serviceAction(ACTION_NEXT, 4207)
        val stop = serviceAction(ACTION_STOP, 4208)
        val style = MediaStyle().setMediaSession(mediaSession?.sessionToken).setShowActionsInCompactView(0, 1, 2)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setLargeIcon(coverBitmap())
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setStyle(style)
            .setProgress(100, progress, false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(android.R.drawable.ic_media_previous, "Párrafo anterior", previous)
            .addAction(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (playing) "Pausar" else "Reproducir", playPause)
            .addAction(android.R.drawable.ic_media_next, "Párrafo siguiente", next)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", stop)
            .build()
    }

    private fun coverBitmap(): Bitmap? {
        val file = coverPath?.let(::File)
        if (file?.isFile == true) BitmapFactory.decodeFile(file.absolutePath)?.let { return it }
        val size = 256
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(30, 36, 39))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(224, 179, 106)
                textAlign = Paint.Align.CENTER
                textSize = 128f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            canvas.drawText(title.trim().take(1).uppercase().ifBlank { "N" }, size / 2f, size * .68f, paint)
        }
    }

    private fun serviceAction(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, AudioPlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun startForegroundCompat(value: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, value, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, value)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Lectura en voz alta", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Controles de reproducción de la voz M5"
                    setSound(null, null)
                }
            )
        }
    }

    override fun onDestroy() {
        mediaSession?.run { isActive = false; release() }
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
