# Projektprotokoll (neueste zuerst)

## 2026-11-13 – Stimme des Geräts, Stimmprofil vergessen, erster Lauf auf dem Gerät
- **Erster Gerätetest durch den Besitzer** (Kopfhörer-Mikro, echte Schlüssel): Die Kette läuft, die Antwort
  kommt im Ohr an. Befund: Die eigene Stimme wird in der Mitschrift als fremd geführt, mehrere Sprecher
  werden zu einem zusammengelegt. Ursache nach Lage des Codes: Ohne Profil gilt ein Schätzwert für den
  eigenen Pegel, und gelernt wird nur von einer Stimme, die schon als die eigene gilt.
- **Sprachausgabe mit der Stimme des Geräts** (`DeviceVoice.kt`, `SpeechOutput.kt` in `core/`,
  `AndroidDeviceVoice.kt` in `app/`): Wahl „Sprachausgabe“ in den Einstellungen. Damit braucht die App
  keinen ElevenLabs-Schlüssel mehr, wenn ein Soniox-Schlüssel die Mitschrift übernimmt. Kein stiller
  Wechsel zur Laufzeit. Welche Schlüssel für welche Kombination nötig sind, steht in `README.md`.
- **Stimmprofil vergessen** in den Einstellungen, je Mikrofon-Art.
- Unabhängig gegengeprüft, fehlende Tests ergänzt. 588 Kern-Tests (1 übersprungen), 166 App-Tests, APK mit
  Weckwort, Lint ohne Fehler.
- **In Arbeit** (nach einem Neustart des Containers neu angesetzt): 18 Funde der Gegenprüfung an der
  Angriffsrunde, darunter vier zur Stimmerkennung (Profil aus einer einzigen Äußerung, Sprecher-Nummern
  des Anbieters übersteuern die Akustik); „Stimme einlernen“ in den Einstellungen; Messwerte in der Mitschrift.
- **Nur auf dem Gerät zu prüfen:** ob die Stimme des Geräts über die Kopfhörer kommt und wie sie klingt.

## 2026-11-12 (Schlussrunde) – Antworten ohne Kopfzeile, Anschlussfrage, Angriffsrunde, CI-Test
Anlass: zwei offene Punkte aus der Gegenprüfung (Antworten ohne Kopfzeile, die verschluckte Anschlussfrage),
danach drei unabhängige Angriffs-Prüfer auf die neue Gesprächssteuerung (Nebenläufigkeit, Stimm-Schranke,
Verhalten im Gespräch) und parallel die Ursache des wackligen Dienst-Tests auf GitHub.

**Neu im Verhalten**
- Eine Antwort ohne Kopfzeile wird jetzt auch ohne Anrede gesprochen, mit dem Deckel „kurz“ (22 Wörter).
  Grund: Alle 31 gemessenen Antworten ohne Kopfzeile waren echte Antworten, 29 davon gewollt. Eine Sperre
  (`Headerless.isCommentary` in `ReplyParser.kt`) hält zurück, was nach lautem Nachdenken über die
  Entscheidung aussieht: ein Protokollwort (SKIP/SAY) in der ersten Zeile, eine bloße Absage („hintergrund“)
  oder eine kurze Ankündigung mit Doppelpunkt. Bekannte Lücke, per Test festgehalten: Ein frei formulierter
  Satz über die Entscheidung („Das ist nicht an mich gerichtet.“) ginge durch; in 1480 Antworten kam keiner vor.
- Steht die Antwort vor einer verspäteten Kopfzeile, wird sie gesprochen statt verworfen. Eine erste Zeile, die
  mit SKIP beginnt, gilt nur noch als Absage, wenn höchstens zwei Wörter folgen (trifft auf 711 von 712
  gemessenen Absagen zu); ein ganzer Satz dahinter wartet auf eine spätere Kopfzeile.
- Anschlussfrage vor der ersten Antwort („Wie viele Zeitzonen hat Russland?“ … „Und China?“): Der Nachsatz
  des Trägers wird nach der ersten Antwort als Nachfrage vorgelegt, mit der Antwort im Verlauf. Das gilt auch
  ohne Fragezeichen und kleingeschrieben („und china“, `Heuristics.isShortFollowUp`). Nur kurze Nachsätze
  gehen ohne Vorprüfung ans Antwortmodell; ein ganzer Satz an jemand anderen läuft erst durch die Vorprüfung.
  Hat inzwischen ein anderer aus der Runde etwas mit Inhalt gesagt, entfällt der Nachsatz.
