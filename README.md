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

### Schlüssel
Welche Schlüssel nötig sind, hängt davon ab, wer mitschreibt und wer spricht:

| Sprachausgabe     | Transkription | nötige Schlüssel              |
|-------------------|---------------|-------------------------------|
| ElevenLabs        | ElevenLabs    | Anthropic, ElevenLabs         |
| ElevenLabs        | Soniox        | Anthropic, ElevenLabs, Soniox |
| Stimme des Geräts | ElevenLabs    | Anthropic, ElevenLabs         |
| Stimme des Geräts | Soniox        | Anthropic, Soniox             |

- **Anthropic API-Key** (console.anthropic.com → API Keys): immer nötig.
- **ElevenLabs API-Key** (elevenlabs.io → Developers → API Keys): für die Sprachausgabe „ElevenLabs“
  und, solange kein Soniox-Key eingetragen ist, für die Transkription.
- **Soniox API-Key:** zweiter Transkriptions-Anbieter mit Sprecher-Erkennung je Wort. Ist er eingetragen,
  schreibt Soniox mit.

Die letzte Zeile der Tabelle kommt ganz ohne ElevenLabs-Konto aus. Fehlt ein Schlüssel, sagen die
Einstellungen und der Start, welcher fehlt und wofür er gebraucht würde.

### Sprachausgabe
Zwei stehen zur Wahl. Ohne eigene Wahl spricht ElevenLabs, solange ein ElevenLabs-Key eingetragen ist,
sonst die Stimme des Geräts. Eine eigene Wahl gilt immer.

- **ElevenLabs:** klingt natürlich, fast wie ein Mensch. Braucht den ElevenLabs-Key und verbraucht dort
  Guthaben; der Text jeder Antwort geht an ElevenLabs. Die **Voice-ID** ist optional (sonst
  Standardstimme).
- **Stimme des Geräts:** die Sprachausgabe, die Android selbst mitbringt („Text-in-Sprache“). Sie kostet
  nichts und braucht kein Konto. Ehrlich gesagt klingt sie deutlich schlechter: hörbar nach Computer,
  gleichförmiger, mit mancher falschen Betonung, und englische Wörter spricht sie oft deutsch aus. Wie
  gut oder schlecht genau, hängt von der Sprach-Engine des Handys ab; zum Verstehen einer kurzen Antwort
  reicht es. Voraussetzung: In den Android-Einstellungen unter „Text-in-Sprache“ ist eine Engine gewählt
  und Deutsch geladen. Die App nimmt nur Stimmen, die ohne Netz sprechen. Fehlt etwas davon, steht das
  beim Start des Zuhörens da und nicht erst bei der ersten Antwort.

Im Betrieb wird nie still zwischen den beiden gewechselt. Scheitert ElevenLabs (Guthaben aufgebraucht,
Schlüssel abgelehnt), nennt die Meldung den Grund und den Ausweg; umstellen müssen Sie selbst.

### Weiteres
- **Assistent:** Name, weitere Rufnamen, Anrede, Charakter
- **Gedächtnis:** „Über mich“, Dateien verknüpfen, Notizen ansehen und löschen
- **Verhalten:** eigene Musik während der Antwort pausieren oder leiser stellen; **Stimme einlernen**;
  **gelerntes Stimmprofil** ansehen und vergessen; Messwerte in der Mitschrift anzeigen (siehe „Benutzen“)
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
  ob es Ihr Gespräch ist. (Ausnahme: Sie haben Ihre Stimme am Handy-Mikro eingelernt, siehe nächster Punkt.)
- **Stimme einlernen:** Stehen Ihre eigenen Sätze in der Mitschrift als NAH oder FERN, kennt die App Ihre
  Stimme an diesem Mikrofon nicht. Das passiert, wenn ein Kopfhörer die eigene Stimme leiser liefert, als
  die App ohne Profil annimmt (etwa -22 dBFS), und von selbst kommt sie da nicht heraus: Sie lernt ein
  Profil nur von einer Stimme, die sie schon für Ihre hält. Abhilfe unter Einstellungen → Verhalten →
  „Stimme einlernen“: Zuhören stoppen, Mikrofon wählen (Vorgabe ist das der Hauptseite), „Einlernen
  starten“ und den angezeigten Text etwa zehn Sekunden in normaler Lautstärke vorlesen, bis der Balken
  voll ist. Die App nimmt dabei über denselben Weg auf wie beim Zuhören (am Kopfhörer-Mikro über den
  Telefonkanal). Der Ton bleibt im Arbeitsspeicher und wird danach verworfen; gespeichert werden nur zwei
  Zahlen je Mikrofon: der Pegel in dBFS und die Stimmlage in Hz. Beide stehen danach im Ergebnis und beim
  Stimmprofil. Abgelehnt wird die Messung, wenn zu wenig Sprache dabei war oder Pegel und Stimmlage stark
  streuen (jemand redet dazwischen, Musik). Ist die Stimme sehr leise (unter -38 dBFS), warnt die App:
  Dann normal laut sprechen und prüfen, ob wirklich das gewählte Mikrofon aufnimmt. Am **Handy-Mikro**
  gilt nach dem Einlernen: Wer ähnlich klingt und ähnlich nah am Handy spricht, wird ebenfalls für Sie
  gehalten und darf merken und stummschalten. Wer das nicht will, lernt nur am Kopfhörer-Mikro ein.
  Mit dem Schalter „Messwerte anzeigen“ (daneben, Standard aus) zeigt die Mitschrift künftig je Zeile
  Pegel und Stimmlage.
- **Stimmprofil vergessen:** Die App lernt nebenbei, wie Ihre Stimme am Mikrofon klingt, getrennt für
  Kopfhörer- und Handy-Mikro. Lehnt sie Merken und Stummschalten bei Ihnen dauernd ab, hat sie
  vielleicht eine falsche Stimme gelernt. Unter Einstellungen → Verhalten → „Gelerntes Stimmprofil“
  steht je Mikrofon, ob ein Profil gespeichert ist; „Vergessen“ löscht es nach einer Rückfrage. Das geht
  nur, solange nicht zugehört wird (eine laufende Sitzung schriebe ihr Profil sofort zurück). Danach
  lernt die App am Kopfhörer-Mikro von vorn; am Handy-Mikro lernt sie von selbst kein neues Profil
  (nur über „Stimme einlernen“). Vergessen nimmt nur etwas weg: Niemand gilt dadurch als Träger.
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
- **Sprachausgabe:** mit „ElevenLabs“ der Text der Antwort, auch wenn sie dann doch nicht zu hören ist,
  weil in der Wartesekunde ein Mensch geantwortet hat. Mit der „Stimme des Geräts“ verlässt der Text das
  Handy nicht: Er geht an die Sprach-Engine des Handys (eine App des Herstellers, meist von Google) und
  wird dort gesprochen; die App wählt nur Stimmen, die ohne Netz auskommen. Was diese Engine sonst tut,
  bestimmt ihr Hersteller.

Die App selbst speichert weder Audio noch Mitschrift. Das gilt auch für die Stimme des Geräts: Android
kann Sprache nur „in eine Datei“ erzeugen; als Ziel bekommt es eines, in dem alles Geschriebene
verschwindet, und die App nimmt den Ton unterwegs ab. Für die Anbieter gelten deren eigene
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
