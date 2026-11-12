package app.claudevoice.core

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceClassifierTest {
    private fun f(level: Double, pitch: Double? = 115.0) = VoiceFeatures(level, pitch, 800)

    @Test fun `AirPods mit Profil trennen eigene Stimme, Gegenueber und Nachbartisch`() {
        val c = VoiceClassifier(MicMode.AIRPODS, VoiceProfile(-18.0, 115.0, 5))
        assertEquals(Voice.ICH, c.classify(f(-20.0)))
        assertEquals(Voice.NAH, c.classify(f(-34.0, 210.0)))
        assertEquals(Voice.FERN, c.classify(f(-47.0, 150.0)))
        assertEquals(Voice.UNKLAR, c.classify(null))
    }

    @Test fun `laute fremde Stimmlage ist nah, aber nicht ich`() {
        val c = VoiceClassifier(MicMode.AIRPODS, VoiceProfile(-18.0, 115.0, 5))
        assertEquals(Voice.NAH, c.classify(f(-19.0, 230.0)))
    }

    @Test fun `ohne Profil dient die lauteste Stimme der Sitzung als Bezug`() {
        val c = VoiceClassifier(MicMode.AIRPODS)
        assertEquals(Voice.ICH, c.classify(f(-20.0)))
        assertEquals(Voice.NAH, c.classify(f(-35.0)))
        assertEquals(Voice.FERN, c.classify(f(-50.0)))
        assertEquals(Voice.ICH, c.classify(f(-21.0)))
    }

    @Test fun `Handy braucht die Tonhoehe fuer ICH`() {
        val known = VoiceClassifier(MicMode.HANDY, VoiceProfile(-25.0, 115.0, 5))
        assertEquals(Voice.ICH, known.classify(f(-28.0, 118.0)))
        assertEquals(Voice.NAH, known.classify(f(-28.0, 200.0)))
        assertEquals(Voice.FERN, known.classify(f(-45.0, 118.0)))
        val unknown = VoiceClassifier(MicMode.HANDY)
        assertEquals(Voice.NAH, unknown.classify(f(-28.0, 118.0)))
    }

    @Test fun `Handy ohne gelernte Tonhoehe vergibt nie ICH und nennt nahe Stimmen NAH`() {
        // Laut, mittel, leise, mit und ohne messbare Tonhöhe: Nie darf daraus ICH werden, das hieße Vertrauen
        // ohne Nachweis. Nahe Stimmen bleiben NAH: Daran hängt in der Pipeline die Vorsicht gegenüber Stimmen,
        // die nicht als die des Trägers erkannt sind (abwarten, ob ein Mensch antwortet; "Nur ich"; Nachfragen).
        // Dass am Handy auch der Träger hinter NAH stecken kann, sagt erst Persona.userMessage dem Modell.
        for (c in listOf(VoiceClassifier(MicMode.HANDY), VoiceClassifier(MicMode.HANDY, VoiceProfile(-25.0, null, 3)))) {
            assertEquals(Voice.NAH, c.classify(f(-26.0, 115.0)))
            assertEquals(Voice.NAH, c.classify(f(-30.0, 210.0)))
            assertEquals(Voice.NAH, c.classify(f(-34.0, null)))
            assertEquals(Voice.FERN, c.classify(f(-45.0, 115.0)))
            assertEquals(Voice.FERN, c.classify(f(-60.0, 115.0)))
            val seen = (0..60).flatMap { step -> listOf(115.0, 180.0, null).map { pitch -> c.classify(f(-10.0 - step, pitch)) } }.toSet()
            assertEquals(setOf(Voice.NAH, Voice.FERN), seen)
        }
    }

    @Test fun `Profil wird aus Anreden gelernt und gemittelt`() {
        val c = VoiceClassifier(MicMode.AIRPODS)
        c.learnOwn(f(-20.0, 110.0))
        c.learnOwn(f(-16.0, 120.0))
        val p = c.profile!!
        assertEquals(-18.0, p.levelDb, 0.01)
        assertEquals(115.0, p.pitchHz!!, 0.01)
        assertEquals(2, p.samples)
    }
}

class ConversationBufferV2Test {
    @Test fun `Mitschrift nennt Alter, Stimme, Sprecher und markiert die aktuelle Zeile`() {
        val b = ConversationBuffer()
        b.add("wir waren letztes Jahr auf der Zugspitze", Voice.NAH, 1_000, "S2")
        val tv = b.add("und nun das Wetter für morgen", Voice.FERN, 5_000)!!
        b.add("krass", Voice.ICH, 9_000, "S1")
        b.markAside(setOf(tv.id), setOf(tv.id))
        val trigger = Line(99, 21_000, Voice.ICH, "wie hoch ist die eigentlich", "S1")
        assertEquals(
            "[vor 20 s] NAH S2: wir waren letztes Jahr auf der Zugspitze\n" +
                "[vor 16 s] (abseits) FERN: und nun das Wetter für morgen\n" +
                "[vor 12 s] ICH S1: krass\n" +
                ">> [jetzt] ICH S1: wie hoch ist die eigentlich",
            ConversationBuffer.render(b.snapshot(21_000), trigger),
        )
    }

