package app.claudevoice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import app.claudevoice.core.Speaker
import app.claudevoice.core.SpeechSynth
import app.claudevoice.core.synthesizeGated
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Spielt die Sprachausgabe ab, während sie noch vom Server eintrifft.
 * Ein AudioTrack im Streaming-Modus ist wie ein Wasserhahn: wir schütten PCM-Bytes hinein,
 * Android spielt sie in der Reihenfolge ab.
 *
 * Rücksicht auf Musik: Android regelt mit dem "Audiofokus", wer gerade zu hören sein soll.
 * Sobald der erste Ton einer Antwort da ist, bitten wir kurz um den Fokus; die Musik-App pausiert
 * dann (oder wird leiser) und macht nach der Antwort von selbst weiter. Kommt gar keine Antwort,
 * wird die Musik nie angefasst.
 *
 * Kopfhörer-Taste: Nur während eine Antwort läuft, meldet sich die App als "Abspieler" an. Ein Druck
 * auf die Taste stoppt dann die Antwort. Davor und danach gehört die Taste wieder der Musik-App.
 * (Gilt für das Handy-Mikro. Im Telefonie-Modus des Kopfhörer-Mikros behandelt Android den Tastendruck
 * als "Auflegen"; er kommt hier nicht an.)
 *
 * Nur ins Ohr: Antworten können Persönliches enthalten. Vor dem ersten Ton JEDER Antwort wird deshalb
 * gefragt, wohin Android den Ton gerade leiten würde ([OutputRoute]). Ist das kein Kopfhörer, bleibt die
 * Antwort stumm, und [onRouteLost] meldet dem Dienst den Grund. Dass beim Start des Dienstes Kopfhörer
 * verbunden waren, genügt nicht: Inzwischen kann sich das Auto verbunden oder der Nutzer die Ausgabe
 * auf "Dieses Handy" umgestellt haben. Wechselt der Weg mitten in einer Antwort, bricht sie sofort ab.
 *
 * Zwei Fälle, in denen nur die ANTWORT endet und nicht gleich das ganze Zuhören:
 *  - Ein Wecker oder Klingelton läuft. Android spielt ihn über Lautsprecher und Kopfhörer zugleich; hängen
 *    die Kopfhörer am Kabel, wäre auch die Antwort laut zu hören. Die Kopfhörer sind aber noch da.
 *  - Kopfhörer-Mikro ([voiceCallRoute]): Der Telefonkanal zum Headset reißt kurz ab, etwa durch einen
 *    Druck auf die Kopfhörer-Taste, und Android baut ihn meist gleich wieder auf. Verstummt wird sofort,
 *    über das Ende des Zuhörens entscheidet erst eine Schonfrist ([CALL_GRACE_MS]).
 */
