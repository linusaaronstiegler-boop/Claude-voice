plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.claudevoice"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.claudevoice"
        minSdk = 31 // ab Android 12: setCommunicationDevice für sauberes Bluetooth-Routing
        targetSdk = 35
        versionCode = 2
        versionName = "0.2"
        // Die Weckwort-Bibliothek (ONNX Runtime) bringt Maschinencode für jede Prozessor-Art mit.
        // Aktuelle Handys sind arm64; nur das einzupacken hält die APK deutlich kleiner.
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging {
        // Jackson & Co. bringen gleichnamige Lizenzdateien mit; ohne Ausschluss bricht das Packen ab.
        resources.excludes += setOf(
            "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES",
            "META-INF/INDEX.LIST", "META-INF/*.kotlin_module", "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        )
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.security:security-crypto:1.1.0")
    // Rechnet die Weckwort-Modelle auf dem Handy. Gleiche Version wie in :core (Begründung dort).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")
}

// Die Weckwort-Modelle ("Hey Jarvis") werden beim Bauen geladen und als Assets in die APK gepackt.
// Sie liegen nicht im Repo (Lizenz, siehe README). Bewusst ohne Weckwort bauen: -PohneWeckwort
extra["wakewordDir"] = file("src/main/assets/wakeword")
extra["wakewordRequired"] = true
apply(from = rootProject.file("gradle/wakeword-models.gradle.kts"))
tasks.named("preBuild") { dependsOn("downloadWakeWordModels") }

// --- Tests der Oberfläche -------------------------------------------------------------------
// Hier gibt es weder Emulator noch Gerät. Robolectric führt Android-Code direkt auf dem Rechner
// (in der JVM) aus, Roborazzi speichert davon Bilder. So lässt sich die Oberfläche ansehen und
// prüfen, ohne ein Handy anzuschließen:  ./gradlew :app:testDebugUnitTest
// Die Bilder landen in app/build/outputs/roborazzi/ (der Ordner build/ wird nicht eingecheckt).
android {
    testOptions {
        unitTests {
            // Robolectric braucht das zusammengeführte Manifest und die Ressourcen der App.
            isIncludeAndroidResources = true
            all {
                // "record" = Bilder bei jedem Testlauf neu schreiben (statt mit alten zu vergleichen).
                it.systemProperty("roborazzi.test.record", "true")
                it.maxHeapSize = "1g"
            }
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.compose.ui:ui-test-junit4") // Version kommt aus der Compose-BOM oben
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.76.0")
}

// --- Glas-Optik --------------------------------------------------------------------------------
dependencies {
    // Zeichnet weich, was hinter schwebendem Glas liegt (Start/Stopp-Knopf, Antwortkarte).
    // 1.6.10 ist die letzte Fassung, die zu unserem Compose (1.8) passt: 1.7 setzt Compose 1.9
    // voraus, die 2.x-Reihe Compose 1.12 und Kotlin 2.4. Wer Compose anhebt, kann nachziehen;
    // ab 2.0 heißen die Aufrufe anders (hazeEffect wird zu hazeBlur).
    implementation("dev.chrisbanes.haze:haze:1.6.10")
}
