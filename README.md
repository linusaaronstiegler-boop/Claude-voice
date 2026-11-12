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
- **Rücksicht:** Im Gespräch wartet der Assistent nach dem Ende der Frage eine Sekunde, ob ein Mensch
  antwortet. Eigene Musik pausiert kurz (oder wird leiser). „Alfred, stopp“ bricht die Antwort ab, die
  Kopfhörer-Taste ebenfalls (mit dem Kopfhörer-Mikro trennt sie dabei kurz die Verbindung, siehe unten).
- **Korrekturen:** Wer sich verbessert, bevor die Antwort zu hören ist („nein, ich meine den Watzmann“),
  bekommt die Antwort auf die neue Frage.
- **Was er nicht kann:** kein Internet, also kein Wetter, keine Spielstände, keine Kurse; keine
  Erinnerungen, keine Timer, nichts schalten. Er sagt das dann, statt etwas zu erfinden.
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
- **Erscheinungsbild:** System, Hell oder Dunkel

## Benutzen
- **Kopfhörer sind Pflicht.** Die App prüft, wohin der Ton wirklich geht: vor dem Start, vor jeder
  Antwort, bei jedem Gerätewechsel und während der Wiedergabe. Lautsprecher, Bluetooth-Boxen und das
  Auto werden abgelehnt; geht der Ton nicht mehr an Kopfhörer, endet das Zuhören mit einer Meldung.
  Jedes Bluetooth-Gerät fragt die App **einmal** ab („Sind das Ihre Ohrhörer?“): Dann ein zweites Mal
  auf Start tippen, danach merkt sie sich das Gerät. Grenze: Ein Kabel, das in einem Lautsprecher
  steckt, kann sie nicht von Kopfhörern unterscheiden.
- **Kopfhörer-Mikro (Standard, z. B. AirPods):** Nur hier ist die eigene Stimme mit Abstand die lauteste.
  Daran erkennt die App, dass Sie selbst sprechen, und davon hängen das Merken, das Stummschalten per
  Sprache und die Reichweite „Nur ich“ ab. Der Preis ist der Telefon-Modus von Bluetooth, solange
  zugehört wird (auch in der Bereitschaft): Musik klingt wie am Telefon, andere Apps bekommen kein
  Mikrofon (Sprachnachrichten, Diktat, Videoton), und ein Druck auf die Kopfhörer-Taste trennt kurz die
  Verbindung. Kommt sie nicht binnen drei Sekunden zurück, endet das Zuhören.
- **Handy-Mikro:** hört den Raum; die Antwort kommt trotzdem über die Kopfhörer, Musik bleibt in voller
  Qualität, und die Kopfhörer-Taste stoppt nur die Antwort. Richtig, wenn nebenbei Musik läuft oder das
  Handy im Hörsaal auf dem Tisch liegt. Die eigene Stimme ist hier nicht von anderen zu unterscheiden:
  Merken und Stummschalten per Sprache werden abgelehnt, und der Assistent entscheidet allein am Inhalt,
  ob es Ihr Gespräch ist.
- **Reichweite:** „Nur ich“ (streng, nur die eigene Stimme; sinnvoll nur mit dem Kopfhörer-Mikro),
  „Mein Gespräch“ (Standard), „Ganzer Raum“.
- **Weckwort:** „Jarvis“ englisch aussprechen („Dschahwis“). Die deutsche Aussprache „Jarwis“ erkennt
  das Modell nicht. Nach dem Ton einfach die Frage stellen; „Hey Jarvis“ allein löst nichts aus.
- **Ruhe:** „Alfred, sei eine halbe Stunde still“ schaltet ihn stumm (ohne Dauer: 30 Minuten, höchstens
  zehn Stunden). Beim Namen genannt, antwortet er wieder.
- **Zahlen:** Die Stimme bekommt Zahlen, Einheiten und Daten immer ausgeschrieben, auch wenn das Modell
  einmal Ziffern schreibt.

## Datenschutz und Recht
Was das Gerät verlässt:
- **Transkription (ElevenLabs oder Soniox):** im Dauer-Zuhören das gesamte Mikrofon-Audio, also auch
  alle Menschen in Hörweite. In der Bereitschaft nichts, bis das Weckwort fällt; dann das Weckwort
  selbst mit gut einer Sekunde Vorlauf und danach höchstens zwei Minuten.
- **Claude (Anthropic):** die Mitschrift der letzten Minuten, Datum und Uhrzeit, eine Einschätzung der
  Lage und das hinterlegte Gedächtnis, jedes Mal, wenn eine mögliche Frage erkannt wird. Das kann schon
  beginnen, kurz bevor der Satz zu Ende gesprochen ist (so wird die Antwort schneller). Außerdem ein
  kurzer Vorwärm-Aufruf mit Anweisungen und Gedächtnis (ohne Audio, ohne Mitschrift) beim Start, beim
  Weckwort und wenn nach mehr als vier Minuten Pause wieder jemand spricht.
- **Sprachausgabe (ElevenLabs):** der Text der Antwort, auch wenn sie dann doch nicht zu hören ist, weil
  in der Wartesekunde ein Mensch geantwortet hat.

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
- **Filter-Auswertung:** `core/src/test/resources/eval/` enthält 185 beschriftete Situationen (Party,
  Unterricht, Telefonate, Musik, durchmischte Gespräche, Weckwort, Vorprüfung …). Jede wird in vier
  Darstellungen gezeigt: Sprecher-Schätzung richtig, teils falsch, gar nicht, und wie am Handy-Mikro.
  - Mit API-Schlüssel: `ANTHROPIC_API_KEY=… ./gradlew :core:evalLive -Prun=test`
  - Ohne Schlüssel, mit angemeldetem Claude Code (Abo): `./gradlew :core:evalDump`, dann
    `python3 tools/eval_cli.py test`
  - Auswerten: `python3 tools/eval_score.py test` (zwei Laufnamen vergleichen zwei Fassungen)
  Stand der Messungen steht in `HANDOVER.md`.
