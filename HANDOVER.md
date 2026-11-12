# Projektprotokoll (neueste zuerst)

## 2026-11-12 (Prüfrunde) – unabhängige Durchsichten, Reparaturen, neues Design
Anlass: Frage „Wie gut ist es schon, geht es besser?“. Antwort der Prüfrunde: gebaut und schlüssig,
aber mit Fehlern, die beim ersten Gerätetest sofort aufgefallen wären.

**Durchsichten (je ein Prüfer, jeder Fund von einem zweiten Agenten angegriffen)**
Fünf Durchsichten: Nebenläufigkeit, Android-Dienst, Latenz, Textverarbeitung, Anweisungen.
29 Funde, alle bestätigt, keiner widerlegt. Die wichtigsten:
- Antworten unter einer halben Sekunde („Ja.“) wurden nie abgespielt, die App hing danach 20 s.
- Eine Korrektur während des Wartens auf das Modell wurde verworfen, die alte Frage beantwortet.
- „Hey Jarvis“, Pause, Frage: nur „Ja, Sir?“ war zu hören, die Frage ging unter.
- Nach längerer Geräuschpause galt die eigene Stimme als fremd (Merken/Stummschalten abgelehnt).
- Die Kopfhörer-Prüfung sah nur „Bluetooth verbunden“: Autoradio oder Box hätten laut abgespielt.
- Start blockierte den Hauptfaden (Schlüsselspeicher, Modelle, verknüpfte Dateien).
- Steuerzeilen des Modells (SAY/MEMO/MUTE) wurden bei kleiner Abweichung vorgelesen statt ausgeführt.
- Am Handy-Mikro entsteht nie das Etikett ICH; das Modell hielt das eigene Gespräch für fremd.

**Eingespielt**
- ScribeClient und ElevenTts unabhängig gegen Dokumentation (und Scribe gegen den echten Dienst ohne
  Schlüssel) geprüft: 8 bzw. 12 Fehler behoben. Rauchtest wertet einen stillen Rückfall der
  Sprachausgabe auf HTTP jetzt als Fehler.
- AndroidSpeaker: Startschwelle 20 ms, Warten nach ausstehendem Ton, Puffer nach jeder Antwort leeren.
- SentenceChunker: erster Satz sofort; ob ein Punkt einen Satz beendet, entscheidet
  `SpokenText.dotEndsSentence` (gemeinsame Regel mit der Zahlen-Aussprache).
- Schranke vor dem Lautsprecher (`Speaker.speak(..., release)`, `synthesizeGated`): Sprache wird schon
  erzeugt, während noch offen ist, ob ein Mensch antwortet. `Speaker.prepare()` zum Vorwärmen.
- Vorfilter gehärtet (`Heuristics`, `HeuristicWords`): Satzform statt Wortanfang, Namen nach Klang.
  Tabelle 887 Zeilen, 786 richtig (vorher 492 von 782), kein Wissenswunsch mehr in COLD, 13 blinde
  Flecken im Test benannt. Neu: `PrefilterOnCasesTest` legt jeden Testfall am Vorfilter vorbei.
- `SpokenText.clean()`: schreibt Zahlen, Einheiten, Daten, Abkürzungen aus (noch nicht eingehängt).
- Oberfläche: Dunkelmodus und Glas-Optik (Haze 1.6.10), Erscheinungsbild System/Hell/Dunkel in den
  Einstellungen (`Prefs.appearance`), Farben in `ui/Theme.kt`. Bilder:
  `./gradlew :app:testDebugUnitTest -PohneWeckwort`, dann `app/build/outputs/roborazzi/`.
- Testfälle: alle neun Dateien von je zwei Prüfern gegengelesen (15 Korrekturen), dazu
  `vorpruefung.json` mit 24 neuen Fällen, deren Auslöser nur über die Vorprüfung läuft. 164 Fälle.

