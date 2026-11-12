package app.claudevoice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.claudevoice.core.UiState

// Werte genau so, wie sie in den Prefs stehen und wie der Service sie liest.
const val MODE_DAUER = "dauer"
const val MODE_WECKWORT = "weckwort"
const val REACH_ICH = "ich"
const val REACH_GESPRAECH = "gespraech"
const val REACH_RAUM = "raum"

// Beschriftung der Bereitschaft. Das Zeichen   ist ein Leerzeichen, an dem nicht umbrochen wird:
// auf schmalen Handys steht "(Hey Jarvis)" dann zusammen in der zweiten Zeile statt zerrissen.
const val LABEL_WECKWORT = "Bereitschaft (Hey Jarvis)"

/** Was vor dem Start gewählt wird: Betriebsart, Mikrofon und wessen Fragen zählen. */
data class ListenChoices(
    val mode: String = MODE_DAUER,
    val bluetoothMic: Boolean = true,
    val reach: String = REACH_GESPRAECH,
)

/** Alles, was die Hauptseite auslösen kann. Die Seite selbst weiß nicht, was dahinter passiert. */
class MainActions(
    val onModeChange: (String) -> Unit = {},
    val onMicChange: (Boolean) -> Unit = {},
    val onReachChange: (String) -> Unit = {},
    val onStartStop: () -> Unit = {},
    val onStopSpeaking: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
)

/**
 * Die Hauptseite. "Zustandslos": sie bekommt alles, was sie zeigt, als Parameter und meldet Tipper über
 * [actions] zurück. Dadurch lässt sie sich im Test mit beliebigen Beispieldaten zeichnen, ohne Mikrofon,
 * Schlüssel oder Service.
 *
 * Aufbau fürs Handy, von oben nach unten:
 *  - oben fest: Titel, Status, Lage
 *  - Mitte, rollt: Auswahlleisten und Mitschrift. Läuft die Mitschrift, wandern die (dann ohnehin
 *    gesperrten) Auswahlleisten nach oben aus dem Bild und machen dem Gespräch Platz.
 *  - unten fest: Hinweis, Frage/Antwort und der große Knopf, dort wo der Daumen ist.
 */
@Composable
fun MainScreen(
    ui: UiState,
    nowMs: Long,
    choices: ListenChoices,
    hasKeys: Boolean,
    hint: String?,
    actions: MainActions,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Palette.Cream)
            // Abstand zu Statusleiste, Navigationsleiste und Tastatur; der Hintergrund reicht darunter durch.
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        // Auf kurzen Bildschirmen ist jede Zeile kostbar: dort rollt die Lage mit der Mitschrift mit,
        // statt oben fest zu stehen.
        val short = maxHeight < 700.dp
        // Eine lange Antwort darf die Mitschrift nicht verdrängen: der Text der Karte bekommt höchstens
        // knapp ein Drittel der Bildschirmhöhe und rollt darüber hinaus in sich.
        val cardTextMax = (maxHeight * 0.3f).coerceIn(96.dp, 260.dp)
        val scene = ui.scene?.takeIf { it.isNotBlank() }
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Header("Claude Voice", "Einstellungen", actions.onOpenSettings)
            StatusBlock(ui, nowMs, scene.takeIf { !short })
            Conversation(ui, choices, scene.takeIf { short }, actions, Modifier.weight(1f))
            BottomArea(ui, hasKeys, hint, cardTextMax, actions)
        }
    }
}

@Composable
private fun StatusBlock(ui: UiState, nowMs: Long, scene: String?) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            // Kleiner Punkt: golden, solange der Service läuft.
            Box(
                Modifier.padding(top = 7.dp).size(9.dp).clip(CircleShape)
                    .background(if (ui.running) Palette.Gold else Palette.Outline),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                statusText(ui.status, ui.running, ui.startedAtMs, nowMs),
                Modifier.testTag("status"),
                style = MaterialTheme.typography.bodyLarge, color = Palette.Navy,
            )
        }
        metaLine(ui.latencyMs, ui.lastDecision)?.let {
            Text(it, Modifier.padding(start = 19.dp), style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft)
        }
        mutedText(ui.mutedUntilMs, nowMs)?.takeIf { ui.running }?.let {
            Text(it, Modifier.padding(start = 19.dp), style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft)
        }
        scene?.let { SceneLine(it, Modifier.padding(top = 6.dp)) }
    }
}

