package app.claudevoice.ui

import android.app.Application
import android.content.ComponentName
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import app.claudevoice.EnrolPhase
import app.claudevoice.FakeEnrolMic
import app.claudevoice.VoiceEnroller
import app.claudevoice.applySystemBars
import app.claudevoice.core.MicMode
import app.claudevoice.core.VoiceProfile
import app.claudevoice.core.SpeechOutput
import app.claudevoice.core.StartKeys
import app.claudevoice.core.UiState
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Gezeichnet wird hell und dunkel in 412 dp Breite (übliches großes Handy), dazu dunkel in 360 dp
 * (schmal und kurz). Diese Bilder entstehen per Software und zeigen das Glas ohne Unschärfe: so muss
 * es auch schon nach Glas aussehen. Zwei weitere Bilder ("unschaerfe") lassen Robolectric so zeichnen
 * wie die Grafikkarte eines Handys; nur dort ist zu sehen, wie die Mitschrift hinter dem schwebenden
 * Glas verschwimmt. Ein echtes Handy ersetzt das nicht, es ist aber dieselbe Zeichen-Bibliothek.
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
            // Nur für die Unschärfe-Bilder: Robolectric soll wie die Grafikkarte eines Handys zeichnen.
            // Der Schalter gilt für das ganze Testprogramm, deshalb wird er danach wieder entfernt.
            if ("unschaerfe" in description.methodName) System.setProperty(HARDWARE_SWITCH, "hardware")
        }

        override fun finished(description: Description) {
            System.clearProperty(HARDWARE_SWITCH)
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

    /** Nach dem Stopp: die Auswahl ist wieder da, Mitschrift und letzte Antwort stehen noch. */
    private val stopped = answering.copy(running = false, speaking = false, status = "Bereit")

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

    /** Ohne ElevenLabs-Konto: Soniox schreibt mit, die Stimme des Geräts spricht. */
    private val deviceForm = form.copy(elevenKey = "", sonioxKey = "beispiel-kein-echter-schluessel")
    private val profiles = listOf(
        VoiceProfileRow("AIRPODS", "AirPods-Mikro", 12, "Die App lernt Ihre Stimme an diesem Mikrofon danach von vorn."),
        VoiceProfileRow("HANDY", "Handy-Mikro", null, "Am Handy-Mikro lernt die App kein neues Profil."),
    )

    @Composable
    private fun Main(
        ui: UiState,
        hasKeys: Boolean = true,
        hint: String? = null,
        actions: MainActions = MainActions(),
        choices: ListenChoices = ListenChoices(),
        appearance: Appearance = Appearance.Light,
    ) {
        ClaudeVoiceTheme(appearance) { MainScreen(ui, now, choices, hasKeys, hint, actions) }
    }

    // --- Bilder -----------------------------------------------------------------------------
    // Jeder Zustand in beiden Erscheinungsbildern. Der Dateiname sagt, was zu sehen ist:
    // Seite, Zustand, Breite in dp, hell oder dunkel.

    @Test
    fun bild_haupt_bereit_ohne_schluessel_412_hell() = shootMain("haupt_bereit_ohne_schluessel_412_hell", idle, Appearance.Light, hasKeys = false)

    @Test
    fun bild_haupt_bereit_ohne_schluessel_412_dunkel() = shootMain("haupt_bereit_ohne_schluessel_412_dunkel", idle, Appearance.Dark, hasKeys = false)

    @Test
    fun bild_haupt_hoert_zu_412_hell() = shootMain("haupt_hoert_zu_412_hell", listening, Appearance.Light)

    @Test
    fun bild_haupt_hoert_zu_412_dunkel() = shootMain("haupt_hoert_zu_412_dunkel", listening, Appearance.Dark)

    @Test
    fun bild_haupt_antwortet_412_hell() = shootMain("haupt_antwortet_412_hell", answering, Appearance.Light)

    @Test
    fun bild_haupt_antwortet_412_dunkel() = shootMain("haupt_antwortet_412_dunkel", answering, Appearance.Dark)

    @Test
    @Config(qualifiers = PHONE_360)
    fun bild_haupt_bereit_ohne_schluessel_360_dunkel() = shootMain("haupt_bereit_ohne_schluessel_360_dunkel", idle, Appearance.Dark, hasKeys = false)

    @Test
    @Config(qualifiers = PHONE_360)
    fun bild_haupt_antwortet_360_dunkel() = shootMain("haupt_antwortet_360_dunkel", answering, Appearance.Dark)

    @Test
    fun bild_einstellungen_412_hell() = shootSettings("einstellungen_412_hell", Appearance.Light)

    @Test
    fun bild_einstellungen_412_dunkel() = shootSettings("einstellungen_412_dunkel", Appearance.Dark)

    @Test
    @Config(qualifiers = PHONE_360)
    fun bild_einstellungen_360_dunkel() = shootSettings("einstellungen_360_dunkel", Appearance.Dark)

    // Dieselbe Seite, wenn die Stimme des Geräts spricht: kein Feld für die Voice-ID, anderer Hinweis.
    @Test
    fun bild_einstellungen_geraetestimme_412_hell() = shootSettings("einstellungen_geraetestimme_412_hell", Appearance.Light, deviceForm)

    // Nach dem Stopp ist die Mitschrift länger als der Platz über der schwebenden Antwortkarte.
    // Ohne Unschärfe blendet sie davor aus.
    @Test
    fun bild_haupt_angehalten_412_hell() = shootMain("haupt_angehalten_412_hell", stopped, Appearance.Light)

    @Test
    fun bild_haupt_angehalten_412_dunkel() = shootMain("haupt_angehalten_412_dunkel", stopped, Appearance.Dark)

    // Derselbe Zustand mit echter Unschärfe (siehe oben): unter der Antwortkarte verschwimmt die Mitschrift.
    @Test
    fun bild_haupt_unschaerfe_412_dunkel() = shootBlurred("haupt_unschaerfe_412_dunkel", Appearance.Dark)

    @Test
    fun bild_haupt_unschaerfe_412_hell() = shootBlurred("haupt_unschaerfe_412_hell", Appearance.Light)

    private fun shootBlurred(name: String, appearance: Appearance) {
        compose.setContent {
            ClaudeVoiceTheme(appearance, backdropBlur = true) { MainScreen(stopped, now, ListenChoices(), true, null, MainActions()) }
        }
        shoot(name)
        assertTouchTargets()
    }

    // Die Rückfrage vor dem Löschen einer Notiz. Ein Dialog ist ein eigenes Fenster; das Bild zeigt nur ihn.
    @Test
    fun bild_einstellungen_notiz_loeschen_412_dunkel() = shootDeleteDialog("einstellungen_notiz_loeschen_412_dunkel", Appearance.Dark)

    @Test
    fun bild_einstellungen_notiz_loeschen_412_hell() = shootDeleteDialog("einstellungen_notiz_loeschen_412_hell", Appearance.Light)

    // Die Rückfrage vor dem Vergessen des Stimmprofils.
    @Test
    fun bild_einstellungen_stimmprofil_vergessen_412_hell() {
        compose.setContent {
            ClaudeVoiceTheme(Appearance.Light) {
                SettingsScreen(form, files, null, notes, running = false, SettingsActions(), voiceProfiles = profiles)
            }
        }
        compose.onNodeWithText("Vergessen").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNode(isDialog()).captureRoboImage("build/outputs/roborazzi/einstellungen_stimmprofil_vergessen_412_hell.png")
        assertTouchTargets()
    }

    // Das Einlernen der Stimme mitten in der Aufnahme und mit Ergebnis samt Warnung. Die Bilder zeigen nur
    // die Seite ab diesem Abschnitt; alle Zahlen sind erfunden.
    @Test
    fun bild_einstellungen_einlernen_aufnahme_412_hell() =
        shootEnrol("einstellungen_einlernen_aufnahme_412_hell", Appearance.Light, EnrolPhase.Recording(0.6f, -31.0), "enrol_progress")

    @Test
    fun bild_einstellungen_einlernen_aufnahme_412_dunkel() =
        shootEnrol("einstellungen_einlernen_aufnahme_412_dunkel", Appearance.Dark, EnrolPhase.Recording(0.6f, -31.0), "enrol_progress")

    @Test
    fun bild_einstellungen_einlernen_ergebnis_412_hell() = shootEnrol(
        "einstellungen_einlernen_ergebnis_412_hell", Appearance.Light,
        EnrolPhase.Done(MicMode.AIRPODS, VoiceProfile(-41.0, 118.0, 50), "Ihre Stimme kommt an diesem Mikrofon sehr leise an."),
        "enrol_warning",
    )

    private fun shootEnrol(name: String, appearance: Appearance, phase: EnrolPhase, scrollTo: String) {
        compose.setContent {
            ClaudeVoiceTheme(appearance) {
                SettingsScreen(form, files, null, notes, running = false, SettingsActions(), voiceProfiles = profiles, enrol = EnrolUi(true, phase))
            }
        }
        compose.onNodeWithTag(scrollTo).performScrollTo()
        shoot(name)
        assertTouchTargets()
    }

    private fun shootDeleteDialog(name: String, appearance: Appearance) {
        compose.setContent {
            ClaudeVoiceTheme(appearance) { SettingsScreen(form, files, null, notes, running = false, SettingsActions()) }
        }
        compose.onAllNodesWithText("Löschen")[0].performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNode(isDialog()).captureRoboImage("build/outputs/roborazzi/$name.png")
        assertTouchTargets()
    }

    private fun shootMain(name: String, ui: UiState, appearance: Appearance, hasKeys: Boolean = true) {
        compose.setContent { Main(ui, hasKeys = hasKeys, appearance = appearance) }
        shoot(name)
        assertTouchTargets()
    }

    /** Die Einstellungen sind länger als der Bildschirm: mehrere Bilder, jeweils eine Bildschirmhöhe weiter. */
    private fun shootSettings(name: String, appearance: Appearance, shown: SettingsForm = form) {
        val scroll = ScrollState(0)
        compose.setContent {
            ClaudeVoiceTheme(appearance) {
                SettingsScreen(
                    shown.copy(appearance = appearance), files, linkError, notes, running = false, SettingsActions(),
                    scrollState = scroll, voiceProfiles = profiles, enrol = EnrolUi(),
                )
            }
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
    fun waehrend_des_zuhoerens_steht_die_auswahl_nur_als_kurzfassung_da() {
        var changes = 0
        val count = MainActions(onModeChange = { changes++ }, onMicChange = { changes++ }, onReachChange = { changes++ })
        val chosen = ListenChoices(mode = MODE_WECKWORT, bluetoothMic = false, reach = REACH_RAUM)
        compose.setContent {
            Main(UiState(running = true, startedAtMs = now - 5_000, status = "Hört zu (Handy-Mikro)"), actions = count, choices = chosen)
        }
        // Gesperrt heißt jetzt: die Leisten sind gar nicht da. Es gibt nichts, das man vergeblich antippt.
        allChoices.forEach { compose.onAllNodesWithText(it).assertCountEquals(0) }
        // Stattdessen steht in der Status-Scheibe, was gilt, und wie man es ändert.
        compose.onNodeWithTag("locked_choices").assertIsDisplayed()
        compose.onNodeWithText("$LABEL_WECKWORT · Handy-Mikro · Ganzer Raum").assertIsDisplayed().performClick()
        compose.onNodeWithText("zum Ändern erst stoppen").assertIsDisplayed()
        assertEquals(0, changes)
        compose.onNodeWithTag("status").assertIsDisplayed()
        compose.onNodeWithText("Hört zu (Handy-Mikro) · 0:05").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = PHONE_360)
    fun auf_kurzen_bildschirmen_rollen_lage_und_kurzfassung_mit_der_mitschrift() {
        var ui by mutableStateOf(listening.copy(transcript = emptyList(), partial = ""))
        compose.setContent { Main(ui) }
        // Ohne Mitschrift ist beides zu sehen ...
        compose.onNodeWithText("Dauer-Zuhören · AirPods-Mikro · Mein Gespräch").assertIsDisplayed()
        compose.onNodeWithText(listening.scene!!).assertIsDisplayed()
        // ... mit langer Mitschrift rollt es nach oben weg und macht dem Gespräch Platz.
        ui = ui.copy(transcript = (1..40).map { "NAH S2: Zeile Nummer $it der Mitschrift." })
        compose.onNodeWithText("Zeile Nummer 40 der Mitschrift.").assertIsDisplayed()
        compose.onAllNodesWithText(listening.scene!!).assertCountEquals(0)
        compose.onNodeWithTag("status").assertIsDisplayed()
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

    // --- Enge Verhältnisse ------------------------------------------------------------------
    // Querformat, sehr große Schrift, ein schmales Handy: hier zeigt sich, ob etwas übereinander
    // gerät oder abgeschnitten wird. Die Bilder dazu tragen "quer", "schrift" oder "320" im Namen.

    /** Zeichnet [content] so, als wäre am Handy die Schriftgröße auf das [scale]-Fache gestellt. */
    @Composable
    private fun FontScale(scale: Float, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = scale), content = content)
    }

    private fun boundsOf(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    /**
     * Der schwebende Bereich darf nie über der Status-Scheibe liegen (sonst stünde Schrift auf Schrift),
     * und der große Knopf muss ganz im Bild bleiben.
     */
    private fun assertDockClearOfStatus() {
        compose.waitForIdle()
        val status = boundsOf("status_card")
        val dock = boundsOf("dock")
        assertTrue("Der schwebende Bereich beginnt bei ${dock.top}, der Status reicht bis ${status.bottom}", dock.top >= status.bottom)
        val button = compose.onNodeWithTag("start_stop").fetchSemanticsNode()
        assertTrue("Der große Knopf ist gestaucht", button.size.height >= with(compose.density) { 48.dp.roundToPx() })
        assertTrue("Der große Knopf ist angeschnitten", button.boundsInRoot.height >= button.size.height - 1)
        assertTrue("Der große Knopf ragt aus dem Bild", button.boundsInRoot.bottom <= compose.onRoot().fetchSemanticsNode().size.height)
    }

    private val trouble = "Verbindung zur Spracherkennung unterbrochen. Neuer Versuch in wenigen Sekunden, bitte warten."

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun im_querformat_bleibt_der_status_frei() {
        var ui by mutableStateOf(answering)
        compose.setContent { Main(ui, appearance = Appearance.Dark) }
        shoot("haupt_antwortet_quer_640_dunkel")
        assertDockClearOfStatus()
        // Wird es eng, gibt der Text der Antwort nach; "Antwort stoppen" bleibt ganz und bedienbar.
        val stop = compose.onNodeWithText("Antwort stoppen").fetchSemanticsNode()
        assertTrue("Antwort stoppen ist angeschnitten", stop.boundsInRoot.height >= stop.size.height - 1)
        assertTouchTargets()
        // Auch mit einer Meldung obendrauf.
        ui = answering.copy(error = trouble)
        assertDockClearOfStatus()
        ui = idle.copy(error = trouble)
        assertDockClearOfStatus()
    }

    @Test
    fun bei_doppelt_grosser_schrift_bleibt_der_status_frei_und_die_mitschrift_sichtbar() {
        compose.setContent { ClaudeVoiceTheme(Appearance.Light) { FontScale(2f) { MainScreen(answering, now, ListenChoices(), true, null, MainActions()) } } }
        shoot("haupt_antwortet_412_hell_schrift_200")
        assertDockClearOfStatus()
        // Bei so großer Schrift zählt der Bildschirm als kurz: Lage und Auswahl rollen mit der Mitschrift
        // mit, statt die halbe Seite zu belegen. So bleibt die neueste Zeile zwischen Status und Karte zu sehen.
        val newest = compose.onAllNodesWithText(answering.question!!)[0].fetchSemanticsNode().boundsInRoot
        assertTrue("Die neueste Zeile liegt unter dem Status", newest.top >= boundsOf("status_card").bottom)
        assertTrue("Die neueste Zeile liegt unter dem schwebenden Bereich", newest.bottom <= boundsOf("dock").top)
    }

    @Test
    @Config(qualifiers = PHONE_360)
    fun auf_kurzen_bildschirmen_bleibt_der_status_auch_mit_meldung_und_antwort_frei() {
        compose.setContent { Main(answering.copy(error = trouble), appearance = Appearance.Dark) }
        assertDockClearOfStatus()
        assertTouchTargets()
    }

    /** Wie die Beschriftungen aller Auswahlfelder gerade gesetzt sind (Zeilen, Umbrüche, Überlauf). */
    private fun segmentLayouts(): List<TextLayoutResult> {
        compose.waitForIdle()
        // Im "unzusammengefassten" Baum ist der Text ein eigener Knoten unter dem antippbaren Feld.
        val isSegment = SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)
        return compose.onAllNodes(hasParent(isSegment), useUnmergedTree = true).fetchSemanticsNodes().map { node ->
            val result = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(result)
            result.single()
        }
    }

    private fun assertNothingCutOff(layouts: List<TextLayoutResult>) {
        for (layout in layouts) {
            val text = layout.layoutInput.text
            // Zu hoch: es gibt mehr Zeilen, als in das Feld passen (die unteren wären unsichtbar).
            assertFalse("'$text' ist unten abgeschnitten", layout.didOverflowHeight)
            // Zu breit: eine Zeile ist länger als das Feld. (Bewusst an den Zeilen gemessen und nicht mit
            // "didOverflowWidth": das schlägt schon an, wenn das Feld nur einen Bildpunkt Luft lässt.)
            for (line in 0 until layout.lineCount) {
                val width = layout.getLineRight(line) - layout.getLineLeft(line)
                assertTrue("'$text' ist seitlich abgeschnitten (Zeile $width, Feld ${layout.size.width})", width <= layout.size.width + 0.5f)
            }
        }
    }

    @Test
    @Config(qualifiers = PHONE_360)
    fun auswahlleisten_brechen_bei_grosser_schrift_sauber_um() {
        compose.setContent { ClaudeVoiceTheme(Appearance.Dark) { FontScale(1.5f) { MainScreen(idle, now, ListenChoices(), true, null, MainActions()) } } }
        shoot("haupt_bereit_360_dunkel_schrift_150")
        val layouts = segmentLayouts()
        assertEquals(allChoices.size, layouts.size)
        assertNothingCutOff(layouts)
        assertTrue("Bei dieser Schriftgröße sollte etwas umbrechen", layouts.any { it.lineCount > 1 })
        // Umbrochen wird nur am Leerzeichen oder hinter einem Bindestrich, nie mitten in einer Silbe.
        for (layout in layouts) {
            val text = layout.layoutInput.text.text
            for (line in 0 until layout.lineCount - 1) {
                val last = text[layout.getLineEnd(line) - 1]
                assertTrue("'$text' bricht hinter '$last' um", last == ' ' || last == BREAK_ALLOWED)
            }
        }
        assertTouchTargets()
    }

    @Test
    @Config(qualifiers = PHONE_360)
    fun auswahlleisten_verlieren_auch_bei_doppelt_grosser_schrift_nichts() {
        compose.setContent { ClaudeVoiceTheme(Appearance.Light) { FontScale(2f) { MainScreen(idle, now, ListenChoices(), true, null, MainActions()) } } }
        // Noch enger. Wie auch immer dann umbrochen wird: es muss alles zu lesen sein,
        // die Leisten wachsen dafür in die Höhe.
        val layouts = segmentLayouts()
        assertEquals(allChoices.size, layouts.size)
        assertNothingCutOff(layouts)
    }

    @Test
    @Config(qualifiers = PHONE_320)
    fun auf_schmalen_handys_wird_der_titel_kleiner_statt_abgeschnitten() {
        compose.setContent { Main(idle, appearance = Appearance.Dark) }
        shoot("haupt_bereit_320_dunkel")
        val title = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Claude Voice").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(title)
        // "Ellipsized" heißt: der Text endet in "…", weil er nicht ganz hineinpasst.
        assertFalse("Der Titel ist abgeschnitten", title.single().isLineEllipsized(0) || title.single().hasVisualOverflow)
        assertNothingCutOff(segmentLayouts())
        assertTouchTargets()
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
    fun erscheinungsbild_laesst_sich_in_den_einstellungen_waehlen() {
        var current by mutableStateOf(form)
        compose.setContent {
            ClaudeVoiceTheme(current.appearance) {
                SettingsScreen(current, emptyList(), null, emptyList(), running = false, SettingsActions(onFormChange = { current = it }))
            }
        }
        compose.onNodeWithText("System").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Dunkel").performScrollTo().performClick()
        assertEquals(Appearance.Dark, current.appearance)
        compose.onNodeWithText("Dunkel").assertIsSelected()
        compose.onNodeWithText("Hell").performClick()
        assertEquals(Appearance.Light, current.appearance)
        // Alles andere bleibt, wie es war.
        assertEquals(form.copy(appearance = Appearance.Light), current)
    }

    // --- Sprachausgabe, Schlüssel und Stimmprofil in den Einstellungen ------------------------

    @Test
    fun sprachausgabe_folgt_dem_schluessel_bis_man_selbst_waehlt() {
        var current by mutableStateOf(deviceForm)
        compose.setContent {
            ClaudeVoiceTheme { SettingsScreen(current, emptyList(), null, emptyList(), running = false, SettingsActions(onFormChange = { current = it })) }
        }
        // Ohne ElevenLabs-Schlüssel spricht die Stimme des Geräts. Gewählt hat der Nutzer dabei nichts,
        // und mit Anthropic- und Soniox-Schlüssel fehlt auch nichts.
        compose.onNodeWithText("Stimme des Geräts").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("speech_hint").assertTextEquals(SPEECH_HINT_DEVICE)
        compose.onAllNodesWithText("ElevenLabs Voice-ID").assertCountEquals(0)
        compose.onAllNodesWithTag("key_problem").assertCountEquals(0)
        assertEquals(null, current.speech)

        // Trägt er einen ElevenLabs-Schlüssel ein, wandert die Vorgabe mit: so wie die App bisher sprach.
        compose.onNodeWithText("ElevenLabs API-Key").performScrollTo().performTextReplacement("beispiel")
        compose.onNodeWithText("ElevenLabs").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("speech_hint").assertTextEquals(SPEECH_HINT_ELEVEN)
        compose.onNodeWithText("ElevenLabs Voice-ID").performScrollTo().assertIsDisplayed()
        assertEquals(null, current.speech)

        // Eine ausdrückliche Wahl gilt, auch wenn ein ElevenLabs-Schlüssel da ist.
        compose.onNodeWithText("Stimme des Geräts").performScrollTo().performClick()
        assertEquals(SpeechOutput.DEVICE, current.speech)
        compose.onNodeWithText("Stimme des Geräts").assertIsSelected()
        compose.onAllNodesWithText("ElevenLabs Voice-ID").assertCountEquals(0)
    }

    @Test
    fun fehlende_schluessel_werden_mit_ihrem_zweck_benannt() {
        var current by mutableStateOf(SettingsForm(anthropicKey = "beispiel"))
        compose.setContent {
            ClaudeVoiceTheme { SettingsScreen(current, emptyList(), null, emptyList(), running = false, SettingsActions(onFormChange = { current = it })) }
        }
        compose.onNodeWithTag("key_problem").performScrollTo().assertTextEquals(StartKeys.NO_TRANSCRIPTION)
        compose.onNodeWithText("Soniox API-Key").performScrollTo().performTextReplacement("beispiel")
        compose.onAllNodesWithTag("key_problem").assertCountEquals(0)

        // Wer ElevenLabs sprechen lassen will, braucht dessen Schlüssel: Die Meldung sagt, wofür.
        compose.onNodeWithText("ElevenLabs").performScrollTo().performClick()
        assertEquals(SpeechOutput.ELEVEN, current.speech)
        compose.onNodeWithTag("key_problem").performScrollTo().assertTextEquals(StartKeys.NO_ELEVEN_FOR_SPEECH)
        // Ein Schlüssel nur aus unsichtbaren Zeichen zählt nicht.
        compose.onNodeWithText("ElevenLabs API-Key").performScrollTo().performTextReplacement(" \n")
        compose.onNodeWithTag("key_problem").performScrollTo().assertTextEquals(StartKeys.NO_ELEVEN_FOR_SPEECH)
        compose.onNodeWithText("ElevenLabs API-Key").performScrollTo().performTextReplacement("beispiel")
        compose.onAllNodesWithTag("key_problem").assertCountEquals(0)
    }

    @Test
    fun stimmprofil_laesst_sich_nur_im_stillstand_und_erst_nach_rueckfrage_vergessen() {
        var running by mutableStateOf(true)
        val forgotten = mutableListOf<String>()
        compose.setContent {
            ClaudeVoiceTheme {
                SettingsScreen(
                    form, emptyList(), null, emptyList(), running = running,
                    SettingsActions(onForgetVoiceProfile = { forgotten += it }), voiceProfiles = profiles,
                )
            }
        }
        compose.onNodeWithText("AirPods-Mikro: gelernt aus 12 Äußerungen").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Handy-Mikro: noch kein Profil gelernt").performScrollTo().assertIsDisplayed()
        // Wo es kein Profil gibt, gibt es auch nichts zu vergessen.
        compose.onAllNodesWithText("Vergessen").assertCountEquals(1)

        // Solange zugehört wird, ist der Knopf gesperrt: Die laufende Sitzung schriebe ihr Profil gleich zurück.
        compose.onNodeWithText("Vergessen").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("forget_hint").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Vergessen").performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(0)

        // Gestoppt: Es wird erst nachgefragt; "Abbrechen" lässt das Profil liegen.
        running = false
        compose.onAllNodesWithTag("forget_hint").assertCountEquals(0)
        compose.onNodeWithText("Vergessen").performScrollTo().assertIsEnabled().performClick()
        compose.onNode(hasText("AirPods-Mikro: Die App lernt Ihre Stimme an diesem Mikrofon danach von vorn.")).assertIsDisplayed()
        compose.onNode(hasText("Abbrechen") and hasAnyAncestor(isDialog())).performClick()
        assertEquals(emptyList<String>(), forgotten)

        compose.onNodeWithText("Vergessen").performScrollTo().performClick()
        compose.onNode(hasText("Vergessen") and hasAnyAncestor(isDialog())).performClick()
        assertEquals("gemeldet wird die Mikrofon-Art, deren Profil vergessen werden soll", listOf("AIRPODS"), forgotten)
        compose.onAllNodes(isDialog()).assertCountEquals(0)
    }

    // --- Stimme einlernen -------------------------------------------------------------------

    /** Die Einstellungen mit einem bestimmten Stand des Einlernens. */
    private fun settingsWithEnrol(enrol: () -> EnrolUi, running: () -> Boolean = { false }, actions: SettingsActions = SettingsActions()) {
        compose.setContent {
            ClaudeVoiceTheme { SettingsScreen(form, emptyList(), null, emptyList(), running = running(), actions, enrol = enrol()) }
        }
    }

    private fun progressOf(tag: String): Float =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current

    @Test
    fun einlernen_zeigt_mikrofonwahl_und_start_und_meldet_die_tipper() {
        var ui by mutableStateOf(EnrolUi(bluetoothMic = true))
        var starts = 0
        settingsWithEnrol({ ui }, actions = SettingsActions(onEnrolMicChange = { ui = ui.copy(bluetoothMic = it) }, onEnrolStart = { starts++ }))
        compose.onNodeWithText("Stimme einlernen").performScrollTo().assertIsDisplayed()
        // Vorgabe ist das Mikrofon der Hauptseite; hier das der Kopfhörer.
        compose.onNode(hasText("AirPods-Mikro") and hasAnyAncestor(hasTestTagValue("enrol_mic"))).performScrollTo().assertIsSelected()
        compose.onAllNodesWithTag("enrol_phone_hint").assertCountEquals(0)

        // Am Handy-Mikro steht dabei, was ein Profil dort bedeutet.
        compose.onNode(hasText("Handy-Mikro") and hasAnyAncestor(hasTestTagValue("enrol_mic"))).performClick()
        assertEquals(false, ui.bluetoothMic)
        compose.onNodeWithTag("enrol_phone_hint").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("Einlernen starten").performScrollTo().performClick()
        assertEquals(1, starts)
        assertTouchTargets()
    }

    @Test
    fun einlernen_geht_nur_wenn_nicht_zugehoert_wird() {
        var running by mutableStateOf(true)
        settingsWithEnrol({ EnrolUi() }, running = { running })
        compose.onNodeWithTag("enrol_hint").performScrollTo().assertTextEquals("Zum Einlernen erst das Zuhören stoppen.")
        compose.onAllNodesWithText("Einlernen starten").assertCountEquals(0)
        running = false
        compose.onAllNodesWithTag("enrol_hint").assertCountEquals(0)
        compose.onNodeWithText("Einlernen starten").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun waehrend_der_aufnahme_stehen_text_fortschritt_pegel_und_abbrechen_da() {
        var phase by mutableStateOf<EnrolPhase>(EnrolPhase.Starting)
        var cancels = 0
        settingsWithEnrol({ EnrolUi(true, phase) }, actions = SettingsActions(onEnrolCancel = { cancels++ }))
        compose.onNodeWithTag("enrol_status").performScrollTo().assertTextEquals("Mikrofon wird geöffnet …")
        // Die Erklärung weicht, damit Balken und Vorlesetext zusammen auf den Bildschirm passen.
        compose.onAllNodesWithTag("enrol_about").assertCountEquals(0)

        phase = EnrolPhase.Recording(0.4f, -31.4)
        compose.onNodeWithTag("enrol_text").performScrollTo().assertTextEquals(ENROL_TEXT)
        compose.onNodeWithText("Sprache erkannt: 40 %").performScrollTo().assertIsDisplayed()
        assertEquals(0.4f, progressOf("enrol_progress"), 0.001f)
        compose.onNodeWithText("Pegel: -31 dBFS").performScrollTo().assertIsDisplayed()
        // -31 dBFS auf der Skala von -60 bis 0.
        assertEquals(0.477f, progressOf("enrol_level"), 0.01f)
        // Die Mikrofonwahl ist währenddessen gesperrt, und statt "Starten" gibt es "Abbrechen".
        compose.onNode(hasText("Handy-Mikro") and hasAnyAncestor(hasTestTagValue("enrol_mic"))).assertIsNotEnabled()
        compose.onAllNodesWithText("Einlernen starten").assertCountEquals(0)

        // In einer Sprechpause fällt der Pegelbalken auf null.
        phase = EnrolPhase.Recording(0.4f, null)
        compose.onNodeWithText("Pegel: –").performScrollTo().assertIsDisplayed()
        assertEquals(0f, progressOf("enrol_level"), 0.001f)

        compose.onNodeWithText("Abbrechen").performScrollTo().performClick()
        assertEquals(1, cancels)
    }

    @Test
    fun das_ergebnis_nennt_mikrofon_pegel_und_stimmlage_und_warnt_bei_leiser_stimme() {
        var phase by mutableStateOf<EnrolPhase>(EnrolPhase.Done(MicMode.AIRPODS, VoiceProfile(-27.4, 118.2, 50), null))
        settingsWithEnrol({ EnrolUi(true, phase) })
        compose.onNodeWithTag("enrol_result").performScrollTo()
            .assertTextEquals("Gespeichert für AirPods-Mikro: Pegel -27 dBFS, Stimmlage 118 Hz.")
        compose.onAllNodesWithTag("enrol_warning").assertCountEquals(0)
        compose.onNodeWithText("Noch einmal einlernen").performScrollTo().assertIsDisplayed()

        phase = EnrolPhase.Done(MicMode.HANDY, VoiceProfile(-43.0, 201.0, 50), "Ihre Stimme kommt sehr leise an.")
        compose.onNodeWithTag("enrol_result").performScrollTo()
            .assertTextEquals("Gespeichert für Handy-Mikro: Pegel -43 dBFS, Stimmlage 201 Hz.")
        compose.onNodeWithTag("enrol_warning").performScrollTo().assertTextEquals("Ihre Stimme kommt sehr leise an.")

        phase = EnrolPhase.Failed("Es war keine Stimme zu hören.")
        compose.onNodeWithTag("enrol_error").performScrollTo().assertTextEquals("Es war keine Stimme zu hören.")
        compose.onAllNodesWithTag("enrol_result").assertCountEquals(0)
        compose.onNodeWithText("Noch einmal einlernen").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun der_ganze_ablauf_mit_nachgestelltem_mikrofon_speichert_das_profil_und_zeigt_es() {
        // Die Seite, angeschlossen an den echten Ablauf (VoiceEnroller) und ein Mikrofon, das künstliche Töne liefert.
        val mic = FakeEnrolMic()
        val saved = mutableStateListOf<Pair<MicMode, VoiceProfile>>() // beobachtbar: die Seite zeichnet neu, wenn gespeichert wird
        lateinit var enroller: VoiceEnroller
        compose.setContent {
            val scope = rememberCoroutineScope()
            enroller = remember { VoiceEnroller(mic, scope, { false }, { m, p -> saved += m to p }) }
            val phase by enroller.phase.collectAsState()
            var headset by remember { mutableStateOf(true) }
            val rows = saved.lastOrNull()?.let { (_, p) ->
                listOf(VoiceProfileRow("AIRPODS", "AirPods-Mikro", p.samples, "", formatMeasurement(p.levelDb, p.pitchHz)))
            } ?: listOf(VoiceProfileRow("AIRPODS", "AirPods-Mikro", null, ""))
            ClaudeVoiceTheme {
                SettingsScreen(
                    form, emptyList(), null, emptyList(), running = false,
                    SettingsActions(
                        onEnrolMicChange = { headset = it },
                        onEnrolStart = { enroller.start(headset) },
                        onEnrolCancel = { enroller.cancel() },
                    ),
                    voiceProfiles = rows, enrol = EnrolUi(headset, phase),
                )
            }
        }
        compose.onNodeWithText("AirPods-Mikro: noch kein Profil gelernt").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Einlernen starten").performScrollTo().performClick()
        compose.waitUntil(5_000) { enroller.phase.value is EnrolPhase.Recording }
        compose.onNodeWithTag("enrol_text").performScrollTo().assertIsDisplayed()

        // Erst zu wenig: Der Balken wächst, fertig ist es noch nicht.
        mic.push(FakeEnrolMic.reading(3, -33.0, 120.0))
        compose.waitUntil(5_000) { (enroller.phase.value as? EnrolPhase.Recording)?.fraction?.let { it > 0.4f } == true }
        assertTrue(saved.isEmpty())

        mic.push(FakeEnrolMic.reading(5, -33.0, 120.0))
        compose.waitUntil(5_000) { enroller.phase.value is EnrolPhase.Done }
        compose.onNodeWithTag("enrol_result").performScrollTo()
            .assertTextEquals("Gespeichert für AirPods-Mikro: Pegel -33 dBFS, Stimmlage 120 Hz.")
        assertEquals(listOf(MicMode.AIRPODS), saved.map { it.first })
        assertFalse("das Mikrofon ist wieder frei", mic.isOpen)
        compose.onNodeWithText("AirPods-Mikro: eingelernt (-33 dBFS · 120 Hz)").performScrollTo().assertIsDisplayed()
    }

    // --- Messwerte in der Mitschrift ----------------------------------------------------------

    @Test
    fun messwerte_sind_aus_bis_man_sie_einschaltet() {
        var current by mutableStateOf(form)
        compose.setContent {
            ClaudeVoiceTheme { SettingsScreen(current, emptyList(), null, emptyList(), running = false, SettingsActions(onFormChange = { current = it })) }
        }
        assertEquals(false, current.showMeasurements)
        compose.onNodeWithText("Messwerte anzeigen").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Aus") and hasAnyAncestor(hasTestTagValue("measurements_choice"))).performScrollTo().assertIsSelected()
        compose.onNode(hasText("An") and hasAnyAncestor(hasTestTagValue("measurements_choice"))).performClick()
        assertEquals(true, current.showMeasurements)
    }

    @Test
    fun eine_zeile_zeigt_ihre_messwerte_nur_auf_wunsch_und_nur_wenn_es_welche_gibt() {
        var show by mutableStateOf(false)
        val measured = TranscriptLine("ICH", "S1", "Wie hoch ist die Zugspitze?", levelDb = -27.3, pitchHz = 118.4)
        val plain = TranscriptLine("NAH", "S2", "Keine Ahnung.")
        compose.setContent {
            ClaudeVoiceTheme {
                androidx.compose.foundation.layout.Column {
                    TranscriptRow(measured, showMeasurements = show)
                    TranscriptRow(plain, showMeasurements = show)
                }
            }
        }
        compose.onAllNodesWithTag("measurement").assertCountEquals(0)
        show = true
        // Nur die Zeile mit Werten bekommt den Anhang.
        compose.onAllNodesWithTag("measurement").assertCountEquals(1)
        compose.onNodeWithTag("measurement").assertTextEquals("-27 dBFS · 118 Hz")
    }

    @Test
    fun die_hauptseite_bleibt_mit_eingeschalteten_messwerten_unveraendert_solange_keine_geliefert_werden() {
        compose.setContent { ClaudeVoiceTheme { MainScreen(listening, now, ListenChoices(), true, null, MainActions(), showMeasurements = true) } }
        compose.onNodeWithText("Dann nehmen wir die frühe Bahn.").assertIsDisplayed()
        compose.onAllNodesWithTag("measurement").assertCountEquals(0)
    }

    @Test
    fun ohne_stimmprofile_erscheint_der_abschnitt_nicht() {
        compose.setContent { ClaudeVoiceTheme { SettingsScreen(form, emptyList(), null, emptyList(), running = false, SettingsActions()) } }
        compose.onAllNodesWithText("Gelerntes Stimmprofil").assertCountEquals(0)
    }

    // --- Erscheinungsbild -------------------------------------------------------------------

    /** Zeichnet nichts, merkt sich nur, ob das Thema gerade dunkel ist. */
    private fun isDarkWith(appearance: Appearance): Boolean {
        var dark: Boolean? = null
        compose.setContent { ClaudeVoiceTheme(appearance) { dark = Glass.colors.isDark } }
        compose.waitForIdle()
        return dark!!
    }

    @Test
    fun am_hellen_handy_ist_system_hell() = assertEquals(false, isDarkWith(Appearance.System))

    @Test
    @Config(qualifiers = PHONE_412_NIGHT)
    fun am_dunklen_handy_ist_system_dunkel() = assertEquals(true, isDarkWith(Appearance.System))

    @Test
    @Config(qualifiers = PHONE_412_NIGHT)
    fun hell_bleibt_hell_auch_am_dunklen_handy() = assertEquals(false, isDarkWith(Appearance.Light))

    @Test
    fun dunkel_bleibt_dunkel_auch_am_hellen_handy() = assertEquals(true, isDarkWith(Appearance.Dark))

    // Der Fensterhintergrund (res/values/themes.xml) ist kurz zu sehen, bevor Compose zeichnet.
    // Er muss dieselbe Farbe haben wie der Grund der Oberfläche, sonst blitzt es beim Start.
    private fun windowBackdrop(): Int =
        ApplicationProvider.getApplicationContext<Application>().getColor(app.claudevoice.R.color.window_backdrop)

    @Test
    fun fensterhintergrund_passt_zum_hellen_grund() = assertEquals(backdropArgb(dark = false), windowBackdrop())

    @Test
    @Config(qualifiers = PHONE_412_NIGHT)
    fun fensterhintergrund_passt_zum_dunklen_grund() = assertEquals(backdropArgb(dark = true), windowBackdrop())

    // Die Symbole in Status- und Navigationsleiste (Uhr, Akku, Zurück-Geste) müssen zum Grund passen.
    // "Light" heißt in Android: die Leiste ist hell, ihre Symbole sind deshalb dunkel.
    @Test
    fun leistensymbole_passen_zum_erscheinungsbild() {
        val activity = compose.activity
        fun bars() = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        fun windowColor() = (activity.window.decorView.background as ColorDrawable).color

        compose.runOnUiThread { activity.applySystemBars(dark = true) }
        assertFalse("Dunkel: helle Symbole in der Statusleiste", bars().isAppearanceLightStatusBars)
        assertFalse("Dunkel: helle Symbole in der Navigationsleiste", bars().isAppearanceLightNavigationBars)
        assertEquals(backdropArgb(dark = true), windowColor())

        // Ein Wechsel in den Einstellungen ruft dieselbe Funktion noch einmal auf.
        compose.runOnUiThread { activity.applySystemBars(dark = false) }
        assertTrue("Hell: dunkle Symbole in der Statusleiste", bars().isAppearanceLightStatusBars)
        assertTrue("Hell: dunkle Symbole in der Navigationsleiste", bars().isAppearanceLightNavigationBars)
        assertEquals(backdropArgb(dark = false), windowColor())

        // Die Leisten selbst bleiben durchsichtig: die Oberfläche zeichnet darunter durch.
        @Suppress("DEPRECATION")
        assertEquals(listOf(Color.TRANSPARENT, Color.TRANSPARENT), listOf(activity.window.statusBarColor, activity.window.navigationBarColor))
    }

    @Test
    fun gespeicherte_werte_des_erscheinungsbilds() {
        assertEquals(Appearance.Dark, Appearance.fromKey("dunkel"))
        assertEquals(Appearance.Light, Appearance.fromKey("hell"))
        assertEquals(Appearance.System, Appearance.fromKey("system"))
        // Fehlt der Wert oder ist er unbekannt, gilt die Einstellung des Handys.
        assertEquals(Appearance.System, Appearance.fromKey(null))
        assertEquals(Appearance.System, Appearance.fromKey("lila"))
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

/** Trifft den Knoten mit genau diesem Test-Namen (für "liegt innerhalb von ..."). */
private fun hasTestTagValue(tag: String) = SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag)

private const val PHONE_412 = "w412dp-h915dp-xhdpi"
private const val PHONE_412_NIGHT = "w412dp-h915dp-night-xhdpi"
private const val PHONE_360 = "w360dp-h640dp-xhdpi"
private const val PHONE_320 = "w320dp-h640dp-xhdpi"
private const val PHONE_LANDSCAPE = "w640dp-h360dp-land-xhdpi"

// Robolectric: "hardware" lässt Bilder über dieselbe Zeichen-Pipeline entstehen wie auf einem Handy.
private const val HARDWARE_SWITCH = "robolectric.pixelCopyRenderMode"
