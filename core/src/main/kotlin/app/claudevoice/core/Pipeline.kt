package app.claudevoice.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** Spricht einen Satz und kehrt zurück, wenn er zu Ende gesprochen ist. */
interface Speaker {
    suspend fun speak(text: String)
    fun stop()
}

/**
 * Das Herzstück: verbindet Mitschrift -> Filter -> Antwort -> Sprachausgabe.
 *
 * Ablauf: Jede fertige Äußerung landet im Puffer und stößt die Verarbeitung an.
 * Der Anstoß läuft über einen "conflated" Channel: ist die Pipeline gerade beschäftigt,
 * merkt sie sich nur die jeweils neueste Äußerung statt eine Warteschlange aufzubauen.
 * So redet die App nie alten Fragen hinterher.
 */
class Pipeline(
    claude: ClaudeApi,
    private val speaker: Speaker,
    private val state: MutableStateFlow<UiState>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val buffer = ConversationBuffer()
    private val gate = QuestionGate(claude)
    private val answerer = Answerer(claude)
    private val trigger = Channel<String>(Channel.CONFLATED)

    @Volatile private var speaking = false
    @Volatile private var current: Job? = null
    private var loop: Job? = null

    fun start() {
        loop = scope.launch {
            for (utterance in trigger) {
                val job = launch { handle(utterance) }
                current = job
                job.join()
            }
        }
    }

    fun stop() {
        loop?.cancel()
        speaker.stop()
    }

    /** Zwischenstand der laufenden Äußerung, nur zur Anzeige. */
    fun onPartial(text: String) {
        if (!speaking) state.update { it.copy(partial = text) }
    }

    /** Fertige Äußerung aus der Transkription. */
    fun onCommitted(text: String) {
        val t = text.trim()
        // Während die App selbst spricht, wird nicht mitgeschrieben: sonst hört sie sich selbst zu.
        if (t.isEmpty() || speaking) return
        buffer.add(t, Voice.UNKLAR, clock())
        state.update { it.copy(partial = "", transcript = (it.transcript + t).takeLast(40)) }
        trigger.trySend(t)
    }

    /** Laufende Antwort abbrechen (Knopf in der App oder Kopfhörer-Taste). */
    fun stopSpeaking() {
        current?.cancel()
        speaker.stop()
    }

    private suspend fun handle(utterance: String) {
        try {
            val now = clock()
            val context = buffer.snapshot(now).joinToString("\n") { it.text }
            val question = withContext(io) { runInterruptible { gate.decide(context, utterance, now) } } ?: return

            state.update { it.copy(question = question, answer = "", speaking = true, error = null) }
            speaking = true
            coroutineScope {
                val sentences = Channel<String>(Channel.UNLIMITED)
                // Produzent: Claude schreibt. Konsument: wir sprechen. Beides gleichzeitig.
                launch(io) {
                    try {
                        runInterruptible { answerer.answer(context, question) { sentences.trySend(it) } }
                    } finally {
                        sentences.close()
                    }
                }
                for (sentence in sentences) {
                    state.update { it.copy(answer = (it.answer + " " + sentence).trim()) }
                    speaker.speak(sentence)
                }
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
}
