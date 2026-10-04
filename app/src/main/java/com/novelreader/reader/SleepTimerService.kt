package com.novelreader.reader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.novelreader.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Mantiene el temporizador de lectura visible aunque NovelReader esté en segundo plano. */
class SleepTimerService : Service() {
    companion object {
        const val ACTION_TICK = "com.novelreader.action.SLEEP_TIMER_TICK"
        const val ACTION_FINISHED = "com.novelreader.action.SLEEP_TIMER_FINISHED"
        const val ACTION_CANCEL = "com.novelreader.action.SLEEP_TIMER_CANCEL"
        const val ACTION_CANCELLED = "com.novelreader.action.SLEEP_TIMER_CANCELLED"
        const val EXTRA_REMAINING_SECONDS = "remaining_seconds"
        private const val EXTRA_MINUTES = "minutes"
        private const val CHANNEL_ID = "novelreader_sleep_timer"
        private const val NOTIFICATION_ID = 4202

        fun start(context: Context, minutes: Int) {
            if (minutes <= 0) {
                stop(context)
                return
            }
            val intent = Intent(context, SleepTimerService::class.java)
                .putExtra(EXTRA_MINUTES, minutes)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SleepTimerService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var timerJob: Job? = null
    private var endAtElapsed: Long = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            sendEvent(ACTION_CANCELLED, 0L)
            stopSelf()
            return START_NOT_STICKY
        }

        val minutes = intent?.getIntExtra(EXTRA_MINUTES, 0) ?: 0
        if (minutes <= 0) {
            stopSelf()
            return START_NOT_STICKY
        }

        timerJob?.cancel()
        endAtElapsed = SystemClock.elapsedRealtime() + minutes * 60_000L
        startForegroundCompat(notification(minutes * 60L))
        sendEvent(ACTION_TICK, minutes * 60L)
        timerJob = scope.launch {
            while (true) {
                val remaining = ((endAtElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L) / 1000L)
                if (remaining <= 0L) break
                // Actualiza la notificación y la UI cada segundo para que el
                // usuario vea una cuenta regresiva real, no solo minutos.
                updateNotification(remaining)
                sendEvent(ACTION_TICK, remaining)
                delay(1_000L)
            }
            sendEvent(ACTION_FINISHED, 0L)
            updateNotification(0L)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun sendEvent(action: String, remainingSeconds: Long) {
        sendBroadcast(Intent(action).setPackage(packageName).putExtra(EXTRA_REMAINING_SECONDS, remainingSeconds))
    }

    private fun updateNotification(remainingSeconds: Long) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            notification(remainingSeconds)
        )
    }

    private fun notification(remainingSeconds: Long): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(this, 4203, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val cancelIntent = PendingIntent.getService(
            this,
            4204,
            Intent(this, SleepTimerService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val active = remainingSeconds > 0L
        val text = if (active) {
            "Tiempo restante: ${formatRemainingTime(remainingSeconds)}"
        } else {
            "Temporizador finalizado"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("NovelReader · Temporizador de lectura")
            .setContentText(text)
            .setSubText("Voz M5 · Supertonic")
            .setOngoing(active)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setWhen(System.currentTimeMillis() + remainingSeconds.coerceAtLeast(0L) * 1_000L)
            .setShowWhen(active)
            .setUsesChronometer(active)
            .setChronometerCountDown(true)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancelar", cancelIntent)
            .build()
    }

    private fun formatRemainingTime(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0L)
        val hours = safe / 3600L
        val minutes = (safe % 3600L) / 60L
        val remaining = safe % 60L
        return if (hours > 0L) {
            "%02d:%02d:%02d".format(hours, minutes, remaining)
        } else {
            "%02d:%02d".format(minutes, remaining)
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Temporizador de NovelReader", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        timerJob?.cancel()
        scope.cancel()
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
