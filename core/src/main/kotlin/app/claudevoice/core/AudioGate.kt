package app.claudevoice.core

/**
 * Die Audio-Weiche zwischen Mikrofon und Transkription.
 *
 * Sie kennt vier Zustände:
 *  - Bereitschaft: Das Audio bleibt auf dem Gerät. Nur der lokale Weckwort-Erkenner hört zu.
 *  - Geweckt: Das Weckwort ist gefallen, die Verbindung wird gleich geöffnet.
 *  - Verbindungsaufbau (auch Neuverbinden): Es gibt eine Verbindung, sie steht aber noch nicht.
 *  - Zuhören: Jedes Stück geht sofort zur Transkription.
 *
 * Solange nichts gesendet werden kann, wird Audio aufgehoben und nachgereicht, sobald die Verbindung
 * steht. Dafür gelten ZWEI Grenzen, und der Unterschied ist der Kern dieser Klasse:
 *  - In der Bereitschaft bleibt nur so viel liegen, wie das Weckwort selbst dauert. Was jemand davor
 *    gesagt hat, darf das Gerät nicht verlassen; die Bereitschaft verspricht genau das.
 *  - Nach dem Weckwort ist alles Gesprochene schon die Bitte an den Assistenten. Dauert der
 *    Verbindungsaufbau einmal länger (Funkloch, zweiter Versuch), soll ihr Anfang nicht verloren gehen.
 *    Darum darf hier deutlich mehr liegen bleiben.
 *
 * Threads: Das Mikrofon ruft [onAudio], die Netzverbindung [connected] und [disconnected], der Dienst
 * den Rest. Alle Methoden sind deshalb gegeneinander gesperrt. Auch der Weckwort-Erkenner wird nur
 * innerhalb dieser Sperre angefasst; er muss selbst nicht mit mehreren Threads umgehen können.
 *
 * [measure] bekommt jedes Stück, das zur Transkription geht (die Pipeline misst daran Lautstärke und
 * Stimmlage). Gespeichert wird nirgends etwas; was nicht gesendet wird, verfällt im Arbeitsspeicher.
 */
