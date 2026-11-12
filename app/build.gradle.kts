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
