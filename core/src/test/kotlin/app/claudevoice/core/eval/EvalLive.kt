package app.claudevoice.core.eval

import app.claudevoice.core.AnthropicClaude
import app.claudevoice.core.ClaudeApi
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.Collections
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

/**
 * Schickt die Testfälle durch das echte Modell, über denselben Aufruf wie die App
 * ([ClaudeApi.respond]), und legt jede Antwort unverändert nach `out/<lauf>/<modus>/<id>.txt`.
 *
 * Wichtig für ehrliche Zahlen: Ein Aufruf, der an Netz oder API scheitert, schreibt KEINE Datei.
 * Eine leere Datei hieße für die Auswertung "das Modell hat geschwiegen", und ein Ausfall der Technik
 * sähe aus wie eine Entscheidung des Modells. Fehlschläge stehen stattdessen in `errors.jsonl`,
 * und die Auswertung meldet die Fälle als fehlend.
 */
object EvalLive {
    /** Höchstens so viele Aufrufe gleichzeitig: schnell genug, ohne ins Ratenlimit zu laufen. */
    const val PARALLEL = 4
    const val ATTEMPTS = 3
    /** Obergrenze für einen einzelnen Aufruf, damit ein hängender Strom nicht den ganzen Lauf aufhält. */
    const val CASE_CEILING_MS = 90_000L

    data class Job(val case: EvalCase, val mode: TagMode)
    data class Failure(val mode: String, val id: String, val attempts: Int, val error: String)
    data class Result(val written: Int, val failures: List<Failure>)

    private enum class Kind { RETRY, GIVE_UP, FATAL }
    private class CaseTimeout : RuntimeException("Aufruf dauerte länger als ${CASE_CEILING_MS / 1000} s")

    fun jobs(cases: List<EvalCase>, modes: List<TagMode>): List<Job> = modes.flatMap { m -> cases.map { Job(it, m) } }

    fun run(
        claude: ClaudeApi,
        system: String,
        jobs: List<Job>,
        runDir: File,
        log: (String) -> Unit = ::println,
        sleep: (Long) -> Unit = Thread::sleep,
        clock: () -> Long = System::currentTimeMillis,
    ): Result {
        jobs.map { it.mode }.distinct().forEach { File(runDir, it.id).mkdirs() }
        val failures = Collections.synchronizedList(ArrayList<Failure>())
        val written = AtomicInteger()
        val finished = AtomicInteger()
        // Ein abgelehnter Schlüssel trifft jeden Aufruf: dann sofort aufhören statt hundertmal zu scheitern.
        val abort = AtomicReference<String?>(null)

        fun one(job: Job) {
            val target = File(File(runDir, job.mode.id), "${job.case.id}.txt")
            target.delete() // ein alter Stand darf nie als Ergebnis dieses Laufs gelten
            val user = EvalRenderer.userMessage(job.case, job.mode)
            var attempts = 0
            var problem: String? = null
            while (attempts < ATTEMPTS && abort.get() == null) {
                attempts++
                val out = StringBuilder() // je Versuch neu: ein halber Strom zählt nicht
                val deadline = clock() + CASE_CEILING_MS
                try {
                    claude.respond(system, user) { delta ->
                        if (clock() > deadline) throw CaseTimeout()
                        out.append(delta)
                    }
                    target.writeText(out.toString(), Charsets.UTF_8)
                    written.incrementAndGet()
                    log(progress(finished.incrementAndGet(), jobs.size, job, firstLine(out.toString())))
                    return
                } catch (e: Exception) {
                    problem = describe(e)
                    when (classify(e)) {
                        Kind.FATAL -> { abort.compareAndSet(null, problem); break }
                        Kind.GIVE_UP -> break
                        // Wartezeit wächst von Versuch zu Versuch; der Zufallsanteil verhindert, dass alle gleichzeitig wiederkommen.
                        Kind.RETRY -> if (attempts < ATTEMPTS) sleep(2_000L * (1L shl (attempts - 1)) + ThreadLocalRandom.current().nextLong(1_000))
                    }
                }
            }
            val error = problem ?: "nicht gesendet, Lauf abgebrochen: ${abort.get()}"
            failures += Failure(job.mode.id, job.case.id, attempts, error)
            log(progress(finished.incrementAndGet(), jobs.size, job, "FEHLER $error"))
        }

        val pool = Executors.newFixedThreadPool(PARALLEL)
        try {
            val pending = jobs.map { job -> pool.submit { one(job) } }
            for (f in pending) {
                try { f.get() } catch (e: ExecutionException) { throw e.cause ?: e }
            }
        } finally {
            pool.shutdownNow()
        }

        val sorted = failures.sortedWith(compareBy({ it.mode }, { it.id }))
        val errorsFile = File(runDir, "errors.jsonl")
        if (sorted.isEmpty()) errorsFile.delete() else {
            val mapper = ObjectMapper()
            errorsFile.writeText(sorted.joinToString("") { mapper.writeValueAsString(it) + "\n" }, Charsets.UTF_8)
        }
        return Result(written.get(), sorted)
    }

    /** Was lohnt einen neuen Versuch? Überlast und Netzfehler ja; ein abgelehnter Schlüssel beendet den Lauf. */
    private fun classify(e: Throwable): Kind = when {
        e is AnthropicServiceException && e.statusCode() in setOf(401, 403) -> Kind.FATAL
        e is AnthropicServiceException && (e.statusCode() in setOf(408, 409, 429) || e.statusCode() >= 500) -> Kind.RETRY
        e is AnthropicIoException || e is IOException || e is UncheckedIOException || e is CaseTimeout -> Kind.RETRY
        else -> Kind.GIVE_UP
    }

