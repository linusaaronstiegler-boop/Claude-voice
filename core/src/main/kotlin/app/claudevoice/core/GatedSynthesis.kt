package app.claudevoice.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/**
 * Sprache sofort erzeugen, aber erst ausliefern, wenn [release] es erlaubt.
 *
 * Wozu: In Gesellschaft wartet die App kurz, ob ein Mensch die Frage selbst beantwortet. Diese Wartezeit
 * soll nicht verloren sein. Also läuft die Sprachausgabe schon, der Ton wird nur zurückgehalten
 * (wie ein Kellner, der das Tablett fertig hat, aber an der Tür wartet).
 *
 * - `release == null`: keine Schranke, verhält sich wie [SpeechSynth.synthesize].
 * - `release` liefert `true`: das bis dahin Gesammelte geht in der richtigen Reihenfolge an [onPcm],
 *   alles Weitere direkt.
 * - `release` liefert `false` (oder wird abgebrochen): die Erzeugung endet, [onPcm] wird nie gerufen.
 */
suspend fun SpeechSynth.synthesizeGated(
    sentences: ReceiveChannel<String>,
    release: Deferred<Boolean>?,
    onPcm: suspend (ByteArray, Int) -> Unit,
) {
    if (release == null) {
        synthesize(sentences, onPcm)
        return
    }
    val held = ArrayList<ByteArray>()
    // Das Schloss sorgt dafür, dass nie zwei Stücke gleichzeitig ausgeliefert werden und dass beim
    // Öffnen kein Stück zwischen "Gesammeltes ausliefern" und "ab jetzt direkt" verloren geht.
    val lock = Mutex()
    var open = false
    coroutineScope {
        val work = launch {
            synthesize(sentences) { pcm, length ->
                lock.withLock {
                    if (open) onPcm(pcm, length) else held += pcm.copyOf(length)
                }
            }
        }
        val go = try {
            release.await()
        } catch (e: CancellationException) {
            coroutineContext.ensureActive() // wurden wir selbst abgebrochen, dann weiterreichen
            false // nur die Schranke wurde verworfen: gilt als Nein
        }
        if (!go) {
            work.cancelAndJoin()
            return@coroutineScope
        }
        lock.withLock {
            for (chunk in held) onPcm(chunk, chunk.size)
            held.clear()
            open = true
        }
        // coroutineScope wartet jetzt von selbst, bis die Erzeugung fertig ist.
    }
}
