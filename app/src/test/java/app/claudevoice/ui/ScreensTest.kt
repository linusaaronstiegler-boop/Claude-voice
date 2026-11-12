package app.claudevoice.ui

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.claudevoice.core.UiState
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Zeichnet die beiden Seiten mit Beispieldaten, speichert Bilder davon und prüft die Bedienung.
 *
 * Das läuft ohne Handy: Robolectric spielt Android auf dem Rechner nach, Roborazzi macht die Bilder
 * (nach app/build/outputs/roborazzi/). Die Bilder sind zum Ansehen gedacht, nicht zum automatischen
 * Vergleich; was sich prüfen lässt (gesperrte Auswahl, Tippflächen, Rückmeldungen), prüfen die Tests.
 *
 * Gezeichnet wird in zwei Handygrößen: 412 dp breit (übliches großes Handy) und 360 dp (schmal und kurz).
 * Alle Beispieltexte sind erfunden.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // echte Zeichenroutinen statt Attrappen, sonst wären die Bilder leer
@Config(sdk = [35], qualifiers = PHONE_412)
class ScreensTest {

    // Die Test-Activity steht nicht im Manifest der App. Statt sie dort nur für Tests einzutragen,
    // wird sie Robolectric hier vor jedem Test bekannt gemacht.
    @get:Rule(order = 0)
    val registerActivity = object : TestWatcher() {
        override fun starting(description: Description) {
            val app = ApplicationProvider.getApplicationContext<Application>()
            Shadows.shadowOf(app.packageManager)
                .addActivityIfNotPresent(ComponentName(app.packageName, ComponentActivity::class.java.name))
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    // --- Beispieldaten ----------------------------------------------------------------------

    private val now = 1_800_000_000_000L

    private val idle = UiState()

    private val listening = UiState(
        running = true,
        startedAtMs = now - 185_000,
        status = "Hört zu (Kopfhörer-Mikro)",
        scene = "Zwei Personen planen am Cafétisch eine Wanderung auf die Zugspitze.",
        lastDecision = "übersprungen: kein Anlass",
        latencyMs = 820,
        transcript = listOf(
            "ICH S1: Am Samstag soll das Wetter halten, sagt der Bericht.",
            "NAH S2: Dann nehmen wir die frühe Bahn.",
            "FERN: Zwei Cappuccino für Tisch vier, bitte.",
            "?: Mhm.",
            "ICH S1: Wie hoch ist die Zugspitze eigentlich genau?",
            "ALFRED: 2962 Meter, Sir.",
            "NAH S2: Und wie lange braucht man durchs Reintal?",
        ),
        partial = "Ich glaube, das sind ungefähr",
    )

    private val answering = listening.copy(
        transcript = listening.transcript + "ICH S1: Alfred, wie lange braucht man durchs Reintal?",
        partial = "",
        question = "Alfred, wie lange braucht man durchs Reintal?",
        answer = "Acht bis zehn Stunden, Sir. Die meisten teilen den Weg auf zwei Tage auf und " +
            "übernachten auf der Reintalangerhütte.",
        speaking = true,
        lastDecision = "antwortet (mittel)",
        latencyMs = 780,
    )

    private val form = SettingsForm(
        anthropicKey = "beispiel-kein-echter-schluessel",
        elevenKey = "beispiel-kein-echter-schluessel",
        voiceId = "JBFqnCBsd6RMkjVDRZzb",
        name = "Alfred",
        aliases = "Jarvis",
        address = "Sir",
        character = "",
        about = "Wandert gern, trinkt Tee und lernt gerade Kotlin.",
        pauseMusic = true,
    )
    private val files = listOf(LinkedFile("content://beispiel/1", "MEMORY.md"), LinkedFile("content://beispiel/2", "Vorhaben-und-Termine.md"))
    private val notes = listOf("2026-11-10: Samstag Wanderung, Abfahrt um sieben Uhr", "2026-11-11: Lieblingstee ist Earl Grey")
    private val linkError = "Datei zu groß (41200 Zeichen, Grenze 30000). Bitte eine kürzere Fassung wählen."

    @Composable
    private fun Main(ui: UiState, hasKeys: Boolean = true, hint: String? = null, actions: MainActions = MainActions()) {
        ClaudeVoiceTheme { MainScreen(ui, now, ListenChoices(), hasKeys, hint, actions) }
    }

    // --- Bilder -----------------------------------------------------------------------------

    @Test
    fun bild_haupt_bereit_ohne_schluessel_412() {
        compose.setContent { Main(idle, hasKeys = false) }
        shoot("haupt_bereit_ohne_schluessel_412")
        assertTouchTargets()
    }

    @Test
    fun bild_haupt_hoert_zu_412() {
        compose.setContent { Main(listening) }
        shoot("haupt_hoert_zu_412")
        assertTouchTargets()
    }

    @Test
    fun bild_haupt_antwortet_412() {
        compose.setContent { Main(answering) }
        shoot("haupt_antwortet_412")
        assertTouchTargets()
    }

    @Test
    @Config(qualifiers = PHONE_360)
    fun bild_haupt_bereit_ohne_schluessel_360() {
        compose.setContent { Main(idle, hasKeys = false) }
        shoot("haupt_bereit_ohne_schluessel_360")
        assertTouchTargets()
    }

    @Test
    @Config(qualifiers = PHONE_360)
    fun bild_haupt_antwortet_360() {
        compose.setContent { Main(answering) }
        shoot("haupt_antwortet_360")
        assertTouchTargets()
    }

    @Test
    fun bild_einstellungen_412() = shootSettings("einstellungen_412")

    @Test
    @Config(qualifiers = PHONE_360)
    fun bild_einstellungen_360() = shootSettings("einstellungen_360")

    /** Die Einstellungen sind länger als der Bildschirm: mehrere Bilder, jeweils eine Bildschirmhöhe weiter. */
    private fun shootSettings(name: String) {
        val scroll = ScrollState(0)
        compose.setContent {
            ClaudeVoiceTheme { SettingsScreen(form, files, linkError, notes, running = false, SettingsActions(), scrollState = scroll) }
        }
        compose.waitForIdle()
        val step = scroll.viewportSize * 9 / 10 // etwas Überlappung, damit an der Schnittkante nichts verloren geht
        val stops = (generateSequence(0) { it + step }.takeWhile { it < scroll.maxValue } + scroll.maxValue).distinct().toList()
        assertTrue("Die Seite sollte rollen können", scroll.maxValue > 0)
        stops.forEachIndexed { index, position ->
            runBlocking { scroll.scrollTo(position) }
            shoot("${name}_${index + 1}")
            assertTouchTargets()
        }
    }

    private fun shoot(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    /**
     * Alles Antippbare braucht eine Tippfläche von mindestens 48 x 48 dp, sonst trifft man es schlecht.
     * Gemessen wird die Fläche, die wirklich auf den Finger reagiert. Die darf größer sein als das,
     * was man sieht (Compose ergänzt um kleine Knöpfe herum unsichtbaren Rand), wird aber von einem
     * umgebenden Rahmen beschnitten. Was gerade halb aus dem Bild gerollt ist, wird übersprungen.
     */
    private fun assertTouchTargets() {
        compose.waitForIdle()
        val min = with(compose.density) { 48.dp.toPx() } - 1f // 1 Pixel Rundung zulassen
        val tooSmall = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().mapNotNull { node ->
            val visible = node.boundsInRoot
            val fullyVisible = visible.width >= node.size.width - 1 && visible.height >= node.size.height - 1
            val touch = node.touchBoundsInRoot
            if (fullyVisible && (touch.width < min || touch.height < min)) {
                "${describe(node)} ${touch.width.toInt()}x${touch.height.toInt()}px"
            } else null
        }
        assertTrue("Zu kleine Tippflächen (Minimum ${min.toInt() + 1}px): $tooSmall", tooSmall.isEmpty())
    }

    /** Trifft Eingabefelder, die [text] gerade im Klartext anzeigen (verdeckt stehen dort nur Punkte). */
    private fun showsPlain(text: String) = SemanticsMatcher("zeigt '$text' im Klartext") {
        it.config.getOrNull(SemanticsProperties.EditableText)?.text == text
    }

    private fun describe(node: SemanticsNode): String =
        node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
            ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            ?: "Knoten ${node.id}"

    // --- Bedienung der Hauptseite -----------------------------------------------------------

    private val allChoices = listOf(
        "Dauer-Zuhören", LABEL_WECKWORT, "AirPods-Mikro", "Handy-Mikro", "Nur ich", "Mein Gespräch", "Ganzer Raum",
    )

    @Test
    fun auswahl_meldet_die_werte_wie_sie_in_den_prefs_stehen() {
        var choices by mutableStateOf(ListenChoices())
        compose.setContent {
            ClaudeVoiceTheme {
                MainScreen(
                    idle, now, choices, hasKeys = true, hint = null,
                    actions = MainActions(
                        onModeChange = { choices = choices.copy(mode = it) },
                        onMicChange = { choices = choices.copy(bluetoothMic = it) },
                        onReachChange = { choices = choices.copy(reach = it) },
                    ),
                )
            }
        }
        allChoices.forEach { compose.onNodeWithText(it).assertIsEnabled() }
        compose.onNodeWithText("Dauer-Zuhören").assertIsSelected()

        compose.onNodeWithText(LABEL_WECKWORT).performClick()
        compose.onNodeWithText("Handy-Mikro").performClick()
        compose.onNodeWithText("Ganzer Raum").performClick()

        assertEquals(ListenChoices(mode = "weckwort", bluetoothMic = false, reach = "raum"), choices)
        compose.onNodeWithText(LABEL_WECKWORT).assertIsSelected()
        compose.onNodeWithText("Ganzer Raum").assertIsSelected()

        compose.onNodeWithText("Nur ich").performClick()
        compose.onNodeWithText("Dauer-Zuhören").performClick()
        compose.onNodeWithText("AirPods-Mikro").performClick()
        assertEquals(ListenChoices(mode = "dauer", bluetoothMic = true, reach = "ich"), choices)
    }

    @Test
    fun waehrend_des_zuhoerens_ist_die_auswahl_gesperrt() {
        var changes = 0
        val count = MainActions(onModeChange = { changes++ }, onMicChange = { changes++ }, onReachChange = { changes++ })
        // Ohne Mitschrift bleiben die Auswahlleisten im Bild (mit Mitschrift rollen sie nach oben weg).
        compose.setContent { Main(UiState(running = true, startedAtMs = now - 5_000, status = "Hört zu (Handy-Mikro)"), actions = count) }
        allChoices.forEach {
            compose.onNodeWithText(it).assertIsNotEnabled()
            compose.onNodeWithText(it).performClick()
        }
        assertEquals(0, changes)
        compose.onNodeWithTag("status").assertIsDisplayed()
        compose.onNodeWithText("Hört zu (Handy-Mikro) · 0:05").assertIsDisplayed()
    }

    @Test
    fun grosser_knopf_startet_und_stoppt() {
        var taps = 0
        var ui by mutableStateOf(idle)
        compose.setContent { Main(ui, actions = MainActions(onStartStop = { taps++ })) }
        compose.onNodeWithText("Zuhören starten").performClick()
        assertEquals(1, taps)
        ui = listening
        compose.onNodeWithText("Stopp").performClick()
        assertEquals(2, taps)
        compose.onAllNodesWithText("Zuhören starten").assertCountEquals(0)
    }

    @Test
    fun antwort_stoppen_gibt_es_nur_waehrend_gesprochen_wird() {
        var stops = 0
        var ui by mutableStateOf(answering)
        compose.setContent { Main(ui, actions = MainActions(onStopSpeaking = { stops++ })) }
        compose.onNodeWithText("Antwort stoppen").performClick()
        assertEquals(1, stops)
        // Fertig gesprochen: Frage und Antwort bleiben stehen, der Knopf verschwindet.
        ui = answering.copy(speaking = false)
        compose.onAllNodesWithText("Antwort stoppen").assertCountEquals(0)
        compose.onNodeWithText(answering.answer).assertIsDisplayed()
    }

    @Test
    fun hinweise_erscheinen_ueber_dem_knopf() {
        var ui by mutableStateOf(idle)
        compose.setContent { Main(ui, hint = "Ohne Mikrofon-Berechtigung kann die App nicht zuhören.") }
        compose.onNodeWithText("Ohne Mikrofon-Berechtigung kann die App nicht zuhören.").assertIsDisplayed()
        // Eine Meldung des Service hat Vorrang vor dem Hinweis der Oberfläche.
        ui = idle.copy(error = "Kein Kopfhörer-Mikro gefunden, nutze das Handy-Mikro.")
        compose.onNodeWithTag("notice").assertIsDisplayed()
        compose.onNodeWithText("Kein Kopfhörer-Mikro gefunden, nutze das Handy-Mikro.").assertIsDisplayed()
        compose.onAllNodesWithText("Ohne Mikrofon-Berechtigung kann die App nicht zuhören.").assertCountEquals(0)
    }

    @Test
    fun mitschrift_zeigt_kuerzel_und_text_getrennt() {
        compose.setContent { Main(listening) }
        // Das Kürzel steht als eigener Text links, nicht mehr als Teil der Zeile.
        compose.onNodeWithText("ALFRED").assertIsDisplayed()
        compose.onNodeWithText("2962 Meter, Sir.").assertIsDisplayed()
        compose.onAllNodesWithText("NAH S2").assertCountEquals(2)
        compose.onNodeWithText("Ich glaube, das sind ungefähr …").assertIsDisplayed()
        compose.onNodeWithText("Antwort nach 0,8 s · Zuletzt: übersprungen: kein Anlass").assertIsDisplayed()
        compose.onNodeWithText("Zwei Personen planen am Cafétisch eine Wanderung auf die Zugspitze.").assertIsDisplayed()
    }

    @Test
    fun lange_mitschrift_laeuft_mit_und_springt_nach_dem_stopp_nach_oben() {
        val many = (1..40).map { "NAH S2: Zeile Nummer $it der Mitschrift." }
        var ui by mutableStateOf(listening.copy(transcript = many, partial = ""))
        compose.setContent { Main(ui) }
        // Läuft: das Neueste ist im Bild, der Anfang (samt Auswahlleisten) ist nach oben weggerollt.
        compose.onNodeWithText("Zeile Nummer 40 der Mitschrift.").assertIsDisplayed()
        compose.onAllNodesWithText("Zeile Nummer 1 der Mitschrift.").assertCountEquals(0)
        compose.onAllNodesWithText("Nur ich").assertCountEquals(0)
        // Eine neue Zeile kommt dazu: die Liste läuft mit.
        ui = ui.copy(transcript = many + "ICH S1: Und noch eine neue Zeile.")
        compose.onNodeWithText("Und noch eine neue Zeile.").assertIsDisplayed()
        // Gestoppt: zurück nach oben, die Auswahl ist wieder da und bedienbar.
        ui = ui.copy(running = false, status = "Bereit")
        compose.onNodeWithText("Nur ich").assertIsDisplayed().assertIsEnabled()
        compose.onAllNodesWithText("Und noch eine neue Zeile.").assertCountEquals(0)
    }

    // --- Bedienung der Einstellungen --------------------------------------------------------

    @Test
    fun einstellungen_melden_aenderungen_dateien_und_notizen() {
        var current by mutableStateOf(form)
        var added = 0
        val removed = mutableListOf<LinkedFile>()
        val deleted = mutableListOf<Int>()
        var closed = 0
        compose.setContent {
            ClaudeVoiceTheme {
                SettingsScreen(
                    current, files, linkError, notes, running = true,
                    actions = SettingsActions(
                        onFormChange = { current = it },
                        onAddFile = { added++ },
                        onRemoveFile = { removed += it },
                        onDeleteNote = { deleted += it },
                        onClose = { closed++ },
                    ),
                )
            }
        }
        compose.onNodeWithText("Gerade wird zugehört. Änderungen wirken ab dem nächsten Start.").assertIsDisplayed()

        compose.onNodeWithText("Name").performScrollTo().performTextReplacement("Jarvis")
        compose.onNodeWithText("Anrede").performScrollTo().performTextReplacement("Madam")
        compose.onNodeWithText("Charakter").performScrollTo().performTextReplacement("Trocken und knapp.")
        assertEquals(form.copy(name = "Jarvis", address = "Madam", character = "Trocken und knapp."), current)

        compose.onNodeWithText("Leiser stellen").performScrollTo().performClick()
        assertEquals(false, current.pauseMusic)

        // Fehlermeldung des Verknüpfens steht bei den Dateien.
        compose.onNodeWithTag("link_error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Datei verknüpfen").performScrollTo().performClick()
        assertEquals(1, added)
        compose.onAllNodesWithText("Entfernen")[1].performScrollTo().performClick()
        assertEquals(listOf(files[1]), removed)

        // Notiz löschen fragt erst nach; "Abbrechen" lässt sie stehen, "Löschen" im Dialog löscht.
        compose.onAllNodesWithText("Löschen")[1].performScrollTo().performClick()
        compose.onNode(hasText("Abbrechen") and hasAnyAncestor(isDialog())).performClick()
        assertEquals(emptyList<Int>(), deleted)
        compose.onAllNodesWithText("Löschen")[1].performScrollTo().performClick()
        compose.onNode(hasText("Löschen") and hasAnyAncestor(isDialog())).performClick()
        assertEquals(listOf(1), deleted)

        compose.onNodeWithText("Fertig").performClick()
        assertEquals(1, closed)
    }

    @Test
    fun schluessel_sind_verdeckt_bis_man_sie_zeigen_laesst() {
        compose.setContent { ClaudeVoiceTheme { SettingsScreen(form, emptyList(), null, emptyList(), running = false, SettingsActions()) } }
        // Verdeckt zeigt das Feld nur Punkte.
        compose.onAllNodes(showsPlain(form.anthropicKey)).assertCountEquals(0)
        compose.onAllNodesWithText("Zeigen")[0].performClick()
        compose.onAllNodes(showsPlain(form.anthropicKey)).assertCountEquals(1) // nur das eine Feld, nicht beide
        compose.onNodeWithText("Verbergen").performClick()
        compose.onAllNodes(showsPlain(form.anthropicKey)).assertCountEquals(0)
        compose.onNodeWithText("Noch keine. Sag zum Beispiel: „Alfred, merk dir, dass …“").performScrollTo().assertIsDisplayed()
    }
}

private const val PHONE_412 = "w412dp-h915dp-xhdpi"
private const val PHONE_360 = "w360dp-h640dp-xhdpi"
