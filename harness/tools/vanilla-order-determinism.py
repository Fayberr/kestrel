#!/usr/bin/env python3
"""Control experiment: is VANILLA's own world generation order-independent?

Background (preresearch/g2-feature-order-notes.md section 3): vanilla decides
cross-chunk FEATURES order in ChunkGenerationTask.scheduleLayer, but a bulk
ticket add races many of those margin walks across several Worker-Main
threads, so the order vanilla actually decorates chunks in is decided by
thread scheduling and varies run to run. Kestrel's BatchParityGate measures
its OWN cross-order stability (99.66% of cells over two shuffled orders) and
we have been treating the shortfall as a Kestrel defect. That inference is
only valid if vanilla itself scores 100% on the same measurement, which has
never been checked.

This boots throwaway vanilla servers on one seed and generates the same chunk
square in deliberately different request orders, so the region files can be
diffed against each other (see VanillaSelfParityCheck). Never touches an
existing world and never talks to a running server: each run gets a fresh
working directory and binds an ephemeral port.

Modes (how the chunk square gets requested, NOT what gets generated):
  sweep         one forceload covering the whole square
  rows-reverse  one forceload per chunk row, issued from +z down to -z
  cols-forward  one forceload per chunk column, issued from -x up to +x
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import time
from pathlib import Path


def region_chunk_count(region_dir: Path) -> int:
    """Count present chunks across all .mca files by reading their headers."""
    total = 0
    if not region_dir.is_dir():
        return 0
    for mca in region_dir.glob("r.*.mca"):
        try:
            with mca.open("rb") as fh:
                header = fh.read(4096)
        except OSError:
            continue
        if len(header) < 4096:
            continue
        for i in range(1024):
            off = struct.unpack_from(">I", header, i * 4)[0]
            if off != 0:
                total += 1
    return total


def forceload_commands(mode: str, half: int) -> list[str]:
    """Chunk square [-half, half-1]^2 expressed as block-coordinate forceloads.

    ForceLoadCommand takes ColumnPos (block coords), converted with >>4.
    """
    lo_b = -half * 16
    hi_b = (half * 16) - 1
    if mode == "sweep":
        return [f"forceload add {lo_b} {lo_b} {hi_b} {hi_b}"]
    if mode == "rows-reverse":
        return [f"forceload add {lo_b} {cz * 16} {hi_b} {cz * 16 + 15}"
                for cz in range(half - 1, -half - 1, -1)]
    if mode == "cols-forward":
        return [f"forceload add {cx * 16} {lo_b} {cx * 16 + 15} {hi_b}"
                for cx in range(-half, half)]
    raise SystemExit(f"unknown mode: {mode}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--java", type=Path, default=Path("java"))
    ap.add_argument("--vanilla-root", type=Path, required=True)
    ap.add_argument("--work", type=Path, required=True,
                    help="fresh directory, must not already exist")
    ap.add_argument("--accept-eula", action="store_true", required=True)
    ap.add_argument("--seed", type=int, default=4242424242)
    ap.add_argument("--half", type=int, default=8,
                    help="chunk square is [-half, half-1] on both axes")
    ap.add_argument("--mode", default="sweep",
                    choices=["sweep", "rows-reverse", "cols-forward"])
    ap.add_argument("--level-type", default="minecraft:normal")
    ap.add_argument("--quiet-polls", type=int, default=3,
                    help="consecutive stable chunk counts that mean 'done'")
    ap.add_argument("--poll-seconds", type=int, default=20)
    ap.add_argument("--timeout", type=int, default=1800)
    ap.add_argument("--freeze-ticks", action="store_true",
                    help="set randomTickSpeed 0 and friends before generating, so the "
                         "diff measures generation order and not idle random ticks")
    args = ap.parse_args()

    work = args.work.resolve()
    work.mkdir(parents=True, exist_ok=False)
    vanilla = args.vanilla_root.resolve()
    jar = vanilla / "META-INF/versions/26.2/server-26.2.jar"
    if not jar.is_file():
        raise SystemExit(f"vanilla server jar not found: {jar}")
    libs = sorted(vanilla.glob("META-INF/libraries/**/*.jar"))
    classpath = os.pathsep.join([str(jar)] + [str(p) for p in libs])

    # generate-structures=false matches how Kestrel generates, so the diff
    # isolates terrain+features and never blames a structure for a mismatch.
    properties = "\n".join([
        f"level-seed={args.seed}", "level-name=world",
        f"level-type={args.level_type}", "generate-structures=false",
        "online-mode=false", "server-ip=127.0.0.1", "server-port=0",
        "view-distance=2", "simulation-distance=2", "spawn-chunk-radius=0",
        "max-tick-time=-1", "pause-when-empty-seconds=-1", "enable-rcon=false",
        "enable-query=false", "spawn-protection=0", "sync-chunk-writes=true",
        "spawn-monsters=false", "spawn-npcs=false", "", ])
    (work / "server.properties").write_text(properties)
    (work / "eula.txt").write_text("eula=true\n")

    command = [str(args.java), "-Xms512m", "-Xmx3G", "-cp", classpath,
               "net.minecraft.server.Main", "--nogui"]
    meta = {"seed": args.seed, "half": args.half, "mode": args.mode,
            "level_type": args.level_type, "command": command,
            "freeze_ticks": args.freeze_ticks,
            "properties": properties,
            "vanilla_sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
            "status": "incomplete"}
    (work / "run.json").write_text(json.dumps(meta, indent=2))

    log_path = work / "console.log"
    # 26.2 stores the overworld under world/dimensions/minecraft/overworld/region.
    # Older layouts used world/region. Resolve lazily: the directory does not
    # exist until the first save, and counting the wrong path would report a
    # permanent zero and look exactly like "generation never happened".
    region_candidates = [
        work / "world" / "dimensions" / "minecraft" / "overworld" / "region",
        work / "world" / "region",
    ]

    def region_dir_now() -> Path:
        for cand in region_candidates:
            if cand.is_dir():
                return cand
        return region_candidates[0]

    started = time.time()
    with log_path.open("wb") as log:
        proc = subprocess.Popen(command, cwd=work, stdin=subprocess.PIPE,
                                stdout=log, stderr=subprocess.STDOUT)

        def send(line: str) -> None:
            proc.stdin.write((line + "\n").encode())
            proc.stdin.flush()

        # Wait for the server to finish booting.
        while True:
            if proc.poll() is not None:
                raise SystemExit(f"server exited early, see {log_path}")
            if time.time() - started > args.timeout:
                proc.kill()
                raise SystemExit("timed out waiting for server startup")
            text = log_path.read_text(errors="replace")
            if re.search(r'Done \(.*\)! For help', text):
                break
            time.sleep(2)
        print(f"[{args.mode}] server up after {time.time() - started:.1f}s", flush=True)

        # Freeze everything that mutates blocks for reasons other than world
        # generation. Forceloaded chunks are ticked even with no players
        # online, so a run that idles ~60s while generation settles will also
        # have run ~60s of random ticks (leaf decay, grass, fluids, dripstone)
        # driven by an unseeded Level.random. Two runs would then diverge for
        # reasons that have nothing to do with chunk order, inflating (or
        # entirely fabricating) the effect this probe exists to measure.
        # 26.2 renamed the gamerules to namespaced snake_case
        # (assets/minecraft/lang/deprecated.json "renamed":
        # gamerule.randomTickSpeed -> gamerule.minecraft.random_tick_speed).
        # The pre-26.2 camelCase names parse-fail with "Incorrect argument for
        # command", which the server reports on ITS stdout and which a naive
        # runner never notices: the first version of this script recorded
        # freeze_ticks=true in run.json while every single rule had been
        # rejected. Each rule is therefore verified against the console log,
        # and a rejected rule aborts the run rather than quietly producing a
        # world whose metadata lies about how it was made.
        if args.freeze_ticks:
            rules = ["gamerule minecraft:random_tick_speed 0",
                     "gamerule minecraft:spawn_mobs false",
                     "gamerule minecraft:advance_weather false",
                     "gamerule minecraft:advance_time false",
                     "gamerule minecraft:mob_drops false",
                     "gamerule minecraft:block_drops false",
                     "gamerule minecraft:mob_griefing false"]
            for rule in rules:
                before = log_path.stat().st_size
                send(rule)
                time.sleep(0.6)
                with log_path.open("r", errors="replace") as fh:
                    fh.seek(before)
                    reply = fh.read()
                if ("Incorrect argument" in reply or "Unknown or incomplete command" in reply
                        or "<--[HERE]" in reply):
                    proc.kill()
                    raise SystemExit(f"gamerule rejected by server: {rule!r}\n"
                                     f"server said: {reply.strip()[:400]}")
            print(f"[{args.mode}] froze ticks, {len(rules)} gamerules accepted", flush=True)

        cmds = forceload_commands(args.mode, args.half)
        for c in cmds:
            send(c)
            time.sleep(0.25)
        print(f"[{args.mode}] issued {len(cmds)} forceload command(s)", flush=True)

        # Generation is silent in the log, so quiescence is measured from the
        # region files themselves: flush, count, repeat until the count stops
        # moving. That also self-verifies the run actually produced chunks.
        stable = 0
        last = -1
        while stable < args.quiet_polls:
            if time.time() - started > args.timeout:
                proc.kill()
                raise SystemExit("timed out waiting for generation to settle")
            time.sleep(args.poll_seconds)
            send("save-all flush")
            time.sleep(3)
            count = region_chunk_count(region_dir_now())
            stable = stable + 1 if count == last and count > 0 else 0
            print(f"[{args.mode}] chunks on disk: {count} (stable {stable})", flush=True)
            last = count

        send("save-all flush")
        time.sleep(3)
        send("stop")
        try:
            proc.wait(timeout=180)
        except subprocess.TimeoutExpired:
            proc.kill()
            raise SystemExit("server did not stop cleanly")

    region_dir = region_dir_now()
    final = region_chunk_count(region_dir)
    if final == 0:
        raise SystemExit(f"run produced no chunks on disk under {region_dir}; refusing to "
                         "record a complete run that generated nothing")
    meta.update({"status": "complete", "chunks_on_disk": final,
                 "region_dir": str(region_dir),
                 "forceload_commands": cmds,
                 "elapsed_seconds": round(time.time() - started, 1)})
    (work / "run.json").write_text(json.dumps(meta, indent=2))
    print(f"[{args.mode}] DONE: {final} chunks in {region_dir}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
