package app.claudevoice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prüft, was Code und Anweisungen einander versprechen.
 *
 * Ob das Modell mit den Anweisungen gut entscheidet, zeigt nur die Auswertung (`:core:evalLive`). Diese
 * Tests sichern die Stellen ab, an denen der Text des Prompts und der Code zusammenpassen müssen: Der
 * Prompt zitiert Zeilen der Lage wörtlich, verweist auf Abschnitte und beschreibt ein Format, das ein
 * Programm liest. Ändert jemand nur eine der beiden Seiten, fällt es hier auf.
 */
class AnweisungenTest {
    private val prompt = Persona.systemPrompt(Profile(aliases = listOf("Jarvis")))

    private fun turn(mic: MicMode, voice: Voice, verified: Boolean) = Turn(
        listOf(Line(1, 1_000, Voice.UNKLAR, "warst du schon mal in Lissabon", "S1")),
        Line(2, 9_000, voice, "wie viele Einwohner hat Lissabon", "S1"),
        Situation("Donnerstag, 12. November 2026, 17:40", mic, musicPlaying = false, assistantSpokeAgoSec = null, scene = null, speakerVerified = verified),
    )

    // ------------------------------------------------------------------------------------------
    // Handy-Mikrofon

    private val phoneHint = "seine eigene Stimme ist in dieser Mitschrift nicht von anderen zu unterscheiden und steht meist als ? da, nicht als ICH"

    @Test fun `am Handy-Mikrofon ohne erkanntes ICH liest das Modell unbekannt statt NAH und erfaehrt warum`() {
        // So kommt es vom Stimmenfilter: Am Handy ist jede nahe Stimme NAH, auch die des Trägers.
        val phone = Persona.userMessage(
            Turn(
                listOf(
                    Line(1, 1_000, Voice.NAH, "warst du schon mal in Lissabon", "S1"),
                    Line(2, 4_000, Voice.NAH, "nee aber Porto fand ich super", "S2"),
                    Line(3, 6_000, Voice.FERN, "zahlen bitte", "S3"),
                    Line(4, 7_000, Voice.ALFRED, "Rund eine halbe Million."),
                    Line(5, 8_000, Voice.NAH, "ach so", "S2").copy(aside = true),
                ),
                Line(6, 9_000, Voice.NAH, "wie viele Einwohner hat Lissabon", "S1"),
                Situation("Donnerstag, 12. November 2026, 17:40", MicMode.HANDY, musicPlaying = false, assistantSpokeAgoSec = 2, scene = null),
            ),
        )
        assertTrue(phone, phone.contains("Mikrofon: Handy-Mikrofon (nimmt den ganzen Raum auf; $phoneHint)\n"))
        val transcript = phone.substringAfter("<mitschrift>\n").substringBefore("\n</mitschrift>")
        assertEquals(
            """
[vor 8 s] ? S1: warst du schon mal in Lissabon
[vor 5 s] ? S2: nee aber Porto fand ich super
[vor 3 s] FERN S3: zahlen bitte
[vor 2 s] ALFRED: Rund eine halbe Million.
[vor 1 s] (abseits) ? S2: ach so
>> [jetzt] ? S1: wie viele Einwohner hat Lissabon
""".trim(),
            transcript,
        )
        // Am Kopfhörer-Mikrofon bleibt alles, wie es war: NAH ist dort eine echte Aussage.
        val headset = Persona.userMessage(turn(MicMode.AIRPODS, Voice.NAH, verified = false))
        assertTrue(headset, headset.contains("Mikrofon: Kopfhörer-Mikrofon (seine eigene Stimme ist mit Abstand die lauteste)\n"))
        assertTrue(headset, headset.contains(">> [jetzt] NAH S1: wie viele Einwohner hat Lissabon"))
        assertFalse(headset, headset.contains("nicht von anderen zu unterscheiden"))
    }