/** Die Lage-Einschätzung ("wer spricht mit wem worüber"), abgesetzt wie ein Zitat. */
@Composable
private fun SceneLine(scene: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(Palette.Gold))
        Spacer(Modifier.width(10.dp))
        Column {
            SmallLabel("Lage")
            Text(
                scene,
                style = MaterialTheme.typography.titleMedium.copy(fontStyle = FontStyle.Italic, fontSize = 15.sp, lineHeight = 21.sp),
                color = Palette.Navy, maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Die rollende Mitte: (auf kurzen Bildschirmen die Lage,) Auswahlleisten, dann die Mitschrift. */
@Composable
private fun Conversation(
    ui: UiState,
    choices: ListenChoices,
    scene: String?,
    actions: MainActions,
    modifier: Modifier = Modifier,
) {
    val lines = remember(ui.transcript) { ui.transcript.map(::parseTranscriptLine) }
    val partial = ui.partial.takeIf { it.isNotBlank() }
    val hasTranscript = lines.isNotEmpty() || partial != null
    val listState = rememberLazyListState()

    // --- Mitlaufen wie in einem Chat --------------------------------------------------------
    // Solange zugehört wird, bleibt das Neueste unten im Bild. Rollt man selbst nach oben, um etwas
    // nachzulesen, bleibt die Liste dort stehen, bis man wieder ganz unten angekommen ist.
    var follow by remember { mutableStateOf(true) }
    val watchFinger = remember(listState) {
        // Wird nur bei Rollbewegungen mit dem Finger aufgerufen, nicht bei unserem eigenen Springen.
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                follow = !listState.canScrollForward
                return Offset.Zero
            }
        }
    }
    // Die Liste beginnt mit festen Einträgen, danach kommen die Zeilen. Der Index des letzten Eintrags:
    val fixedItems = (if (scene != null) 1 else 0) + 2
    val lastIndex = fixedItems + lines.size + (if (partial != null) 1 else 0) - 1
    LaunchedEffect(ui.running) {
        // Start: wieder mitlaufen. Stopp: nach oben, damit die Auswahlleisten wieder zu sehen sind.
        if (ui.running) follow = true else listState.scrollToItem(0)
    }
    LaunchedEffect(ui.running, lastIndex, partial, follow) {
        if (ui.running && follow && hasTranscript) listState.scrollToItem(lastIndex)
    }

    Box(modifier.fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxSize().nestedScroll(watchFinger).testTag("conversation"), state = listState) {
            if (scene != null) item(key = "scene") { SceneLine(scene, Modifier.padding(top = 2.dp, bottom = 4.dp)) }
            item(key = "choices") { ChoiceBlock(choices, enabled = !ui.running, actions) }
            item(key = "label") {
                Row(Modifier.padding(top = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SmallLabel("Mitschrift")
                    Spacer(Modifier.width(8.dp))
                    Text("wird nicht gespeichert", style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft)
                }
            }
            if (!hasTranscript) {
                item(key = "empty") {
                    Text(
                        if (ui.running) "Noch nichts gehört." else "Hier erscheint, was gehört wird.",
                        Modifier.padding(vertical = 6.dp),
                        style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft,
                    )
                }
            }
            items(lines) { TranscriptRow(it) }
            if (partial != null) {
                // Die laufende Äußerung: noch nicht fertig, deshalb kursiv und ohne Sprecher.
                item(key = "partial") { TranscriptRow(TranscriptLine(null, null, "$partial …"), unfinished = true) }
            }
        }
        // Feine Linien zeigen, dass ober- oder unterhalb noch etwas liegt, wohin man rollen kann.
        if (listState.canScrollBackward) Hairline(Modifier.align(Alignment.TopCenter))
        if (listState.canScrollForward) Hairline(Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun ChoiceBlock(choices: ListenChoices, enabled: Boolean, actions: MainActions) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SmallLabel("Modus")
        SegmentedChoice(
            listOf(MODE_DAUER to "Dauer-Zuhören", MODE_WECKWORT to LABEL_WECKWORT),
            choices.mode, actions.onModeChange, enabled = enabled,
        )
        Spacer(Modifier.height(6.dp))
        SmallLabel("Mikrofon")
        SegmentedChoice(
            listOf(true to "AirPods-Mikro", false to "Handy-Mikro"),
            choices.bluetoothMic, actions.onMicChange, enabled = enabled,
        )
        Spacer(Modifier.height(6.dp))
        SmallLabel("Reichweite")
        SegmentedChoice(
            listOf(REACH_ICH to "Nur ich", REACH_GESPRAECH to "Mein Gespräch", REACH_RAUM to "Ganzer Raum"),
            choices.reach, actions.onReachChange, enabled = enabled,
        )
        if (!enabled) {
            Text(
                "Während des Zuhörens gesperrt. Zum Ändern erst stoppen.",
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft,
            )
        }
    }
}

/**
 * Eine Zeile der Mitschrift wie in einem Drehbuch: links das Sprecher-Kürzel in gesperrten
 * Großbuchstaben, rechts der Text. So erkennt man auf einen Blick, wer was gesagt hat.
 */
@Composable
private fun TranscriptRow(line: TranscriptLine, unfinished: Boolean = false) {
    // Spaltenbreite in "sp" gedacht: stellt jemand die Schrift größer, wächst die Spalte mit.
    val prefixWidth = with(LocalDensity.current) { 66.sp.toDp() }
    val isAssistant = line.voice == "ALFRED"
    val isBackground = line.voice == "FERN" || line.voice == "?"
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            if (unfinished) "…" else line.prefix,
            Modifier.width(prefixWidth).padding(top = 4.dp, end = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = when {
                line.voice == "ICH" -> Palette.Navy
                isAssistant -> Palette.GoldDeep
                else -> Palette.InkSoft
            },
        )
        Text(
            line.text,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = if (isAssistant) FontFamily.Serif else null,
            fontStyle = if (unfinished) FontStyle.Italic else null,
            color = if (isBackground || unfinished) Palette.InkSoft else Palette.Navy,
        )
    }
}

