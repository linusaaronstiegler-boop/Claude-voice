package app.claudevoice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import app.claudevoice.core.AccountHintSynth
import app.claudevoice.core.AnthropicClaude
import app.claudevoice.core.AudioGate
import app.claudevoice.core.Brain
import app.claudevoice.core.ConnectionReport
import app.claudevoice.core.CurrentOnlyListener
import app.claudevoice.core.DeviceVoiceEngine
import app.claudevoice.core.DeviceVoiceReport
import app.claudevoice.core.DeviceVoiceSynth
import app.claudevoice.core.DeviceVoiceUnavailable
import app.claudevoice.core.ElevenTts
import app.claudevoice.core.MicMode
import app.claudevoice.core.OutputConfirmation
import app.claudevoice.core.Pipeline
import app.claudevoice.core.Profile
import app.claudevoice.core.Reach
import app.claudevoice.core.RouteVerdict
import app.claudevoice.core.ScribeClient
import app.claudevoice.core.SonioxClient
import app.claudevoice.core.SpeechOutput
import app.claudevoice.core.SpeechSynth
import app.claudevoice.core.SpokenPart
import app.claudevoice.core.StartFailure
import app.claudevoice.core.StartKeys
import app.claudevoice.core.Transcriber
import app.claudevoice.core.UiState
import app.claudevoice.core.VoiceProfile
import app.claudevoice.core.WakeWordDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
 *
 * Wer darf was anfassen? Alles, was den Dienst auf- und abbaut (Start, Weckwort, Bereitschaft, Ende),
 * läuft auf dem Haupt-Thread. So kann nie ein Aufbau mitten in ein Abbauen geraten. Langsames
 * (Einstellungen entschlüsseln, Modelle laden, Dateien lesen) wird in den Hintergrund ausgelagert und
 * kehrt mit dem Ergebnis auf den Haupt-Thread zurück. Nur das Mikrofon und die Netzverbindung arbeiten
 * auf eigenen Threads; sie treffen sich in der [AudioGate], die dafür gesperrt ist.
 *
 * Grundsatz "im Zweifel zu" (fail closed): Lässt sich etwas nicht herstellen oder nicht prüfen
 * (Kopfhörer, Weckwort-Erkenner, Mikrofon, Schlüssel, Stimme des Geräts), wird weder gesprochen noch
 * übertragen. Der Dienst endet dann mit einer Meldung, die den Grund nennt ([fail]).
 *
 * Zwei Sprachausgaben stehen zur Wahl (Einstellungen): ElevenLabs oder die Stimme des Geräts. Gewechselt
 * wird nur dort. Fällt ElevenLabs im Betrieb aus (Guthaben leer, Schlüssel abgelehnt), springt NICHT still
 * die andere Stimme ein: Die Meldung nennt den Grund und den Ausweg, entscheiden soll der Nutzer.
 */
