package app.claudevoice.ui

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

// Bausteine, die Hauptseite und Einstellungen gemeinsam benutzen.
//
// Der Glas-Eindruck entsteht aus vier einfachen Schichten, von hinten nach vorn:
//   1. AmbientBackdrop: ruhiger Grund mit weichen Lichtscheinen
//   2. ein weicher Schatten um die Scheibe (softShadow)
//   3. die durchscheinende Füllung, bei schwebenden Scheiben mit Unschärfe dahinter (frost)
//   4. die hauchdünne Kante, oben heller als unten
// Die Farben dazu stehen in Theme.kt (GlassColors).

/**
 * Der Zustand der Unschärfe-Bibliothek "Haze" für die Seite, über der gerade Glas schwebt.
 * null heißt: keine echte Unschärfe (so in den meisten Tests).
 */
internal val LocalHaze = staticCompositionLocalOf<HazeState?> { null }

// --- Hintergrund ----------------------------------------------------------------------------

/**
 * Der Hintergrund hinter allem: ein sehr sanfter Verlauf und darauf drei große, weiche Lichtscheine.
 * Sie stehen still (nichts lenkt ab) und geben dem Glas davor etwas, das durchscheinen kann.
 */
@Composable
fun AmbientBackdrop(modifier: Modifier = Modifier) {
    val c = Glass.colors
    Box(
        modifier.fillMaxSize().drawBehind {
            drawRect(Brush.verticalGradient(listOf(c.backdropTop, c.backdropBottom)))
            // Kühl oben links hinter dem Titel, warm rechts beim Daumen, noch einmal schwach kühl unten links.
            glow(c.glowCool, Offset(size.width * 0.05f, size.height * 0.03f), size.width * 1.10f)
            glow(c.glowWarm, Offset(size.width * 1.05f, size.height * 0.66f), size.width * 0.95f)
            glow(c.glowCool.copy(alpha = c.glowCool.alpha * 0.6f), Offset(0f, size.height * 1.03f), size.width * 0.90f)
        },
    )
}

/** Ein Lichtschein: in der Mitte am stärksten, zum Rand hin unmerklich auslaufend. */
private fun DrawScope.glow(color: Color, center: Offset, radius: Float) {
    val brush = Brush.radialGradient(
        0f to color, 0.5f to color.copy(alpha = color.alpha * 0.45f), 1f to color.copy(alpha = 0f),
        center = center, radius = radius,
    )
    drawCircle(brush, radius, center)
}

// --- Glas -----------------------------------------------------------------------------------

/**
 * Eine Scheibe aus Milchglas, in der Inhalt untereinander steht.
 *
 * @param accent goldene statt weißer Kante (für die Antwortkarte)
 * @param floating die Scheibe schwebt über rollendem Inhalt: der wird dahinter weichgezeichnet,
 *   und der Schatten fällt kräftiger aus
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = Glass.CardShape,
    accent: Boolean = false,
    floating: Boolean = false,
    padding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Glass.colors
    Column(
        modifier
            .glass(
                shape = shape,
                fill = Brush.verticalGradient(listOf(c.glassTop, c.glassBottom)),
                rim = if (accent) Brush.verticalGradient(listOf(c.accentRimTop, c.accentRimBottom))
                else Brush.verticalGradient(listOf(c.rimTop, c.rimBottom)),
                floating = floating,
            )
            .padding(padding),
        content = content,
    )
}

/** Schatten, Füllung und Kante einer Glasscheibe; siehe die Erklärung oben in dieser Datei. */
@Composable
private fun Modifier.glass(shape: Shape, fill: Brush, rim: Brush, floating: Boolean, shadow: Color = Glass.colors.shadow): Modifier {
    // Genau ein Bildpunkt des Geräts: so fein wie eine Lichtkante auf echtem Glas.
    val onePixel = with(LocalDensity.current) { 1.toDp() }
    return this
        .softShadow(shape, shadow, blur = if (floating) 26.dp else 16.dp, offsetY = if (floating) 10.dp else 6.dp)
        .clip(shape)
        .frost(floating)
        .background(fill)
        .border(onePixel, rim, shape)
}

/**
 * Was hinter schwebendem Glas liegt, wird weichgezeichnet (auf dem Handy, über die Bibliothek Haze).
 * Ohne Unschärfe ist hier nichts zu tun: dann endet rollender Inhalt schon vor dem Glas (siehe
 * MainScreen), es liegt also nie Schrift hinter der Scheibe.
 */