- Korrektur im Wortlaut der Anleitung („Nein, ich meine den Watzmann.“) löst die wartende eigene Frage ab
  (`Heuristics.isCorrection`: „ich meine / meinte / mein …“ nach höchstens drei Wörtern wie nein, ach, sorry).
  Bewusst nicht erfasst: ein bloßes „Nein, den Watzmann.“, weil so auch „Nein, danke.“ an den Kellner beginnt.
- In Gesellschaft nimmt der eigene Nachsatz des Trägers ihm die Antwort nicht mehr weg: Bei seiner Frage
  zählt als „ein Mensch antwortet“ nur noch eine andere Stimme. Solange er selbst weiterspricht, wartet die
  Antwort. Gilt am Kopfhörer-Mikro; am Handy-Mikro ist der Träger von den anderen nicht zu unterscheiden.
- Nach dem Weckwort wird auch eine kurze Bitte ohne Frageform („Uhrzeit bitte“) dem Antwortmodell vorgelegt,
  solange das Fenster offen ist und die Stimme nah ist. Preis: Jede nahe Äußerung in diesen bis zu zwölf
  Sekunden kostet einen Aufruf.

**Aus der Angriffsrunde (19 Funde, 18 behoben, je mit Test, der vorher scheiterte)**
- Stimm-Schranke, enger gezogen: Ohne gespeichertes Stimmprofil reichte am Kopfhörer-Mikro „lauteste Stimme
  der Sitzung“. Ein Tischnachbar konnte so etwas merken lassen, und sein erster Ruf mit dem Namen wurde als
  Profil des Trägers gespeichert und sperrte den Träger aus. Jetzt braucht es ohne Profil beides: die Stimme
  ist in der Sitzung die nächste UND absolut laut genug (mindestens −30 dBFS, vorher −35). Das Profil lernt nur
  noch von einer so gesicherten Stimme, die mit dem Namen beginnt. Folge für den Gerätetest: Kommt die eigene
  Stimme leiser als −30 dBFS an, lassen sich ohne Profil keine Notizen und keine Ruhe per Sprache auslösen.
- Zugesagte Ruhe galt erst, wenn das Modell zu Ende geschrieben hatte, und eine zurückgestellte Äußerung
  rückte trotz Ruhe nach. Jetzt wird die Ruhe an jeder Stelle geprüft, an der Ton freigegeben wird; bittet der
  Träger erkennbar um Ruhe, gilt sie schon, während der Rest der Antwort noch gelesen wird.
- Scheitert das Speichern einer Notiz hinter dem Längendeckel, stürzte die App ab; jetzt erscheint eine Meldung.
- Was direkt nach einem „Stopp“ gesagt wurde, konnte für immer zurückgestellt bleiben; „Stopp“ räumt jetzt auf.
- Eine alte Antwort konnte noch hörbar werden, wenn sie genau zwischen Ablösung und Abbruch eintraf (älter
  als diese Runde, unter Last jeder zehnte Lauf). Eine Antwort wird nur noch freigegeben, solange ihr Auftrag
  der aktuelle ist.
- Der Fernseher verdrängte eine zurückgestellte Aussage, die Anschlussfrage und ein „Warum?“ des Trägers. Der
  eine Warteplatz behält jetzt die Äußerung mit dem höheren Anspruch.
- Das Echo-Filter schluckte die Rückfrage des Trägers, wenn sie Wörter der Antwort wiederholte. Was die Akustik
  dem Träger zuordnet, gilt nie als Echo.

**Der wacklige Test auf GitHub** (`ListenServiceStartTest`, „mitten im Aufbau beendet“)
- Ursache: eine Wettlaufsituation in der Annahme des Tests, kein Fehler nach dem Ende. Robolectric arbeitet in
  `startCommand()` die Warteschlange des Hauptfadens ab; war der erste Hintergrundschritt schon fertig, meldete
  der Aufbau zu Recht „Bitte zuerst Kopfhörer verbinden“, bevor der Dienst endete. Unter Last 1 von 7 Läufen,
  mit künstlich verbreitertem Fenster jedes Mal.
- Behoben mit einem Halter statt einer Wartezeit (`SetupHold.kt`): Der Test bestimmt, wann der erste Schritt
  endet. Danach 15 von 15 Läufen grün unter Last.
- Dabei drei echte Lücken in `ListenService.kt` geschlossen, je mit Test: Nach dem Ende ging der
  Vorwärm-Aufruf ein zweites Mal an Claude hinaus; Meldungen der Transkription kamen während des Abbaus noch
  in der Anzeige an; nach einem Stopp blieb stehen, was zuletzt in die Meldezeile geschrieben wurde.