class AudioGate(
    private val wake: WakeWordDetector?,
    private val measure: (ByteArray, Int) -> Unit,
    private val wakePhraseChunks: Int = WAKE_PHRASE_CHUNKS,
    private val backlogChunks: Int = BACKLOG_CHUNKS,
) {
    private var stream: Transcriber? = null
    private var live = false
    private var woken = false
    private var shut = false
    private val waiting = ArrayDeque<ByteArray>()

    /**
     * Ein Stück Mikrofon-Audio (100 ms).
     * @return true genau dann, wenn mit diesem Stück das Weckwort erkannt wurde. Der Aufrufer öffnet
     *         daraufhin die Verbindung ([open]); bis dahin wird weiter gesammelt, aber nicht mehr gelauscht.
     */
    @Synchronized
    fun onAudio(pcm: ByteArray, length: Int): Boolean {
        if (shut) return false
        val n = minOf(length, pcm.size)
        if (n <= 0) return false
        val t = stream
        if (t != null && live) {
            measure(pcm, n)
            t.sendAudio(pcm, n)
            return false
        }
        val resting = t == null && !woken
        waiting.addLast(pcm.copyOf(n))
        val limit = if (resting) wakePhraseChunks else backlogChunks
        while (waiting.size > limit) waiting.removeFirst()
        if (resting && wake != null && wake.feed(pcm, n)) {
            // Sofort leeren, damit das Ende desselben "Hey Jarvis" nicht ein zweites Mal auslöst.
            wake.reset()
            woken = true
            return true
        }
        return false
    }

    /**
     * Eine neue Verbindung [t] wird aufgebaut. Das bis hierher Gesammelte bleibt liegen und geht hinaus,
     * sobald sie steht.
     * @return false, wenn die Weiche schon endgültig geschlossen ist; [t] darf dann nicht verbunden werden.
     */
    @Synchronized
    fun open(t: Transcriber): Boolean {
        if (shut) return false
        stream = t
        live = false
        woken = false
        return true
    }

    /**
     * Die Verbindung [t] steht: Gesammeltes in der richtigen Reihenfolge nachreichen, ab jetzt direkt senden.
     * @return false, wenn [t] nicht (mehr) die aktuelle Verbindung ist. Dann passiert nichts.
     */
    @Synchronized
    fun connected(t: Transcriber): Boolean {
        if (shut || stream !== t) return false
        for (chunk in waiting) {
            measure(chunk, chunk.size)
            t.sendAudio(chunk, chunk.size)
        }
        waiting.clear()
        live = true
        return true
    }

    /** Die Verbindung [t] ist unterbrochen: bis sie wieder steht, wird gesammelt. */
    @Synchronized
    fun disconnected(t: Transcriber): Boolean {
        if (shut || stream !== t) return false
        live = false
        return true
    }

    /** Ist [t] die Verbindung, die gerade zählt? Rückrufe einer abgelösten Verbindung werden damit aussortiert. */
    @Synchronized
    fun isCurrent(t: Transcriber): Boolean = !shut && stream === t

    /**
     * Zurück in die Bereitschaft: Gesammeltes verwerfen und wieder nur auf das Weckwort lauschen.
     * @return die bisherige Verbindung, damit der Aufrufer sie schließt (außerhalb der Sperre).
     */
    @Synchronized
    fun rest(): Transcriber? {
        val old = stream
        stream = null
        live = false
        woken = false
        waiting.clear()
        if (!shut) wake?.reset()
        return old
    }

    /**
     * Endgültig schließen (der Dienst endet). Danach wird kein Audio mehr angenommen, und der
     * Weckwort-Erkenner ist freigegeben. Weil das unter derselben Sperre passiert wie [onAudio], kann
     * das Mikrofon nie in einen schon freigegebenen Erkenner hineinsprechen.
     */
    @Synchronized
    fun shutdown(): Transcriber? {
        val old = rest()
        if (!shut) {
            shut = true
            runCatching { wake?.close() }
        }
        return old
    }

    companion object {
        /**
         * Vorlauf in der Bereitschaft: 1,3 s. "Hey Jarvis" dauert gut eine Sekunde, erkannt wird es wenige
         * Hundertstel nach seinem Ende. So steht das Weckwort selbst noch in der Mitschrift (die Pipeline
         * erkennt daran die Anrede), von dem davor aber höchstens ein Wimpernschlag.
         */
        const val WAKE_PHRASE_CHUNKS = 13

        /** Höchstens 8 s (etwa 256 KB) warten auf die Verbindung, danach verfällt das Älteste. */
        const val BACKLOG_CHUNKS = 80
    }
}

/**
 * Reicht die Rückrufe einer Transkription nur weiter, solange [isCurrent] gilt.
 *
 * Wozu: Geht der Dienst zurück in die Bereitschaft, schließt er die Verbindung. Ein Rückruf, der in
 * genau diesem Moment schon unterwegs ist, käme sonst danach noch an und würde etwa den Status wieder
 * auf "Hört zu" stellen oder eine Äußerung nachreichen, obwohl gar nicht mehr zugehört wird.
 */
class CurrentOnlyListener(
    private val inner: Transcriber.Listener,
    private val isCurrent: () -> Boolean,
) : Transcriber.Listener {
    override fun onPartial(text: String, turnProbability: Double?, speaker: String?) {
        if (isCurrent()) inner.onPartial(text, turnProbability, speaker)
    }

    override fun onCommitted(text: String, speaker: String?, segment: Int?) {
        if (isCurrent()) inner.onCommitted(text, speaker, segment)
    }

    override fun onDiarized(segment: Int, parts: List<SpokenPart>) {
        if (isCurrent()) inner.onDiarized(segment, parts)
    }

    override fun onSpeakersMerged(from: String, to: String) {
        if (isCurrent()) inner.onSpeakersMerged(from, to)
    }

    override fun onConnection(connected: Boolean, error: String?, fresh: Boolean) {
        if (isCurrent()) inner.onConnection(connected, error, fresh)
    }
}
