# Projektprotokoll (neueste zuerst)

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

**Nicht geprüft**
- Kein Lauf gegen die echten Dienste und keiner auf einem Gerät: Es gab weder Schlüssel noch Handy.
- Die Anweisungen in `Persona.kt` sind nicht gegen die Testfälle gemessen. Der geplante Vergleich
  dreier Fassungen fiel aus, weil die Nutzungs-Credits der Agenten aufgebraucht waren.
- ScribeClient, ElevenTts, SonioxClient, OpenWakeWord und die Oberfläche stammen aus Agentenläufen,
  die vor ihrer unabhängigen Prüfung abbrachen. Ihre eigenen Tests laufen, mehr nicht.
- Sechs von sieben Code-Reviews brachen ab. Verloren gingen außerdem drei Bauteile, bevor etwas
  gespeichert war: Audio-Bauteil (von Hand nachgebaut), Zahlen-Aussprache, Vorfilter-Härtung.

**Zwei alternative Fassungen der Anweisungen** liegen außerhalb des Repos vor und müssen erst gemessen
werden, bevor eine davon `Persona.kt` ersetzt.

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
