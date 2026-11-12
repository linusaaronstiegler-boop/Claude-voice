package app.claudevoice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// Bausteine, die Hauptseite und Einstellungen gemeinsam benutzen.

/** Kleines Etikett in gesperrten Großbuchstaben, z. B. "MITSCHRIFT". */
@Composable
fun SmallLabel(text: String, modifier: Modifier = Modifier, color: Color = Palette.GoldDeep) {
    Text(text.uppercase(), modifier, color = color, style = MaterialTheme.typography.labelSmall)
}

/** Feine goldene Linie als ruhiger Trenner. Reiner Schmuck, deshalb darf sie blass sein. */
@Composable
fun GoldRule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Palette.Gold))
}

/** Noch feinere, graue Linie für Listen und Rollkanten. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Palette.Hairline))
}

/** Kopf einer Seite: großer Titel links, eine Text-Schaltfläche rechts, goldene Linie darunter. */
@Composable
fun Header(title: String, actionLabel: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.headlineMedium, color = Palette.Navy,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        // Um die Polsterung nach rechts gerückt: so schließt die Schrift bündig mit der Linie darunter ab,
        // die Tippfläche bleibt trotzdem groß.
        TextButton(onClick = onAction, modifier = Modifier.offset(x = 12.dp)) { Text(actionLabel) }
    }
    GoldRule()
}

/** Überschrift eines Abschnitts in den Einstellungen: Serifenschrift mit goldener Linie darunter. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(
            text, Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge, color = Palette.Navy,
        )
        Spacer(Modifier.height(6.dp))
        GoldRule()
    }
}

/**
 * Auswahlleiste: mehrere Möglichkeiten nebeneinander, genau eine ist gewählt.
 *
 * Selbst gebaut statt der fertigen Material-Chips, weil die Leiste die volle Breite füllen soll.
 * Passen alle Beschriftungen in gleich breite Felder, sind die Felder gleich breit (ruhiges Bild).
 * Passt eine nicht (schmales Handy, lange Beschriftung), bekommt jedes Feld Platz nach seiner
 * Textlänge; reicht auch das nicht, bricht der Text in zwei Zeilen um und alle Felder wachsen mit.
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
    val shape = MaterialTheme.shapes.small
    val style = MaterialTheme.typography.labelLarge
    // Gesperrt (während zugehört wird) bleibt die Wahl lesbar, tritt aber sichtbar zurück.
    val border = if (enabled) Palette.Outline else Palette.Outline.copy(alpha = 0.55f)
    val measurer = rememberTextMeasurer()
    val sidePadding = 6.dp
    val sidePaddingPx = with(LocalDensity.current) { sidePadding.roundToPx() }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Wie breit wäre jede Beschriftung in einer Zeile, samt Rand links und rechts?
        val needed = options.map { (_, label) ->
            measurer.measure(label, style, maxLines = 1).size.width + 2 * sidePaddingPx + 1
        }
        val equalFits = needed.max() <= constraints.maxWidth / options.size
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min) // alle Felder so hoch wie das höchste
                .clip(shape)
                .border(1.dp, border, shape)
                .selectableGroup(),
        ) {
            options.forEachIndexed { index, (value, label) ->
                if (index > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(border))
                val isSelected = value == selected
                val fill = when {
                    !isSelected -> Color.Transparent
                    enabled -> Palette.Navy
                    else -> Palette.Navy.copy(alpha = 0.6f)
                }
                val ink = when {
                    isSelected -> Palette.Cream
                    enabled -> Palette.Navy
                    else -> Palette.Navy.copy(alpha = 0.6f)
                }
                Box(
                    Modifier
                        .weight(if (equalFits) 1f else needed[index].toFloat())
                        .fillMaxHeight()
                        .heightIn(min = 48.dp)
                        .background(fill)
                        // "selectable" + Rolle: Vorlese-Dienste sagen "ausgewählt, 1 von 3" an.
                        .selectable(selected = isSelected, enabled = enabled, role = Role.RadioButton) { onSelect(value) }
                        .padding(horizontal = sidePadding, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = ink, textAlign = TextAlign.Center, style = style)
                }
            }
        }
    }
}
