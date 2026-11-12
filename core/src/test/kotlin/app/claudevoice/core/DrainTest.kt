package app.claudevoice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Hinter dem Längendeckel: Die Bestätigung ("Notiert, Sir.", "Sehr wohl, Sir.") ist verklungen, das Modell
 * schreibt aber noch, und ganz am Schluss steht die Sonderzeile (MEMO, MUTE).
 *
 * Der Auftrag endet mit dem Ton, und ein kleiner Leser liest den Rest (Pipeline.drain). Nach einer Bitte um
 * Ruhe gilt die Ruhe schon, solange dieser Leser läuft: Sie ist zugesagt, auch wenn MUTE noch nicht dasteht.
 * Alles mit echten Threads, denn nur so ist der Aufruf wirklich noch unterwegs, während der Ton zu Ende ist.
 */
class DrainTest {

    private val withProfile = Pipeline.Config(voiceProfile = VoiceProfile(-18.0, 115.0, 5))

    /** Ein Satz, der den Deckel von "kurz" (22 Wörter) sprengt: Er wird nicht mehr gesprochen. */
    private val tooLong = "Dazu kommen noch sehr viele weitere Einzelheiten die an dieser Stelle niemand hören will " +
        "weil sie die Antwort nur unnötig in die Länge ziehen würden und nichts Neues bringen. "

    /**
     * Ein Modell, dessen Antworten der Test abschnittsweise freigibt. [script] nennt für die markierte Zeile
     * eines Aufrufs die Abschnitte: je ein Stichwort und die Textstücke dazu. Vor jedem Abschnitt hält der
     * Aufruf an, bis der Test das Stichwort öffnet ([open]). Die Vorprüfung sagt immer JA.
     */
    private class Staged(private val script: (String) -> List<Pair<String, List<String>>>) : ClaudeApi {
        val calls = CopyOnWriteArrayList<String>()
        /** Die Aufrufe, die bis zu ihrem letzten Abschnitt geliefert haben. */
        val finished = CopyOnWriteArrayList<String>()
        private val reached = ConcurrentHashMap<String, CountDownLatch>()
        private val gates = ConcurrentHashMap<String, CountDownLatch>()

        private fun reachedLatch(stage: String) = reached.computeIfAbsent(stage) { CountDownLatch(1) }
        private fun gate(stage: String) = gates.computeIfAbsent(stage) { CountDownLatch(1) }

        /** Wartet, bis ein Aufruf vor dem Abschnitt [stage] steht. */
        fun awaitStage(stage: String) = reachedLatch(stage).await(5, TimeUnit.SECONDS)
        fun open(stage: String) = gate(stage).countDown()

        override fun respond(system: String, user: String, cancel: CancelToken, onText: (String) -> Unit) {
            val trigger = user.lineSequence().first { it.startsWith(">> [jetzt]") }
            calls += trigger
            for ((stage, pieces) in script(trigger)) {
                reachedLatch(stage).countDown()
                gate(stage).await() // ein abgebrochener Aufruf beendet das Warten mit einer Ausnahme
                pieces.forEach(onText)
            }
            finished += trigger
        }

        override fun quick(system: String, user: String, maxTokens: Long): String = "JA"
    }

    /** Die Bestätigung, dann weit über den Deckel hinaus, und erst im Abschnitt "schluss" die Sonderzeile [tail]. */
    private fun rambling(confirmation: String, tail: String) = listOf(
        "anfang" to listOf("SAY kurz\n", "$confirmation ", tooLong),
        "schluss" to listOf("\n$tail\n"),
    )

    private fun info(rig: ThreadRig, claude: Staged) =
        "gesprochen=${rig.speaker.spoken} Aufrufe=${claude.calls} zuletzt=${rig.state.value.lastDecision} stumm=${rig.state.value.mutedUntilMs}"