    @Test fun `steht am Handy-Mikrofon irgendwo ein ICH, entfaellt der Hinweis und NAH bleibt NAH`() {
        // Ein ICH am Handy gibt es nur mit Stimmprofil. Dann wäre "nicht als ICH" falsch, und NAH heißt wieder
        // "jemand anderes". Die Lage darf nie etwas behaupten, dem die Mitschrift darunter widerspricht.
        val history = listOf(Line(1, 1_000, Voice.ICH, "warst du schon mal in Lissabon", "S1"))
        val friend = Line(2, 9_000, Voice.NAH, "wie viele Einwohner hat Lissabon", "S2")
        val situation = Situation("Donnerstag, 12. November 2026, 17:40", MicMode.HANDY, musicPlaying = false, assistantSpokeAgoSec = null, scene = null)
        val seen = Persona.userMessage(Turn(history, friend, situation))
        assertTrue(seen, seen.contains("Mikrofon: Handy-Mikrofon (nimmt den ganzen Raum auf)\n"))
        assertTrue(seen, seen.contains("[vor 8 s] ICH S1: warst du schon mal in Lissabon\n>> [jetzt] NAH S2: wie viele Einwohner hat Lissabon"))
        // Dasselbe, wenn die auslösende Äußerung selbst das ICH trägt.
        val own = Persona.userMessage(Turn(listOf(friend.copy(atMs = 1_000)), history[0].copy(atMs = 9_000), situation.copy(speakerVerified = true)))
        assertFalse(own, own.contains("nicht als ICH"))
        assertTrue(own, own.contains("[vor 8 s] NAH S2: wie viele Einwohner hat Lissabon\n>> [jetzt] ICH S1: warst du schon mal in Lissabon"))
    }

    @Test fun `der Schluss vom fehlenden ICH aufs blosse Zuhoeren gilt nur am Kopfhoerer-Mikrofon`() {
        val sorting = prompt.substringAfter("Deshalb sortierst du zuerst selbst").substringBefore("# Wann du sprichst")
        assertTrue(sorting.contains("Stammt am Kopfhörer-Mikrofon keine Zeile erkennbar von ihm"))
        assertTrue(sorting.contains("Am Handy-Mikrofon darfst du so nicht schließen"))
        assertFalse("die Regel darf nicht mehr für jedes Mikrofon gelten", sorting.contains("- Stammt keine Zeile erkennbar von ihm"))
        // Die Gegenrichtung: Auch am Handy gibt es bloßes Zuhören (Fremde im Zug). Ohne ICH bleibt dafür als
        // Anhalt, wer den Assistenten angesprochen hat, und ob dieser Sprecher im Gespräch der anderen vorkommt.
        // Dafür müssen die Sprecher-Kürzel, auf die sich der Satz stützt, in der Mitschrift auch stehen.
        assertTrue(sorting.contains("Hat eine Stimme dich mit Namen angesprochen, ist das meist er. Reden danach nur andere Sprecher-Kürzel miteinander,"))
        assertTrue(prompt.contains("Dahinter steht manchmal ein Sprecher-Kürzel wie S1 oder S2"))
        // Die Namen der Mikrofone im Prompt sind die, die auch in der Lage stehen.
        for (mic in MicMode.entries) {
            val named = Persona.userMessage(turn(mic, Voice.UNKLAR, verified = false)).substringAfter("Mikrofon: ").substringBefore(" (")
            assertTrue(named, sorting.contains(named))
        }
    }

    // ------------------------------------------------------------------------------------------
    // Noch einmal fragen

    @Test fun `wer nach der Antwort noch einmal fragt, bekommt sie noch einmal`() {
        val speak = prompt.substringAfter("# Wann du sprichst").substringBefore("Schweig, wenn")
        val silent = prompt.substringAfter("Schweig, wenn").substringBefore("# Wie du antwortest")
        assertTrue(speak.contains("oder sie noch einmal hören will („nochmal“, „wie bitte?“, dieselbe Frage erneut)"))
        // Die Schweige-Regel traf früher auch den Träger selbst ("du dieselbe Frage eben erst beantwortet hast").
        assertFalse(silent.contains("du dieselbe Frage eben erst beantwortet hast"))
        assertTrue(silent.contains("- jemand anderes die Frage wiederholt, die du eben beantwortet hast"))
        // Der Grund bleibt im Format bekannt, sonst hätte die neue Schweige-Regel kein Stichwort.
        assertTrue(prompt.substringAfter("Mögliche Gründe:").substringBefore("\n").contains("wiederholung"))
    }

