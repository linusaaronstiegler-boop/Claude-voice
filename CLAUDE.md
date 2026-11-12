# Claude Voice – Regeln für die Arbeit an diesem Repo

Native Android-App (Kotlin, Compose). Sie hört über Kopfhörer- oder Handy-Mikro ein Gespräch mit,
erkennt echte Fragen im eigenen Gespräch und spricht eine kurze Antwort von Claude ins Ohr.
Bereitschaftsmodus mit lokalem Weckwort „Hey Jarvis“.

## Aufbau
- `core/` – reine JVM-Logik ohne Android-Klassen, alles per `./gradlew :core:test` testbar:
  - `Contracts.kt` – Schnittstellen: `Transcriber`, `SpeechSynth`, `Speaker`, `WakeWordDetector`
  - `Pipeline.kt` – Herzstück: Mitschrift → Entscheidung → Antwort → Sprache (Vorgriff, Zurückhalten,
    Längendeckel, Schutzregeln für Merken/Stummschalten)
  - `Persona.kt`, `Brain.kt`, `ClaudeApi.kt` – Anweisungen und die drei Claude-Aufrufe
    (Entscheiden+Antworten, Vorprüfung, Ordner)
  - `VoiceMeter.kt`, `VoiceClassifier.kt`, `SpeakerMap.kt` – wer spricht (ich / nah / fern)
  - `ScribeClient.kt`, `SonioxClient.kt`, `ElevenTts.kt`, `OpenWakeWord.kt` – Anbieter und Weckwort
  - `src/test/.../eval/`, `tools/eval_score.py` – Auswertung der Anweisungen; `smoke/` – Rauchtest
- `app/` – Android: `ListenService` (verdrahtet alles), `MicSource`, `AndroidSpeaker`, `MemoryStore`,
  `Prefs`, Oberfläche unter `ui/`.

## Regeln
- Neue Logik gehört nach `core/`, wenn sie ohne Android auskommt. `app/` bleibt dünn.
- Es wird kein Audio gespeichert und keine Mitschrift auf Platte geschrieben. Nicht ändern.
- Das Repo ist öffentlich: keine persönlichen Daten, keine Schlüssel, keine Stimm-IDs von Nutzerkonten.
- API-Schlüssel liegen nur verschlüsselt in `Prefs`, nie in Logs.
- Merken, Stummschalten und das Lernen des Stimmprofils dürfen nur von einer Stimme ausgelöst werden,
  die die Akustik dem Träger zuordnet (`trusted` in `Pipeline`). Diese Schranke nicht aufweichen.
- Die Bereitschaft darf nie stillschweigend auf Dauerübertragung zurückfallen.
- Claude-Aufrufe über das offizielle SDK (`com.anthropic:anthropic-java`). Modelle:
  Antwort `claude-sonnet-5-5`, Vorprüfung und Ordner `claude-haiku-5-5`.
- Testnamen ohne Umlaute und ß (aus ihnen werden Dateinamen).
- Änderungen an `Persona.kt` mit der Auswertung gegenprüfen (`:core:evalLive`), nicht nach Gefühl.
- In Phasen arbeiten. Nach jeder Phase: Tests, `:app:assembleDebug`, Commit, Push.
- Jede Sitzung trägt oben in `HANDOVER.md` ein kurzes Update ein (neueste zuerst).

## Befehle
- Tests: `./gradlew :core:test :app:testDebugUnitTest`
- APK: `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- Rauchtest (echte Schlüssel): `ELEVENLABS_API_KEY=… ANTHROPIC_API_KEY=… ./gradlew :core:smoke`
- Auswertung: `./gradlew :core:evalDump`, `:core:evalLive -Prun=<name>`, `python3 tools/eval_score.py <ordner>`
- CI (`.github/workflows/build.yml`) baut bei jedem Push und hängt die APK als Artefakt an.
