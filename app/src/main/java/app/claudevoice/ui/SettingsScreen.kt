package app.claudevoice.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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
    val change = actions.onFormChange
    Column(modifier.fillMaxSize().background(Palette.Cream).windowInsetsPadding(WindowInsets.safeDrawing)) {
        // Der Kopf bleibt stehen, damit "Fertig" immer erreichbar ist.
        Column(Modifier.padding(horizontal = 20.dp)) { Header("Einstellungen", "Fertig", actions.onClose) }

        Column(
            Modifier.weight(1f).fillMaxWidth().testTag("settings_scroll").verticalScroll(scrollState)
                .padding(horizontal = 20.dp).padding(top = 14.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Alles auf dieser Seite wird nur auf diesem Gerät gespeichert, die Schlüssel verschlüsselt. " +
                    "Änderungen gelten sofort und brauchen kein Speichern.",
                style = MaterialTheme.typography.bodyMedium, color = Palette.Navy,
            )
            if (running) {
                Text(
                    "Gerade wird zugehört. Änderungen wirken ab dem nächsten Start.",
                    style = MaterialTheme.typography.bodyMedium, color = Palette.Alert,
                )
            }

            // --- Schlüssel ------------------------------------------------------------------
            SectionTitle("Schlüssel", Modifier.padding(top = 8.dp))
            SecretField("Anthropic API-Key", form.anthropicKey) { change(form.copy(anthropicKey = it)) }
            SecretField("ElevenLabs API-Key", form.elevenKey) { change(form.copy(elevenKey = it)) }
            SecretField("Soniox API-Key (optional)", form.sonioxKey) { change(form.copy(sonioxKey = it)) }
            Field(
                "ElevenLabs Voice-ID", form.voiceId, { change(form.copy(voiceId = it)) },
                hint = "Die Stimme der Antworten. Leer = Standardstimme.",
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            )

            // --- Assistent ------------------------------------------------------------------
            SectionTitle("Assistent", Modifier.padding(top = 16.dp))
            Field(
                "Name", form.name, { change(form.copy(name = it)) },
                hint = "Auf diesen Namen hört er, z. B. „Alfred, wie spät ist es?“",
                keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            )
            Field(
                "Weitere Rufnamen / Weckwort", form.aliases, { change(form.copy(aliases = it)) },
                hint = "Mit Komma getrennt. „Jarvis“ ist zugleich das Weckwort der Bereitschaft („Hey Jarvis“).",
                keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            )
            Field(
                "Anrede", form.address, { change(form.copy(address = it)) },
                hint = "So spricht er dich an, z. B. „Sir“.",
                keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            )
            Field(
                "Charakter", form.character, { change(form.copy(character = it)) },
                hint = "Wie er auftreten soll. Leer = der eingebaute Alfred-Charakter.",
                lines = 4,
            )

            // --- Gedächtnis -----------------------------------------------------------------
            SectionTitle("Gedächtnis", Modifier.padding(top = 16.dp))
            Field(
                "Über mich", form.about, { change(form.copy(about = it)) },
                hint = "Was er über dich wissen soll: Beruf, Vorlieben, laufende Vorhaben.",
                lines = 4,
            )

            SubTitle("Verknüpfte Dateien")
            Text(
                "Zum Beispiel deine Notizdatei. Die App merkt sich nur den Verweis und liest die Datei bei " +
                    "jedem Start neu.",
                style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft,
            )
            linkedFiles.forEach { file ->
                ListRow(file.name, "Entfernen", "Datei ${file.name} entfernen") { actions.onRemoveFile(file) }
            }
            linkError?.let {
                Text(it, Modifier.testTag("link_error"), style = MaterialTheme.typography.bodyMedium, color = Palette.Alert)
            }
            OutlinedButton(
                onClick = actions.onAddFile,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = MaterialTheme.shapes.small,
            ) { Text("Datei verknüpfen") }

            SubTitle("Notizen des Assistenten")
            if (notes.isEmpty()) {
                Text(
                    "Noch keine. Sag zum Beispiel: „${form.name.ifBlank { "Alfred" }}, merk dir, dass …“",
                    style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft,
                )
            }
            // Löschen lässt sich nicht rückgängig machen, deshalb erst nachfragen.
            var pendingDelete by remember { mutableStateOf<Int?>(null) }
            notes.forEachIndexed { index, note ->
                ListRow(note, "Löschen", "Notiz löschen: $note") { pendingDelete = index }
            }
            pendingDelete?.let { index ->
                val note = notes.getOrNull(index)
                if (note == null) pendingDelete = null
                else AlertDialog(
                    onDismissRequest = { pendingDelete = null },
                    title = { Text("Notiz löschen?", style = MaterialTheme.typography.titleLarge) },
                    text = { Text(note) },
                    confirmButton = {
                        TextButton(onClick = { pendingDelete = null; actions.onDeleteNote(index) }) { Text("Löschen") }
                    },
                    dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Abbrechen") } },
                )
            }

            // --- Verhalten ------------------------------------------------------------------
            SectionTitle("Verhalten", Modifier.padding(top = 16.dp))
            Text(
                "Eigene Musik, während er antwortet",
                style = MaterialTheme.typography.bodyLarge, color = Palette.Navy,
            )
            SegmentedChoice(
                listOf(true to "Pausieren", false to "Leiser stellen"),
                form.pauseMusic, { change(form.copy(pauseMusic = it)) },
            )
        }
    }
}

@Composable
private fun SubTitle(text: String) {
    Text(
        text, Modifier.padding(top = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.titleMedium, color = Palette.Navy,
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Palette.Navy, unfocusedTextColor = Palette.Navy,
    focusedSupportingTextColor = Palette.InkSoft, unfocusedSupportingTextColor = Palette.InkSoft,
)

/** Gewöhnliches Eingabefeld mit Erklärzeile darunter; mit [lines] > 1 mehrzeilig. */
@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    lines: Int = 1,
    keyboard: KeyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        supportingText = { Text(hint) },
        singleLine = lines == 1,
        minLines = lines,
        maxLines = if (lines == 1) 1 else 10,
        keyboardOptions = keyboard,
        colors = fieldColors(),
        shape = MaterialTheme.shapes.small,
    )
}

/** Eingabefeld für einen Schlüssel: verdeckt wie ein Passwort, auf Wunsch kurz sichtbar. */
@Composable
private fun SecretField(label: String, value: String, onChange: (String) -> Unit) {
    // Bewusst "remember" und nicht "rememberSaveable": nach einem Neuaufbau ist der Schlüssel wieder verdeckt.
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Next),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }, Modifier.padding(end = 4.dp)) {
                Text(if (visible) "Verbergen" else "Zeigen")
            }
        },
        colors = fieldColors(),
        shape = MaterialTheme.shapes.small,
    )
}

/** Listenzeile mit Text links und einer Aktion rechts, darunter eine feine Linie. */
@Composable
private fun ListRow(text: String, action: String, actionDescription: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text, Modifier.weight(1f).padding(vertical = 6.dp),
                style = MaterialTheme.typography.bodyLarge, color = Palette.Navy,
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = onAction,
                // Für Vorlese-Dienste: "Entfernen" allein sagt nicht, was entfernt wird.
                // Der Versatz lässt die Schrift bündig mit der Linie darunter enden (wie im Seitenkopf).
                modifier = Modifier.offset(x = 12.dp).semantics { contentDescription = actionDescription },
            ) { Text(action) }
        }
        Hairline()
    }
}