- CI führt jetzt genau die Befehle aus `CLAUDE.md` aus und hängt bei einem Fehlschlag die Testberichte an.
  Die Tests der Release-Variante laufen dort nicht mehr mit (gleiche Quellen, gleiche Tests).

**Aus der Recherche zu Schlüsseln und Kosten** (offizielle Seiten, gelesen am 12.11.2026, je gegengeprüft)
- Der Anthropic-Schlüssel muss beim Anlegen einem Workspace zugeordnet werden. Ohne Zuordnung verlangt die
  API bei jedem Aufruf einen Workspace-Kopf, den die App nicht sendet (Antwort 400).
- Die eingebaute Standardstimme ist eine „Default voice“ von ElevenLabs. Die gibt es nur für Konten von vor
  März 2026 und nur bis 31.12.2026. Die App braucht also eine eigene Voice-ID.
- Die App schickt die Rufnamen als Schlüsselbegriffe an die Transkription. Das kostet bei ElevenLabs einen
  Aufpreis (0,08 $ je Stunde auf 0,22 $). Stille wird mitberechnet.
- ElevenLabs verlangt in den Bedingungen der Echtzeit-Transkription, dass Anwesende vorher erfahren, dass
  mitgeschrieben wird, und verbietet Stimmdaten von unter 18-Jährigen. Soniox speichert laut eigener Angabe
  weder Ton noch Mitschrift.
- Ein falscher oder leerer Anthropic-Schlüssel beendet das Zuhören nicht: Der Ton geht weiter an die
  Transkription, nur die Antwort scheitert.

**Offen**
- Die Anschlussfrage greift bisher nur hinter sehr kurzen ersten Antworten (sie darf höchstens 4 s alt sein,
  wenn die erste Antwort verklungen ist). Wird gerade geändert: Die Sprechzeit der ersten Antwort zählt nicht.
- Am Handy-Mikro ergeben zwei schnelle Fragen weiter höchstens die zweite Antwort.
- Kein Weg in der App, ein falsch gelerntes Stimmprofil zu vergessen (in Arbeit).
- Ohne ElevenLabs-Schlüssel startet die App nicht; eine Sprachausgabe mit der Stimme des Geräts ist in Arbeit.
- Die Reparaturen der Angriffsrunde hat außer ihrem Autor noch niemand geprüft; die Gegenprüfung läuft.

**Stand der Tests**: 553 Kern-Tests (1 übersprungen: braucht einen Schlüssel), 131 App-Tests, alle grün;
APK mit Weckwort gebaut, Lint ohne Fehler; Selbsttest der Auswertung bestanden (797 Prüfungen).

**Nicht geprüft**: weiterhin nichts mit echten Schlüsseln durch die App selbst und nichts auf einem Gerät.

## 2026-11-12 (Gegenprüfung nach dem Zusammenführen) – Tests am gehärteten Vorfilter, fünf Nahtstellen repariert
Anlass: Gesprächssteuerung, gehärteter Vorfilter und Sprech-Sicherheitsnetz wurden zusammengeführt; acht
Tests waren rot und wurden an die neuen Einstufungen angeglichen. Diese Runde hat das unabhängig geprüft
und die Stellen durchgespielt, an denen die drei Teile aufeinandertreffen.

**Geprüft**
- Jeder ersetzte Beispielsatz mit dem echten Vorfilter eingestuft (die Tests prüfen das jetzt selbst).
  Keine Erwartung gelockert oder gestrichen.
- Jede Regel, die einer der acht Tests bewacht, einzeln gebrochen (zwölf Mutationen an `Pipeline.kt`):
  Der Test fällt jedes Mal durch. Dasselbe für die neuen Tests unten (sieben Mutationen).
- Zwei weitere Tests wieder auf ihren Namen gebracht: „Nachfrage ohne Fragewort“ benutzte „und seit wann“,
  das der Vorfilter inzwischen selbst als möglichen Anlass einstuft. Jetzt „und in Fuß“ (COLD).

**Repariert (je mit Test)**
- Rang 0 in `Pipeline.claimOf`: „Keine Ahnung, wie das heißt“ ist für den Vorfilter eine klare Frage, für
  `Heuristics.isRealContent` aber keine Antwort. Die Pipeline hielt den Satz deshalb für einen Einwurf wie
  „Warum?“, und die nächste Quizfrage aus dem Fernseher nahm dem Träger die Antwort weg. Jetzt zwei
  Maßstäbe (`Pipeline.isAside`): Kommt der Satz neu dazu, steht er wie bisher zurück (die ratlose Reaktion
  eines Freundes verdrängt die Frage nicht); wartet er selbst, zählt `Heuristics.saysSomething`.
