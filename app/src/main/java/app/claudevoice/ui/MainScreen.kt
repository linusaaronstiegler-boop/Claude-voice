package app.claudevoice.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.claudevoice.core.UiState
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

// Werte genau so, wie sie in den Prefs stehen und wie der Service sie liest.
const val MODE_DAUER = "dauer"
const val MODE_WECKWORT = "weckwort"
const val REACH_ICH = "ich"
const val REACH_GESPRAECH = "gespraech"
const val REACH_RAUM = "raum"

// Beschriftung der Bereitschaft. "\u00A0" ist ein Leerzeichen, an dem nicht umbrochen wird:
// auf schmalen Handys steht "(Hey Jarvis)" dann zusammen in der zweiten Zeile statt zerrissen.
// (Als Code geschrieben, weil man es einem gewöhnlichen Leerzeichen sonst nicht ansieht.)
const val LABEL_WECKWORT = "Bereitschaft (Hey\u00A0Jarvis)"

// Die drei Auswahlleisten: gespeicherter Wert und Beschriftung. Als Listen an einer Stelle, weil die
// Beschriftungen zweimal gebraucht werden: in den Leisten und in der Kurzfassung während des Zuhörens.
private val MODE_OPTIONS = listOf(MODE_DAUER to "Dauer-Zuhören", MODE_WECKWORT to LABEL_WECKWORT)
private val MIC_OPTIONS = listOf(true to "AirPods-Mikro", false to "Handy-Mikro")
private val REACH_OPTIONS = listOf(REACH_ICH to "Nur ich", REACH_GESPRAECH to "Mein Gespräch", REACH_RAUM to "Ganzer Raum")

private fun <T> List<Pair<T, String>>.labelOf(value: T): String = firstOrNull { it.first == value }?.second.orEmpty()