    // ------------------------------------------------------------------------------------------
    // Was er nicht kann

    @Test fun `Anweisungen nennen, was der Assistent nicht kann`() {
        val limits = prompt.substringAfter("# Was du nicht kannst\n").substringBefore("\n# ")
        for (needle in listOf("kein Internet", "keine aktuellen Daten", "Wetter", "Spielstände", "keinen Timer", "nicht später an etwas erinnern", "kein Wecker")) {
            assertTrue(needle, limits.contains(needle))
        }
        // Eine Erinnerung darf nicht als Notiz enden: Die läge für immer im Gedächtnis und erinnerte niemanden.
        assertTrue(limits.contains("für eine Erinnerung hängst du sie nicht an"))
        assertTrue(limits.contains("Bestätige nichts, was du nicht ausführst."))
    }

    @Test fun `jeder Verweis auf einen Abschnitt fuehrt zu einer Ueberschrift`() {
        val headings = Regex("^# (.+)$", RegexOption.MULTILINE).findAll(prompt).map { it.groupValues[1] }.toSet()
        val references = Regex("siehe „([^“]+)“").findAll(prompt).map { it.groupValues[1] }.toList()
        assertTrue("es gibt Verweise", references.isNotEmpty())
        for (r in references) assertTrue("Abschnitt „$r“ fehlt, vorhanden: $headings", r in headings)
    }

    @Test fun `die beste Einschaetzung gilt nicht fuer Dinge, die er nachsehen muesste`() {
        val how = prompt.substringAfter("# Wie du antwortest").substringBefore("# Was du nicht kannst")
        assertTrue(how.contains("nenne deine beste Einschätzung; sonst schweig. Was du nachsehen müsstest (siehe „Was du nicht kannst“), schätzt du nicht."))
        // Veraltetes Wissen: Der Prompt verweist auf das Datum, also muss die Lage eines nennen und der Prompt sie so beschreiben.
        assertTrue(how.contains("Dein Wissen endet an einem Stichtag, und das Datum in der Lage liegt oft mehr als ein Jahr danach."))
        assertTrue(prompt.contains("Du bekommst die Lage (Datum und Uhrzeit, "))
        assertTrue(Persona.userMessage(turn(MicMode.AIRPODS, Voice.ICH, verified = true)).contains("Zeit: Donnerstag, 12. November 2026, 17:40\n"))
    }

    // ------------------------------------------------------------------------------------------
    // MUTE und die Ablehnung bei ungesicherter Stimme

    @Test fun `Anweisungen zitieren die Zeile der Lage woertlich, an der Merken und Stillsein haengen`() {
        val unverified = Persona.userMessage(turn(MicMode.AIRPODS, Voice.NAH, verified = false))
        val line = unverified.lines().single { it.startsWith("Sprecher der letzten Äußerung:") }
        assertEquals("Sprecher der letzten Äußerung: nicht gesichert", line)
        // Der Prompt nennt genau diese Zeile. Ändert sich ihr Wortlaut nur im Code, greift die Regel nicht mehr.
        assertTrue(prompt.contains("steht in der Lage „$line“, dann hängst du keine Sonderzeile an und bestätigst die Bitte mit keinem Wort."))
        // Die Absage ersetzt die Bestätigung, aber nicht das Schweigen: Macht sich erkennbar ein anderer einen
        // Spaß mit dem Namen, bleibt es dabei, dass der Assistent nichts sagt.
        assertTrue(prompt.contains("Kommt sie erkennbar von jemand anderem, der dich vorführen will oder sich einen Spaß macht, schweigst du wie sonst auch (SKIP)."))
        // Der alte Satz klang für den Träger, der als Einziger zuhört, als hielte der Assistent ihn für einen Fremden.
        assertFalse(prompt.contains("dass du das nur von ihm selbst annimmst"))
        assertTrue(prompt.contains("ob die Stimme der letzten Äußerung als seine gesichert ist"))
        val verified = Persona.userMessage(turn(MicMode.AIRPODS, Voice.ICH, verified = true))
        assertTrue(verified.contains("Sprecher der letzten Äußerung: nach der Stimme er selbst\n"))
    }

