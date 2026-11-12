# Claude Voice

Android-App: AirPods rein, App starten. Sie hört das Gespräch mit, filtert echte Fragen heraus,
fragt Claude Sonnet und spricht die Antwort in ein bis zwei Sätzen ins Ohr.

## Installieren
1. Auf GitHub unter **Actions** den neuesten grünen Lauf „Build“ öffnen und das Artefakt
   `claude-voice-debug-apk` herunterladen, entpacken.
2. Die `app-debug.apk` aufs Handy kopieren und öffnen (Installation aus unbekannten Quellen erlauben).

## Einrichten
In der App unter **Einstellungen** eintragen:
- **Anthropic API-Key** – erzeugen unter console.anthropic.com → API Keys
- **ElevenLabs API-Key** – erzeugen unter elevenlabs.io → Developers → API Keys
- **Voice-ID** – optional, sonst Standardstimme

Die Schlüssel werden verschlüsselt nur auf dem Gerät gespeichert.

## Benutzen
- **AirPods-Mikro**: hört vor allem dich selbst (eigene Fragen, Telefon-Headset-Qualität).
- **Handy-Mikro**: hört den Raum (Gespräche mit anderen), Antwort kommt trotzdem über die AirPods.
- Es wird kein Audio und keine Mitschrift gespeichert.

## Entwickeln
Siehe `CLAUDE.md`. Stand und nächste Schritte: `HANDOVER.md`, `ROADMAP.md`.
