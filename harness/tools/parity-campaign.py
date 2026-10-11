#!/usr/bin/env python3
"""Unattended Tier-R parity campaign: many fresh seeds and windows, pipelined.

For every window: capture a fresh Vanilla trace (structures on), then run the
strict ordered replay (FeaturesTraceReplay --structures) and the native live gate
(LiveStructuresTraceCheck --replay-chunk-loads --replay-lighting). The capture of
the next window runs while the previous window is being checked. A window passes
only if both checks exit 0 and print their PASS verdict with 0 diffs; its trace is
then deleted to keep disk bounded. Failed windows keep their trace.

Start it at below-normal priority so the machine stays usable; children inherit it:
    start /belownormal /wait /b cmd /c "python parity-campaign.py --out D:\\camp ..."

Results: <out>/campaign.tsv (one row per window) and <out>/campaign.log.
"""
import argparse
import os
import random
import re
import secrets
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path

STRICT_MAIN = "dev.kestrel.worldgen.FeaturesTraceReplay"
LIVE_MAIN = "dev.kestrel.worldgen.LiveStructuresTraceCheck"
TIER_R_RE = re.compile(r"TIER_R ring=2 scoredChunks=(\d+) exact=(\d+) .*cells=(\d+) diffs=(\d+)")
LIVE_RE = re.compile(r"LiveStructuresTraceCheck: PASSED \((\d+) chunks, (\d+) cells\)")
HEADER = ["name", "seed", "min_x", "min_z", "size", "trace_startup", "capture_s", "capture_rc",
          "strict_rc", "strict_scored", "strict_exact", "strict_diffs", "strict_s",
          "live_rc", "live_scored", "live_s", "verdict"]

LOCK = threading.Lock()


def log(out, msg):
    line = time.strftime("%Y-%m-%d %H:%M:%S ") + msg
    with LOCK:
        print(line, flush=True)
        with open(out / "campaign.log", "a") as f:
            f.write(line + "\n")


