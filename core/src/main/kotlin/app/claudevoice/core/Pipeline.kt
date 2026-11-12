package app.claudevoice.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Wessen Äußerungen eine Antwort auslösen dürfen. Mitgeschrieben wird immer alles. */
enum class Reach { ICH, GESPRAECH, RAUM }

/**
 * Das Herzstück: Mikrofon -> Mitschrift -> Entscheidung -> Antwort -> Sprachausgabe.
 *
 * Drei Schichten sorgen dafür, dass nur das eigene Gespräch zählt:
 *  1. Akustik ([VoiceMeter], [VoiceClassifier]): wie laut, welche Stimmlage -> ICH / NAH / FERN.
 *  2. Ordner ([Brain.tidy]): sortiert im Hintergrund, welche Zeilen zu einem fremden Gespräch gehören.
 *  3. Antwort-Modell ([Brain.respond]): entscheidet am Inhalt, auch wenn Schicht 1 und 2 danebenliegen.
 *
 * Für Tempo:
 *  - Entscheiden und Antworten ist EIN gestreamter Aufruf; das erste Wort ist die Entscheidung.
 *  - Der Aufruf startet in dem Moment, in dem eine Äußerung angenommen ist ([onCommitted]). Er wartet nicht
 *    auf das Urteil über die Äußerung davor, außer die neue muss hinter ihr zurückstehen (siehe unten).
 *    Auch die billige Vorprüfung für beiläufige Aussagen ist Teil dieses Auftrags, mit eigener kurzer Frist.
 *  - Vorgriff: Meldet die Transkription, dass der Satz wahrscheinlich zu Ende ist, startet der Aufruf
 *    schon, bevor die Äußerung offiziell fertig ist. Stimmt die fertige Äußerung in allem damit überein
 *    ([Basis]), ist die Antwort bereits unterwegs; sonst wird der Vorgriff verworfen.
 *  - Antworten werden satzweise gesprochen, während das Modell noch schreibt. Muss die Antwort erst
 *    abwarten, ob ein Mensch selbst antwortet, entsteht die Sprache trotzdem schon; nur der Ton wartet.
 *
 * Wer wann dran ist:
 *  - Es gibt höchstens einen Auftrag ([handle]), der eine Antwort abholt und spricht.
 *  - Eine neuere Äußerung verdrängt ihn, solange noch nichts zu hören ist, wenn sie mindestens so viel
 *    Anspruch auf eine Antwort hat ([claimOf]). Hat sie weniger, steht sie zurück und kommt dran, falls der
 *    Auftrag ohne Ton endet ([revive]).
 *  - Ist die Antwort schon zu hören, unterbricht sie nur eine direkte Anrede oder der Träger selbst,
 *    der einfach weiterredet.
 */
