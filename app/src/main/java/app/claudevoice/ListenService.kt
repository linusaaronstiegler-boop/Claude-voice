package app.claudevoice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.IBinder
import android.os.PowerManager
import app.claudevoice.core.AnthropicClaude
import app.claudevoice.core.Brain
import app.claudevoice.core.ElevenTts
import app.claudevoice.core.MicMode
import app.claudevoice.core.Pipeline
import app.claudevoice.core.Profile
import app.claudevoice.core.Reach
import app.claudevoice.core.ScribeClient
import app.claudevoice.core.SpokenPart
import app.claudevoice.core.Transcriber
import app.claudevoice.core.UiState
import app.claudevoice.core.VoiceProfile
import app.claudevoice.core.WakeWordDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Foreground-Service: Android beendet Hintergrund-Apps mit Mikrofonzugriff, sobald der
 * Bildschirm aus ist. Ein Foreground-Service mit sichtbarer Benachrichtigung darf weiterlaufen.
 *
 * Hier wird nur verdrahtet: Mikrofon -> Transkription -> Pipeline -> Sprachausgabe.
 * Die eigentliche Logik liegt in :core und ist dort getestet.
 *
 * Zwei Betriebsarten:
 *  - Dauer-Zuhören: Das Mikrofon geht durchgehend zur Transkription; Fragen im Gespräch werden beantwortet.
 *  - Bereitschaft: Das Audio bleibt auf dem Gerät. Ein lokaler Erkenner wartet auf das Weckwort
 *    ("Hey Jarvis"); erst dann wird für eine Weile zugehört. Danach geht es zurück in Bereitschaft.
 */