    @Test fun `bei Platzmangel fliegt zuerst, was abseits ist`() {
        val b = ConversationBuffer(maxChars = 20)
        val a = b.add("aaaaaaaa", Voice.NAH, 0)!!
        val x = b.add("xxxxxxxx", Voice.FERN, 1)!!
        b.markAside(setOf(x.id), setOf(a.id, x.id))
        b.add("cccccccc", Voice.ICH, 2)
        assertEquals(listOf("aaaaaaaa", "cccccccc"), b.snapshot(3).map { it.text })
    }

    @Test fun `alte Zeilen fallen aus dem Zeitfenster`() {
        val b = ConversationBuffer(windowMs = 1000)
        b.add("alt", Voice.NAH, 0)
        b.add("neu", Voice.ICH, 900)
        assertEquals(listOf("neu"), b.snapshot(1500).map { it.text })
    }

    @Test fun `Diarisierung kann Sprecher und Text nachtragen`() {
        val b = ConversationBuffer()
        val l = b.add("hallo wie gehts gut danke", Voice.NAH, 0)!!
        b.relabel(l.id, voice = Voice.ICH, speaker = "S1", text = "S1: hallo wie gehts / S2: gut danke")
        val now = b.snapshot(1).single()
        assertEquals(Voice.ICH, now.voice); assertEquals("S1", now.speaker)
        b.renameSpeaker("S1", "S3")
        assertEquals("S3", b.snapshot(1).single().speaker)
    }
}

class SpeakerMapTest {
    @Test fun `Kuerzel in Reihenfolge des Auftretens, Traeger per Abstimmung`() {
        val m = SpeakerMap()
        assertEquals("S1", m.label("speaker_4")); assertEquals("S2", m.label("speaker_0")); assertEquals("S1", m.label("speaker_4"))
        assertEquals(null, m.wearer())
        m.observe("speaker_0", Voice.ICH); m.observe("speaker_4", Voice.NAH)
        assertEquals(null, m.wearer()) // eine Stimme reicht nicht
        m.observe("speaker_0", Voice.ICH); m.observe("speaker_0", Voice.NAH)
        assertEquals("speaker_0", m.wearer())
    }

    @Test fun `zusammengelegte Sprecher teilen Stimmen und Kuerzel`() {
        val m = SpeakerMap()
        m.label("a"); m.observe("a", Voice.ICH)
        m.observe("b", Voice.ICH)
        m.merge("b", "a")
        assertEquals("a", m.wearer())
        m.reset()
        assertEquals("S1", m.label("zzz"))
    }
}

class BrainTest {
    @Test fun `Ordner-Antwort wird in Szene und Zeilen-IDs uebersetzt`() {
        val lines = listOf(Line(11, 0, Voice.ICH, "a"), Line(12, 0, Voice.FERN, "b"), Line(13, 0, Voice.NAH, "c"))
        val t = Brain.parseTidy("SZENE: WG-Party, er spricht mit Jonas über Berge.\nABSEITS: 2, 9", lines)
        assertEquals("WG-Party, er spricht mit Jonas über Berge.", t.scene)
        assertEquals(setOf(12L), t.aside)
        assertEquals(emptySet<Long>(), Brain.parseTidy("SZENE: unklar\nABSEITS: -", lines).aside)
    }

    @Test fun `System-Prompt ist stabil und enthaelt Name, Anrede, Gedaechtnis`() {
        val p = Profile(name = "Alfred", address = "Sir", about = "Wohnt in Bayern.", notes = listOf("Kennzeichen M-AB 1234"))
        val a = Persona.systemPrompt(p)
        assertEquals(a, Persona.systemPrompt(p)) // byte-gleich, sonst greift das Prompt-Caching nicht
        for (needle in listOf("Du bist Alfred", "„Sir“", "Wohnt in Bayern.", "Kennzeichen M-AB 1234", "SKIP <grund>", "SAY <länge>"))
            assert(a.contains(needle)) { needle }
    }

    @Test fun `Nutzer-Nachricht enthaelt Lage und markierte Mitschrift`() {
        val turn = Turn(
            listOf(Line(1, 1_000, Voice.NAH, "wir waren auf der Zugspitze", "S2")),
            Line(2, 9_000, Voice.ICH, "wie hoch ist die", "S1"),
            Situation("Donnerstag, 21:14", MicMode.AIRPODS, musicPlaying = true, assistantSpokeAgoSec = 30, scene = "Party", sessionNotes = listOf("x")),
        )
        val m = Persona.userMessage(turn)
        for (needle in listOf("Zeit: Donnerstag, 21:14", "Seine Musik läuft: ja", "vor 30 s", "Szene: Party", "<heute_gemerkt>", "[vor 8 s] NAH S2: wir waren", ">> [jetzt] ICH S1: wie hoch ist die"))
            assert(m.contains(needle)) { needle }
    }
}