- Echo-Filter: Es kannte nur den Text, wie das Modell ihn schrieb („3 km“). Zu hören ist seit dem
  Sicherheitsnetz die ausgeschriebene Fassung; ein Echo davon („drei Kilometer und 250 Meter“) rutschte
  als fremde Zeile in die Mitschrift. Jetzt zählen die Wörter beider Fassungen.
- Fenster nach dem Weckwort: Ein „Ja.“ oder „Hey“ ohne Namen gilt weiter als bloßer Ruf, legt aber nicht
  mehr fest, wer gerufen hat (außer die Akustik erkennt den Träger). Vorher machte ein „Ja.“ aus dem
  Fernseher den Fernseher zum Rufer, und sein nächster Satz ging als direkte Bitte ans Modell.
- Längendeckel: Seit der Aufruf hinter dem Deckel weiterläuft (für MEMO/MUTE am Schluss), galt der
  Assistent als sprechend, bis das Modell fertig war, und überhörte so lange jede Frage ohne Anrede; riss
  die Verbindung in dieser Zeit ab, wurde der laufende Satz abgeschnitten und ein Fehler gemeldet. Jetzt
  endet der Auftrag mit dem Ton, den Rest liest ein eigener Leser nur noch auf Sonderzeilen
  (`Pipeline.drain`), hinter derselben Schranke `trusted`.
- „Stopp“ zwischen zwei Aufträgen: Der neue Auftrag lief an und wurde gleich wieder abgebrochen. Lag
  seine Antwort schon bereit, sprach er dazwischen den ersten Satz (der Test dazu fiel unter Last etwa
  jedes zwölfte Mal durch). Jetzt startet er erst, wenn feststeht, dass kein Stopp dazwischenkam.

**Offen (bewusst nicht geändert, Entscheidung des Besitzers)**
- „Wie viele Zeitzonen hat Russland?“ und gleich danach „Und China?“, bevor die erste Antwort zu hören ist:
  Die verkürzte Anschlussfrage ist für den Vorfilter MAYBE, steht als beiläufige Aussage zurück und verfällt,
  sobald die erste Antwort gesprochen ist. Nach der Antwort gestellt, wird sie als Nachfrage beantwortet.
- Eine Merk-Bitte ohne Anrede („merk dir bitte …“) ist nie HOT; sie hängt an der Vorprüfung, und die fragt
  nur nach einem Wissenswunsch. Verlässlich ist nur „Alfred, merk dir …“.
- Nach dem Weckwort gilt „Gruß und ein unbekanntes Wort“ als verhörter Ruf. Das verschluckt auch eine
  Zwei-Wort-Bitte wie „Hey, Uhrzeit?“ (war schon vor dem Zusammenführen so).
- Das Echo-Filter kennt den Wortlaut einer Antwort erst, wenn sie zu Ende ist (ebenfalls schon vorher so).

**Stand der Tests** nach dem Einspielen auf den Hauptzweig: 482 Kern-Tests (1 übersprungen: braucht einen
Schlüssel), 126 App-Tests, alle grün; APK mit Weckwort gebaut, Lint ohne Fehler.

**Nicht geprüft**: weiterhin kein Lauf mit echten Schlüsseln und keiner auf einem Gerät. `Persona.kt` ist
unverändert, die Auswertung war deshalb nicht nötig.

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

**Anweisungen gemessen (echtes Modell über `tools/eval_cli.py`, ohne Nachdenkzeit; nicht der Aufruf der App)**
185 Fälle in vier Darstellungen (korrekt, verrauscht, ohne, handy), 8 strittige nicht gewertet, je Fall
ein Aufruf: 708 gewertete Entscheidungen je Fassung.

| Fassung | richtig | Fehlauslöser | verpasst | ohne Kopfzeile |
|---|---|---|---|---|
| vorher (A4) | 666 von 708 (94,1 %) | 22 | 20 | 25 von 740 |
| neu (A5, im Repo) | 675 von 708 (95,3 %) | 17 | 16 | 6 von 740 |

Nach Darstellung (F1, neu): korrekt 0,98, verrauscht 0,96, ohne 0,93, handy 0,95. 18 Entscheidungen
besser, 9 schlechter; einzelne Fälle kippen von Lauf zu Lauf. In allen vier Darstellungen falsch bleiben
`mix-02` (Fremdgespräch, das thematisch an die letzte Antwort anschließt) und `vor-09` (allein laut
gerechnete Aufgabe wird nicht als Frage erkannt).
Antworten ohne Kopfzeile waren in allen 31 Fällen echte Antworten, kein lautes Nachdenken.

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
