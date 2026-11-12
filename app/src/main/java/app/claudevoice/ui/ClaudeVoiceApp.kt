package app.claudevoice.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.claudevoice.AppState
import app.claudevoice.ListenService
import app.claudevoice.MemoryStore
import app.claudevoice.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Die dünne Schicht zwischen den gezeichneten Seiten und dem Rest der App.
 *
 * [MainScreen] und [SettingsScreen] zeichnen nur. Hier wird entschieden, welche Seite zu sehen ist,
 * und hier (nur hier) wird mit [Prefs], [MemoryStore], [ListenService] und [AppState] gesprochen.
 * Diese Trennung macht die Seiten testbar und hält die Android-Abhängigkeiten an einer Stelle.
 */
@Composable
fun ClaudeVoiceApp() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val ui by AppState.ui.collectAsStateWithLifecycle()

    // "rememberSaveable" übersteht auch das Drehen des Handys. Ohne Schlüssel geht es direkt in die Einstellungen.
    var showSettings by rememberSaveable { mutableStateOf(!prefs.hasKeys) }
    var hasKeys by remember { mutableStateOf(prefs.hasKeys) }
    var choices by remember { mutableStateOf(ListenChoices(prefs.listenMode, prefs.useBluetoothMic, prefs.reach)) }
    var hint by remember { mutableStateOf<String?>(null) }

    // Android fragt Berechtigungen zur Laufzeit ab; erst nach "Erlauben" darf der Service starten.
    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true ||
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            hint = null
            ListenService.start(context)
        } else {
            hint = "Ohne Mikrofon-Berechtigung kann die App nicht zuhören."
        }
    }

    fun start() {
        if (!prefs.hasKeys) { showSettings = true; return }
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissions.launch(wanted.toTypedArray())
    }

    if (showSettings) {
        val close = { hasKeys = prefs.hasKeys; showSettings = false }
        // Die Zurück-Geste soll die Einstellungen schließen, nicht die App.
        BackHandler(onBack = close)
        SettingsRoute(prefs, running = ui.running, onClose = close)
    } else {
        MainScreen(
            ui = ui,
            // Die Uhr tickt nur, solange zugehört wird: nur dann zeigt die Seite eine Laufzeit.
            nowMs = rememberNow(ticking = ui.running),
            choices = choices,
            hasKeys = hasKeys,
            hint = hint,
            actions = MainActions(
                // Jede Wahl sofort ablegen: der Service liest sie beim nächsten Start aus den Prefs.
                onModeChange = { choices = choices.copy(mode = it); prefs.listenMode = it },
                onMicChange = { choices = choices.copy(bluetoothMic = it); prefs.useBluetoothMic = it },
                onReachChange = { choices = choices.copy(reach = it); prefs.reach = it },
                onStartStop = { if (ui.running) ListenService.stop(context) else start() },
                onStopSpeaking = { ListenService.stopSpeaking(context) },
                onOpenSettings = { showSettings = true },
            ),
        )
    }
}

/** Aktuelle Uhrzeit in Millisekunden; aktualisiert sich jede Sekunde, solange [ticking] gilt. */
@Composable
private fun rememberNow(ticking: Boolean): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ticking) {
        now = System.currentTimeMillis()
        while (ticking) { delay(1000); now = System.currentTimeMillis() }
    }
    return now
}

/** Die Einstellungen samt Anbindung: lädt die Werte, legt Änderungen sofort ab, öffnet den Dateiauswähler. */
@Composable
private fun SettingsRoute(prefs: Prefs, running: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val memory = remember { MemoryStore(context) }
    val scope = rememberCoroutineScope()

    var form by remember { mutableStateOf(prefs.toForm()) }
    var linked by remember { mutableStateOf(emptyList<LinkedFile>()) }
    var notes by remember { mutableStateOf(emptyList<String>()) }
    var linkError by remember { mutableStateOf<String?>(null) }

    // Dateien und Notizen liegen auf Platte oder (bei Drive) hinter dem Netz. Solche Zugriffe gehören
    // nicht auf den Haupt-Thread, sonst ruckelt die Oberfläche; "Dispatchers.IO" ist der Ort dafür.
    suspend fun reload() {
        val (files, saved) = withContext(Dispatchers.IO) {
            memory.linked().map { LinkedFile(it.uri.toString(), it.name) } to memory.notes()
        }
        linked = files
        notes = saved
    }
    LaunchedEffect(Unit) { reload() }

    // Der Android-Dateiauswähler gibt Lesezugriff auf genau die gewählte Datei, auch in Google Drive.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            linkError = withContext(Dispatchers.IO) { memory.link(uri) }
            reload()
        }
    }

    SettingsScreen(
        form = form,
        linkedFiles = linked,
        linkError = linkError,
        notes = notes,
        running = running,
        actions = SettingsActions(
            onFormChange = { changed -> prefs.save(old = form, new = changed); form = changed },
            // Textdateien aller Art. "octet-stream" steht dabei, weil manche Speicher-Apps
            // Markdown-Dateien (.md) nicht als Text melden und sie sonst ausgegraut wären.
            onAddFile = { linkError = null; picker.launch(arrayOf("text/*", "application/octet-stream")) },
            onRemoveFile = { file ->
                scope.launch {
                    withContext(Dispatchers.IO) { memory.unlink(Uri.parse(file.id)) }
                    reload()
                }
            },
            onDeleteNote = { index ->
                scope.launch {
                    withContext(Dispatchers.IO) { memory.removeNote(index) }
                    reload()
                }
            },
            onClose = onClose,
        ),
    )
}

private fun Prefs.toForm() = SettingsForm(
    anthropicKey = anthropicKey,
    elevenKey = elevenKey,
    sonioxKey = sonioxKey,
    voiceId = voiceId,
    name = assistantName,
    aliases = aliases.joinToString(", "),
    address = address,
    character = character,
    about = about,
    pauseMusic = pauseMusic,
)

/** Schreibt nur das Feld, das sich geändert hat (beim Tippen ist das immer genau eines). */
private fun Prefs.save(old: SettingsForm, new: SettingsForm) {
    if (new.anthropicKey != old.anthropicKey) anthropicKey = new.anthropicKey
    if (new.elevenKey != old.elevenKey) elevenKey = new.elevenKey
    if (new.sonioxKey != old.sonioxKey) sonioxKey = new.sonioxKey
    if (new.voiceId != old.voiceId) voiceId = new.voiceId
    if (new.name != old.name) assistantName = new.name
    if (new.aliases != old.aliases) aliases = new.aliases.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (new.address != old.address) address = new.address
    if (new.character != old.character) character = new.character
    if (new.about != old.about) about = new.about
    if (new.pauseMusic != old.pauseMusic) pauseMusic = new.pauseMusic
}