@Composable
private fun BottomArea(ui: UiState, hasKeys: Boolean, hint: String?, cardTextMax: Dp, actions: MainActions) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        (ui.error ?: hint)?.let {
            Text(it, Modifier.testTag("notice"), style = MaterialTheme.typography.bodyMedium, color = Palette.Alert)
        }
        if (!hasKeys && !ui.running) {
            Text(
                "Es fehlen noch die API-Schlüssel. Bitte zuerst in den Einstellungen eintragen.",
                style = MaterialTheme.typography.bodyMedium, color = Palette.Navy,
            )
        }
        ui.question?.let { AnswerCard(it, ui.answer, ui.speaking, cardTextMax, actions.onStopSpeaking) }
        Button(
            onClick = actions.onStartStop,
            modifier = Modifier.fillMaxWidth().height(60.dp).testTag("start_stop"),
            shape = MaterialTheme.shapes.medium,
            // Läuft es, wird der Knopf golden: man sieht ohne Lesen, dass gerade zugehört wird.
            colors = ButtonDefaults.buttonColors(
                containerColor = if (ui.running) Palette.Gold else Palette.Navy,
                contentColor = if (ui.running) Palette.Navy else Palette.Cream,
            ),
        ) {
            Text(if (ui.running) "Stopp" else "Zuhören starten", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun AnswerCard(question: String, answer: String, speaking: Boolean, textMax: Dp, onStopSpeaking: () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Palette.Paper).border(1.dp, Palette.Gold, shape).padding(16.dp),
    ) {
        val scroll = rememberScrollState()
        // Die Antwort wächst Satz für Satz; die Karte rollt mit, damit das Neueste sichtbar bleibt.
        LaunchedEffect(scroll) { snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) } }
        Column(Modifier.fillMaxWidth().heightIn(max = textMax).verticalScroll(scroll)) {
            SmallLabel("Frage")
            Text(question, style = MaterialTheme.typography.titleMedium, color = Palette.Navy)
            Spacer(Modifier.height(10.dp))
            SmallLabel("Antwort")
            Text(answer.ifEmpty { "…" }, style = MaterialTheme.typography.bodyLarge, color = Palette.Navy)
        }
        if (speaking) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onStopSpeaking,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) { Text("Antwort stoppen") }
        }
    }
}
