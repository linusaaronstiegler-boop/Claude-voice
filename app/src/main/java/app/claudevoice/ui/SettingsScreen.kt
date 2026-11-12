package app.claudevoice.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/** Alle Textfelder und Schalter der Einstellungen als ein Wert. Rufnamen als ein Text, mit Komma getrennt. */
data class SettingsForm(
    val anthropicKey: String = "",
    val elevenKey: String = "",
    val sonioxKey: String = "",
    val voiceId: String = "",
    val name: String = "",
    val aliases: String = "",
    val address: String = "",
    val character: String = "",
    val about: String = "",
    val pauseMusic: Boolean = true,
    val appearance: Appearance = Appearance.System,
)

/** Eine verknüpfte Gedächtnis-Datei: [id] ist der gespeicherte Verweis, [name] der angezeigte Dateiname. */
data class LinkedFile(val id: String, val name: String)

class SettingsActions(
    val onFormChange: (SettingsForm) -> Unit = {},
    val onAddFile: () -> Unit = {},
    val onRemoveFile: (LinkedFile) -> Unit = {},
    val onDeleteNote: (Int) -> Unit = {},
    val onClose: () -> Unit = {},
)

/**
 * Die Einstellungen als eigene, rollende Seite. Wie die Hauptseite zustandslos: Werte rein, Tipper raus.
 * Es gibt keinen Speichern-Knopf; jede Änderung wird vom Aufrufer sofort abgelegt (siehe ClaudeVoiceApp).
 * Das ist auf dem Handy üblich und verhindert, dass ein vergessenes "Speichern" Eingaben verschluckt.
 *
 * Jeder Abschnitt hat eine Überschrift und darunter eine oder mehrere Glasscheiben mit seinen Feldern.
 */
@Composable
fun SettingsScreen(
    form: SettingsForm,
    linkedFiles: List<LinkedFile>,
    linkError: String?,
    notes: List<String>,
    running: Boolean,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
) {
    val c = Glass.colors
    val change = actions.onFormChange
    // Ist nach oben gerollt, blendet der obere Rand des Inhalts weich unter dem Kopf aus.
    val fade by animateDpAsState(if (scrollState.canScrollBackward) 16.dp else 0.dp, label = "rand")
    Box(modifier.fillMaxSize()) {
        AmbientBackdrop()
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            // Der Kopf bleibt stehen, damit "Fertig" immer erreichbar ist.
            Header("Einstellungen", "Fertig", actions.onClose, Modifier.padding(horizontal = 20.dp))

            Column(
                Modifier.weight(1f).fillMaxWidth().fadeEdges(top = { fade }).testTag("settings_scroll").verticalScroll(scrollState)
                    .padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Alles auf dieser Seite wird nur auf diesem Gerät gespeichert, die Schlüssel verschlüsselt. " +
                        "Änderungen gelten sofort und brauchen kein Speichern.",
                    Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.bodyMedium, color = c.inkSoft,
                )
                if (running) {
                    Text(
                        "Gerade wird zugehört. Änderungen wirken ab dem nächsten Start.",
                        Modifier.padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.bodyMedium, color = c.alert,
                    )
                }

                // --- Schlüssel --------------------------------------------------------------
                SectionTitle("Schlüssel", Modifier.padding(top = 12.dp))
                Pane {
                    SecretField("Anthropic API-Key", form.anthropicKey) { change(form.copy(anthropicKey = it)) }
                    SecretField("ElevenLabs API-Key", form.elevenKey) { change(form.copy(elevenKey = it)) }
                    SecretField("Soniox API-Key (optional)", form.sonioxKey) { change(form.copy(sonioxKey = it)) }
                    GlassTextField(
                        "ElevenLabs Voice-ID", form.voiceId, { change(form.copy(voiceId = it)) },
                        hint = "Die Stimme der Antworten. Leer = Standardstimme.",
                        keyboard = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    )
                }

                // --- Assistent --------------------------------------------------------------
                SectionTitle("Assistent", Modifier.padding(top = 16.dp))
                Pane {
                    GlassTextField(
                        "Name", form.name, { change(form.copy(name = it)) },
                        hint = "Auf diesen Namen hört er, z. B. „Alfred, wie spät ist es?“",
                        keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    )
                    GlassTextField(
                        "Weitere Rufnamen / Weckwort", form.aliases, { change(form.copy(aliases = it)) },
                        hint = "Mit Komma getrennt. „Jarvis“ ist zugleich das Weckwort der Bereitschaft („Hey Jarvis“).",
                        keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    )
                    GlassTextField(
                        "Anrede", form.address, { change(form.copy(address = it)) },
                        hint = "So spricht er dich an, z. B. „Sir“.",
                        keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    )
                    GlassTextField(
                        "Charakter", form.character, { change(form.copy(character = it)) },
                        hint = "Wie er auftreten soll. Leer = der eingebaute Alfred-Charakter.",
                        lines = 4, keyboard = SENTENCES,
                    )
                }

                // --- Gedächtnis -------------------------------------------------------------
                SectionTitle("Gedächtnis", Modifier.padding(top = 16.dp))
                Pane {
                    GlassTextField(
                        "Über mich", form.about, { change(form.copy(about = it)) },
                        hint = "Was er über dich wissen soll: Beruf, Vorlieben, laufende Vorhaben.",
                        lines = 4, keyboard = SENTENCES,
                    )
                }
                Pane {
                    SubTitle("Verknüpfte Dateien")
                    Text(
                        "Zum Beispiel deine Notizdatei. Die App merkt sich nur den Verweis und liest die Datei bei " +
                            "jedem Start neu.",
                        style = MaterialTheme.typography.bodyMedium, color = c.inkSoft,
                    )
                    if (linkedFiles.isNotEmpty()) {
                        Column {
                            linkedFiles.forEachIndexed { index, file ->
                                ListRow(file.name, "Entfernen", "Datei ${file.name} entfernen", divider = index > 0) { actions.onRemoveFile(file) }
                            }
                        }
                    }
                    linkError?.let {
                        Text(it, Modifier.testTag("link_error"), style = MaterialTheme.typography.bodyMedium, color = c.alert)
                    }
                    GlassButton("Datei verknüpfen", actions.onAddFile)
                }
                Pane {
                    SubTitle("Notizen des Assistenten")
                    if (notes.isEmpty()) {
                        Text(
                            "Noch keine. Sag zum Beispiel: „${form.name.ifBlank { "Alfred" }}, merk dir, dass …“",
                            style = MaterialTheme.typography.bodyMedium, color = c.inkSoft,
                        )
                    }
                    // Löschen lässt sich nicht rückgängig machen, deshalb erst nachfragen.
                    var pendingDelete by remember { mutableStateOf<Int?>(null) }
                    if (notes.isNotEmpty()) {
                        Column {
                            notes.forEachIndexed { index, note ->
                                ListRow(note, "Löschen", "Notiz löschen: $note", divider = index > 0) { pendingDelete = index }
                            }
                        }
                    }
                    pendingDelete?.let { index ->
                        val note = notes.getOrNull(index)
                        if (note == null) pendingDelete = null
                        else ConfirmDialog(
                            title = "Notiz löschen?", text = note, confirmLabel = "Löschen",
                            onConfirm = { pendingDelete = null; actions.onDeleteNote(index) },
                            onDismiss = { pendingDelete = null },
                        )
                    }
                }

                // --- Verhalten --------------------------------------------------------------
                SectionTitle("Verhalten", Modifier.padding(top = 16.dp))
                Pane {
                    Text(
                        "Eigene Musik, während er antwortet",
                        Modifier.padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.bodyLarge, color = c.ink,
                    )
                    SegmentedChoice(
                        listOf(true to "Pausieren", false to "Leiser stellen"),
                        form.pauseMusic, { change(form.copy(pauseMusic = it)) },
                    )
                }

                // --- Erscheinungsbild -------------------------------------------------------
                SectionTitle("Erscheinungsbild", Modifier.padding(top = 16.dp))
                Pane {
                    SegmentedChoice(
                        listOf(Appearance.System to "System", Appearance.Light to "Hell", Appearance.Dark to "Dunkel"),
                        form.appearance, { change(form.copy(appearance = it)) },
                    )
                    Text(
                        "„System“ folgt der Einstellung des Handys.",
                        Modifier.padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = c.inkSoft,
                    )
                }
            }
        }
    }
}