@Composable
private fun Modifier.frost(floating: Boolean): Modifier {
    if (!floating) return this
    val c = Glass.colors
    val haze = LocalHaze.current ?: return this
    return hazeEffect(
        haze,
        HazeStyle(
            backgroundColor = c.backdropBottom,
            // Haze soll nur weichzeichnen. Getönt wird danach einheitlich mit unserer eigenen Füllung.
            tints = listOf(HazeTint(Color.Transparent)),
            blurRadius = 24.dp,
            noiseFactor = 0.03f,
            // Kann ein Gerät wider Erwarten nicht weichzeichnen, deckt Haze den Inhalt stattdessen ab.
            fallbackTint = HazeTint(c.scrim),
        ),
    )
}

/**
 * Weicher Schatten rund um eine Form. Gezeichnet wird er nur außerhalb der Form: unter durchscheinendem
 * Glas sähe man ihn sonst als grauen Fleck.
 */
private fun Modifier.softShadow(shape: Shape, color: Color, blur: Dp, offsetY: Dp): Modifier = drawWithCache {
    // "drawWithCache": Umriss und Pinsel werden nur neu gebaut, wenn sich die Größe ändert.
    val outline = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
    val paint = Paint().apply { this.color = color }
    paint.asFrameworkPaint().maskFilter = BlurMaskFilter(blur.toPx(), BlurMaskFilter.Blur.NORMAL)
    onDrawBehind {
        clipPath(outline, ClipOp.Difference) {
            translate(top = offsetY.toPx()) { drawIntoCanvas { it.drawPath(outline, paint) } }
        }
    }
}

/**
 * Lässt die Ränder eines rollenden Bereichs weich ausblenden, statt Zeilen hart abzuschneiden.
 *
 * @param top Höhe des Übergangs am oberen Rand; 0 schaltet ihn ab (wenn oben nichts verborgen ist)
 * @param bottomCover Höhe dessen, was unten über dem Bereich schwebt (0 = nichts)
 * @param bottomFade über diese Strecke, vom oberen Rand des Schwebenden an, verliert sich der Inhalt.
 *   Darunter ist nichts mehr von ihm zu sehen: so stehen zwischen und unter schwebenden Scheiben
 *   keine Zeilenreste.
 *
 * Alles als Funktion übergeben, damit eine Änderung nur neu zeichnet und die Seite nicht neu aufbaut.
 */
fun Modifier.fadeEdges(top: () -> Dp, bottomCover: () -> Dp = { 0.dp }, bottomFade: () -> Dp = { 0.dp }): Modifier = this
    // "Offscreen": der Bereich wird erst für sich gezeichnet, damit das Ausblenden nur ihn trifft
    // und nicht auch den Hintergrund dahinter.
    .graphicsLayer {
        compositingStrategy = if (top() > 0.dp || bottomCover() > 0.dp) CompositingStrategy.Offscreen else CompositingStrategy.Auto
    }
    .drawWithContent {
        drawContent()
        // "DstIn" behält vom Inhalt so viel, wie der Verlauf an der Stelle deckt (Schwarz = alles, durchsichtig = nichts).
        val topPx = top().toPx()
        if (topPx > 0f) {
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black), endY = topPx),
                size = Size(size.width, topPx),
                blendMode = BlendMode.DstIn,
            )
        }
        val coverPx = bottomCover().toPx()
        if (coverPx > 0f) {
            val from = size.height - coverPx // hier beginnt der schwebende Bereich
            drawRect(
                Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = from, endY = from + bottomFade().toPx().coerceAtLeast(1f)),
                topLeft = Offset(0f, from),
                size = Size(size.width, coverPx),
                blendMode = BlendMode.DstIn,
            )
        }
    }

// --- Schrift und Linien ---------------------------------------------------------------------

/** Kleines Etikett in gesperrten Großbuchstaben, z. B. "MITSCHRIFT". */
@Composable
fun SmallLabel(text: String, modifier: Modifier = Modifier, color: Color = Glass.colors.label) {
    Text(text.uppercase(), modifier, color = color, style = MaterialTheme.typography.labelSmall)
}

