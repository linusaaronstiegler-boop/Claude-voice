package app.claudevoice.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

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
 *  - Vorgriff: Meldet die Transkription, dass der Satz wahrscheinlich zu Ende ist, startet der Aufruf
 *    schon, bevor die Äußerung offiziell fertig ist. Stimmt der fertige Text damit überein, ist die
 *    Antwort bereits unterwegs; sonst wird der Vorgriff verworfen.
 *  - Antworten werden satzweise gesprochen, während das Modell noch schreibt.
 */
class Pipeline(
    private val brain: Brain,
    private val speaker: Speaker,
    private val state: MutableStateFlow<UiState>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val config: Config,
    private val env: Env,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Config(
        val assistantName: String = "Alfred",
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

    /** Ein laufender Antwort-Aufruf. Seine Ergebnisse sammeln sich in [events], bis jemand sie abholt. */
    private class Run(val key: String, val voice: Voice, val events: Channel<Event>, val job: Job)

    private class Trigger(val line: Line, val hot: Boolean, val named: Boolean, val run: Run?)

    /** Signal "Modell hat SKIP gesagt, Rest nicht nötig". Ohne Stacktrace, weil es kein Fehler ist. */
    private class Skipped : RuntimeException(null, null, false, false)

    private val name = config.assistantName
    private val meter = VoiceMeter()
    private val classifier = VoiceClassifier(config.mic, config.voiceProfile)
    private val speakers = SpeakerMap()
    private val buffer = ConversationBuffer()
    private val sessionNotes = CopyOnWriteArrayList<String>()
    private val segmentLines = LinkedHashMap<Int, Long>()

    // "Conflated": Ist die Pipeline beschäftigt, zählt nur die jeweils neueste Äußerung.
    // So redet die App nie alten Fragen hinterher. Ein verdrängter Vorgriff wird dabei abgebrochen.
    private val triggers = Channel<Trigger>(Channel.CONFLATED, onUndeliveredElement = { it.run?.job?.cancel() })

    @Volatile private var speaking = false
    @Volatile private var current: Job? = null
    @Volatile private var lastSpokeAtMs = 0L
    @Volatile private var mutedUntilMs = 0L
    @Volatile private var scene: String? = null
    /** Zeitpunkt der letzten Äußerung, mit der ein Mensch im Gespräch selbst zu antworten begann. */
    @Volatile private var humanSpokeAtMs = 0L
    @Volatile private var lastAnswerWords: Set<String> = emptySet()
    private var speculation: Run? = null
    private var loop: Job? = null
    private var tidyJob: Job? = null
    private var linesSinceTidy = 0
    private var lastTidyAtMs = 0L

    fun start() {
        loop = scope.launch {
            for (trigger in triggers) {
                val job = launch { handle(trigger) }
                current = job
                job.join()
            }
        }
    }

    fun stop() {
        loop?.cancel()
        tidyJob?.cancel()
        synchronized(this) { speculation?.job?.cancel(); speculation = null }
        speaker.stop()
    }

    /** Rohes Mikrofon-Audio: wird nur vermessen (Lautstärke, Stimmlage), nicht gespeichert. */
    fun onAudio(pcm: ByteArray, length: Int) = meter.feed(pcm, length)

    /** Zwischenstand der laufenden Äußerung. */
    fun onPartial(text: String, turnProbability: Double?) {
        if (config.echoRisk && speaking) return
        state.update { it.copy(partial = text) }
        if (speaking) {
            // Ins Wort fallen: "Alfred, stopp" oder ein bloßes "stopp" beenden die Ausgabe sofort.
            // Redet der Träger selbst länger weiter, schweigt der Assistent ebenfalls, statt ihn zu übertönen.
            val w = Heuristics.words(text)
            val own = classifier.classify(meter.peekSummary(), record = false) == Voice.ICH
            if (Heuristics.isStopCommand(text, name) || (own && Heuristics.isBareStop(text)) ||
                (own && w.size >= BARGE_IN_WORDS && !Heuristics.mentionsName(text, name) && !isEcho(w))
            ) {
                stopSpeaking()
                return
            }
        } else if (Heuristics.isRealContent(text)) {
            humanSpokeAtMs = clock()
        }
        speculate(text, turnProbability)
    }

    /** Fertige Äußerung aus der Transkription. */
    fun onCommitted(text: String, speakerId: String?, segment: Int?) {
        val features = meter.takeSummary()
        val t = text.trim()
        if (t.isEmpty() || (config.echoRisk && speaking)) {
            takeSpeculation()?.job?.cancel()
            return
        }
        val now = clock()
        // Eigenes Echo: Schallt die Sprachausgabe ins Mikro zurück, steht der Antworttext plötzlich
        // in der Mitschrift. Was dem eben Gesprochenen stark ähnelt, wird verworfen.
        if ((speaking || now - lastSpokeAtMs < 3_000) && isEcho(Heuristics.words(t))) {
            takeSpeculation()?.job?.cancel()
            return
        }
        val named = Heuristics.mentionsName(t, name)
        val voice = voiceOf(features, speakerId, t, named, learn = true)
        val line = buffer.add(t, voice, now, speakers.label(speakerId)) ?: return
        if (segment != null) rememberSegment(segment, line.id)
        state.update { it.copy(partial = "", transcript = (it.transcript + display(line)).takeLast(60)) }

        if (Heuristics.isRealContent(t)) humanSpokeAtMs = now
        val spec = takeSpeculation()
        if (Heuristics.isStopCommand(t, name) || (speaking && voice == Voice.ICH && Heuristics.isBareStop(t))) {
            spec?.job?.cancel()
            stopSpeaking()
            return
        }
        scheduleTidy(now)

        // Wer ihn beim Namen ruft, hebt eine Stummschaltung auf ("Alfred, du darfst wieder").
        if (named && mutedUntilMs != 0L) unmute()
        val heat = Heuristics.heat(t, name)
        val followUp = isFollowUp(now, voice, t)
        val wanted = heat != Heuristics.Heat.COLD || followUp
        val permitted = named || (now >= mutedUntilMs && allowed(voice))
        // Während der Assistent spricht, unterbricht ihn nur eine direkte Anrede.
        if (!wanted || !permitted || (speaking && !named)) {
            spec?.job?.cancel()
            return
        }
        if (speaking) stopSpeaking()

        val adopted = spec?.takeIf { it.key == normalize(t) && it.voice == voice }
        if (adopted == null) spec?.job?.cancel()
        triggers.trySend(Trigger(line, hot = heat == Heuristics.Heat.HOT || followUp, named = named, run = adopted))
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

    /** Nach einem Neuverbinden vergibt der Anbieter Sprecher-IDs und Segmentnummern neu. */
    fun onTranscriberReset() {
        speakers.reset()
        synchronized(segmentLines) { segmentLines.clear() }
        takeSpeculation()?.job?.cancel()
    }

    /** Laufende Antwort abbrechen (Knopf in der App, Kopfhörer-Taste oder "Alfred, stopp"). */
    fun stopSpeaking() {
        current?.cancel()
        speaker.stop()
    }

    // ------------------------------------------------------------------------------------------
    // Wer spricht?

    private fun voiceOf(f: VoiceFeatures?, speakerId: String?, text: String, named: Boolean, learn: Boolean): Voice {
        var voice = classifier.classify(f, record = learn)
        if (learn) {
            // Selbstkalibrierung: Wer den Satz mit dem Namen des Assistenten beginnt, ist fast sicher der Träger.
            // Mit dessen Pegel und Stimmlage wird das Profil der eigenen Stimme nachgeführt.
            val addressed = named && Heuristics.words(text).take(3).any { Heuristics.mentionsName(it, name) }
            val plausible = classifier.profile == null || voice == Voice.ICH
            if (addressed && plausible && f != null) {
                classifier.learnOwn(f)
                classifier.profile?.let(env::saveVoiceProfile)
                voice = Voice.ICH
            }
            speakers.observe(speakerId, voice)
        }
        val wearer = speakers.wearer()
        if (speakerId != null && wearer != null) {
            // Die Diarisierung kennt den Träger bereits: sie überstimmt die Akustik in beide Richtungen.
            if (speakerId == wearer) voice = Voice.ICH
            else if (voice == Voice.ICH) voice = Voice.NAH
        }
        return voice
    }

    private fun allowed(voice: Voice): Boolean = when (config.reach) {
        Reach.ICH -> voice == Voice.ICH || voice == Voice.UNKLAR
        Reach.GESPRAECH -> voice != Voice.FERN
        Reach.RAUM -> true
    }

    private fun isFollowUp(now: Long, voice: Voice, text: String): Boolean =
        lastSpokeAtMs > 0 && now - lastSpokeAtMs < FOLLOW_UP_MS &&
            (voice == Voice.ICH || voice == Voice.UNKLAR) && Heuristics.words(text).size >= 2

    // ------------------------------------------------------------------------------------------
    // Vorgriff

    @Synchronized
    private fun speculate(text: String, turnProbability: Double?) {
        val key = normalize(text)
        speculation?.let { if (it.key != key) { it.job.cancel(); speculation = null } }
        if (turnProbability == null || turnProbability < SPECULATE_AT || speculation != null || speaking) return
        if (key.isEmpty() || Heuristics.heat(text, name) != Heuristics.Heat.HOT) return
        val now = clock()
        val named = Heuristics.mentionsName(text, name)
        val voice = voiceOf(meter.peekSummary(), null, text, named, learn = false)
        if (!(named || (now >= mutedUntilMs && allowed(voice)))) return
        val line = Line(id = -1, atMs = now, voice = voice, text = text.trim())
        speculation = startRun(turnFor(line), key, voice)
    }

    @Synchronized
    private fun takeSpeculation(): Run? = speculation.also { speculation = null }

    private fun normalize(text: String) = Heuristics.words(text).joinToString(" ")

    // ------------------------------------------------------------------------------------------
    // Antwort

    private fun turnFor(line: Line): Turn {
        val history = buffer.snapshot(clock()).filter { it.id != line.id }
        val spokeAgo = lastSpokeAtMs.takeIf { it > 0 }?.let { ((line.atMs - it) / 1000).coerceAtLeast(0) }
        return Turn(
            history, line,
            Situation(env.localTime(), config.mic, env.musicPlaying(), spokeAgo, scene, sessionNotes.toList()),
        )
    }

    /** Startet den Modell-Aufruf sofort im Hintergrund; die Ergebnisse warten im Kanal auf Abholung. */
    private fun startRun(turn: Turn, key: String, voice: Voice): Run {
        val events = Channel<Event>(Channel.UNLIMITED)
        val job = scope.launch(io) {
            val chunker = SentenceChunker()
            val parser = ReplyParser(
                onDecision = { events.trySend(Event.Decided(it)) },
                onSpeech = { delta -> chunker.feed(delta).forEach { events.trySend(Event.Sentence(it)) } },
                onDirective = { events.trySend(Event.Direct(it)) },
            )
            try {
                runInterruptible {
                    try {
                        brain.respond(turn) { delta ->
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
                events.trySend(Event.Failed(e))
            } finally {
                events.close()
            }
        }
        return Run(key, voice, events, job)
    }

    private suspend fun handle(trigger: Trigger) {
        try {
            var active = trigger.run
            if (active == null) {
                val turn = turnFor(trigger.line)
                if (!trigger.hot) {
                    val go = withContext(io) { runInterruptible { brain.pregate(turn) } }
                    if (!go) {
                        state.update { it.copy(lastDecision = "übersprungen: kein Anlass") }
                        return
                    }
                }
                active = startRun(turn, normalize(trigger.line.text), trigger.line.voice)
            }
            try {
                consume(active, trigger)
            } finally {
                active.job.cancel()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.update { it.copy(error = "Antwort fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}") }
        } finally {
            speaking = false
            state.update { it.copy(speaking = false) }
        }
    }

    private suspend fun consume(run: Run, trigger: Trigger) {
        val line = trigger.line
        val answer = StringBuilder()
        var said = false
        var words = 0
        var cap = Int.MAX_VALUE
        try {
            coroutineScope {
                val sentences = Channel<String>(Channel.UNLIMITED)
                // Die Sprachausgabe läuft neben dem Lesen her: Satz eins ist zu hören, während Satz zwei entsteht.
                val speech = launch {
                    speaker.speak(sentences) {
                        state.update { it.copy(latencyMs = clock() - line.atMs) }
                    }
                }
                try {
                    for (event in run.events) when (event) {
                        is Event.Decided -> when (val d = event.decision) {
                            is Decision.Skip -> skipped(d.reason.ifEmpty { "ohne Angabe" })
                            is Decision.Say -> {
                                // Kopfzeile vergessen: Dann gilt die Antwort nur, wenn er den Assistenten direkt angesprochen hat.
                                if (d.offProtocol && !trigger.named) { skipped("format"); break }
                                // Höflichkeit: Im Gespräch mit anderen erst abwarten, ob ein Mensch antwortet.
                                if (!holdForHumans(trigger)) { skipped("mensch antwortet"); break }
                                said = true
                                speaking = true
                                cap = WORD_CAPS[d.length] ?: WORD_CAPS.getValue("mittel")
                                state.update {
                                    it.copy(
                                        question = line.text, answer = "", speaking = true, error = null,
                                        lastDecision = "antwortet" + if (d.length.isNotEmpty()) " (${d.length})" else "",
                                    )
                                }
                            }
                        }
                        is Event.Sentence -> {
                            if (!said) continue
                            // Längendeckel im Code: Das Modell hält sich meist an seine Länge, verlassen darf man sich nicht darauf.
                            if (words > 0 && words + Heuristics.words(event.text).size > cap) break
                            words += Heuristics.words(event.text).size
                            if (answer.isNotEmpty()) answer.append(' ')
                            answer.append(event.text)
                            state.update { it.copy(answer = answer.toString()) }
                            sentences.send(event.text)
                        }
                        is Event.Direct -> if (said) directive(event.directive)
                        is Event.Failed -> throw event.error
                    }
                } finally {
                    sentences.close()
                }
                speech.join()
            }
        } finally {
            // Auch eine abgebrochene Antwort gehört in den Verlauf: Nachfragen beziehen sich darauf.
            if (said && answer.isNotBlank()) {
                buffer.add(answer.toString(), Voice.ALFRED, clock())
                lastAnswerWords = Heuristics.words(answer.toString()).toSet()
                lastSpokeAtMs = clock()
            }
        }
    }

    private fun skipped(reason: String) = state.update { it.copy(lastDecision = "übersprungen: $reason") }

    /**
     * Menschen antworten einander nach etwa 0,2 s; bleibt es eine Sekunde still, ist Hilfe willkommen.
     * Diese Sekunde läuft seit dem Ende der Äußerung, also parallel zum Modell-Aufruf, und kostet darum
     * praktisch keine Zeit. Beginnt in ihr jemand zu antworten, schweigt der Assistent.
     *
     * @return true, wenn gesprochen werden darf.
     */
    private suspend fun holdForHumans(trigger: Trigger): Boolean {
        if (trigger.named) return true
        val line = trigger.line
        // Veraltet: Wer vier Sekunden nach der Frage erst loslegt, stört mehr, als er hilft.
        if (clock() - line.atMs > STALE_MS) return false
        val inCompany = buffer.snapshot(clock()).any {
            it.id != line.id && !it.aside && it.voice != Voice.ALFRED && it.voice != Voice.ICH && line.atMs - it.atMs < 30_000
        } || line.voice == Voice.NAH
        if (!inCompany) return true
        while (clock() - line.atMs < HOLD_MS) {
            if (humanSpokeAtMs > line.atMs) return false
            kotlinx.coroutines.delay(50)
        }
        return humanSpokeAtMs <= line.atMs
    }

    private fun isEcho(words: List<String>): Boolean {
        val spoken = lastAnswerWords
        if (words.size < 3 || spoken.isEmpty()) return false
        val w = words.toSet()
        return w.intersect(spoken).size.toDouble() / w.size >= 0.6
    }

    private fun directive(d: Directive) {
        when (d) {
            is Directive.Memo -> {
                sessionNotes += d.text
                env.saveNote(d.text)
            }
            is Directive.Mute -> {
                mutedUntilMs = clock() + d.minutes * 60_000L
                state.update { it.copy(mutedUntilMs = mutedUntilMs) }
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
        const val HOLD_MS = 1_000L
        const val STALE_MS = 4_000L
        const val BARGE_IN_WORDS = 5
        /** Obergrenzen in Wörtern je Länge; großzügiger als die Vorgabe im Prompt, nur als Sicherung. */
        val WORD_CAPS = mapOf("kurz" to 22, "mittel" to 50, "lang" to 120)
    }
}
