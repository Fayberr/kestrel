#!/usr/bin/env python3
"""Run one fresh, disposable 26.2 vanilla instance and capture FEATURES order.

Requires an already accepted Minecraft EULA; --accept-eula is explicit.
Never points at an existing world or a live server. No remote console/network API.
Use --structures to capture the same oracle with generate-structures=true.
Use --chunk-window MIN_X MIN_Z WIDTH HEIGHT for a tiled scale capture. Tiles are
loaded serially and never exceed vanilla's 256-forceload-chunk command limit.
Use --trace-startup for windows near the world spawn: it arms the trace before
Vanilla boots, so chunks generated while selecting the world spawn are recorded
instead of appearing later as untraced, already-advanced chunks.
"""
import argparse
import hashlib
import json
import os
from collections import namedtuple
from pathlib import Path
import subprocess
import time

SEED = 3527749958874813416
TARGETS = [(-6, -6), (-6, 6), (-5, 6)]
COMMANDS = ["forceload add -96 -96", "forceload add -96 96", "forceload add -80 96"]
Batch = namedtuple("Batch", "required add_command remove_command")


def plan_batches(min_x, min_z, width, height):
    """Tile a chunk window into disjoint commands within vanilla's 256-chunk limit."""
    if width <= 0 or height <= 0:
        raise ValueError("capture window dimensions must be positive")
    batches = []
    for z0 in range(min_z, min_z + height, 16):
        z1 = min(z0 + 15, min_z + height - 1)
        for x0 in range(min_x, min_x + width, 16):
            x1 = min(x0 + 15, min_x + width - 1)
            required = frozenset((x, z) for x in range(x0, x1 + 1) for z in range(z0, z1 + 1))
            corners = f"{x0 * 16} {z0 * 16} {x1 * 16} {z1 * 16}"
            batches.append(Batch(required, f"forceload add {corners}", f"forceload remove {corners}"))
    return batches


def legacy_batches():
    return [Batch(frozenset((x + dx, z + dz)
                            for dx in range(-1, 2) for dz in range(-1, 2)), command, None)
            for (x, z), command in zip(TARGETS, COMMANDS)]


def scored_interior_count(origins, ring):
    return sum(all((x + dx, z + dz) in origins
                   for dx in range(-ring, ring + 1) for dz in range(-ring, ring + 1))
               for x, z in origins)