private val SENTENCES = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)

/** Eine Glasscheibe der Einstellungen: ihr Inhalt steht mit gleichmäßigem Abstand untereinander. */
@Composable
private fun Pane(content: @Composable ColumnScope.() -> Unit) {
    GlassSurface(Modifier.fillMaxWidth(), padding = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun SubTitle(text: String) {
    Text(
        text, Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleMedium, color = Glass.colors.ink,
    )
}

/**
 * Rückfrage mit "Abbrechen" und einer Bestätigung. Ein Dialog liegt in einem eigenen Fenster über der
 * Seite; durchscheinendes Glas ginge dort nicht (die Seite dahinter bliebe scharf). Deshalb ist die
 * Fläche deckend und übernimmt vom Glas nur die runde Form und die feine Lichtkante.
 */
@Composable
private fun ConfirmDialog(title: String, text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = Glass.colors
    val shape = MaterialTheme.shapes.extraLarge
    val onePixel = with(LocalDensity.current) { 1.toDp() }
    AlertDialog(
        onDismissRequest = onDismiss,
        // Eigene Breite statt der des Systems: immer 24 dp Abstand zum Bildschirmrand.
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 24.dp).border(onePixel, Brush.verticalGradient(listOf(c.rimTop, c.rimBottom)), shape),
        shape = shape,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Eingabefeld für einen Schlüssel: verdeckt wie ein Passwort, auf Wunsch kurz sichtbar. */
@Composable
private fun SecretField(label: String, value: String, onChange: (String) -> Unit) {
    // Bewusst "remember" und nicht "rememberSaveable": nach einem Neuaufbau ist der Schlüssel wieder verdeckt.
    var visible by remember { mutableStateOf(false) }
    GlassTextField(
        label, value, onChange,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Next),
        trailing = {
            TextButton(onClick = { visible = !visible }, Modifier.padding(end = 4.dp)) {
                Text(if (visible) "Verbergen" else "Zeigen")
            }
        },
    )
}

/** Listenzeile mit Text links und einer Aktion rechts; mit [divider] trennt eine feine Linie sie von der Zeile darüber. */
@Composable
private fun ListRow(text: String, action: String, actionDescription: String, divider: Boolean, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (divider) Hairline()
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text, Modifier.weight(1f).padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodyLarge, color = Glass.colors.ink,
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = onAction,
                // Für Vorlese-Dienste: "Entfernen" allein sagt nicht, was entfernt wird.
                // Der Versatz lässt die Schrift bündig mit dem Rand der Felder darüber enden.
                modifier = Modifier.offset(x = 12.dp).semantics { contentDescription = actionDescription },
            ) { Text(action) }
        }
    }
}
