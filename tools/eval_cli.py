#!/usr/bin/env python3
"""
Messung der Anweisungen mit dem echten Modell, aber ohne API-Schlüssel: über das Kommandozeilen-Programm
"claude" (Claude Code), das mit dem eigenen Abo angemeldet ist.

Wozu: `./gradlew :core:evalLive` braucht einen API-Schlüssel. Wer nur ein Abo hat, kann mit diesem Skript
trotzdem dieselben Fälle an dasselbe Modell schicken. Jeder Fall ist ein eigener Aufruf mit genau dem
System-Prompt der App und genau der Nutzer-Nachricht, die die App schicken würde, ohne Werkzeuge und ohne
Nachdenkzeit (wie in der App).

Was es NICHT ist: der Aufruf der App selbst. Es fehlen deren Feinheiten (Zwischenspeicher, Sparstufe,
Ersatzmodell), und "claude" kann dem Prompt eigene Zeilen hinzufügen. Die Zahlen liegen deshalb nah an
der Wahrheit, sind aber nicht die Wahrheit. Die liefert nur `:core:evalLive`.

Ablauf:
    ./gradlew :core:evalDump                      # schreibt core/build/eval/{system.txt,cases/...}
    python3 tools/eval_cli.py <lauf>              # schickt alle Fälle, schreibt out/<lauf>/<modus>/<id>.txt
    python3 tools/eval_score.py <lauf>            # wertet aus

Ein abgebrochener Lauf lässt sich einfach neu starten: Was schon da ist, wird übersprungen.
"""
import argparse
import os
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor


def main() -> int:
    ap = argparse.ArgumentParser(description="Testfälle über das Programm 'claude' an das echte Modell schicken.")
    ap.add_argument("run", help="Name des Laufs (Ordner unter out/)")
    ap.add_argument("--eval-dir", default="core/build/eval", help="Ordner von evalDump (Standard: core/build/eval)")
    ap.add_argument("--system-file", help="anderer System-Prompt als system.txt (zum Vergleichen zweier Fassungen)")
    ap.add_argument("--modes", help="nur diese Modi, mit Komma getrennt (Standard: alle vorhandenen)")
    ap.add_argument("--only", help="nur Fälle, deren ID so beginnt (mehrere mit Komma)")
    ap.add_argument("--ids-file", help="Datei mit einer Fall-ID je Zeile")
    ap.add_argument("--model", default="claude-sonnet-5-5", help="Modell (Vorprüfung: claude-haiku-5-5)")
    ap.add_argument("--workers", type=int, default=6, help="gleichzeitige Aufrufe")
    ap.add_argument("--timeout", type=int, default=120, help="Sekunden je Aufruf")
    args = ap.parse_args()

    base = os.path.abspath(args.eval_dir)
    system = open(args.system_file or os.path.join(base, "system.txt"), encoding="utf-8").read()
    cases_dir = os.path.join(base, "cases")
    modes = args.modes.split(",") if args.modes else sorted(os.listdir(cases_dir))
    prefixes = args.only.split(",") if args.only else None
    wanted = None
    if args.ids_file:
        wanted = {line.strip() for line in open(args.ids_file, encoding="utf-8") if line.strip()}

    jobs = []
    for mode in modes:
        for name in sorted(os.listdir(os.path.join(cases_dir, mode))):
            case_id = name[:-4]
            if not name.endswith(".txt"):
                continue
            if prefixes and not any(case_id.startswith(p) for p in prefixes):
                continue
            if wanted is not None and case_id not in wanted:
                continue
            target = os.path.join(base, "out", args.run, mode, name)
            if not os.path.exists(target):
                jobs.append((mode, case_id, target))
    print(f"{args.run}: {len(jobs)} Aufrufe offen ({', '.join(modes)}), Modell {args.model}", flush=True)

    # Ein leerer Ordner als Arbeitsverzeichnis: "claude" liest sonst Projektdateien (CLAUDE.md) mit ein.
    empty = os.path.join(base, "empty")
    os.makedirs(empty, exist_ok=True)
    env = dict(os.environ, MAX_THINKING_TOKENS="0")

    def one(job):
        mode, case_id, target = job
        user = open(os.path.join(cases_dir, mode, case_id + ".txt"), encoding="utf-8").read()
        last = ""
        for attempt in range(3):
            try:
                p = subprocess.run(
                    ["claude", "-p", "--model", args.model, "--system-prompt", system, "--tools", "",
                     "--strict-mcp-config", "--no-session-persistence", "--setting-sources", "", user],
                    cwd=empty, env=env, capture_output=True, text=True, timeout=args.timeout,
                )
            except subprocess.TimeoutExpired:
                last = "Zeit abgelaufen"
                continue
            if p.returncode == 0 and p.stdout.strip():
                os.makedirs(os.path.dirname(target), exist_ok=True)
                with open(target, "w", encoding="utf-8") as f:
                    f.write(p.stdout.rstrip("\n"))
                return None
            last = f"rc={p.returncode} {p.stderr.strip()[:160]}"
            time.sleep(5 * (attempt + 1))  # meist ein Ratenlimit: kurz warten
        return f"{mode}/{case_id}: {last}"

    done = 0
    failed = []
    started = time.time()
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        for result in pool.map(one, jobs):
            done += 1
            if result:
                failed.append(result)
            if done % 50 == 0 or done == len(jobs):
                print(f"  {done}/{len(jobs)} nach {int(time.time() - started)} s, Fehler: {len(failed)}", flush=True)
    for line in failed:
        print("FEHLER " + line)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
