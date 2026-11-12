// Lädt die drei Modelle für das Weckwort "Hey Jarvis" (openWakeWord) aus dem Netz.
//
// Warum nicht einfach ins Repo legen? Die Modelle stehen unter CC BY-NC-SA 4.0 (nur
// nicht-kommerziell, Weitergabe unter gleicher Lizenz). Das Repo ist öffentlich und soll frei
// von solchen Auflagen bleiben. Also holt der Build die Dateien bei Bedarf selbst.
//
// Warum die Prüfsummen? Ein Download kann abbrechen oder (im schlimmsten Fall) unterwegs
// ausgetauscht werden. Die SHA-256-Summe ist ein Fingerabdruck der Datei: stimmt er nicht,
// wird die Datei verworfen und der Build bricht ab, statt etwas Unbekanntes in die App zu packen.
//
// Eingebunden wird dieses Skript von :core (für die Tests) und :app (für die APK). Wohin die
// Dateien sollen und ob sie Pflicht sind, legt das jeweilige Modul vorher fest:
//   extra["wakewordDir"]      = Zielordner
//   extra["wakewordRequired"] = true: Build bricht ab, wenn die Modelle fehlen
//                               false: nur eine Warnung (die Tests überspringen sich dann selbst)

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

tasks.register("downloadWakeWordModels") {
    group = "build setup"
    description = "Lädt die openWakeWord-Modelle für \"Hey Jarvis\" und prüft ihre SHA-256-Summen."

    // Alles, was die Aufgabe braucht, steht hier als einfacher Wert. So hängt sie an nichts
    // anderem aus dem Build-Skript und läuft auch mit Gradles Konfigurations-Cache.
    val baseUrl = "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1"
    val models = mapOf(
        "melspectrogram.onnx" to "ba2b0e0f8b7b875369a2c89cb13360ff53bac436f2895cced9f479fa65eb176f",
        "embedding_model.onnx" to "70d164290c1d095d1d4ee149bc5e00543250a7316b59f31d056cff7bd3075c1f",
        "hey_jarvis_v0.1.onnx" to "94a13cfe60075b132f6a472e7e462e8123ee70861bc3fb58434a73712ee0d2cb",
    )
    val dir = project.extra["wakewordDir"] as File
    val required = project.extra["wakewordRequired"] as Boolean
    val offline = gradle.startParameter.isOffline                        // ./gradlew --offline
    val without = providers.gradleProperty("ohneWeckwort").isPresent     // ./gradlew -PohneWeckwort

    doLast {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        // Schon vorhanden und unverändert? Dann ist nichts zu tun, auch ohne Netz.
        val missing = models.filter { (name, hash) ->
            val file = File(dir, name)
            !file.isFile || sha256(file) != hash
        }
        if (missing.isEmpty()) return@doLast

        var problem: String? = null
        if (without) {
            problem = "mit -PohneWeckwort abgeschaltet"
        } else if (offline) {
            problem = "Gradle läuft mit --offline"
        } else {
            dir.mkdirs()
            for ((name, hash) in missing) {
                val part = File(dir, "$name.part")
                try {
                    val connection = URI("$baseUrl/$name").toURL().openConnection() as HttpURLConnection
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 30_000
                    if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
                    connection.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
                } catch (e: IOException) {
                    part.delete()
                    problem = "$name: ${e.message ?: e.javaClass.simpleName}"
                    break
                }
                val actual = sha256(part)
                if (actual != hash) {
                    part.delete()
                    // Kein "ohne Netz"-Fall, sondern eine falsche Datei: immer abbrechen.
                    throw GradleException("Weckwort-Modell $name hat eine falsche Prüfsumme ($actual statt $hash).")
                }
                Files.move(part.toPath(), File(dir, name).toPath(), StandardCopyOption.REPLACE_EXISTING)
                logger.lifecycle("Weckwort-Modell geladen: $name")
            }
        }

        val reason = problem ?: return@doLast
        if (required && !without) {
            throw GradleException(
                "Die Weckwort-Modelle konnten nicht geladen werden ($reason). " +
                    "Mit Netz noch einmal versuchen, oder bewusst ohne Weckwort bauen: -PohneWeckwort " +
                    "(die App hört dann durchgehend zu)."
            )
        }
        logger.warn(
            "WARNUNG: Weckwort-Modelle fehlen in $dir ($reason). " +
                if (required) "Die App wird ohne \"Hey Jarvis\" gebaut und hört durchgehend zu."
                else "Die Weckwort-Tests werden übersprungen."
        )
    }
}