    @Test fun `die Absage behauptet nichts, was in einer der Lagen falsch waere`() {
        val rule = prompt.lines().single { it.startsWith("Beides nimmst du nur von ihm selbst an.") }
        // "Ihre Stimme habe ich gerade nicht erkannt" stimmte nur, wenn er selbst leise gesprochen hat. Bittet
        // ein anderer, hat der Träger gar nichts gesagt; am Handy liegt es nicht am Augenblick, sondern am Mikrofon.
        assertFalse(rule, rule.contains("seine Stimme gerade nicht sicher erkannt"))
        // Am Kopfhörer stimmt "nicht sicher, wer das gesagt hat" in beiden Fällen: wenn er zu leise war und wenn
        // ein anderer gebeten hat. Am Handy nennt der Satz die Ursache, die dort gilt, und den Ausweg;
        // "verlässlich", weil es mit einem gespeicherten Stimmprofil auch am Handy gelingen kann.
        val headset = "„Das habe ich nicht übernommen, ich bin nicht sicher, wer das gesagt hat.“"
        val phone = "„Das habe ich nicht übernommen, Merken und Stillsein gehen verlässlich nur mit dem Kopfhörer-Mikrofon.“"
        assertTrue(rule, rule.contains("Nennt die Lage das Kopfhörer-Mikrofon, etwa: $headset Nennt sie das Handy-Mikrofon, etwa: $phone"))
        // Die Beispielsätze kommen ohne Anrede aus. Die Anrede (Sie, du, "Sir") steht in den Einstellungen; ein
        // fest eingebautes "Sie" spräche gegen einen Charakter, der duzt.
        for (word in Regex("\\p{L}+").findAll(headset + phone).map { it.value.lowercase() }) {
            assertFalse(word, word in setOf("sie", "ihnen", "ihre", "du", "dir", "deine", "sir"))
        }
        // Keine Aufforderung, es noch einmal zu sagen: Am Handy führte sie im Kreis.
        assertFalse(rule, rule.contains("noch einmal"))
        // Die Namen der Mikrofone in der Regel sind die, die in der Lage stehen; sonst erkennt das Modell den Fall nicht.
        for (mic in MicMode.entries) {
            val named = Persona.userMessage(turn(mic, Voice.UNKLAR, verified = false)).substringAfter("Mikrofon: ").substringBefore(" (")
            assertTrue(named, rule.contains(named))
        }
    }

    @Test fun `MUTE verlangt Minuten in Ziffern und nennt Ersatzwerte`() {
        val mute = prompt.lines().single { it.startsWith("MUTE: <minuten>") }
        for (needle in listOf(
            "nur eine ganze Zahl in Ziffern, in Minuten und ohne Einheit, zum Beispiel MUTE: 30.",
            "Die Regel, Zahlen auszuschreiben, gilt nur für den Sprechtext.",
            "eine Stunde ist MUTE: 60",
            "Nennt er keine Dauer („erstmal“, „eine Weile“), nimm 30",
            "bei „bis morgen“ oder „für heute“ nimm 600",
            "nie mit SKIP: Nach SKIP liest das Programm nichts mehr.",
        )) assertTrue(needle, mute.contains(needle))
    }

