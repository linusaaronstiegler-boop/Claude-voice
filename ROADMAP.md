# Roadmap

- [x] Phase 1 – Gerüst: Gradle-Projekt, App-Hülle, Einstellungen mit verschlüsselten Keys, CI-Build
- [ ] Phase 2 – Hören: Mikrofon (AirPods/Handy), ElevenLabs Realtime-Transkription, Live-Mitschrift
- [ ] Phase 3 – Denken und Sprechen: Fragenfilter (Haiku), Antwort (Sonnet, Streaming), Sprachausgabe
- [ ] Phase 4 – Bedienung: Antwort stoppen per Kopfhörer-Taste, Reconnect, Fehleranzeigen

## Später
- Release-Build mit R8 verkleinern (Debug-APK ist wegen des ungeschrumpften Claude-SDK ca. 70 MB)
- Sprecher unterscheiden (eigene Stimme vs. andere), damit nur fremde oder nur eigene Fragen zählen
- Codewort-Modus als Alternative zur Automatik
- Server-seitige Refusal-Fallbacks der Claude API einschalten