**Gemessen (Ersatzmessung mit Agenten, nicht die echte API)**
- Vorprüfung (Haiku), 37 Fälle x 3 Darstellungen: bestehende Anweisung lässt 62 von 63 Antwort-Fällen
  durch und sortiert 34 von 48 Schweige-Fällen aus. Eine überarbeitete Fassung war nicht besser
  (62 von 63, 27 von 48) und wurde verworfen.
- Hauptanweisung auf den 24 neuen, unverbrauchten Fällen: 64 von 72 richtig (89 %), 2 Fehlauslöser,
  6 verpasste Antworten. Schwachstellen: Frage des Banknachbarn im Unterricht wird als Unterricht
  übergangen; laut gerechnete Aufgabe und unsichere Vermutung des Trägers, wenn er allein ist; allein
  wählt das Modell „kurz“, wo „mittel“ erwartet war.

**Entschieden**
- Mikrofon-Standard bleibt das Kopfhörer-Mikro (Wunsch des Besitzers, beste Stimmerkennung). Seine
  Nebenwirkungen (Musik in Telefonqualität, andere Apps ohne Mikrofon, Taste legt auf) gehören als
  Hinweis in die Oberfläche. Das Handy-Mikro wird trotzdem repariert.

**Stand der Tests**: 220 Kern-Tests (1 übersprungen ohne Schlüssel), 56 App-Tests, alle grün.

**Nicht geprüft**: weiterhin kein Lauf mit echten Schlüsseln und keiner auf einem Gerät.

## 2026-11-12 – Version 2: Tempo, Filter, Persona, Weckwort
**Gebaut**
- Ein gestreamter Sonnet-Aufruf entscheidet und antwortet (erste Zeile `SKIP`/`SAY`); billige
  Vorprüfung nur für Äußerungen, die nicht wie eine Frage aussehen; „Ordner“ sortiert im Hintergrund
  Fremdgespräche aus und beschreibt die Lage.
- Tempo: Stille-Schwelle 0,5 s statt 1,5 s, Satzende-Vorhersage mit Vorgriff, kein Vorab-Nachdenken,
  vorgewärmter Zwischenspeicher, Sprachausgabe über eine Dauerverbindung.
- Wer spricht: Lautstärke und Tonhöhe (ICH/NAH/FERN), Sprecher-Kürzel aus der Transkription,
  Selbstkalibrierung auf die eigene Stimme.
- Persona Alfred mit Rufnamen Alfred und Jarvis, Gedächtnis (Text, verknüpfte Dateien, Notizen).
- Weckwort „Hey Jarvis“ lokal (openWakeWord auf ONNX Runtime), Bereitschaftsmodus.
- Neue Oberfläche mit Einstellungen-Seite; Audiofokus (Musik pausiert kurz), Kopfhörer-Taste.
- Schutzregeln aus dem Datenschutz-Review (siehe `CLAUDE.md`).

**Geprüft**
- 130 Kern-Tests und 26 Oberflächen-Tests grün, Lint ohne Fehler, Debug-APK baut.
- Weckwort mit echten, lokal synthetisierten Aufnahmen: 14 von 17 „Hey Jarvis“-Aufnahmen genau einmal
  erkannt, normale Sätze nie, eine ähnlich klingende Wortfolge („Hey Travis … Hey Charles“) einmal
  fälschlich. Die Kotlin-Portierung weicht höchstens 0,00004 vom Python-Original ab.
- Oberfläche als gerenderte Screenshots angesehen (JVM, ohne Emulator).

**Filter gemessen (Ersatzmessung, siehe Einschränkung)**
140 beschriftete Situationen, 7 davon strittig und nicht gewertet. Härtester Modus „ohne“: Das Modell
sieht keinerlei Sprecher-Schätzung, der Stimmfilter ist also komplett ausgefallen.

| Fassung der Anweisungen | Modus | richtig | Fehlauslöser | verpasste Fragen |
|---|---|---|---|---|
| erste Fassung | ohne | 123 von 133 (92 %) | 9 | 1 |
| nachgeschärft (im Repo) | ohne | 126 von 133 (95 %) | 5 | 2 |
| nachgeschärft (im Repo) | verrauscht, 72 Fälle | 62 von 68 (91 %) | 2 | 4 |