/** Was vor dem Start gewählt wird: Betriebsart, Mikrofon und wessen Fragen zählen. */
data class ListenChoices(
    val mode: String = MODE_DAUER,
    val bluetoothMic: Boolean = true,
    val reach: String = REACH_GESPRAECH,
) {
    /** Die Wahl in einer Zeile, z. B. "Dauer-Zuhören · AirPods-Mikro · Mein Gespräch". */
    val summary: String
        get() = listOf(MODE_OPTIONS.labelOf(mode), MIC_OPTIONS.labelOf(bluetoothMic), REACH_OPTIONS.labelOf(reach))
            .filter { it.isNotEmpty() }.joinToString(" · ")
}

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
 * Aufbau fürs Handy, in zwei Ebenen:
 *  - hinten: Titel, Status-Scheibe und darunter die rollende Mitte. Ruht die App, stehen dort die
 *    Auswahlleisten; läuft sie, sind sie gesperrt und schrumpfen zu einer Zeile in der Status-Scheibe,
 *    damit die Mitschrift den Platz bekommt.
 *  - vorn, unten schwebend: Hinweis, Frage/Antwort und der große Knopf, dort wo der Daumen ist.
 *    Die Mitschrift läuft unter die oberste dieser Scheiben und scheint dort verschwommen durch.
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
    // Nur mit echter Unschärfe (siehe LocalBackdropBlur) gibt es diesen Zustand. Er verbindet das, was
    // weichgezeichnet werden soll ("Quelle": Hintergrund und Mitschrift), mit dem schwebenden Glas davor.
    // Titel und Status-Scheibe sind bewusst keine Quelle: sie liegen nie unter dem Glas, und so muss
    // nicht jedes Mal neu weichgezeichnet werden, wenn der Statuspunkt atmet oder die Uhr weiterzählt.
    val haze = if (LocalBackdropBlur.current) rememberHazeState() else null
    BoxWithConstraints(modifier.fillMaxSize()) {
        // Auf kurzen Bildschirmen ist jede Zeile kostbar: dort rollen Lage und Kurzfassung mit der
        // Mitschrift mit, statt oben fest zu stehen. "Kurz" hängt auch von der Schriftgröße ab: wer
        // die Schrift am Handy doppelt so groß stellt, hat nur halb so viele Zeilen Platz.
        val short = maxHeight < 700.dp * LocalDensity.current.fontScale
        // Eine lange Antwort darf die Mitschrift nicht verdrängen: der Text der Karte bekommt höchstens
        // knapp ein Drittel der Bildschirmhöhe und rollt darüber hinaus in sich.
        val cardTextMax = (maxHeight * 0.3f).coerceIn(96.dp, 260.dp)
        val scene = ui.scene?.takeIf { it.isNotBlank() }
        val locked = choices.takeIf { ui.running }
        // So hoch ist der schwebende Bereich gerade. Die Mitschrift hält unten so viel Abstand,
        // dass ihre letzte Zeile darüber zu stehen kommt.
        var dockHeight by remember { mutableStateOf(0.dp) }
        // Und so hoch ist darin die oberste Scheibe.
        var topPaneHeight by remember { mutableStateOf(0.dp) }
        // So viel Platz bleibt unter der Status-Scheibe bis zum unteren Rand. Höher darf der schwebende
        // Bereich nicht werden, sonst läge er über dem Status (Querformat, sehr große Schrift).
        // Vor der ersten Messung unbekannt; dann gilt noch keine Grenze.
        var roomBelowStatus by remember { mutableStateOf(Dp.Unspecified) }
        val density = LocalDensity.current

        AmbientBackdrop(Modifier.blurSource(haze, layer = 0f))
        Column(
            // Abstand zur Statusleiste und zu den Seiten; nach unten reicht die Liste bis zum Rand durch.
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        ) {
            Header("Claude Voice", "Einstellungen", actions.onOpenSettings, Modifier.padding(horizontal = PAGE_MARGIN))
            StatusCard(ui, nowMs, scene.takeIf { !short }, locked.takeIf { !short }, Modifier.padding(horizontal = PAGE_MARGIN))
            Conversation(
                ui, choices, scene.takeIf { short }, locked.takeIf { short }, actions,
                bottomSpace = dockHeight,
                // Mit Unschärfe scheint die Mitschrift unter der obersten Scheibe noch durch und verliert sich
                // bis zu deren unterem Rand. Ohne Unschärfe endet sie schon in der Lücke davor: sonst stünde
                // scharfe Schrift hinter der Schrift auf dem Glas.
                fadeUnderGlass = if (haze != null) DOCK_GAP + topPaneHeight else DOCK_GAP,
                // Die Mitte füllt genau den Platz unter der Status-Scheibe; ihre Höhe ist die gesuchte Grenze.
                Modifier.weight(1f).onSizeChanged { roomBelowStatus = with(density) { it.height.toDp() } }.blurSource(haze, layer = 1f),
            )
        }
        CompositionLocalProvider(LocalHaze provides haze) {
            Dock(
                ui, hasKeys, hint, cardTextMax, actions,
                onTopPaneHeight = { topPaneHeight = with(density) { it.toDp() } },
                Modifier
                    .align(Alignment.BottomCenter)
                    // Wird es eng, gibt die Antwortkarte nach (ihr Text rollt), nicht der Status.
                    .heightIn(max = roomBelowStatus)
                    .onSizeChanged { dockHeight = with(density) { it.height.toDp() } }
                    // Abstand zur Navigationsleiste (und zur Tastatur).
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    // Der obere Abstand zählt zur gemessenen Höhe: so viel Luft bleibt zwischen der
                    // letzten Zeile der Mitschrift und der ersten schwebenden Scheibe.
                    .padding(start = PAGE_MARGIN, end = PAGE_MARGIN, top = DOCK_GAP, bottom = 16.dp),
            )
        }
    }
}

/** Meldet diesen Baustein als Quelle für die Unschärfe an; [layer] sagt, was über was liegt. Ohne [haze] passiert nichts. */
private fun Modifier.blurSource(haze: HazeState?, layer: Float): Modifier =
    if (haze != null) hazeSource(haze, zIndex = layer) else this

/** Abstand des Inhalts zum linken und rechten Bildschirmrand. */
private val PAGE_MARGIN = 20.dp

/** Luft zwischen der letzten Zeile der Mitschrift und der ersten schwebenden Scheibe. */
private val DOCK_GAP = 20.dp

/** Die Status-Scheibe: Punkt und Status, darunter Antwortzeit, ggf. Lage und die gesperrte Auswahl. */
@Composable
private fun StatusCard(ui: UiState, nowMs: Long, scene: String?, locked: ListenChoices?, modifier: Modifier = Modifier) {
    val c = Glass.colors
    GlassSurface(modifier.fillMaxWidth().testTag("status_card"), padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            StatusDot(ui.running, Modifier.padding(top = 6.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                statusText(ui.status, ui.running, ui.startedAtMs, nowMs),
                Modifier.testTag("status"),
                style = MaterialTheme.typography.bodyLarge, color = c.ink,
            )
        }
        // Die Nebenzeilen beginnen ganz links unter dem Punkt: so passen sie meist in eine Zeile.
        metaLine(ui.latencyMs, ui.lastDecision)?.let {
            Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
        }
        mutedText(ui.mutedUntilMs, nowMs)?.takeIf { ui.running }?.let {
            Text(it, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
        }
        if (scene != null || locked != null) {
            Hairline(Modifier.padding(top = 12.dp, bottom = 12.dp))
            SceneAndChoices(scene, locked)
        }
    }
}

/** Lage und gesperrte Auswahl untereinander, mit einer feinen Linie dazwischen. */
@Composable
private fun SceneAndChoices(scene: String?, locked: ListenChoices?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        scene?.let { SceneLine(it) }
        if (scene != null && locked != null) Hairline()
        locked?.let { LockedChoices(it) }
    }
}