def stop_process(proc, polite_timeout=10):
    """Best-effort bounded shutdown; never leave Ctrl+C stuck in wait()."""
    if proc.poll() is not None:
        return
    try:
        proc.stdin.write("stop\n")
        proc.stdin.flush()
    except (BrokenPipeError, OSError):
        pass
    try:
        proc.wait(timeout=polite_timeout)
        return
    except subprocess.TimeoutExpired:
        pass
    proc.terminate()
    try:
        proc.wait(timeout=5)
        return
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait(timeout=5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--vanilla-root", type=Path, required=True)
    parser.add_argument("--agent", type=Path, required=True)
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--accept-eula", action="store_true", required=True)
    parser.add_argument("--timeout", type=int, default=900)
    parser.add_argument("--seed", type=int, default=3527749958874813416,
                        help="world seed (default: legacy Tier-R seed)")
    parser.add_argument("--structures", action="store_true",
                        help="capture a structures-on Vanilla world instead of the legacy structures-off oracle")
    parser.add_argument("--dimension", choices=("overworld", "nether", "end"), default="overworld",
                        help="dimension whose FEATURES order and snapshots are captured")
    parser.add_argument("--chunk-window", nargs=4, type=int,
                        metavar=("MIN_X", "MIN_Z", "WIDTH", "HEIGHT"),
                        help="capture a tiled chunk rectangle instead of the three diagnostic targets")
    parser.add_argument("--trace-startup", action="store_true",
                        help="arm the trace before boot so spawn-selection chunk generation is recorded "
                             "(needed when the window overlaps chunks Vanilla generates at startup)")
    parser.add_argument("--allow-no-tier-r", action="store_true",
                        help="diagnostic only: permit a chunk window with no ring-2 Tier-R interior")
    args = parser.parse_args()
    work = args.work.resolve()
    work.mkdir(parents=True, exist_ok=False)
    vanilla = args.vanilla_root.resolve()
    jar = vanilla / "META-INF/versions/26.2/server-26.2.jar"
    classpath = os.pathsep.join([str(jar)] + [str(p) for p in sorted(vanilla.glob("META-INF/libraries/**/*.jar"))])
    properties = "\n".join([
        f"level-seed={args.seed}", "level-name=world", "level-type=minecraft:normal",
        f"generate-structures={'true' if args.structures else 'false'}",
        "online-mode=false", "server-ip=127.0.0.1",
        "server-port=0", "view-distance=2", "simulation-distance=2",
        "spawn-chunk-radius=0", "max-tick-time=-1", "pause-when-empty-seconds=-1",
        "enable-rcon=false", "enable-query=false", "spawn-protection=0", "",
    ])
    (work / "server.properties").write_text(properties)
    (work / "eula.txt").write_text("eula=true\n")
    arm_file = work / "trace.arm"
    command = [str(args.java.resolve()), "-Xms256m", "-Xmx2G", "-XX:ActiveProcessorCount=2",
               "-Dmax.bg.threads=1", f"-Dkestrel.featuresTrace={work / 'trace'}",
               f"-Dkestrel.featuresTraceArm={arm_file}",
               f"-javaagent:{args.agent.resolve()}", "-cp", classpath,
               "net.minecraft.server.Main", "--nogui"]
    if args.chunk_window:
        batches = plan_batches(*args.chunk_window)
        requested = set().union(*(batch.required for batch in batches))
        if scored_interior_count(requested, 2) == 0 and not args.allow_no_tier_r:
            raise ValueError("chunk window has no ring-2 interior and cannot produce a Tier R score; "
                             "use --allow-no-tier-r only for targeted diagnostics")
    else:
        batches = legacy_batches()
        requested = set(TARGETS)
    dimension_id = {"overworld": "minecraft:overworld", "nether": "minecraft:the_nether",
                    "end": "minecraft:the_end"}[args.dimension]
    def in_dimension(command):
        return command if args.dimension == "overworld" else f"execute in {dimension_id} run {command}"
    console_commands = [in_dimension(command) for batch in batches
                        for command in (batch.add_command, batch.remove_command) if command]
    metadata = {"seed": str(args.seed), "targets": sorted(requested), "command": command,
                "console_commands": console_commands, "properties": properties,
                "generate_structures": args.structures,
                "dimension": args.dimension,
                "allow_no_tier_r": args.allow_no_tier_r,
                "requested_ring2_interior": scored_interior_count(requested, 2),
                "vanilla_sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
                "agent_sha256": hashlib.sha256(args.agent.read_bytes()).hexdigest(),
                "trace_arm_file": str(arm_file),
                "trace_startup": args.trace_startup,
                "status": "incomplete"}
    (work / "run.json").write_text(json.dumps(metadata, indent=2))
    if args.trace_startup:
        arm_file.touch(exist_ok=False)
    deadline = time.monotonic() + args.timeout
    batch_index = -1
    quiet_since = None
    last_size = -1
    last_progress = 0.0
    try:
        console_log = work / "console.log"
        print(f"[capture] seed={args.seed} structures={'on' if args.structures else 'off'} "
              f"trace_startup={'on' if args.trace_startup else 'off'} "
              f"requested_centers={len(requested)} ring2_interior={metadata['requested_ring2_interior']}", flush=True)
        print(f"[capture] vanilla console: {console_log}", flush=True)
        with console_log.open("w") as log:
            proc = subprocess.Popen(command, cwd=work, stdin=subprocess.PIPE, stdout=log,
                                    stderr=subprocess.STDOUT, text=True)
            print(f"[capture] vanilla pid={proc.pid}; waiting for server readiness", flush=True)
            try:
                while time.monotonic() < deadline:
                    if proc.poll() is not None:
                        raise RuntimeError(f"vanilla exited early: {proc.returncode}; inspect console.log")
                    if batch_index < 0 and 'Done (' in console_log.read_text():
                        # Do not trace Vanilla's startup/spawn preparation. It is
                        # outside the requested replay window and makes the heavy
                        # 3x3 snapshot observer dominate boot time. Arm only at
                        # the stable server-ready boundary, immediately before
                        # the first requested forceload.
                        # With --trace-startup the arm file already exists and
                        # the startup chunks are part of the recorded order.
                        # Freeze random block mutation before requested terrain
                        # enters the pipeline. Otherwise a Nether fire or lava
                        # tick can change the final light engine after the
                        # FEATURES block snapshot was captured.
                        proc.stdin.write("gamerule randomTickSpeed 0\n")
                        # Scheduled fluid ticks are independent of randomTickSpeed.
                        # Freeze level ticking before requested generation so the
                        # final light snapshot remains a terrain-generation cut,
                        # not a later lava-flow state.
                        proc.stdin.write("tick freeze\n")
                        arm_file.touch(exist_ok=args.trace_startup)
                        deadline = time.monotonic() + args.timeout
                        batch_index = 0
                        proc.stdin.write(in_dimension(batches[batch_index].add_command) + "\n")
                        proc.stdin.flush()
                        print(f"[capture] vanilla ready; started batch 1/{len(batches)} "
                              f"({len(batches[0].required)} required centers)", flush=True)
                    trace = work / "trace/events.tsv"
                    spawn_trace = work / "trace/spawn-data.tsv"
                    if batch_index >= 0 and trace.exists() and spawn_trace.exists():
                        lines = trace.read_text().splitlines()[1:]
                        completed = {(int(r[3]), int(r[4])) for line in lines
                                     if len(r := line.split("\t")) == 11 and r[1] == "COMPLETE"
                                     and dimension_id in r[6]}
                        spawn_lines = spawn_trace.read_text().splitlines()[1:]
                        spawn_completed = {
                            (int(r[8]), int(r[9])) for line in spawn_lines
                            if len(r := line.split("\t", 14)) == 15
                            and dimension_id in r[11]
                        }
                        size = trace.stat().st_size + spawn_trace.stat().st_size
                        batch = batches[batch_index]
                        now = time.monotonic()
                        done_in_batch = len(batch.required & completed)
                        spawn_done_in_batch = len(batch.required & spawn_completed)
                        batch_complete = (
                            batch.required <= completed
                            and batch.required <= spawn_completed
                        )
                        if now - last_progress >= 5:
                            print(f"[capture] batch {batch_index + 1}/{len(batches)} "
                                  f"features={done_in_batch}/{len(batch.required)} "
                                  f"spawn={spawn_done_in_batch}/{len(batch.required)} "
                                  f"total_features={len(completed)} total_spawn={len(spawn_completed)} "
                                  f"trace_rows={len(lines)} spawn_rows={len(spawn_lines)}", flush=True)
                            last_progress = now
                        if batch_complete and batch_index + 1 < len(batches):
                            if batch.remove_command:
                                proc.stdin.write(in_dimension(batch.remove_command) + "\n")
                            batch_index += 1
                            proc.stdin.write(in_dimension(batches[batch_index].add_command) + "\n")
                            proc.stdin.flush()
                            print(f"[capture] started batch {batch_index + 1}/{len(batches)} "
                                  f"({len(batches[batch_index].required)} required centers)", flush=True)
                            quiet_since = None
                        elif batch_complete and size == last_size:
                            quiet_since = quiet_since or time.monotonic()
                            if time.monotonic() - quiet_since >= 10:
                                print("[capture] requested FEATURES+SPAWN coverage complete and trace quiescent", flush=True)
                                break
                        else:
                            quiet_since = None
                        last_size = size
                    time.sleep(1)
                else:
                    raise TimeoutError("target FEATURES+SPAWN coverage did not complete before timeout")
                stop_process(proc)
                if proc.returncode != 0:
                    raise RuntimeError(f"vanilla shutdown failed: {proc.returncode}")
                metadata["status"] = "captured; validate events and replay before claiming parity"
                print("[capture] vanilla stopped cleanly", flush=True)
            except KeyboardInterrupt:
                metadata["status"] = "interrupted by user"
                print("\n[capture] interrupted; stopping vanilla process", flush=True)
                stop_process(proc, polite_timeout=3)
                raise
            finally:
                if proc.poll() is None:
                    stop_process(proc, polite_timeout=3)
    finally:
        (work / "run.json").write_text(json.dumps(metadata, indent=2))
    print(work / "trace/events.tsv")


if __name__ == "__main__":
    main()