class AndroidSpeaker(
    context: Context,
    private val synth: SpeechSynth,
    private val voiceCallRoute: Boolean,
    pauseMusic: Boolean,
    private val route: OutputRoute,
    /** Die laufende Antwort soll enden (auf irgendeinem Thread gerufen). Der Dienst bricht sie dann ab. */
    private val onStopRequested: () -> Unit,
    /** Wird gerufen (auf irgendeinem Thread), wenn Antworten nicht mehr in Kopfhörer gehen: Das Zuhören soll enden. */
    private val onRouteLost: (String) -> Unit,
    /** Eine Antwort wurde verschwiegen, obwohl die Kopfhörer da sind; der Text nennt den Grund (irgendein Thread). */
    private val onWithheld: (String) -> Unit = {},
) : Speaker {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    /** Die Eigenschaften, mit denen Antworten abgespielt werden. Nach ihnen richtet Android den Ausgabeweg. */
    val attributes: AudioAttributes = attributes(voiceCallRoute)

    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(
        // TRANSIENT = "kurz, gleich wieder weg": die Musik pausiert. MAY_DUCK = sie wird nur leiser.
        if (pauseMusic) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
    )
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener({ change ->
            // Jemand anderes braucht den Ton dringender (z. B. ein Anruf): die Antwort bricht ab.
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) onStopRequested()
        }, main)
        .build()

    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(attributes)
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(synth.sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setBufferSizeInBytes(
            maxOf(
                AudioTrack.getMinBufferSize(synth.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT),
                synth.sampleRate, // ca. eine halbe Sekunde Puffer
            )
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    init {
        // Ohne diese Zeile wartet Android, bis der ganze Puffer (eine halbe Sekunde) gefüllt ist, bevor
        // der erste Ton kommt. Ein "Ja." ist kürzer als das und würde nie abgespielt. Mit der Schwelle
        // beginnt die Wiedergabe nach etwa 20 ms Ton; der große Puffer bleibt als Reserve gegen Ruckler.
        runCatching { track.setStartThresholdInFrames(synth.sampleRate / 50) }
    }

    @Volatile private var framesWritten = 0L
    private var session: MediaSession? = null

    /** true von "erster Ton" bis "Antwort vorbei": Nur dann sagt der Abspieler verlässlich, worüber er läuft. */
    @Volatile private var playing = false

    /**
     * Über welche Geräte der Abspieler gerade wirklich läuft. Als austauschbare Funktion, damit ein Test
     * mehrere Geräte zugleich nachstellen kann (der nachgestellte Abspieler kennt nur eines).
     */
    internal var actualDevices: () -> List<AudioDeviceInfo> = ::routedDevices

    /**
     * Zweite Sicherung nach der Prüfung vor dem ersten Ton: Der Abspieler meldet, über welche Geräte er
     * WIRKLICH läuft. Das fängt Wechsel mitten in der Antwort (Auto verbindet sich, Ausgabe umgestellt)
     * und den seltenen Fall, dass Android anders leitet als angekündigt.
     *
     * Nachgesehen wird kurz nach dem Start, bei jeder Meldung des Abspielers über einen Wechsel und,
     * solange die Antwort läuft, zusätzlich zweimal je Sekunde (falls so eine Meldung einmal ausbleibt).
     */
    private val checkActualRoute = object : Runnable {
        override fun run() {
            main.removeCallbacks(this) // nie zwei Ketten nebeneinander, auch wenn der Abspieler dazwischenruft
            if (!playing) return
            // Zuerst der Wecker: Sonst sähe sein Lautsprecher weiter unten aus wie "Kopfhörer getrennt".
            if (route.loudSoundPlaying()) {
                silence(LOUD_SOUND_NOTE)
                return
            }
            val devices = actualDevices()
            if (devices.isEmpty()) {
                // Solange noch nichts läuft, kennt der Abspieler sein Gerät nicht: anfangs in kurzem Abstand nachfragen.
                main.postDelayed(this, if (routeChecksLeft-- > 0) ROUTE_CHECK_DELAY_MS else ROUTE_RECHECK_MS)
                return
            }
            val why = route.refusal(route.verdict(devices), starting = false, callLink = voiceCallRoute)
            when {
                why == null -> {
                    wentAstray = false
                    main.postDelayed(this, ROUTE_RECHECK_MS)
                }
                // Mit dem Handy-Mikro gibt es nichts abzuwarten: Der Ton läuft über ein fremdes Gerät.
                // Mit dem Kopfhörer-Mikro gilt das erst beim zweiten Mal in Folge (siehe wentAstray).
                !voiceCallRoute || wentAstray -> {
                    playing = false // für diese Antwort ist alles entschieden; weitere Meldungen des Abspielers zählen nicht mehr
                    stop()
                    onRouteLost(why)
                }
                else -> {
                    wentAstray = true
                    silence(CALL_LINK_NOTE)
                    beginGrace()
                }
            }
        }
    }
    private var routeChecksLeft = 0 // nur auf dem Haupt-Thread gezählt
    private val routingWatch = AudioRouting.OnRoutingChangedListener { checkActualRoute.run() }

    // --- Schonfrist für den Telefonkanal (nur Kopfhörer-Mikro, nur Haupt-Thread) -------------

    private var graceRunning = false

    /**
     * true, nachdem der Abspieler über ein fremdes Gerät lief, bis er wieder nachweislich über Kopfhörer
     * läuft. Die Schonfrist gibt es nur einmal: Passiert es bei der nächsten Antwort gleich wieder, leitet
     * Android offenbar dauerhaft anders als angekündigt, und jede weitere Antwort wäre kurz falsch zu hören.
     */
    private var wentAstray = false

    private val afterGrace = Runnable {
        graceRunning = false
        // Der Kanal hatte Zeit zurückzukommen. Ginge eine Antwort immer noch nicht ins Headset, ist Schluss.
        routeProblem()?.let(onRouteLost)
    }

    /** Gibt dem Telefonkanal Zeit zurückzukommen und sieht danach noch einmal nach. */
    private fun beginGrace() {
        if (graceRunning) return
        graceRunning = true
        main.postDelayed(afterGrace, CALL_GRACE_MS)
    }

    init {
        track.addOnRoutingChangedListener(routingWatch, main)
    }

    /** Meldung, falls eine Antwort jetzt nicht in Kopfhörer ginge; null, wenn der Weg in Ordnung ist. */
    fun routeProblem(starting: Boolean = false): String? =
        route.refusal(route.verdict(attributes), starting, callLink = voiceCallRoute)

    /**
     * Für den Dienst, wenn sich an den Geräten etwas geändert hat (verbunden, getrennt, "gleich wird es laut"):
     * Ginge eine Antwort jetzt noch in Kopfhörer? Wenn nicht, verstummt sie sofort; das Zuhören endet gleich
     * (Handy-Mikro) oder nach der Schonfrist (Kopfhörer-Mikro). Gehört auf den Haupt-Thread.
     */
    fun checkRoute() {
        val why = routeProblem() ?: return
        stop()
        if (voiceCallRoute) {
            onStopRequested()
            beginGrace()
        } else {
            onRouteLost(why)
        }
    }

    /**
     * Letzte Schranke vor dem ersten Ton einer Antwort. Läuft auf dem Thread der Spracherzeugung.
     * @return null, wenn gespielt werden darf. Sonst der Abbruch, mit dem die Antwort endet; alles Nötige
     *         (Meldung an den Dienst, Schonfrist) ist dann schon veranlasst.
     */
    private fun barrier(): CancellationException? {
        val why = routeProblem()
        if (why != null) {
            if (voiceCallRoute) {
                // Vielleicht ist nur der Telefonkanal kurz weg. Diese Antwort entfällt; ob das Zuhören endet,
                // zeigt sich nach der Schonfrist.
                onWithheld(CALL_LINK_NOTE)
                onStopRequested()
                main.post { beginGrace() }
            } else {
                onRouteLost(why)
            }
            return CancellationException("Ausgabe ginge nicht in Kopfhörer")
        }
        if (route.loudSoundPlaying()) {
            onWithheld(LOUD_SOUND_NOTE)
            onStopRequested()
            return CancellationException("Wecker oder Klingelton läuft")
        }
        return null
    }

    /** Die laufende Antwort endet sofort, das Zuhören geht weiter. [note] nennt dem Nutzer den Grund. */
    private fun silence(note: String) {
        playing = false // für diese Antwort ist alles entschieden; weitere Meldungen des Abspielers zählen nicht mehr
        stop()
        onWithheld(note)
        onStopRequested()
    }

    /**
     * Alle Geräte, über die der Abspieler gerade läuft; leer, solange er es selbst noch nicht weiß.
     *
     * Android kann denselben Ton über mehrere Geräte zugleich ausgeben. Die Auskunft darüber
     * (`AudioTrack.getRoutedDevices()`) gibt es erst ab Android 16; die App wird gegen Android 15 gebaut,
     * deshalb wird die Methode dort über ihren Namen gerufen. Darunter nennt Android nur EIN Gerät.
     */
    private fun routedDevices(): List<AudioDeviceInfo> {
        if (Build.VERSION.SDK_INT >= 36) {
            val all = runCatching { ROUTED_DEVICES?.invoke(track) as? List<*> }.getOrNull()
            if (all != null) return all.filterIsInstance<AudioDeviceInfo>()
        }
        return listOfNotNull(runCatching { track.routedDevice }.getOrNull())
    }

    override suspend fun speak(sentences: ReceiveChannel<String>, release: Deferred<Boolean>?, onFirstAudio: () -> Unit) {
        var started = false
        var odd = -1 // einzelnes Byte, dessen Partner erst im nächsten Stück kommt (ein Sample = 2 Bytes)
        val pair = ByteArray(2)
        try {
            // Mit Schranke ([release]) wird schon Sprache erzeugt, aber noch nichts abgespielt: Fokus,
            // Taste und Abspieler werden erst angefasst, wenn das erste Stück wirklich freigegeben ist.
            // Eigener Rahmen für diese eine Antwort: So lässt sie sich von innen als Ganzes abbrechen
            // (siehe Schranke unten), auch wenn die Spracherzeugung das Abspielen in eine Neben-Coroutine legt.
            coroutineScope {
                val answer = this
                synth.synthesizeGated(sentences, release) { pcm, length ->
                    if (!started) {
                        // Letzte Schranke vor dem Lautsprecher. Fällt sie, gilt die Antwort als abgebrochen:
                        // kein Ton, kein Fokus, keine Taste, und auch die Erzeugung der Sprache endet.
                        barrier()?.let { refused ->
                            answer.cancel(refused)
                            throw refused
                        }
                        started = true
                        begin()
                        onFirstAudio()
                    }
                    withContext(Dispatchers.IO) {
                        var from = 0
                        if (odd >= 0 && length > 0) {
                            pair[0] = odd.toByte(); pair[1] = pcm[0]
                            if (track.write(pair, 0, 2) > 0) framesWritten += 1
                            odd = -1
                            from = 1
                        }
                        val even = (length - from) and 1.inv()
                        if (even > 0) {
                            val written = track.write(pcm, from, even)
                            if (written > 0) framesWritten += written / 2
                        }
                        if (from + even < length) odd = pcm[length - 1].toInt() and 0xFF
                    }
                }
            }
            if (started) {
                // Erst zurückkehren, wenn der letzte Satz auch wirklich zu Ende gespielt ist. Gewartet wird
                // höchstens so lange, wie der noch ausstehende Ton dauert, plus etwas Luft. Eine feste lange
                // Frist würde die App blockieren, falls die Wiedergabe einmal hängt.
                val outstandingMs = (framesWritten - playedFrames()).coerceAtLeast(0) * 1000 / synth.sampleRate
                withTimeoutOrNull(outstandingMs + 300) {
                    while (playedFrames() < framesWritten) delay(20)
                }
            }
        } catch (e: CancellationException) {
            stop() // abgebrochen: sofort still sein
            throw e
        } finally {
            if (started) end()
        }
    }

    /** Erster Ton einer Antwort: Fokus holen, Kopfhörer-Taste übernehmen, Wiedergabe starten. */
    private fun begin() {
        audio.requestAudioFocus(focusRequest)
        session = runCatching {
            MediaSession(appContext, "ClaudeVoice").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onMediaButtonEvent(intent: Intent): Boolean {
                        // Die typsichere Variante gibt es erst ab Android 13; darunter bleibt nur die alte.
                        val event = if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                        } else {
                            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                        } ?: return false
                        if (event.action == KeyEvent.ACTION_DOWN) onStopRequested()
                        return true
                    }
                }, main)
                setPlaybackState(
                    PlaybackState.Builder()
                        .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_STOP)
                        .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                        .build()
                )
                isActive = true
            }
        }.getOrNull()
        playing = true
        track.play()
        // Kurz nach dem Start noch einmal nachsehen, worüber wirklich gespielt wird (siehe checkActualRoute).
        main.post { routeChecksLeft = ROUTE_CHECK_TRIES }
        main.postDelayed(checkActualRoute, ROUTE_CHECK_DELAY_MS)
    }

    /** Wie viele Samples Android seit dem Start dieser Antwort wirklich abgespielt hat. */
    private fun playedFrames(): Long = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL

    /**
     * Antwort vorbei: Taste zurückgeben, den Abspieler anhalten und leeren, dann den Fokus abgeben
     * (die Musik läuft von selbst weiter). Das Leeren sorgt dafür, dass jede Antwort mit einem leeren
     * Puffer beginnt und kein Rest der vorigen vor ihr erklingt.
     */
    private fun end() {
        playing = false
        main.removeCallbacks(checkActualRoute)
        runCatching { session?.release() }
        session = null
        stop()
        audio.abandonAudioFocusRequest(focusRequest)
    }

    override fun prepare() = synth.prepare()

    override fun stop() {
        runCatching {
            track.pause()
            track.flush() // verwirft, was noch im Puffer liegt
        }
        framesWritten = 0
    }

    fun release() {
        stop()
        end()
        main.removeCallbacks(afterGrace)
        runCatching { track.removeOnRoutingChangedListener(routingWatch) }
        track.release()
    }

    companion object {
        /** Nach so vielen Millisekunden hat der Abspieler in aller Regel begonnen und kennt sein Gerät. */
        private const val ROUTE_CHECK_DELAY_MS = 60L

        /** So oft wird im selben Abstand nachgefragt, falls er es dann doch noch nicht kennt (zusammen eine halbe Sekunde). */
        private const val ROUTE_CHECK_TRIES = 8

        /** Abstand der laufenden Nachprüfung während einer Antwort. */
        private const val ROUTE_RECHECK_MS = 500L

        /**
         * Schonfrist für den Telefonkanal. Etwas länger als die des Mikrofons ([MicSource] wartet drei Sekunden
         * auf denselben Kanal): Kommt er nicht zurück, meldet sich das Mikrofon zuerst, mit demselben Wortlaut.
         */
        internal const val CALL_GRACE_MS = 3_500L

        /** Hinweis, wenn eine Antwort wegen eines Weckers oder Klingeltons nicht (zu Ende) gesprochen wurde. */
        const val LOUD_SOUND_NOTE = "verschwiegen: Wecker oder Klingelton läuft"

        /** Hinweis, wenn eine Antwort entfiel, weil der Telefonkanal zum Headset gerade fehlte. */
        const val CALL_LINK_NOTE = "verschwiegen: Verbindung zum Kopfhörer-Mikro unterbrochen"

        /** `AudioTrack.getRoutedDevices()`, falls dieses Android die Methode schon kennt (ab Android 16). */
        private val ROUTED_DEVICES = runCatching { AudioTrack::class.java.getMethod("getRoutedDevices") }.getOrNull()

        /**
         * Die Eigenschaften einer Antwort. Als eigene Funktion, weil der Dienst schon vor dem Start (noch ohne
         * Abspieler) wissen will, wohin Ton mit genau diesen Eigenschaften ginge.
         */
        fun attributes(voiceCallRoute: Boolean): AudioAttributes = AudioAttributes.Builder()
            // Läuft das Kopfhörer-Mikro (Telefonie-Modus), muss auch die Ausgabe über diesen Kanal,
            // sonst landet sie auf dem Handy-Lautsprecher.
            .setUsage(if (voiceCallRoute) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
