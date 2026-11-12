# Claude Voice

Android-App für Kopfhörer: Sie hört über das Mikrofon mit, erkennt echte Fragen im eigenen Gespräch,
fragt Claude und spricht eine kurze Antwort ins Ohr. Der Assistent heißt „Alfred“ und hört auch auf
„Jarvis“.

## Was die App kann
- **Dauer-Zuhören:** hört durchgehend mit und antwortet, wenn im eigenen Gespräch eine Wissensfrage
  fällt, wenn man sich laut etwas fragt oder den Assistenten beim Namen anspricht.
- **Bereitschaft („Hey Jarvis“):** lauscht nur lokal auf das Weckwort. Erst danach wird für kurze Zeit
  zugehört und übertragen, dann geht es zurück in die Bereitschaft.
- **Filter in drei Schichten:** Lautstärke und Stimmlage (ich / nah / fern), ein „Ordner“, der
  Nachbargespräche, Fernseher und Liedtexte aussortiert, und das Antwort-Modell, das am Inhalt
  entscheidet, auch wenn die ersten beiden Schichten danebenliegen.
- **Antworten:** erst die Antwort, dann höchstens etwas Einordnung; die Länge richtet sich nach der Lage.
- **Rücksicht:** Im Gespräch wartet der Assistent eine Sekunde, ob ein Mensch antwortet. Eigene Musik
  pausiert kurz (oder wird leiser). „Alfred, stopp“ oder die Kopfhörer-Taste brechen die Antwort ab.
- **Gedächtnis:** „Über mich“-Text, verknüpfte Dateien (werden bei jedem Start frisch gelesen) und
  Notizen auf Zuruf („Alfred, merk dir …“). Alles bleibt auf dem Gerät.

## Installieren
1. Auf GitHub unter **Actions** den neuesten grünen Lauf „Build“ öffnen, das Artefakt
   `claude-voice-debug-apk` herunterladen und entpacken.
2. Die `app-debug.apk` aufs Handy kopieren und öffnen (Installation aus unbekannten Quellen erlauben).

## Einrichten
In der App unter **Einstellungen**:
- **Anthropic API-Key** (console.anthropic.com → API Keys)
- **ElevenLabs API-Key** (elevenlabs.io → Developers → API Keys), für Transkription und Stimme
- **Voice-ID** (optional, sonst Standardstimme)
- **Soniox API-Key** (optional): zweiter Transkriptions-Anbieter mit Sprecher-Erkennung je Wort
- **Assistent:** Name, weitere Rufnamen, Anrede, Charakter
- **Gedächtnis:** „Über mich“, Dateien verknüpfen, Notizen ansehen und löschen

## Benutzen
- **Kopfhörer sind Pflicht.** Ohne verbundene Kopfhörer startet das Zuhören nicht, und es endet, wenn
  sie getrennt werden: Antworten sollen nie aus dem Lautsprecher kommen.
- **AirPods-Mikro:** hört vor allem die eigene Stimme, das Gegenüber deutlich leiser. Gut für eigene
  Fragen und volle Räume. Nachteil: Solange es läuft, klingt auch Musik in Telefonqualität.
- **Handy-Mikro:** hört den Raum; die Antwort kommt trotzdem über die Kopfhörer. Besser, wenn nebenbei
  Musik läuft oder das Gegenüber gut verstanden werden soll.
- **Reichweite:** „Nur ich“ (streng, nur die eigene Stimme), „Mein Gespräch“ (Standard), „Ganzer Raum“.
- **Weckwort:** „Jarvis“ englisch aussprechen („Dschahwis“). Die deutsche Aussprache „Jarwis“ erkennt
  das Modell nicht.
- **Zahlen:** Die Antworten stehen mit ausgeschriebenen Zahlen in der App, weil die Stimme sie so
  zuverlässig spricht.

## Datenschutz und Recht
Was das Gerät verlässt:
- **Transkription (ElevenLabs oder Soniox):** im Dauer-Zuhören das gesamte Mikrofon-Audio, also auch
  alle Menschen in Hörweite. In der Bereitschaft nichts, bis das Weckwort fällt, danach höchstens zwei
  Minuten.
- **Claude (Anthropic):** die Mitschrift der letzten Minuten, Uhrzeit, eine Einschätzung der Lage und
  das hinterlegte Gedächtnis, jedes Mal, wenn eine mögliche Frage erkannt wird.
- **Sprachausgabe (ElevenLabs):** der Text der Antwort.

Die App selbst speichert weder Audio noch Mitschrift. Für die Anbieter gelten deren eigene
Aufbewahrungsregeln. Das Gedächtnis liegt nur auf dem Gerät und ist von Backups ausgenommen.

In Deutschland ist es strafbar, das nichtöffentlich gesprochene Wort anderer ohne deren Einwilligung
aufzunehmen (§ 201 StGB), und das Übertragen fremder Gespräche an Dienste ist eine Datenverarbeitung
im Sinne der DSGVO. Die App nimmt nichts auf, überträgt aber live. Sagen Sie den Anwesenden, dass ein
Assistent mithört, und holen Sie ihr Einverständnis ein. In Schule, Uni, Betrieb und bei Prüfungen
gelten zusätzlich eigene Regeln. Das ist kein Rechtsrat.

## Weckwort und Lizenz der Modelle
Die Erkennung von „Hey Jarvis“ nutzt die vortrainierten Modelle von
[openWakeWord](https://github.com/dscripka/openWakeWord) (David Scripka). Sie stehen unter
CC BY-NC-SA 4.0 (nur nicht-kommerzielle Nutzung). Sie liegen deshalb nicht in diesem Repo: Der Build
lädt sie mit Prüfsummen und packt sie in die APK. Ohne Weckwort bauen: `./gradlew assembleDebug -PohneWeckwort`.

## Entwickeln und prüfen
- Regeln und Aufbau: `CLAUDE.md`. Stand: `HANDOVER.md`. Offene Punkte: `ROADMAP.md`.
- Tests: `./gradlew :core:test :app:testDebugUnitTest` (Screenshots der Oberfläche landen in
  `app/build/outputs/roborazzi/`).
- **Rauchtest mit echten Schlüsseln, ohne Handy:**
  `ELEVENLABS_API_KEY=… ANTHROPIC_API_KEY=… ./gradlew :core:smoke`
  spielt Sprache → Transkription → Entscheidung/Antwort → Sprache durch und misst jede Stufe.
- **Filter-Auswertung:** `core/src/test/resources/eval/` enthält beschriftete Situationen (Party,
  Unterricht, Telefonate, Musik, durchmischte Gespräche, Weckwort …).
  `ANTHROPIC_API_KEY=… ./gradlew :core:evalLive -Prun=test` schickt sie an das echte Modell,
  `python3 tools/eval_score.py core/build/eval/out/test` wertet aus.
