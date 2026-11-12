package app.claudevoice.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Liefert je Satz ein Stück "Ton" (hier einfach die Bytes des Satzes), 100 ms nach dem Satz. */
private class SlowSynth : SpeechSynth {
    override val sampleRate = 24000
    var finished = false
    var cancelled = false

    override suspend fun synthesize(sentences: ReceiveChannel<String>, onPcm: suspend (ByteArray, Int) -> Unit) {
        try {
            for (s in sentences) {
                delay(100)
                val bytes = s.toByteArray()
                onPcm(bytes, bytes.size)
            }
            finished = true
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelled = true
            throw e
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class GatedSynthesisTest {
    private fun sentences(vararg s: String, close: Boolean = true): Channel<String> {
        val ch = Channel<String>(Channel.UNLIMITED)
        s.forEach { ch.trySend(it) }
        if (close) ch.close()
        return ch
    }

    @Test fun `ohne Schranke geht jedes Stueck sofort durch`() = runTest {
        val out = mutableListOf<String>()
        SlowSynth().synthesizeGated(sentences("eins", "zwei"), null) { pcm, n -> out += String(pcm, 0, n) }
        assertEquals(listOf("eins", "zwei"), out)
    }

    @Test fun `mit Schranke wird gesammelt und nach der Freigabe in der richtigen Reihenfolge geliefert`() = runTest {
        val synth = SlowSynth()
        val release = CompletableDeferred<Boolean>()
        val out = mutableListOf<String>()
        val ch = sentences("eins", "zwei", close = false)
        val job = launch { synth.synthesizeGated(ch, release) { pcm, n -> out += String(pcm, 0, n) } }

        advanceTimeBy(250); runCurrent()
        assertEquals("vor der Freigabe darf nichts hörbar sein", emptyList<String>(), out)

        release.complete(true); runCurrent()
        assertEquals(listOf("eins", "zwei"), out)

        // Was danach kommt, geht direkt durch.
        ch.trySend("drei"); ch.close()
        advanceTimeBy(150); runCurrent()
        assertEquals(listOf("eins", "zwei", "drei"), out)
        job.join()
        assertTrue(synth.finished)
    }

    @Test fun `Freigabe erst nach dem Ende der Erzeugung liefert trotzdem alles`() = runTest {
        val release = CompletableDeferred<Boolean>()
        val out = mutableListOf<String>()
        val job = launch { SlowSynth().synthesizeGated(sentences("kurz"), release) { pcm, n -> out += String(pcm, 0, n) } }
        advanceTimeBy(500); runCurrent()
        assertEquals(emptyList<String>(), out)
        release.complete(true)
        job.join()
        assertEquals(listOf("kurz"), out)
    }

    @Test fun `bei Nein erklingt nichts und die Erzeugung bricht ab`() = runTest {
        val synth = SlowSynth()
        val release = CompletableDeferred<Boolean>()
        val out = mutableListOf<String>()
        val job = launch { synth.synthesizeGated(sentences("eins", "zwei", close = false), release) { pcm, n -> out += String(pcm, 0, n) } }
        advanceTimeBy(150); runCurrent()
        release.complete(false)
        job.join()
        assertEquals(emptyList<String>(), out)
        assertTrue(synth.cancelled)
        assertFalse(synth.finished)
    }

    @Test fun `verworfene Schranke gilt als Nein, eigener Abbruch wird weitergereicht`() = runTest {
        val out = mutableListOf<String>()
        val dropped = CompletableDeferred<Boolean>().also { it.cancel() }
        SlowSynth().synthesizeGated(sentences("eins"), dropped) { pcm, n -> out += String(pcm, 0, n) }
        assertEquals(emptyList<String>(), out)

        val synth = SlowSynth()
        val never = CompletableDeferred<Boolean>()
        val job = launch { synth.synthesizeGated(sentences("eins", close = false), never) { pcm, n -> out += String(pcm, 0, n) } }
        advanceTimeBy(50); runCurrent()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(synth.cancelled)
        assertEquals(emptyList<String>(), out)
    }
}