class ListenService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS) // hält WebSocket-Verbindungen offen und bemerkt Funklöcher
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private var mic: MicSource? = null
    private var speaker: AndroidSpeaker? = null
    private var pipeline: Pipeline? = null
    private var wake: WakeWordDetector? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var standbyWatch: Job? = null
    private lateinit var prefs: Prefs
    private var micName = ""
    private var notice: String? = null

    // --- Audio-Weiche -----------------------------------------------------------------------
    // Drei Zustände: Bereitschaft (nur Weckwort), Verbindungsaufbau (Audio wird kurz gesammelt),
    // Zuhören (Audio geht direkt zur Transkription). Die Sperre schützt den Wechsel, weil das
    // Mikrofon und die Netzverbindung auf verschiedenen Threads arbeiten.
    private val gate = Any()
    private var transcriber: Transcriber? = null
    private var live = false
    private val waiting = ArrayDeque<ByteArray>()
    @Volatile private var lastActivityMs = 0L

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
        prefs = Prefs(this)
        val memory = MemoryStore(this)
        AppState.ui.value = UiState(running = true, startedAtMs = System.currentTimeMillis(), status = "Startet …")

        // Hält die CPU wach, wenn der Bildschirm aus ist. Wird in onDestroy wieder freigegeben.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "claudevoice:listen")
            .apply { acquire(4 * 60 * 60 * 1000L) }

        val microphone = MicSource(this, prefs.useBluetoothMic)
        mic = microphone
        try {
            // Zuerst das Mikro öffnen: danach steht fest, ob wirklich über die Kopfhörer aufgenommen wird.
            microphone.start(::onMicAudio)
        } catch (e: Exception) {
            AppState.ui.update { it.copy(error = e.message) }
            stopSelf()
            return
        }
        val micMode = if (microphone.usingBluetooth) MicMode.AIRPODS else MicMode.HANDY
        micName = if (microphone.usingBluetooth) "Kopfhörer-Mikro" else "Handy-Mikro"
        val problems = mutableListOf<String>()
        if (prefs.useBluetoothMic && !microphone.usingBluetooth) problems += "Kein Kopfhörer-Mikro gefunden, nutze das Handy-Mikro."

        // Gedächtnis: "Über mich" + verknüpfte Dateien (frisch gelesen) + gemerkte Notizen.
        val linked = memory.loadLinked()
        problems += linked.problems
        val names = (listOf(prefs.assistantName) + prefs.aliases).distinct()
        val profile = Profile(
            name = prefs.assistantName,
            aliases = prefs.aliases,
            address = prefs.address,
            character = prefs.character,
            about = listOf(prefs.about, linked.text).filter { it.isNotBlank() }.joinToString("\n\n"),
            notes = memory.notes(),
        )

        val out = AndroidSpeaker(ElevenTts(prefs.elevenKey, http, prefs.voiceId), microphone.usingBluetooth)
        speaker = out
        val pipe = Pipeline(
            Brain(AnthropicClaude(prefs.anthropicKey), profile),
            out, AppState.ui, scope, Dispatchers.IO,
            Pipeline.Config(
                names = names,
                mic = micMode,
                reach = when (prefs.reach) { "ich" -> Reach.ICH; "raum" -> Reach.RAUM; else -> Reach.GESPRAECH },
                echoRisk = !headphonesConnected(),
                voiceProfile = loadVoiceProfile(micMode),
            ),
            object : Pipeline.Env {
                private val format = DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy, HH:mm", Locale.GERMAN)
                private val audio = getSystemService(AudioManager::class.java)
                override fun localTime(): String = LocalDateTime.now().format(format)
                override fun musicPlaying(): Boolean = audio.isMusicActive
                override fun saveNote(text: String) = memory.addNote(text)
                override fun saveVoiceProfile(profile: VoiceProfile) =
                    prefs.saveVoiceProfile(micMode.name, "${profile.levelDb};${profile.pitchHz ?: ""};${profile.samples}")
            },
        )
        pipeline = pipe
        pipe.start()
        notice = problems.joinToString(" ").ifEmpty { null }

        wake = if (prefs.listenMode == "weckwort") WakeWords.create(this) else null
        if (prefs.listenMode == "weckwort" && wake == null) {
            notice = listOfNotNull(notice, "Weckwort-Erkennung nicht verfügbar, höre durchgehend zu.").joinToString(" ")
        }
        if (wake == null) openStream() else standby()
    }

    // --- Mikrofon-Audio ---------------------------------------------------------------------

    private fun onMicAudio(buffer: ByteArray, length: Int) {
        var woke = false
        synchronized(gate) {
            val t = transcriber
            when {
                t != null && live -> { pipeline?.onAudio(buffer, length); t.sendAudio(buffer, length) }
                // Verbindung im Aufbau oder Bereitschaft: die letzten Sekunden aufheben, damit kein Wort
                // verloren geht ("Hey Jarvis, wie hoch ist ..." wird in einem Atemzug gesprochen).
                else -> {
                    waiting.addLast(buffer.copyOf(length))
                    while (waiting.size > PRE_ROLL_CHUNKS) waiting.removeFirst()
                    if (t == null) woke = wake?.feed(buffer, length) == true
                }
            }
        }
        if (woke) onWake()
    }

    private fun onWake() {
        wake?.reset()
        // Kurzer Ton als Bestätigung, wie man es vom Sprachassistenten kennt.
        runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 55).startTone(ToneGenerator.TONE_PROP_BEEP, 120) }
        lastActivityMs = System.currentTimeMillis()
        // Die Pipeline merkt sich das Weckwort: die folgende Äußerung gilt als Anrede, auch wenn die
        // Transkription "Hey Jarvis" verhört.
        pipeline?.onWakeWord()
        openStream()
    }

    /** Verbindung zur Transkription aufbauen; gesammeltes Audio wird nachgereicht, sobald sie steht. */
    private fun openStream() {
        val pipe = pipeline ?: return
        AppState.ui.update { it.copy(status = "Verbinde …") }
        val client = ScribeClient(prefs.elevenKey, http, object : Transcriber.Listener {
            override fun onPartial(text: String, turnProbability: Double?) = pipe.onPartial(text, turnProbability)

            override fun onCommitted(text: String, speaker: String?, segment: Int?) {
                lastActivityMs = System.currentTimeMillis()
                pipe.onCommitted(text, speaker, segment)
            }

            override fun onDiarized(segment: Int, parts: List<SpokenPart>) = pipe.onDiarized(segment, parts)
            override fun onSpeakersMerged(from: String, to: String) = pipe.onSpeakersMerged(from, to)

            override fun onConnection(connected: Boolean, error: String?, fresh: Boolean) {
                if (connected && fresh) pipe.onTranscriberReset()
                synchronized(gate) {
                    val t = transcriber
                    if (connected && t != null) {
                        for (chunk in waiting) { pipe.onAudio(chunk, chunk.size); t.sendAudio(chunk, chunk.size) }
                        waiting.clear()
                        live = true
                    } else {
                        live = false
                    }
                }
                AppState.ui.update {
                    it.copy(
                        status = if (connected) "Hört zu ($micName)" else "Verbindung unterbrochen, neuer Versuch …",
                        error = error ?: if (connected) notice else it.error,
                    )
                }
            }
        })
        synchronized(gate) { transcriber = client }
        client.connect()
        if (wake != null) watchForStandby()
    }

    /** Zurück in die Bereitschaft: Verbindung schließen, nur noch lokal auf das Weckwort lauschen. */
    private fun standby() {
        standbyWatch?.cancel()
        val old = synchronized(gate) {
            val t = transcriber
            transcriber = null
            live = false
            waiting.clear()
            t
        }
        old?.close()
        wake?.reset()
        val call = prefs.aliases.firstOrNull() ?: prefs.assistantName
        AppState.ui.update { it.copy(status = "Bereit, sag „Hey $call“", partial = "", error = notice) }
    }

    private fun watchForStandby() {
        standbyWatch?.cancel()
        standbyWatch = scope.launch {
            while (true) {
                delay(1_000)
                // Solange gesprochen wird, bleibt das Fenster offen; danach zählt die Stille.
                if (AppState.ui.value.speaking) lastActivityMs = System.currentTimeMillis()
                if (System.currentTimeMillis() - lastActivityMs > ACTIVE_WINDOW_MS) {
                    standby()
                    return@launch
                }
            }
        }
    }

    // --- Hilfen -----------------------------------------------------------------------------

    private fun headphonesConnected(): Boolean {
        val types = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
        )
        return getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in types }
    }

    private fun loadVoiceProfile(mic: MicMode): VoiceProfile? {
        val parts = prefs.voiceProfile(mic.name)?.split(';') ?: return null
        val level = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
        return VoiceProfile(level, parts.getOrNull(1)?.toDoubleOrNull(), parts.getOrNull(2)?.toIntOrNull() ?: 1)
    }

    override fun onDestroy() {
        standbyWatch?.cancel()
        mic?.stop()
        synchronized(gate) { transcriber.also { transcriber = null; live = false } }?.close()
        pipeline?.stop()
        speaker?.release()
        wake?.close()
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
            .setContentTitle("Claude Voice ist aktiv")
            .setContentText("Tippen zum Öffnen.")
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
        private const val PRE_ROLL_CHUNKS = 25      // 2,5 s bei 100-ms-Stücken
        private const val ACTIVE_WINDOW_MS = 25_000L // so lange bleibt der Assistent nach dem Weckwort wach

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