    @Test fun `scheitert das Speichern der Notiz im Nachleser, steht das als Meldung in der Anzeige`() {
        // Der Leser läuft in einer eigenen Coroutine. Eine Ausnahme, die sie ungefangen verlässt, landet beim
        // Thread: Auf Android beendet das die App. Zu hören war "Notiert", gespeichert wäre nichts.
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
        val claude = Staged { rambling("Notiert, Sir.", "MEMO: Kennzeichen M-AB 1234") }
        val rig = ThreadRig(claude, withProfile, env = FakeEnv(saveFails = java.io.IOException("No space left on device")))
        try {
            claude.open("anfang")
            rig.wearerSays("Alfred, merk dir das Kennzeichen M-AB 1234")
            assertTrue(info(rig, claude), waitFor(3_000) { rig.speaker.spoken.isNotEmpty() && !rig.state.value.speaking })
            assertEquals(listOf("Notiert, Sir."), rig.speaker.spoken.toList())
            assertNull(rig.state.value.error)

            claude.open("schluss") // jetzt erst kommt die Sonderzeile, und das Speichern scheitert
            waitFor(3_000) { rig.state.value.error != null || uncaught.isNotEmpty() }
            Thread.sleep(100)
            assertTrue("unbehandelte Ausnahme in einer Coroutine des Dienstes: $uncaught", uncaught.isEmpty())
            val error = rig.state.value.error
            assertTrue("Anzeige: $error", error != null && error.contains("No space left on device"))
            assertTrue("nichts gilt als gemerkt", rig.env.notes.isEmpty())
        } finally {
            rig.close()
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
    }

    @Test fun `zugesagte Ruhe gilt, sobald die Zusage verklungen ist, auch wenn das Modell noch weiterschreibt`() {
        val quiet = "Alfred, sei bitte zehn Minuten ruhig"
        assertTrue("\"$quiet\" ist als Bitte um Ruhe zu erkennen", Heuristics.asksForQuiet(quiet))
        val claude = Staged { t ->
            if (t.contains("ruhig")) rambling("Sehr wohl, Sir.", "MUTE: 10") else listOf("frage" to listOf("SAY kurz\n", "Um acht."))
        }
        val rig = ThreadRig(claude, withProfile)
        try {
            claude.open("anfang")
            claude.open("frage") // eine Frage bekäme sofort ihre Antwort
            rig.wearerSays(quiet)
            assertTrue(info(rig, claude), waitFor(3_000) { rig.speaker.spoken.isNotEmpty() })
            assertEquals(listOf("Sehr wohl, Sir."), rig.speaker.spoken.toList())
            Thread.sleep(300) // der Ton ist längst zu Ende, das Modell hängt noch hinter dem Deckel

            // In dieser Lücke fragt er etwas ohne Anrede. Die Ruhe ist zugesagt: Darauf kommt nichts mehr.
            rig.clock.set(2_000)
            rig.wearerSays("Wann fängt der Film an?")
            waitFor(700) { rig.speaker.spoken.size > 1 }
            claude.open("schluss")
            assertTrue("die Ruhe wird am Ende eingetragen: ${info(rig, claude)}", waitFor(3_000) { rig.state.value.mutedUntilMs > 0 })
            Thread.sleep(200)
            assertEquals(info(rig, claude), listOf("Sehr wohl, Sir."), rig.speaker.spoken.toList())
        } finally {
            rig.close()
        }
    }

    @Test fun `redet der Traeger nach der zugesagten Ruhe einfach weiter, wird sie trotzdem eingetragen`() {
        // Warum der Auftrag nach einer Bitte um Ruhe nicht einfach stehen bleibt, bis MUTE gelesen ist: Er gälte
        // so lange als sprechend. Wer dem sprechenden Assistenten ins Wort fällt, bringt ihn zum Schweigen, und
        // das bräche den Aufruf ab, bevor die Sonderzeile da ist. Die Ruhe wäre zugesagt und nie eingetragen.
        val claude = Staged { rambling("Sehr wohl, Sir.", "MUTE: 10") }
        val rig = ThreadRig(claude, withProfile)
        try {
            claude.open("anfang")
            rig.wearerSays("Alfred, sei bitte zehn Minuten ruhig")
            assertTrue(info(rig, claude), waitFor(3_000) { rig.speaker.spoken.isNotEmpty() && !rig.state.value.speaking })

            // Er wendet sich wieder seinem Gespräch zu: ein längerer Satz mit seiner eigenen Stimme.
            rig.clock.set(2_000)
            val more = "so jetzt kann ich mich endlich wieder auf den Aufsatz konzentrieren"
            rig.pipeline.onAudio(silence(300), 9600)
            tone(115.0, 8000.0, 600).let { rig.pipeline.onAudio(it, it.size) }
            rig.pipeline.onPartial(more, null)
            rig.pipeline.onCommitted(more, null, null)

            claude.open("schluss")
            assertTrue("die zugesagte Ruhe wurde nie eingetragen: ${info(rig, claude)}", waitFor(3_000) { rig.state.value.mutedUntilMs > 0 })
            assertEquals(listOf("Sehr wohl, Sir."), rig.speaker.spoken.toList())
            assertEquals("über den Satz urteilt kein Modell: Er ist stumm", 1, claude.calls.size)
        } finally {
            rig.close()
        }
    }

    @Test fun `zugesagte Ruhe gilt auch fuer eine Frage, deren Antwort erst nach dem Stummschalten fertig ist`() {
        // Hier ist der Bitte nicht anzusehen, dass sie um Ruhe bittet: Der Auftrag endet mit dem Ton, und der
        // Leser trägt die Ruhe ein, sobald das Modell MUTE schreibt. Eine Frage, die davor angenommen wurde und
        // deren Antwort danach fertig wird, bleibt ungesprochen. Geprüft wird stumm also auch dort, wo der Ton
        // freigegeben wird, nicht nur beim Annehmen.
        val request = "Alfred, ich muss mich jetzt konzentrieren"
        assertFalse(Heuristics.asksForQuiet(request))
        val claude = Staged { t ->
            if (t.contains("konzentrieren")) rambling("Sehr wohl, Sir.", "MUTE: 10") else listOf("frage" to listOf("SAY kurz\n", "Um acht."))
        }
        val rig = ThreadRig(claude, withProfile)
        try {
            claude.open("anfang")
            rig.wearerSays(request)
            assertTrue(info(rig, claude), waitFor(3_000) { rig.speaker.spoken.isNotEmpty() && !rig.state.value.speaking })

            rig.clock.set(2_000)
            rig.wearerSays("Wann fängt der Film an?")
            assertTrue("die Frage geht ans Modell: ${info(rig, claude)}", claude.awaitStage("frage"))
            claude.open("schluss")
            assertTrue("die Ruhe ist eingetragen: ${info(rig, claude)}", waitFor(3_000) { rig.state.value.mutedUntilMs > 0 })

            rig.clock.set(2_500)
            claude.open("frage") // jetzt erst ist die Antwort fertig
            waitFor(2_000) { rig.speaker.spoken.size > 1 || rig.state.value.lastDecision == "übersprungen: stumm" }
            assertEquals("obwohl schon stumm geschaltet war, wurde noch geantwortet", listOf("Sehr wohl, Sir."), rig.speaker.spoken.toList())
            assertEquals("übersprungen: stumm", rig.state.value.lastDecision)
        } finally {
            rig.close()
        }
    }

    @Test fun `Ruhe, die erst am Ende des Aufrufs zugesagt wird, gilt auch fuer den Nachsatz, der davor wartete`() {
        // Der Träger bittet um Ruhe und schiebt gleich einen Satz nach, der hinter seiner Bitte zurücksteht.
        // Nach der Bestätigung rückte er früher als Nachfrage nach, und seine Antwort war zu hören, obwohl die
        // Anzeige die Stummschaltung zeigte. Beide Wortlaute: einmal als Bitte um Ruhe erkennbar, einmal nicht.
        val remark = "ich hab gestern den ganzen Abend an dem Aufsatz gesessen"
        for (request in listOf("Alfred, sei bitte zehn Minuten ruhig", "Alfred, ich muss mich jetzt konzentrieren")) {
            val claude = Staged { t ->
                if (t.contains("Alfred")) rambling("Sehr wohl, Sir.", "MUTE: 10") else listOf("zweite" to listOf("SAY kurz\n", "Fleißig, Sir."))
            }
            val rig = ThreadRig(claude, withProfile)
            try {
                rig.wearerSays(request)
                assertTrue(claude.awaitStage("anfang"))
                rig.clock.set(500)
                rig.wearerSays(remark) // steht hinter der Bitte zurück
                rig.clock.set(1_000)
                claude.open("anfang")
                assertTrue(info(rig, claude), waitFor(3_000) { rig.speaker.spoken.isNotEmpty() })
                Thread.sleep(300) // die Bestätigung ist verklungen, das Modell schreibt noch

                claude.open("schluss")
                assertTrue("die Ruhe gilt: ${info(rig, claude)}", waitFor(3_000) { rig.state.value.mutedUntilMs > 0 })
                claude.open("zweite") // wäre der Nachsatz vorgelegt worden, käme jetzt seine Antwort
                Thread.sleep(400)
                assertEquals("$request: ${info(rig, claude)}", listOf("Sehr wohl, Sir."), rig.speaker.spoken.toList())
            } finally {
                rig.close()
            }
        }
    }

    @Test fun `spricht der Traeger den Assistenten vor der nachgelesenen Ruhe wieder an, wird sie nicht mehr eingetragen`() {
        // Die Bestätigung ist verklungen, das Modell schreibt noch, MUTE steht ganz am Schluss. In dieser Lücke
        // sagt der Träger "Alfred, du darfst wieder". Eine Anrede hebt die Ruhe auf; früher trug der Leser sie
        // danach trotzdem ein, und der Assistent schwieg zehn Minuten, obwohl er eben wieder reden durfte.
        // Beide Wortlaute: einmal als Bitte um Ruhe erkennbar (die Ruhe gilt dann schon vorläufig), einmal nicht.
        for (request in listOf("Alfred, sei bitte zehn Minuten ruhig", "Alfred, ich muss mich jetzt konzentrieren")) {
            val claude = Staged { t ->
                when {
                    t.contains("darfst wieder") -> listOf("wieder" to listOf("SAY kurz\n", "Gern, Sir."))
                    t.contains("Film") -> listOf("frage" to listOf("SAY kurz\n", "Um acht."))
                    else -> rambling("Sehr wohl, Sir.", "MUTE: 10")
                }
            }
            val rig = ThreadRig(claude, withProfile)
            try {
                claude.open("anfang")
                claude.open("wieder")
                claude.open("frage")
                rig.wearerSays(request)
                assertTrue(info(rig, claude), waitFor(3_000) { rig.speaker.spoken.isNotEmpty() && !rig.state.value.speaking })

                rig.clock.set(2_000)
                rig.wearerSays("Alfred, du darfst wieder")
                assertTrue("$request: ${info(rig, claude)}", waitFor(3_000) { rig.speaker.spoken.size == 2 })

                claude.open("schluss") // jetzt erst schreibt das Modell die Sonderzeile
                assertTrue(info(rig, claude), waitFor(3_000) { claude.finished.size == 2 })
                Thread.sleep(200)
                assertEquals("$request: die Ruhe wurde nach der Anrede noch eingetragen", 0L, rig.state.value.mutedUntilMs)

                // Und er hört wieder zu: Die nächste Frage ohne Anrede wird beantwortet.
                rig.clock.set(30_000)
                rig.wearerSays("Wann fängt der Film an?")
                assertTrue("$request: ${info(rig, claude)}", waitFor(3_000) { rig.speaker.spoken.size == 3 })
            } finally {
                rig.close()
            }
        }
    }

    @Test fun `Kontrolle - ohne Stummschaltung wird die Frage in der Luecke beantwortet`() {
        // Die Gegenprobe zu den Tests oben: Hängt am Schluss nur eine Notiz, hört der Assistent wieder zu, sobald
        // der Ton zu Ende ist (so prüft es auch WiringTest). Die Ruhe-Regeln dürfen das nicht mit abschalten.
        val claude = Staged { t ->
            if (t.contains("merk dir")) rambling("Notiert, Sir.", "MEMO: Kennzeichen M-AB 1234") else listOf("frage" to listOf("SAY kurz\n", "Um acht."))
        }
        val rig = ThreadRig(claude, withProfile)
        try {
            claude.open("anfang")
            claude.open("frage")
            rig.wearerSays("Alfred, merk dir das Kennzeichen M-AB 1234")
            assertTrue(info(rig, claude), waitFor(3_000) { rig.speaker.spoken.isNotEmpty() && !rig.state.value.speaking })
            rig.clock.set(2_000)
            rig.wearerSays("Wann fängt der Film an?")
            assertTrue(info(rig, claude), waitFor(3_000) { rig.speaker.spoken.size == 2 })
            assertEquals(listOf("Notiert, Sir.", "Um acht."), rig.speaker.spoken.toList())
            claude.open("schluss")
            assertTrue(waitFor(3_000) { rig.env.notes.isNotEmpty() })
        } finally {
            rig.close()
        }
    }
}
