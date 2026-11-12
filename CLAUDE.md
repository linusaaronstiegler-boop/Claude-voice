# Claude Voice – Regeln für die Arbeit an diesem Repo

Native Android-App (Kotlin, Compose). Sie hört über AirPods- oder Handy-Mikro ein Gespräch mit,
erkennt echte Fragen und spricht eine kurze Antwort von Claude ins Ohr.

## Aufbau
- `core/` – reine JVM-Logik ohne Android-Klassen (Transkriptpuffer, Fragenfilter, Claude-Aufrufe,
  ElevenLabs-Protokolle, Pipeline). Alles hier ist per `./gradlew :core:test` testbar.
- `app/` – Android-Teil: Foreground-Service, Mikrofon, Audioausgabe, Oberfläche, Schlüsselablage.

## Regeln
- Neue Logik gehört nach `core/`, wenn sie ohne Android auskommt. `app/` bleibt dünn.
- Es wird kein Audio gespeichert und kein Transkript auf Platte geschrieben. Nicht ändern.
- API-Schlüssel nie ins Repo, nie in Logs. Sie liegen nur verschlüsselt in `Prefs`.
- Claude-Aufrufe über das offizielle SDK (`com.anthropic:anthropic-java`). Modelle:
  Antwort `claude-sonnet-5-5`, Filter `claude-haiku-5-5`.
- In Phasen arbeiten. Nach jeder Phase: `./gradlew :core:test :app:assembleDebug`, Commit, Push.
- Jede Sitzung trägt oben in `HANDOVER.md` ein kurzes Update ein (neueste zuerst).

## Befehle
- Tests: `./gradlew :core:test`
- APK: `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- CI (`.github/workflows/build.yml`) baut bei jedem Push und hängt die APK als Artefakt an.
