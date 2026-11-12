# Projektprotokoll (neueste zuerst)

## 2026-11-12 – Phase 1: Gerüst
- Gradle-Projekt mit `core` (JVM) und `app` (Android, minSdk 31), Compose-Oberfläche, Einstellungen
  mit verschlüsselter Key-Ablage, Foreground-Service als Hülle, CI-Workflow.
- Build geprüft: `./gradlew :core:test :app:assembleDebug` läuft durch.
- Noch ohne Funktion: Der Start-Knopf startet nur den Service, es wird noch nichts gehört.
