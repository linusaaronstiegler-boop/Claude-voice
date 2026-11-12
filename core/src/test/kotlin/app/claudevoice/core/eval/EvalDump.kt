package app.claudevoice.core.eval

import app.claudevoice.core.Persona
import app.claudevoice.core.Profile
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File

/** Was beide Programme (evalDump, evalLive) gemeinsam brauchen: Profil, System-Prompt, Aufruf-Optionen. */
object EvalSetup {
    /** Das Profil, mit dem ausgewertet wird: Assistent "Alfred", hört auch auf das Weckwort "Jarvis". */
    val PROFILE = Profile(name = "Alfred", aliases = listOf("Jarvis"), address = "Sir")

    /**
     * Der System-Prompt der App oder, wenn [systemFile] gesetzt ist, der Inhalt dieser Datei, unverändert.
     * So lässt sich ein geänderter Prompt gegen den aktuellen messen, ohne den Code anzufassen.
     */
    fun systemPrompt(systemFile: File?): String {
        if (systemFile == null) return Persona.systemPrompt(PROFILE)
        require(systemFile.isFile) { "systemFile nicht gefunden: $systemFile" }
        return systemFile.readText(Charsets.UTF_8)
    }

    class Options(val positional: List<String>, private val named: Map<String, String>) {
        operator fun get(name: String): String? = named[name]?.takeIf { it.isNotBlank() }
        fun file(name: String): File? = get(name)?.let(::File)
        fun list(name: String): List<String> = get(name)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    }

    /** Liest "--name wert"-Paare; alles andere sind Angaben ohne Namen in ihrer Reihenfolge. */
    fun options(args: Array<String>): Options {
        val positional = ArrayList<String>()
        val named = LinkedHashMap<String, String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a.startsWith("--")) {
                require(i + 1 < args.size) { "Wert fehlt hinter $a" }
                named[a.removePrefix("--")] = args[i + 1]
                i += 2
            } else {
                positional += a
                i++
            }
        }
        return Options(positional, named)
    }

    /** Modi aus einer Liste wie "korrekt,ohne"; leer = alle. */
    fun modes(names: List<String>): List<TagMode> {
        if (names.isEmpty()) return TagMode.entries
        return names.map { name ->
            requireNotNull(TagMode.of(name)) { "Unbekannter Modus '$name' (erlaubt: ${TagMode.entries.joinToString(", ") { it.id }})" }
        }.distinct()
    }

    /** Auswahl von Fällen über IDs oder ID-Anfänge ("solo-01,edge-"); leer = alle. */
    fun select(cases: List<EvalCase>, only: List<String>): List<EvalCase> =
        if (only.isEmpty()) cases else cases.filter { c -> only.any { c.id == it || c.id.startsWith(it) } }
}

/**
 * Schreibt alles, was man braucht, um den Prompt ohne die App zu prüfen, nach `core/build/eval/`:
 *
 *  - `system.txt`                 der System-Prompt
 *  - `cases/<modus>/<id>.txt`     je Fall und Modus die exakte Nutzer-Nachricht
 *  - `expected.json`              was erwartet wird (liest `tools/eval_score.py`). Darin stehen zum Nachlesen
 *                                 auch die Erwartungen an MEMO, MUTE und Sprechtext, die [EvalCheck] prüft.
 *  - `batches/<modus>-NN.json`    die Fälle in gemischten Sechser-Stapeln
 *  - `parser_check.json`          Beispiel-Antworten, gelesen vom ReplyParser der App (siehe [EvalParserCheck])
 *
 * Die Antworten des Modells gehören nach `out/<lauf>/<modus>/<id>.txt`. Diesen Ordner fasst der Dump
 * nicht an, alte Läufe bleiben also liegen.
 */
object EvalDump {
    data class Summary(val cases: Int, val batchesPerMode: Int)

