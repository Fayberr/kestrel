#!/usr/bin/env python3
"""Kestrel MSPT reference-table runner (vanilla vs Paper vs Kestrel).

Drives reference benchmark runs for Minecraft servers on a fixed seed:
- Launches server jar headless in an isolated throwaway directory
  (eula=true, online-mode=false, fixed seed, view-distance=10, 0 players).
- Connects N tp-bots (via tp-bot.py) teleporting on a deterministic spiral
  to force chunk generation and tick load.
- Parses per-tick MSPT from the server log ('Time:...ms' style) and chunk counts.
- Emits standard MSPTRUN lines for benchmark runner integration.
- Outputs structured JSON rows per run and generates comparison markdown tables
  via --summarize across servers for identical seeds.
- Provides a comprehensive --self-test mode with synthetic log parsing,
  JSON schema validation, and spiral determinism assertions (no server needed).

Usage:
  # Run reference benchmark on vanilla/paper:
  python3 harness/tools/mspt-table.py --server vanilla --jar server.jar --seed 4242424242 --ticks 600

  # Summarize row files into a comparison markdown table:
  python3 harness/tools/mspt-table.py --summarize row_vanilla.json row_paper.json

  # Run self-test (no server required):
  python3 harness/tools/mspt-table.py --self-test
"""

import argparse
import json
import math
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from typing import Any, Dict, List, Optional, Tuple

# Supported server implementations
SERVERS = ("vanilla", "paper", "kestrel")

# Regex to extract per-tick timing from server logs.
# Matches forms such as:
#   "Time: 12.34ms", "Time: 12.34 ms", "time=12.34ms"
#   "Tick 42: Time: 18.5ms"
#   "[Paper] Tick 1 - Time: 12.3ms - Chunks: 441"
#   "Average tick time: 14.5ms", "Tick time: 15.2ms"
#   "MSPT: 14.2ms", "mspt=14.2"
#   "15.2ms/tick"
# Does NOT match lag warnings ("Running 5000ms behind").
MSPT_REGEX = re.compile(
    r"(?:"
    r"(?:time|mspt|tick\s+time|average\s+tick\s+time)\s*[:=]\s*([0-9]+(?:\.[0-9]+)?)\s*(?:ms)?\b"
    r"|"
    r"\b([0-9]+(?:\.[0-9]+)?)\s*ms/tick\b"
    r")",
    re.IGNORECASE,
)

# Regex to extract chunk count from server logs.
# Matches forms such as:
#   "Chunks loaded: 441", "Chunks: 441", "chunks=441"
#   "441 chunks loaded", "441 chunks"
CHUNKS_REGEX = re.compile(
    r"(?:"
    r"(?:chunks?(?:\s+loaded)?|loaded\s+chunks?)\s*[:=]\s*([0-9]+)\b"
    r"|"
    r"\b([0-9]+)\s+chunks?(?:\s+loaded)?\b"
    r")",
    re.IGNORECASE,
)

# Benchmark runner result line format: MSPTRUN server=<s> seed=<n> med=<ms> p95=<ms>
MSPTRUN_PATTERN = re.compile(
    r"^MSPTRUN\s+server=(?P<server>\S+)\s+seed=(?P<seed>-?\d+)\s+med=(?P<med>[0-9.]+)\s+p95=(?P<p95>[0-9.]+)"
)


def generate_spiral(
    seed: int,
    count: int,
    step: float = 64.0,
    y: float = 70.0,
) -> List[Tuple[float, float, float]]:
    """Generate a deterministic expanding square spiral of (x, y, z) teleport targets.

    Given a seed and count, generates a deterministic sequence of coordinates.
    Guarantees:
      - Calling with the same seed and count produces the exact same coordinate list.
      - Different seeds produce different teleport sequences.
      - Each step advances outward in an expanding perimeter, spacing targets
        by `step` blocks (default 64.0 = 4 chunks).
    """
    if count <= 0:
        return []

    # Derive initial center offset from seed so different seeds explore different volumes
    # while identical seeds produce identical spirals.
    s = seed & 0xFFFFFFFFFFFFFFFF
    x_origin = float(((s % 1000) - 500) * 16)
    z_origin = float((((s >> 16) % 1000) - 500) * 16)

    coords: List[Tuple[float, float, float]] = []
    x, z = 0, 0
    coords.append((round(x_origin + x * step, 1), round(y, 1), round(z_origin + z * step, 1)))

    dx, dz = 1, 0
    seg_len = 1
    seg_passed = 0

    while len(coords) < count:
        x += dx
        z += dz
        coords.append((round(x_origin + x * step, 1), round(y, 1), round(z_origin + z * step, 1)))
        seg_passed += 1
        if seg_passed == seg_len:
            seg_passed = 0
            # Turn 90 degrees counter-clockwise: (1,0) -> (0,1) -> (-1,0) -> (0,-1)
            dx, dz = -dz, dx
            if dz == 0:
                seg_len += 1

    return coords


