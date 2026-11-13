package app.claudevoice.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Eine Engine nur für Tests. Was sie zu einem eingereihten Satz meldet, bestimmt [script]; ohne Angabe
 * spricht sie jeden Satz sofort (der "Ton" sind einfach die Bytes des Textes) und meldet ihn fertig.
 */
private class FakeEngine : DeviceVoiceEngine {
    override var onEvent: (id: String, event: VoiceEvent) -> Unit = { _, _ -> }
    val queued = ArrayList<Pair<String, String>>() // Kennung und Text, in der Reihenfolge des Einreihens
    var stops = 0
    /** Was der Engine der Reihe nach gesagt wurde: "Satz: …" fuer jedes Einreihen, "Stopp" fuer jedes Anhalten. */
    val calls = ArrayList<String>()
    var closed = false
    var accept = true
    var script: FakeEngine.(id: String, text: String) -> Unit = { id, text -> say(id, text) }

    val texts: List<String> get() = queued.map { it.second }

    override fun enqueue(id: String, text: String): Boolean {
        if (!accept) return false
        queued += id to text
        calls += "Satz: $text"
        script(id, text)
        return true
    }

    override fun stop() { stops++; calls += "Stopp" }
    override fun close() { closed = true }

    fun begin(id: String, rate: Int = 24000, encoding: PcmEncoding = PcmEncoding.PCM_16, channels: Int = 1) =
        onEvent(id, VoiceEvent.Begin(rate, encoding, channels))

    fun audio(id: String, text: String) = onEvent(id, VoiceEvent.Audio(text.toByteArray()))

    /** Ein ganzer Satz, wie eine gesunde Engine ihn meldet. */
    fun say(id: String, text: String, rate: Int = 24000) {
        begin(id, rate)
        audio(id, text)
        onEvent(id, VoiceEvent.Done)
    }
}

/** 16-Bit-Werte als Bytes, das niederwertige zuerst (so liefert Android sie). */
private fun pcm16(vararg values: Int): ByteArray =
    ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putShort(it.toShort()) } }.array()

private fun values16(bytes: ByteArray): List<Int> =
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).let { buffer -> List(bytes.size / 2) { buffer.short.toInt() } }

