#!/usr/bin/env python3
import os
import sys
import time
import subprocess
import shutil
import csv

def main():
    base_dir = os.path.abspath(os.path.dirname(__file__))
    worktree = os.path.abspath(os.path.join(base_dir, "../../../.."))
    vanilla_dir = os.path.join(base_dir, "vanilla-run")
    out_csv = os.path.join(base_dir, "speed-vanilla.csv")
    
    server_jar = os.environ.get("VANILLA_SERVER_JAR", os.path.join(worktree, "preresearch/vanilla-26.2/server.jar"))
    agent_jar = os.path.join(worktree, "harness/agent/build/libs/kestrel-harness-agent-0.1.0.jar")
    java_bin = os.environ.get("JAVA_BIN", shutil.which("java") or "java")
    
    print("=== Vanilla 26.2 Speed Benchmark on Pi (JDK 25) ===")
    print(f"Working directory: {vanilla_dir}")
    print(f"Server Jar:        {server_jar}")
    print(f"Agent Jar:         {agent_jar}")
    
    if os.path.exists(vanilla_dir):
        shutil.rmtree(vanilla_dir)
    os.makedirs(vanilla_dir, exist_ok=True)
    
    # 1. eula.txt
    with open(os.path.join(vanilla_dir, "eula.txt"), "w") as f:
        f.write("eula=true\n")
        
    # 2. server.properties
    props = """server-port=25588
online-mode=false
max-players=0
view-distance=10
spawn-monsters=false
spawn-animals=false
gamemode=creative
difficulty=peaceful
level-seed=4242424242
level-type=minecraft\\:normal
generate-structures=false
simulation-distance=10
pause-when-empty-seconds=0
"""
    with open(os.path.join(vanilla_dir, "server.properties"), "w") as f:
        f.write(props)
        
    # 3. Datapack: cgwatch forceload [-5..5] x [-5..5] (121 chunks)
    dp_func_dir = os.path.join(vanilla_dir, "world/datapacks/cgwatch/data/cgwatch/function")
    dp_tag_dir = os.path.join(vanilla_dir, "world/datapacks/cgwatch/data/minecraft/tags/function")
    os.makedirs(dp_func_dir, exist_ok=True)
    os.makedirs(dp_tag_dir, exist_ok=True)
    
    with open(os.path.join(vanilla_dir, "world/datapacks/cgwatch/pack.mcmeta"), "w") as f:
        f.write('{"pack":{"description":"chunkgen watch forceload","pack_format":81,"supported_formats":[17,81]}}\n')
        
    with open(os.path.join(dp_tag_dir, "load.json"), "w") as f:
        f.write('{"values":["cgwatch:init"]}\n')
        
    expected_targets = set()
    radius = 5
    with open(os.path.join(dp_func_dir, "init.mcfunction"), "w") as f:
        for x in range(-radius, radius + 1):
            for z in range(-radius, radius + 1):
                expected_targets.add((x, z))
                f.write(f"forceload add {x * 16} {z * 16}\n")
                
    print(f"Configured forceload for {len(expected_targets)} chunks ([-{radius}..{radius}] x [-{radius}..{radius}])...")
    
    ticks_log = os.path.join(vanilla_dir, "ticks.log")
    cg_csv = os.path.join(vanilla_dir, "chunkgen-watch.csv")
    stdout_log = os.path.join(vanilla_dir, "stdout.log")
    stderr_log = os.path.join(vanilla_dir, "stderr.log")
    
    cmd = [
        java_bin,
        "-Xms512M",
        "-Xmx1500M",
        "-Dkestrel.harness.perTickTraces=false",
        f"-javaagent:{agent_jar}={ticks_log}",
        "-jar",
        server_jar,
        "--nogui"
    ]
    
    print("Launching Vanilla 26.2 Server...")
    t_start = time.time()
    
    with open(stdout_log, "w") as out_f, open(stderr_log, "w") as err_f:
        proc = subprocess.Popen(
            cmd,
            cwd=vanilla_dir,
            stdin=subprocess.PIPE,
            stdout=out_f,
            stderr=err_f,
            text=True
        )
        
        # Poll for completion of all 121 chunks
        completed_targets = set()
        last_count = 0
        timeout = 180 # 3 minutes max
        
        while time.time() - t_start < timeout:
            time.sleep(2)
            if not os.path.exists(cg_csv):
                continue
            try:
                with open(cg_csv, "r") as f:
                    reader = csv.DictReader(f)
                    for row in reader:
                        cx = int(row["chunk_x"])
                        cz = int(row["chunk_z"])
                        if (cx, cz) in expected_targets:
                            completed_targets.add((cx, cz))
            except Exception:
                pass
                
            if len(completed_targets) != last_count:
                last_count = len(completed_targets)
                elapsed = time.time() - t_start
                print(f"  Target chunks completed: {last_count}/{len(expected_targets)} in {elapsed:.1f}s...")
                
            if len(completed_targets) >= len(expected_targets):
                print("All 121 target chunks have been generated!")
                break
                
            if proc.poll() is not None:
                print("Server exited unexpectedly!")
                break
                
        t_finish = time.time()
        wall_seconds = t_finish - t_start
        print(f"Stopping Vanilla Server (wall time: {wall_seconds:.2f}s)...")
        try:
            proc.stdin.write("stop\n")
            proc.stdin.flush()
            proc.wait(timeout=20)
        except Exception:
            proc.kill()
            
    print(f"Vanilla Server stopped. Total wall time: {wall_seconds:.2f}s.")
    
    # Analyze chunkgen-watch.csv
    if not os.path.exists(cg_csv):
        print("ERROR: chunkgen-watch.csv was not generated!")
        with open(stderr_log) as f:
            print("stderr:", f.read()[-500:])
        return 1
        
    rows = []
    with open(cg_csv, "r") as f:
        reader = csv.DictReader(f)
        for r in reader:
            cx = int(r["chunk_x"])
            cz = int(r["chunk_z"])
            if (cx, cz) in expected_targets:
                rows.append(r)
                
    print(f"\nExtracted {len(rows)} matching target chunk rows for evaluation.")
    
    # Save target csv
    with open(out_csv, "w", newline="") as f:
        if rows:
            writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
            writer.writeheader()
            writer.writerows(rows)
    print(f"Saved filtered target CSV to {out_csv}")
    
    # Calculate statistics
    def col_stats(name):
        vals = [float(r[name]) for r in rows]
        vals.sort()
        mean_v = sum(vals) / len(vals)
        p50_v = vals[len(vals)//2]
        p95_idx = int(len(vals) * 0.95)
        p95_v = vals[min(len(vals)-1, p95_idx)]
        max_v = vals[-1]
        min_v = vals[0]
        return mean_v, p50_v, p95_v, max_v, min_v
        
    stages = ["total_ms", "biomes_ms", "noise_ms", "surface_ms", "carvers_ms", "features_ms", "initialize_light_ms", "light_ms"]
    
    print("\n=========================================================================")
    print("                 VANILLA 26.2 CHUNKGEN RESULTS (121 CHUNKS)              ")
    print("=========================================================================")
    for st in stages:
        if st in rows[0]:
            mean_v, p50_v, p95_v, max_v, min_v = col_stats(st)
            print(f"{st:20s}: mean={mean_v:7.2f} ms | p50={p50_v:7.2f} ms | p95={p95_v:7.2f} ms | max={max_v:7.2f} ms | min={min_v:7.2f} ms")
    print("=========================================================================")
    
    mean_tot, p50_tot, p95_tot, max_tot, _ = col_stats("total_ms")
    sections_per_sec = (len(rows) * 24.0) / wall_seconds
    
    print(f"Target Chunks:       {len(rows)}")
    print(f"Wall time:           {wall_seconds:.2f} s")
    print(f"Mean ms-per-chunk:   {mean_tot:.2f} ms (server thread generation sum)")
    print(f"p95 ms-per-chunk:    {p95_tot:.2f} ms")
    print(f"Max ms-per-chunk:    {max_tot:.2f} ms")
    print(f"Sections per second: {sections_per_sec:.2f} sec/s (24 sections/chunk)")
    print(f"Vs 50ms Tick Budget: {mean_tot / 50.0:.2f}x tick budget (Mean = {mean_tot/50.0:.1f} ticks, p95 = {p95_tot/50.0:.1f} ticks)")
    
    return 0

if __name__ == "__main__":
    sys.exit(main())
