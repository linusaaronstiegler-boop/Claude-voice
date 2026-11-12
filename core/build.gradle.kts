// Reine JVM-Logik ohne Android-Klassen: dadurch laufen die Tests in Sekunden
// auf dem Rechner/CI, ganz ohne Emulator.
plugins { id("org.jetbrains.kotlin.jvm") }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.anthropic:anthropic-java:3.3.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// ---------------------------------------------------------------------------------------------
// Auswertung des Entscheide-und-antworte-Prompts (Anleitung: tools/README.md).
// Die Programme liegen bei den Tests (core/src/test/.../eval), weil sie die Testfälle aus
// src/test/resources/eval lesen und nicht in die App gehören. JavaExec startet sie mit dem
// Klassenpfad der Tests, also mit genau dem Code, den auch `:core:test` prüft.

/** Zusatzangaben von der Kommandozeile (-Pname=wert) an das Programm weiterreichen. */
fun JavaExec.evalOptions(vararg names: String) {
    for (name in names) {
        val value = providers.gradleProperty(name).orNull ?: continue
        if (name == "systemFile") {
            // Relative Pfade gelten ab dem Ordner, in dem gradlew aufgerufen wurde (nicht ab core/).
            val file = File(value).let { if (it.isAbsolute) it else File(gradle.startParameter.currentDir, value) }
            args("--system", file.absolutePath)
        } else {
            args("--$name", value)
        }
    }
}

tasks.register<JavaExec>("evalDump") {
    group = "verification"
    description = "Schreibt System-Prompt, Testfälle in drei Modi, Erwartungen und Stapel nach core/build/eval. Optional: -PsystemFile=<pfad>"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.claudevoice.core.eval.EvalDumpKt")
    args(layout.buildDirectory.dir("eval").get().asFile.absolutePath)
    evalOptions("systemFile")
}

tasks.register<JavaExec>("evalLive") {
    group = "verification"
    description = "Schickt alle Testfälle an das echte Modell (braucht ANTHROPIC_API_KEY). Optionen: -Prun=<name> -PsystemFile=<pfad> -Pmodes=<liste> -Ponly=<liste>"
    // Erst der Dump: Die Auswertung (tools/eval_score.py) braucht expected.json neben den Antworten.
    dependsOn("evalDump")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.claudevoice.core.eval.EvalLiveKt")
    args(layout.buildDirectory.dir("eval").get().asFile.absolutePath)
    evalOptions("run", "systemFile", "modes", "only")
}

// --- Weckwort "Hey Jarvis" ------------------------------------------------------------------
// Die ONNX Runtime rechnet die Weckwort-Modelle (siehe OpenWakeWord.kt).
// compileOnly: :core wird nur gegen die Bibliothek übersetzt, liefert sie aber nicht mit. Auf dem
// Handy bringt :app das Android-Paket derselben Version mit (gleiche Java-Klassen, andere
// Maschinenbibliotheken). Für die Tests auf dem Rechner kommt das JVM-Paket dazu.
//
// Warum 1.28.0 und nicht die neueste Version? Ab 1.29.0 enthält das Android-Paket Telemetrie:
// Es startet beim App-Start von selbst mit und kann Nutzungsdaten samt Gerätekennung an
// Microsoft melden. Das passt nicht zu einer App, die verspricht, in Bereitschaft nichts zu
// senden. 1.28.0 ist die letzte Version ohne diesen Code. Vor einem Update neu prüfen.
dependencies {
    compileOnly("com.microsoft.onnxruntime:onnxruntime:1.28.0")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.28.0")
}

// Die Modelle liegen nicht im Repo (Lizenz, siehe README). Vor den Tests werden sie nach
// build/wakeword geladen; ohne Netz gibt es nur eine Warnung, und die Weckwort-Tests melden
// sich als übersprungen.
extra["wakewordDir"] = layout.buildDirectory.dir("wakeword").get().asFile
extra["wakewordRequired"] = false
apply(from = rootProject.file("gradle/wakeword-models.gradle.kts"))
tasks.test { dependsOn("downloadWakeWordModels") }

// --- Rauchtest ------------------------------------------------------------------------------
// Spielt die ganze Kette gegen die echten Dienste durch (ohne Handy) und misst die Latenzen:
//   ELEVENLABS_API_KEY=... ANTHROPIC_API_KEY=... ./gradlew :core:smoke
tasks.register<JavaExec>("smoke") {
    group = "verification"
    description = "Rauchtest: Sprache -> Transkription -> Entscheidung/Antwort -> Sprache, mit echten Schlüsseln."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.claudevoice.core.smoke.SmokeKt")
}
