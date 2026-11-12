# Roadmap

- [x] Phase 1 – Gerüst: Gradle-Projekt, App-Hülle, verschlüsselte Keys, CI-Build
- [x] Phase 2 – Hören: Mikrofon, Echtzeit-Transkription, Live-Mitschrift
- [x] Phase 3 – Denken und Sprechen: Entscheiden+Antworten, Sprachausgabe
- [x] Phase 4 – Bedienung: Antwort stoppen, Reconnect, Fehleranzeigen
- [x] Version 2 – Tempo (ein Aufruf, 0,5 s Stille-Schwelle, Vorgriff, Vorwärmen), Filter in drei
      Schichten, Alfred-Persona mit Gedächtnis, Weckwort „Hey Jarvis“, neue Oberfläche, Schutzregeln

## Als Nächstes (in dieser Reihenfolge)
- [ ] **Rauchtest mit echten Schlüsseln** (`:core:smoke`): Bisher ist nichts gegen die echten Dienste
      gelaufen. Erwartbar sind kleine Protokollfehler bei Transkription und Sprachausgabe.
- [ ] **Gerätetest** auf dem Handy mit Kopfhörern (Checkliste in `HANDOVER.md`).
- [ ] **Filter messen** (`:core:evalLive`): Die Anweisungen in `Persona.kt` sind noch nie gegen die
      140 beschrifteten Situationen gelaufen. Zwei alternative Fassungen liegen zum Vergleich vor.
- [ ] Testfall-Sätze `party`, `mixed`, `call`, `musik`, `english` unabhängig gegenprüfen
      (bei `edge`, `lernen`, `solo`, `jarvis` ist das geschehen).
- [ ] Schwellen der Stimm-Einschätzung mit echten Aufnahmen vom Gerät einstellen.

## Offen aus Version 2
- [ ] Code-Review der Bereiche Nebenläufigkeit, Service, Textverarbeitung, Stimm-Messung, Latenz
      (begonnen, wegen aufgebrauchter Credits abgebrochen; nur Datenschutz kam durch).
- [ ] Unabhängige Prüfung der übernommenen Bausteine ScribeClient, ElevenTts, SonioxClient, OpenWakeWord.
- [ ] Zahlen-Aussprache als Sicherung im Code (heute verlässt sich die App auf die Anweisung ans Modell).
- [ ] Vorfilter (`Heuristics`) mit großer Beispieltabelle härten.
- [ ] Ersteinrichtung mit Hinweis auf Einwilligung der Anwesenden und Akku-Einstellungen.

## Später
- Release-Build mit R8 verkleinern (Debug-APK ist rund 105 MB groß).
- Sprecher-Erkennung auf dem Gerät per Stimm-Embedding statt Lautstärke und Tonhöhe.
- Weckwort-Fehlauslöser senken (ähnlich klingende Wörter), eigene Schwelle in den Einstellungen.
