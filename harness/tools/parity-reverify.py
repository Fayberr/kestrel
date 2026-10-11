#!/usr/bin/env python3
"""Re-run both parity gates on a campaign's retained windows with newer classes.

parity-campaign.py keeps the trace of every window that did not pass. Once a fix
lands, this reruns the strict and live gates on those traces with a new frozen
classes dir: the same gate commands, the same verdict rule (parity-campaign.judge)
and the same concurrency (strict and live of one window in parallel). Captures
are never redone, since the trace is the evidence.

Each campaign dir gets reverify.tsv (one row per window) and reverify.log. The
gate logs are written as wNN.<strict|live>.reverify-<classes>.log, so the
campaign's original wNN.strict.log / wNN.live.log are never overwritten. A window
that now passes has its trace and world deleted (the campaign's own policy)
unless --keep-traces is given.

Do not point it at a campaign that is still capturing. Run below normal priority:
    start /belownormal /wait /b cmd /c "python parity-reverify.py --classes ... C:\\bench\\camp1"
"""
import argparse
import importlib.util
import os
import re
import shutil
import subprocess
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

_spec = importlib.util.spec_from_file_location(
    "parity_campaign", Path(__file__).resolve().with_name("parity-campaign.py"))
pc = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(pc)

HEADER = ["name", "classes", "strict_rc", "strict_scored", "strict_exact", "strict_diffs", "strict_s",
          "live_rc", "live_scored", "live_s", "verdict", "trace_deleted"]
LOCK = threading.Lock()


def log(camp, msg):
    line = time.strftime("%Y-%m-%d %H:%M:%S ") + msg
    with LOCK:
        print(line, flush=True)
        with open(camp / "reverify.log", "a") as f:
            f.write(line + "\n")


def gate(args, window, tag, main, extra):
    """One gate run; the command line is parity-campaign.check()'s, only the log name differs."""
    cp = [str(args.classes), str(args.vanilla_jar)] + [
        str(p) for p in sorted((args.vanilla_root / "META-INF" / "libraries").rglob("*.jar"))]
    cmd = [str(args.java), "-Xms512m", "-Xmx" + args.heap, "-Dkestrel.datapack=" + str(args.datapack),
           "-cp", os.pathsep.join(cp), main, str(window / "trace")] + extra
    logf = window.parent / f"{window.name}.{tag}.reverify-{args.classes.name}.log"
    t = time.time()
    with open(logf, "w") as lf:
        rc = subprocess.call(cmd, stdout=lf, stderr=subprocess.STDOUT)
    return rc, logf.read_text(errors="replace"), round(time.time() - t)


def reverify(args, window):
    camp = window.parent
    log(camp, f"{window.name} reverify start classes={args.classes.name}")
    out = {}

    def run(tag, main, extra):
        out[tag] = gate(args, window, tag, main, extra)

    threads = [threading.Thread(target=run, args=("strict", pc.STRICT_MAIN, ["--structures"])),
               threading.Thread(target=run, args=("live", pc.LIVE_MAIN,
                                                  ["--replay-chunk-loads", "--replay-lighting"]))]
    for th in threads:
        th.start()
    for th in threads:
        th.join()
    src, stext, ss = out["strict"]
    lrc, ltext, ls = out["live"]
    fields, verdict = pc.judge(src, stext, lrc, ltext)
    row = dict(name=window.name, classes=args.classes.name, strict_rc=src, strict_s=ss,
               live_rc=lrc, live_s=ls, verdict=verdict, trace_deleted=False, **fields)
    if verdict == "PASS" and not args.keep_traces:
        shutil.rmtree(window / "trace", ignore_errors=True)
        shutil.rmtree(window / "world", ignore_errors=True)
        row["trace_deleted"] = not (window / "trace").exists()
    log(camp, f"{window.name} strict rc={src} {row['strict_exact']}/{row['strict_scored']} "
              f"diffs={row['strict_diffs']} {ss}s | live rc={lrc} scored={row['live_scored']} "
              f"{ls}s -> {verdict}{' (trace deleted)' if row['trace_deleted'] else ''}")
    path = camp / "reverify.tsv"
    with LOCK:
        new = not path.exists()
        with open(path, "a") as f:
            if new:
                f.write("\t".join(HEADER) + "\n")
            f.write("\t".join(str(row.get(k, "")) for k in HEADER) + "\n")
    return row


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("campaigns", nargs="+", type=Path, help="campaign --out dirs")
    p.add_argument("--java", type=Path, required=True)
    p.add_argument("--vanilla-root", type=Path, required=True)
    p.add_argument("--classes", type=Path, required=True, help="frozen Kestrel core classes dir")
    p.add_argument("--datapack", type=Path, required=True, help="vanilla data dump (kestrel.datapack)")
    p.add_argument("--heap", default="4G")
    p.add_argument("--only", nargs="*", default=None,
                   help="only these windows, in this order, as <campaign dir name>/<window>, e.g. camp1/w03")
    p.add_argument("--parallel", type=int, default=1, help="windows at once (each runs two JVMs)")
    p.add_argument("--keep-traces", action="store_true")
    args = p.parse_args()
    args.vanilla_jar = args.vanilla_root / "META-INF" / "versions" / "26.2" / "server-26.2.jar"
    todo = []
    for camp in args.campaigns:
        for w in sorted(camp.iterdir()):
            if not (w.is_dir() and re.fullmatch(r"w\d\d", w.name) and (w / "trace").is_dir()):
                continue
            if args.only is not None and f"{camp.name}/{w.name}" not in args.only:
                continue
            todo.append(w)
    if args.only is not None:
        # --only also sets the order, e.g. likely passes first to free disk early
        todo.sort(key=lambda w: args.only.index(f"{w.parent.name}/{w.name}"))
    for camp in args.campaigns:
        mine = [w.name for w in todo if w.parent == camp]
        log(camp, f"reverify plan classes={args.classes} windows={' '.join(mine) or '-'}")
    with ThreadPoolExecutor(max_workers=max(1, args.parallel)) as ex:
        rows = list(ex.map(lambda w: reverify(args, w), todo))
    for camp in args.campaigns:
        mine = [r for w, r in zip(todo, rows) if w.parent == camp]
        ok = sum(1 for r in mine if r["verdict"] == "PASS")
        chunks = sum(int(r["strict_scored"] or 0) for r in mine if r["verdict"] == "PASS")
        log(camp, f"reverify done windows={len(mine)} pass={ok} fail={len(mine) - ok} "
                  f"scoredChunksPassed={chunks}")
    raise SystemExit(0 if rows and all(r["verdict"] == "PASS" for r in rows) else 1)


if __name__ == "__main__":
    main()