Die Fassung im Repo enthält zusätzlich zwei spätere Klarstellungen (Merken nur auf ausdrückliche Bitte,
Uhrzeit aus der Lage). Für sie gibt es nur einen Nachtest auf 42 gezielt gewählten Fällen: 30 richtig
gegenüber 29 davor. Einzelne Fälle kippen von Lauf zu Lauf; Unterschiede von ein, zwei Fällen sind
bei dieser Messart Rauschen.

Zwei unabhängig geschriebene Alternativen (viele Beispiele; Entscheidungsverfahren) lagen auf
denselben 72 Fällen mit je 88 % hinter der ersten Fassung (91 %) und wurden verworfen.

Einschränkung, bitte ernst nehmen: Gemessen wurde nicht über die echte API, sondern mit
Sonnet-Agenten, die die Antwort des Modells nachstellen. Das ist optimistischer als der echte
Aufruf ohne Nachdenkzeit. Außerdem wurden die Anweisungen an genau diesen Fällen nachgeschärft; eine
zurückgehaltene Kontrollmenge gibt es nicht. Die belastbare Zahl liefert erst
`./gradlew :core:evalLive` mit eigenem Schlüssel.

Offene Fehlermuster: thematisch passende Fremdgespräche direkt nach einer Antwort (Nachbartisch,
Doku-Sprecher, fremdes Kind am selben Exponat), stark verkürzte Nachfragen ohne Sprecher-Schätzung,
und bei falschen Sprecher-Etiketten zu viel Zurückhaltung.

**Nicht geprüft**
- Kein Lauf gegen die echten Dienste und keiner auf einem Gerät: Es gab weder Schlüssel noch Handy.
- ScribeClient, ElevenTts, SonioxClient, OpenWakeWord und die Oberfläche stammen aus Agentenläufen,
  die vor ihrer unabhängigen Prüfung abbrachen. Ihre eigenen Tests laufen, mehr nicht.
- Sechs von sieben Code-Reviews brachen ab. Verloren gingen außerdem drei Bauteile, bevor etwas
  gespeichert war: Audio-Bauteil (von Hand nachgebaut), Zahlen-Aussprache, Vorfilter-Härtung.


### Checkliste Gerätetest
1. APK installieren, Schlüssel eintragen, Kopfhörer verbinden. Ohne Kopfhörer muss der Start mit
   einer Meldung abbrechen.
2. Dauer-Zuhören, Handy-Mikro: Status „Hört zu“, Mitschrift läuft mit Sprecher-Kürzeln.
3. Laut fragen „Wie hoch ist die Zugspitze?“: Antwort im Ohr, Zeit bis zur Antwort steht im Status.
4. Smalltalk bleibt unbeantwortet; der Grund steht unter „Zuletzt“.
5. Musik laufen lassen und fragen: Musik pausiert kurz und läuft weiter.
6. Während der Antwort Kopfhörer-Taste drücken oder „Alfred, stopp“ sagen: Ausgabe endet.
7. „Alfred, merk dir …“: Notiz erscheint in den Einstellungen. Dasselbe von einer anderen Person
   gesprochen darf nichts speichern.
8. Bildschirm sperren, erneut fragen: läuft weiter.
9. Bereitschaft: „Hey Jarvis“ (englisch ausgesprochen) → Ton → Frage → Antwort → nach etwa
   20 Sekunden zurück in die Bereitschaft.
10. Kopfhörer ins Etui: Zuhören endet mit Meldung.
11. AirPods-Mikro wählen: Status nennt das Kopfhörer-Mikro, Antwort kommt über die Kopfhörer.

## 2026-11-12 – Phasen 1 bis 4
- Gerüst, Mikrofon, Transkription, Fragenfilter mit zwei Aufrufen, Sprachausgabe, Kopfhörer-Taste.
  Durch Version 2 abgelöst.