/** Feine Trennlinie innerhalb einer Scheibe. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Glass.colors.hairline))
}

/** Kopf einer Seite: großer Titel links, rechts eine kleine Glas-Kapsel als Schaltfläche. */
@Composable
fun Header(title: String, actionLabel: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        val style = MaterialTheme.typography.headlineMedium
        BasicText(
            title,
            Modifier.weight(1f).semantics { heading() },
            style = style.copy(color = Glass.colors.ink),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            // Passt der Titel nicht neben die Schaltfläche (schmales Handy, große Schrift), wird er
            // schrittweise kleiner, statt als "Claude V…" abgeschnitten zu werden.
            autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = style.fontSize, stepSize = 1.sp),
        )
        Spacer(Modifier.width(12.dp))
        GlassButton(actionLabel, onAction, compact = true)
    }
}

/** Überschrift eines Abschnitts: Serifenschrift, darunter ein kurzer goldener Strich. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(start = 4.dp)) {
        Text(
            text, Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge, color = Glass.colors.ink,
        )
        Spacer(Modifier.height(6.dp))
        Box(Modifier.width(28.dp).height(2.dp).clip(Glass.Capsule).background(Glass.colors.accent))
    }
}

/**
 * Der Punkt neben dem Status: ein leerer Ring, solange nichts läuft; golden, solange zugehört wird.
 * Dann "atmet" ein blasser Hof langsam um ihn herum, als ruhiges Zeichen, dass die App lebt.
 */
@Composable
fun StatusDot(active: Boolean, modifier: Modifier = Modifier) {
    val c = Glass.colors
    if (!active) {
        Box(modifier.size(10.dp).drawBehind { drawCircle(c.inkSoft, size.minDimension / 2 - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx())) })
        return
    }
    // Läuft endlos zwischen 1 und 0 hin und her, gut zwei Sekunden je Richtung.
    val breath by rememberInfiniteTransition(label = "atmen").animateFloat(
        initialValue = 1f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "hof",
    )
    // Der Wert wird erst beim Zeichnen gelesen: so wird nur neu gezeichnet, nicht neu aufgebaut.
    Box(
        modifier.size(10.dp).drawBehind {
            val dot = size.minDimension / 2
            drawCircle(c.accent.copy(alpha = 0.10f + 0.14f * breath), radius = dot * (1.6f + 0.6f * breath))
            drawCircle(c.accent, radius = dot)
        },
    )
}

// --- Schaltflächen --------------------------------------------------------------------------

/**
 * Schaltfläche als Glas-Kapsel. Mit [compact] klein und so breit wie ihr Text (für den Seitenkopf),
 * sonst so breit wie der Platz. Die Tippfläche ist in beiden Fällen mindestens 48 dp hoch.
 */
@Composable
fun GlassButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val c = Glass.colors
    Box(
        modifier
            // Hält um die kleine Kapsel herum Platz frei, sodass die Tippfläche 48 dp erreicht.
            .minimumInteractiveComponentSize()
            .then(if (compact) Modifier else Modifier.fillMaxWidth())
            .glass(
                shape = Glass.Capsule,
                fill = Brush.verticalGradient(listOf(c.thumbTop, c.thumbBottom)),
                rim = Brush.verticalGradient(listOf(c.thumbRimTop, c.thumbRimBottom)),
                floating = false,
                shadow = c.shadow.copy(alpha = c.shadow.alpha * 0.6f),
            )
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = if (compact) 40.dp else 48.dp)
            .padding(horizontal = if (compact) 14.dp else 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = c.ink, maxLines = 1)
    }
}

/**
 * Der große Knopf zum Starten und Stoppen: eine schwebende Glas-Kapsel mit goldener Tönung.
 * Mit [active] (es wird zugehört) leuchtet sie in vollem Gold: das sieht man, ohne zu lesen.
 */
@Composable
fun PrimaryButton(text: String, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Glass.colors
    Box(
        modifier
            .fillMaxWidth()
            .height(60.dp)
            .glass(
                shape = Glass.Capsule,
                fill = Brush.verticalGradient(if (active) listOf(c.activeTop, c.activeBottom) else listOf(c.primaryTop, c.primaryBottom)),
                rim = Brush.verticalGradient(if (active) listOf(c.activeRimTop, c.activeRimBottom) else listOf(c.primaryRimTop, c.primaryRimBottom)),
                floating = true,
            )
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge, color = if (active) c.onActive else c.onPrimary)
    }
}