def parse_ticks_log(ticks_text: str) -> Dict[str, Any]:
    """Parse per-tick cadence/period timings from kestrel-harness ticks.log.

    Extracts exit lines and computes consecutive exit-to-exit timestamp deltas.
    NOTE: Exit-to-exit timestamp deltas measure tick cadence / period (~50ms pacing interval),
    NOT actual tick execution duration.
    """
    exit_nanos: List[int] = []
    for line in ticks_text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split(",")
        if len(parts) >= 4 and parts[1] == "exit":
            try:
                exit_nanos.append(int(parts[3]))
            except ValueError:
                pass

    deltas: List[float] = []
    for i in range(1, len(exit_nanos)):
        deltas.append(round((exit_nanos[i] - exit_nanos[i - 1]) / 1e6, 3))

    return {
        "tick_period_ms": deltas,
        "cadence_ms": deltas,
        "mspt_values": deltas,
        "tick_count": len(exit_nanos),
    }


def parse_server_log(log_text: str) -> Dict[str, Any]:
    """Parse per-tick MSPT timings and chunk counts from server log text.

    Scans for 'Time:...ms' style timing statements and chunk count markers.
    Returns:
      {
        "mspt_values": [float, ...],
        "chunks_loaded": int
      }
    """
    mspt_values: List[float] = []
    max_chunks = 0

    for line in log_text.splitlines():
        # Match MSPT timing
        m = MSPT_REGEX.search(line)
        if m:
            val_str = m.group(1) or m.group(2)
            if val_str:
                try:
                    mspt_values.append(float(val_str))
                except ValueError:
                    pass

        # Match chunk loading statements
        c = CHUNKS_REGEX.search(line)
        if c:
            chk_str = c.group(1) or c.group(2)
            if chk_str:
                try:
                    chk_val = int(chk_str)
                    if chk_val > max_chunks:
                        max_chunks = chk_val
                except ValueError:
                    pass

    return {
        "mspt_values": mspt_values,
        "chunks_loaded": max_chunks,
    }