class Pipeline(
    private val brain: Brain,
    private val speaker: Speaker,
    private val state: MutableStateFlow<UiState>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val config: Config,
    private val env: Env,
    /** Nur für Tests: eine Uhr von Hand. Ohne Angabe gelten die zwei Uhren, die unten beschrieben sind. */
    clock: (() -> Long)? = null,
) {
    /**
     * Zwei Uhren, weil sie zwei verschiedene Fragen beantworten:
     *  - [clock] misst Abstände ("vor wie vielen Sekunden", "ist die Frist um"). Sie läuft gleichmäßig
     *    vorwärts und beginnt bei null. Die Wanduhr taugt dafür nicht: Das Handy stellt sie nach dem Netz,
     *    und ein Sprung zurück ließe eine Stummschaltung zehn Minuten länger dauern oder alte Zeilen als
     *    "eben gesagt" erscheinen.
     *  - [wallClock] sagt, wie spät es ist. Sie wird nur gebraucht, um der Oberfläche das Ende einer
     *    Stummschaltung als Uhrzeit mitzuteilen.
     */
    private val clock: () -> Long = clock ?: monotonicClock()
    private val wallClock: () -> Long = clock ?: System::currentTimeMillis

    data class Config(
        /** Alle Namen, auf die der Assistent hört. */
        val names: List<String> = listOf("Alfred"),
        val mic: MicMode = MicMode.AIRPODS,
        val reach: Reach = Reach.GESPRAECH,
        /** true, wenn die Sprachausgabe ins Mikrofon zurückschallt (kein Kopfhörer): dann wird beim Sprechen nicht mitgeschrieben. */
        val echoRisk: Boolean = false,
        val voiceProfile: VoiceProfile? = null,
    )

    /** Was die Pipeline von außen braucht (Android liefert es, Tests eine Attrappe). */
    interface Env {
        fun localTime(): String
        fun musicPlaying(): Boolean
        fun saveNote(text: String)
        fun saveVoiceProfile(profile: VoiceProfile)
    }

    private sealed interface Event {
        data class Decided(val decision: Decision) : Event
        data class Sentence(val text: String) : Event
        data class Direct(val directive: Directive) : Event
        data class Failed(val error: Exception) : Event
    }

    /**
     * Alles, wovon der Text eines Modell-Aufrufs abhängt. Ein Vorgriff wird nur übernommen, wenn die fertige
     * Äußerung in jedem dieser Punkte dasselbe ergibt. Sonst hätte das Modell etwas anderes gelesen, als gilt
     * (zum Beispiel ohne den Hinweis auf das Weckwort oder mit der falschen Stimme).
     */
    private data class Basis(
        val key: String,
        val voice: Voice,
        val verified: Boolean,
        val woken: Boolean,
        val speaker: String?,
        /** true = beiläufige Aussage: Vor dem starken Modell läuft die billige Vorprüfung. */
        val gated: Boolean,
        /** Stand von [lastSpokeAtMs] beim Start. Hat der Assistent seither gesprochen, fehlt das in der Mitschrift des Aufrufs. */
        val spokeAt: Long?,
    )

    /** Ein laufender Antwort-Aufruf. Seine Ergebnisse sammeln sich in [events], bis jemand sie abholt. */
    private class Run(val basis: Basis, val events: Channel<Event>, private val job: Job, private val token: CancelToken) {
        /**
         * true, sobald nur noch [drain] den Rest des Aufrufs liest. Der Auftrag, der ihn gestartet hat, ist dann
         * fertig und räumt wie immer hinter sich auf; den Aufruf selbst darf er dabei nicht mehr abbrechen.
         */
        @Volatile var handedOver = false

        /** Der Auftrag braucht diesen Aufruf nicht mehr. Liest ihn [drain] noch zu Ende, bleibt er bestehen. */
        fun cancel() {
            if (!handedOver) end()
        }

        /**
         * Erst die Netzverbindung schließen, dann die Coroutine abbrechen. Die Reihenfolge ist der Punkt:
         * Ein Thread, der auf Netzdaten wartet, bemerkt den Abbruch der Coroutine nicht zuverlässig. Die
         * geschlossene Verbindung bemerkt er sofort.
         */
        fun end() {
            token.cancel()
            job.cancel()
        }
    }

    private class Trigger(
        val line: Line, val hot: Boolean, val named: Boolean, val woken: Boolean,
        /** Die Stimme passt nach Lautstärke und Stimmlage zum Träger (nicht bloß, weil sein Name fiel). */
        val trusted: Boolean,
        /** Der schon laufende Aufruf; null, wenn er erst in [handle] starten soll. */
        val run: Run?,
        /** Seit wann es still ist: das Ende der Sprache, nicht das (spätere) Eintreffen der fertigen Äußerung. */
        val quietSinceMs: Long,
        /** Stand des Stopp-Zählers beim Annehmen. Kam danach ein "Stopp", gilt es auch für diese Äußerung. */
        val epoch: Int,
        /** Wie viel Anspruch auf eine Antwort diese Äußerung hat, solange sie wartet ([claimOf]). */
        val claim: Int,
    )

    /** Ergebnis der Stimm-Einschätzung für eine Äußerung. */
    private class Heard(val voice: Voice, val trusted: Boolean)

    /** Wie die Äußerung zum Assistenten steht: nach dem Weckwort, bloßer Ruf, direkte Anrede. */
    private class Address(val woken: Boolean, val callOnly: Boolean, val named: Boolean)

    /** Wie dringend die Äußerung ist und ob diese Stimme überhaupt etwas auslösen darf. */
    private class Urgency(val hot: Boolean, val wanted: Boolean, val permitted: Boolean)

    /** Was die Höflichkeitspause für eine Antwort bedeutet. */
    private sealed interface Hold {
        data object Go : Hold
        class No(val reason: String) : Hold
        class Wait(val untilMs: Long) : Hold
    }

    /** Schritte in [consume], die nicht vom Modell kommen. */
    private class Released(val go: Boolean)
    private data object Finished
    /** Der Längendeckel hat gegriffen, und was noch gesprochen werden sollte, ist verklungen. */
    private data object Faded

    /** Signal "Modell hat SKIP gesagt, Rest nicht nötig". Ohne Stacktrace, weil es kein Fehler ist. */
    private class Skipped : RuntimeException(null, null, false, false)

    private val names = config.names
    private val meter = VoiceMeter()
    private val classifier = VoiceClassifier(config.mic, config.voiceProfile)
    private val speakers = SpeakerMap()
    private val buffer = ConversationBuffer()
    private val sessionNotes = CopyOnWriteArrayList<String>()
    private val segmentLines = LinkedHashMap<Int, Long>()

    /** Die jüngste angenommene Äußerung, solange ihr Auftrag noch aussteht oder läuft. */
    private val pending = AtomicReference<Trigger?>(null)
    /** Die jüngste Äußerung, die [pending] nicht verdrängen durfte. Sie kommt dran, falls jene ohne Ton endet. */
    private val parked = AtomicReference<Trigger?>(null)
    /**
     * Schützt die Übergabe "wer ist als Nächstes dran": Annehmen oder Zurückstellen in [onCommitted] und das
     * Nachrücken in [revive] laufen auf verschiedenen Threads und dürfen sich nicht überkreuzen.
     */
    private val turnLock = Any()
    /** Zählt jedes "Stopp". Damit erwischt ein Stopp auch eine Äußerung, die gerade zwischen zwei Aufträgen hängt. */
    private val stopEpoch = AtomicInteger()

    // "Conflated": Der Kanal hält nur die jeweils neueste Äußerung. Was verdrängt wird, bevor sein Auftrag
    // beginnt, braucht auch seinen Modell-Aufruf nicht mehr.
    private val triggers = Channel<Trigger>(Channel.CONFLATED, onUndeliveredElement = { drop(it) })

    /** true, solange eine Antwort zu hören ist (nicht schon, wenn sie nur vorbereitet wird). */
    @Volatile private var speaking = false
    @Volatile private var current: Job? = null
    /** Wann der Assistent zuletzt gesprochen hat; null = in dieser Sitzung noch nicht. */
    @Volatile private var lastSpokeAtMs: Long? = null
    @Volatile private var mutedUntilMs = 0L
    @Volatile private var scene: String? = null
    /** Zeitpunkt der letzten Äußerung, mit der ein Mensch im Gespräch selbst zu antworten begann. */
    @Volatile private var humanSpokeAtMs = 0L
    @Volatile private var lastAnswerWords: Set<String> = emptySet()
    /** Zeitpunkt, zu dem das lokale Weckwort erkannt wurde; null, sobald die Bitte danach verarbeitet ist. */
    @Volatile private var wokeAtMs: Long? = null
    /** Wie der Ruf nach dem Weckwort ("Hey Jarvis") klang; null, solange er nicht in der Mitschrift steht ([ownsWake]). */
    @Volatile private var wakeCaller: Voice? = null
    /** true, sobald die Transkription für die laufende Äußerung den ersten Text gemeldet hat. */
    @Volatile private var utteranceOpen = false
    /** Klang der Beginn der laufenden Äußerung nach dem Träger? Großzügig gemessen, zählt nur fürs Aufhören ([beginUtterance]). */
    @Volatile private var bargeOwn = false
    /** Wortlaut und Zeitpunkt des letzten Zwischenstands, dessen Text sich geändert hat: das hörbare Ende der Sprache. */
    @Volatile private var heardKey = ""
    @Volatile private var heardAtMs: Long? = null
    /** Wann zuletzt irgendein Aufruf an Claude ging (hält die Verbindung offen) und wann zuletzt einer mit dem großen Prompt. */
    @Volatile private var lastCallAtMs = clock()
    @Volatile private var lastPromptAtMs = clock()
    private var speculation: Run? = null
    private var loop: Job? = null
    private var tidyJob: Job? = null
    @Volatile private var warmJob: Job? = null
    private var linesSinceTidy = 0
    private var lastTidyAtMs = 0L

    fun start() {
        loop = scope.launch {
            for (trigger in triggers) {
                // Hier kommt nur an, was den laufenden Auftrag verdrängen darf ([mayReplace]) oder was nachrückt,
                // weil er ohne Ton zu Ende ging ([revive]). Der alte räumt erst zu Ende auf (angefangene Antwort
                // in den Verlauf, speaking = false), dann beginnt der neue.
                current?.cancelAndJoin()
                // LAZY: Der Auftrag läuft erst an, wenn unten feststeht, dass kein "Stopp" dazwischenkam. Liefe er
                // sofort los und würde gleich wieder abgebrochen, bliebe ein Augenblick, in dem er schon spricht:
                // Sein Aufruf läuft seit dem Eintreffen der Äußerung, die Antwort kann also längst bereitliegen.
                val job = launch(start = CoroutineStart.LAZY) { handle(trigger) }
                // Greift auch, wenn der Auftrag gar nicht mehr anläuft: Sein Modell-Aufruf darf nicht weiterlaufen.
                job.invokeOnCompletion { drop(trigger) }
                current = job
                // Kam ein "Stopp", während der alte Auftrag aufräumte, gilt es auch für diesen. Die Reihenfolge
                // macht das dicht: Hier wird erst `current` gesetzt und dann der Zähler geprüft, in
                // [stopSpeaking] erst gezählt und dann `current` abgebrochen. Eins von beiden trifft immer.
                if (trigger.epoch != stopEpoch.get()) job.cancel() else job.start()
            }
        }
    }

    fun stop() {
        loop?.cancel()
        tidyJob?.cancel()
        warmJob?.cancel()
        takeSpeculation()?.cancel()
        parked.set(null)
        speaker.stop()
    }

    /**
     * Das lokale Weckwort ("Hey Jarvis") wurde erkannt. Die nächste Bitte gilt damit als direkte Anrede,
     * auch wenn die Transkription den Namen verhört ("Hey Travis") oder ganz verschluckt.
     */
    fun onWakeWord() {
        val now = clock()
        wokeAtMs = now
        wakeCaller = null // wer gerufen hat, zeigt erst die Mitschrift des Rufs
        // Gleich kommt eine Frage. Bis sie ausgesprochen ist, vergehen ein, zwei Sekunden: genug, um die
        // Leitungen zur Sprachausgabe und zu Claude zu öffnen, ohne dass es die Antwort Zeit kostet.
        speaker.prepare()
        keepWarm(now)
    }

    /** Rohes Mikrofon-Audio: wird nur vermessen (Lautstärke, Stimmlage), nicht gespeichert. */
    fun onAudio(pcm: ByteArray, length: Int) = meter.feed(pcm, length)

    /**
     * Zwischenstand der laufenden Äußerung. [speakerId] ist die vorläufige Sprecher-ID der Transkription;
     * mit ihr schätzt der Vorgriff die Stimme genauso ein wie später die fertige Äußerung.
     */
    fun onPartial(text: String, turnProbability: Double?, speakerId: String? = null) {
        if (config.echoRisk && speaking) return
        state.update { it.copy(partial = text) }
        val now = clock()
        val key = normalize(text)
        if (key.isEmpty()) {
            // Leerer Zwischenstand (Lebenszeichen in einer Pause, zurückgezogener Text): Es ist nichts offen.
            utteranceOpen = false
            bargeOwn = false
            heardKey = ""
        } else {
            if (!utteranceOpen) {
                utteranceOpen = true
                beginUtterance()
                // Jemand fängt an zu reden: der späteste Moment, eine eingeschlafene Leitung zu Claude zu wecken.
                keepWarm(now)
            }
            // Das Ende der Sprache ist der letzte Zwischenstand, dessen Wortlaut sich noch geändert hat.
            // Wiederholungen desselben Textes verschieben es nicht.
            if (key != heardKey) {
                heardKey = key
                heardAtMs = now
            }
        }
        val heard = voiceOf(meter.peekSummary(), speakerId, text, learn = false)
        val address = addressOf(text, now, heard.voice)
        if (speaking) {
            // Ins Wort fallen: "Alfred, stopp" oder ein bloßes "stopp" beenden die Ausgabe sofort.
            // Redet der Träger selbst länger weiter, schweigt der Assistent ebenfalls, statt ihn zu übertönen.
            val w = Heuristics.words(text)
            val own = heard.voice == Voice.ICH || bargeOwn
            if (Heuristics.isStopCommand(text, names) || (own && Heuristics.isBareStop(text)) ||
                (own && w.size >= BARGE_IN_WORDS && !Heuristics.mentionsAny(text, names) && !isEcho(w))
            ) {
                stopSpeaking()
                return
            }
        } else if (heard.voice != Voice.FERN && Heuristics.isRealContent(text)) {
            // Nur wer zum Gespräch gehört, "antwortet". Der Fernseher und der Nachbartisch reden ständig
            // weiter; sie dürfen eine wartende Antwort nicht zurückziehen.
            humanSpokeAtMs = now
        }
        speculate(text, key, turnProbability, speakerId, address, heard, now)
    }

    /**
     * Die Transkription meldet den ersten Text einer Äußerung. Ab hier soll der Stimm-Messer nur noch diese
     * Äußerung beschreiben: Daran hängt, ob ICH, NAH oder FERN gilt, und damit, wem die App vertraut.
     *
     * Wer gerade spricht, ist auch gerade zu hören. Liegt der letzte Laut im Messer schon eine Sprechpause
     * zurück, stammt er von etwas anderem (ein Lachen, Geschirr, die Äußerung davor) und wird verworfen.
     * Das zählt vor allem für eine leise fremde Stimme, die der Messer gar nicht erfasst: Sonst stünde ein
     * Räuspern des Trägers von eben für sie, sie gälte als der Träger und dürfte ins Gedächtnis schreiben.
     *
     * Die Kehrseite: Meldet die Transkription ein kurzes Wort erst spät, ist auch die eigene Stimme schon
     * "eine Pause her" und die Äußerung gilt als "?". Für die Frage, ob der Träger dem Assistenten ins Wort
     * fällt ("stopp"), wird deshalb vorher großzügig gemessen ([bargeOwn]): die letzte zusammenhängende
     * Sprache, auch wenn sie schon etwas her ist. Dort ist ein Irrtum harmlos, schlimmstenfalls schweigt
     * der Assistent einmal zu früh. Vertrauen (merken, stummschalten) hängt nie an dieser Messung.
     */
    private fun beginUtterance() {
        meter.keepUtterance(UTTERANCE_LEAD_MS)
        bargeOwn = classifier.classify(meter.peekSummary(), record = false) == Voice.ICH
        meter.keepUtterance(UTTERANCE_LEAD_MS, minMs = 0)
    }

    /** Fertige Äußerung aus der Transkription. */
    fun onCommitted(text: String, speakerId: String?, segment: Int?) {
        val t = text.trim()
        // Kam kein Zwischenstand davor, ist unbekannt, wo die Äußerung begann. Dann wird die Länge aus dem
        // Text geschätzt (rund zwei bis drei Wörter je Sekunde) und an der letzten Pause geschnitten.
        // Sicher ist nur das Ende: Die Äußerung ist eben erst verklungen. Liegt der letzte Laut im Messer
        // länger zurück, als die Transkription zum Melden braucht, hat er eine andere Stimme gemessen.
        if (!utteranceOpen) {
            meter.keepUtterance(UTTERANCE_LEAD_MS + MS_PER_WORD * Heuristics.words(t).size, idleMs = COMMIT_LAG_MS)
        }
        utteranceOpen = false
        val ownStart = bargeOwn
        bargeOwn = false
        val features = meter.takeSummary()
        val now = clock()
        // Die fertige Äußerung trifft erst nach einer Stille-Frist der Transkription ein (bis zu einer halben
        // Sekunde, je nach Anbieter mehr). Still ist es aber schon seit dem letzten neuen Wort.
        val quietSince = heardAtMs?.let { maxOf(it, now - HOLD_MS) }?.coerceAtMost(now) ?: now
        heardAtMs = null
        heardKey = ""
        if (t.isEmpty() || (config.echoRisk && speaking)) {
            takeSpeculation()?.cancel()
            return
        }
        // Eigenes Echo: Schallt die Sprachausgabe ins Mikro zurück, steht der Antworttext plötzlich
        // in der Mitschrift. Was dem eben Gesprochenen stark ähnelt, wird verworfen.
        if ((speaking || spokeWithin(now, 3_000)) && isEcho(Heuristics.words(t))) {
            takeSpeculation()?.cancel()
            return
        }
        val heard = voiceOf(features, speakerId, t, learn = true)
        val voice = heard.voice
        // Erst die Stimme, dann die Frage, ob die Äußerung das Weckwort einlöst: Das darf nur, wer gerufen hat.
        val address = addressOf(t, now, voice)
        // Der bloße Ruf ("Hey Jarvis", durch die Pause vor der Bitte abgetrennt) verbraucht das Weckwort
        // nicht: Die Bitte kommt erst noch. Das Fenster schließt sich mit ihr oder nach WAKE_WINDOW_MS.
        // Was andere in der Zwischenzeit sagen, lässt es offen.
        if (address.callOnly) {
            // Der erste Ruf sagt, wer gerufen hat. Ein späterer zählt nur noch, wenn er sicher vom Träger kommt.
            // Ein Ruf, in dem gar kein Name fällt ("Ja.", "Hey"), sagt es nicht: Solche Wörter kommen auch aus
            // dem Fernseher, und der dürfte danach das Weckwort einlösen ([ownsWake]). Er gilt weiter als
            // bloßer Ruf und verbraucht nichts, der Rufer bleibt aber offen, als stünde der Ruf nicht da.
            if (voice == Voice.ICH || (wakeCaller == null && !isNamelessCall(t))) wakeCaller = voice
        } else if (address.woken) {
            wokeAtMs = null
        }
        val line = buffer.add(t, voice, now, speakers.label(speakerId)) ?: return
        if (segment != null) rememberSegment(segment, line.id)
        state.update { it.copy(partial = "", transcript = (it.transcript + display(line)).takeLast(60)) }

        if (voice != Voice.FERN && Heuristics.isRealContent(t)) humanSpokeAtMs = now
        val spec = takeSpeculation()
        if (Heuristics.isStopCommand(t, names) || (speaking && (voice == Voice.ICH || ownStart) && Heuristics.isBareStop(t))) {
            spec?.cancel()
            stopSpeaking()
            return
        }
        scheduleTidy(now)

        // Nur der Träger selbst hebt eine Stummschaltung auf ("Alfred, du darfst wieder"). Dass sein Name
        // irgendwo fällt, ein Fremder ihn ruft oder ein Film "Jarvis" sagt, reicht dafür nicht.
        val muted = now < mutedUntilMs
        if (muted && address.named && heard.trusted) unmute()
        // Der Ton nach dem Weckwort war schon die Bestätigung. Ein "Ja, Sir?" würde jetzt über die
        // eigentliche Frage sprechen. Der Ruf steht in der Mitschrift, löst aber nichts aus.
        if (address.callOnly) {
            spec?.cancel()
            return
        }
        val urgency = urgencyOf(t, now, heard, address.named, muted)
        // Während der Assistent zu hören ist, unterbricht ihn nur eine direkte Anrede.
        if (!urgency.wanted || !urgency.permitted || (speaking && !address.named)) {
            spec?.cancel()
            return
        }
        val standing = claimOf(address.named, urgency.hot, voice, t, waiting = true)
        synchronized(turnLock) {
            // Wartet schon eine Antwort, verdrängt sie nur, wer mindestens so viel Anspruch hat. Wer weniger hat,
            // steht zurück: Sein Aufruf startet erst, falls die wartende Antwort ohne Ton endet ([revive]).
            if (!mayReplace(claimOf(address.named, urgency.hot, voice, t, waiting = false))) {
                spec?.cancel()
                parked.set(
                    Trigger(
                        line, hot = urgency.hot, named = address.named, woken = address.woken, trusted = heard.trusted,
                        run = null, quietSinceMs = quietSince, epoch = stopEpoch.get(), claim = standing,
                    )
                )
                return
            }
            val interrupting = speaking
            if (interrupting) stopSpeaking()

            val basis = Basis(normalize(t), voice, heard.trusted, address.woken, line.speaker, gated = !urgency.hot, spokeAt = lastSpokeAtMs)
            // Wird gerade eine hörbare Antwort unterbrochen, kommt ihr gesprochener Teil erst noch in die
            // Mitschrift. Dann startet der Aufruf in [handle], nachdem der alte Auftrag aufgeräumt hat.
            val adopted = spec?.takeIf { !interrupting && it.basis == basis }
            if (adopted == null) spec?.cancel()
            val run = adopted ?: if (interrupting) null else startRun(turnFor(line, address.woken, heard.trusted), basis)
            val trigger = Trigger(
                line, hot = urgency.hot, named = address.named, woken = address.woken, trusted = heard.trusted,
                run = run, quietSinceMs = quietSince, epoch = stopEpoch.get(), claim = standing,
            )
            // Es zählt die neueste Äußerung: Was bisher zurückstand, ist älter und mit abgelöst.
            parked.set(null)
            pending.set(trigger)
            triggers.trySend(trigger)
        }
    }

    /** Die Diarisierung liefert nach, wer in einer früheren Äußerung gesprochen hat. */
    fun onDiarized(segment: Int, parts: List<SpokenPart>) {
        val id = synchronized(segmentLines) { segmentLines[segment] } ?: return
        if (parts.isEmpty()) return
        // Aufeinanderfolgende Teile desselben Sprechers zusammenfassen.
        val groups = ArrayList<Pair<String?, StringBuilder>>()
        for (p in parts) {
            if (groups.isNotEmpty() && groups.last().first == p.speaker) groups.last().second.append(p.text)
            else groups += p.speaker to StringBuilder(p.text)
        }
        val dominant = groups.groupBy({ it.first }, { it.second.length }).maxByOrNull { it.value.sum() }?.key
        val real = groups.filter { Heuristics.words(it.second.toString()).size >= 2 }
        val text = if (real.map { it.first }.distinct().size >= 2) {
            // Zwei Stimmen in einer Zeile: sichtbar trennen, damit das Modell sie auseinanderhalten kann.
            real.joinToString(" / ") { "${speakers.label(it.first) ?: "?"}: ${it.second.toString().trim()}" }
        } else null
        val voice = if (dominant != null && speakers.wearer() == dominant) Voice.ICH else null
        buffer.relabel(id, voice = voice, speaker = speakers.label(dominant), text = text)
    }

    fun onSpeakersMerged(from: String, to: String) {
        val old = speakers.label(from)
        speakers.merge(from, to)
        val new = speakers.label(to)
        if (old != null && new != null && old != new) buffer.renameSpeaker(old, new)
    }

    /**
     * Nach einem Neuverbinden vergibt der Anbieter Sprecher-IDs und Segmentnummern neu. Auch der Stimm-Messer
     * fängt von vorn an: Was vor der Pause ins Mikro kam, gehört zu keiner Äußerung der neuen Sitzung.
     * Das Weckwort bleibt bewusst stehen, denn nach ihm wird die Sitzung überhaupt erst geöffnet.
     */
    fun onTranscriberReset() {
        speakers.reset()
        synchronized(segmentLines) { segmentLines.clear() }
        takeSpeculation()?.cancel()
        meter.clear()
        utteranceOpen = false
        bargeOwn = false
        heardKey = ""
        heardAtMs = null
    }

    /** Laufende oder noch wartende Antwort abbrechen (Knopf in der App, Kopfhörer-Taste oder "Alfred, stopp"). */
    fun stopSpeaking() {
        stopEpoch.incrementAndGet()
        parked.set(null) // auch was zurückstand, rückt nach einem Stopp nicht mehr nach
        current?.cancel()
        speaker.stop()
        speaking = false
    }

    // ------------------------------------------------------------------------------------------
    // Wer spricht, und wie ist die Äußerung gemeint?
    // Vorgriff und fertige Äußerung rechnen mit denselben Funktionen. Nur so kann ein Vorgriff
    // übernommen werden, ohne dass dem Modell etwas anderes vorgelegt wurde, als am Ende gilt.

    private fun addressOf(text: String, now: Long, voice: Voice): Address {
        // Nur lesen: Verbraucht wird das Weckwort erst von der fertigen Äußerung.
        val wakeOpen = wokeAtMs?.let { now - it < WAKE_WINDOW_MS } ?: false
        // Der bloße Ruf zählt von jeder Stimme als Ruf: Er löst ohnehin nichts aus.
        val callOnly = wakeOpen && isBareCall(text, names, afterWakeWord = true)
        val mine = wakeOpen && ownsWake(voice)
        return Address(woken = mine && !callOnly, callOnly = callOnly, named = mine || Heuristics.mentionsAny(text, names))
    }

    /**
     * Darf diese Stimme das offene Fenster nach dem Weckwort einlösen, gilt ihre Äußerung also als die Bitte?
     *
     * Nach "Hey Jarvis" überlegt der Träger oft noch einen Moment. Was in dieser Zeit sonst jemand sagt, hat
     * mit dem Weckwort nichts zu tun: Würde es als direkte Bitte behandelt, bekäme der Fernseher eine Antwort
     * ins Ohr des Trägers, ohne Höflichkeitspause und sogar bei Reichweite "Nur ich".
     *
     * Maßstab ist, wie der Ruf selbst klang ([wakeCaller]):
     *  - Hat die Akustik den Ruf dem Träger zugeordnet (ICH), löst nur seine Stimme das Weckwort ein.
     *  - Sonst jede Stimme aus der Nähe (NAH, "?"): Genauer lässt sich der Rufer dann nicht eingrenzen.
     *  - Eine ferne Stimme (FERN) nur, wenn schon der Ruf fern klang; dann sagt der Abstand nichts. Steht
     *    der Ruf gar nicht in der Mitschrift, gilt das am Handy-Mikro ebenso, denn dort klingt auch der
     *    Träger oft fern. Am Kopfhörer-Mikro klingt er so gut wie nie fern: Dort ist es eher der Fernseher.
     *    Nennt eine ferne Stimme den Namen, bleibt das unabhängig davon eine Anrede.
     *
     * Wer das Weckwort nicht einlöst, verbraucht es auch nicht: Seine Äußerung geht den normalen Weg, und
     * die Bitte des Trägers kann danach noch kommen. Das Fenster schließt sich mit der Bitte, mit der
     * nächsten Antwort des Assistenten ([consume]) oder nach WAKE_WINDOW_MS von selbst.
     */
    private fun ownsWake(voice: Voice): Boolean {
        val caller = wakeCaller
        return when {
            caller == Voice.ICH -> voice == Voice.ICH
            voice != Voice.FERN -> true
            else -> caller == Voice.FERN || (caller == null && config.mic == MicMode.HANDY)
        }
    }

    private fun urgencyOf(text: String, now: Long, heard: Heard, named: Boolean, muted: Boolean): Urgency {
        val heat = Heuristics.heat(text, names)
        val hot = named || isFollowUp(now, heard.voice, text) || heat == Heuristics.Heat.HOT
        val wanted = hot || heat == Heuristics.Heat.MAYBE
        val permitted = if (muted) named && heard.trusted else named || allowed(heard.voice, hot)
        return Urgency(hot, wanted, permitted)
    }

    /**
     * @param learn true nur für die fertige Äußerung: Dann zählt sie in die Statistik und darf das Stimmprofil
     *              lernen. Ein Zwischenstand (false) rechnet gleich, lernt und speichert aber nichts.
     */
    private fun voiceOf(f: VoiceFeatures?, speakerId: String?, text: String, learn: Boolean): Heard {
        val raw = classifier.classify(f, record = learn)
        var voice = raw
        // "Gesichert" heißt: Die Akustik selbst hält die Stimme für die des Trägers. Nur dann darf eine
        // Äußerung etwas Dauerhaftes bewirken (merken, stummschalten, Stimmprofil lernen).
        var trusted = raw == Voice.ICH
        // Selbstkalibrierung: Wer den Satz mit dem Namen des Assistenten beginnt, ist meist der Träger.
        // Das gilt aber nur, wenn auch die Akustik dazu passt. Sonst würde der Erste, der den Namen
        // ruft (ein Freund, ein Film), als "Träger" gespeichert. Gemeint ist der Name im Wortlaut: Das
        // Weckwort allein reicht dafür nicht, es sagt nichts darüber, wessen Stimme hier zu hören ist.
        val addressed = Heuristics.words(text).take(3).any { Heuristics.mentionsAny(it, names) }
        val plausible = f != null && if (classifier.profile == null) {
            config.mic == MicMode.AIRPODS && f.levelDb >= OWN_MIN_DB
        } else raw == Voice.ICH
        if (addressed && plausible) {
            if (learn) {
                classifier.learnOwn(f)
                classifier.profile?.let(env::saveVoiceProfile)
            }
            voice = Voice.ICH
            trusted = true
        }
        if (learn) speakers.observe(speakerId, voice)
        val wearer = speakers.wearer()
        if (speakerId != null && wearer != null) {
            // Die Diarisierung kennt den Träger bereits: sie überstimmt die Akustik in beide Richtungen.
            if (speakerId == wearer) { voice = Voice.ICH; trusted = true }
            else if (voice == Voice.ICH) { voice = Voice.NAH; trusted = false }
        }
        return Heard(voice, trusted)
    }

    /**
     * Darf diese Stimme eine Antwort auslösen?
     * - ICH: streng, nur die eigene Stimme (oder unbekannt).
     * - GESPRAECH: Die Lautstärke-Schätzung irrt sich (Flüstern im Unterricht klingt "fern"). Deshalb wird
     *   eine klare Frage auch dann dem Modell vorgelegt, wenn sie als FERN gilt; es sieht den Hinweis und
     *   entscheidet am Inhalt. Nur beiläufige Aussagen ferner Stimmen werden gleich verworfen.
     * - RAUM: alles.
     */
    private fun allowed(voice: Voice, hot: Boolean): Boolean = when (config.reach) {
        Reach.ICH -> voice == Voice.ICH || voice == Voice.UNKLAR
        Reach.GESPRAECH -> voice != Voice.FERN || hot
        Reach.RAUM -> true
    }

    /**
     * Wie viel Anspruch auf eine Antwort hat eine Äußerung? Davon hängt ab, ob sie die Antwort verdrängen
     * darf, die gerade wartet (noch beim Modell oder in der Höflichkeitspause, jedenfalls noch nicht zu hören).
     *
     *     Rang  Äußerung
     *     6     direkte Anrede aus der Nähe (Name oder Weckwort);
     *           Frage des Trägers, wenn die Akustik sie ihm zuordnet (ICH): Er korrigiert sich oder fragt weiter
     *     5     direkte Anrede einer fernen Stimme (der Name fällt im Fernseher, oder der Träger flüstert)
     *     4     Frage aus der Runde (NAH oder "?")
     *     3     beiläufige Aussage des Trägers (ICH), über die erst die Vorprüfung urteilt
     *     2     Frage einer fernen Stimme (FERN)
     *     1     beiläufige Aussage anderer
     *     0     Einwurf ohne eigenes Anliegen ("Warum?", "Ja wieso?"), siehe [isAside]
     *
     * Die neue Äußerung verdrängt die wartende, wenn ihr Rang mindestens so hoch ist ([mayReplace]). Bei
     * Gleichstand gewinnt also die neuere: "Wie hoch ist die Zugspitze? Nein, ich meine den Watzmann" soll
     * nicht mit der Zugspitze beantwortet werden; die alte Frage steht ohnehin in der Mitschrift des neuen
     * Aufrufs. Hat die neue weniger Anspruch, steht sie zurück ([parked]) und kommt dran, falls die
     * wartende Antwort ohne Ton endet. So nimmt der Satz eines Freundes oder der Fernseher dem Träger keine
     * Antwort weg, und trotzdem geht keine Äußerung verloren, nur weil gerade über eine andere geurteilt wird.
     *
     * Drei Einzelheiten, die sich aus Fehlern ergeben haben:
     *  - "?" heißt "zu wenig Signal" und trifft gerade leise und kurze fremde Stimmen. Die eigene Stimme ist
     *    am Kopfhörer-Mikro die, die sich immer messen lässt. "?" zählt darum wie die Runde, nicht wie der Träger.
     *    Der Preis: Wo der Träger selbst nicht als ICH messbar ist (Handy-Mikro ohne Stimmprofil, lauter
     *    Dauerlärm), verdrängt seine Korrektur ohne Anrede die eigene direkte Bitte nicht. Die wird dann
     *    beantwortet, wie sie gestellt war; wer sich korrigieren will, nennt den Namen noch einmal.
     *  - Ein Einwurf ohne Inhalt sieht mit seinem Fragezeichen wie eine Frage aus, ist aber kein Anliegen.
     *    ("Echt?", "Was?" und "Oder?" kommen gar nicht mehr bis hierher: Die verwirft schon der Vorfilter.)
     *    Was als Einwurf gilt, hängt davon ab, ob die Äußerung neu dazukommt oder selbst wartet ([isAside]).
     *  - Wartet die Frage des Trägers selbst und trägt keine Anrede ([waiting]), steht sie nur auf Rang 4. Eine
     *    inhaltliche Äußerung aus der Runde danach heißt: Das Gespräch geht weiter. Die Höflichkeitspause
     *    zöge die Antwort dann ohnehin zurück ([holdVerdict]); so beginnt der neue Aufruf wenigstens gleich.
     *
     * Vertrauen (merken, stummschalten) hängt von alledem nicht ab, nur davon, wer als Nächstes dran ist.
     */
    private fun claimOf(named: Boolean, hot: Boolean, voice: Voice, text: String, waiting: Boolean): Int = when {
        named -> if (voice == Voice.FERN) CLAIM_FAR_CALL else CLAIM_DIRECT
        hot && isAside(text, waiting) -> CLAIM_ASIDE
        hot && voice == Voice.ICH -> if (waiting) CLAIM_QUESTION else CLAIM_DIRECT
        hot && voice == Voice.FERN -> CLAIM_FAR_QUESTION
        hot -> CLAIM_QUESTION
        voice == Voice.ICH -> CLAIM_OWN_REMARK
        else -> CLAIM_REMARK
    }

    /**
     * Ist das ein Einwurf ohne eigenes Anliegen (Rang 0 in [claimOf])? Gemessen wird verschieden, je nachdem,
     * auf welcher Seite die Äußerung steht:
     *  - Kommt sie neu dazu ([waiting] = false), gilt der Maßstab der Höflichkeitspause
     *    ([Heuristics.isRealContent]): Wer dort keine Antwort zurückzieht, verdrängt sie auch vorher nicht.
     *    Dazu gehört Ratlosigkeit. Sagt der Freund auf die Frage des Trägers "keine Ahnung, wie die heißt",
     *    steht sein Satz zurück, und die Frage wird beantwortet.
     *  - Wartet sie selbst ([waiting] = true), zählt nur, ob sie für sich etwas sagt
     *    ([Heuristics.saysSomething]). Derselbe Satz vom Träger, ohne Frage davor, ist sein Wissenswunsch und
     *    behält den Rang einer Frage. Sonst nähme ihm die nächste Quizfrage aus dem Fernseher die Antwort
     *    weg, und nachgerückt wäre sie nicht mehr: Verdrängt ist verdrängt.
     */
    private fun isAside(text: String, waiting: Boolean): Boolean =
        if (waiting) !Heuristics.saysSomething(text) else !Heuristics.isRealContent(text)

    /** Darf eine Äußerung mit diesem Anspruch ([claimOf]) die wartende Antwort verdrängen? Wartet keine: ja. */
    private fun mayReplace(claim: Int): Boolean = pending.get()?.let { claim >= it.claim } ?: true

    private fun isFollowUp(now: Long, voice: Voice, text: String): Boolean =
        spokeWithin(now, FOLLOW_UP_MS) && (voice == Voice.ICH || voice == Voice.UNKLAR) && Heuristics.words(text).size >= 2

    private fun spokeWithin(now: Long, ms: Long): Boolean = lastSpokeAtMs?.let { now - it < ms } ?: false

    // ------------------------------------------------------------------------------------------
    // Vorgriff

    @Synchronized
    private fun speculate(
        text: String, key: String, turnProbability: Double?, speakerId: String?, address: Address, heard: Heard, now: Long,
    ) {
        speculation?.let { if (it.basis.key != key) { it.cancel(); speculation = null } }
        if (turnProbability == null || turnProbability < SPECULATE_AT || speculation != null || speaking) return
        // Der bloße Ruf nach dem Weckwort ist keine Bitte: darauf wird nichts vorbereitet.
        if (key.isEmpty() || address.callOnly) return
        if (now < mutedUntilMs) return // stumm: kein Vorgriff, die fertige Äußerung entscheidet
        val urgency = urgencyOf(text, now, heard, address.named, muted = false)
        if (!urgency.wanted || !urgency.permitted) return
        // Ein Vorgriff für eine Äußerung, die die wartende Antwort gar nicht verdrängen dürfte, wäre verschenkt.
        if (!mayReplace(claimOf(address.named, urgency.hot, heard.voice, text, waiting = false))) return
        val line = Line(id = -1, atMs = now, voice = heard.voice, text = text.trim(), speaker = speakers.label(speakerId))
        val basis = Basis(key, heard.voice, heard.trusted, address.woken, line.speaker, gated = !urgency.hot, spokeAt = lastSpokeAtMs)
        speculation = startRun(turnFor(line, address.woken, heard.trusted), basis)
    }

    @Synchronized
    private fun takeSpeculation(): Run? = speculation.also { speculation = null }

    private fun normalize(text: String) = Heuristics.words(text).joinToString(" ")

    // ------------------------------------------------------------------------------------------
    // Vorwärmen

    /**
     * Weckt die Leitung zu Claude, bevor sie gebraucht wird.
     *
     * Zwei Dinge schlafen ein: die Netzverbindung (nach rund fünf Minuten ohne Aufruf wird sie abgebaut,
     * der nächste Aufruf muss sie erst neu aushandeln) und der Zwischenspeicher für den großen Prompt
     * (eine Stunde). Geweckt wird mit einem Aufruf, der nur den festen Prompt schickt und keine Antwort
     * erzeugt ([Brain.warmUp]).
     *
     * Aufgerufen wird das nur, wenn gerade jemand zu reden beginnt oder das Weckwort fiel. Bis daraus eine
     * Frage geworden ist, vergeht mehr Zeit, als das Wecken braucht. Es läuft in einer eigenen Coroutine,
     * auf die nie eine Antwort wartet. Einen Zeitgeber gibt es mit Absicht nicht: Solange niemand spricht,
     * soll auch nichts das Gerät verlassen.
     */
    private fun keepWarm(now: Long) {
        val asleep = now - lastCallAtMs >= WARM_AFTER_IDLE_MS || now - lastPromptAtMs >= WARM_CACHE_AFTER_MS
        if (!asleep || warmJob?.isActive == true) return
        lastCallAtMs = now
        lastPromptAtMs = now
        warmJob = scope.launch(io) {
            try {
                runInterruptible { brain.warmUp() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Vorwärmen ist Kür. Scheitert es, baut der nächste echte Aufruf die Verbindung eben selbst auf.
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Antwort

    private fun turnFor(line: Line, woken: Boolean, verified: Boolean): Turn {
        val history = buffer.snapshot(clock()).filter { it.id != line.id }
        val spokeAgo = lastSpokeAtMs?.let { ((line.atMs - it) / 1000).coerceAtLeast(0) }
        return Turn(
            history, line,
            Situation(env.localTime(), config.mic, env.musicPlaying(), spokeAgo, scene, sessionNotes.toList(), woken, verified),
        )
    }

    /**
     * Startet den Modell-Aufruf sofort im Hintergrund; die Ergebnisse warten im Kanal auf Abholung.
     * Für eine beiläufige Aussage ([Basis.gated]) läuft davor die billige Vorprüfung, im selben Auftrag.
     */
    private fun startRun(turn: Turn, basis: Basis): Run {
        val events = Channel<Event>(Channel.UNLIMITED)
        val token = CancelToken()
        lastCallAtMs = clock()
        val job = scope.launch(io) {
            val chunker = SentenceChunker()
            val parser = ReplyParser(
                onDecision = { events.trySend(Event.Decided(it)) },
                onSpeech = { delta -> chunker.feed(delta).forEach { events.trySend(Event.Sentence(it)) } },
                onDirective = { events.trySend(Event.Direct(it)) },
            )
            try {
                if (basis.gated && !passesGate(turn)) {
                    events.trySend(Event.Decided(Decision.Skip("kein Anlass")))
                    return@launch
                }
                ensureActive()
                // Ab jetzt kann eine Antwort kommen: Die Leitung zur Sprachausgabe soll stehen, wenn der erste
                // Satz da ist. Das gilt für den Vorgriff genauso wie für die fertige Äußerung. Für beiläufige
                // Aussagen geschieht es erst hier, nach der Vorprüfung: Die meisten enden mit "kein Anlass".
                runCatching { speaker.prepare() } // Kür: Ohne Vorbereitung dauert der erste Ton nur etwas länger
                lastPromptAtMs = clock()
                runInterruptible {
                    try {
                        brain.respond(turn, token) { delta ->
                            parser.feed(delta)
                            // SKIP steht in der ersten Zeile: den Rest brauchen wir nicht, also Aufruf abbrechen.
                            if (parser.decision is Decision.Skip) throw Skipped()
                        }
                    } catch (e: Skipped) {
                        // gewollt
                    }
                }
                parser.finish()
                chunker.flush()?.let { events.trySend(Event.Sentence(it)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nach einem gewollten Abbruch ist der Lesefehler erwartet und keine Meldung wert.
                if (!token.cancelled) events.trySend(Event.Failed(e))
            } finally {
                events.close()
            }
        }
        // Sicherheitsnetz für jeden Weg, der nicht über Run.cancel() führt (etwa wenn der ganze Dienst endet,
        // oder der Auftrag abgebrochen wurde, bevor er anlief): Verbindung zu, Kanal zu.
        job.invokeOnCompletion {
            token.cancel()
            events.close()
        }
        return Run(basis, events, job, token)
    }

    /**
     * Die billige Vorprüfung. Sie darf die Antwort nie lange aufhalten: Läuft die Frist ab oder scheitert der
     * Aufruf, gilt das als "weiter". Das starke Modell entscheidet danach ohnehin noch einmal selbst.
     */
    private suspend fun passesGate(turn: Turn): Boolean = try {
        withTimeoutOrNull(GATE_TIMEOUT_MS) { runInterruptible { brain.pregate(turn) } } ?: true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        true
    }

    private suspend fun handle(trigger: Trigger) {
        var run = trigger.run
        // Wird wahr, sobald der Lautsprecher den ersten Ton meldet. Erst dann ist etwas gesagt.
        val heard = AtomicBoolean(false)
        try {
            // Der Aufruf lief schon los, als die Äußerung ankam. Neu gestartet wird er nur, wenn der Assistent
            // seither etwas gesagt hat: Dann fehlt dem laufenden Aufruf diese Antwort in der Mitschrift.
            if (run == null || run.basis.spokeAt != lastSpokeAtMs) {
                run?.cancel()
                val line = trigger.line
                val basis = Basis(
                    normalize(line.text), line.voice, trigger.trusted, trigger.woken, line.speaker,
                    gated = !trigger.hot, spokeAt = lastSpokeAtMs,
                )
                run = startRun(turnFor(line, trigger.woken, trigger.trusted), basis)
            }
            consume(run, trigger, heard)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.update { it.copy(error = "Antwort fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}") }
        } finally {
            run?.cancel()
            speaking = false
            state.update { it.copy(speaking = false) }
        }
        // Bis hierher kommt nur ein Auftrag, der von selbst zu Ende ging (nicht verdrängt, nicht gestoppt).
        revive(trigger, spoke = heard.get())
    }

    /** Diese Äußerung ist erledigt oder verdrängt: Ihr Aufruf wird nicht mehr gebraucht. */
    private fun drop(trigger: Trigger) {
        trigger.run?.cancel()
        pending.compareAndSet(trigger, null)
    }

    /**
     * Der Auftrag für [done] ist von selbst zu Ende gegangen. Er macht Platz, und wer zurückstehen musste
     * ([parked]), rückt nach.
     *
     * Nachgerückt wird nur, wenn nichts zu hören war (das Modell schweigt, ein Mensch hat selbst geantwortet,
     * der Aufruf ist gescheitert). Wurde gesprochen ([spoke]), verfällt die zurückgestellte Äußerung: Der
     * Assistent hat eben geredet, und was währenddessen gesagt wurde, steht in der Mitschrift für die
     * nächste Frage.
     */
    private fun revive(done: Trigger, spoke: Boolean) {
        synchronized(turnLock) {
            // Nur wenn dieser Auftrag noch der aktuelle ist. Hat inzwischen eine neuere Äußerung übernommen,
            // hat sie auch die zurückgestellte abgelöst.
            if (pending.get() !== done) return
            val next = parked.getAndSet(null)?.takeIf {
                !spoke &&
                    it.epoch == stopEpoch.get() && // dazwischen kam kein "Stopp"
                    // Veraltet: Ohne Anrede würde die Antwort jetzt ohnehin nicht mehr gesprochen ([holdVerdict]).
                    (it.named || clock() - it.line.atMs <= STALE_MS)
            }
            // Auch ohne Nachrücker wird hier schon freigegeben, nicht erst, wenn die Coroutine ganz beendet ist:
            // Eine Äußerung, die in genau diesem Augenblick eintrifft, soll nicht hinter einem Auftrag
            // zurückstehen, der niemanden mehr nachholt.
            pending.set(next)
            if (next != null) triggers.trySend(next)
        }
    }

    /**
     * Holt die Ergebnisse eines Aufrufs ab und lässt sie sprechen.
     *
     * Die Sätze gehen sofort an die Sprachausgabe, auch während noch offen ist, ob ein Mensch selbst
     * antwortet. Darf gleich gesprochen werden (direkte Anrede, niemand sonst im Gespräch), gibt es keine
     * Schranke. Muss die Antwort die Höflichkeitspause abwarten, entscheidet die Schranke `release`, ob
     * die schon erzeugte Sprache zu hören ist: Sie öffnet sich nach der Pause, oder sie bleibt zu.
     *
     * Solange die Schranke zu ist, gilt nichts als gesagt: `speaking` bleibt false, die Anzeige zeigt die
     * Antwort nicht, MEMO und MUTE warten. Bleibt sie zu, steht auch nichts im Verlauf. Sonst würde das
     * Modell beim nächsten Mal glauben, es habe etwas gesagt, das nie jemand gehört hat.
     *
     * Aus demselben Grund kommt die Antwort erst in den Verlauf, wenn der Lautsprecher den ersten Ton
     * gemeldet hat ([heard]). Zwischen "freigegeben" und "zu hören" liegen ein paar Zehntelsekunden, in
     * denen die Sprache noch entsteht. Wer in dieser Spanne "stopp" sagt, hat nichts gehört.
     */
    private suspend fun consume(run: Run, trigger: Trigger, heard: AtomicBoolean) {
        val line = trigger.line
        val answer = StringBuilder()
        // Dieselben Sätze in der Fassung, die die Stimme gelesen hat (siehe speakable).
        val voiced = StringBuilder()
        val waiting = ArrayList<Directive>()
        var say: Decision.Say? = null
        var audible = false
        var words = 0
        var cap = Int.MAX_VALUE
        var capped = false
        try {
            coroutineScope {
                val sentences = Channel<String>(Channel.UNLIMITED)
                var release: CompletableDeferred<Boolean>? = null
                var speech: Job? = null
                var hold: Deferred<Boolean>? = null
                var events: ReceiveChannel<Event>? = run.events

                // Die Sprachausgabe läuft neben dem Lesen her: Satz eins ist zu hören, während Satz zwei entsteht.
                // Sie startet erst, wenn das Modell sprechen will; die Leitung dafür steht schon ([startRun]).
                fun speak(gate: CompletableDeferred<Boolean>?) {
                    release = gate
                    speech = launch {
                        speaker.speak(sentences, gate) {
                            heard.set(true)
                            state.update { it.copy(latencyMs = clock() - trigger.quietSinceMs) }
                        }
                    }
                }

                fun open(d: Decision.Say) {
                    audible = true
                    speaking = true
                    // Das Weckwort gilt genau einer Bitte. Wird auf eine Äußerung geantwortet, die nach ihm kam,
                    // ist es eingelöst, auch wenn sie es selbst nicht einlösen durfte (etwa weil die Stimme des
                    // Trägers diesmal leiser gemessen wurde). Sonst gälte sein nächster Satz noch als Bitte.
                    wokeAtMs?.let { if (it <= line.atMs) wokeAtMs = null }
                    state.update {
                        it.copy(
                            question = line.text, answer = answer.toString(), speaking = true, error = null,
                            lastDecision = "antwortet" + if (d.length.isNotEmpty()) " (${d.length})" else "",
                        )
                    }
                    release?.complete(true)
                    waiting.forEach { directive(it, trigger) }
                    waiting.clear()
                }

                try {
                    // Zwei Dinge können als Nächstes passieren: Das Modell liefert etwas, oder die
                    // Höflichkeitspause geht zu Ende. `select` wartet auf beides und nimmt, was zuerst kommt.
                    // Nach dem Längendeckel kommt ein Drittes dazu: Der letzte Satz ist verklungen. Liegt beides
                    // bereit, geht das Modell vor (die Reihenfolge hier ist die Rangfolge): Was es schon geliefert
                    // hat, wird noch an Ort und Stelle gelesen.
                    while (events != null || hold != null) {
                        val step: Any = select {
                            hold?.let { h -> h.onAwait { Released(it) } }
                            events?.let { ch -> ch.onReceiveCatching { it.getOrNull() ?: Finished } }
                            speech?.takeIf { capped && audible && events != null }?.let { s -> s.onJoin { Faded } }
                        }
                        when (step) {
                            is Released -> {
                                hold = null
                                val d = say
                                if (step.go && d != null) open(d) else { skipped("mensch antwortet"); break }
                            }
                            is Finished -> {
                                events = null
                                sentences.close() // mehr Text kommt nicht: Die Sprachausgabe kann abschließen
                            }
                            is Faded -> {
                                // Der Ton ist zu Ende, das Modell schreibt noch. Zu sprechen gibt es davon nichts
                                // mehr; offen ist nur, ob am Schluss eine Sonderzeile steht. Bliebe dieser Auftrag
                                // dafür stehen, gälte der Assistent so lange als sprechend und überhörte jede
                                // Frage ohne Anrede. Den Rest liest deshalb ein eigener kleiner Leser ([drain]).
                                drain(run, trigger)
                                events = null
                            }
                            is Event.Decided -> when (val d = step.decision) {
                                is Decision.Skip -> skipped(d.reason.ifEmpty { "ohne Angabe" })
                                is Decision.Say -> {
                                    // Kopfzeile vergessen: Dann gilt die Antwort nur, wenn er den Assistenten direkt angesprochen hat.
                                    if (d.offProtocol && !trigger.named) { skipped("format"); break }
                                    cap = WORD_CAPS[d.length] ?: WORD_CAPS.getValue("mittel")
                                    // Höflichkeit: Im Gespräch mit anderen erst abwarten, ob ein Mensch antwortet.
                                    when (val verdict = holdVerdict(trigger)) {
                                        is Hold.Go -> {
                                            say = d
                                            speak(gate = null)
                                            open(d)
                                        }
                                        is Hold.No -> { skipped(verdict.reason); break }
                                        is Hold.Wait -> {
                                            say = d
                                            speak(gate = CompletableDeferred())
                                            hold = async { quietUntil(trigger, verdict.untilMs) }
                                        }
                                    }
                                }
                            }
                            is Event.Sentence -> {
                                if (say == null) continue
                                val n = Heuristics.words(step.text).size
                                if (capped || (words > 0 && words + n > cap)) {
                                    // Längendeckel im Code: Das Modell hält sich meist an seine Länge, verlassen darf
                                    // man sich nicht darauf. Gesprochen wird ab hier nichts mehr. Der Aufruf läuft aber
                                    // zu Ende: Eine Sonderzeile (MEMO, MUTE) steht hinter dem Sprechtext und ginge sonst
                                    // verloren, obwohl die Bestätigung dazu schon zu hören war.
                                    if (!capped) {
                                        capped = true
                                        sentences.close()
                                    }
                                    continue
                                }
                                words += n
                                if (answer.isNotEmpty()) answer.append(' ')
                                answer.append(step.text)
                                if (audible) state.update { it.copy(answer = answer.toString()) }
                                // Sicherheitsnetz: Ziffern, Einheiten, Abkürzungen und Markdown ausschreiben, bevor die
                                // Stimme sie liest. Anzeige und Verlauf behalten den Text, wie das Modell ihn schrieb.
                                speakable(step.text)?.let {
                                    voiced.append(it).append(' ')
                                    sentences.send(it)
                                }
                            }
                            is Event.Direct -> if (say != null) {
                                if (audible) directive(step.directive, trigger) else waiting += step.directive
                            }
                            // Bricht der Aufruf hinter dem Deckel ab, fehlt nichts, was noch zu sprechen wäre. Als
                            // Fehler gemeldet, schnitte das den Satz ab, der gerade zu hören ist.
                            is Event.Failed -> if (!capped) throw step.error
                        }
                    }
                } finally {
                    sentences.close()
                    hold?.cancel()
                    // Jeder Weg hier heraus muss die Schranke entscheiden, sonst wartet die Sprachausgabe ewig.
                    // complete() wirkt nur beim ersten Mal: War sie schon offen, bleibt sie offen.
                    release?.complete(false)
                }
                speech?.join()
                // "SAY kurz" und dann nichts: Es wurde nichts gesagt, also darf die Anzeige das auch nicht behaupten.
                if (audible && answer.isBlank()) skipped("leere antwort")
            }
        } finally {
            // Auch eine abgebrochene Antwort gehört in den Verlauf: Nachfragen beziehen sich darauf.
            // Aber nur, wenn sie zu hören war.
            if (heard.get() && answer.isNotBlank()) {
                buffer.add(answer.toString(), Voice.ALFRED, clock())
                // Das eigene Echo erkennt man am Wortlaut, und der hat zwei Fassungen: Geschrieben hat das Modell
                // vielleicht "3 km", zu hören war "drei Kilometer". Die Transkription schreibt ein Echo mal so
                // und mal so zurück ("drei Kilometer und 250 Meter"), also zählen die Wörter beider Fassungen.
                lastAnswerWords = (Heuristics.words(answer.toString()) + Heuristics.words(voiced.toString())).toSet()
                lastSpokeAtMs = clock()
            }
        }
    }

    private fun skipped(reason: String) = state.update { it.copy(lastDecision = "übersprungen: $reason") }

    /**
     * Liest den Rest eines Aufrufs zu Ende, dessen Antwort schon verklungen ist ([Faded]). Gebraucht wird davon
     * nur noch eine Sonderzeile (MEMO, MUTE): Sie steht ganz am Schluss, und die Bestätigung dazu ("Notiert")
     * war schon zu hören. Alles andere wird überlesen, auch ein Abbruch: Gesprochen ist bereits.
     *
     * Das läuft neben der Pipeline her und hält sie nicht auf. Der Auftrag, zu dem der Aufruf gehört, endet
     * sofort; die nächste Äußerung wird behandelt wie nach jeder anderen Antwort. Für die Sonderzeile gilt
     * dieselbe Schranke wie sonst: Ausgeführt wird sie nur, wenn die Stimme der Äußerung als die des Trägers
     * gesichert war ([directive]).
     */
    private fun drain(run: Run, trigger: Trigger) {
        run.handedOver = true
        scope.launch {
            try {
                for (event in run.events) if (event is Event.Direct) directive(event.directive, trigger)
            } finally {
                run.end()
            }
        }
    }

    /**
     * Der Satz in der Form, die die Stimme lesen soll, oder null, wenn nichts Sprechbares übrig bleibt.
     * Geht beim Umschreiben etwas schief, wird der Satz unverändert gesprochen: Das Sicherheitsnetz darf nie
     * selbst der Grund sein, dass eine Antwort ausfällt. (LinkageError deckt den Fall ab, dass Android eines
     * der Suchmuster nicht kennt; das zeigt sich dort erst beim ersten Gebrauch.)
     */
    private fun speakable(text: String): String? {
        val clean = try {
            SpokenText.clean(text)
        } catch (e: Exception) {
            text
        } catch (e: LinkageError) {
            text
        }
        return clean.takeIf { it.isNotBlank() }
    }

    /**
     * Die Höflichkeitspause. Menschen antworten einander nach etwa 0,2 s; bleibt es eine Sekunde still, ist
     * Hilfe willkommen. Beginnt in dieser Sekunde jemand aus dem Gespräch zu antworten, schweigt der Assistent.
     *
     * - Die Sekunde zählt ab dem Ende der Sprache ([Trigger.quietSinceMs]), nicht ab dem Eintreffen der
     *   fertigen Äußerung. Die Transkription meldet sie erst nach ihrer eigenen Stille-Frist; die wäre sonst
     *   doppelt gewartet.
     * - Nach dem Eintreffen wird trotzdem mindestens [HOLD_FLOOR_MS] gewartet: Dass jemand antwortet, zeigt
     *   sich erst an einem Zwischenstand mit zwei Inhaltswörtern, also mit etwas Verzug.
     * - Gewartet wird nur in Gesellschaft, und dazu zählt nur, wer zum Gespräch gehören kann: nicht der
     *   Assistent, nicht der Träger selbst, nicht was der Ordner als abseits markiert hat und nicht ferne
     *   Stimmen (Fernseher, Nachbartisch). "?" zählt mit, weil dort jemand am Tisch gemeint sein kann.
     * - Läuft die Pause ab, während gerade jemand hörbar spricht, wartet die Antwort bis eine volle Sekunde
     *   nach dem Eintreffen ([someoneStarting]). Zu hören ist ein Mensch früher, als sein Text ankommt.
     * - Die Sprachausgabe arbeitet in der Pause schon (siehe [consume]); gewartet wird nur mit dem Ton.
     */
    private fun holdVerdict(trigger: Trigger): Hold {
        if (trigger.named) return Hold.Go
        val line = trigger.line
        val now = clock()
        // Veraltet: Wer vier Sekunden nach der Frage erst loslegt, stört mehr, als er hilft.
        if (now - line.atMs > STALE_MS) return Hold.No("zu spät")
        val inCompany = line.voice == Voice.NAH || buffer.snapshot(now).any {
            it.id != line.id && !it.aside && it.voice != Voice.ALFRED && it.voice != Voice.ICH && it.voice != Voice.FERN &&
                line.atMs - it.atMs < COMPANY_MS
        }
        if (!inCompany) return Hold.Go
        if (humanSpokeAtMs > line.atMs) return Hold.No("mensch antwortet")
        val until = maxOf(trigger.quietSinceMs + HOLD_MS, line.atMs + HOLD_FLOOR_MS)
        // Ist die Pause schon um, darf gleich gesprochen werden, außer es setzt gerade jemand an: Dann
        // entscheidet [quietUntil], das dieselbe Verlängerung kennt.
        return if (now >= until && !(now < line.atMs + HOLD_MS && someoneStarting())) Hold.Go else Hold.Wait(until)
    }

    /**
     * Wartet die Höflichkeitspause ab.
     *
     * @return true, wenn bis zum Ende niemand aus dem Gespräch zu antworten begonnen hat.
     */
    private suspend fun quietUntil(trigger: Trigger, untilMs: Long): Boolean {
        val askedAt = trigger.line.atMs
        // Länger als eine volle Sekunde nach dem Eintreffen der fertigen Äußerung wird nie gewartet.
        val latest = askedAt + HOLD_MS
        var until = untilMs
        while (true) {
            if (humanSpokeAtMs > askedAt) return false
            if (clock() >= until) {
                if (until >= latest || !someoneStarting()) return true
                until = latest
            }
            delay(HOLD_POLL_MS)
        }
    }

    /**
     * Spricht seit der fertigen Äußerung gerade jemand, ohne dass sein Text schon da ist?
     *
     * Dass ein Mensch antwortet, erkennt die Pipeline am Text: an einem Zwischenstand mit zwei Inhaltswörtern
     * ([humanSpokeAtMs]). Der kommt spät, erst wenn die Wörter gesprochen und transkribiert sind. Die Stimme
     * selbst ist früher da. Der Stimm-Messer wird mit jeder fertigen Äußerung geleert; hat er seither wieder
     * Sprache gesammelt, die nicht fern klingt, hat jemand angesetzt. Dasselbe gilt, wenn die Transkription
     * schon ein erstes Wort gemeldet hat und die Stimme nicht messbar ist.
     *
     * Dann lohnt es, auf den Text zu warten, statt dem Menschen ins Wort zu fallen. Das kostet nur Zeit, wenn
     * wirklich jemand zu hören ist. Kommt kein Inhalt ("Ähm ..."), wird nach der vollen Sekunde gesprochen.
     */
    private fun someoneStarting(): Boolean {
        val f = meter.peekSummary()
        if (f == null && !utteranceOpen) return false
        return classifier.classify(f, record = false) != Voice.FERN
    }

    private fun isEcho(words: List<String>): Boolean {
        val spoken = lastAnswerWords
        if (words.size < 3 || spoken.isEmpty()) return false
        val w = words.toSet()
        return w.intersect(spoken).size.toDouble() / w.size >= 0.6
    }

    private fun directive(d: Directive, trigger: Trigger) {
        // Merken und Stummschalten wirken über den Moment hinaus. Das darf nur der Träger selbst auslösen,
        // nicht der Tischnachbar und nicht der Fernseher, die "Alfred, merk dir ..." sagen.
        if (!trigger.trusted) {
            state.update { it.copy(lastDecision = "abgelehnt: Stimme nicht als die des Trägers erkannt") }
            return
        }
        when (d) {
            is Directive.Memo -> {
                // Eine Zeile, begrenzte Länge: Notizen landen später im System-Prompt.
                val note = d.text.replace('\n', ' ').trim().take(MAX_NOTE_CHARS)
                if (note.isEmpty()) return
                sessionNotes += note
                env.saveNote(note)
            }
            is Directive.Mute -> {
                mutedUntilMs = clock() + d.minutes * 60_000L
                // Die Oberfläche rechnet mit der Wanduhr ("stumm bis ..."), also bekommt sie eine Uhrzeit.
                val until = wallClock() + d.minutes * 60_000L
                state.update { it.copy(mutedUntilMs = until) }
            }
        }
    }

    private fun unmute() {
        mutedUntilMs = 0
        state.update { it.copy(mutedUntilMs = 0) }
    }

    // ------------------------------------------------------------------------------------------
    // Ordner

    /**
     * Alle paar Äußerungen sortiert das schnelle Modell im Hintergrund die Mitschrift:
     * Was gehört zu seinem Gespräch, was nicht? Das Ergebnis (Szene, "abseits"-Vermerke) steht dem
     * nächsten Antwort-Aufruf zur Verfügung, ohne ihn aufzuhalten.
     */
    private fun scheduleTidy(now: Long) {
        linesSinceTidy++
        if (linesSinceTidy < TIDY_EVERY_LINES || now - lastTidyAtMs < TIDY_MIN_GAP_MS || tidyJob?.isActive == true) return
        linesSinceTidy = 0
        lastTidyAtMs = now
        lastCallAtMs = now
        val lines = buffer.snapshot(now).filter { it.voice != Voice.ALFRED }
        val before = scene
        tidyJob = scope.launch(io) {
            try {
                val result = runInterruptible { brain.tidy(lines, before, config.mic, now) }
                result.scene?.let { s ->
                    scene = s
                    state.update { it.copy(scene = s) }
                }
                buffer.markAside(result.aside, lines.map { it.id }.toSet())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Der Ordner ist Kür. Fällt er aus, entscheidet das Antwort-Modell eben ohne seine Vorarbeit.
            }
        }
    }

    private fun rememberSegment(segment: Int, lineId: Long) = synchronized(segmentLines) {
        segmentLines[segment] = lineId
        if (segmentLines.size > 200) segmentLines.remove(segmentLines.keys.first())
    }

    private fun display(line: Line) = "${ConversationBuffer.who(line)}: ${line.text}"

    private companion object {
        const val SPECULATE_AT = 0.7
        const val FOLLOW_UP_MS = 20_000L
        const val TIDY_EVERY_LINES = 4
        const val TIDY_MIN_GAP_MS = 12_000L
        const val WAKE_WINDOW_MS = 12_000L
        const val MAX_NOTE_CHARS = 240
        /** Leiser als das kann die eigene Stimme am Kopfhörer-Mikro kaum sein (dBFS). */
        const val OWN_MIN_DB = -35.0
        const val HOLD_MS = 1_000L
        /** So lange wird nach dem Eintreffen der fertigen Äußerung mindestens gewartet. */
        const val HOLD_FLOOR_MS = 350L
        const val HOLD_POLL_MS = 50L
        /** Wer in dieser Spanne vor der Frage etwas gesagt hat, gilt als Gesellschaft. */
        const val COMPANY_MS = 30_000L
        const val STALE_MS = 4_000L
        const val BARGE_IN_WORDS = 5
        /** So viel Audio vor dem ersten Zwischenstand zählt noch zur Äußerung (die Transkription hinkt hinterher). */
        const val UTTERANCE_LEAD_MS = 3_000
        const val MS_PER_WORD = 450
        /**
         * So lange nach dem letzten Laut trifft die fertige Äußerung spätestens ein: die Stille-Frist der
         * Transkription (0,5 s bis 1 s je nach Anbieter) plus Netz, großzügig gerechnet.
         */
        const val COMMIT_LAG_MS = 2_000
        /** Nach so langer Funkstille gilt die Verbindung zu Claude als eingeschlafen (abgebaut wird sie nach fünf Minuten). */
        const val WARM_AFTER_IDLE_MS = 240_000L
        /** Der Zwischenspeicher für den großen Prompt hält eine Stunde; kurz davor wird er aufgefrischt. */
        const val WARM_CACHE_AFTER_MS = 50 * 60_000L
        // Ränge für den Anspruch auf eine Antwort, siehe [claimOf].
        const val CLAIM_ASIDE = 0
        const val CLAIM_REMARK = 1
        const val CLAIM_FAR_QUESTION = 2
        const val CLAIM_OWN_REMARK = 3
        const val CLAIM_QUESTION = 4
        const val CLAIM_FAR_CALL = 5
        const val CLAIM_DIRECT = 6
        /** Obergrenzen in Wörtern je Länge; großzügiger als die Vorgabe im Prompt, nur als Sicherung. */
        val WORD_CAPS = mapOf("kurz" to 22, "mittel" to 50, "lang" to 120)
    }
}

/** Eine Uhr in Millisekunden, die bei null beginnt und nie springt (siehe die beiden Uhren in [Pipeline]). */
internal fun monotonicClock(): () -> Long {
    val start = System.nanoTime()
    return { (System.nanoTime() - start) / 1_000_000 }
}
