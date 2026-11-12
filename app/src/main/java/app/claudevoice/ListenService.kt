package app.claudevoice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import app.claudevoice.core.UiState
import kotlinx.coroutines.flow.update

/**
 * Foreground-Service: Android beendet Hintergrund-Apps mit Mikrofonzugriff, sobald der
 * Bildschirm aus ist. Ein Foreground-Service mit sichtbarer Benachrichtigung darf weiterlaufen.
 * Phase 1: nur Start/Stopp und Benachrichtigung, die Audio-Pipeline folgt.
 */
class ListenService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
        }
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        AppState.ui.value = UiState(running = true, startedAtMs = System.currentTimeMillis(), status = "Läuft")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        AppState.ui.update { it.copy(running = false, speaking = false, partial = "", status = "Bereit") }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Zuhören", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ListenService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Claude Voice hört zu")
            .setContentText("Fragen im Gespräch werden beantwortet.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stopp", stop).build())
            .build()
    }

    companion object {
        const val ACTION_STOP = "app.claudevoice.STOP"
        private const val CHANNEL = "listen"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) =
            context.startForegroundService(Intent(context, ListenService::class.java))

        fun stopSpeaking(context: Context) {}

        fun stop(context: Context) =
            context.startService(Intent(context, ListenService::class.java).setAction(ACTION_STOP))
    }
}
