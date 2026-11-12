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
import android.os.PowerManager
import app.claudevoice.core.AnthropicClaude
import app.claudevoice.core.ElevenTts
import app.claudevoice.core.Pipeline
import app.claudevoice.core.ScribeClient
import app.claudevoice.core.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Foreground-Service: Android beendet Hintergrund-Apps mit Mikrofonzugriff, sobald der
 * Bildschirm aus ist. Ein Foreground-Service mit sichtbarer Benachrichtigung darf weiterlaufen.
 *
 * Hier wird nur verdrahtet: Mikrofon -> Transkription -> Pipeline (Filter, Claude) -> Sprachausgabe.
 * Die eigentliche Logik liegt in :core und ist dort getestet.
 */
class ListenService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS) // hält die WebSocket-Verbindung offen und bemerkt Funklöcher
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private var mic: MicSource? = null
    private var scribe: ScribeClient? = null
    private var speaker: AndroidSpeaker? = null
    private var pipeline: Pipeline? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_STOP_SPEAKING -> pipeline?.stopSpeaking()
            else -> if (pipeline == null) begin()
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        val prefs = Prefs(this)
        AppState.ui.value = UiState(running = true, startedAtMs = System.currentTimeMillis(), status = "Verbinde …")

        // Hält die CPU wach, wenn der Bildschirm aus ist. Wird in onDestroy wieder freigegeben.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "claudevoice:listen")
            .apply { acquire(4 * 60 * 60 * 1000L) }

        val microphone = MicSource(this, prefs.useBluetoothMic)
        mic = microphone
        try {
            // Zuerst das Mikro öffnen: danach steht fest, ob wirklich über die Kopfhörer aufgenommen wird.
            microphone.start { buffer, length -> scribe?.sendAudio(buffer, length) }
        } catch (e: Exception) {
            AppState.ui.update { it.copy(error = e.message) }
            stopSelf()
            return
        }
        val micName = if (microphone.usingBluetooth) "Kopfhörer-Mikro" else "Handy-Mikro"
        val fallbackNote = if (prefs.useBluetoothMic && !microphone.usingBluetooth)
            "Kein Kopfhörer-Mikro gefunden, nutze das Handy-Mikro." else null

        val out = AndroidSpeaker(ElevenTts(prefs.elevenKey, http), prefs.voiceId, microphone.usingBluetooth)
        speaker = out
        val pipe = Pipeline(AnthropicClaude(prefs.anthropicKey), out, AppState.ui, scope, Dispatchers.IO)
        pipeline = pipe
        pipe.start()

        scribe = ScribeClient(prefs.elevenKey, http, object : ScribeClient.Listener {
            override fun onPartial(text: String) = pipe.onPartial(text)
            override fun onCommitted(text: String) = pipe.onCommitted(text)
            override fun onConnection(connected: Boolean, error: String?) {
                AppState.ui.update {
                    it.copy(
                        status = if (connected) "Hört zu ($micName)" else "Verbindung unterbrochen, neuer Versuch …",
                        error = error ?: if (connected) fallbackNote else it.error,
                    )
                }
            }
        }).also { it.connect() }
    }

    override fun onDestroy() {
        mic?.stop()
        scribe?.close()
        pipeline?.stop()
        speaker?.release()
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
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
        const val ACTION_STOP_SPEAKING = "app.claudevoice.STOP_SPEAKING"
        private const val CHANNEL = "listen"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) =
            context.startForegroundService(Intent(context, ListenService::class.java))

        fun stop(context: Context) {
            context.startService(Intent(context, ListenService::class.java).setAction(ACTION_STOP))
        }

        fun stopSpeaking(context: Context) {
            context.startService(Intent(context, ListenService::class.java).setAction(ACTION_STOP_SPEAKING))
        }
    }
}
