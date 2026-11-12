package app.claudevoice

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.time.LocalDate

/**
 * Das Gedächtnis des Assistenten. Drei Quellen, alle nur auf dem Gerät:
 *  1. "Über mich": freier Text aus den Einstellungen.
 *  2. Verknüpfte Dateien: z. B. die MEMORY.md aus dem eigenen Notizsystem. Die App merkt sich nur den
 *     Verweis und liest die Datei bei jedem Start neu, die Datei bleibt also die Quelle der Wahrheit.
 *  3. Notizen: was der Assistent sich auf Zuruf gemerkt hat ("Alfred, merk dir ...").
 *
 * Verknüpfte Dateien laufen über den Android-Dateiauswähler (Storage Access Framework). Der gibt der App
 * Lesezugriff auf genau die gewählte Datei, auch in Google Drive, ohne eigenes Drive-Login.
 */
class MemoryStore(
    private val context: Context,
    // Wer schon Einstellungen geöffnet hat, reicht sie herein; sonst teilt sich Prefs ohnehin eine Instanz je Prozess.
    private val prefs: Prefs = Prefs(context),
) {
    private val notesFile = File(context.filesDir, "notes.txt")
    private val cacheDir = File(context.filesDir, "linked").apply { mkdirs() }

    data class Linked(val uri: Uri, val name: String)
    data class Loaded(val text: String, val problems: List<String>)

    // --- Notizen -----------------------------------------------------------------------------

    fun notes(): List<String> =
        if (notesFile.exists()) notesFile.readLines().filter { it.isNotBlank() } else emptyList()

    @Synchronized
    fun addNote(text: String) {
        val line = "${LocalDate.now()}: ${text.replace('\n', ' ').trim()}"
        notesFile.appendText(line + "\n")
    }

    @Synchronized
    fun removeNote(index: Int) {
        val all = notes().toMutableList()
        if (index in all.indices) {
            all.removeAt(index)
            notesFile.writeText(all.joinToString("") { it + "\n" })
        }
    }

    // --- Verknüpfte Dateien ------------------------------------------------------------------

    fun linked(): List<Linked> = prefs.linkedUris.map { Uri.parse(it) }.map { Linked(it, displayName(it)) }

    /** @return Fehlermeldung oder null bei Erfolg. */
    fun link(uri: Uri): String? {
        val text = try {
            read(uri)
        } catch (e: Exception) {
            return "Datei konnte nicht gelesen werden: ${e.message}"
        }
        if (text.length > MAX_CHARS_PER_FILE) {
            // Lieber ablehnen als still kürzen: ein halbes Gedächtnis antwortet zuversichtlich falsch.
            return "Datei zu groß (${text.length} Zeichen, Grenze $MAX_CHARS_PER_FILE). Bitte eine kürzere Fassung wählen."
        }
        // Dauerhafte Leseerlaubnis: ohne sie wäre der Verweis nach dem nächsten Neustart wertlos.
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        prefs.linkedUris = (prefs.linkedUris + uri.toString()).distinct()
        cacheFile(uri).writeText(text)
        return null
    }

    fun unlink(uri: Uri) {
        prefs.linkedUris = prefs.linkedUris - uri.toString()
        cacheFile(uri).delete()
        runCatching {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Liest alle verknüpften Dateien frisch. Ist eine gerade nicht erreichbar (offline, Drive nicht
     * synchronisiert), wird die zuletzt gelesene Fassung genommen und das gemeldet.
     */
    fun loadLinked(): Loaded {
        val problems = mutableListOf<String>()
        val parts = mutableListOf<String>()
        for (item in linked()) {
            val cache = cacheFile(item.uri)
            val text = try {
                read(item.uri).also { if (it.length <= MAX_CHARS_PER_FILE) cache.writeText(it) }
            } catch (e: Exception) {
                if (cache.exists()) {
                    problems += "${item.name}: nicht erreichbar, nutze die zuletzt gelesene Fassung."
                    cache.readText()
                } else {
                    problems += "${item.name}: nicht lesbar und keine frühere Fassung vorhanden."
                    continue
                }
            }
            if (text.length > MAX_CHARS_PER_FILE) {
                problems += "${item.name}: inzwischen zu groß (${text.length} Zeichen), wird ausgelassen."
                continue
            }
            parts += "## ${item.name}\n${text.trim()}"
        }
        return Loaded(parts.joinToString("\n\n"), problems)
    }

    private fun read(uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalStateException("kein Zugriff")

    private fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "Datei"

    private fun cacheFile(uri: Uri) = File(cacheDir, uri.toString().hashCode().toUInt().toString(16) + ".txt")

    companion object {
        const val MAX_CHARS_PER_FILE = 30_000
    }
}