    fun write(outDir: File, cases: List<EvalCase>, system: String): Summary {
        outDir.mkdirs()
        // Eingaben immer frisch schreiben: Ein gelöschter oder umbenannter Fall darf keine alte Datei hinterlassen.
        val casesDir = File(outDir, "cases").also { it.deleteRecursively() }
        val batchesDir = File(outDir, "batches").also { it.deleteRecursively(); it.mkdirs() }
        val json = ObjectMapper().writerWithDefaultPrettyPrinter()

        File(outDir, "system.txt").writeText(system, Charsets.UTF_8)

        val expected = LinkedHashMap<String, Any>()
        for (c in cases) {
            expected[c.id] = linkedMapOf(
                "answer" to c.expect.answer,
                "length" to c.expect.length,
                "gist" to c.expect.gist,
                "why" to c.expect.why,
                "ambiguous" to c.ambiguous,
                "tags" to c.tags,
                "scene" to c.scene,
                // Die prüfbaren Erwartungen an Sonderzeilen und Sprechtext (siehe EvalExpect; null = keine Angabe).
                "memo" to c.expect.memo,
                "mute_minutes" to c.expect.muteMinutes,
                "forbidden" to c.expect.forbidden,
                "required" to c.expect.required,
                // Je Modus: Gilt die Stimme der auslösenden Äußerung als die des Trägers? Nur dann darf eine
                // MEMO- oder MUTE-Zeile stehen. Das hängt an den Angaben des Modus, nicht am Fall allein.
                "verified" to TagMode.entries.associateTo(LinkedHashMap()) { it.id to EvalRenderer.render(c, it).situation.speakerVerified },
            )
        }
        File(outDir, "expected.json").writeText(json.writeValueAsString(expected) + "\n", Charsets.UTF_8)
        File(outDir, "parser_check.json").writeText(json.writeValueAsString(EvalParserCheck.all()) + "\n", Charsets.UTF_8)

        var batchesPerMode = 0
        for (mode in TagMode.entries) {
            val dir = File(casesDir, mode.id).also { it.mkdirs() }
            for (c in cases) File(dir, "${c.id}.txt").writeText(EvalRenderer.userMessage(c, mode), Charsets.UTF_8)

            val batches = EvalBatches.plan(cases, mode)
            batchesPerMode = batches.size
            val digits = maxOf(2, batches.size.toString().length)
            batches.forEachIndexed { i, ids ->
                val batch = linkedMapOf(
                    "mode" to mode.id,
                    "batch" to i + 1,
                    "of" to batches.size,
                    // Pfade relativ zu diesem Auswertungs-Ordner (core/build/eval).
                    "system" to "system.txt",
                    "ids" to ids,
                    "cases" to ids.map { "cases/${mode.id}/$it.txt" },
                    "out" to "out/<lauf>/${mode.id}/<id>.txt",
                )
                val name = "${mode.id}-${(i + 1).toString().padStart(digits, '0')}.json"
                File(batchesDir, name).writeText(json.writeValueAsString(batch) + "\n", Charsets.UTF_8)
            }
        }
        return Summary(cases.size, batchesPerMode)
    }
}

/** Einstieg für `./gradlew :core:evalDump`. Erste Angabe: Zielordner; optional `--system <datei>`. */
fun main(args: Array<String>) {
    val options = EvalSetup.options(args)
    val outDir = File(options.positional.firstOrNull() ?: "build/eval")
    val systemFile = options.file("system")
    val cases = EvalCases.loadAll()
    val summary = EvalDump.write(outDir, cases, EvalSetup.systemPrompt(systemFile))

    val files = cases.map { it.file }.distinct()
    val clear = cases.filter { !it.ambiguous }
    val modes = TagMode.entries.size
    println("evalDump: ${cases.size} Fälle aus ${files.size} Dateien (${files.joinToString(", ")})")
    println("  Antwort erwartet: ${clear.count { it.expect.answer }}, Schweigen erwartet: ${clear.count { !it.expect.answer }}, mehrdeutig: ${cases.size - clear.size}")
    println("  Szenen: " + cases.groupingBy { it.scene }.eachCount().toSortedMap().entries.joinToString(", ") { "${it.key} ${it.value}" })
    println("  System-Prompt: " + (systemFile?.let { "aus Datei $it" } ?: "Persona.systemPrompt (Alfred, Jarvis, Sir)"))
    println("  geschrieben nach ${outDir.absolutePath}:")
    println("    system.txt, expected.json, parser_check.json, cases/<modus>/<id>.txt ($modes x ${summary.cases}), batches/<modus>-NN.json ($modes x ${summary.batchesPerMode})")
    println("Weiter: Modell-Antworten nach out/<lauf>/<modus>/<id>.txt legen (oder ./gradlew :core:evalLive -Prun=<lauf>),")
    println("        dann  python3 tools/eval_score.py <lauf>            spricht es, wenn es soll?")
    println("        und   ./gradlew :core:evalCheck -Prun=<lauf>        stimmen MEMO, MUTE und die Absagen?")
}