// --- Auswahlleiste --------------------------------------------------------------------------

/**
 * Auswahlleiste: mehrere Möglichkeiten nebeneinander, genau eine ist gewählt.
 *
 * Eine Kapsel als Schiene; auf dem gewählten Feld liegt ein hellerer "Daumen" aus Glas, und dessen
 * Schrift ist kräftiger gefärbt als die der anderen. So erkennt man die Wahl auf einen Blick.
 *
 * Selbst gebaut statt der fertigen Material-Chips, weil die Leiste die volle Breite füllen soll.
 * Die Breite der Felder richtet sich danach, was passt (siehe [segmentWidths]).
 * Jedes Feld ist mindestens 48 dp hoch, die empfohlene Mindestgröße für etwas, das man antippt.
 */
@Composable
fun <T> SegmentedChoice(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = Glass.colors
    val style = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val onePixel = with(density) { 1.toDp() }
    val inset = 4.dp // Abstand zwischen Schiene und Daumen
    val sidePadding = 4.dp
    val extraPx = with(density) { 2 * (inset + sidePadding).roundToPx() } + 1

    BoxWithConstraints(modifier.fillMaxWidth()) {
        fun width(text: String) = measurer.measure(text, style, maxLines = 1).size.width + extraPx
        // Je Beschriftung: Breite in einer Zeile, und Breite ihres längsten Worts (schmaler geht es nicht).
        val words = options.map { (_, label) -> label.split(' ').maxOf(::width) }
        // Passen nicht einmal die längsten Wörter nebeneinander (große Schrift auf schmalem Handy),
        // darf zusätzlich hinter einem Bindestrich umbrochen werden: "AirPods-" / "Mikro".
        val hyphenBreaks = words.sum() >= constraints.maxWidth
        val shown = options.map { (_, label) -> if (hyphenBreaks) breakableAtHyphens(label) else label }
        val widths = segmentWidths(
            total = constraints.maxWidth,
            wanted = options.map { (_, label) -> width(label) },
            atLeast = if (hyphenBreaks) shown.map { it.split(' ', BREAK_ALLOWED).maxOf(::width) } else words,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min) // alle Felder so hoch wie das höchste
                // Gesperrt bleibt die Wahl lesbar, tritt aber sichtbar zurück.
                .alpha(if (enabled) 1f else 0.55f)
                .clip(Glass.Capsule)
                .background(c.track)
                .border(onePixel, Brush.verticalGradient(listOf(c.hairline.copy(alpha = 0f), c.hairline)), Glass.Capsule)
                .selectableGroup(),
        ) {
            options.forEachIndexed { index, (value, label) ->
                val isSelected = value == selected
                val thumb = if (!isSelected) Modifier else Modifier
                    .softShadow(Glass.Capsule, c.shadow.copy(alpha = c.shadow.alpha * 0.7f), blur = 6.dp, offsetY = 2.dp)
                    .clip(Glass.Capsule)
                    .background(Brush.verticalGradient(listOf(c.thumbTop, c.thumbBottom)))
                    .border(onePixel, Brush.verticalGradient(listOf(c.thumbRimTop, c.thumbRimBottom)), Glass.Capsule)
                Box(
                    Modifier
                        .weight(widths[index])
                        .fillMaxHeight()
                        .heightIn(min = 48.dp)
                        .clip(Glass.Capsule)
                        // "selectable" + Rolle: Vorlese-Dienste sagen "ausgewählt, 1 von 3" an.
                        .selectable(selected = isSelected, enabled = enabled, role = Role.RadioButton) { onSelect(value) }
                        .padding(inset)
                        .then(thumb)
                        .padding(horizontal = sidePadding, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(shown[index], color = if (isSelected) c.ink else c.inkSoft, textAlign = TextAlign.Center, style = style)
                }
            }
        }
    }
}

/**
 * Ein unsichtbares Zeichen ohne Breite ("zero width space"), an dem umbrochen werden darf.
 * (Als Code geschrieben, weil man es im Text sonst nicht sieht.)
 */
internal const val BREAK_ALLOWED = '\u200B'

/**
 * Android bricht von sich aus nicht hinter einem Bindestrich um; ein zu langes Wort wird dann mitten
 * in einer Silbe zerrissen ("AirPods-Mikr" / "o"). Mit dem unsichtbaren Zeichen dahinter bricht es
 * am Bindestrich. Nur für den Notfall gedacht: der Text enthält danach ein Zeichen mehr.
 */