def plan_windows(count, size, rng):
    """Spread windows over origin, near, mid and far distances (chunk coordinates)."""
    bands = [(0, 0), (100, 2_000), (2_000, 60_000), (60_000, 1_800_000)]
    plans = []
    for i in range(count):
        if i == 0 or i % 6 == 3:
            # origin: covers the spawn area, needs startup chunks in the trace
            plans.append((-size // 2, -size // 2, True))
            continue
        lo, hi = bands[1 + (i % 3)]
        r = rng.randint(lo, hi)
        sx, sz = rng.choice((-1, 1)), rng.choice((-1, 1))
        if rng.random() < 0.5:
            x, z = sx * r, sz * rng.randint(0, r)
        else:
            x, z = sx * rng.randint(0, r), sz * r
        plans.append((x - size // 2, z - size // 2, False))
    return plans


def capture(args, name, seed, x, z, trace_startup):
    work = args.out / name
    cmd = [sys.executable, str(args.capture), "--java", str(args.java), "--vanilla-root",
           str(args.vanilla_root), "--agent", str(args.agent), "--work", str(work),
           "--accept-eula", "--seed", str(seed), "--structures", "--timeout", str(args.capture_timeout),
           "--chunk-window", str(x), str(z), str(args.size), str(args.size)]
    if trace_startup:
        cmd.append("--trace-startup")
    t = time.time()
    with open(args.out / (name + ".capture.log"), "w") as lf:
        rc = subprocess.call(cmd, stdout=lf, stderr=subprocess.STDOUT)
    return rc, round(time.time() - t)


def check(args, name, main, extra):
    trace = args.out / name / "trace"
    cp = [str(args.classes), str(args.vanilla_jar)] + [
        str(p) for p in sorted((args.vanilla_root / "META-INF" / "libraries").rglob("*.jar"))]
    cmd = [str(args.java), "-Xms512m", "-Xmx" + args.heap, "-Dkestrel.datapack=" + str(args.datapack),
           "-cp", os.pathsep.join(cp), main, str(trace)] + extra
    tag = "strict" if main == STRICT_MAIN else "live"
    logf = args.out / (name + "." + tag + ".log")
    t = time.time()
    with open(logf, "w") as lf:
        rc = subprocess.call(cmd, stdout=lf, stderr=subprocess.STDOUT)
    text = logf.read_text(errors="replace")
    return rc, text, round(time.time() - t)


def judge(src, stext, lrc, ltext):
    """The PASS rule for one window's two gate runs (also used by parity-reverify.py).

    Returns (fields, verdict): the parsed strict/live counts and "PASS" or "FAIL".
    """
    m = TIER_R_RE.search(stext)
    lm = LIVE_RE.search(ltext)
    fields = dict(strict_scored=m.group(1) if m else "", strict_exact=m.group(2) if m else "",
                  strict_diffs=m.group(4) if m else "", live_scored=lm.group(1) if lm else "")
    strict_ok = (src == 0 and m is not None and m.group(1) == m.group(2) and m.group(4) == "0"
                 and int(m.group(1)) > 0 and "TIER_R VERDICT: PASS" in stext)
    live_ok = lrc == 0 and lm is not None and int(lm.group(1)) > 0
    return fields, "PASS" if strict_ok and live_ok else "FAIL"


def verify(args, row, results):
    name = row["name"]
    out = {}

    def run(tag, main, extra):
        out[tag] = check(args, name, main, extra)

    live_args = ["--replay-chunk-loads", "--replay-lighting"] + list(getattr(args, "live_args", None) or [])
    threads = [threading.Thread(target=run, args=("strict", STRICT_MAIN, ["--structures"])),
               threading.Thread(target=run, args=("live", LIVE_MAIN, live_args))]
    for th in threads:
        th.start()
    for th in threads:
        th.join()
    src, stext, ss = out["strict"]
    lrc, ltext, ls = out["live"]
    fields, verdict = judge(src, stext, lrc, ltext)
    row.update(strict_rc=src, strict_s=ss, live_rc=lrc, live_s=ls, **fields)
    row["verdict"] = verdict
    log(args.out, f"{name} strict rc={src} {row['strict_exact']}/{row['strict_scored']} "
                  f"diffs={row['strict_diffs']} {ss}s | live rc={lrc} scored={row['live_scored']} "
                  f"{ls}s -> {row['verdict']}")
    if row["verdict"] == "PASS" and not args.keep_traces:
        shutil.rmtree(args.out / name / "trace", ignore_errors=True)
        shutil.rmtree(args.out / name / "world", ignore_errors=True)
    write_row(args.out, row)
    results.append(row)


def write_row(out, row):
    path = out / "campaign.tsv"
    with LOCK:
        new = not path.exists()
        with open(path, "a") as f:
            if new:
                f.write("\t".join(HEADER) + "\n")
            f.write("\t".join(str(row.get(k, "")) for k in HEADER) + "\n")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--java", type=Path, required=True)
    p.add_argument("--vanilla-root", type=Path, required=True)
    p.add_argument("--agent", type=Path, required=True)
    p.add_argument("--classes", type=Path, required=True, help="frozen Kestrel core classes dir")
    p.add_argument("--datapack", type=Path, required=True, help="vanilla data dump (kestrel.datapack)")
    p.add_argument("--capture", type=Path, default=Path(__file__).with_name("capture-features-order.py"))
    p.add_argument("--hours", type=float, default=10.0, help="stop starting captures after this")
    p.add_argument("--max-windows", type=int, default=40)
    p.add_argument("--size", type=int, default=64, help="window edge in chunks")
    p.add_argument("--heap", default="4G")
    p.add_argument("--capture-timeout", type=int, default=7200)
    p.add_argument("--min-free-gb", type=float, default=60.0)
    p.add_argument("--keep-traces", action="store_true")
    p.add_argument("--plan-seed", type=int, default=None, help="reproduce a window plan")
    p.add_argument("--live-arg", dest="live_args", action="append", default=[],
                   help="extra live-gate argument, repeatable (e.g. --live-arg=--carver-threads=3 "
                        "to gate the pooled Step 10 path)")
    args = p.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    args.vanilla_jar = args.vanilla_root / "META-INF" / "versions" / "26.2" / "server-26.2.jar"
    plan_seed = args.plan_seed if args.plan_seed is not None else secrets.randbits(32)
    rng = random.Random(plan_seed)
    plans = plan_windows(args.max_windows, args.size, rng)
    deadline = time.time() + args.hours * 3600
    log(args.out, f"campaign start plan_seed={plan_seed} size={args.size} hours={args.hours} "
                  f"classes={args.classes} live_args={args.live_args}")
    results, pending, capture_failures = [], None, 0
    for i, (x, z, startup) in enumerate(plans):
        if time.time() > deadline:
            log(args.out, "deadline reached; no new captures")
            break
        free = shutil.disk_usage(args.out).free / 1e9
        if free < args.min_free_gb:
            log(args.out, f"only {free:.0f} GB free; no new captures")
            break
        seed = secrets.randbits(63) * (1 if secrets.randbits(1) else -1)
        name = f"w{i:02d}"
        log(args.out, f"{name} capture seed={seed} window={x},{z} {args.size}x{args.size} startup={startup}")
        row = dict(name=name, seed=seed, min_x=x, min_z=z, size=args.size, trace_startup=startup)
        rc, secs = capture(args, name, seed, x, z, startup)
        row.update(capture_rc=rc, capture_s=secs)
        log(args.out, f"{name} capture rc={rc} {secs}s")
        if pending:
            pending.join()
        if rc != 0:
            row["verdict"] = "CAPTURE_FAIL"
            write_row(args.out, row)
            results.append(row)
            pending = None
            capture_failures += 1
            if capture_failures >= 2:
                log(args.out, "two consecutive capture failures; stopping (setup problem?)")
                break
            continue
        capture_failures = 0
        pending = threading.Thread(target=verify, args=(args, row, results))
        pending.start()
    if pending:
        pending.join()
    passed = [r for r in results if r["verdict"] == "PASS"]
    chunks = sum(int(r["strict_scored"] or 0) for r in passed)
    log(args.out, f"campaign done windows={len(results)} pass={len(passed)} "
                  f"fail={len(results) - len(passed)} scoredChunksPassed={chunks}")
    raise SystemExit(0 if results and len(passed) == len(results) else 1)


if __name__ == "__main__":
    main()
