# Projektprotokoll (neueste zuerst)

## 2026-11-12 – Phasen 2 bis 4: Hören, Denken, Sprechen, Bedienung
- Pipeline steht: Mikro -> ElevenLabs Realtime-Transkription -> Haiku-Filter -> Sonnet (Streaming)
  -> ElevenLabs-Sprachausgabe satzweise. Kopfhörer-Taste und App-Knopf stoppen die Antwort.
- Geprüft: 17 JVM-Tests grün (Logik und alle drei Schnittstellen gegen einen Testserver),
  `assembleDebug` und Lint ohne Fehler.
- **Nicht geprüft:** Lauf auf einem echten Gerät und gegen die echten APIs. Hier gab es weder
  Gerät noch Keys. Erwartbare Baustellen: Bluetooth-Routing der AirPods, Lautstärke im
  Telefonie-Modus, Feinabstimmung des Filters.
- Abweichung vom Plan: kein Android-TTS-Fallback (ohne ElevenLabs-Key läuft die Transkription
  ohnehin nicht), Refusal-Fallbacks der Claude API noch nicht aktiv (siehe Roadmap).

### Checkliste Gerätetest
1. APK installieren, Keys eintragen, AirPods verbinden.
2. „Handy-Mikro“ wählen, Start: Status zeigt „Hört zu (Handy-Mikro)“, Mitschrift läuft mit.
3. Laut fragen „Wie hoch ist die Zugspitze?“: Antwort kommt in den AirPods, Frage und Antwort stehen in der Karte.
4. Smalltalk („Na, wie geht's?“) bleibt unbeantwortet.
5. Während der Antwort AirPods-Taste drücken: Ausgabe stoppt.
6. Bildschirm sperren, erneut fragen: läuft weiter.
7. Stopp, „AirPods-Mikro“ wählen, Start: Status zeigt „Hört zu (Kopfhörer-Mikro)“, Antwort kommt über die AirPods.
8. Flugmodus kurz an und aus: Status wechselt auf „Verbindung unterbrochen“ und erholt sich.

## 2026-11-12 – Phase 1: Gerüst
- Gradle-Projekt mit `core` (JVM) und `app` (Android, minSdk 31), Compose-Oberfläche, Einstellungen
  mit verschlüsselter Key-Ablage, Foreground-Service als Hülle, CI-Workflow.
- Build geprüft: `./gradlew :core:test :app:assembleDebug` läuft durch.
- Noch ohne Funktion: Der Start-Knopf startet nur den Service, es wird noch nichts gehört.
