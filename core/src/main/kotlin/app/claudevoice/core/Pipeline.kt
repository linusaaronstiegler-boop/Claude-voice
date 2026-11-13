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
import kotlinx.coroutines.currentCoroutineContext
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
import java.util.concurrent.atomic.AtomicLong
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
 *    Auftrag ohne Ton endet ([revive]). Wurde gesprochen, verfällt sie, mit einer Ausnahme: Was der Träger
 *    hinter seiner eigenen Frage hergeschoben hat ("Und China?"), wird danach wie eine Nachfrage vorgelegt.
 *    Verbessert er sich ausdrücklich ("Nein, ich meine den Watzmann"), löst das seine Frage sofort ab.
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
        /**
         * Seit wann die Äußerung auf ihre Antwort wartet. Daran misst die Höflichkeitspause, ob die Antwort noch
         * rechtzeitig kommt und ob seither ein Mensch geantwortet hat. Fast immer ist das der Moment, in dem die
         * Äußerung fertig war. Nur eine nachgereichte Anschlussfrage ([asFollowUp]) wartet erst, seit die
         * Antwort davor verklungen ist: Vorher konnte niemand auf sie eingehen, auch der Assistent nicht.
         */
        val askedAtMs: Long = line.atMs,
        /** Warum die Stimme nicht als gesichert gilt, falls sich das genauer sagen lässt ([Heard.doubt]). */
        val doubt: String? = null,
    )

    /**
     * Ergebnis der Stimm-Einschätzung für eine Äußerung.
     * [doubt]: Die Stimme klang nach dem Träger, gilt aber aus einem benennbaren Grund nicht als gesichert (zu
     * leise angekommen, leiser als das Stimmprofil, eine zweite Stimme in der Äußerung, die Sprecher-Erkennung
     * widerspricht). Dann steht hier der Satz für die Anzeige, falls Merken oder Stummschalten abgelehnt wird
     * ([directive]). Er enthält höchstens Messwerte, kein Audio, und bleibt im Arbeitsspeicher.
     * [measure]: die Messwerte dieser Äußerung für die Anzeige ([UiState.measures]).
     */
    private class Heard(val voice: Voice, val trusted: Boolean, val doubt: String?, val measure: VoiceMeasure)

    /**
     * Wie die Äußerung zum Assistenten steht: nach dem Weckwort, bloßer Ruf, direkte Anrede.
     * [awaited]: Das Fenster nach dem Weckwort ist offen, und die Stimme ließ sich nicht messen ([addressOf]).
     */
    private class Address(val woken: Boolean, val callOnly: Boolean, val named: Boolean, val awaited: Boolean)

    /**
     * Wie dringend die Äußerung ist und ob diese Stimme überhaupt etwas auslösen darf.
     * [correction]: Der Träger verbessert seine eigene, noch wartende Frage. [addOn]: ein verkürzter Nachsatz
     * hinter ihr, den nur diese Lage zum Anlass macht (beides in [urgencyOf]).
     */
    private class Urgency(
        val hot: Boolean, val wanted: Boolean, val permitted: Boolean,
        val correction: Boolean = false, val addOn: Boolean = false,
    )

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
    /**
     * Eine Äußerung, die [pending] nicht verdrängen durfte: von mehreren die mit dem höchsten Anspruch, bei
     * Gleichstand die jüngste. Sie kommt dran, falls jene ohne Ton endet, oder als Nachfrage, falls der
     * Träger sie hinter seiner eigenen Frage hergeschoben hat ([revive]).
     */
    private val parked = AtomicReference<Trigger?>(null)
    /**
     * Schützt die Übergabe "wer ist als Nächstes dran": Annehmen oder Zurückstellen in [onCommitted] und das
     * Nachrücken in [revive] laufen auf verschiedenen Threads und dürfen sich nicht überkreuzen.
     */
    private val turnLock = Any()
    /** Zählt jedes "Stopp". Damit erwischt ein Stopp auch eine Äußerung, die gerade zwischen zwei Aufträgen hängt. */
    private val stopEpoch = AtomicInteger()
    /** Zählt jede direkte Anrede des Trägers selbst. Sie hebt eine Ruhe auf, auch eine, die erst noch eingetragen wird ([drain]). */
    private val addressEpoch = AtomicInteger()
    /**
     * Schützt das Ein- und Austragen der Ruhe. Der Nachleser ([drain]) prüft "hat er mich inzwischen wieder
     * angesprochen?" und trägt dann die Ruhe ein; die Anrede zählt hoch und hebt die Ruhe auf. Das sind auf
     * beiden Seiten zwei Schritte auf verschiedenen Threads. Ohne Sperre konnte "Alfred, du darfst wieder"
     * genau zwischen Prüfung und Eintrag landen, und die eben aufgehobene Ruhe galt doch.
     */
    private val muteLock = Any()

    // "Conflated": Der Kanal hält nur die jeweils neueste Äußerung. Was verdrängt wird, bevor sein Auftrag
    // beginnt, braucht auch seinen Modell-Aufruf nicht mehr.
    private val triggers = Channel<Trigger>(Channel.CONFLATED, onUndeliveredElement = { drop(it) })

    /** true, solange eine Antwort zu hören ist (nicht schon, wenn sie nur vorbereitet wird). */
    @Volatile private var speaking = false
    @Volatile private var current: Job? = null
    /** Wann der Assistent zuletzt gesprochen hat; null = in dieser Sitzung noch nicht. */
    @Volatile private var lastSpokeAtMs: Long? = null
    @Volatile private var mutedUntilMs = 0L
    /**
     * Der Leser, der hinter einer zugesagten Ruhe noch auf die Sonderzeile MUTE wartet ([drain]); sonst null.
     * Solange er läuft, gilt die Ruhe schon ([mutedAt]).
     */
    @Volatile private var quietReader: Job? = null
    @Volatile private var scene: String? = null
    /** Zeitpunkt der letzten Äußerung, mit der ein Mensch im Gespräch selbst zu antworten begann. */
    @Volatile private var humanSpokeAtMs = 0L
    /** Dasselbe, aber ohne den Träger: wann zuletzt jemand ANDERES aus dem Gespräch inhaltlich etwas gesagt hat ([answeredSince]). */
    @Volatile private var othersSpokeAtMs = 0L
    @Volatile private var lastAnswerWords: Set<String> = emptySet()
    /** Zeitpunkt, zu dem das lokale Weckwort erkannt wurde; null, sobald die Bitte danach verarbeitet ist. */
    @Volatile private var wokeAtMs: Long? = null
    /** Wie der Ruf nach dem Weckwort ("Hey Jarvis") klang; null, solange er nicht in der Mitschrift steht ([ownsWake]). */
    @Volatile private var wakeCaller: Voice? = null
    /** true, sobald die Transkription für die laufende Äußerung den ersten Text gemeldet hat. */
    @Volatile private var utteranceOpen = false
    /** true, solange eine fertige Äußerung gerade verarbeitet wird ([onCommitted]). Siehe [wearerGoesOn]. */
    @Volatile private var committing = false
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
        // Für die Anzeige der Messwerte: mit welchem Stimmprofil diese Sitzung vergleicht.
        state.update { it.copy(voiceProfile = classifier.profile) }
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
        val heard = voiceOf(meter.peekSpan(), speakerId, text, learn = false)
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
            humanSpoke(heard.voice, now)
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
        // Solange die Äußerung verarbeitet wird, gilt der Träger noch als redend ([wearerGoesOn]). Erst wenn
        // feststeht, was sie war (neue Frage, Nachsatz, nichts), darf eine wartende Antwort hörbar werden.
        committing = true
        try {
            commit(text, speakerId, segment)
        } finally {
            committing = false
        }
    }

    private fun commit(text: String, speakerId: String?, segment: Int?) {
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
        val span = meter.takeSpan()
        val features = span?.whole
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
        // Die Stimme des Trägers ist nie das Echo: Was aus dem Kopfhörer zurückschallt, kommt am Mikro viel
        // leiser an als er selbst. Seine Rückfrage "Wirklich drei Kilometer?" wiederholt die Wörter der
        // Antwort und ginge sonst als Echo verloren. Nur ohne Kopfhörer (echoRisk) trennt die Lautstärke nicht.
        val ownVoice = !config.echoRisk && classifier.classify(features, record = false) == Voice.ICH
        if (!ownVoice && (speaking || spokeWithin(now, 3_000)) && isEcho(Heuristics.words(t))) {
            takeSpeculation()?.cancel()
            return
        }
        val heard = voiceOf(span, speakerId, t, learn = true)
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
        // Die Messwerte stehen in einer zweiten Liste an gleicher Stelle wie die Zeile. Beide wachsen und
        // schrumpfen hier gemeinsam, an keiner anderen Stelle.
        state.update {
            it.copy(
                partial = "",
                transcript = (it.transcript + display(line)).takeLast(TRANSCRIPT_SHOWN),
                measures = (it.measures + heard.measure).takeLast(TRANSCRIPT_SHOWN),
                voiceProfile = classifier.profile,
            )
        }

        if (voice != Voice.FERN && Heuristics.isRealContent(t)) humanSpoke(voice, now)
        val spec = takeSpeculation()
        if (Heuristics.isStopCommand(t, names) || (speaking && (voice == Voice.ICH || ownStart) && Heuristics.isBareStop(t))) {
            spec?.cancel()
            stopSpeaking()
            return
        }
        scheduleTidy(now)

        // Nur der Träger selbst hebt eine Stummschaltung auf ("Alfred, du darfst wieder"). Dass sein Name
        // irgendwo fällt, ein Fremder ihn ruft oder ein Film "Jarvis" sagt, reicht dafür nicht.
        //
        // Die eine Ausnahme: ein bloßer Dank ("Danke, Alfred."), solange Ruhe gilt oder gerade zugesagt ist. Wer
        // sich für "Sehr wohl, Sir." bedankt, will die eben erbetene Ruhe behalten. Früher hob der Dank sie auf
        // (oder verhinderte hinter einer langen Antwort, dass sie überhaupt eingetragen wurde), und zu hören war
        // "Gern, Sir.". Der Dank bleibt jetzt ohne Antwort, und die Ruhe gilt weiter.
        var thanked = false
        val muted = synchronized(muteLock) {
            val m = mutedAt(now)
            thanked = m && Heuristics.isMereThanks(t, names)
            if (address.named && heard.trusted && !thanked) {
                addressEpoch.incrementAndGet()
                if (m) unmute()
            }
            m
        }
        if (thanked) {
            spec?.cancel()
            state.update { it.copy(lastDecision = "stumm: Dank gehört, die Ruhe gilt weiter") }
            return
        }
        // Der Ton nach dem Weckwort war schon die Bestätigung. Ein "Ja, Sir?" würde jetzt über die
        // eigentliche Frage sprechen. Der Ruf steht in der Mitschrift, löst aber nichts aus.
        if (address.callOnly) {
            spec?.cancel()
            return
        }
        val urgency = urgencyOf(t, now, heard, address, muted)
        // Stumm, beim Namen genannt, aber die Stimme ließ sich nicht dem Träger zuordnen: Die Ruhe bleibt (nur
        // er hebt sie auf). Früher geschah dabei gar nichts Sichtbares, und die Anzeige versprach weiter "Beim
        // Namen genannt, antwortet er wieder". Jetzt steht dort wenigstens, warum nichts passiert ist.
        if (muted && address.named && !urgency.permitted) {
            state.update { it.copy(lastDecision = "stumm: Anrede gehört, Stimme nicht als die des Trägers erkannt") }
        }
        // Während der Assistent zu hören ist, unterbricht ihn nur eine direkte Anrede.
        if (!urgency.wanted || !urgency.permitted || (speaking && !address.named)) {
            spec?.cancel()
            return
        }
        val standing = claimOf(address.named, urgency, voice, t, now, waiting = true)
        // Was der Aufruf über die Lage wissen muss (Uhrzeit, läuft Musik?, Mitschrift), wird VOR der Sperre
        // geholt. "Läuft Musik?" ist auf dem Handy eine Anfrage an das System, und seit auch der Stopp-Knopf
        // diese Sperre nimmt ([stopSpeaking]), hinge er sonst so lange fest. Aus demselben Grund wird ein
        // verworfener Vorgriff erst nach der Sperre abgebrochen (das schließt eine Netzverbindung).
        val turn = turnFor(line, address.woken, heard.trusted)
        var discarded: Run? = null
        try {
            accept(t, now, line, heard, address, urgency, standing, quietSince, spec, turn) { discarded = it }
        } finally {
            discarded?.cancel()
        }
    }

    /** Der letzte Schritt von [commit]: annehmen, zurückstellen oder verwerfen, unter [turnLock]. */
    private fun accept(
        t: String, now: Long, line: Line, heard: Heard, address: Address, urgency: Urgency, standing: Int,
        quietSince: Long, spec: Run?, turn: Turn, discard: (Run) -> Unit,
    ) {
        val voice = heard.voice
        synchronized(turnLock) {
            // Ein Nachsatz, den nur die wartende Frage zum Anlass macht, kann ihr nichts wegnehmen: Er steht
            // hinter ihr zurück. Ist sie in diesem Augenblick schon erledigt, bleibt er, was er ohne sie wäre.
            if (urgency.addOn && pending.get() == null) {
                spec?.let(discard)
                return
            }
            // Wartet schon eine Antwort, verdrängt sie nur, wer mindestens so viel Anspruch hat. Wer weniger hat,
            // steht zurück: Sein Aufruf startet erst, falls die wartende Antwort ohne Ton endet ([revive]).
            if (urgency.addOn || !mayReplace(claimOf(address.named, urgency, voice, t, now, waiting = false))) {
                spec?.let(discard)
                // Zurückstehen kann nur eine Äußerung. Es bleibt die mit dem höheren Anspruch, bei Gleichstand
                // die neuere. Früher gewann immer die neuere: Eine Quizfrage aus dem Fernseher warf den Nachsatz
                // des Trägers aus dem Platz, obwohl sie ihm nach [claimOf] nichts wegnehmen darf.
                val waiting = parked.get()
                if (waiting == null || standing >= waiting.claim || outdated(waiting, now)) {
                    parked.set(
                        Trigger(
                            line, hot = urgency.hot, named = address.named, woken = address.woken, trusted = heard.trusted,
                            run = null, quietSinceMs = quietSince, epoch = stopEpoch.get(), claim = standing,
                            doubt = heard.doubt,
                        )
                    )
                }
                return
            }
            val interrupting = speaking
            if (interrupting) stopSpeaking()

            val basis = Basis(normalize(t), voice, heard.trusted, address.woken, line.speaker, gated = !urgency.hot, spokeAt = lastSpokeAtMs)
            // Wird gerade eine hörbare Antwort unterbrochen, kommt ihr gesprochener Teil erst noch in die
            // Mitschrift. Dann startet der Aufruf in [handle], nachdem der alte Auftrag aufgeräumt hat.
            val adopted = spec?.takeIf { !interrupting && it.basis == basis }
            if (adopted == null) spec?.let(discard)
            val run = adopted ?: if (interrupting) null else startRun(turn, basis)
            val trigger = Trigger(
                line, hot = urgency.hot, named = address.named, woken = address.woken, trusted = heard.trusted,
                run = run, quietSinceMs = quietSince, epoch = stopEpoch.get(), claim = standing,
                doubt = heard.doubt,
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
        // Unter derselben Sperre wie das Annehmen und Zurückstellen ([onCommitted]): Der Platz wird hier schon
        // frei, nicht erst, wenn der gestoppte Auftrag aufgeräumt hat. Der Lautsprecher braucht dafür einen
        // Augenblick, und was in dieser Spanne gesagt wurde, stellte sich sonst hinter einem Auftrag an, der
        // niemanden mehr nachholt: Es blieb für immer liegen. Was nach einem Stopp gesagt wird, wird neu
        // beurteilt; die Schleife in [start] wartet trotzdem, bis der alte Auftrag fertig aufgeräumt hat.
        //
        // Auch `speaking = false` und der Griff nach dem laufenden Auftrag gehören unter die Sperre. Früher
        // standen beide dahinter, als letzte Schritte nach dem Anhalten des Lautsprechers. Brauchte das Anhalten
        // einen Moment und begann in dieser Zeit schon die nächste Antwort, schrieb das alte Stopp danach
        // `speaking = false` über sie: Sie war zu hören, galt aber als nicht hörbar, und das bloße "Stopp" des
        // Trägers blieb ohne Wirkung. Unter der Sperre kann das nicht mehr passieren: Der gestoppte Auftrag
        // kommt nicht mehr durch [consume] (sein Platz in `pending` ist weg), und das `true` eines späteren
        // Auftrags wird nicht mehr überschrieben. Abgebrochen wird genau der Auftrag, der beim Stopp lief.
        val stopped = synchronized(turnLock) {
            stopEpoch.incrementAndGet()
            parked.set(null) // auch was zurückstand, rückt nach einem Stopp nicht mehr nach
            pending.set(null)
            speaking = false
            current
        }
        stopped?.cancel()
        speaker.stop()
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
        return Address(
            woken = mine && !callOnly, callOnly = callOnly, named = mine || Heuristics.mentionsAny(text, names),
            // Nach dem Weckwort wird eine Bitte erwartet. Löst die Äußerung es nicht ein (der Ruf klang nach dem
            // Träger, sie selbst ließ sich nicht messen), ist sie keine Anrede und trägt keinen Hinweis. Dem
            // Modell wird sie trotzdem vorgelegt, auch ohne Frageform ([urgencyOf]).
            // Das gilt nur für eine Stimme, die sich nicht messen ließ ("?"). Früher galt es für jede Stimme aus
            // der Nähe: Nach "Hey Jarvis" ging dann zwölf Sekunden lang jeder Satz des Tischnachbarn ("Prost!",
            // "Ja genau.") ohne Vorprüfung ans starke Modell. Wessen Stimme als NAH gemessen ist, der ist nicht
            // der Träger, der eben gerufen hat; seine Sätze gehen den gewöhnlichen Weg.
            awaited = wakeOpen && !callOnly && voice == Voice.UNKLAR,
        )
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
     *
     * Der normale Weg hat im offenen Fenster eine Besonderheit ([Address.awaited]): Eine Äußerung, deren Stimme
     * sich nicht messen ließ, geht auch dann ans Modell, wenn der Vorfilter in ihr keinen Anlass sieht. Die Bitte nach dem Weckwort
     * ist oft keine Frage ("Uhrzeit bitte", "Timer zehn Minuten"), und die Stimme des Trägers lässt sich bei
     * so kurzen Sätzen nicht immer messen. Sie gälte sonst als Füllsel und bliebe ohne jede Reaktion. Das
     * Modell liest sie ohne den Hinweis auf das Weckwort, sieht aber den Ruf direkt darüber, und für den Ton
     * gilt die Höflichkeitspause wie bei jeder Äußerung ohne Anrede. Gemessene fremde Stimmen (NAH, FERN)
     * bleiben ausgenommen.
     */
    private fun ownsWake(voice: Voice): Boolean {
        val caller = wakeCaller
        return when {
            caller == Voice.ICH -> voice == Voice.ICH
            voice != Voice.FERN -> true
            else -> caller == Voice.FERN || (caller == null && config.mic == MicMode.HANDY)
        }
    }

    /**
     * Zwei Dinge sieht der Vorfilter nicht, weil sie vom Zusammenhang abhängen. Beide gelten nur, solange die
     * eigene Frage des Trägers noch auf ihre Antwort wartet und nichts zu hören ist ([behindOwnQuestion]):
     *  - Die Korrektur ("Nein, ich meine den Watzmann."): für sich genommen ein kurzer Satz ohne Frageform,
     *    den niemand ein Modell sähe. Hinter der eigenen Frage ist sie die neue Frage und löst die alte ab.
     *  - Der verkürzte Nachsatz ("Und China", "und in Fuß"): Als Anlass erkennt der Vorfilter ihn nur mit
     *    Fragezeichen und großem Hauptwort, und beides liefert die Transkription nicht verlässlich. Er wird
     *    zurückgestellt und nach der Antwort vorgelegt ([asFollowUp]), so wie derselbe Satz eine Sekunde
     *    nach der Antwort ohnehin als Nachfrage gälte.
     */
    private fun urgencyOf(text: String, now: Long, heard: Heard, address: Address, muted: Boolean): Urgency {
        val heat = Heuristics.heat(text, names)
        val behindOwn = !muted && behindOwnQuestion(heard.voice)
        val correction = behindOwn && heard.voice == Voice.ICH && pending.get()?.line?.voice == Voice.ICH &&
            Heuristics.isCorrection(text)
        val hot = address.named || address.awaited || correction || isFollowUp(now, heard.voice, text) ||
            heat == Heuristics.Heat.HOT
        val addOn = !hot && heat != Heuristics.Heat.MAYBE && behindOwn && Heuristics.isShortFollowUp(text)
        val wanted = hot || heat == Heuristics.Heat.MAYBE || addOn
        val permitted = if (muted) address.named && heard.trusted else address.named || allowed(heard.voice, hot)
        return Urgency(hot, wanted, permitted, correction, addOn)
    }

    /**
     * Wartet gerade eine Frage des Trägers auf ihre Antwort (nicht bloß eine beiläufige Aussage, über die noch
     * die Vorprüfung urteilt), und kann auch diese Stimme er sein? Großzügig gemessen ([mayBeWearer]), denn
     * zunächst geht es nur darum, ob eine Äußerung zurückgestellt wird. Die Korrektur verlangt mehr ([urgencyOf]).
     */
    private fun behindOwnQuestion(voice: Voice): Boolean =
        !speaking && mayBeWearer(voice) && pending.get()?.let { it.hot && mayBeWearer(it.line.voice) } == true

    /**
     * @param learn true nur für die fertige Äußerung: Dann zählt sie in die Statistik und darf das Stimmprofil
     *              lernen. Ein Zwischenstand (false) rechnet gleich, lernt und speichert aber nichts.
     */
    private fun voiceOf(span: VoiceSpan?, speakerId: String?, text: String, learn: Boolean): Heard {
        val f = span?.whole
        val raw = classifier.classify(f, record = learn)
        var voice = raw
        // "Gesichert" heißt: Die Akustik selbst hält die Stimme für die des Trägers. Nur dann darf eine
        // Äußerung etwas Dauerhaftes bewirken (merken, stummschalten, Stimmprofil lernen).
        //  - Mit Stimmprofil ist das die Einschätzung ICH: so laut wie das Profil und in seiner Stimmlage.
        //  - Ohne Profil heißt ICH nur "die lauteste Stimme dieser Sitzung". Die erste Stimme überhaupt bekäme
        //    es geschenkt, auch der Fernseher. Als Etikett in der Mitschrift ist diese Schätzung gut genug, als
        //    Nachweis nicht. Dazu muss die Stimme auch für sich genommen so laut sein, wie nur die eigene am
        //    Kopfhörer-Mikro ankommt (OWN_MIN_DB). Am Handy-Mikro trennt die Lautstärke nicht: Dort gibt es
        //    ohne Profil keinen Nachweis.
        // Früher genügte jeweils eine der beiden Bedingungen. Die Stimme vom Tischnachbarn erfüllte mal die
        // eine, mal die andere: Sie durfte merken und stummschalten, und ihr erster Ruf wurde als Profil des
        // Trägers gespeichert, der danach ausgesperrt war.
        //
        // Am Kopfhörer-Mikro gilt die Untergrenze OWN_MIN_DB inzwischen immer, auch mit Profil. Mit Profil hieß
        // ICH bisher nur "höchstens acht Dezibel leiser als das Profil". War das Profil aus leisen Rufen
        // entstanden, reichte dieses Band bis in den Bereich, in dem der Tischnachbar ankommt: Mit ähnlicher
        // Stimmlage durfte er merken und stummschalten, und sein Ruf wurde ins Profil gemittelt.
        val known = classifier.profile != null
        val airpods = config.mic == MicMode.AIRPODS
        val loudEnough = f != null && f.levelDb >= OWN_MIN_DB
        val wholeOwn = f != null && raw == Voice.ICH && if (airpods) loudEnough else known
        // Dieselbe Prüfung noch einmal für den Anfang und für das Ende der Äußerung, jedes für sich
        // ([VoiceSpan]). Die Messung über das Ganze ließ sich von einer zweiten Stimme huckepack nehmen: Der
        // Träger lacht in den Ruf des Tischnachbarn, oder der Nachbar hängt "Alfred, merk dir ..." ohne Pause an
        // einen Satz des Trägers an, und das Ganze sah nach dem Träger aus. Der Nachbar durfte dann merken und
        // stummschalten, und seine Stimmlage kam ins Stimmprofil. Gehört ein Rand einer anderen Stimme, gilt
        // die Äußerung jetzt nicht mehr als gesichert. Das Etikett in der Mitschrift bleibt, wie es war.
        fun own(part: VoiceFeatures) =
            classifier.classify(part, record = false) == Voice.ICH && if (airpods) part.levelDb >= OWN_MIN_DB else known
        var trusted = wholeOwn && span != null && own(span.head) && own(span.tail)
        // Für die Anzeige: Die Stimme klang nach ihm, und es lässt sich sagen, woran es lag.
        var doubt: String? = when {
            f == null || trusted -> null
            wholeOwn -> "abgelehnt: Anfang oder Ende der Äußerung klang nicht nach dem Träger (eine zweite Stimme?). Bitte allein wiederholen."
            airpods && !loudEnough && (raw == Voice.ICH || classifier.fitsPitch(f)) ->
                "abgelehnt: eigene Stimme zu leise gemessen (%.0f dBFS, nötig %.0f). Näher am Mikro oder lauter sprechen."
                    .format(java.util.Locale.ROOT, f.levelDb, OWN_MIN_DB)
            airpods && raw != Voice.ICH && classifier.fitsPitch(f) ->
                "abgelehnt: Stimme passt in der Stimmlage, kam aber leiser an als im Stimmprofil (%.0f dBFS, Profil %.0f). Lauter sprechen oder die Stimme neu einlernen."
                    .format(java.util.Locale.ROOT, f.levelDb, classifier.profile?.levelDb ?: 0.0)
            else -> null
        }
        // Selbstkalibrierung: Wer den Satz mit dem Namen des Assistenten beginnt, ist meist der Träger.
        // Gelernt wird daraus aber nur, wenn die Akustik die Stimme schon für seine hält. Sonst würde der
        // Erste, der den Namen ruft (ein Freund, ein Film), als "Träger" gespeichert. Gemeint ist der Name im
        // Wortlaut: Das Weckwort allein reicht dafür nicht, es sagt nichts darüber, wessen Stimme hier zu hören ist.
        val addressed = Heuristics.words(text).take(3).any { Heuristics.mentionsAny(it, names) }
        // Gespeichert wird nur, wenn sich das Profil dabei geändert hat. Ein Profil, das feststeht (in den
        // Einstellungen eingelernt oder aus genug Proben entstanden), ändert sich nicht mehr und wird hier
        // deshalb auch nie überschrieben ([VoiceClassifier.learnOwn]).
        if (addressed && trusted && learn && classifier.learnOwn(f)) {
            classifier.profile?.let(env::saveVoiceProfile)
        }
        // Für die Abstimmung, welche Sprecher-ID dem Träger gehört, zählt ICH nur mit Nachweis. Sonst wählte
        // die bloße Schätzung "lauteste Stimme" einen Träger, und der gälte danach als gesichert.
        if (learn) speakers.observe(speakerId, if (voice == Voice.ICH && !trusted) Voice.UNKLAR else voice)
        val wearer = speakers.wearer()
        if (speakerId != null && wearer != null) {
            // Die Diarisierung kennt den Träger bereits. Vertrauen kann sie nur entziehen, nie verleihen: Ihre
            // IDs sind vorläufig, und früher galt eine ferne Stimme (der Fernseher), der sie die ID des Trägers
            // gab, als gesichert und durfte merken und stummschalten. Gesichert ist nur, was die Akustik selbst
            // dem Träger zuordnet UND was die Diarisierung nicht einem anderen zuschreibt.
            //
            // Auch beim Etikett in der Mitschrift hilft sie nur noch dort, wo die Akustik unsicher ist. Früher
            // überstimmte sie die Akustik in beide Richtungen: Legte der Anbieter zwei Menschen unter eine ID
            // zusammen, stand vor den Sätzen des Tischnachbarn "ICH"; gab er dem Träger eine neue ID, stand vor
            // seinen eigenen "NAH".
            if (speakerId == wearer) {
                // Unter der ID des Trägers: ICH, außer die Akustik hat klar eine andere Stimme gemessen (fern,
                // oder eine Stimmlage, die nicht zum Stimmprofil passt). "Nur leiser als sonst" widerspricht nicht.
                val otherPitch = f?.pitchHz != null && classifier.profile?.pitchHz != null && !classifier.fitsPitch(f)
                if (raw != Voice.FERN && !otherPitch) voice = Voice.ICH
            } else {
                // Unter einer anderen ID: Was die Akustik selbst dem Träger zuordnet, bleibt in der Mitschrift
                // ICH; gesichert ist es nicht, bis auch die Diarisierung ihn unter dieser ID kennt (zwei
                // Äußerungen, [SpeakerMap.wearer]). Die bloße Schätzung "lauteste Stimme" wird zu NAH.
                if (trusted) doubt = "abgelehnt: Die Sprecher-Erkennung ordnet die Stimme noch jemand anderem zu. Bitte noch einmal sagen."
                else if (voice == Voice.ICH) voice = Voice.NAH
                trusted = false
            }
        }
        return Heard(voice, trusted, doubt, VoiceMeasure(f?.levelDb, f?.pitchHz, voice.label, trusted))
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
     * Vier Einzelheiten, die sich aus Fehlern ergeben haben:
     *  - Die ausdrückliche Korrektur des Trägers ("Nein, ich meine den Watzmann.") zählt wie seine Frage, auch
     *    wenn der Vorfilter in dem Satz keine erkennt ([urgencyOf]).
     *  - "?" heißt "zu wenig Signal" und trifft gerade leise und kurze fremde Stimmen. Die eigene Stimme ist
     *    am Kopfhörer-Mikro die, die sich immer messen lässt. "?" zählt darum wie die Runde, nicht wie der Träger.
     *    Der Preis: Wo der Träger selbst nicht als ICH messbar ist (Handy-Mikro ohne Stimmprofil, lauter
     *    Dauerlärm), verdrängt seine Korrektur ohne Anrede die eigene direkte Bitte nicht. Die wird dann
     *    beantwortet, wie sie gestellt war; wer sich korrigieren will, nennt den Namen noch einmal.
     *  - Ein Einwurf ohne Inhalt sieht mit seinem Fragezeichen wie eine Frage aus, ist aber kein Anliegen.
     *    ("Echt?", "Was?" und "Oder?" kommen gar nicht mehr bis hierher: Die verwirft schon der Vorfilter.)
     *    Was als Einwurf gilt, hängt davon ab, ob die Äußerung neu dazukommt oder selbst wartet ([isAside]).
     *    Eine Ausnahme: Hat der Assistent eben gesprochen, ist das "Warum?" des Trägers eine Nachfrage zu
     *    dieser Antwort. Wartet es, steht es so hoch wie seine beiläufige Aussage (Rang 3): Die Quizfrage
     *    aus dem Fernseher nimmt ihm die Antwort dann nicht weg, eine Frage aus der Runde schon.
     *  - Wartet die Frage des Trägers selbst und trägt keine Anrede ([waiting]), steht sie nur auf Rang 4. Eine
     *    inhaltliche Äußerung aus der Runde danach heißt: Das Gespräch geht weiter. Die Höflichkeitspause
     *    zöge die Antwort dann ohnehin zurück ([holdVerdict]); so beginnt der neue Aufruf wenigstens gleich.
     *
     * Vertrauen (merken, stummschalten) hängt von alledem nicht ab, nur davon, wer als Nächstes dran ist.
     */
    private fun claimOf(named: Boolean, urgency: Urgency, voice: Voice, text: String, now: Long, waiting: Boolean): Int =
        claimOf(named, urgency.hot, urgency.correction, voice, text, now, waiting)

    private fun claimOf(
        named: Boolean, hot: Boolean, correction: Boolean, voice: Voice, text: String, now: Long, waiting: Boolean,
    ): Int = when {
        named -> if (voice == Voice.FERN) CLAIM_FAR_CALL else CLAIM_DIRECT
        correction -> if (waiting) CLAIM_QUESTION else CLAIM_DIRECT
        hot && isAside(text, waiting) ->
            if (waiting && voice == Voice.ICH && spokeWithin(now, FOLLOW_UP_MS)) CLAIM_OWN_REMARK else CLAIM_ASIDE
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
        spokeWithin(now, FOLLOW_UP_MS) && mayBeWearer(voice) && Heuristics.words(text).size >= 2

    /**
     * Kann diese Stimme der Träger sein? ICH, oder "?", wenn die Akustik nichts hergab (am Handy-Mikro ist das
     * oft er selbst). NAH und FERN sind andere. Das ist der großzügige Maßstab der Nachfrage: Er entscheidet
     * nur, ob das Modell gefragt wird. Vertrauen (merken, stummschalten) hängt allein an `trusted`.
     */
    private fun mayBeWearer(voice: Voice): Boolean = voice == Voice.ICH || voice == Voice.UNKLAR

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
        if (mutedAt(now)) return // stumm: kein Vorgriff, die fertige Äußerung entscheidet
        val urgency = urgencyOf(text, now, heard, address, muted = false)
        // Ein Nachsatz hinter der eigenen Frage steht ohnehin zurück: Für ihn lohnt kein Vorgriff.
        if (!urgency.wanted || !urgency.permitted || urgency.addOn) return
        // Ein Vorgriff für eine Äußerung, die die wartende Antwort gar nicht verdrängen dürfte, wäre verschenkt.
        if (!mayReplace(claimOf(address.named, urgency, heard.voice, text, now, waiting = false))) return
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
        // Wann dieser erste Ton begann; -1, solange nichts zu hören war ([revive]).
        val toneAt = AtomicLong(-1)
        try {
            // Der Aufruf lief schon los, als die Äußerung ankam. Neu gestartet wird er nur, wenn der Assistent
            // seither etwas gesagt hat: Dann fehlt dem laufenden Aufruf diese Antwort in der Mitschrift.
            if (run == null || run.basis.spokeAt != lastSpokeAtMs) {
                run?.cancel()
                // Für das Modell ist "jetzt" der Moment, seit dem die Äußerung auf ihre Antwort wartet. Bei einer
                // nachgereichten Anschlussfrage liegt er hinter der Antwort davor ([asFollowUp]); sonst ändert sich nichts.
                val line = trigger.line.let { if (it.atMs == trigger.askedAtMs) it else it.copy(atMs = trigger.askedAtMs) }
                val basis = Basis(
                    normalize(line.text), line.voice, trigger.trusted, trigger.woken, line.speaker,
                    gated = !trigger.hot, spokeAt = lastSpokeAtMs,
                )
                run = startRun(turnFor(line, trigger.woken, trigger.trusted), basis)
            }
            consume(run, trigger, heard, toneAt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Ein Auftrag, der schon abgebrochen ist (Stopp, eine neuere Äußerung, der Dienst endet), meldet
            // keinen Fehler mehr. Beim Abbrechen wird der Sprachausgabe die Verbindung unter den Füßen
            // geschlossen, und was sie dabei wirft, ist keine CancellationException, sondern etwa eine
            // IOException. Die stand dann als "Antwort fehlgeschlagen" in der Anzeige, obwohl nur geschah, was
            // gewollt war. ensureActive() wirft bei einem abgebrochenen Auftrag stattdessen den Abbruch weiter.
            currentCoroutineContext().ensureActive()
            state.update { it.copy(error = "Antwort fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}") }
        } finally {
            run?.cancel()
            speaking = false
            state.update { it.copy(speaking = false) }
        }
        // Bis hierher kommt nur ein Auftrag, der von selbst zu Ende ging (nicht verdrängt, nicht gestoppt).
        revive(trigger, spoke = heard.get(), toneAtMs = toneAt.get())
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
     * Unverändert nachgerückt wird nur, wenn nichts zu hören war (das Modell schweigt, ein Mensch hat selbst
     * geantwortet, der Aufruf ist gescheitert). Wurde gesprochen ([spoke]), verfällt die zurückgestellte
     * Äußerung: Der Assistent hat eben geredet, und was währenddessen gesagt wurde, steht in der Mitschrift
     * für die nächste Frage. Die eine Ausnahme davon beschreibt [asFollowUp].
     *
     * @param toneAtMs wann der erste Ton der Antwort begann (nur von Belang, wenn gesprochen wurde).
     */
    private fun revive(done: Trigger, spoke: Boolean, toneAtMs: Long) {
        synchronized(turnLock) {
            // Nur wenn dieser Auftrag noch der aktuelle ist. Hat inzwischen eine neuere Äußerung übernommen,
            // hat sie auch die zurückgestellte abgelöst.
            if (pending.get() !== done) return
            val now = clock()
            val waited = parked.getAndSet(null)?.takeIf {
                // Kein "Stopp" dazwischen, nicht veraltet, und inzwischen ist auch keine Ruhe zugesagt. Das gilt
                // auf beiden Wegen: Auch eine Bestätigung ohne Ton ("SAY kurz" und gleich "MUTE: 10") schaltet stumm.
                // "Veraltet" misst sich hinter einer gesprochenen Antwort anders ([lapsedBehindAnswer]).
                !(if (spoke) lapsedBehindAnswer(it, toneAtMs, now) else outdated(it, now)) && !silenced(it)
            }
            val next = if (spoke) waited?.let { asFollowUp(it, done, now) } else waited
            // Auch ohne Nachrücker wird hier schon freigegeben, nicht erst, wenn die Coroutine ganz beendet ist:
            // Eine Äußerung, die in genau diesem Augenblick eintrifft, soll nicht hinter einem Auftrag
            // zurückstehen, der niemanden mehr nachholt.
            pending.set(next)
            if (next != null) triggers.trySend(next)
        }
    }

    /**
     * Die Anschlussfrage, die zu früh kam.
     *
     * Der Träger fragt "Wie viele Zeitzonen hat Russland?" und schiebt gleich "Und China?" nach, bevor die erste
     * Antwort zu hören ist. Für sich genommen ist der Nachsatz eine beiläufige Aussage: Er steht hinter der
     * eigenen Frage zurück ([claimOf]). Früher verfiel er, sobald die Antwort gesprochen war, und über "Und
     * China?" urteilte nie jemand. Dieselben zwei Wörter eine Sekunde später, nach der Antwort, gelten dagegen
     * als Nachfrage ([isFollowUp]) und werden beantwortet.
     *
     * Deshalb wird [waited] jetzt, wo die Antwort auf [answered] verklungen ist, genauso vorgelegt wie eine
     * Nachfrage direkt nach der Antwort: mit der Antwort im Verlauf. Für das Modell ist die Lage damit
     * dieselbe wie bei einer echten Nachfrage, nur dass der Träger sie schon gestellt hatte.
     *
     * Ohne Vorprüfung geht dabei nur, was nach einer verkürzten Anschlussfrage aussieht
     * ([Heuristics.isShortFollowUp]: "Und China?", "und in Fuß", "Wirklich so viele?") oder den Namen nennt.
     * Alles andere, was der Träger in der Wartezeit gesagt hat ("Kannst du mir mal das Salz geben?"), geht
     * erst durch die billige Vorprüfung, so wie es ohne die Antwort davor gegangen wäre. Sonst bekäme das
     * starke Modell jeden Satz an den Tischnachbarn als Nachfrage direkt unter seiner eigenen Antwort
     * vorgelegt, und gemessen spricht es in solchen Fällen etwa jedes zwanzigste Mal, obwohl es schweigen soll.
     *
     * Die Bedingungen, alle zusammen:
     *  - Die Antwort war wirklich zu hören. Dafür sorgt der Aufrufer ([revive] mit `spoke`); nur dann steht
     *    sie auch im Verlauf, auf den sich die Nachfrage bezieht.
     *  - Kein "Stopp" dazwischen und nicht veraltet: Das hat [revive] schon geprüft ([lapsedBehindAnswer]).
     *    Die Zeit, in der die erste Antwort zu hören war, zählt dabei nicht gegen den Nachsatz.
     *  - Es war die eigene Frage des Trägers, hinter der er zurückstand, und der Nachsatz erfüllt, was auch
     *    eine Nachfrage erfüllen muss: seine Stimme, mindestens zwei Wörter, im Fenster nach der Antwort. Für
     *    NAH und FERN gilt das nie. Was der Tischnachbar dazwischen sagt, verfällt wie bisher.
     *  - Die Antwort hat nicht gerade Ruhe zugesagt. Der Nachsatz wurde angenommen, als noch nicht stumm
     *    geschaltet war; käme er jetzt erst, bliebe er ohne Anrede ungehört. Dabei bleibt es ([revive]).
     *  - Seit dem Nachsatz hat niemand anderes aus der Runde inhaltlich etwas gesagt. Sonst hat ein Mensch
     *    schon darauf geantwortet ("Nur eine."), oder das Gespräch ist weitergegangen. Die Höflichkeitspause
     *    sähe das nicht mehr: Sie zählt für die Nachfrage erst ab jetzt.
     *  - Höchstens eine: Zurückstehen kann immer nur eine Äußerung.
     *
     * @return die Äußerung als Nachfrage, oder null, wenn sie verfällt.
     */
    private fun asFollowUp(waited: Trigger, answered: Trigger, now: Long): Trigger? {
        val line = waited.line
        if (!mayBeWearer(answered.line.voice) || !isFollowUp(now, line.voice, line.text)) return null
        if (othersSpokeAtMs > line.atMs) return null
        val hot = waited.named || Heuristics.isShortFollowUp(line.text)
        return Trigger(
            line, hot = hot, named = waited.named, woken = waited.woken, trusted = waited.trusted, run = null,
            quietSinceMs = waited.quietSinceMs, epoch = waited.epoch,
            claim = claimOf(waited.named, hot, correction = false, line.voice, line.text, now, waiting = true),
            // Die Höflichkeitspause und ihre Frist zählen ab jetzt: Vorher war die Antwort davor noch zu hören.
            askedAtMs = now,
            doubt = waited.doubt,
        )
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
    private suspend fun consume(run: Run, trigger: Trigger, heard: AtomicBoolean, toneAt: AtomicLong) {
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
        // Hat der Träger um Ruhe gebeten, steht die Sonderzeile dazu (MUTE) noch aus, solange das Modell schreibt.
        // Die zugesagte Ruhe gilt aber ab der Zusage: Übernimmt der Nachleser den Rest, gilt sie so lange schon
        // vorläufig ([drain]). Sonst bekäme in der Lücke bis zur Sonderzeile die nächste Frage noch eine Antwort.
        val quietAsked = trigger.trusted && Heuristics.asksForQuiet(line.text)
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
                            val t = clock()
                            toneAt.compareAndSet(-1, t)
                            heard.set(true)
                            state.update { it.copy(latencyMs = t - trigger.quietSinceMs) }
                        }
                    }
                }

                /**
                 * Macht die Antwort hörbar. Das darf nur der Auftrag, der noch dran ist.
                 *
                 * Eine neuere Äußerung löst diesen Auftrag ab, indem [onCommitted] sie als [pending] einträgt;
                 * abgebrochen wird er erst danach, von der Schleife in [start]. Dazwischen liegt ein
                 * Augenblick, und trifft die Antwort genau dann ein, wäre die alte Frage beantwortet worden,
                 * obwohl der Träger sich schon verbessert hat (so gesehen unter Last, mit echten Threads).
                 * Dasselbe gilt nach einem "Stopp". Geprüft wird unter derselben Sperre, unter der die neue
                 * Äußerung angenommen wird: Entweder ist diese Antwort dann schon zu hören (`speaking`) und
                 * wird als hörbare Antwort unterbrochen, oder sie wird es nicht mehr.
                 *
                 * @return false, wenn dieser Auftrag nicht mehr dran ist; dann endet er ohne Ton.
                 */
                fun open(d: Decision.Say): Boolean {
                    synchronized(turnLock) {
                        if (pending.get() !== trigger) return false
                        audible = true
                        speaking = true
                    }
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
                    return true
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
                                when {
                                    !step.go || d == null -> { skipped("mensch antwortet"); break }
                                    // In der Pause kann die Ruhe eingetragen worden sein (siehe unten).
                                    silenced(trigger) -> { skipped("stumm"); break }
                                    !open(d) -> break
                                }
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
                                drain(run, trigger, quiet = quietAsked)
                                events = null
                            }
                            is Event.Decided -> when (val d = step.decision) {
                                is Decision.Skip -> skipped(d.reason.ifEmpty { "ohne Angabe" })
                                is Decision.Say -> {
                                    // Kopfzeile vergessen. Gemessen war das in 31 von 1480 Antworten so, und jede davon
                                    // war eine echte Antwort: Sie wird gesprochen wie jede andere, auch durch dieselbe
                                    // Höflichkeitspause. Verworfen wird nur noch, was nach einem Kommentar zur
                                    // Entscheidung aussieht ([Headerless]), und auch das nicht nach direkter Anrede.
                                    if (d.offProtocol && d.commentary && !trigger.named) { skipped("format"); break }
                                    // Stumm wird nicht nur beim Annehmen geprüft, sondern auch hier, wo der Ton
                                    // freigegeben wird: Die Ruhe kann eingetragen worden sein, während diese
                                    // Antwort entstand (ein Nachleser liest MUTE erst am Ende, siehe [drain]).
                                    if (silenced(trigger)) { skipped("stumm"); break }
                                    cap = WORD_CAPS[d.length] ?: WORD_CAPS.getValue(defaultLength(d, trigger))
                                    // Höflichkeit: Im Gespräch mit anderen erst abwarten, ob ein Mensch antwortet.
                                    when (val verdict = holdVerdict(trigger)) {
                                        is Hold.Go -> {
                                            say = d
                                            if (!open(d)) break
                                            speak(gate = null)
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
     * Welcher Längendeckel gilt, wenn hinter SAY keine bekannte Länge stand?
     *
     * Mit Kopfzeile wie bisher "mittel". Ganz ohne Kopfzeile hat das Modell auch keine Länge gewählt, und
     * dann richtet sich der Deckel danach, wie sicher die Antwort erwünscht ist: nach direkter Anrede
     * "mittel", sonst "kurz". Im Gespräch mit anderen ist kurz ohnehin der Normalfall, und sollte doch einmal
     * ein Kommentar statt einer Antwort durchrutschen, ist er nach einem Satz vorbei. (Der erste Satz geht
     * immer ganz hinaus, der Deckel greift erst beim zweiten.)
     */
    private fun defaultLength(d: Decision.Say, trigger: Trigger): String =
        if (d.offProtocol && !trigger.named) "kurz" else "mittel"

    /**
     * Liest den Rest eines Aufrufs zu Ende, dessen Antwort schon verklungen ist ([Faded]). Gebraucht wird davon
     * nur noch eine Sonderzeile (MEMO, MUTE): Sie steht ganz am Schluss, und die Bestätigung dazu ("Notiert")
     * war schon zu hören. Alles andere wird überlesen. Reißt der Aufruf ab, bevor eine Sonderzeile kam, steht
     * das als Meldung in der Anzeige.
     *
     * Das läuft neben der Pipeline her und hält sie nicht auf. Der Auftrag, zu dem der Aufruf gehört, endet
     * sofort; die nächste Äußerung wird behandelt wie nach jeder anderen Antwort. Für die Sonderzeile gilt
     * dieselbe Schranke wie sonst: Ausgeführt wird sie nur, wenn die Stimme der Äußerung als die des Trägers
     * gesichert war ([directive]).
     *
     * Die Lücke bis zur Sonderzeile MUTE:
     *  - War die Äußerung eine Bitte um Ruhe ([quiet]), gilt die Ruhe schon, solange dieser Leser läuft
     *    ([mutedAt]). "Sehr wohl, Sir." war zu hören, und was der Träger danach ohne Anrede fragt, bekäme
     *    sonst noch eine Antwort. Der Auftrag selbst bleibt dafür nicht stehen: Er gälte als sprechend, und
     *    redete der Träger dann einfach weiter, bräche das den Aufruf ab, bevor MUTE gelesen ist.
     *  - War ihr das nicht anzusehen und schaltet das Modell trotzdem stumm, gilt die Ruhe ab dem Moment, in dem
     *    die Sonderzeile eintrifft. Eine Antwort, die dann erst fertig wird, bleibt ungesprochen ([silenced]);
     *    eine, die schon zu hören war, lässt sich nicht zurücknehmen.
     *  - Spricht der Träger den Assistenten in der Lücke wieder an ("Alfred, du darfst wieder"), wird die Ruhe
     *    gar nicht mehr eingetragen: Eine Anrede hebt sie auf, ob die Sonderzeile schon gelesen war oder nicht.
     */
    private fun drain(run: Run, trigger: Trigger, quiet: Boolean) {
        run.handedOver = true
        val addressed = addressEpoch.get()
        // LAZY, damit der Leser als "Ruhe gilt schon" eingetragen ist, bevor er anläuft und bevor der Auftrag endet.
        val reader = scope.launch(start = CoroutineStart.LAZY) {
            try {
                var done = false
                for (event in run.events) {
                    // Der Aufruf ist abgerissen (Netz weg, Zeitüberschreitung), bevor eine Sonderzeile kam. Zu
                    // hören war vielleicht "Notiert, Sir." oder "Sehr wohl, Sir.", eingetragen ist nichts. Ohne
                    // Deckel steht derselbe Abriss als "Antwort fehlgeschlagen" in der Anzeige ([handle]); hier
                    // blieb er früher stumm. Der Ton ist an dieser Stelle schon verklungen, die Meldung schneidet
                    // also nichts ab. (Ein Abbruch, den die Pipeline selbst wollte, kommt hier gar nicht an.)
                    if (event is Event.Failed && !done) {
                        state.update {
                            it.copy(error = "Antwort unvollständig: Verbindung abgebrochen. Sollte etwas gemerkt oder Ruhe gehalten werden, ist das nicht eingetragen.")
                        }
                    }
                    if (event !is Event.Direct) continue
                    done = true
                    if (event.directive is Directive.Mute) {
                        // Der Träger hat den Assistenten inzwischen wieder angesprochen: Die Ruhe gilt nicht mehr.
                        // Prüfung und Eintrag unter einer Sperre, damit seine Anrede nicht dazwischen passt.
                        synchronized(muteLock) {
                            if (addressEpoch.get() == addressed) directive(event.directive, trigger)
                        }
                        continue
                    }
                    // Scheitert das Speichern (Speicher voll, Datei gesperrt), wird das eine Meldung in der
                    // Anzeige, wie auf dem gewöhnlichen Weg in [handle]. Ungefangen verließe die Ausnahme diese
                    // Coroutine, und auf Android beendet das die ganze App: Zu hören war "Notiert", gespeichert
                    // wäre nichts, und zugehört würde auch nicht mehr.
                    try {
                        directive(event.directive, trigger)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        state.update { it.copy(error = "Merken fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}") }
                    }
                }
            } finally {
                run.end()
            }
        }
        if (quiet) quietReader = reader
        reader.start()
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
     * - Redet der Träger nach seiner eigenen Frage selbst weiter, nimmt ihm das die Antwort nicht weg
     *   ([answeredSince]). Der Ton wartet dann aber, bis sein Satz zu Ende ist ([wearerGoesOn]).
     * - Die Sprachausgabe arbeitet in der Pause schon (siehe [consume]); gewartet wird nur mit dem Ton.
     */
    private fun holdVerdict(trigger: Trigger): Hold {
        if (trigger.named) return Hold.Go
        val line = trigger.line
        // Seit wann die Äußerung wartet: fast immer, seit sie fertig war ([Trigger.askedAtMs]).
        val askedAt = trigger.askedAtMs
        val now = clock()
        // Veraltet: Wer vier Sekunden nach der Frage erst loslegt, stört mehr, als er hilft.
        if (now - askedAt > STALE_MS) return Hold.No("zu spät")
        val inCompany = line.voice == Voice.NAH || buffer.snapshot(now).any {
            it.id != line.id && !it.aside && it.voice != Voice.ALFRED && it.voice != Voice.ICH && it.voice != Voice.FERN &&
                line.atMs - it.atMs < COMPANY_MS
        }
        if (!inCompany) return Hold.Go
        if (answeredSince(trigger)) return Hold.No("mensch antwortet")
        val until = maxOf(trigger.quietSinceMs + HOLD_MS, askedAt + HOLD_FLOOR_MS)
        // Ist die Pause schon um, darf gleich gesprochen werden, außer es setzt gerade jemand an: Dann
        // entscheidet [quietUntil], das dieselbe Verlängerung kennt.
        val free = now >= until && !(now < askedAt + HOLD_MS && someoneStarting()) && !wearerGoesOn(trigger)
        return if (free) Hold.Go else Hold.Wait(until)
    }

    /**
     * Hat seit der Äußerung ein Mensch aus dem Gespräch selbst zu antworten begonnen?
     *
     * Auf die Frage des Trägers antworten nur die anderen. Redet er selbst weiter ("Und China?", "Mal sehen,
     * was er sagt."), hat ihm damit niemand geantwortet. Früher zog sein eigener Nachsatz die Antwort auf
     * seine erste Frage zurück, sobald jemand am Tisch saß: Zu hören war dann höchstens die zweite Antwort.
     * Fragt jemand anderes, zählt wie bisher jede Stimme aus dem Gespräch, auch die des Trägers.
     */
    private fun answeredSince(trigger: Trigger): Boolean =
        (if (trigger.line.voice == Voice.ICH) othersSpokeAtMs else humanSpokeAtMs) > trigger.askedAtMs

    /** Jemand aus dem Gespräch hat inhaltlich etwas gesagt ([answeredSince]). */
    private fun humanSpoke(voice: Voice, now: Long) {
        humanSpokeAtMs = now
        if (voice != Voice.ICH) othersSpokeAtMs = now
    }

    /**
     * Gilt für diese Äußerung gerade eine zugesagte Ruhe? Nur die direkte Anrede des Trägers selbst geht
     * durch eine Stummschaltung hindurch (sie hebt sie auf, siehe [onCommitted]).
     */
    private fun silenced(trigger: Trigger): Boolean = mutedAt(clock()) && !(trigger.named && trigger.trusted)

    /**
     * Ist der Assistent zu diesem Zeitpunkt stumm geschaltet? Entweder ist die Ruhe eingetragen, oder sie ist
     * zugesagt und ihre Sonderzeile wird gerade noch gelesen ([quietReader]).
     */
    private fun mutedAt(now: Long): Boolean = now < mutedUntilMs || quietReader?.isActive == true

    /** Rückt diese zurückgestellte Äußerung nicht mehr nach, weil ein "Stopp" kam oder sie zu alt ist? */
    private fun outdated(waiting: Trigger, now: Long): Boolean =
        waiting.epoch != stopEpoch.get() ||
            // Veraltet: Ohne Anrede würde die Antwort jetzt ohnehin nicht mehr gesprochen ([holdVerdict]).
            !(waiting.named || now - waiting.line.atMs <= STALE_MS)

    /**
     * Dieselbe Frage für eine Äußerung, die hinter einer GESPROCHENEN Antwort zurückstand ([asFollowUp]).
     *
     * Die Frist von vier Sekunden zählte früher durch, auch während die erste Antwort zu hören war. Hinter
     * "Elf." kam der Nachsatz "Und China?" dran, hinter einem gewöhnlichen Satz von dreizehn Wörtern (rund
     * fünf Sekunden Sprache) war er schon zu alt. Die Zeit des Zuhörens zählt jetzt nicht mehr gegen ihn:
     *  - Er darf höchstens STALE_MS alt gewesen sein, als der erste Ton der Antwort begann ([toneAtMs]).
     *    Bis dahin hat der Assistent ihn warten lassen; danach hat der Träger zugehört.
     *  - Insgesamt darf seit dem Nachsatz nicht mehr Zeit vergangen sein als das Fenster für Nachfragen
     *    (FOLLOW_UP_MS). Nach einer sehr langen Antwort weiß niemand mehr, worauf er sich bezog.
     *  - Ein "Stopp" dazwischen lässt ihn verfallen wie bisher.
     * Eine direkte Anrede verfällt durch Warten vor dem ersten Ton nicht (wie in [outdated]), nach dem ganzen
     * Fenster aber auch.
     */
    private fun lapsedBehindAnswer(waiting: Trigger, toneAtMs: Long, now: Long): Boolean =
        waiting.epoch != stopEpoch.get() ||
            !(waiting.named || toneAtMs - waiting.line.atMs <= STALE_MS) ||
            now - waiting.line.atMs > FOLLOW_UP_MS

    /**
     * Wartet die Höflichkeitspause ab.
     *
     * @return true, wenn bis zum Ende niemand aus dem Gespräch zu antworten begonnen hat.
     */
    private suspend fun quietUntil(trigger: Trigger, untilMs: Long): Boolean {
        val askedAt = trigger.askedAtMs
        // Länger als eine volle Sekunde nach dem Eintreffen der fertigen Äußerung wird nie gewartet.
        val latest = askedAt + HOLD_MS
        var until = untilMs
        while (true) {
            if (answeredSince(trigger)) return false
            val now = clock()
            if (now >= until) {
                when {
                    until < latest && someoneStarting() -> until = latest
                    !wearerGoesOn(trigger) -> return true
                    // Er redet seit vier Sekunden: Die Antwort käme jetzt zu spät ([holdVerdict]).
                    now - askedAt > STALE_MS -> return false
                }
            }
            delay(HOLD_POLL_MS)
        }
    }

    /**
     * Redet der Träger nach seiner eigenen Frage gerade selbst weiter?
     *
     * Sein Weiterreden zieht die Antwort nicht mehr zurück ([answeredSince]). Ohne diese Prüfung spräche der
     * Assistent dafür nach der Höflichkeitspause mitten in seinen Satz hinein und würde gleich wieder
     * unterbrochen (wer selbst weiterredet, bringt ihn zum Schweigen, siehe [onPartial]). Also wartet der Ton,
     * bis der Satz fertig ist. Dann zeigt sich auch, was er war: Eine neue Frage löst die alte ab, alles andere
     * steht hinter ihr zurück, und die Antwort ist zu hören.
     *
     * Gemessen wird großzügig ([bargeOwn]): Ein Irrtum kostet nur etwas Wartezeit.
     *
     * Als "er redet noch" gilt auch der Augenblick, in dem seine fertige Äußerung gerade verarbeitet wird
     * ([committing]). [onCommitted] meldet "Äußerung zu Ende" nämlich als Erstes und nimmt sie erst ein paar
     * Schritte später an. Fragte die Höflichkeitspause genau dazwischen nach, hieß es "er ist fertig", die
     * ALTE Antwort wurde hörbar, und seine neue Frage wurde verworfen, weil der Assistent ja gerade sprach.
     * So gesehen in etwa einem von hundert Versuchen. Der Preis: höchstens eine Abfrage (50 ms) mehr Wartezeit,
     * wenn während der Pause irgendeine Äußerung fertig wird.
     */
    private fun wearerGoesOn(trigger: Trigger): Boolean =
        trigger.line.voice == Voice.ICH && (committing || (utteranceOpen && bargeOwn))

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
            // Klang die Stimme nach dem Träger und scheiterte an etwas Benennbarem (zu leise, zweite Stimme),
            // sagt die Anzeige das dazu ([voiceOf]). Sonst erfährt ein Träger, dessen Kopfhörer-Mikro ihn leise
            // aufnimmt, nie, warum nichts gemerkt wird.
            state.update { it.copy(lastDecision = trigger.doubt ?: "abgelehnt: Stimme nicht als die des Trägers erkannt") }
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
        quietReader = null
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
        /** So viele Zeilen der Mitschrift (und ihre Messwerte) hält die Anzeige. */
        const val TRANSCRIPT_SHOWN = 60
        const val MAX_NOTE_CHARS = 240
        /**
         * Leiser als das ist die eigene Stimme am Kopfhörer-Mikro nicht (dBFS). Die Grenze gilt für das
         * Vertrauen immer, mit und ohne Stimmprofil ([voiceOf]), und sie liegt acht Dezibel unter dem
         * Erfahrungswert für normale Sprache direkt am Mikro (-22). Früher stand hier -35, mitten in dem
         * Bereich, in dem der Tischnachbar ankommt. Der Wert ist eine Schätzung: Auf einem echten Kopfhörer
         * ist er noch nicht nachgemessen.
         */
        const val OWN_MIN_DB = -30.0
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