def calculate_median(values: List[float]) -> float:
    """Calculate the median of a list of numeric values."""
    if not values:
        return 0.0
    s = sorted(values)
    n = len(s)
    if n % 2 == 1:
        return round(s[n // 2], 3)
    else:
        return round((s[n // 2 - 1] + s[n // 2]) / 2.0, 3)


def calculate_percentile(values: List[float], pct: float = 95.0) -> float:
    """Calculate the p-th percentile of a list of numeric values (nearest rank)."""
    if not values:
        return 0.0
    s = sorted(values)
    n = len(s)
    rank = math.ceil(pct / 100.0 * n) - 1
    rank = max(0, min(rank, n - 1))
    return round(s[rank], 3)


def calculate_mean(values: List[float]) -> float:
    """Calculate the arithmetic mean of a list of numeric values."""
    if not values:
        return 0.0
    return round(sum(values) / len(values), 3)


def create_json_row(
    server: str,
    seed: int,
    ticks: int,
    mspt_med: float,
    mspt_p95: float,
    mspt_mean: float,
    chunks_loaded: Optional[int] = 0,
    notes: str = "",
) -> Dict[str, Any]:
    """Create a standardized benchmark result row dictionary."""
    return {
        "server": str(server),
        "seed": int(seed),
        "ticks": int(ticks),
        "mspt_med": round(float(mspt_med), 3),
        "mspt_p95": round(float(mspt_p95), 3),
        "mspt_mean": round(float(mspt_mean), 3),
        "chunks_loaded": int(chunks_loaded) if chunks_loaded is not None else None,
        "notes": str(notes),
    }


def validate_json_row(row: Dict[str, Any]) -> Tuple[bool, str]:
    """Validate that a dictionary conforms strictly to the JSON row schema."""
    required_keys = {
        "server",
        "seed",
        "ticks",
        "mspt_med",
        "mspt_p95",
        "mspt_mean",
        "chunks_loaded",
        "notes",
    }
    missing = required_keys - set(row.keys())
    if missing:
        return False, f"missing required fields: {sorted(missing)}"

    if not isinstance(row["server"], str) or row["server"] not in SERVERS:
        return False, f"invalid server: {row.get('server')!r} (must be one of {SERVERS})"
    if not isinstance(row["seed"], int):
        return False, f"seed must be int, got {type(row['seed']).__name__}"
    if not isinstance(row["ticks"], int):
        return False, f"ticks must be int, got {type(row['ticks']).__name__}"
    if not isinstance(row["mspt_med"], (int, float)):
        return False, f"mspt_med must be float/int, got {type(row['mspt_med']).__name__}"
    if not isinstance(row["mspt_p95"], (int, float)):
        return False, f"mspt_p95 must be float/int, got {type(row['mspt_p95']).__name__}"
    if not isinstance(row["mspt_mean"], (int, float)):
        return False, f"mspt_mean must be float/int, got {type(row['mspt_mean']).__name__}"
    if row["chunks_loaded"] is not None and not isinstance(row["chunks_loaded"], int):
        return False, f"chunks_loaded must be int or None, got {type(row['chunks_loaded']).__name__}"
    if not isinstance(row["notes"], str):
        return False, f"notes must be str, got {type(row['notes']).__name__}"

    return True, "valid"


def format_msptrun_line(server: str, seed: int, med: float, p95: float) -> str:
    """Format benchmark runner RUN line for stdout integration."""
    return f"MSPTRUN server={server} seed={seed} med={med:.2f} p95={p95:.2f}"


def format_markdown_table(rows: List[Dict[str, Any]]) -> str:
    """Format multiple benchmark run rows into a comparison markdown table.

    Groups rows by seed, showing Vanilla vs Paper vs Kestrel columns,
    and computes multipliers where both baseline and comparator exist.
    """
    seeds: Dict[int, Dict[str, List[Dict[str, Any]]]] = {}
    for r in rows:
        seed = r.get("seed")
        if seed is None:
            continue
        if seed not in seeds:
            seeds[seed] = {}
        srv = str(r.get("server", "")).lower()
        if srv not in seeds[seed]:
            seeds[seed][srv] = []
        seeds[seed][srv].append(r)

    lines: List[str] = [
        "| Seed | Vanilla (med / p95) | Paper (med / p95) | Kestrel (med / p95) | Chunks | Notes |",
        "| :--- | :--- | :--- | :--- | :--- | :--- |",
    ]

    for seed in sorted(seeds.keys()):
        srvs = seeds[seed]

        def fmt_cell(srv_name: str) -> str:
            if srv_name not in srvs or not srvs[srv_name]:
                return "N/A"
            items = srvs[srv_name]
            if any("NOT-IMPLEMENTED" in str(it.get("notes", "")) for it in items):
                return "NOT-IMPLEMENTED"
            meds = [float(it["mspt_med"]) for it in items if it.get("mspt_med") is not None and it["mspt_med"] > 0]
            p95s = [float(it["mspt_p95"]) for it in items if it.get("mspt_p95") is not None and it["mspt_p95"] > 0]
            if not meds:
                return "N/A"
            avg_med = sum(meds) / len(meds)
            avg_p95 = sum(p95s) / len(p95s)
            if len(items) > 1:
                return f"{avg_med:.2f} ms / {avg_p95:.2f} ms (n={len(items)})"
            return f"{avg_med:.2f} ms / {avg_p95:.2f} ms"

        chunks_list = []
        for s in ("vanilla", "paper", "kestrel"):
            if s in srvs:
                max_c = max([it.get("chunks_loaded") or 0 for it in srvs[s]], default=0)
                if max_c > 0:
                    chunks_list.append(f"{s[:1].upper()}:{max_c}")
        chunks_str = ", ".join(chunks_list) if chunks_list else "-"

        all_notes = []
        for s in ("vanilla", "paper", "kestrel"):
            if s in srvs:
                for it in srvs[s]:
                    n = it.get("notes", "")
                    if n and "NOT-IMPLEMENTED" not in n:
                        all_notes.append(n)
        notes_dedup = list(dict.fromkeys(filter(None, all_notes)))
        notes_str = "; ".join(notes_dedup) or "-"

        lines.append(
            f"| {seed} | {fmt_cell('vanilla')} | {fmt_cell('paper')} | {fmt_cell('kestrel')} | {chunks_str} | {notes_str} |"
        )

    # Multiplier table section
    mult_lines: List[str] = [
        "",
        "### Multiplier Summary",
        "| Seed | Comparison | Baseline Median | Server Median | Multiplier (Speedup) | Multiplier (MSPT ratio) |",
        "| :--- | :--- | :--- | :--- | :--- | :--- |",
    ]
    has_mults = False
    for seed in sorted(seeds.keys()):
        srvs = seeds[seed]
        van_items = srvs.get("vanilla", [])
        van_meds = [float(it["mspt_med"]) for it in van_items if it.get("mspt_med") and it["mspt_med"] > 0]
        if not van_meds:
            continue
        van_med = sum(van_meds) / len(van_meds)

        # Paper comparison
        paper_items = srvs.get("paper", [])
        paper_meds = [float(it["mspt_med"]) for it in paper_items if it.get("mspt_med") and it["mspt_med"] > 0]
        if paper_meds:
            p_med = sum(paper_meds) / len(paper_meds)
            speedup = van_med / p_med if p_med > 0 else 0.0
            ratio = p_med / van_med if van_med > 0 else 0.0
            mult_lines.append(
                f"| {seed} | Paper vs Vanilla | {van_med:.2f} ms | {p_med:.2f} ms | **{speedup:.2f}x faster** | {ratio:.2f}x vanilla MSPT |"
            )
            has_mults = True

        # Kestrel comparison if measured
        kestrel_items = srvs.get("kestrel", [])
        kest_meds = [float(it["mspt_med"]) for it in kestrel_items if it.get("mspt_med") and it["mspt_med"] > 0]
        if kest_meds:
            k_med = sum(kest_meds) / len(kest_meds)
            k_speedup = van_med / k_med if k_med > 0 else 0.0
            k_ratio = k_med / van_med if van_med > 0 else 0.0
            mult_lines.append(
                f"| {seed} | Kestrel vs Vanilla | {van_med:.2f} ms | {k_med:.2f} ms | **{k_speedup:.2f}x faster** | {k_ratio:.2f}x vanilla MSPT |"
            )
            has_mults = True
        elif kestrel_items:
            mult_lines.append(
                f"| {seed} | Kestrel vs Vanilla | {van_med:.2f} ms | N/A | Skipped (no worldgen) | N/A |"
            )
            has_mults = True

    if has_mults:
        lines.extend(mult_lines)

    has_ticks_log = any("ticks.log" in str(r.get("notes", "")) for r in rows)
    if has_ticks_log:
        lines.append("")
        lines.append("*Table note: ticks.log exit-to-exit deltas measure tick cadence/interval (~50ms pacing), not actual execution duration.*")

    return "\n".join(lines)


def run_summarize(files: List[str]) -> int:
    """Read N row files and print a markdown comparison table."""
    all_rows: List[Dict[str, Any]] = []

    if not files:
        # Read from stdin if no files specified
        raw = sys.stdin.read().strip()
        if raw:
            try:
                data = json.loads(raw)
                if isinstance(data, list):
                    all_rows.extend(data)
                elif isinstance(data, dict):
                    if "results" in data and isinstance(data["results"], list):
                        all_rows.extend(data["results"])
                    elif "runs" in data and isinstance(data["runs"], list):
                        all_rows.extend(data["runs"])
                    else:
                        all_rows.append(data)
            except json.JSONDecodeError:
                for line in raw.splitlines():
                    line = line.strip()
                    if line:
                        all_rows.append(json.loads(line))
    else:
        for fpath in files:
            if not os.path.isfile(fpath):
                print(f"Warning: file not found: {fpath}", file=sys.stderr)
                continue
            with open(fpath, "r", encoding="utf-8") as f:
                content = f.read().strip()
                if not content:
                    continue
                try:
                    data = json.loads(content)
                    if isinstance(data, list):
                        all_rows.extend(data)
                    elif isinstance(data, dict):
                        if "results" in data and isinstance(data["results"], list):
                            all_rows.extend(data["results"])
                        elif "runs" in data and isinstance(data["runs"], list):
                            all_rows.extend(data["runs"])
                        else:
                            all_rows.append(data)
                except json.JSONDecodeError:
                    for line in content.splitlines():
                        line = line.strip()
                        if line:
                            all_rows.append(json.loads(line))

    if not all_rows:
        print("No valid benchmark rows found to summarize.", file=sys.stderr)
        return 1

    table = format_markdown_table(all_rows)
    print(table)
    return 0


def run_self_test() -> int:
    """Execute standalone verification suite without requiring any server or JDK."""
    print("[self-test] starting mspt-table verification...", flush=True)
    tests_run = 0

    # 1. Synthetic log parsing (vanilla format)
    canned_vanilla_log = """
[12:00:00] [Server thread/INFO]: Preparing start region for dimension minecraft:overworld
[12:00:01] [Server thread/INFO]: Time: 12.0ms
[12:00:02] [Server thread/INFO]: Time: 18.0ms
[12:00:03] [Server thread/INFO]: Time: 24.0ms
[12:00:04] [Server thread/INFO]: Time: 30.0ms
[12:00:05] [Server thread/INFO]: Time: 36.0ms
[12:00:06] [Server thread/INFO]: Chunks loaded: 441
"""
    p1 = parse_server_log(canned_vanilla_log)
    assert len(p1["mspt_values"]) == 5, f"expected 5 mspt values, got {len(p1['mspt_values'])}"
    assert p1["chunks_loaded"] == 441, f"expected 441 chunks, got {p1['chunks_loaded']}"
    med1 = calculate_median(p1["mspt_values"])
    p95_1 = calculate_percentile(p1["mspt_values"], 95)
    mean1 = calculate_mean(p1["mspt_values"])
    assert med1 == 24.0, f"median mismatch: expected 24.0, got {med1}"
    assert mean1 == 24.0, f"mean mismatch: expected 24.0, got {mean1}"
    assert p95_1 == 36.0, f"p95 mismatch: expected 36.0, got {p95_1}"
    tests_run += 1
    print(f"[self-test] 1. Synthetic log parsing (vanilla): PASS (med={med1}ms p95={p95_1}ms chunks={p1['chunks_loaded']})", flush=True)

    # 2. Synthetic log parsing (Paper format)
    canned_paper_log = """
[10:11:12 INFO]: [Paper] Tick 1 - Time: 10.5ms - Chunks: 200
[10:11:13 INFO]: [Paper] Tick 2 - Time: 15.5ms - Chunks: 250
[10:11:14 INFO]: [Paper] Tick 3 - Time: 20.5ms - Chunks: 300
"""
    p2 = parse_server_log(canned_paper_log)
    assert len(p2["mspt_values"]) == 3, f"expected 3 mspt values, got {len(p2['mspt_values'])}"
    assert p2["chunks_loaded"] == 300, f"expected 300 chunks, got {p2['chunks_loaded']}"
    med2 = calculate_median(p2["mspt_values"])
    assert med2 == 15.5, f"median mismatch: expected 15.5, got {med2}"
    tests_run += 1
    print(f"[self-test] 2. Synthetic log parsing (Paper): PASS (med={med2}ms chunks={p2['chunks_loaded']})", flush=True)

    # 3. Log noise rejection (lag warnings and recipes should not be captured)
    canned_noisy_log = """
[10:00:00 WARN]: Can't keep up! Is the server overloaded? Running 5000ms or 100 ticks behind
[10:00:01 INFO]: Loaded 250 recipes in 12ms
[10:00:02 INFO]: Tick time: 14.2ms
[10:00:03 INFO]: Average tick time: 16.8ms
[10:00:04 INFO]: MSPT: 18.0ms
"""
    p3 = parse_server_log(canned_noisy_log)
    assert len(p3["mspt_values"]) == 3, f"expected 3 mspt values, got {len(p3['mspt_values'])}: {p3['mspt_values']}"
    assert 5000.0 not in p3["mspt_values"], "lag warning was improperly captured"
    assert 12.0 not in p3["mspt_values"], "recipe load time was improperly captured"
    assert p3["mspt_values"] == [14.2, 16.8, 18.0]
    tests_run += 1
    print("[self-test] 3. Log noise rejection: PASS (lag warnings and recipe loaders ignored)", flush=True)

    # 4. JSON row schema validation & round-trip
    row = create_json_row(
        server="vanilla",
        seed=4242424242,
        ticks=600,
        mspt_med=24.0,
        mspt_p95=36.0,
        mspt_mean=24.0,
        chunks_loaded=441,
        notes="test run",
    )
    valid, msg = validate_json_row(row)
    assert valid, f"schema validation failed: {msg}"
    serialized = json.dumps(row)
    deserialized = json.loads(serialized)
    assert deserialized == row, "JSON round-trip mismatch"

    # Verify invalid schema rejection
    bad_row = dict(row)
    del bad_row["seed"]
    valid_bad, _ = validate_json_row(bad_row)
    assert not valid_bad, "schema validator failed to reject missing seed"

    # Verify None chunks_loaded support (unmeasured from logs)
    row_none = create_json_row(
        server="vanilla",
        seed=4242424242,
        ticks=600,
        mspt_med=24.0,
        mspt_p95=36.0,
        mspt_mean=24.0,
        chunks_loaded=None,
        notes="unmeasured chunks",
    )
    valid_none, msg_none = validate_json_row(row_none)
    assert valid_none, f"schema validation failed for None chunks_loaded: {msg_none}"
    assert row_none["chunks_loaded"] is None
    tests_run += 1
    print("[self-test] 4. JSON row schema assert: PASS (all 8 fields validated & serialized, None chunks supported)", flush=True)

    # 5. Spiral coordinate determinism assert (same seed -> same teleport list)
    spiral_a1 = generate_spiral(seed=4242424242, count=30, step=64.0)
    spiral_a2 = generate_spiral(seed=4242424242, count=30, step=64.0)
    assert spiral_a1 == spiral_a2, "spiral determinism failed: same seed produced different coordinates"
    assert len(spiral_a1) == 30, f"expected 30 coordinates, got {len(spiral_a1)}"

    # Ensure different seed produces different coordinates
    spiral_b = generate_spiral(seed=1234567890, count=30, step=64.0)
    assert spiral_a1 != spiral_b, "different seeds produced identical coordinates"

    # Ensure step distance is adhered to
    p0, p1 = spiral_a1[0], spiral_a1[1]
    dist = math.hypot(p1[0] - p0[0], p1[2] - p0[2])
    assert abs(dist - 64.0) < 1e-3, f"expected step distance 64.0, got {dist}"
    tests_run += 1
    print(f"[self-test] 5. Spiral coordinate determinism: PASS (seed 4242424242 -> identical 30-point list, step={dist:.1f})", flush=True)

    # 6. Markdown table formatting assert
    mock_rows = [
        create_json_row("vanilla", 4242424242, 600, 14.50, 22.10, 15.20, 441, "ref"),
        create_json_row("paper", 4242424242, 600, 11.20, 16.80, 11.90, 441, "ref"),
        create_json_row("kestrel", 4242424242, 0, 0.0, 0.0, 0.0, 0, "NOT-IMPLEMENTED"),
        create_json_row("vanilla", 9999, 600, 15.00, 24.00, 16.00, 350, "ref"),
        create_json_row("paper", 9999, 600, 12.00, 18.00, 12.50, 350, "ref"),
    ]
    md_table = format_markdown_table(mock_rows)
    assert "| Seed | Vanilla (med / p95) | Paper (med / p95) | Kestrel (med / p95) |" in md_table
    assert "4242424242" in md_table
    assert "9999" in md_table
    assert "14.50 ms / 22.10 ms" in md_table
    assert "11.20 ms / 16.80 ms" in md_table
    assert "NOT-IMPLEMENTED" in md_table
    tests_run += 1
    print("[self-test] 6. Markdown table generation: PASS (columns: vanilla vs paper vs kestrel, same-seed rows)", flush=True)

    # 7. MSPTRUN runner line format assert
    sample_run_line = format_msptrun_line("vanilla", 4242424242, 14.50, 22.10)
    m_run = MSPTRUN_PATTERN.match(sample_run_line)
    assert m_run is not None, f"MSPTRUN pattern failed to match line: {sample_run_line}"
    assert m_run.group("server") == "vanilla"
    assert int(m_run.group("seed")) == 4242424242
    assert float(m_run.group("med")) == 14.50
    assert float(m_run.group("p95")) == 22.10
    tests_run += 1
    print(f"[self-test] 7. MSPTRUN line emission: PASS ({sample_run_line})", flush=True)

    # 8. Ticks.log parser assert (exit-to-exit timestamp deltas)
    canned_ticks_log = """
# kestrel-harness tick log, schema_version=6
# seq,phase,tick_count,nanos,world_state_hash
0,enter,0,1000000000000,0
1,exit,1,1000050000000,0
2,enter,1,1000050100000,0
3,exit,2,1000100000000,0
4,enter,2,1000100100000,0
5,exit,3,1000150000000,0
"""
    p_ticks = parse_ticks_log(canned_ticks_log)
    assert p_ticks["tick_count"] == 3, f"expected 3 exit ticks, got {p_ticks['tick_count']}"
    assert len(p_ticks["mspt_values"]) == 2, f"expected 2 deltas, got {len(p_ticks['mspt_values'])}"
    assert p_ticks["mspt_values"] == [50.0, 50.0], f"expected [50.0, 50.0], got {p_ticks['mspt_values']}"
    assert p_ticks["tick_period_ms"] == [50.0, 50.0], f"expected [50.0, 50.0], got {p_ticks['tick_period_ms']}"
    assert p_ticks["cadence_ms"] == [50.0, 50.0], f"expected [50.0, 50.0], got {p_ticks['cadence_ms']}"
    tests_run += 1
    print("[self-test] 8. Ticks.log parser (exit-to-exit deltas as cadence): PASS (2 ticks, 50.0ms)", flush=True)

    print(f"SELF-TEST PASSED: {tests_run}/{tests_run} checks OK", flush=True)
    return 0


def drive_server_run(args: argparse.Namespace) -> int:
    """Drive ONE reference run for the specified server implementation."""
    # 1. Kestrel stub path
    if args.server == "kestrel":
        print(
            "NOT-IMPLEMENTED: Kestrel server reference run is not implemented "
            "(no standalone server jar in this phase)",
            file=sys.stderr,
        )
        row = create_json_row(
            server="kestrel",
            seed=args.seed,
            ticks=0,
            mspt_med=0.0,
            mspt_p95=0.0,
            mspt_mean=0.0,
            chunks_loaded=0,
            notes="NOT-IMPLEMENTED",
        )
        print(format_msptrun_line("kestrel", args.seed, 0.0, 0.0))
        print(json.dumps(row, indent=2))
        if args.output:
            with open(args.output, "w", encoding="utf-8") as f:
                json.dump(row, f, indent=2)
                f.write("\n")
        return 2

    # 2. Validate jar for vanilla or paper
    if not args.jar:
        sys.exit("Error: --jar <path> is required for vanilla or paper runs")
    jar_path = os.path.abspath(args.jar)
    if not os.path.isfile(jar_path):
        sys.exit(f"Error: server jar not found at {jar_path}")

    # Locate tp-bot.py
    script_dir = os.path.dirname(os.path.abspath(__file__))
    tp_bot_path = os.path.join(script_dir, "tp-bot.py")
    if not os.path.isfile(tp_bot_path):
        tp_bot_path = os.path.abspath("harness/tools/tp-bot.py")
    if not os.path.isfile(tp_bot_path):
        sys.exit(f"Error: tp-bot.py not found at {tp_bot_path}")

    # 3. Setup temporary workspace
    work_dir = tempfile.mkdtemp(prefix=f"mspt-{args.server}-{args.seed}-")
    print(f"[{args.server}] created temporary workspace: {work_dir}", flush=True)

    try:
        # Write eula.txt
        with open(os.path.join(work_dir, "eula.txt"), "w", encoding="utf-8") as f:
            f.write("eula=true\n")

        # Write server.properties
        max_players = max(20, args.bots * 2)
        props = [
            "online-mode=false",
            f"server-port={args.port}",
            f"level-seed={args.seed}",
            f"max-players={max_players}",
            "view-distance=10",
            "simulation-distance=10",
            "spawn-monsters=false",
            "spawn-animals=false",
            "difficulty=peaceful",
            "gamemode=creative",
            "generate-structures=true",
            r"level-type=minecraft\:normal",
        ]
        with open(os.path.join(work_dir, "server.properties"), "w", encoding="utf-8") as f:
            f.write("\n".join(props) + "\n")

        # 4. Launch server process
        jvm_flags = args.jvm_flags.split() if args.jvm_flags else []
        ticks_log_path = os.path.join(work_dir, "ticks.log")
        if getattr(args, "agent_jar", None):
            agent_path = os.path.abspath(args.agent_jar)
            if os.path.isfile(agent_path):
                jvm_flags.append(f"-javaagent:{agent_path}={ticks_log_path}")
        cmd = [args.java] + jvm_flags + ["-jar", jar_path, "--nogui"]
        stdout_path = os.path.join(work_dir, "stdout.log")
        stderr_path = os.path.join(work_dir, "stderr.log")

        stdout_file = open(stdout_path, "w", encoding="utf-8", buffering=1)
        stderr_file = open(stderr_path, "w", encoding="utf-8", buffering=1)

        print(f"[{args.server}] launching server jar: {' '.join(cmd)}", flush=True)
        server_proc = subprocess.Popen(
            cmd,
            cwd=work_dir,
            stdin=subprocess.PIPE,
            stdout=stdout_file,
            stderr=stderr_file,
            text=True,
        )

        # 5. Wait for readiness pattern
        boot_start = time.time()
        is_ready = False
        print(f"[{args.server}] waiting for server boot (timeout: {args.ready_timeout}s)...", flush=True)

        ready_patterns = ["Done (", "Done!", "Done", "listening on", "Server started"]
        while time.time() - boot_start < args.ready_timeout:
            time.sleep(1.0)
            if server_proc.poll() is not None:
                break

            # Check stdout.log
            if os.path.isfile(stdout_path):
                with open(stdout_path, "r", encoding="utf-8", errors="replace") as sf:
                    content = sf.read()
                    if any(rp in content for rp in ready_patterns):
                        is_ready = True
                        break

            # Check logs/latest.log if created by Minecraft
            latest_log = os.path.join(work_dir, "logs", "latest.log")
            if os.path.isfile(latest_log):
                with open(latest_log, "r", encoding="utf-8", errors="replace") as lf:
                    content = lf.read()
                    if any(rp in content for rp in ready_patterns):
                        is_ready = True
                        break

        if not is_ready:
            print(f"[{args.server}] boot failed or timed out. Killing server...", file=sys.stderr)
            try:
                server_proc.kill()
                server_proc.wait(timeout=5)
            except Exception:
                pass
            stdout_file.close()
            stderr_file.close()
            return 1

        boot_time = round(time.time() - boot_start, 1)
        print(f"[{args.server}] booted in {boot_time}s. Driving {args.bots} tp-bots on spiral...", flush=True)

        # 6. Drive tp-bots on deterministic spiral
        stay_seconds = max(5.0, float(args.ticks) / float(args.tps))
        spiral_y = getattr(args, "y", 70.0)
        spiral_step = getattr(args, "step", 64.0)
        spiral = generate_spiral(args.seed, count=max(args.bots, 1), step=spiral_step, y=spiral_y)

        bot_procs: List[subprocess.Popen] = []
        if getattr(args, "sequential", False) and args.bots > 1:
            per_bot_stay = max(5.0, stay_seconds / args.bots)
            for i in range(args.bots):
                pt = spiral[i]
                bot_cmd = [
                    sys.executable,
                    tp_bot_path,
                    "--host",
                    "127.0.0.1",
                    "--port",
                    str(args.port),
                    "--x",
                    str(pt[0]),
                    "--y",
                    str(pt[1]),
                    "--z",
                    str(pt[2]),
                    "--stay",
                    str(per_bot_stay),
                    "--name",
                    f"TpBot{i+1}",
                ]
                bp = subprocess.Popen(bot_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                try:
                    bout, berr = bp.communicate(timeout=per_bot_stay + 30.0)
                    if bp.returncode != 0:
                        print(f"[{args.server}] warning: TpBot{i+1} exited with code {bp.returncode}: {berr.strip()}", file=sys.stderr)
                except subprocess.TimeoutExpired:
                    bp.kill()
        else:
            for i in range(args.bots):
                pt = spiral[i]
                bot_cmd = [
                    sys.executable,
                    tp_bot_path,
                    "--host",
                    "127.0.0.1",
                    "--port",
                    str(args.port),
                    "--x",
                    str(pt[0]),
                    "--y",
                    str(pt[1]),
                    "--z",
                    str(pt[2]),
                    "--stay",
                    str(stay_seconds),
                    "--name",
                    f"TpBot{i+1}",
                ]
                bp = subprocess.Popen(
                    bot_cmd,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                )
                bot_procs.append(bp)

            # Wait for bots to complete
            bot_deadline = time.time() + stay_seconds + 30.0
            for i, bp in enumerate(bot_procs):
                rem = max(1.0, bot_deadline - time.time())
                try:
                    bout, berr = bp.communicate(timeout=rem)
                    if bp.returncode != 0:
                        print(f"[{args.server}] warning: TpBot{i+1} exited with code {bp.returncode}: {berr.strip()}", file=sys.stderr)
                except subprocess.TimeoutExpired:
                    bp.kill()

        # 7. Stop server
        print(f"[{args.server}] stopping server...", flush=True)
        try:
            if server_proc.stdin:
                server_proc.stdin.write("stop\n")
                server_proc.stdin.flush()
            server_proc.wait(timeout=20)
        except (subprocess.TimeoutExpired, OSError):
            server_proc.kill()
            server_proc.wait(timeout=5)
        finally:
            stdout_file.close()
            stderr_file.close()

        # 8. Parse logs
        full_log_parts = []
        if os.path.isfile(stdout_path):
            with open(stdout_path, "r", encoding="utf-8", errors="replace") as sf:
                full_log_parts.append(sf.read())
        latest_log = os.path.join(work_dir, "logs", "latest.log")
        if os.path.isfile(latest_log):
            with open(latest_log, "r", encoding="utf-8", errors="replace") as lf:
                full_log_parts.append(lf.read())

        combined_log = "\n".join(full_log_parts)

        # Check ticks.log first (highest fidelity timing from javaagent)
        mspts = []
        is_ticks_log = False
        if os.path.isfile(ticks_log_path):
            with open(ticks_log_path, "r", encoding="utf-8", errors="replace") as tf:
                parsed_ticks = parse_ticks_log(tf.read())
                mspts = parsed_ticks.get("tick_period_ms", parsed_ticks.get("mspt_values", []))
                if mspts:
                    is_ticks_log = True

        # Fallback to stdout.log / latest.log if no ticks.log timings
        if not mspts:
            parsed = parse_server_log(combined_log)
            mspts = parsed["mspt_values"]
        else:
            parsed = parse_server_log(combined_log)

        chunks_loaded = parsed.get("chunks_loaded") or 0

        if mspts:
            med = calculate_median(mspts)
            p95 = calculate_percentile(mspts, 95)
            mean = calculate_mean(mspts)
            timing_src = (
                "ticks.log cadence (tick cadence/interval, not execution duration)"
                if is_ticks_log
                else "stdout"
            )
            notes_text = f"{len(mspts)} ticks collected ({timing_src}), boot={boot_time}s"
        else:
            med = 0.0
            p95 = 0.0
            mean = 0.0
            notes_text = f"boot={boot_time}s; no MSPT timing lines parsed"

        if args.notes:
            notes_text = f"{notes_text}; {args.notes}"

        # 9. Format outputs
        row = create_json_row(
            server=args.server,
            seed=args.seed,
            ticks=len(mspts) if mspts else args.ticks,
            mspt_med=med,
            mspt_p95=p95,
            mspt_mean=mean,
            chunks_loaded=chunks_loaded,
            notes=notes_text,
        )

        run_line = format_msptrun_line(args.server, args.seed, med, p95)
        print(run_line)

        row_json = json.dumps(row, indent=2)
        print(row_json)

        if args.output:
            with open(args.output, "w", encoding="utf-8") as f:
                f.write(row_json + "\n")
            print(f"[{args.server}] row written to {args.output}", flush=True)

        return 0

    finally:
        if not args.keep_dir:
            shutil.rmtree(work_dir, ignore_errors=True)
        else:
            print(f"[{args.server}] kept working dir: {work_dir}", flush=True)


def main() -> int:
    ap = argparse.ArgumentParser(
        description="Kestrel MSPT reference-table runner (vanilla vs Paper vs Kestrel)"
    )
    ap.add_argument(
        "--server",
        choices=SERVERS,
        help="server implementation under test (vanilla, paper, kestrel)",
    )
    ap.add_argument(
        "--jar",
        help="path to server jar binary",
    )
    ap.add_argument(
        "--seed",
        type=int,
        default=4242424242,
        help="world seed (long integer, default: 4242424242)",
    )
    ap.add_argument(
        "--ticks",
        type=int,
        default=600,
        help="target ticks to drive benchmark (default: 600)",
    )
    ap.add_argument(
        "--tps",
        type=float,
        default=20.0,
        help="target server ticks per second (default: 20.0)",
    )
    ap.add_argument(
        "--bots",
        type=int,
        default=1,
        help="number of tp-bots teleporting on spiral (default: 1)",
    )
    ap.add_argument(
        "--port",
        type=int,
        default=25575,
        help="server port (default: 25575)",
    )
    ap.add_argument(
        "--java",
        default="java",
        help="java binary path (default: java)",
    )
    ap.add_argument(
        "--jvm-flags",
        default="-Xmx2G",
        help="JVM flags passed to server jar (default: -Xmx2G)",
    )
    ap.add_argument(
        "--ready-timeout",
        type=int,
        default=180,
        help="server boot timeout in seconds (default: 180)",
    )
    ap.add_argument(
        "--output",
        "-o",
        help="output file path for JSON row result",
    )
    ap.add_argument(
        "--notes",
        default="",
        help="optional user notes to append to result row",
    )
    ap.add_argument(
        "--agent-jar",
        help="path to kestrel harness javaagent jar (records ticks.log)",
    )
    ap.add_argument(
        "--y",
        type=float,
        default=70.0,
        help="target Y coordinate for tp-bot spiral (default: 70.0)",
    )
    ap.add_argument(
        "--step",
        type=float,
        default=64.0,
        help="spiral step size in blocks (default: 64.0)",
    )
    ap.add_argument(
        "--sequential",
        action="store_true",
        help="drive tp-bots sequentially across spiral waypoints instead of concurrently",
    )
    ap.add_argument(
        "--keep-dir",
        action="store_true",
        help="keep temporary server directory after run instead of deleting",
    )
    ap.add_argument(
        "--summarize",
        nargs="*",
        help="read N JSON row-files and print a comparison markdown table",
    )
    ap.add_argument(
        "files",
        nargs="*",
        help="additional JSON row-files for summarize mode",
    )
    ap.add_argument(
        "--self-test",
        action="store_true",
        help="run self-test suite (synthetic log parse, schema assert, determinism assert) and exit",
    )

    args = ap.parse_args()

    # Self-test mode
    if args.self_test:
        return run_self_test()

    # Summarize mode
    if args.summarize is not None:
        input_files = list(args.summarize) + list(args.files)
        return run_summarize(input_files)

    # Server reference run mode
    if not args.server:
        ap.error("--server is required unless --self-test or --summarize is specified")

    return drive_server_run(args)


if __name__ == "__main__":
    sys.exit(main())