class ListenService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Für Tests: true, solange noch irgendeine Coroutine dieses Dienstes läuft. Das kann auch nach dem Ende
     * kurz so sein: Ein Hintergrund-Schritt, der gerade rechnet oder auf das Netz wartet, lässt sich nicht
     * mittendrin anhalten. Er läuft zu Ende, und erst dann wird sein Ergebnis verworfen (siehe [shutDown]).
     * Tests warten auf "false", statt zu schätzen, wie lange so etwas dauert.
     */
    internal val busy: Boolean get() = scope.coroutineContext[Job]?.children?.any() == true

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS) // hält WebSocket-Verbindungen offen und bemerkt Funklöcher
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    // Eigenes Merkmal für "Start läuft oder ist durch". Früher diente dafür "pipeline == null"; die Pipeline
    // entsteht aber erst am Ende des Aufbaus, ein zweiter Start-Befehl dazwischen hätte doppelt aufgebaut.
    private var started = false

    /**
     * true, sobald feststeht, dass der Dienst endet (Stopp-Befehl, Fehler oder onDestroy). Ab dann wird nichts
     * mehr geöffnet und nichts mehr gemeldet. Gesetzt wird es nur auf dem Haupt-Thread, in [shutDown];
     * gelesen auch von anderen Threads, deshalb "volatile".
     */
    @Volatile private var stopping = false

    /** Der Grund, mit dem [fail] das Zuhören beendet hat; null bei einem gewöhnlichen Stopp. */
    private var stopReason: String? = null

    /**
     * Was in der Meldungszeile stand, als der Dienst sein Ende beschlossen hat ([shutDown]). Bei einem
     * gewöhnlichen Stopp ist das die Meldung, die nach dem Ende stehen bleibt (siehe onDestroy).
     */
    private var noticeAtEnd: String? = null
    private var mic: MicSource? = null
    private var speaker: AndroidSpeaker? = null
    @Volatile private var pipeline: Pipeline? = null
    private var synth: ElevenTts? = null
    /** Die Sprach-Engine des Handys, falls die Stimme des Geräts spricht. Wird am Ende freigegeben. */
    private var deviceVoice: DeviceVoiceEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var standbyWatch: Job? = null
    private var tone: ToneGenerator? = null
    private var watchingDevices = false
    private var standbyMode = false
    private var micName = ""
    private var callName = ""
    private var keyterms: List<String> = emptyList()
    private var elevenKey = ""
    private var sonioxKey = ""

    /** Hinweise, die während des ganzen Zuhörens gelten (z. B. "nutze das Handy-Mikro"). */
    @Volatile private var startNotice: String? = null
    /** Letzte Störungsmeldung der Sprachausgabe, mit Uhrzeit (siehe [wire]). */
    @Volatile private var speechNotice: String? = null

    /** Die Weiche zwischen Mikrofon und Transkription (Bereitschaft, Verbindungsaufbau, Zuhören). */
    @Volatile private var gate: AudioGate? = null
    @Volatile private var lastActivityMs = 0L
    @Volatile private var wokeAtMs = 0L

    /**
     * Antworten sind nur für den Träger bestimmt und können Persönliches aus dem Gedächtnis enthalten.
     * Ändert sich, was am Handy hängt (Kopfhörer ins Etui, das Auto verbindet sich), wird deshalb sofort
     * geprüft, wohin eine Antwort jetzt ginge. Auch ein NEUES Gerät zählt: Verbindet sich das Auto, macht
     * Android es zum Ausgabegerät, obwohl die Kopfhörer vielleicht noch in der Liste stehen.
     */
    private val deviceWatch = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = checkRoute()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = checkRoute()
    }

    /**
     * Android kündigt mit dieser Nachricht an, dass der Ton gleich aus dem Lautsprecher käme (Kopfhörer
     * herausgezogen oder getrennt). Sie kommt, BEVOR umgeschaltet wird: Die laufende Antwort verstummt
     * darum sofort, und kurz danach wird nachgesehen, wohin der Ton nun wirklich ginge.
     */
    private val noisyWatch = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            pipeline?.stopSpeaking()
            main.removeCallbacks(recheckRoute)
            main.postDelayed(recheckRoute, RECHECK_ROUTE_MS)
        }
    }
    private val recheckRoute = Runnable { checkRoute() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // Erst alles schließen, dann Android um das Ende bitten: onDestroy kommt erst einen Moment
                // später, und in diesem Moment soll schon nichts mehr laufen (siehe shutDown).
                shutDown()
                stopSelf()
            }
            ACTION_STOP_SPEAKING -> pipeline?.stopSpeaking()
            else -> if (!started) {
                started = true
                begin()
            }
        }
        return START_NOT_STICKY
    }

    // --- Start ------------------------------------------------------------------------------

    /**
     * Sofort erledigt wird nur, was Android sofort verlangt (die Benachrichtigung) und was nichts kostet.
     * Der eigentliche Aufbau läuft in [setUp] als Coroutine. Was auch immer dabei schiefgeht, landet hier:
     * Der Dienst endet mit einer Meldung, statt die ganze App abstürzen zu lassen.
     */
    private fun begin() {
        try {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            AppState.ui.value = UiState(running = true, startedAtMs = System.currentTimeMillis(), status = "Startet …")
            // Hält die CPU wach, wenn der Bildschirm aus ist. Wird am Ende wieder freigegeben (siehe shutDown).
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "claudevoice:listen")
                .apply { acquire(4 * 60 * 60 * 1000L) }
        } catch (e: Exception) {
            fail(StartFailure.describe(e))
            return
        }
        scope.launch(Dispatchers.Main.immediate) {
            try {
                setUp()
            } catch (e: CancellationException) {
                throw e // der Dienst wurde währenddessen beendet: nichts zu melden
            } catch (e: Throwable) {
                // Throwable statt Exception: Auch eine fehlende Maschinenbibliothek (ein "Error") soll als
                // Meldung enden. Gezeigt wird nur die Art des Fehlers, nie sein Text (siehe StartFailure).
                fail(StartFailure.describe(e))
            }
        }
    }

    /** Die Einstellungen, einmal im Hintergrund gelesen (das Entschlüsseln gehört nicht auf den Haupt-Thread). */
    private class Settings(val prefs: Prefs) {
        val standby = prefs.listenMode == "weckwort"
        val headsetMic = prefs.useBluetoothMic
        val pauseMusic = prefs.pauseMusic
        val reach = when (prefs.reach) { "ich" -> Reach.ICH; "raum" -> Reach.RAUM; else -> Reach.GESPRAECH }
        val name = prefs.assistantName
        val aliases = prefs.aliases
        val address = prefs.address
        val character = prefs.character
        val about = prefs.about
        val anthropicKey = prefs.anthropicKey
        val elevenKey = prefs.elevenKey
        val sonioxKey = prefs.sonioxKey
        val voiceId = prefs.voiceId
        /** Womit gesprochen wird: die Wahl des Nutzers, sonst nach dem ElevenLabs-Schlüssel (siehe SpeechOutput). */
        val speech = SpeechOutput.resolve(prefs.speechChoice, elevenKey.isNotEmpty())
        val names = (listOf(name) + aliases).distinct()
    }

    /** Was der Aufbau im Hintergrund vorbereitet: Gedächtnis gelesen, Claude-Client und Anweisungen stehen. */
    private class Prepared(val memory: MemoryStore, val problems: List<String>, val brain: Brain)

    /**
     * Der Aufbau, Schritt für Schritt. Läuft auf dem Haupt-Thread und wechselt für Langsames mit
     * `withContext(Dispatchers.IO)` in den Hintergrund. Endet der Dienst währenddessen, bricht die Coroutine
     * an der nächsten solchen Stelle ab; danach wird nichts mehr geöffnet.
     */
    private suspend fun setUp() {
        val s = withContext(Dispatchers.IO) { Settings(Prefs(applicationContext)) }
        val route = OutputRoute(this) { s.prefs.confirmedOutputs }

        // Zuallererst die Schlüssel. Welche nötig sind, hängt von der Sprachausgabe ab: Mit der Stimme des
        // Geräts genügt neben dem Anthropic-Schlüssel einer für die Transkription (ElevenLabs ODER Soniox).
        // Die Oberfläche fragt dieselbe Regel und lässt den Start sonst gar nicht zu; hier steht sie noch
        // einmal, damit der Dienst nie mit einem leeren Schlüssel ins Netz geht.
        StartKeys.problem(s.anthropicKey, s.elevenKey, s.sonioxKey, s.speech)?.let { missing ->
            fail(missing)
            return
        }

        // Drei Dinge müssen vor dem ersten Ton feststehen, sonst startet gar nichts.
        // Erstens: Antworten gehen in Kopfhörer. Geprüft wird das, bevor das Mikrofon überhaupt aufgeht.
        if (!headphonesReady(route, s)) return

        // Zweitens (nur Bereitschaft): Der Weckwort-Erkenner ist da. Bereitschaft verspricht, dass bis zum
        // Weckwort nichts das Gerät verlässt. Ohne Erkenner lässt sich das nicht halten, also lieber gar
        // nicht zuhören als stillschweigend alles übertragen.
        var detector: WakeWordDetector? = null
        val ready = try {
            withContext(Dispatchers.IO) {
                if (s.standby) detector = WakeWords.create(applicationContext)
                if (s.standby && detector == null) return@withContext null
                // Gedächtnis: "Über mich" + verknüpfte Dateien (frisch gelesen, bei Drive über das Netz) + Notizen.
                val memory = MemoryStore(applicationContext, s.prefs)
                val linked = memory.loadLinked()
                val profile = Profile(
                    name = s.name,
                    aliases = s.aliases,
                    address = s.address,
                    character = s.character,
                    about = listOf(s.about, linked.text).filter { it.isNotBlank() }.joinToString("\n\n"),
                    notes = memory.notes(),
                )
                Prepared(memory, linked.problems, Brain(AnthropicClaude(s.anthropicKey), profile))
            }
        } catch (e: Throwable) {
            // Auch bei einem Abbruch: Der Erkenner hält Speicher außerhalb von Kotlin und muss freigegeben werden.
            detector?.close()
            throw e
        }
        if (ready == null) {
            fail("Weckwort-Erkennung nicht verfügbar. Bitte „Dauer-Zuhören“ wählen oder die App neu installieren.")
            return
        }

        // Drittens (nur mit der Stimme des Geräts): Die Sprach-Engine des Handys ist da, hat eine deutsche
        // Stimme und liefert wirklich Ton. Das zeigt sich jetzt und nicht erst bei der ersten Antwort.
        val voice: DeviceVoiceSynth? = try {
            if (s.speech == SpeechOutput.DEVICE) openDeviceVoice() else null
        } catch (e: DeviceVoiceUnavailable) {
            detector?.close()
            fail(e.message ?: DeviceVoiceReport.NO_ENGINE)
            return
        } catch (e: Throwable) {
            detector?.close()
            throw e
        }
        wire(s, route, ready, detector, voice)
    }

    /**
     * Verbindet sich mit der Sprach-Engine des Handys und lässt sie einen Probesatz erzeugen (nicht hörbar).
     * Aus der Probe stammt die Abtastrate, für die gleich der Abspieler gebaut wird.
     * @throws DeviceVoiceUnavailable mit einer Meldung für den Nutzer, wenn die Stimme des Geräts nicht taugt.
     */
    private suspend fun openDeviceVoice(): DeviceVoiceSynth {
        val engine = AndroidDeviceVoice.open(applicationContext)
        // Ab hier kennt der Dienst die Engine: Endet er (auch mitten in der Probe), gibt shutDown() sie frei.
        deviceVoice = engine
        return withContext(Dispatchers.IO) { DeviceVoiceSynth.start(engine) }
    }

    /**
     * Prüft vor dem Start, ob Antworten jetzt in Kopfhörer gingen. Ein Bluetooth-Gerät, das der Träger noch
     * nicht bestätigt hat, wird erfragt (die Meldung nennt es beim Namen); ein zweiter Start kurz darauf
     * gilt als sein Ja, und das Gerät wird gemerkt.
     * @return false, wenn nicht gestartet werden darf. Die Meldung steht dann schon in der Oberfläche.
     */
    private fun headphonesReady(route: OutputRoute, s: Settings): Boolean {
        val media = AndroidSpeaker.attributes(voiceCallRoute = false)
        fun judge(): RouteVerdict {
            val forMedia = route.verdict(media)
            if (forMedia is RouteVerdict.Ask || !s.headsetMic) return forMedia
            // Auch wenn der Musikweg schon als Kopfhörer gilt: Für das Kopfhörer-Mikro zählt der Telefonkanal,
            // und den führt Android als eigenes Gerät. Auf dem Testgerät galt der Musikweg als privat, der
            // Telefonkanal derselben Ohrhörer aber als "noch zu bestätigen". Früher wurde hier sofort "privat"
            // gemeldet, nie nachgefragt, und das Mikro fiel stillschweigend auf das Handy zurück.
            if (forMedia == RouteVerdict.Private && route.privateHeadset() == null) {
                route.headsetToConfirm()?.let { return it }
                return forMedia
            }
            // Mit Kopfhörer-Mikro läuft auch die Antwort über den Telefonkanal des Headsets; dann zählt dieses
            // Gerät. Ob der Kanal zustande kommt, zeigt sich erst beim Öffnen des Mikrofons; die verbindliche
            // Prüfung folgt deshalb in wire().
            if (route.privateHeadset() != null) return RouteVerdict.Private
            return route.headsetToConfirm() ?: forMedia
        }
        var verdict = judge()
        if (verdict is RouteVerdict.Ask) {
            val key = verdict.device.key
            val now = SystemClock.elapsedRealtime()
            if (confirmation.answers(key, now)) {
                s.prefs.confirmedOutputs = s.prefs.confirmedOutputs + key
                verdict = judge()
            } else {
                confirmation.ask(key, now)
            }
        }
        if (verdict == RouteVerdict.Private) return true
        fail(route.refusal(verdict, starting = true) ?: "Bitte zuerst Kopfhörer verbinden.")
        return false
    }

    /** Alles ist vorbereitet: Mikrofon öffnen, Sprachausgabe und Pipeline verdrahten, loslegen. */
    private suspend fun wire(
        s: Settings,
        route: OutputRoute,
        ready: Prepared,
        detector: WakeWordDetector?,
        deviceSpeech: DeviceVoiceSynth?,
    ) {
        // Ab hier gehört der Erkenner der Weiche; sie gibt ihn frei, wenn der Dienst endet.
        val weiche = AudioGate(detector, measure = { pcm, n -> pipeline?.onAudio(pcm, n) })
        gate = weiche
        standbyMode = s.standby

        val audio = getSystemService(AudioManager::class.java)
        audio.registerAudioDeviceCallback(deviceWatch, main)
        val noisy = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        // Die Nachricht kommt vom System. "Nicht exportiert" heißt: Andere Apps können sie uns nicht unterschieben.
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisyWatch, noisy, RECEIVER_NOT_EXPORTED)
        else registerReceiver(noisyWatch, noisy)
        watchingDevices = true

        val microphone = MicSource(this) { why -> fail(why) }
        mic = microphone
        val headset = if (s.headsetMic) route.privateHeadset() else null
        try {
            // Zuerst das Mikro öffnen: danach steht fest, ob wirklich über die Kopfhörer aufgenommen wird.
            // Mit Kopfhörer-Mikro wartet das bis zu vier Sekunden auf den Telefonkanal ("Startet …" bleibt stehen).
            microphone.start(headset, ::onMicAudio)
        } catch (e: MicSource.Unavailable) {
            fail(e.message ?: "Mikrofon konnte nicht geöffnet werden")
            return
        }
        val micMode = if (microphone.usingBluetooth) MicMode.AIRPODS else MicMode.HANDY
        micName = if (microphone.usingBluetooth) "Kopfhörer-Mikro" else "Handy-Mikro"
        val problems = ready.problems.toMutableList()
        if (s.headsetMic && !microphone.usingBluetooth) {
            problems.add(
                0,
                // Der Zusatz in Klammern sagt, woran es lag; ohne ihn lässt sich das nur am Gerät erraten.
                if (headset == null) "Kein Kopfhörer-Mikro gefunden, nutze das Handy-Mikro. (${route.headsetReport()})"
                else "Das Kopfhörer-Mikro kam nicht zustande, nutze das Handy-Mikro. (${microphone.linkNote})",
            )
        }

        // Lief eine Antwort nicht sauber, war aber (wenigstens langsamer oder teilweise) zu hören, soll man
        // das sehen können: bei ElevenLabs etwa, wenn der schnelle Weg (WebSocket) scheitert, bei der Stimme
        // des Geräts, wenn ein Satz stumm blieb. Die Meldung nennt nur den Grund, nie Antworttext oder Schlüssel.
        val trouble: (String) -> Unit = { reason ->
            val line = "Sprachausgabe um ${LocalTime.now().format(CLOCK)}: $reason"
            speechNotice = line
            // Die Prüfung steht IN der Aktualisierung (wie beim Listener in openStream): Endet der Dienst
            // genau jetzt, bleibt sein Grund stehen und wird nicht von dieser Zeile überschrieben.
            AppState.ui.update { if (stopping) it else it.copy(error = line) }
        }
        // Wer spricht, steht in den Einstellungen und wechselt im Betrieb nicht. Beide erfüllen dieselbe
        // Schnittstelle (SpeechSynth); der Abspieler dahinter, mit Kopfhörer-Prüfung, Audiofokus und Stopp,
        // ist für beide derselbe.
        val tts: SpeechSynth = if (deviceSpeech != null) {
            deviceSpeech.onTrouble = trouble
            deviceSpeech
        } else {
            val eleven = ElevenTts(s.elevenKey, http, s.voiceId)
            synth = eleven
            eleven.onTrouble = trouble
            // Scheitert ElevenLabs am Konto, nennt die Meldung zusätzlich den Ausweg (die Stimme des Geräts).
            AccountHintSynth(eleven)
        }
        val out = AndroidSpeaker(
            this, tts, microphone.usingBluetooth, s.pauseMusic, route,
            onStopRequested = { pipeline?.stopSpeaking() },
            onRouteLost = { why -> main.post { fail(why) } },
            onWithheld = { note -> AppState.ui.update { if (stopping) it else it.copy(lastDecision = note) } },
        )
        speaker = out
        // Verbindliche Prüfung mit genau den Eigenschaften, mit denen gleich gesprochen wird. Kam der
        // Telefonkanal nicht zustande oder hat sich inzwischen etwas geändert, endet der Start hier.
        out.routeProblem(starting = true)?.let { why ->
            fail(why)
            return
        }

        // Vorwärmen, während der Nutzer noch gar nichts gesagt hat: Die Verbindung zur Sprachausgabe steht
        // dann schon (bei der Stimme des Geräts gibt es nichts zu verbinden), und Claude hat Regeln und
        // Gedächtnis bereits zwischengespeichert.
        tts.prepare()
        // runInterruptible: Endet der Dienst, während dieser Aufruf noch unterwegs ist, wird sein Thread
        // unterbrochen. Ohne das liefe er unbeirrt weiter, und der Client von Anthropic versuchte es nach einem
        // Fehlschlag sogar noch einmal: Regeln und Gedächtnis gingen dann ein zweites Mal hinaus, obwohl schon
        // niemand mehr zuhört. Eine Anfrage, die bereits abgeschickt ist, holt die Unterbrechung nicht zurück;
        // sie verhindert aber jede weitere.
        scope.launch(Dispatchers.IO) { runCatching { runInterruptible { ready.brain.warmUp() } } }
        keyterms = s.names
        callName = s.aliases.firstOrNull() ?: s.name
        elevenKey = s.elevenKey
        sonioxKey = s.sonioxKey
        val pipe = Pipeline(
            ready.brain,
            out, AppState.ui, scope, Dispatchers.IO,
            Pipeline.Config(
                names = s.names,
                mic = micMode,
                reach = s.reach,
                echoRisk = false, // Kopfhörer sind Pflicht (vor jeder Antwort geprüft), die Ausgabe schallt nicht ins Mikro zurück
                voiceProfile = s.prefs.storedVoiceProfile(micMode),
            ),
            object : Pipeline.Env {
                private val format = DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy, HH:mm", Locale.GERMAN)
                override fun localTime(): String = LocalDateTime.now().format(format)
                override fun musicPlaying(): Boolean = audio.isMusicActive
                override fun saveNote(text: String) = ready.memory.addNote(text)
                // Nach dem Ende wird nichts mehr geschrieben ("ended"). Die Einstellungen lassen das Profil nur
                // vergessen, solange nicht zugehört wird; ein Nachzügler dieser Sitzung darf es dann nicht
                // wieder hinlegen.
                override fun saveVoiceProfile(profile: VoiceProfile) {
                    s.prefs.storeVoiceProfile(micMode, profile, ended = stopping)
                }
            },
        )
        pipeline = pipe
        pipe.start()
        startNotice = problems.joinToString(" ").ifEmpty { null }

        if (standbyMode) standby() else openStream()
    }

    // --- Mikrofon-Audio ---------------------------------------------------------------------

    /** Läuft auf dem Mikrofon-Thread. Die Weiche entscheidet, was mit dem Stück geschieht. */
    private fun onMicAudio(buffer: ByteArray, length: Int) {
        if (gate?.onAudio(buffer, length) == true) main.post {
            // Auch hier gilt: Ein unerwarteter Fehler endet als Meldung, nicht als Absturz der App.
            try {
                onWake()
            } catch (e: Exception) {
                fail(StartFailure.describe(e, what = "Aufwachen"))
            }
        }
    }

    private fun onWake() {
        val pipe = pipeline
        val out = speaker
        if (stopping || pipe == null || out == null) return
        // Vor dem ersten übertragenen Wort: Gingen Antworten noch in Kopfhörer? Wenn nicht, wird gar nicht
        // erst übertragen (seit dem Start kann jemand die Ausgabe umgestellt haben).
        out.routeProblem()?.let { why ->
            fail(why)
            return
        }
        // Kurzer Ton als Bestätigung, wie man es vom Sprachassistenten kennt. Der Tongeber wird einmal
        // angelegt und am Ende freigegeben (jeder neue belegt einen der knappen Abspieler des Systems).
        runCatching {
            (tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, 55).also { tone = it })
                .startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        }
        // Die Fenster der Bereitschaft zählen auf der Uhr, die seit dem Einschalten des Handys läuft. Die
        // Wanduhr kann vom Netz verstellt werden; spränge sie zurück, bliebe die Übertragung entsprechend
        // länger offen, und genau das darf in der Bereitschaft nie still passieren.
        lastActivityMs = SystemClock.elapsedRealtime()
        wokeAtMs = lastActivityMs
        // Die Pipeline merkt sich das Weckwort: die folgende Äußerung gilt als Anrede, auch wenn die
        // Transkription "Hey Jarvis" verhört. Sie wärmt dabei auch die Verbindungen auf, die für die
        // Antwort gebraucht werden (Sprachausgabe immer, Claude nur nach längerer Ruhe). Zwischen Weckwort
        // und Ende der Frage vergehen ein, zwei Sekunden; so kostet das die Antwort keine Zeit.
        pipe.onWakeWord()
        openStream()
    }

    /** Verbindung zur Transkription aufbauen; gesammeltes Audio wird nachgereicht, sobald sie steht. */
    private fun openStream() {
        if (stopping) return
        val pipe = pipeline ?: return
        val weiche = gate ?: return
        val micLabel = micName
        // Der Listener muss seine eigene Verbindung kennen, um veraltete Rückrufe auszusortieren. Die
        // Verbindung entsteht aber erst nach ihm; dieses Fach wird gefüllt, bevor sie sich verbindet.
        val mine = AtomicReference<Transcriber>()
        AppState.ui.update { it.copy(status = "Verbinde …") }
        val listener = object : Transcriber.Listener {
            override fun onPartial(text: String, turnProbability: Double?, speaker: String?) =
                pipe.onPartial(text, turnProbability, speaker)

            override fun onCommitted(text: String, speaker: String?, segment: Int?) {
                // Nur die Bitte direkt nach dem Weckwort hält das Fenster offen. Dass danach irgendwer im
                // Raum weiterredet, darf die Übertragung nicht endlos verlängern.
                val now = SystemClock.elapsedRealtime()
                if (now - wokeAtMs < ACTIVE_WINDOW_MS) lastActivityMs = now
                pipe.onCommitted(text, speaker, segment)
            }

            override fun onDiarized(segment: Int, parts: List<SpokenPart>) = pipe.onDiarized(segment, parts)
            override fun onSpeakersMerged(from: String, to: String) = pipe.onSpeakersMerged(from, to)

            override fun onConnection(connected: Boolean, error: String?, fresh: Boolean) {
                val t = mine.get() ?: return
                if (connected && fresh) pipe.onTranscriberReset()
                val current = if (connected) weiche.connected(t) else weiche.disconnected(t)
                if (!current) return
                if (!connected && ConnectionReport.isHopeless(error)) {
                    // Abgelehnter Schlüssel, leeres Guthaben: Der nächste Versuch scheitert genauso. Statt
                    // "neuer Versuch …" zu versprechen und mit offenem Mikrofon ins Leere zu laufen, endet
                    // das Zuhören mit dem wahren Grund.
                    main.post { fail(ConnectionReport.stopMessage(error.orEmpty())) }
                    return
                }
                AppState.ui.update {
                    // Die Prüfung steht absichtlich IN der Aktualisierung: Geht der Dienst genau jetzt in die
                    // Bereitschaft oder endet er, darf dieser Rückruf die Anzeige nicht mehr überschreiben.
                    if (stopping || !weiche.isCurrent(t)) it
                    else it.copy(
                        status = ConnectionReport.status(connected, error, micLabel),
                        error = error ?: if (connected) notice() else it.error,
                    )
                }
            }
        }
        // Rückrufe einer Verbindung, die nicht mehr die aktuelle ist (nach der Rückkehr in die Bereitschaft
        // geschlossen), werden verworfen, bevor sie den Listener oben erreichen.
        //
        // Dasselbe gilt ab dem Moment, in dem der Dienst sein Ende beschlossen hat ("stopping"). Die Verbindung
        // selbst wird erst etwas später geschlossen: shutDown() wartet vorher auf den Mikrofon-Thread, bis zu
        // einer halben Sekunde. Ohne diese Prüfung konnte die Transkription in dieser Zeit noch eine Meldung in
        // die Anzeige setzen oder der Pipeline eine Äußerung nachreichen, aus der ein neuer Aufruf an Claude wurde.
        val current = CurrentOnlyListener(listener) { !stopping && mine.get()?.let(weiche::isCurrent) == true }
        // Zwei Anbieter stehen zur Wahl. Soniox (eigener Schlüssel) liefert Sprecher-Kürzel je Wort;
        // ohne Soniox-Schlüssel läuft die Transkription über ElevenLabs. Dass dessen Schlüssel dann da ist,
        // hat der Start geprüft (StartKeys).
        val client: Transcriber =
            if (sonioxKey.isNotEmpty()) SonioxClient(sonioxKey, http, current, keyterms = keyterms)
            else ScribeClient(elevenKey, http, current, keyterms = keyterms)
        mine.set(client)
        if (!weiche.open(client)) {
            client.close()
            return
        }
        client.connect()
        if (standbyMode) watchForStandby()
    }

    /** Zurück in die Bereitschaft: Verbindung schließen, nur noch lokal auf das Weckwort lauschen. */
    private fun standby() {
        if (stopping) return
        standbyWatch?.cancel()
        gate?.rest()?.close()
        AppState.ui.update { it.copy(status = "Bereit, sag „Hey $callName“", partial = "", error = notice()) }
    }

    private fun watchForStandby() {
        standbyWatch?.cancel()
        standbyWatch = scope.launch(Dispatchers.Main) {
            while (true) {
                delay(1_000)
                // Solange der Assistent spricht, bleibt das Fenster offen (für Nachfragen); danach zählt die
                // Zeit. Spätestens nach MAX_AWAKE_MS ist Schluss, bis das Weckwort erneut fällt.
                val now = SystemClock.elapsedRealtime()
                val speaking = AppState.ui.value.speaking
                if (speaking) lastActivityMs = now
                if (!speaking && (now - lastActivityMs > ACTIVE_WINDOW_MS || now - wokeAtMs > MAX_AWAKE_MS)) {
                    standby()
                    return@launch
                }
            }
        }
    }

    // --- Hilfen -----------------------------------------------------------------------------

    /** Dauerhafte Hinweise dieser Sitzung in einer Zeile; null, wenn es keine gibt. */
    private fun notice(): String? = listOfNotNull(startNotice, speechNotice).joinToString(" ").ifEmpty { null }

    /**
     * Ginge eine Antwort jetzt noch in Kopfhörer? Wenn nicht, verstummt sie sofort, und die Sprachausgabe
     * meldet über `onRouteLost`, dass das Zuhören enden soll (mit dem Kopfhörer-Mikro erst nach einer
     * Schonfrist für den Telefonkanal, siehe [AndroidSpeaker.checkRoute]).
     */
    private fun checkRoute() {
        // Solange der Aufbau läuft, gibt es noch keine Sprachausgabe; der Start prüft dann selbst.
        val out = speaker ?: return
        if (stopping) return
        out.checkRoute()
    }

    /**
     * Im Zweifel zu: alles schließen, den Grund zeigen, Dienst beenden. Gehört auf den Haupt-Thread;
     * andere Threads reichen ihre Meldung mit `main.post { fail(...) }` herüber.
     *
     * Der erste Grund gilt. Erst wird geschlossen, dann gemeldet: Was in diesem Augenblick noch unterwegs
     * ist (ein Rückruf der Transkription, eine Störung der Sprachausgabe), kann die Meldung so nicht mehr
     * überschreiben.
     */
    private fun fail(message: String) {
        if (stopping) return
        shutDown()
        stopReason = message
        AppState.ui.update { it.copy(error = message) }
        stopSelf()
    }

    /**
     * Schließt alles, sofort und genau einmal: Antwort, Mikrofon, Verbindungen, Wachhalten.
     *
     * Warum nicht einfach in onDestroy? `stopSelf()` ist nur eine Bitte an Android; onDestroy kommt erst
     * mit einer späteren Nachricht auf dem Haupt-Thread. In der Zwischenzeit lief früher alles weiter: Ein
     * Aufbau, der genau dann fertig wurde, öffnete noch das Mikrofon, und die Transkription überschrieb den
     * Grund des Endes mit ihrer eigenen Meldung. Darum schließt schon die Entscheidung selbst alles
     * ([fail], Stopp-Befehl); onDestroy ruft diese Funktion noch einmal, dann ohne Wirkung.
     */
    private fun shutDown() {
        if (stopping) return
        stopping = true
        noticeAtEnd = AppState.ui.value.error
        main.removeCallbacksAndMessages(null)
        standbyWatch?.cancel()
        // Jeder Schritt wird für sich versucht (runCatching): Scheitert einer, dürfen die folgenden nicht
        // ausfallen. Ein Mikrofon, das offen bleibt, weil davor etwas anderes klemmte, wäre der schlimmste Fall.
        //
        // Zuerst verstummen. Das Mikrofon gibt weiter unten den Telefonkanal frei; liefe die Antwort dann
        // noch, suchte Android ihr einen anderen Weg.
        runCatching { pipeline?.stopSpeaking() }
        if (watchingDevices) {
            watchingDevices = false
            runCatching { getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(deviceWatch) }
            runCatching { unregisterReceiver(noisyWatch) }
        }
        runCatching { mic?.stop() }
        // Schließt auch den Weckwort-Erkenner, und zwar unter derselben Sperre, unter der das Mikrofon ihn füttert.
        runCatching { gate?.shutdown()?.close() }
        runCatching { pipeline?.stop() }
        runCatching { speaker?.release() }
        runCatching { synth?.close() }
        runCatching { deviceVoice?.close() }
        runCatching { tone?.release() }
        // Bricht auch einen Aufbau ab, der noch im Hintergrund arbeitet: Er kehrt nicht mehr zurück.
        scope.cancel()
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
    }

    override fun onDestroy() {
        shutDown()
        // Die Meldungszeile kommt hier auf den Stand zurück, den sie beim Ende hatte: den Grund aus fail() oder,
        // bei einem gewöhnlichen Stopp, das, was in dem Moment dastand. Pipeline und Sprachausgabe schreiben von
        // eigenen Threads aus in dieselbe Anzeige. Ein Schritt, der beim Ende gerade lief, kann die Zeile noch
        // einen Augenblick danach geleert haben (die Pipeline löscht alte Fehler, wenn sie antwortet) oder eine
        // eigene Meldung hineingesetzt haben ("Antwort fehlgeschlagen", weil ihm die Verbindung unter den
        // Händen geschlossen wurde). Beides gehört nicht mehr zu dem, was der Nutzer nach dem Ende lesen soll.
        AppState.ui.update {
            it.copy(running = false, speaking = false, partial = "", status = "Bereit", error = stopReason ?: noticeAtEnd)
        }
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
        private const val ACTIVE_WINDOW_MS = 20_000L // so lange bleibt der Assistent nach Weckwort oder Antwort wach
        private const val MAX_AWAKE_MS = 120_000L    // Obergrenze je Weckwort, danach zurück in die Bereitschaft
        private const val RECHECK_ROUTE_MS = 1_000L  // so lange braucht Android höchstens, um den Ton umzuleiten
        private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

        /**
         * Merkt sich über einen abgelehnten Start hinweg, nach welchem Bluetooth-Gerät gefragt wurde (der Dienst
         * selbst ist dann schon beendet). Lebt nur im Arbeitsspeicher; nach einem Neustart der App wird neu gefragt.
         */
        internal val confirmation = OutputConfirmation()

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
