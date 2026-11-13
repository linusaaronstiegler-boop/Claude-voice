# Roadmap

- [x] Phase 1 – Gerüst: Gradle-Projekt, App-Hülle, verschlüsselte Keys, CI-Build
- [x] Phase 2 – Hören: Mikrofon, Echtzeit-Transkription, Live-Mitschrift
- [x] Phase 3 – Denken und Sprechen: Entscheiden+Antworten, Sprachausgabe
- [x] Phase 4 – Bedienung: Antwort stoppen, Reconnect, Fehleranzeigen
- [x] Version 2 – Tempo (ein Aufruf, 0,5 s Stille-Schwelle, Vorgriff, Vorwärmen), Filter in drei
      Schichten, Alfred-Persona mit Gedächtnis, Weckwort „Hey Jarvis“, neue Oberfläche, Schutzregeln
- [x] Prüfrunde – fünf unabhängige Durchsichten (Nebenläufigkeit, Android-Dienst, Latenz,
      Textverarbeitung, Anweisungen), 29 Funde behoben; Gesprächssteuerung neu gebaut; Vorfilter mit
      Tabelle gehärtet; Zahlen-Aussprache als Sicherung im Code; alle Testfall-Sätze gegengeprüft;
      ScribeClient und ElevenTts gegen die Dokumentation geprüft
- [x] Design – Dark Mode und durchscheinende Flächen („Liquid Glass“), Wahl Hell/Dunkel/System
- [x] Schlussrunde – Antworten ohne Kopfzeile werden gesprochen, Anschlussfrage vor der ersten Antwort,
      Korrektur „Nein, ich meine …“, Angriffsrunde mit 19 Funden (Stimm-Schranke enger, Ruhe, Stopp,
      Warteplatz), wackliger CI-Test an der Ursache behoben

## In Arbeit
- [x] Gegenprüfung der Reparaturen aus der Angriffsrunde; Anschlussfrage auch hinter normal langen Antworten.
- [x] Sprachausgabe mit der Stimme des Geräts, damit die App ohne ElevenLabs-Schlüssel läuft.
- [x] Gelerntes Stimmprofil in den Einstellungen vergessen können.
- [x] „Stimme einlernen“ in den Einstellungen und Messwerte in der Mitschrift (Befund vom ersten
      Gerätetest: die eigene Stimme gilt als fremd).

## Als Nächstes (in dieser Reihenfolge)
Alles hier braucht den Besitzer: Schlüssel oder das Gerät.
- [ ] **Rauchtest mit echten Schlüsseln** (`:core:smoke`): Die App hat noch nie mit ihren eigenen
      Aufrufen gegen die echten Dienste gesprochen. Scribe und die Sprachausgabe sind nur gegen die
      Dokumentation geprüft.
- [ ] **Gerätetest** auf dem Handy mit Kopfhörern (Checkliste in `HANDOVER.md`). Dabei zuerst: Kommt
      der Ton im Ohr an, wird die eigene Stimme als ICH erkannt, hält die Bereitschaft den Akku aus.
- [ ] **Anweisungen über die echte API messen** (`:core:evalLive`): Bisher gibt es die Messung über das
      Kommandozeilen-Programm (`tools/eval_cli.py`, 95,3 % von 708 Entscheidungen). Die liegt nah an
      der Wahrheit, ist aber nicht der Aufruf der App.
- [ ] **Zurückgehaltene Kontrollmenge** an Testfällen anlegen: Die 185 Fälle wurden zum Einstellen
      benutzt, eine unverzerrte Zahl gibt es erst mit frischen Fällen.
- [ ] Schwellen der Stimm-Einschätzung (ICH/NAH/FERN) mit echten Aufnahmen vom Gerät einstellen.

## Offen im Code
- [ ] Bestätigung eines neuen Bluetooth-Geräts als richtiger Dialog (heute: zweiter Tipp auf Start),
      dazu eine Möglichkeit, bestätigte Geräte wieder zu vergessen.
- [ ] Unabhängige Prüfung der übernommenen Bausteine SonioxClient und OpenWakeWord (bei ScribeClient
      und ElevenTts ist das geschehen).
- [ ] Durchsicht der Stimm-Messung (`VoiceMeter`, `VoiceClassifier`): als einziger Bereich der
      Prüfrunde noch ohne eigenen Prüfer.
- [ ] Ersteinrichtung mit Hinweis auf Einwilligung der Anwesenden und Akku-Einstellungen.
- [ ] Bekannte Fehlentscheidungen der Anweisungen (in jeder Darstellung falsch): ein fremdes Gespräch,
      das thematisch an die letzte Antwort anschließt (`mix-02`), und eine allein laut gerechnete
      Aufgabe ohne Frageform (`vor-09`).
- [ ] Merk-Bitte ohne Anrede („merk dir bitte …“) erreicht das Antwortmodell meist nicht. Verlässlich
      ist „Alfred, merk dir …“ oder die Bitte direkt nach dem Weckwort. Bewusst so gelassen: Derselbe
      Satz gilt im Gespräch meist einem Menschen.
- [ ] Ein Kabel in einen Lautsprecher lässt sich von Kabel-Kopfhörern nicht unterscheiden.
- [ ] Standardstimme ersetzen: Die eingebaute Voice-ID ist eine „Default voice“ von ElevenLabs, die es nur
      für ältere Konten und nur bis 31.12.2026 gibt. Voice-ID zur Pflichtangabe machen oder klar melden.
- [ ] Verständliche Meldung, wenn der Anthropic-Schlüssel keinem Workspace zugeordnet ist (Antwort 400).
- [ ] Ein abgelehnter oder leerer Anthropic-Schlüssel sollte das Zuhören beenden; heute läuft die
      Transkription weiter und kostet, während jede Antwort scheitert.
- [ ] Schalter für die Schlüsselbegriffe der Transkription (bei ElevenLabs Aufpreis je Stunde).
- [ ] Verbrauchsanzeige: Die App liest die Zähler der Aufrufe nicht aus; ob der Zwischenspeicher für die
      Anweisungen greift, ist deshalb unbestätigt.
- [ ] Am Handy-Mikro ergeben zwei schnelle Fragen höchstens die zweite Antwort (der Träger ist dort von den
      anderen nicht zu unterscheiden).
- [ ] Die Wachhalte-Sperre gilt nur vier Stunden; eine längere Sitzung mit dunklem Bildschirm kann stocken.

## Später
- Zielgerät läuft mit Android 16: dort testen, danach `compileSdk`/`targetSdk` von 35 auf 36 anheben.
- Release-Build mit R8 verkleinern (Debug-APK ist rund 105 MB groß).
- Sprecher-Erkennung auf dem Gerät per Stimm-Embedding statt Lautstärke und Tonhöhe.
- Weckwort-Fehlauslöser senken (ähnlich klingende Wörter), eigene Schwelle in den Einstellungen.
- Kosten: Vorgriff und Zurückhalten erzeugen Aufrufe und Sprachzeichen, die nie zu hören sind. Auf dem
  Gerät messen, ob sich das im Alltag lohnt, und sonst einen Sparmodus anbieten.
- Sprachausgabe über Soniox (gleicher Schlüssel wie die Mitschrift, gleiches Tonformat) als dritte Wahl.
- Soniox in der EU-Region (eigene Adresse, regionsgebundener Schlüssel).