    @Test fun `was die Anweisungen als MUTE-Zeile vorschreiben, liest die App als Minuten`() {
        // Jedes Beispiel aus dem Prompt, so wie das Modell es schreiben soll, einmal durch den echten Leser.
        fun minutes(line: String): List<Int> {
            val out = ArrayList<Int>()
            val parser = ReplyParser(onDecision = {}, onSpeech = {}, onDirective = { if (it is Directive.Mute) out += it.minutes })
            parser.feed("SAY kurz\nSehr wohl.\n$line")
            parser.finish()
            return out
        }
        assertEquals(listOf(30), minutes("MUTE: 30"))
        assertEquals(listOf(60), minutes("MUTE: 60"))
        assertEquals(listOf(600), minutes("MUTE: 600"))
        assertEquals(listOf(120), minutes("MUTE: 120"))
    }

    // ------------------------------------------------------------------------------------------
    // Ordner

    @Test fun `Ordner erfaehrt vom Assistenten im Ohr und wie er Nummern schreiben soll`() {
        val system = Persona.TIDY_SYSTEM
        assertTrue(system.startsWith("Du ordnest")) // daran erkennen Tests und Attrappen den Ordner-Aufruf
        assertTrue(system.contains("Der Träger hat einen Sprachassistenten im Ohr, den er mit Namen anspricht und dessen Antworten nur er hört; sie stehen nicht in der Mitschrift."))
        assertTrue(system.contains("Schreib dann „Er ist allein“ und vermute kein Telefonat."))
        assertTrue(system.contains("ABSEITS: <jede Nummer einzeln und mit Komma getrennt, also 3, 4, 5 und nicht 3-5; nur Zeilennummern, keine Sprecher-Kürzel; ein Strich, wenn keine>"))
    }

    @Test fun `Ordner-Nachricht nennt die Namen des Assistenten und den Hinweis zum Handy-Mikrofon`() {
        // Vom Stimmenfilter kommt am Handy NAH; der Ordner liest wie das Antwort-Modell "?".
        val lines = listOf(
            Line(7, 0, Voice.NAH, "Alfred, wie hoch ist die Zugspitze?", "S1"),
            Line(8, 2_000, Voice.FERN, "und nun das Wetter", "S2"),
        )
        val expected = """
Mikrofon: Handy-Mikrofon (die Stimme des Trägers ist in dieser Mitschrift nicht von anderen zu unterscheiden und steht meist als ? da, nicht als ICH)
Namen des Assistenten: Alfred, Jarvis
Bisherige Einschätzung der Szene: keine
<mitschrift>
1. [vor 5 s] ? S1: Alfred, wie hoch ist die Zugspitze?
2. [vor 3 s] FERN S2: und nun das Wetter
</mitschrift>
""".trim()
        assertEquals(expected, Persona.tidyMessage(lines, null, MicMode.HANDY, 5_000, listOf("Alfred", "Jarvis")))
        // Ohne Namen und am Kopfhörer-Mikrofon bleibt die Nachricht, wie sie war.
        val plain = Persona.tidyMessage(lines, "Er ist allein.", MicMode.AIRPODS, 5_000)
        assertTrue(plain, plain.startsWith("Mikrofon: Kopfhörer-Mikrofon\nBisherige Einschätzung der Szene: Er ist allein.\n<mitschrift>\n1. [vor 5 s] NAH S1: Alfred"))
        // Mit einem erkannten ICH am Handy (Stimmprofil vorhanden) entfällt der Hinweis, und NAH bleibt NAH.
        val known = Persona.tidyMessage(lines + Line(9, 4_000, Voice.ICH, "ach so", "S3"), null, MicMode.HANDY, 5_000)
        assertTrue(known, known.startsWith("Mikrofon: Handy-Mikrofon\nBisherige Einschätzung der Szene: keine\n<mitschrift>\n1. [vor 5 s] NAH S1: Alfred"))
    }

