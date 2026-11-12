package app.claudevoice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

private val Navy = Color(0xFF1F3864)
private val Gold = Color(0xFFC9A84C)
private val Cream = Color(0xFFF5F0E8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Navy, onPrimary = Cream, secondary = Gold,
                    background = Cream, surface = Color.White, onSurface = Navy, onBackground = Navy,
                )
            ) { MainScreen() }
        }
    }
}

@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val ui by AppState.ui.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(!prefs.hasKeys) }
    var bluetoothMic by remember { mutableStateOf(prefs.useBluetoothMic) }
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

    Column(
        Modifier.fillMaxSize().background(Cream).systemBarsPadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Claude Voice", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
                fontSize = 28.sp, color = Navy, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { showSettings = true }) { Text("Einstellungen") }
        }

        Text(statusLine(ui.status, ui.running, ui.startedAtMs), color = Navy.copy(alpha = 0.75f))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = bluetoothMic, enabled = !ui.running,
                onClick = { bluetoothMic = true; prefs.useBluetoothMic = true },
                label = { Text("AirPods-Mikro") },
            )
            FilterChip(
                selected = !bluetoothMic, enabled = !ui.running,
                onClick = { bluetoothMic = false; prefs.useBluetoothMic = false },
                label = { Text("Handy-Mikro") },
            )
        }

        Button(
            onClick = { if (ui.running) ListenService.stop(context) else start() },
            modifier = Modifier.fillMaxWidth().height(64.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (ui.running) Gold else Navy,
                contentColor = if (ui.running) Navy else Cream),
        ) { Text(if (ui.running) "Stopp" else "Zuhören starten", fontSize = 20.sp) }

        (ui.error ?: hint)?.let { Text(it, color = Color(0xFF9B2C2C)) }

        if (ui.question != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Frage", color = Gold, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(ui.question ?: "", fontFamily = FontFamily.Serif, fontSize = 17.sp)
                    Text("Antwort", color = Gold, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(ui.answer.ifEmpty { "…" }, fontSize = 17.sp)
                    if (ui.speaking) {
                        OutlinedButton(onClick = { ListenService.stopSpeaking(context) }) { Text("Antwort stoppen") }
                    }
                }
            }
        }

        Text("Mitschrift (wird nicht gespeichert)", color = Gold, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        val lines = ui.transcript + listOfNotNull(ui.partial.takeIf { it.isNotBlank() }?.let { "$it …" })
        val listState = rememberLazyListState()
        LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            items(lines) { Text(it, Modifier.padding(vertical = 3.dp), color = Navy.copy(alpha = 0.85f)) }
        }
        Spacer(Modifier.height(4.dp))
    }

    if (showSettings) SettingsDialog(prefs) { showSettings = false }
}

/** Zeigt den Status und, solange zugehört wird, die Laufzeit (Dauerbetrieb kostet API-Minuten). */
@Composable
private fun statusLine(status: String, running: Boolean, startedAtMs: Long): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        while (running) { now = System.currentTimeMillis(); delay(1000) }
    }
    if (!running) return status
    val s = ((now - startedAtMs) / 1000).coerceAtLeast(0)
    return "$status · %d:%02d".format(s / 60, s % 60)
}

@Composable
private fun SettingsDialog(prefs: Prefs, onClose: () -> Unit) {
    var anthropic by remember { mutableStateOf(prefs.anthropicKey) }
    var eleven by remember { mutableStateOf(prefs.elevenKey) }
    var voice by remember { mutableStateOf(prefs.voiceId) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Einstellungen", fontFamily = FontFamily.Serif) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Die Schlüssel bleiben verschlüsselt auf diesem Gerät.", fontSize = 13.sp)
                OutlinedTextField(
                    anthropic, { anthropic = it }, label = { Text("Anthropic API-Key") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    eleven, { eleven = it }, label = { Text("ElevenLabs API-Key") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(voice, { voice = it }, label = { Text("ElevenLabs Voice-ID") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                prefs.anthropicKey = anthropic; prefs.elevenKey = eleven; prefs.voiceId = voice
                onClose()
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Abbrechen") } },
    )
}