/**
 * Die Logik der "Stimme des Geräts" mit einer nachgestellten Engine: Reihenfolge, Abbruch, Fehler nach dem
 * Ton, gar kein Ton, Tonformat. Ob Androids echte Sprachausgabe sich so verhält, zeigt nur ein Handy; hier
 * steht, was die App aus jedem denkbaren Verhalten macht.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceVoiceTest {
    private fun sentences(vararg s: String, close: Boolean = true): Channel<String> {
        val ch = Channel<String>(Channel.UNLIMITED)
        s.forEach { ch.trySend(it) }
        if (close) ch.close()
        return ch
    }

    /** Eine Sprachausgabe, deren Probe schon bestanden ist. Die Probe selbst zählt nicht zu [FakeEngine.queued]. */
    private suspend fun ready(engine: FakeEngine, rate: Int = 24000): DeviceVoiceSynth {
        val normal = engine.script
        engine.script = { id, text -> say(id, text, rate) }
        val synth = DeviceVoiceSynth.start(engine)
        engine.script = normal
        engine.queued.clear()
        return synth
    }

    private suspend fun speak(synth: DeviceVoiceSynth, ch: Channel<String>): List<String> {
        val out = ArrayList<String>()
        synth.synthesize(ch) { pcm, n -> out += String(pcm, 0, n) }
        return out
    }

    private suspend fun problemOf(engine: FakeEngine): String = try {
        DeviceVoiceSynth.start(engine)
        fail("die Probe haette scheitern muessen")
        ""
    } catch (e: DeviceVoiceUnavailable) {
        e.message.orEmpty()
    }

    // --- Probe beim Start ---------------------------------------------------------------------

    @Test fun `die Probe uebernimmt die Abtastrate der Engine und ist nirgends zu hoeren`() = runTest {
        val engine = FakeEngine()
        engine.script = { id, text -> say(id, text, rate = 22050) }
        val synth = DeviceVoiceSynth.start(engine)
        assertEquals(22050, synth.sampleRate)
        assertEquals("genau ein Probesatz", 1, engine.queued.size)
        assertEquals("eine saubere Probe muss nicht angehalten werden", 0, engine.stops)
        assertEquals("die Probe kostet keine Wartezeit", 0, currentTime)
    }

    @Test fun `liefert die Engine bei der Probe keinen Ton steht das als klare Meldung da`() = runTest {
        val silent = FakeEngine().apply { script = { id, _ -> begin(id); onEvent(id, VoiceEvent.Done) } }
        assertEquals(DeviceVoiceReport.noAudio(null), problemOf(silent))

        val broken = FakeEngine().apply { script = { id, _ -> onEvent(id, VoiceEvent.Failed(-3)) } }
        assertTrue(problemOf(broken).startsWith("Die Stimme des Geräts liefert keinen Ton (Fehler -3)."))

        val refusing = FakeEngine().apply { accept = false }
        assertTrue(problemOf(refusing).startsWith("Die Stimme des Geräts liefert keinen Ton"))
    }

    @Test fun `schweigt die Engine bei der Probe ganz endet das Warten mit einer Meldung`() = runTest {
        val engine = FakeEngine().apply { script = { _, _ -> } }
        assertEquals(DeviceVoiceReport.STUCK, problemOf(engine))
        assertEquals(DeviceVoiceSynth.FIRST_EVENT_MS, currentTime)
        assertEquals("die Engine soll nicht weiterrechnen", 1, engine.stops)
    }

    @Test fun `ein Fehler erst nach dem Ton laesst die Probe bestehen`() = runTest {
        val engine = FakeEngine().apply {
            script = { id, text -> begin(id, 16000); audio(id, text); onEvent(id, VoiceEvent.Failed(-5)) }
        }
        assertEquals(16000, DeviceVoiceSynth.start(engine).sampleRate)
    }

    @Test fun `bleibt nach dem Ton der Probe jede Schlussmeldung aus genuegt der Ton`() = runTest {
        val engine = FakeEngine().apply { script = { id, text -> begin(id); audio(id, text) } }
        assertEquals(24000, DeviceVoiceSynth.start(engine).sampleRate)
        assertEquals(1, engine.stops)
    }

    @Test fun `ein unbekanntes Tonformat wird bei der Probe gemeldet`() = runTest {
        val engine = FakeEngine().apply {
            script = { id, text -> begin(id, encoding = PcmEncoding.OTHER); audio(id, text); onEvent(id, VoiceEvent.Done) }
        }
        assertEquals(DeviceVoiceReport.FORMAT, problemOf(engine))
    }

    // --- Reihenfolge --------------------------------------------------------------------------

    @Test fun `Saetze werden in ihrer Reihenfolge und ohne Luecke gesprochen`() = runTest {
        val engine = FakeEngine()
        val synth = ready(engine)
        assertEquals(listOf("Eins.", "Zwei.", "Drei."), speak(synth, sentences("Eins.", "  ", " Zwei. ", "Drei.")))
        assertEquals("leere Saetze gehen gar nicht erst an die Engine", listOf("Eins.", "Zwei.", "Drei."), engine.texts)
        assertEquals("jeder Satz hat seine eigene Kennung", 3, engine.queued.map { it.first }.toSet().size)
        assertEquals("nach einem sauberen Ende gibt es nichts anzuhalten", 0, engine.stops)
        assertEquals(0, currentTime)
    }

    @Test fun `der naechste Satz wird schon eingereiht waehrend der vorige noch spielt`() = runTest {
        val engine = FakeEngine()
        val synth = ready(engine)
        val ch = sentences("Eins.", close = false)
        val playing = CompletableDeferred<Unit>()
        val goOn = CompletableDeferred<Unit>()
        val out = ArrayList<String>()
        val job = launch {
            synth.synthesize(ch) { pcm, n ->
                out += String(pcm, 0, n)
                if (out.size == 1) { playing.complete(Unit); goOn.await() } // der Abspieler ist noch mit Satz eins beschaeftigt
            }
        }
        playing.await()
        ch.send("Zwei.")
        runCurrent()
        assertEquals("die Engine arbeitet nicht vor", listOf("Eins.", "Zwei."), engine.texts)
        assertEquals(listOf("Eins."), out)
        goOn.complete(Unit)
        ch.close()
        job.join()
        assertEquals(listOf("Eins.", "Zwei."), out)
    }

    @Test fun `kommt kein einziger Satz wird die Engine nicht angefasst`() = runTest {
        val engine = FakeEngine()
        val synth = ready(engine)
        assertEquals(emptyList<String>(), speak(synth, sentences()))
        assertTrue(engine.queued.isEmpty())
        assertEquals(0, engine.stops)
    }

    // --- Abbruch ------------------------------------------------------------------------------

    @Test fun `ein Abbruch haelt die Engine sofort an und Nachzuegler erreichen die naechste Antwort nicht`() = runTest {
        // Der erste Satz liefert Ton und wird nie fertig; der zweite wartet noch in der Engine.
        val engine = FakeEngine().apply { script = { id, text -> if (text == "Alt eins.") { begin(id); audio(id, text) } } }
        val synth = ready(engine)
        val out = ArrayList<String>()
        val job = launch { synth.synthesize(sentences("Alt eins.", "Alt zwei.", close = false)) { pcm, n -> out += String(pcm, 0, n) } }
        runCurrent()
        assertEquals(listOf("Alt eins."), out)
        val old = engine.queued.map { it.first }

        job.cancelAndJoin()
        assertEquals("ein Abbruch muss die Engine anhalten", 1, engine.stops)
        assertEquals("angehalten wird sofort, nicht nach einer Frist", 0, currentTime)

        // Die Engine meldet noch etwas zu den alten Saetzen, mitten in die naechste Antwort hinein.
        engine.script = { id, text ->
            audio(old[1], "Alt zwei.")
            onEvent(old[0], VoiceEvent.Stopped)
            say(id, text)
        }
        assertEquals(listOf("Neu."), speak(synth, sentences("Neu.")))
    }

    @Test fun `ein Abbruch mitten im Satz waehrend der Abspieler beschaeftigt ist laesst nichts weiterlaufen`() = runTest {
        // Die Engine hat zwei Stuecke Ton zum ersten Satz geliefert; der Abspieler haengt noch am ersten.
        val engine = FakeEngine().apply { script = { id, _ -> begin(id); audio(id, "Stueck eins."); audio(id, "Stueck zwei.") } }
        val synth = ready(engine)
        val out = ArrayList<String>()
        val busy = CompletableDeferred<Unit>()
        val job = launch {
            synth.synthesize(sentences("Ein langer Satz.", "Noch einer.", close = false)) { pcm, n ->
                out += String(pcm, 0, n)
                busy.complete(Unit)
                CompletableDeferred<Unit>().await() // der Abspieler nimmt gerade nichts mehr an
            }
        }
        busy.await()
        job.cancelAndJoin()
        assertEquals("nach dem Abbruch darf kein weiteres Stueck mehr zum Abspieler gehen", listOf("Stueck eins."), out)
        assertEquals("die Engine muss angehalten werden", 1, engine.stops)
        assertEquals("angehalten wird sofort, nicht nach einer Frist", 0, currentTime)
        // Nichts haengt mehr: Die naechste Antwort kommt sofort dran.
        engine.script = { id, text -> say(id, text) }
        assertEquals(listOf("Neu."), speak(synth, sentences("Neu.")))
        assertEquals(0, currentTime)
    }

    @Test fun `zwei Antworten kurz nacheinander werden nacheinander gesprochen und vermischen sich nicht`() = runTest {
        val engine = FakeEngine()
        val synth = ready(engine)
        val out = ArrayList<String>()
        val playing = CompletableDeferred<Unit>()
        val goOn = CompletableDeferred<Unit>()
        val first = launch {
            synth.synthesize(sentences("A eins.", "A zwei.")) { pcm, n ->
                out += String(pcm, 0, n)
                if (out.size == 1) { playing.complete(Unit); goOn.await() } // die erste Antwort spielt noch
            }
        }
        playing.await()
        val second = launch { synth.synthesize(sentences("B eins.", "B zwei.")) { pcm, n -> out += String(pcm, 0, n) } }
        runCurrent()
        // Die Engine hat eine einzige Warteschlange. Kaeme die zweite Antwort jetzt schon hinein, wuerde ein
        // Stopp der ersten sie mit wegwerfen.
        assertEquals("die zweite Antwort darf erst nach der ersten an die Engine", listOf("A eins.", "A zwei."), engine.texts)
        goOn.complete(Unit)
        first.join()
        second.join()
        assertEquals(listOf("A eins.", "A zwei.", "B eins.", "B zwei."), out)
        assertEquals(0, engine.stops)
    }

    @Test fun `auf einen Stopp folgt sofort eine neue Antwort erst wird angehalten dann der neue Satz eingereiht`() = runTest {
        // Androids Engine wirft bei einem Stopp ALLES weg, was diese App eingereiht hat. Kaeme der Stopp der
        // alten Antwort erst nach dem ersten Satz der neuen an, waere die neue Antwort stumm.
        val engine = FakeEngine().apply { script = { id, text -> if (text == "Alt.") { begin(id); audio(id, text) } else say(id, text) } }
        val synth = ready(engine)
        val old = launch { synth.synthesize(sentences("Alt.", close = false)) { _, _ -> } }
        runCurrent()
        val out = ArrayList<String>()
        // Die neue Antwort steht schon bereit, bevor die alte abgebrochen ist (so eng liegt es im Betrieb).
        val next = launch { synth.synthesize(sentences("Neu.")) { pcm, n -> out += String(pcm, 0, n) } }
        runCurrent()
        old.cancel()
        next.join()
        assertEquals(listOf("Satz: Alt.", "Stopp", "Satz: Neu."), engine.calls.drop(1)) // ohne den Probesatz
        assertEquals(listOf("Neu."), out)
        assertEquals("die neue Antwort wartet auf keine Frist", 0, currentTime)
    }

    // --- Fehler nach dem Ton, fehlende Schlussmeldung ------------------------------------------

    @Test fun `ein Fehler nach dem Ton verliert weder diesen Satz noch den naechsten`() = runTest {
        // So verhaelt sich eine Engine, die am Schluss an ihrem Ausgabeziel scheitert: Der Ton ist vollstaendig
        // geliefert, danach kommt ein Fehler statt der Fertigmeldung.
        val engine = FakeEngine().apply {
            script = { id, text -> begin(id); audio(id, text); onEvent(id, VoiceEvent.Failed(-5)) }
        }
        val synth = ready(engine)
        val trouble = ArrayList<String>()
        synth.onTrouble = { trouble += it }
        assertEquals(listOf("Eins.", "Zwei."), speak(synth, sentences("Eins.", "Zwei.")))
        assertEquals(2, trouble.size)
        assertTrue(trouble[0], "(-5)" in trouble[0])
        assertEquals("ein spaeter Fehler kostet keine Wartezeit", 0, currentTime)
    }

    @Test fun `bleibt die Schlussmeldung ganz aus geht trotzdem kein Satz verloren`() = runTest {
        // Der haertere Fall: Nach dem Ton kommt gar nichts mehr, weder "fertig" noch "Fehler". Die Engine
        // macht einfach mit dem naechsten Satz weiter.
        val engine = FakeEngine().apply { script = { id, text -> begin(id); audio(id, text) } }
        val synth = ready(engine)
        val trouble = ArrayList<String>()
        synth.onTrouble = { trouble += it }
        assertEquals(listOf("Eins.", "Zwei.", "Drei."), speak(synth, sentences("Eins.", "Zwei.", "Drei.")))
        // Zwischen den Saetzen wird nicht gewartet: Dass der naechste beginnt, zeigt das Ende des vorigen.
        // Nur nach dem letzten gibt es nichts mehr, woran man das Ende saehe; dort laeuft die kurze Frist ab.
        assertEquals(DeviceVoiceSynth.IDLE_MS, currentTime)
        assertEquals(listOf("Die Stimme des Geräts hat das Ende eines Satzes nicht gemeldet"), trouble)
        assertEquals("nach einem vollstaendigen Ende wird nichts angehalten", 0, engine.stops)
    }

    // --- Gar kein Ton -------------------------------------------------------------------------

    @Test fun `liefert die Engine fuer die ganze Antwort keinen Ton endet sie mit einer klaren Meldung`() = runTest {
        val engine = FakeEngine()
        val synth = ready(engine)
        engine.script = { id, _ -> begin(id); onEvent(id, VoiceEvent.Done) }
        val out = ArrayList<String>()
        val error = runCatching { synth.synthesize(sentences("Eins.", "Zwei.")) { pcm, n -> out += String(pcm, 0, n) } }.exceptionOrNull()
        assertTrue("erwartet war ein Fehler der Sprachausgabe, es kam $error", error is IOException)
        assertEquals(DeviceVoiceReport.noAudio(null), error!!.message)
        assertTrue(out.isEmpty())

        engine.script = { id, _ -> onEvent(id, VoiceEvent.Failed(-4)) }
        val failed = runCatching { speak(synth, sentences("Eins.")) }.exceptionOrNull()
        assertEquals(DeviceVoiceReport.noAudio(-4), failed!!.message)

        engine.accept = false
        val refused = runCatching { speak(synth, sentences("Eins.")) }.exceptionOrNull()
        assertEquals(DeviceVoiceReport.noAudio(DeviceVoiceSynth.REFUSED), refused!!.message)
    }

    @Test fun `antwortet die Engine gar nicht mehr endet das Warten mit einer Meldung`() = runTest {
        val engine = FakeEngine()
        val synth = ready(engine)
        engine.script = { _, _ -> }
        val error = runCatching { speak(synth, sentences("Eins.", close = false)) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals(DeviceVoiceReport.STUCK, error!!.message)
        assertEquals(DeviceVoiceSynth.FIRST_EVENT_MS, currentTime)
        assertEquals("eine haengende Engine wird angehalten", 1, engine.stops)
    }

    @Test fun `bleibt nur ein Satz stumm wird der Rest gesprochen und es gibt einen Hinweis`() = runTest {
        val engine = FakeEngine()
        val synth = ready(engine)
        engine.script = { id, text -> if (text == "…") { begin(id); onEvent(id, VoiceEvent.Done) } else say(id, text) }
        val trouble = ArrayList<String>()
        synth.onTrouble = { trouble += it }
        assertEquals(listOf("Eins.", "Drei."), speak(synth, sentences("Eins.", "…", "Drei.")))
        assertEquals(listOf("Die Stimme des Geräts blieb bei 1 von 3 Sätzen stumm"), trouble)
    }

    // --- Tonformat ----------------------------------------------------------------------------

    @Test fun `im Normalfall gehen die Bytes der Engine unveraendert durch`() {
        val shaper = PcmShaper(24000)
        assertTrue(shaper.begin(VoiceEvent.Begin(24000, PcmEncoding.PCM_16, 1)))
        val bytes = pcm16(1, -2, 3)
        assertSame(bytes, shaper.feed(bytes))
    }

    @Test fun `eine abweichende Abtastrate wird auf die feste Rate umgerechnet`() {
        // Die Engine liefert 12 kHz, der Abspieler ist fuer 24 kHz gebaut: Jeder zweite Wert liegt genau
        // zwischen zwei Werten der Engine.
        val shaper = PcmShaper(24000)
        shaper.begin(VoiceEvent.Begin(12000, PcmEncoding.PCM_16, 1))
        assertEquals(listOf(0, 50, 100, 150, 200, 250, 300), values16(shaper.feed(pcm16(0, 100, 200, 300))))
        // Das naechste Stueck schliesst lueckenlos an: Der Wert zwischen den Stuecken (350) fehlt nicht.
        assertEquals(listOf(350, 400, 450, 500), values16(shaper.feed(pcm16(400, 500))))

        // Abwaerts: 48 kHz auf 24 kHz behaelt jeden zweiten Wert.
        val down = PcmShaper(24000)
        down.begin(VoiceEvent.Begin(48000, PcmEncoding.PCM_16, 1))
        assertEquals(listOf(10, 30, 50), values16(down.feed(pcm16(10, 20, 30, 40, 50, 60))))
    }

    @Test fun `wie die Engine den Ton stueckelt aendert nichts am Ergebnis`() {
        val all = pcm16(*IntArray(200) { (it * 37) % 2000 - 1000 })
        fun shaped(cuts: List<Int>): List<Int> {
            val shaper = PcmShaper(24000)
            shaper.begin(VoiceEvent.Begin(16000, PcmEncoding.PCM_16, 1))
            val out = ArrayList<Int>()
            var from = 0
            for (cut in cuts + all.size) {
                out += values16(shaper.feed(all.copyOfRange(from, cut)))
                from = cut
            }
            return out
        }
        val whole = shaped(emptyList())
        assertEquals("16 kHz auf 24 kHz: anderthalbmal so viele Werte", 299, whole.size)
        // Auch Schnitte mitten in einem Wert (ungerade Stelle) duerfen nichts aendern.
        assertEquals(whole, shaped(listOf(1, 2, 77, 78, 301)))
        assertEquals(whole, shaped(listOf(200)))
    }

    @Test fun `Stereo acht Bit und Kommazahlen werden zu sechzehn Bit mono`() {
        val stereo = PcmShaper(24000)
        stereo.begin(VoiceEvent.Begin(24000, PcmEncoding.PCM_16, 2))
        assertEquals(listOf(150, -300), values16(stereo.feed(pcm16(100, 200, -200, -400))))

        val eight = PcmShaper(24000)
        eight.begin(VoiceEvent.Begin(24000, PcmEncoding.PCM_8, 1))
        assertEquals(listOf(0, 32512, -32768), values16(eight.feed(byteArrayOf(128.toByte(), 255.toByte(), 0))))

        val floats = PcmShaper(24000)
        floats.begin(VoiceEvent.Begin(24000, PcmEncoding.PCM_FLOAT, 1))
        val bytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(0f).putFloat(0.5f).putFloat(-2f).putFloat(Float.NaN).array()
        assertEquals(listOf(0, 16384, -32767, 0), values16(floats.feed(bytes)))
    }

    @Test fun `ein Satz in anderer Rate wird mitten in der Antwort umgerechnet`() = runTest {
        val engine = FakeEngine()
        val synth = ready(engine, rate = 24000)
        engine.script = { id, _ ->
            begin(id, rate = 12000)
            onEvent(id, VoiceEvent.Audio(pcm16(0, 100, 200)))
            onEvent(id, VoiceEvent.Done)
        }
        val out = ArrayList<Int>()
        synth.synthesize(sentences("Hello.")) { pcm, n -> out += values16(pcm.copyOf(n)) }
        assertEquals(listOf(0, 50, 100, 150, 200), out)
    }

    // --- Wahl der Stimme ----------------------------------------------------------------------

    private fun voice(
        name: String, language: String = "de", country: String = "DE", quality: Int = 400, latency: Int = 200,
        network: Boolean = false, installed: Boolean = true, default: Boolean = false,
    ) = VoiceOption(name, language, country, quality, latency, network, installed, default)

    @Test fun `gewaehlt wird die beste deutsche Stimme die ohne Netz spricht`() {
        val best = DeviceVoiceChoice.best(
            listOf(
                voice("en-us-lokal", language = "en", country = "US", quality = 500),
                voice("de-netz", quality = 500, network = true),     // schickte den Text ins Netz
                voice("de-fehlt", quality = 500, installed = false), // Sprachdaten nicht geladen
                voice("de-at-fein", country = "AT", quality = 500),
                voice("de-de-grob", quality = 300),
                voice("de-de-gut", quality = 400),
            )
        )
        assertEquals("de-de-gut", best?.name)
    }

    @Test fun `bei gleicher Guete gewinnt die Stimme die Android ohnehin naehme`() {
        val options = listOf(voice("de-de-a"), voice("de-de-b", default = true), voice("de-de-c", latency = 100))
        assertEquals("de-de-b", DeviceVoiceChoice.best(options)?.name)
        // Ohne so eine Vorgabe entscheidet die Verzoegerung, zuletzt der Name: immer dieselbe Stimme.
        assertEquals("de-de-c", DeviceVoiceChoice.best(options.filter { !it.isDefault })?.name)
        assertEquals("de-de-a", DeviceVoiceChoice.best(listOf(voice("de-de-b"), voice("de-de-a")))?.name)
    }

    @Test fun `ohne brauchbare deutsche Stimme gibt es keine Wahl`() {
        assertNull(DeviceVoiceChoice.best(emptyList()))
        assertNull(DeviceVoiceChoice.best(listOf(voice("en", language = "en"), voice("de-netz", network = true))))
        // Manche Engines nennen die Sprache mit drei Buchstaben.
        assertEquals("deu", DeviceVoiceChoice.best(listOf(voice("deu", language = "deu", country = "DEU")))?.name)
        assertFalse(DeviceVoiceReport.NO_GERMAN.isBlank())
    }
}