    @Test fun `Brain gibt dem Ordner alle Rufnamen mit, jeden einmal`() {
        assertEquals(listOf("Alfred", "Jarvis"), Persona.calledNames(Profile(name = "Alfred", aliases = listOf("Jarvis", "alfred", " ", "Jarvis"))))
        val claude = FakeClaude(quickReply = { _, _ -> "SZENE: Er ist allein.\nABSEITS: -" })
        val brain = Brain(claude, Profile(name = "Alfred", aliases = listOf("Jarvis")))
        val tidy = brain.tidy(listOf(Line(7, 0, Voice.ICH, "Alfred, wie hoch ist die Zugspitze?", "S1")), null, MicMode.AIRPODS, 5_000)
        assertEquals(Tidy("Er ist allein.", emptySet()), tidy)
        val (system, user) = claude.quickCalls.single()
        assertEquals(Persona.TIDY_SYSTEM, system)
        assertTrue(user, user.contains("\nNamen des Assistenten: Alfred, Jarvis\n"))
    }
}

/** Liest die Antwort des Ordners ([Brain.parseTidy]): Nummern, Bereiche und alles, was keine Zeilennummer ist. */
class OrdnerAntwortTest {
    /** Zehn Zeilen mit den IDs 101 bis 110; die ersten beiden gehören dem Träger. */
    private fun tenLines() = (1..10).map { Line(100L + it, 0, if (it <= 2) Voice.ICH else Voice.FERN, "zeile $it", if (it <= 2) "S1" else "S2") }

    private fun aside(abseits: String, lines: List<Line> = tenLines()): Set<Long> =
        Brain.parseTidy("SZENE: Wohnzimmer, im Hintergrund läuft der Fernseher.\nABSEITS: $abseits", lines).aside

    private fun ids(vararg numbers: Int): Set<Long> = numbers.map { 100L + it }.toSet()

    @Test fun `ein Bereich meint alle Zeilen dazwischen`() {
        val threeToNine = (3..9).map { 100L + it }.toSet()
        assertEquals(threeToNine, aside("3-9"))
        assertEquals(threeToNine, aside("3 bis 9"))
        assertEquals(threeToNine, aside("3–9"))
        assertEquals(threeToNine, aside("3 - 9"))
        assertEquals(ids(1, 2, 3, 5), aside("1-3, 5"))
        assertEquals(ids(3, 4, 5, 7), aside("Zeilen 3 bis 5 und 7"))
    }

    @Test fun `ein Sprecher-Kuerzel ist keine Zeilennummer`() {
        // Früher markierte die 2 aus "S2" die Zeile 2, also eine Zeile des Trägers.
        assertEquals(ids(4, 6), aside("4, 6 (beide S2)"))
        assertEquals(emptySet<Long>(), aside("S1-S3"))
    }

    @Test fun `einzelne Nummern wie bisher, ein Strich heisst keine`() {
        assertEquals(ids(2, 7, 10), aside("2, 7, 10"))
        assertEquals(emptySet<Long>(), aside("-"))
        assertEquals(emptySet<Long>(), aside("–"))
        assertEquals(emptySet<Long>(), aside(""))
        assertEquals(ids(3), aside("- 3"))
    }

    @Test fun `Bereiche bleiben innerhalb der vorhandenen Zeilen und werfen nie`() {
        assertEquals(emptySet<Long>(), aside("9-3")) // verkehrt herum: lieber nichts markieren als raten
        assertEquals(ids(8, 9, 10), aside("8-100000"))
        assertEquals(ids(8, 9, 10), aside("8-99999999999999999999")) // obere Grenze passt in keine Zahl
        assertEquals(emptySet<Long>(), aside("99999999999999999999")) // warf früher und verwarf damit auch die Szene
        assertEquals(emptySet<Long>(), aside("0"))
        assertEquals(ids(1, 2), aside("0-2"))
        val three = tenLines().take(3)
        assertEquals(setOf(102L), aside("2, 9", three))
    }

    @Test fun `die Szene uebersteht eine unlesbare ABSEITS-Zeile`() {
        val t = Brain.parseTidy("SZENE: Er ist allein.\nABSEITS: 99999999999999999999, 4", tenLines())
        assertEquals("Er ist allein.", t.scene)
        assertEquals(ids(4), t.aside)
    }
}