internal fun breakableAtHyphens(label: String): String = label.replace("-", "-$BREAK_ALLOWED")

/**
 * Verteilt die Breite [total] einer Auswahlleiste auf ihre Felder. Das Ergebnis sind Anteile
 * (nur ihr Verhältnis zählt).
 *
 *  - Passen alle Beschriftungen in gleich breite Felder, sind die Felder gleich breit (ruhiges Bild).
 *  - Sonst bekommt jedes Feld Platz nach seiner Textlänge [wanted].
 *  - Reicht auch das nicht (schmales Handy), muss etwas in zwei Zeilen umbrechen. Dann behält jedes
 *    Feld mindestens die Breite seines längsten Worts [atLeast], und nur der Rest wird verteilt:
 *    so bricht "Bereitschaft (Hey Jarvis)" am Leerzeichen um und nicht "Dauer-Zuhören" mitten im Wort.
 *  - Passen selbst die längsten Wörter nicht, wird wieder nach Textlänge geteilt. Dann zerreißt ein
 *    Wort, aber die Leiste wächst in die Höhe und es geht nichts verloren.
 */
internal fun segmentWidths(total: Int, wanted: List<Int>, atLeast: List<Int>): List<Float> = when {
    wanted.max() <= total / wanted.size -> wanted.map { 1f }
    wanted.sum() <= total -> wanted.map { it.toFloat() }
    // Nicht einmal die längsten Wörter passen (sehr große Schrift): der Platz wird nach Textlänge
    // geteilt, und die Anteile ergeben zusammen genau die Breite. Das ist wichtig: die Zeile fragt
    // ihre Felder vorab, wie hoch sie bei ihrer Breite werden, und rechnet dabei mit ganzen Zahlen.
    // Wären die Anteile in der Summe größer als der Platz, hielte sie jedes Feld für breiter, als es
    // ist, und die zweite Textzeile würde abgeschnitten.
    atLeast.sum() >= total -> wanted.map { it * total.toFloat() / wanted.sum() }
    else -> {
        val spare = (total - atLeast.sum()).toFloat()
        val stretch = wanted.indices.map { (wanted[it] - atLeast[it]).coerceAtLeast(0) }
        wanted.indices.map { atLeast[it] + spare * stretch[it] / stretch.sum() }
    }
}

// --- Eingabefeld ----------------------------------------------------------------------------

/**
 * Eingabefeld im Glas-Stil: eine leicht vertiefte Fläche mit feinem Rahmen, die Beschriftung steht
 * im Feld und rückt beim Schreiben nach oben. Hat das Feld den Fokus, wird der Rahmen golden.
 * Die Erklärzeile [hint] steht darunter. Mit [lines] > 1 mehrzeilig.
 */
@Composable
fun GlassTextField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    lines: Int = 1,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Glass.colors
    // Meldet, ob gerade in dieses Feld geschrieben wird.
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(modifier.fillMaxWidth()) {
        TextField(
            value = value, onValueChange = onChange,
            modifier = Modifier
                .fillMaxWidth()
                .border(if (focused) 1.5.dp else 1.dp, if (focused) c.label else c.wellBorder, Glass.FieldShape),
            label = { Text(label) },
            singleLine = lines == 1,
            minLines = lines,
            maxLines = if (lines == 1) 1 else 10,
            keyboardOptions = keyboard,
            visualTransformation = visualTransformation,
            trailingIcon = trailing,
            interactionSource = interaction,
            shape = Glass.FieldShape,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = c.well, unfocusedContainerColor = c.well,
                // Die Material-Unterstreichung entfällt; den Rahmen zeichnen wir oben selbst.
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = c.ink, unfocusedTextColor = c.ink,
                focusedLabelColor = c.label, unfocusedLabelColor = c.inkSoft,
                cursorColor = c.label,
                selectionColors = TextSelectionColors(handleColor = c.accent, backgroundColor = c.accent.copy(alpha = 0.35f)),
            ),
        )
        hint?.let {
            Text(
                it, Modifier.padding(start = 16.dp, top = 6.dp, end = 16.dp),
                style = MaterialTheme.typography.bodySmall, color = c.inkSoft,
            )
        }
    }
}