    private fun describe(e: Throwable): String =
        (e.javaClass.simpleName + ": " + (e.message ?: "ohne Meldung")).replace(Regex("\\s+"), " ").take(300)

    private fun firstLine(output: String): String =
        output.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(70) ?: "(leere Ausgabe)"

    private fun progress(n: Int, total: Int, job: Job, what: String): String =
        "[${n.toString().padStart(total.toString().length)}/$total] ${job.mode.id.padEnd(10)} ${job.case.id.padEnd(12)} $what"

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Erlaubte Laufnamen: Der Name wird zum Ordner, also keine Schrägstriche oder Punkte am Anfang. */
    val RUN_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

    val USAGE = """
evalLive: ANTHROPIC_API_KEY ist nicht gesetzt, es wurde nichts gesendet.

So läuft die Auswertung gegen das echte Modell (${AnthropicClaude.ANSWER_MODEL}):
  export ANTHROPIC_API_KEY=...            nur in der Shell setzen, nie ins Repo schreiben
  ./gradlew :core:evalLive -Prun=basis    jeder Fall in jedem Modus, höchstens $PARALLEL Aufrufe gleichzeitig
  python3 tools/eval_score.py basis       Zahlen dazu

Optionen:
  -Prun=<name>          Name des Laufs, Ausgaben landen in core/build/eval/out/<name>/ (Standard: live)
  -PsystemFile=<pfad>   diesen Text als System-Prompt verwenden statt des Prompts der App
  -Pmodes=korrekt,ohne  nur diese Modi (Standard: korrekt, verrauscht, ohne)
  -Ponly=solo-01,edge-  nur diese Fälle (IDs oder ID-Anfänge)

Ohne Schlüssel: ./gradlew :core:evalDump schreibt alle Eingaben nach core/build/eval/.
""".trim()
}

/** Einstieg für `./gradlew :core:evalLive`. Erste Angabe: Auswertungs-Ordner; dazu `--run`, `--system`, `--modes`, `--only`. */
fun main(args: Array<String>) {
    val key = System.getenv("ANTHROPIC_API_KEY")?.trim().orEmpty()
    if (key.isEmpty()) {
        println(EvalLive.USAGE)
        return
    }
    val options = EvalSetup.options(args)
    val evalDir = File(options.positional.firstOrNull() ?: "build/eval")
    val run = options["run"] ?: "live"
    require(EvalLive.RUN_NAME.matches(run)) { "Ungültiger Laufname '$run' (Buchstaben, Ziffern, Punkt, Binde- und Unterstrich)" }
    val modes = EvalSetup.modes(options.list("modes"))
    val only = options.list("only")
    val cases = EvalSetup.select(EvalCases.loadAll(), only)
    require(cases.isNotEmpty()) { "Kein Fall passt auf -Ponly=${only.joinToString(",")}" }
    val systemFile = options.file("system")
    val system = EvalSetup.systemPrompt(systemFile)

    val runDir = File(File(evalDir, "out"), run)
    // Ein voller Lauf beginnt mit leerem Ordner; ein Teil-Lauf (-Pmodes, -Ponly) ersetzt nur seine eigenen Dateien.
    if (only.isEmpty() && options.list("modes").isEmpty()) runDir.deleteRecursively()
    runDir.mkdirs()
    // Festhalten, womit der Lauf entstand: Zwei Läufe sind nur vergleichbar, wenn man weiß, welcher Prompt dahinter steht.
    File(runDir, "system.txt").writeText(system, Charsets.UTF_8)
    val meta = linkedMapOf(
        "run" to run,
        "model" to AnthropicClaude.ANSWER_MODEL,
        "system" to (systemFile?.name ?: "Persona.systemPrompt"),
        "system_sha256" to EvalLive.sha256(system),
        "modes" to modes.map { it.id },
        "cases" to cases.size,
        "parallel" to EvalLive.PARALLEL,
        "started" to OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString(),
    )
    File(runDir, "run.json").writeText(ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(meta) + "\n", Charsets.UTF_8)

    val jobs = EvalLive.jobs(cases, modes)
    println("evalLive: Lauf '$run', ${jobs.size} Aufrufe (${cases.size} Fälle x ${modes.size} Modi) an ${AnthropicClaude.ANSWER_MODEL}")
    // ANTHROPIC_BASE_URL ist nur für einen Testserver gedacht; ohne die Variable geht es an die echte API.
    val claude = AnthropicClaude(key, System.getenv("ANTHROPIC_BASE_URL")?.trim()?.ifEmpty { null })
    val result = EvalLive.run(claude, system, jobs, runDir)

    println("evalLive: ${result.written} Antworten in ${runDir.absolutePath}")
    if (result.failures.isNotEmpty()) {
        println("evalLive: ${result.failures.size} Aufrufe fehlgeschlagen, siehe errors.jsonl. Diese Fälle fehlen in der Auswertung.")
    }
    println("Weiter: python3 tools/eval_score.py $run")
    // Ausdrücklich beenden: Der HTTP-Client hält sonst noch Threads offen und Gradle würde warten.
    exitProcess(if (result.failures.isEmpty()) 0 else 1)
}