/** Die Lage-Einschätzung ("wer spricht mit wem worüber"), abgesetzt wie ein Zitat. */
@Composable
private fun SceneLine(scene: String, modifier: Modifier = Modifier) {
    val c = Glass.colors
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(2.dp).fillMaxHeight().clip(Glass.Capsule).background(c.accent))
        Spacer(Modifier.width(12.dp))
        Column {
            SmallLabel("Lage")
            Text(
                scene,
                style = MaterialTheme.typography.titleMedium.copy(fontStyle = FontStyle.Italic, fontSize = 15.sp, lineHeight = 21.sp),
                color = c.ink, maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Während zugehört wird, lässt sich die Auswahl nicht ändern. Statt drei gesperrter Leisten steht
 * hier nur, was gilt: das spart Platz für die Mitschrift und es gibt nichts, das man vergeblich antippt.
 */
@Composable
private fun LockedChoices(choices: ListenChoices, modifier: Modifier = Modifier) {
    val c = Glass.colors
    Column(modifier.fillMaxWidth().testTag("locked_choices"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallLabel("Auswahl")
            Spacer(Modifier.width(8.dp))
            Text("zum Ändern erst stoppen", style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
        }
        Text(choices.summary, style = MaterialTheme.typography.bodyMedium, color = c.ink)
    }
}

/**
 * Die rollende Mitte. Ruht die App: die Auswahlleisten, dann die Mitschrift. Läuft sie: (auf kurzen
 * Bildschirmen Lage und Kurzfassung der Auswahl,) dann die Mitschrift.
 * [bottomSpace] ist der Platz, den der schwebende Bereich unten verdeckt; über die Strecke
 * [fadeUnderGlass] verliert sich die Mitschrift darunter (siehe [fadeEdges]).
 */
@Composable
private fun Conversation(
    ui: UiState,
    choices: ListenChoices,
    scene: String?,
    locked: ListenChoices?,
    actions: MainActions,
    bottomSpace: Dp,
    fadeUnderGlass: Dp,
    modifier: Modifier = Modifier,
) {
    val c = Glass.colors
    val lines = remember(ui.transcript) { ui.transcript.map(::parseTranscriptLine) }
    val partial = ui.partial.takeIf { it.isNotBlank() }
    val hasTranscript = lines.isNotEmpty() || partial != null
    val listState = rememberLazyListState()
    val showContext = scene != null || locked != null

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
    val fixedItems = (if (!ui.running) 1 else 0) + (if (showContext) 1 else 0) + 1
    val lastIndex = fixedItems + lines.size + (if (partial != null) 1 else 0) - 1
    LaunchedEffect(ui.running) {
        // Start: wieder mitlaufen. Stopp: nach oben, damit die Auswahlleisten wieder zu sehen sind.
        if (ui.running) follow = true else listState.scrollToItem(0)
    }
    // Auch wenn der schwebende Bereich wächst (eine Antwort erscheint), rückt die Liste nach.
    LaunchedEffect(ui.running, lastIndex, partial, follow, bottomSpace) {
        if (ui.running && follow && hasTranscript) listState.scrollToItem(lastIndex)
    }

    // --- Der obere Rand ---------------------------------------------------------------------
    // Läuft die Liste von selbst mit, soll oben keine halbe Zeile stehen: die angeschnittene Zeile
    // wird dann gar nicht gezeichnet, die Mitschrift beginnt mit der ersten ganzen. Sonst (man rollt
    // selbst, oder oben ragt eine ganze Scheibe herein) blendet der Rand weich aus, damit man sieht,
    // dass darüber noch etwas liegt.
    val tidy = ui.running && follow
    val maxCut = with(LocalDensity.current) { 96.dp.roundToPx() } // nur Zeilen weglassen, keine ganze Scheibe
    val cutTop by remember(listState, maxCut) {
        // "derivedStateOf": wird nur neu berechnet, wenn sich die Lage der Einträge ändert.
        derivedStateOf {
            val info = listState.layoutInfo
            val top = info.viewportStartOffset
            val firstWhole = info.visibleItemsInfo.firstOrNull { it.offset >= top }
            (firstWhole?.let { it.offset - top } ?: 0).takeIf { it <= maxCut } ?: 0
        }
    }
    // Die Scheibe mit Lage und Auswahl (kurze Bildschirme) wirft einen Schatten über ihren Rand hinaus,
    // bis in die Zeile darunter. Wird sie oben weggelassen, muss sie deshalb als Ganzes verschwinden:
    // nur abgeschnitten bliebe der untere Rest ihres Schattens als harter Streifen stehen.
    val contextCut by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull()
            first != null && first.key == "context" && first.offset < info.viewportStartOffset
        }
    }
    val fade by animateDpAsState(if (listState.canScrollBackward) 16.dp else 0.dp, label = "rand")
    // Gelesen wird "cutTop" erst beim Zeichnen (in den beiden Klammern unten): so löst das Rollen
    // nur neues Zeichnen aus und baut die Seite nicht jedes Mal neu auf.
    LazyColumn(
        modifier
            .fillMaxWidth()
            .fadeEdges(
                top = { if (tidy && cutTop > 0) 0.dp else fade },
                bottomCover = { bottomSpace }, bottomFade = { fadeUnderGlass },
            )
            .drawWithContent {
                val cut = if (tidy) cutTop.toFloat() else 0f
                if (cut > 0f) clipRect(top = cut) { this@drawWithContent.drawContent() } else drawContent()
            }
            .nestedScroll(watchFinger)
            .testTag("conversation"),
        state = listState,
        // Der seitliche Rand gehört in die Liste (nicht außen herum), damit die Schatten der Scheiben Platz haben.
        contentPadding = PaddingValues(start = PAGE_MARGIN, end = PAGE_MARGIN, top = 12.dp, bottom = bottomSpace),
    ) {
        if (!ui.running) item(key = "choices") { ChoiceCard(choices, actions, Modifier.padding(bottom = 12.dp)) }
        if (showContext) {
            item(key = "context") {
                GlassSurface(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        // Gelesen wird erst beim Zeichnen, wie bei "cutTop" unten.
                        .graphicsLayer { alpha = if (tidy && cutTop > 0 && contextCut) 0f else 1f },
                    padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    SceneAndChoices(scene, locked)
                }
            }
        }
        item(key = "label") {
            Row(Modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallLabel("Mitschrift")
                Spacer(Modifier.width(8.dp))
                Text("wird nicht gespeichert", style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
            }
        }
        if (!hasTranscript) {
            item(key = "empty") {
                Text(
                    if (ui.running) "Noch nichts gehört." else "Hier erscheint, was gehört wird.",
                    Modifier.padding(start = 4.dp, top = 6.dp, bottom = 6.dp),
                    style = MaterialTheme.typography.bodyMedium, color = c.inkSoft,
                )
            }
        }
        items(lines) { TranscriptRow(it) }
        if (partial != null) {
            // Die laufende Äußerung: noch nicht fertig, deshalb kursiv und ohne Sprecher.
            item(key = "partial") { TranscriptRow(TranscriptLine(null, null, "$partial …"), unfinished = true) }
        }
    }
}

/** Die drei Auswahlleisten in einer Scheibe. Nur zu sehen, solange nicht zugehört wird. */
@Composable
private fun ChoiceCard(choices: ListenChoices, actions: MainActions, modifier: Modifier = Modifier) {
    GlassSurface(modifier.fillMaxWidth(), padding = PaddingValues(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallLabel("Modus", Modifier.padding(start = 8.dp, top = 4.dp))
            SegmentedChoice(MODE_OPTIONS, choices.mode, actions.onModeChange)
            SmallLabel("Mikrofon", Modifier.padding(start = 8.dp, top = 8.dp))
            SegmentedChoice(MIC_OPTIONS, choices.bluetoothMic, actions.onMicChange)
            SmallLabel("Reichweite", Modifier.padding(start = 8.dp, top = 8.dp))
            SegmentedChoice(REACH_OPTIONS, choices.reach, actions.onReachChange)
        }
    }
}

/**
 * Eine Zeile der Mitschrift wie in einem Drehbuch: links das Sprecher-Kürzel in gesperrten
 * Großbuchstaben, rechts der Text. So erkennt man auf einen Blick, wer was gesagt hat.
 */
@Composable
private fun TranscriptRow(line: TranscriptLine, unfinished: Boolean = false) {
    val c = Glass.colors
    // Spaltenbreite in "sp" gedacht: stellt jemand die Schrift größer, wächst die Spalte mit.
    val prefixWidth = with(LocalDensity.current) { 66.sp.toDp() }
    val isAssistant = line.voice == "ALFRED"
    val isBackground = line.voice == "FERN" || line.voice == "?"
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, bottom = 4.dp)) {
        Text(
            if (unfinished) "…" else line.prefix,
            Modifier.width(prefixWidth).padding(top = 4.dp, end = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = when {
                line.voice == "ICH" -> c.ink
                isAssistant -> c.label
                else -> c.inkSoft
            },
        )
        Text(
            line.text,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = if (isAssistant) FontFamily.Serif else null,
            fontStyle = if (unfinished) FontStyle.Italic else null,
            color = if (isBackground || unfinished) c.inkSoft else c.ink,
        )
    }
}

/**
 * Der schwebende Bereich unten: Hinweise, Frage/Antwort und der große Knopf.
 * [onTopPaneHeight] meldet die Höhe (in Bildpunkten) der Scheibe, die gerade zuoberst steht.
 */
@Composable
private fun Dock(
    ui: UiState,
    hasKeys: Boolean,
    hint: String?,
    cardTextMax: Dp,
    actions: MainActions,
    onTopPaneHeight: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Glass.colors
    val notice = ui.error ?: hint
    val keysMissing = !hasKeys && !ui.running
    val showNotice = notice != null || keysMissing
    val question = ui.question
    // Welche Scheibe zuoberst steht, hängt davon ab, was gerade zu zeigen ist. Nur sie wird vermessen.
    val measured = Modifier.onSizeChanged { onTopPaneHeight(it.height) }
    fun measuredIf(isTop: Boolean): Modifier = if (isTop) measured else Modifier
    Column(modifier.fillMaxWidth().testTag("dock"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showNotice) {
            GlassSurface(Modifier.fillMaxWidth().then(measured), floating = true, padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    notice?.let { Text(it, Modifier.testTag("notice"), style = MaterialTheme.typography.bodyMedium, color = c.alert) }
                    if (keysMissing) {
                        Text(
                            "Es fehlen noch die API-Schlüssel. Bitte zuerst in den Einstellungen eintragen.",
                            style = MaterialTheme.typography.bodyMedium, color = c.ink,
                        )
                    }
                }
            }
        }
        if (question != null) {
            // "weight" macht die Karte zum nachgiebigen Teil: Hinweis und Knopf behalten ihre Größe,
            // die Karte bekommt den Rest (und nimmt davon nur, was sie braucht).
            AnswerCard(
                question, ui.answer, ui.speaking, cardTextMax, actions.onStopSpeaking,
                Modifier.weight(1f, fill = false).then(measuredIf(!showNotice)),
            )
        }
        PrimaryButton(
            if (ui.running) "Stopp" else "Zuhören starten",
            active = ui.running,
            onClick = actions.onStartStop,
            modifier = Modifier.testTag("start_stop").then(measuredIf(!showNotice && question == null)),
        )
    }
}

@Composable
private fun AnswerCard(
    question: String,
    answer: String,
    speaking: Boolean,
    textMax: Dp,
    onStopSpeaking: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Glass.colors
    GlassSurface(modifier.fillMaxWidth(), accent = true, floating = true) {
        val scroll = rememberScrollState()
        // Die Antwort wächst Satz für Satz; die Karte rollt mit, damit das Neueste sichtbar bleibt.
        LaunchedEffect(scroll) { snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) } }
        // Auch hier gibt der Text nach, nicht "Antwort stoppen": ist die Karte knapp bemessen,
        // wird der rollende Bereich kleiner und der Knopf bleibt ganz.
        Column(Modifier.weight(1f, fill = false).fillMaxWidth().heightIn(max = textMax).verticalScroll(scroll)) {
            SmallLabel("Frage")
            Text(question, style = MaterialTheme.typography.titleMedium, color = c.ink)
            Spacer(Modifier.height(10.dp))
            SmallLabel("Antwort")
            Text(answer.ifEmpty { "…" }, style = MaterialTheme.typography.bodyLarge, color = c.ink)
        }
        if (speaking) {
            Spacer(Modifier.height(12.dp))
            GlassButton("Antwort stoppen", onStopSpeaking)
        }
    }
}
