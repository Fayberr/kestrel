#!/usr/bin/env python3
"""Kestrel JFR-baseline parser & independent cross-check for the ASM chunkgen stopwatch.

Parses OpenJDK Flight Recorder (JFR) ChunkGeneration events emitted by vanilla
Minecraft 26.2's JfrProfiler (ChunkStep.apply -> JvmProfiler.onChunkGenerate),
aggregating duration by stage (count, median, p95 per stage).

Background & Why stdlib uses 'jfr print':
  JFR is a binary chunk-based container format using dynamic event metadata
  descriptors, LEB128 variable-length integer encoding, constant pools, and
  JDK-version-dependent checkpoint records (varying across JDK 17, 21, and 25).
  Writing a robust binary JFR parser in stdlib Python without third-party C
  extensions is fragile and breaks across JVM releases. OpenJDK provides the
  canonical, forward-compatible CLI tool 'jfr print --events minecraft.ChunkGeneration'
  (or --json). This script accepts raw .jfr recordings via --jfr (which executes
  'jfr print' via subprocess) or accepts pre-extracted text/json via --jfr-text.

The Known Trust-Split Caveat:
  ChunkStep.apply wraps every chunk generation stage in:
    JvmProfiler.INSTANCE.onChunkGenerate(pos, dimension, status);
  The event begins before task.doWork and commits in thenApply (on completion).
  - INLINE stages (STRUCTURE_STARTS, STRUCTURE_REFERENCES, SURFACE, CARVERS,
    FEATURES, SPAWN):
    Executed synchronously on the single worldgen consecutive executor
    (completedFuture). JFR event duration closely measures true synchronous
    CPU work time. These stages serve as the independent cross-check for ASM probes
    (target: ASM within +-10% of JFR median).
  - ASYNC stages (BIOMES, NOISE on Util.backgroundExecutor worker pool;
    INITIALIZE_LIGHT, LIGHT on ThreadedLevelLightEngine light queue; FULL on main thread):
    Executed asynchronously. The JFR event begins before task dispatch and commits
    when the async future finishes, so JFR duration includes executor QUEUE WAIT latency.
    In contrast, current ASM probes (ChunkgenWatch) wrap only method execution
    (ChunkStatusTasks.generate* returning CompletableFuture) and record submit-only
    overhead (~0.02 ms).
  This script explicitly flags this split in all outputs and comparisons.

Usage:
  # Self-test (no JFR file needed, runs synthetic test suite):
  python3 harness/tools/jfr-baseline.py --self-test

  # Parse text dump from 'jfr print --events minecraft.ChunkGeneration recording.jfr':
  python3 harness/tools/jfr-baseline.py --jfr-text recording.txt --output result.json

  # Parse .jfr binary directly (requires 'jfr' in PATH or --jfr-cmd):
  python3 harness/tools/jfr-baseline.py --jfr recording.jfr --output result.json

  # Compare JFR baseline with ASM stopwatch CSV side-by-side:
  python3 harness/tools/jfr-baseline.py --jfr-text recording.txt --compare chunkgen-watch.csv

  # Match JFR events to ASM rows per-chunk on (chunkX, chunkZ, status) for inline stages and emit MATCH-JUDGE verdicts:
  python3 harness/tools/jfr-baseline.py --jfr-text recording.txt --match chunkgen-watch.csv

  # Specify seed and user notes:
  python3 harness/tools/jfr-baseline.py --jfr-text recording.txt --seed 4242424242 --notes "winpc 5700x3d run"
"""

import argparse
import csv
import io
import json
import math
import os
import re
import subprocess
import sys
from typing import Any, Dict, List, Optional, Set, Tuple

# Pipeline stage ordering in Minecraft 26.2 (ChunkStatus registry order)
STAGE_ORDER: List[str] = [
    "EMPTY",
    "STRUCTURE_STARTS",
    "STRUCTURE_REFERENCES",
    "BIOMES",
    "NOISE",
    "SURFACE",
    "CARVERS",
    "FEATURES",
    "INITIALIZE_LIGHT",
    "LIGHT",
    "SPAWN",
    "FULL",
]

# Stages that execute synchronously on the single worldgen consecutive executor.
# JFR duration reflects true work time (independent oracle for ASM probes).
INLINE_STAGES: Set[str] = {
    "EMPTY",
    "STRUCTURE_STARTS",
    "STRUCTURE_REFERENCES",
    "SURFACE",
    "CARVERS",
    "FEATURES",
    "SPAWN",
}

# Stages that fan out asynchronously to background worker pools, light queues,
# or main thread dispatch. JFR duration includes executor queue wait time.
ASYNC_STAGES: Set[str] = {
    "BIOMES",
    "NOISE",
    "INITIALIZE_LIGHT",
    "LIGHT",
    "FULL",
}

# Regex to extract key = value attributes in 'jfr print' event blocks
ATTR_REGEX = re.compile(r"^\s*([A-Za-z0-9_]+)\s*=\s*(.+?)\s*$")

# Regex to extract duration components (e.g., "12.34 ms", "1.5 s", "250 us", "120000 ns")
DURATION_PART_REGEX = re.compile(
    r"([0-9]+(?:[.,][0-9]+)?)\s*(d|h|m|s|ms|us|µs|ns)\b",
    re.IGNORECASE,
)

# Regex to parse ISO-8601 duration components (e.g., "PT0.035S", "PT1M2.5S")
ISO_DURATION_REGEX = re.compile(
    r"([0-9]+(?:[.,][0-9]+)?)\s*([HMS])",
    re.IGNORECASE,
)

# Benchmark log-parse line pattern: JFRBASE stage=<S> n=<n> med=<ms> p95=<ms>
JFRBASE_PATTERN = re.compile(
    r"^JFRBASE\s+stage=(?P<stage>\S+)\s+n=(?P<n>\d+)\s+med=(?P<med>[0-9.]+)\s+p95=(?P<p95>[0-9.]+)"
)

# Benchmark log-parse line pattern: MATCH-JUDGE stage=<S> n=<n> med_ratio=<r> verdict=PASS|FAIL
MATCH_JUDGE_PATTERN = re.compile(
    r"^MATCH-JUDGE\s+stage=(?P<stage>\S+)\s+n=(?P<n>\d+)\s+med_ratio=(?P<med_ratio>[0-9.]+)\s+verdict=(?P<verdict>PASS|FAIL)$"
)

# Per-chunk match line pattern: MATCH-CHUNK chunk=(<cx>,<cz>) stage=<S> asm_ms=<asm> jfr_ms=<jfr> ratio=<r>
MATCH_CHUNK_PATTERN = re.compile(
    r"^MATCH-CHUNK\s+chunk=\((?P<cx>-?\d+),(?P<cz>-?\d+)\)\s+stage=(?P<stage>\S+)\s+asm_ms=(?P<asm>[0-9.]+)\s+jfr_ms=(?P<jfr>[0-9.]+)\s+ratio=(?P<ratio>[0-9.]+|inf)$"
)


def get_trust_split(stage: str) -> str:
    """Return trust-split classification for a generation stage.

    Returns:
      'inline-work': synchronous work on worldgen executor (trustworthy work time).
      'queue-wait': async stage where JFR duration includes executor queue latency.
    """
    normalized = normalize_status(stage)
    if normalized in ASYNC_STAGES:
        return "queue-wait"
    return "inline-work"


def normalize_status(raw_status: str) -> str:
    """Normalize a chunk status identifier to uppercase snake_case.

    Handles namespace prefixes ('minecraft:features' -> 'FEATURES')
    and common alias forms.
    """
    status = raw_status.strip().strip('"').strip("'")
    if ":" in status:
        status = status.split(":", 1)[1]
    status = status.upper().replace("-", "_").replace(" ", "_")

    # Common short aliases
    if status == "INIT_LIGHT":
        return "INITIALIZE_LIGHT"
    if status == "STARTS":
        return "STRUCTURE_STARTS"
    if status in ("REFERENCES", "REFS"):
        return "STRUCTURE_REFERENCES"
    return status


def parse_duration_ms(val_str: str) -> float:
    """Parse duration string from JFR output into milliseconds float.

    Supports:
      - Standard units: ms, s, us, µs, ns, m, h, d (with '.' or ',' decimals)
      - Compound units: '1 s 20 ms'
      - ISO-8601 duration strings: 'PT0.035S', 'PT1S' (from 'jfr print --json')
      - Plain numeric strings: assumed to be milliseconds
    """
    s = val_str.strip().strip('"').strip("'")
    if not s:
        return 0.0

    # Handle ISO-8601 duration format (from JFR JSON output)
    if s.startswith("PT") or s.startswith("-PT"):
        parts = ISO_DURATION_REGEX.findall(s.replace(",", "."))
        if parts:
            total_ms = 0.0
            for num_str, unit in parts:
                v = float(num_str)
                u = unit.upper()
                if u == "H":
                    total_ms += v * 3600.0 * 1000.0
                elif u == "M":
                    total_ms += v * 60.0 * 1000.0
                elif u == "S":
                    total_ms += v * 1000.0
            return round(total_ms, 3)

    # Standard human-readable duration parsing
    matches = DURATION_PART_REGEX.findall(s)
    if matches:
        total_ms = 0.0
        for num_str, unit in matches:
            v = float(num_str.replace(",", "."))
            u = unit.lower()
            if u == "d":
                total_ms += v * 86400.0 * 1000.0
            elif u == "h":
                total_ms += v * 3600.0 * 1000.0
            elif u == "m":
                total_ms += v * 60.0 * 1000.0
            elif u == "s":
                total_ms += v * 1000.0
            elif u == "ms":
                total_ms += v
            elif u in ("us", "µs"):
                total_ms += v / 1000.0
            elif u == "ns":
                total_ms += v / 1_000_000.0
        return round(total_ms, 3)

    # Fallback: attempt plain numeric float parse
    try:
        return round(float(s.replace(",", ".")), 3)
    except ValueError:
        return 0.0


def calculate_median(values: List[float]) -> float:
    """Calculate the median of numeric values (same shape as mspt-table.py)."""
    if not values:
        return 0.0
    s = sorted(values)
    n = len(s)
    if n % 2 == 1:
        return round(s[n // 2], 3)
    else:
        return round((s[n // 2 - 1] + s[n // 2]) / 2.0, 3)


def calculate_percentile(values: List[float], pct: float = 95.0) -> float:
    """Calculate p-th percentile of numeric values (nearest rank, same shape as mspt-table.py)."""
    if not values:
        return 0.0
    s = sorted(values)
    n = len(s)
    rank = math.ceil(pct / 100.0 * n) - 1
    rank = max(0, min(rank, n - 1))
    return round(s[rank], 3)


def calculate_mean(values: List[float]) -> float:
    """Calculate arithmetic mean of numeric values."""
    if not values:
        return 0.0
    return round(sum(values) / len(values), 3)


def extract_event_blocks(text: str, target_event: str = "ChunkGeneration") -> List[str]:
    """Extract individual event text blocks matching the target event name.

    Tracks brace depth to handle nested attributes or multiline values cleanly.
    """
    blocks: List[str] = []
    lines = text.splitlines()
    in_event = False
    brace_depth = 0
    current_block: List[str] = []

    for line in lines:
        stripped = line.strip()
        if not in_event:
            # Check for event header like 'minecraft.ChunkGeneration {' or 'ChunkGeneration {'
            if target_event in stripped and "{" in stripped:
                in_event = True
                brace_depth = line.count("{") - line.count("}")
                current_block = [line]
                if brace_depth <= 0:
                    blocks.append("\n".join(current_block))
                    in_event = False
                    current_block = []
        else:
            current_block.append(line)
            brace_depth += line.count("{") - line.count("}")
            if brace_depth <= 0:
                blocks.append("\n".join(current_block))
                in_event = False
                brace_depth = 0
                current_block = []

    return blocks


def parse_jfr_text(raw_text: str) -> Dict[str, Any]:
    """Parse ChunkGeneration events from JFR output (plain text or JSON).

    Returns:
      {
        "samples_by_stage": { STATUS: [dur_ms, ...] },
        "unique_chunks": Set[Tuple[int, int]],
        "raw_event_count": int,
        "events_by_chunk_stage": { (cx, cz, STATUS): [dur_ms, ...] }
      }
    """
    samples_by_stage: Dict[str, List[float]] = {}
    unique_chunks: Set[Tuple[int, int]] = set()
    events_by_chunk_stage: Dict[Tuple[int, int, str], List[float]] = {}
    raw_event_count = 0

    stripped_input = raw_text.lstrip()

    # Path A: Input is JSON (from 'jfr print --json')
    if stripped_input.startswith("{") or stripped_input.startswith("["):
        try:
            data = json.loads(raw_text)
            events: List[Dict[str, Any]] = []
            if isinstance(data, dict):
                # Standard OpenJDK JFR JSON structure: { "recording": { "events": [...] } }
                rec = data.get("recording", {})
                if isinstance(rec, dict) and "events" in rec:
                    events = rec["events"]
                elif "events" in data:
                    events = data["events"]
            elif isinstance(data, list):
                events = data

            for ev in events:
                ev_type = str(ev.get("type", ""))
                if "ChunkGeneration" not in ev_type:
                    continue
                vals = ev.get("values", {})
                dur_raw = str(vals.get("duration", "0"))
                dur_ms = parse_duration_ms(dur_raw)
                status_raw = str(vals.get("status", ""))
                if not status_raw:
                    continue
                status = normalize_status(status_raw)

                cx_val = vals.get("chunkPosX", vals.get("chunkX"))
                cz_val = vals.get("chunkPosZ", vals.get("chunkZ"))
                if cx_val is not None and cz_val is not None:
                    try:
                        cx = int(cx_val)
                        cz = int(cz_val)
                        unique_chunks.add((cx, cz))
                        events_by_chunk_stage.setdefault((cx, cz, status), []).append(dur_ms)
                    except (ValueError, TypeError):
                        pass

                samples_by_stage.setdefault(status, []).append(dur_ms)
                raw_event_count += 1

            return {
                "samples_by_stage": samples_by_stage,
                "unique_chunks": unique_chunks,
                "raw_event_count": raw_event_count,
                "events_by_chunk_stage": events_by_chunk_stage,
            }
        except json.JSONDecodeError:
            pass  # Not valid JSON, fall through to text block parser

    # Path B: Standard 'jfr print' text output
    blocks = extract_event_blocks(raw_text, "ChunkGeneration")
    for block in blocks:
        status: Optional[str] = None
        dur_ms: Optional[float] = None
        cx: Optional[int] = None
        cz: Optional[int] = None

        for line in block.splitlines():
            m = ATTR_REGEX.match(line)
            if not m:
                continue
            k, v = m.group(1), m.group(2)
            k_lower = k.lower()

            if k_lower == "status":
                status = normalize_status(v)
            elif k_lower == "duration":
                dur_ms = parse_duration_ms(v)
            elif k_lower in ("chunkposx", "chunkx", "x"):
                try:
                    clean_v = v.split()[0].replace(",", "").strip('"').strip("'")
                    cx = int(clean_v)
                except (ValueError, IndexError):
                    pass
            elif k_lower in ("chunkposz", "chunkz", "z"):
                try:
                    clean_v = v.split()[0].replace(",", "").strip('"').strip("'")
                    cz = int(clean_v)
                except (ValueError, IndexError):
                    pass

        if status is not None and dur_ms is not None:
            samples_by_stage.setdefault(status, []).append(dur_ms)
            if cx is not None and cz is not None:
                unique_chunks.add((cx, cz))
                events_by_chunk_stage.setdefault((cx, cz, status), []).append(dur_ms)
            raw_event_count += 1

    return {
        "samples_by_stage": samples_by_stage,
        "unique_chunks": unique_chunks,
        "raw_event_count": raw_event_count,
        "events_by_chunk_stage": events_by_chunk_stage,
    }


def parse_stopwatch_csv(csv_content_or_path: str) -> Dict[str, Any]:
    """Parse ASM chunkgen-watch.csv output.

    Expected header shape from ChunkgenWatch.java:
      chunk_x,chunk_z,total_ms,structure_starts_ms,structure_references_ms,biomes_ms,...

    Returns:
      {
        "chunks": int,
        "stages": {
          STATUS: { "n": int, "med_ms": float, "p95_ms": float, "mean_ms": float }
        },
        "by_chunk": {
          (chunk_x, chunk_z): { STATUS: float_ms, ... }
        }
      }
    """
    if "\n" not in csv_content_or_path and os.path.exists(csv_content_or_path):
        with open(csv_content_or_path, "r", encoding="utf-8", errors="replace") as f:
            lines = f.readlines()
    else:
        lines = csv_content_or_path.strip().splitlines()

    reader = csv.reader(lines)
    header = next(reader, None)
    if not header:
        return {"chunks": 0, "stages": {}, "by_chunk": {}}

    col_to_stage: Dict[int, str] = {}
    cx_idx: Optional[int] = None
    cz_idx: Optional[int] = None

    for idx, col in enumerate(header):
        c = col.strip().lower()
        if c in ("chunk_x", "chunkx", "cx", "x"):
            cx_idx = idx
            continue
        if c in ("chunk_z", "chunkz", "cz", "z"):
            cz_idx = idx
            continue
        if c == "total_ms":
            continue
        if c.endswith("_ms"):
            stage_name = normalize_status(c[:-3])
            col_to_stage[idx] = stage_name

    stage_samples: Dict[str, List[float]] = {s: [] for s in col_to_stage.values()}
    rows_by_chunk: Dict[Tuple[int, int], Dict[str, float]] = {}
    row_count = 0

    for row in reader:
        if not row:
            continue
        row_count += 1
        cx: Optional[int] = None
        cz: Optional[int] = None

        if cx_idx is not None and cx_idx < len(row):
            try:
                cx = int(float(row[cx_idx].strip().replace(",", ".")))
            except ValueError:
                pass
        if cz_idx is not None and cz_idx < len(row):
            try:
                cz = int(float(row[cz_idx].strip().replace(",", ".")))
            except ValueError:
                pass

        chunk_stages: Dict[str, float] = {}
        for idx, stage in col_to_stage.items():
            if idx < len(row):
                try:
                    val = float(row[idx].strip().replace(",", "."))
                    stage_samples[stage].append(val)
                    chunk_stages[stage] = val
                except ValueError:
                    pass

        if cx is not None and cz is not None:
            rows_by_chunk[(cx, cz)] = chunk_stages

    stages_result: Dict[str, Dict[str, Any]] = {}
    for stage, vals in stage_samples.items():
        if vals:
            stages_result[stage] = {
                "n": len(vals),
                "med_ms": calculate_median(vals),
                "p95_ms": calculate_percentile(vals, 95.0),
                "mean_ms": calculate_mean(vals),
                "trust": get_trust_split(stage),
            }

    return {
        "chunks": row_count,
        "stages": stages_result,
        "by_chunk": rows_by_chunk,
    }


def aggregate_jfr_stages(parsed_jfr: Dict[str, Any]) -> Tuple[int, Dict[str, Dict[str, Any]]]:
    """Aggregate raw samples by stage into median and p95 metrics.

    Returns:
      (chunks_count, { STATUS: { "n": int, "med_ms": float, "p95_ms": float, "trust": str } })
    """
    samples_by_stage = parsed_jfr["samples_by_stage"]
    unique_chunks = parsed_jfr["unique_chunks"]

    stages: Dict[str, Dict[str, Any]] = {}

    # Order stages canonically
    ordered_keys = [s for s in STAGE_ORDER if s in samples_by_stage]
    extra_keys = sorted([s for s in samples_by_stage if s not in STAGE_ORDER])

    for status in ordered_keys + extra_keys:
        samples = samples_by_stage[status]
        n = len(samples)
        med_ms = calculate_median(samples)
        p95_ms = calculate_percentile(samples, 95.0)
        trust = get_trust_split(status)
        stages[status] = {
            "n": n,
            "med_ms": med_ms,
            "p95_ms": p95_ms,
            "trust": trust,
        }

    chunks_count = (
        len(unique_chunks)
        if unique_chunks
        else max((len(v) for v in samples_by_stage.values()), default=0)
    )

    return chunks_count, stages


def create_json_output(
    chunks: int,
    stages: Dict[str, Dict[str, Any]],
    seed: Optional[int] = None,
    notes: str = "",
    match_results: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """Create standardized benchmark result JSON conforming to project schema:

      {
        "seed": 4242424242,   # optional
        "chunks": 81,
        "stages": {
          "FEATURES": {
            "n": 81,
            "med_ms": 35.10,
            "p95_ms": 44.00,
            "trust": "inline-work"
          },
          ...
        },
        "notes": "...",
        "match": {            # optional, when --match is used
          "FEATURES": {
            "n": 81,
            "med_ratio": 1.012,
            "p95_ratio": 1.045,
            "verdict": "PASS"
          }
        }
      }
    """
    res: Dict[str, Any] = {
        "chunks": int(chunks),
        "stages": stages,
        "notes": str(notes),
    }
    if seed is not None:
        res["seed"] = int(seed)
    if match_results is not None:
        res["match"] = match_results
    return res


def validate_json_schema(data: Dict[str, Any]) -> Tuple[bool, str]:
    """Validate that dictionary conforms strictly to the JFR baseline schema."""
    if not isinstance(data, dict):
        return False, f"root must be dict, got {type(data).__name__}"

    required_keys = {"chunks", "stages", "notes"}
    missing = required_keys - set(data.keys())
    if missing:
        return False, f"missing required root fields: {sorted(missing)}"

    if not isinstance(data["chunks"], int) or data["chunks"] < 0:
        return False, f"chunks must be non-negative int, got {data.get('chunks')!r}"

    if not isinstance(data["notes"], str):
        return False, f"notes must be str, got {type(data['notes']).__name__}"

    if "seed" in data and data["seed"] is not None:
        if not isinstance(data["seed"], int):
            return False, f"seed must be int, got {type(data['seed']).__name__}"

    if not isinstance(data["stages"], dict):
        return False, f"stages must be dict, got {type(data['stages']).__name__}"

    for stage_name, stage_val in data["stages"].items():
        if not isinstance(stage_name, str) or not stage_name:
            return False, f"invalid stage name: {stage_name!r}"
        if not isinstance(stage_val, dict):
            return False, f"stage {stage_name} value must be dict, got {type(stage_val).__name__}"

        stage_keys = {"n", "med_ms", "p95_ms", "trust"}
        missing_stage_keys = stage_keys - set(stage_val.keys())
        if missing_stage_keys:
            return False, f"stage {stage_name} missing fields: {sorted(missing_stage_keys)}"

        if not isinstance(stage_val["n"], int) or stage_val["n"] < 0:
            return False, f"stage {stage_name} n must be non-negative int"
        if not isinstance(stage_val["med_ms"], (int, float)):
            return False, f"stage {stage_name} med_ms must be numeric"
        if not isinstance(stage_val["p95_ms"], (int, float)):
            return False, f"stage {stage_name} p95_ms must be numeric"

        trust_val = stage_val["trust"]
        if trust_val not in ("inline-work", "queue-wait"):
            return False, f"stage {stage_name} trust must be 'inline-work' or 'queue-wait', got {trust_val!r}"

        # Trust-split validation: assert async stages are correctly flagged
        norm_stage = normalize_status(stage_name)
        if norm_stage in ASYNC_STAGES and trust_val != "queue-wait":
            return False, f"async stage {stage_name} must be flagged 'queue-wait', got {trust_val!r}"
        if norm_stage in INLINE_STAGES and trust_val != "inline-work":
            return False, f"inline stage {stage_name} must be flagged 'inline-work', got {trust_val!r}"

    if "match" in data and data["match"] is not None:
        if not isinstance(data["match"], dict):
            return False, f"match must be dict, got {type(data['match']).__name__}"
        for m_stage, m_val in data["match"].items():
            if not isinstance(m_val, dict):
                return False, f"match stage {m_stage} must be dict"
            for required_f in ("n", "med_ratio", "p95_ratio", "verdict"):
                if required_f not in m_val:
                    return False, f"match stage {m_stage} missing field: {required_f}"
            if not isinstance(m_val["n"], int) or m_val["n"] < 0:
                return False, f"match stage {m_stage} n must be non-negative int"
            if not isinstance(m_val["med_ratio"], (int, float)):
                return False, f"match stage {m_stage} med_ratio must be numeric"
            if not isinstance(m_val["p95_ratio"], (int, float)):
                return False, f"match stage {m_stage} p95_ratio must be numeric"
            if m_val["verdict"] not in ("PASS", "FAIL"):
                return False, f"match stage {m_stage} verdict must be PASS or FAIL"

    return True, "valid"


def format_jfrbase_line(stage: str, n: int, med: float, p95: float) -> str:
    """Format benchmark runner result line for log parsing:

    JFRBASE stage=<S> n=<n> med=<ms> p95=<ms>
    """
    return f"JFRBASE stage={stage} n={n} med={med:.2f} p95={p95:.2f}"


def format_comparison_table(
    jfr_stages: Dict[str, Dict[str, Any]],
    asm_stages: Dict[str, Dict[str, Any]],
) -> str:
    """Format side-by-side comparison table (JFR vs ASM-probe) with trust split caveats."""
    all_stages: List[str] = []
    # Collect stages in canonical order first
    for s in STAGE_ORDER:
        if s in jfr_stages or s in asm_stages:
            all_stages.append(s)
    # Collect any extra stages
    extra = sorted(
        set(jfr_stages.keys()).union(set(asm_stages.keys())) - set(STAGE_ORDER)
    )
    all_stages.extend(extra)

    lines: List[str] = [
        "| Stage | Trust Split | JFR n | JFR (med / p95) | ASM n | ASM (med / p95) | Ratio (JFR/ASM) | Notes |",
        "| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |",
    ]

    for stage in all_stages:
        trust = get_trust_split(stage)

        jfr_entry = jfr_stages.get(stage)
        asm_entry = asm_stages.get(stage)

        if jfr_entry:
            jfr_n_str = str(jfr_entry["n"])
            jfr_med = jfr_entry["med_ms"]
            jfr_p95 = jfr_entry["p95_ms"]
            jfr_cell = f"{jfr_med:.2f} / {jfr_p95:.2f} ms"
        else:
            jfr_n_str = "-"
            jfr_med = None
            jfr_cell = "N/A"

        if asm_entry:
            asm_n_str = str(asm_entry["n"])
            asm_med = asm_entry["med_ms"]
            asm_p95 = asm_entry["p95_ms"]
            asm_cell = f"{asm_med:.2f} / {asm_p95:.2f} ms"
        else:
            asm_n_str = "-"
            asm_med = None
            asm_cell = "N/A"

        if jfr_med is not None and asm_med is not None:
            if asm_med > 0.001:
                ratio = jfr_med / asm_med
                ratio_str = f"{ratio:.2f}x"
            else:
                ratio_str = "inf"
        else:
            ratio_str = "-"

        if trust == "queue-wait":
            note = "Async stage: JFR includes queue wait; ASM is submit-only"
        else:
            if jfr_med is not None and asm_med is not None:
                delta_pct = (
                    abs(jfr_med - asm_med) / max(asm_med, 0.001) * 100.0
                )
                if delta_pct <= 10.0:
                    note = f"Inline agreement (delta: {delta_pct:.1f}%)"
                else:
                    note = f"Inline divergence (delta: {delta_pct:.1f}%)"
            else:
                note = "Inline work"

        lines.append(
            f"| {stage} | {trust} | {jfr_n_str} | {jfr_cell} | {asm_n_str} | {asm_cell} | {ratio_str} | {note} |"
        )

    lines.append("")
    lines.append(
        "Trust-Split Caveat:\n"
        "  - INLINE stages (STARTS, REFS, SURFACE, CARVERS, FEATURES, SPAWN):\n"
        "    Executed synchronously on the single worldgen executor.\n"
        "    JFR duration measures true synchronous CPU work time and serves as\n"
        "    an independent cross-check for the ASM stopwatch (target: +-10% agreement).\n"
        "  - ASYNC stages (BIOMES, NOISE on worker pool; INITIALIZE_LIGHT, LIGHT on light queue; FULL on main thread):\n"
        "    Executed asynchronously. JFR ChunkGeneration events begin before task submission\n"
        "    and commit in thenApply, thereby including executor queue wait latency.\n"
        "    In contrast, current ASM probes wrap only method execution (future creation) and record ~0.02 ms."
    )

    return "\n".join(lines)


def format_match_chunk_line(
    cx: int,
    cz: int,
    stage: str,
    asm_ms: float,
    jfr_ms: float,
    ratio: float,
) -> str:
    """Format per-matched chunk line:

    MATCH-CHUNK chunk=(<cx>,<cz>) stage=<S> asm_ms=<asm> jfr_ms=<jfr> ratio=<r>
    """
    ratio_str = f"{ratio:.3f}" if ratio != float("inf") else "inf"
    return (
        f"MATCH-CHUNK chunk=({cx},{cz}) stage={stage} "
        f"asm_ms={asm_ms:.3f} jfr_ms={jfr_ms:.3f} ratio={ratio_str}"
    )


def format_match_stage_line(
    stage: str,
    n: int,
    med_ratio: float,
    p95_ratio: float,
) -> str:
    """Format per-stage aggregate line:

    MATCH-STAGE stage=<S> n=<n> med_ratio=<med> p95_ratio=<p95>
    """
    return (
        f"MATCH-STAGE stage={stage} n={n} "
        f"med_ratio={med_ratio:.3f} p95_ratio={p95_ratio:.3f}"
    )


def format_match_judge_line(
    stage: str,
    n: int,
    med_ratio: float,
    verdict: str,
) -> str:
    """Format benchmark runner verdict line for log parsing:

    MATCH-JUDGE stage=<S> n=<n> med_ratio=<r> verdict=PASS|FAIL
    """
    return f"MATCH-JUDGE stage={stage} n={n} med_ratio={med_ratio:.3f} verdict={verdict}"


def format_match_table(match_result: Dict[str, Any]) -> str:
    """Format markdown summary table for matched inline stages."""
    lines: List[str] = [
        "| Stage | Matched Chunks (n) | Median Ratio (ASM/JFR) | P95 Ratio | Verdict | Gate (0.9-1.1) |",
        "| :--- | :--- | :--- | :--- | :--- | :--- |",
    ]
    for stage, s in match_result["stage_summaries"].items():
        lines.append(
            f"| {stage} | {s['n']} | {s['med_ratio']:.3f} | {s['p95_ratio']:.3f} | {s['verdict']} | {'PASS' if s['verdict'] == 'PASS' else 'FAIL'} |"
        )
    return "\n".join(lines)


def match_jfr_and_asm(
    parsed_jfr: Dict[str, Any],
    parsed_csv: Dict[str, Any],
) -> Dict[str, Any]:
    """Join JFR events to ASM stopwatch rows on (chunkX, chunkZ, status) for INLINE stages only.

    Trust split:
      Only stages where get_trust_split(stage) == 'inline-work' are evaluated.
      Async stages (BIOMES, NOISE, INITIALIZE_LIGHT, LIGHT, FULL) are excluded because
      JFR duration includes queue-wait while ASM measures submit-only overhead.

    Per matched chunk+stage:
      computes ratio = asm_ms / jfr_ms

    Per stage aggregate:
      - n: match count
      - med_ratio: median of ratios
      - p95_ratio: 95th percentile of ratios
      - verdict: PASS if 0.90 <= round(med_ratio, 4) <= 1.10 else FAIL

    Returns:
      {
        "per_chunk_matches": [
          { "chunk": (cx, cz), "chunk_x": cx, "chunk_z": cz, "stage": stage,
            "asm_ms": float, "jfr_ms": float, "ratio": float },
          ...
        ],
        "stage_summaries": {
          STAGE: { "n": int, "med_ratio": float, "p95_ratio": float, "verdict": str, "verdict_line": str }
        },
        "verdict_lines": [ str, ... ]
      }
    """
    jfr_events: Dict[Tuple[int, int, str], List[float]] = parsed_jfr.get("events_by_chunk_stage", {})
    csv_by_chunk: Dict[Tuple[int, int], Dict[str, float]] = parsed_csv.get("by_chunk", {})

    per_chunk_matches: List[Dict[str, Any]] = []
    matched_by_stage: Dict[str, List[Dict[str, Any]]] = {}

    # Identify chunks present in both JFR and CSV
    jfr_chunks = set(k[:2] for k in jfr_events.keys())
    common_chunks = sorted(jfr_chunks.intersection(csv_by_chunk.keys()))

    # Determine candidate inline stages
    all_stages = list(STAGE_ORDER)
    extra_stages = sorted(
        set(k[2] for k in jfr_events.keys()).union(
            *[s.keys() for s in csv_by_chunk.values()]
        ) - set(STAGE_ORDER)
    )
    all_stages.extend(extra_stages)

    ordered_inline_stages = [s for s in all_stages if get_trust_split(s) == "inline-work"]

    for chunk in common_chunks:
        cx, cz = chunk
        csv_stages = csv_by_chunk.get(chunk, {})
        for stage in ordered_inline_stages:
            jfr_key = (cx, cz, stage)
            if jfr_key in jfr_events and stage in csv_stages:
                jfr_durs = jfr_events[jfr_key]
                jfr_ms = calculate_median(jfr_durs)
                asm_ms = csv_stages[stage]

                if jfr_ms > 0:
                    ratio = asm_ms / jfr_ms
                elif asm_ms == 0:
                    ratio = 1.0
                else:
                    ratio = float("inf")

                match_entry = {
                    "chunk": (cx, cz),
                    "chunk_x": cx,
                    "chunk_z": cz,
                    "stage": stage,
                    "asm_ms": round(asm_ms, 3),
                    "jfr_ms": round(jfr_ms, 3),
                    "ratio": round(ratio, 4),
                }
                per_chunk_matches.append(match_entry)
                matched_by_stage.setdefault(stage, []).append(match_entry)

    stage_summaries: Dict[str, Dict[str, Any]] = {}
    verdict_lines: List[str] = []

    # Check which inline stages were present in both inputs
    jfr_stages_present = set(k[2] for k in jfr_events.keys())
    csv_stages_present = set(s for c_dict in csv_by_chunk.values() for s in c_dict.keys())
    both_present = jfr_stages_present.intersection(csv_stages_present)

    for stage in ordered_inline_stages:
        if stage in matched_by_stage:
            entries = matched_by_stage[stage]
            ratios = [e["ratio"] for e in entries]
            n = len(ratios)
            med_ratio = calculate_median(ratios)
            p95_ratio = calculate_percentile(ratios, 95.0)

            is_pass = (0.90 <= round(med_ratio, 4) <= 1.10)
            verdict = "PASS" if is_pass else "FAIL"

            v_line = format_match_judge_line(stage, n, med_ratio, verdict)
            verdict_lines.append(v_line)

            stage_summaries[stage] = {
                "n": n,
                "med_ratio": med_ratio,
                "p95_ratio": p95_ratio,
                "verdict": verdict,
                "verdict_line": v_line,
            }
        elif stage in both_present:
            # Stage was present in both inputs, but 0 chunks matched
            v_line = format_match_judge_line(stage, 0, 0.0, "FAIL")
            verdict_lines.append(v_line)
            stage_summaries[stage] = {
                "n": 0,
                "med_ratio": 0.0,
                "p95_ratio": 0.0,
                "verdict": "FAIL",
                "verdict_line": v_line,
            }

    return {
        "per_chunk_matches": per_chunk_matches,
        "stage_summaries": stage_summaries,
        "verdict_lines": verdict_lines,
    }


def run_self_test() -> int:
    """Execute standalone verification suite without requiring JFR files or JDK."""
    print("[self-test] starting jfr-baseline verification...", flush=True)
    tests_run = 0

    # 1. Synthetic JFR print text fixture (human-readable format)
    synthetic_jfr_text = """
# JFR dump test
minecraft.ChunkGeneration {
  startTime = 12:00:00.100
  duration = 0.05 ms
  chunkPosX = 0
  chunkPosZ = 0
  status = "minecraft:structure_starts"
}
minecraft.ChunkGeneration {
  startTime = 12:00:00.110
  duration = 0.10 ms
  chunkPosX = 0
  chunkPosZ = 1
  status = "structure_starts"
}
minecraft.ChunkGeneration {
  startTime = 12:00:00.120
  duration = 0.15 ms
  chunkPosX = 1
  chunkPosZ = 0
  status = "STRUCTURE_STARTS"
}
minecraft.ChunkGeneration {
  startTime = 12:00:00.200
  duration = 12.0 ms
  chunkPosX = 0
  chunkPosZ = 0
  status = "minecraft:biomes"
}
minecraft.ChunkGeneration {
  startTime = 12:00:00.210
  duration = 15,0 ms
  chunkPosX = 0
  chunkPosZ = 1
  status = "biomes"
}
minecraft.ChunkGeneration {
  startTime = 12:00:00.220
  duration = 18.0 ms
  chunkPosX = 1
  chunkPosZ = 0
  status = "BIOMES"
}
minecraft.ChunkGeneration {
  startTime = 12:00:00.300
  duration = 35.0 ms
  chunkPosX = 0
  chunkPosZ = 0
  status = "noise"
}
minecraft.ChunkGeneration {
  startTime = 12:00:00.310
  duration = 40.0 ms
  chunkPosX = 0
  chunkPosZ = 1
  status = "noise"
}
minecraft.ChunkGeneration {
  startTime = 12:00:00.320
  duration = 45.0 ms
  chunkPosX = 1
  chunkPosZ = 0
  status = "noise"
}
minecraft.ChunkGeneration {
  startTime = 12:00:00.400
  duration = 8.0 ms
  chunkPosX = 0
  chunkPosZ = 0
  status = "surface"
}
minecraft.ChunkGeneration {
  startTime = 12:00:00.410
  duration = 9.0 ms
  chunkPosX = 0
  chunkPosZ = 1
  status = "surface"
}
minecraft.ChunkGeneration {
  startTime = 12:00:00.420
  duration = 10.0 ms
  chunkPosX = 1
  chunkPosZ = 0
  status = "surface"
}
minecraft.ChunkGeneration {
  startTime = 12:00:00.500
  duration = 4.5 ms
  chunkPosX = 0
  chunkPosZ = 0
  status = "carvers"
}
minecraft.ChunkGeneration {
  startTime = 12:00:00.510
  duration = 5.0 ms
  chunkPosX = 0
  chunkPosZ = 1
  status = "carvers"
}
minecraft.ChunkGeneration {
  startTime = 12:00:00.520
  duration = 5.5 ms
  chunkPosX = 1
  chunkPosZ = 0
  status = "carvers"
}
minecraft.ChunkGeneration {
  startTime = 12:00:00.600
  duration = 32.0 ms
  chunkPosX = 0
  chunkPosZ = 0
  status = "features"
}
minecraft.ChunkGeneration {
  startTime = 12:00:00.610
  duration = 35.0 ms
  chunkPosX = 0
  chunkPosZ = 1
  status = "features"
}
minecraft.ChunkGeneration {
  startTime = 12:00:00.620
  duration = 38.0 ms
  chunkPosX = 1
  chunkPosZ = 0
  status = "features"
}
minecraft.ChunkGeneration {
  startTime = 12:00:00.700
  duration = 1.5 ms
  chunkPosX = 0
  chunkPosZ = 0
  status = "initialize_light"
}
minecraft.ChunkGeneration {
  startTime = 12:00:00.710
  duration = 2.0 ms
  chunkPosX = 0
  chunkPosZ = 1
  status = "initialize_light"
}
minecraft.ChunkGeneration {
  startTime = 12:00:00.720
  duration = 2.5 ms
  chunkPosX = 1
  chunkPosZ = 0
  status = "initialize_light"
}
minecraft.ChunkGeneration {
  startTime = 12:00:00.800
  duration = 3.5 ms
  chunkPosX = 0
  chunkPosZ = 0
  status = "light"
}
minecraft.ChunkGeneration {
  startTime = 12:00:00.810
  duration = 4.0 ms
  chunkPosX = 0
  chunkPosZ = 1
  status = "light"
}
minecraft.ChunkGeneration {
  startTime = 12:00:00.820
  duration = 4.5 ms
  chunkPosX = 1
  chunkPosZ = 0
  status = "light"
}
"""
    p1 = parse_jfr_text(synthetic_jfr_text)
    assert p1["raw_event_count"] == 24, f"expected 24 events, got {p1['raw_event_count']}"
    assert len(p1["unique_chunks"]) == 3, f"expected 3 unique chunks, got {len(p1['unique_chunks'])}"

    chunks1, stages1 = aggregate_jfr_stages(p1)
    assert chunks1 == 3, f"expected 3 chunks, got {chunks1}"

    # Assert parsed medians
    expected_medians = {
        "STRUCTURE_STARTS": 0.10,
        "BIOMES": 15.00,
        "NOISE": 40.00,
        "SURFACE": 9.00,
        "CARVERS": 5.00,
        "FEATURES": 35.00,
        "INITIALIZE_LIGHT": 2.00,
        "LIGHT": 4.00,
    }
    for st, exp_med in expected_medians.items():
        assert st in stages1, f"stage {st} missing from parsed results"
        actual_med = stages1[st]["med_ms"]
        assert abs(actual_med - exp_med) < 1e-3, (
            f"median mismatch for {st}: expected {exp_med}, got {actual_med}"
        )
    tests_run += 1
    print(
        f"[self-test] 1. Synthetic JFR text fixture parsing: PASS (24 events, 3 chunks, 8 stages parsed correctly)",
        flush=True,
    )

    # 2. Schema validation and JSON round-trip assert
    output_obj = create_json_output(
        chunks=chunks1,
        stages=stages1,
        seed=4242424242,
        notes="self-test synthetic run",
    )
    valid, msg = validate_json_schema(output_obj)
    assert valid, f"schema validation failed on valid object: {msg}"

    # Round-trip JSON test
    serialized = json.dumps(output_obj)
    deserialized = json.loads(serialized)
    assert deserialized == output_obj, "JSON round-trip mismatch"

    # Schema rejection tests
    bad_obj1 = dict(output_obj)
    del bad_obj1["stages"]
    valid_bad1, _ = validate_json_schema(bad_obj1)
    assert not valid_bad1, "schema validator failed to reject missing 'stages'"

    bad_obj2 = json.loads(serialized)
    bad_obj2["stages"]["FEATURES"]["med_ms"] = "not-a-number"
    valid_bad2, _ = validate_json_schema(bad_obj2)
    assert not valid_bad2, "schema validator failed to reject non-numeric med_ms"
    tests_run += 1
    print(
        "[self-test] 2. JSON schema validation & round-trip: PASS (validates schema, rejects malformed payloads)",
        flush=True,
    )

    # 3. Trust-split labeling assert (async stages flagged queue-wait)
    for stage_name, stage_info in stages1.items():
        trust = stage_info["trust"]
        if stage_name in ASYNC_STAGES:
            assert trust == "queue-wait", (
                f"async stage {stage_name} must be flagged 'queue-wait', got {trust!r}"
            )
        elif stage_name in INLINE_STAGES:
            assert trust == "inline-work", (
                f"inline stage {stage_name} must be flagged 'inline-work', got {trust!r}"
            )

    # Verify invalid trust assignment is rejected by schema validator
    bad_obj3 = json.loads(serialized)
    bad_obj3["stages"]["NOISE"]["trust"] = "inline-work"  # NOISE is async, must be queue-wait!
    valid_bad3, _ = validate_json_schema(bad_obj3)
    assert not valid_bad3, "schema validator failed to reject async stage labeled as inline-work"
    tests_run += 1
    print(
        "[self-test] 3. Trust-split labeling assert: PASS (BIOMES/NOISE/LIGHT flagged 'queue-wait', STARTS/SURFACE/CARVERS/FEATURES flagged 'inline-work')",
        flush=True,
    )

    # 4. Stopwatch CSV parsing & side-by-side comparison table assert
    synthetic_csv = """chunk_x,chunk_z,total_ms,structure_starts_ms,structure_references_ms,biomes_ms,noise_ms,surface_ms,carvers_ms,features_ms,initialize_light_ms,light_ms
0,0,49.200,0.100,0.000,0.020,0.015,9.200,5.100,35.000,0.500,1.200
0,1,48.100,0.080,0.000,0.019,0.016,8.900,4.950,34.800,0.480,1.150
1,0,49.800,0.120,0.000,0.021,0.015,9.100,5.050,35.200,0.520,1.250
"""
    parsed_csv = parse_stopwatch_csv(synthetic_csv)
    assert parsed_csv["chunks"] == 3, f"expected 3 CSV rows, got {parsed_csv['chunks']}"
    asm_stages = parsed_csv["stages"]
    assert "FEATURES" in asm_stages
    assert abs(asm_stages["FEATURES"]["med_ms"] - 35.0) < 1e-3
    assert abs(asm_stages["NOISE"]["med_ms"] - 0.015) < 1e-3

    table_str = format_comparison_table(stages1, asm_stages)
    assert "| Stage | Trust Split |" in table_str
    assert "FEATURES" in table_str
    assert "queue-wait" in table_str
    assert "inline-work" in table_str
    assert "Trust-Split Caveat:" in table_str
    tests_run += 1
    print(
        "[self-test] 4. Stopwatch CSV parsing & side-by-side table: PASS (JFR vs ASM table formatted with trust-split caveats)",
        flush=True,
    )

    # 5. JFRBASE line formatting and regex parsing assert
    lines = []
    for st in ("FEATURES", "SURFACE", "NOISE"):
        d = stages1[st]
        line = format_jfrbase_line(st, d["n"], d["med_ms"], d["p95_ms"])
        m = JFRBASE_PATTERN.match(line)
        assert m is not None, f"pattern failed to match line: {line}"
        assert m.group("stage") == st
        assert int(m.group("n")) == d["n"]
        assert float(m.group("med")) == d["med_ms"]
        assert float(m.group("p95")) == d["p95_ms"]
        lines.append(line)
    tests_run += 1
    print(
        f"[self-test] 5. JFRBASE line emission assert: PASS ({lines[0]})",
        flush=True,
    )

    # 6. Synthetic JFR JSON format parsing (ISO-8601 duration support)
    synthetic_json_jfr = json.dumps({
        "recording": {
            "events": [
                {
                    "type": "minecraft.ChunkGeneration",
                    "values": {
                        "chunkPosX": 10,
                        "chunkPosZ": -5,
                        "status": "features",
                        "duration": "PT0.035S",
                    },
                },
                {
                    "type": "minecraft.ChunkGeneration",
                    "values": {
                        "chunkPosX": 10,
                        "chunkPosZ": -5,
                        "status": "surface",
                        "duration": "PT0.009S",
                    },
                },
            ]
        }
    })
    p_json = parse_jfr_text(synthetic_json_jfr)
    assert p_json["raw_event_count"] == 2
    assert "FEATURES" in p_json["samples_by_stage"]
    assert abs(p_json["samples_by_stage"]["FEATURES"][0] - 35.0) < 1e-3
    assert abs(p_json["samples_by_stage"]["SURFACE"][0] - 9.0) < 1e-3
    tests_run += 1
    print(
        "[self-test] 6. JFR JSON format & ISO-8601 duration parsing: PASS (PT0.035S -> 35.0 ms)",
        flush=True,
    )

    # 7. Duration unit parsing edge cases
    assert abs(parse_duration_ms("12.34 ms") - 12.34) < 1e-3
    assert abs(parse_duration_ms("12,34 ms") - 12.34) < 1e-3
    assert abs(parse_duration_ms("1.5 s") - 1500.0) < 1e-3
    assert abs(parse_duration_ms("500 us") - 0.5) < 1e-3
    assert abs(parse_duration_ms("500 µs") - 0.5) < 1e-3
    assert abs(parse_duration_ms("1000000 ns") - 1.0) < 1e-3
    assert abs(parse_duration_ms("1 s 20 ms") - 1020.0) < 1e-3
    assert abs(parse_duration_ms("PT1M2.5S") - 62500.0) < 1e-3
    assert abs(parse_duration_ms("0 s") - 0.0) < 1e-3
    tests_run += 1
    print(
        "[self-test] 7. Duration unit parsing: PASS (ms, s, us, ns, commas, compound, and ISO-8601)",
        flush=True,
    )

    # 8. Per-chunk JFR vs ASM matching fixture & MATCH-JUDGE asserts (M0 gate validation)
    # Synthetic fixtures:
    #   - SURFACE: exact 1.0 ratio -> PASS
    #   - CARVERS: 0.95 ratio -> PASS
    #   - FEATURES: 0.50 ratio -> FAIL
    #   - NOISE: async queue-wait stage -> strictly EXCLUDED from inline match
    #   - Unmatched chunk (99, 99) in JFR only -> ignored
    #   - Unmatched chunk (88, 88) in CSV only -> ignored
    synthetic_match_jfr = """
minecraft.ChunkGeneration {
  duration = 10.0 ms
  chunkPosX = 0
  chunkPosZ = 0
  status = "surface"
}
minecraft.ChunkGeneration {
  duration = 20.0 ms
  chunkPosX = 0
  chunkPosZ = 0
  status = "carvers"
}
minecraft.ChunkGeneration {
  duration = 40.0 ms
  chunkPosX = 0
  chunkPosZ = 0
  status = "features"
}
minecraft.ChunkGeneration {
  duration = 200.0 ms
  chunkPosX = 0
  chunkPosZ = 0
  status = "noise"
}
minecraft.ChunkGeneration {
  duration = 12.0 ms
  chunkPosX = 1
  chunkPosZ = 1
  status = "surface"
}
minecraft.ChunkGeneration {
  duration = 20.0 ms
  chunkPosX = 1
  chunkPosZ = 1
  status = "carvers"
}
minecraft.ChunkGeneration {
  duration = 30.0 ms
  chunkPosX = 1
  chunkPosZ = 1
  status = "features"
}
minecraft.ChunkGeneration {
  duration = 210.0 ms
  chunkPosX = 1
  chunkPosZ = 1
  status = "noise"
}
minecraft.ChunkGeneration {
  duration = 999.0 ms
  chunkPosX = 99
  chunkPosZ = 99
  status = "surface"
}
minecraft.ChunkGeneration {
  duration = 999.0 ms
  chunkPosX = 99
  chunkPosZ = 99
  status = "carvers"
}
"""

    synthetic_match_csv = """chunk_x,chunk_z,total_ms,surface_ms,carvers_ms,features_ms,noise_ms
0,0,70.02,10.000,19.000,20.000,0.020
1,1,66.02,12.000,19.000,15.000,0.020
88,88,297.02,99.000,99.000,99.000,0.020
"""

    parsed_match_jfr = parse_jfr_text(synthetic_match_jfr)
    parsed_match_csv = parse_stopwatch_csv(synthetic_match_csv)
    match_res = match_jfr_and_asm(parsed_match_jfr, parsed_match_csv)

    # Assert matched chunk count: only (0,0) and (1,1) match
    matched_coords = {m["chunk"] for m in match_res["per_chunk_matches"]}
    assert matched_coords == {(0, 0), (1, 1)}, f"unexpected matched chunks: {matched_coords}"
    assert (99, 99) not in matched_coords, "unmatched JFR-only chunk was not ignored"
    assert (88, 88) not in matched_coords, "unmatched CSV-only chunk was not ignored"
    assert len(match_res["per_chunk_matches"]) == 6, f"expected 6 matches, got {len(match_res['per_chunk_matches'])}"

    # Assert async stage strictly excluded from inline matching
    assert "NOISE" not in match_res["stage_summaries"], "async stage 'NOISE' must not be in inline matched stages"

    # Assert stage summaries & verdicts:
    summaries = match_res["stage_summaries"]
    assert "SURFACE" in summaries, "SURFACE missing from summaries"
    assert summaries["SURFACE"]["n"] == 2
    assert abs(summaries["SURFACE"]["med_ratio"] - 1.000) < 1e-3, f"SURFACE med_ratio expected 1.000, got {summaries['SURFACE']['med_ratio']}"
    assert summaries["SURFACE"]["verdict"] == "PASS", f"SURFACE expected PASS, got {summaries['SURFACE']['verdict']}"

    assert "CARVERS" in summaries, "CARVERS missing from summaries"
    assert summaries["CARVERS"]["n"] == 2
    assert abs(summaries["CARVERS"]["med_ratio"] - 0.950) < 1e-3, f"CARVERS med_ratio expected 0.950, got {summaries['CARVERS']['med_ratio']}"
    assert summaries["CARVERS"]["verdict"] == "PASS", f"CARVERS expected PASS, got {summaries['CARVERS']['verdict']}"

    assert "FEATURES" in summaries, "FEATURES missing from summaries"
    assert summaries["FEATURES"]["n"] == 2
    assert abs(summaries["FEATURES"]["med_ratio"] - 0.500) < 1e-3, f"FEATURES med_ratio expected 0.500, got {summaries['FEATURES']['med_ratio']}"
    assert summaries["FEATURES"]["verdict"] == "FAIL", f"FEATURES expected FAIL, got {summaries['FEATURES']['verdict']}"

    # Assert verdict lines & regex matching
    assert len(match_res["verdict_lines"]) == 3, f"expected 3 verdict lines, got {len(match_res['verdict_lines'])}"
    for v_line in match_res["verdict_lines"]:
        m = MATCH_JUDGE_PATTERN.match(v_line)
        assert m is not None, f"verdict line failed pattern: {v_line}"
        st = m.group("stage")
        assert st in summaries
        assert int(m.group("n")) == summaries[st]["n"]
        assert abs(float(m.group("med_ratio")) - summaries[st]["med_ratio"]) < 1e-3
        assert m.group("verdict") == summaries[st]["verdict"]

    # Assert per-chunk match line regex formatting
    for m_entry in match_res["per_chunk_matches"]:
        c_line = format_match_chunk_line(
            m_entry["chunk_x"],
            m_entry["chunk_z"],
            m_entry["stage"],
            m_entry["asm_ms"],
            m_entry["jfr_ms"],
            m_entry["ratio"],
        )
        cm = MATCH_CHUNK_PATTERN.match(c_line)
        assert cm is not None, f"chunk match line failed pattern: {c_line}"
        assert int(cm.group("cx")) == m_entry["chunk_x"]
        assert int(cm.group("cz")) == m_entry["chunk_z"]
        assert cm.group("stage") == m_entry["stage"]

    tests_run += 1
    print(
        "[self-test] 8. Per-chunk JFR vs ASM matching: PASS (exact 1.0 PASS, 0.95 PASS, 0.50 FAIL, unmatched ignored, async excluded)",
        flush=True,
    )

    # 9. Schema validation with match results integration
    matched_json = create_json_output(
        chunks=chunks1,
        stages=stages1,
        seed=4242424242,
        notes="matched self-test run",
        match_results=match_res["stage_summaries"],
    )
    valid_m, msg_m = validate_json_schema(matched_json)
    assert valid_m, f"schema validation failed on matched json: {msg_m}"
    assert "match" in matched_json
    assert matched_json["match"]["SURFACE"]["verdict"] == "PASS"
    assert matched_json["match"]["FEATURES"]["verdict"] == "FAIL"
    tests_run += 1
    print(
        "[self-test] 9. JSON schema validation with match results: PASS (match stage summaries validated)",
        flush=True,
    )

    print(f"\nSELF-TEST PASSED: {tests_run}/{tests_run} checks OK", flush=True)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Kestrel JFR-baseline parser & independent cross-check for the ASM chunkgen stopwatch"
    )
    parser.add_argument(
        "--jfr",
        help="path to raw .jfr recording file (invokes 'jfr print' via subprocess)",
    )
    parser.add_argument(
        "--jfr-text",
        help="path to pre-extracted text/json from 'jfr print --events minecraft.ChunkGeneration' (or '-' for stdin)",
    )
    parser.add_argument(
        "--jfr-cmd",
        default="jfr",
        help="path or name of OpenJDK 'jfr' CLI binary (default: 'jfr')",
    )
    parser.add_argument(
        "--output",
        "-o",
        help="output path to write JSON summary",
    )
    parser.add_argument(
        "--seed",
        type=int,
        help="world seed (long integer, optional)",
    )
    parser.add_argument(
        "--compare",
        help="path to stopwatch CSV (chunkgen-watch.csv) to print side-by-side comparison table",
    )
    parser.add_argument(
        "--match",
        help="path to stopwatch CSV (chunkgen-watch.csv) to join JFR events with ASM rows on (chunkX, chunkZ, status) for inline stages and emit MATCH-JUDGE verdicts",
    )
    parser.add_argument(
        "--notes",
        default="",
        help="optional user notes to include in output JSON",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="run standalone self-test suite (synthetic log parse, schema assert, trust-split assert) and exit",
    )

    args = parser.parse_args()

    # Mode 1: Standalone Self-Test
    if args.self_test:
        return run_self_test()

    # Validate input options
    if not args.jfr and not args.jfr_text:
        parser.error("Either --jfr <path.jfr> or --jfr-text <path.txt> is required (or run with --self-test)")

    raw_text = ""

    # Extract text from .jfr binary or read pre-extracted text
    if args.jfr:
        jfr_path = os.path.abspath(args.jfr)
        if not os.path.isfile(jfr_path):
            sys.exit(f"Error: JFR recording file not found: {jfr_path}")
        cmd = [args.jfr_cmd, "print", "--events", "minecraft.ChunkGeneration", jfr_path]
        try:
            print(f"[jfr-baseline] extracting events via: {' '.join(cmd)}", file=sys.stderr)
            res = subprocess.run(cmd, capture_output=True, text=True, check=False)
            if res.returncode != 0:
                sys.exit(f"Error: jfr print failed (exit code {res.returncode}):\n{res.stderr.strip()}")
            raw_text = res.stdout
        except FileNotFoundError:
            sys.exit(
                f"Error: '{args.jfr_cmd}' command not found in PATH.\n"
                f"Please ensure OpenJDK JDK 17+ or JDK 25 is installed, or pass pre-extracted text via --jfr-text."
            )
    elif args.jfr_text:
        if args.jfr_text == "-":
            raw_text = sys.stdin.read()
        else:
            text_path = os.path.abspath(args.jfr_text)
            if not os.path.isfile(text_path):
                sys.exit(f"Error: JFR text file not found: {text_path}")
            with open(text_path, "r", encoding="utf-8", errors="replace") as f:
                raw_text = f.read()

    # Parse JFR events
    parsed_jfr = parse_jfr_text(raw_text)
    if not parsed_jfr["samples_by_stage"]:
        print(
            "Warning: no ChunkGeneration events parsed from input text.\n"
            "Ensure recording was captured with --jfrProfile / JfrProfiler enabled.",
            file=sys.stderr,
        )

    chunks, stages = aggregate_jfr_stages(parsed_jfr)

    # Optional side-by-side comparison with ASM stopwatch CSV
    if args.compare:
        compare_path = os.path.abspath(args.compare)
        if not os.path.exists(compare_path):
            print(f"Warning: stopwatch CSV file not found: {compare_path}", file=sys.stderr)
        else:
            parsed_csv = parse_stopwatch_csv(compare_path)
            table_md = format_comparison_table(stages, parsed_csv["stages"])
            print("\n=== Chunkgen Cross-Check: JFR Baseline vs ASM Stopwatch ===")
            print(table_md)
            print("============================================================\n")

    # Optional per-chunk matching with ASM stopwatch CSV (inline stages only)
    match_result = None
    if args.match:
        match_path = os.path.abspath(args.match)
        if not os.path.exists(match_path):
            sys.exit(f"Error: match CSV file not found: {match_path}")
        parsed_match_csv = parse_stopwatch_csv(match_path)
        match_result = match_jfr_and_asm(parsed_jfr, parsed_match_csv)

        print("\n=== Per-Chunk JFR vs ASM Matches (Inline Stages Only) ===")
        for m in match_result["per_chunk_matches"]:
            print(
                format_match_chunk_line(
                    m["chunk_x"],
                    m["chunk_z"],
                    m["stage"],
                    m["asm_ms"],
                    m["jfr_ms"],
                    m["ratio"],
                )
            )

        print("\n=== Stage Aggregate Match Ratios (Inline Stages Only) ===")
        print(format_match_table(match_result))
        print("=========================================================\n")

        for stage, s in match_result["stage_summaries"].items():
            print(format_match_stage_line(stage, s["n"], s["med_ratio"], s["p95_ratio"]))

        for line in match_result["verdict_lines"]:
            print(line)

    # Print JFRBASE log-parsing lines
    for st, d in stages.items():
        print(format_jfrbase_line(st, d["n"], d["med_ms"], d["p95_ms"]))

    # Construct and print JSON output
    notes = args.notes
    if not notes and parsed_jfr["raw_event_count"] > 0:
        notes = f"{parsed_jfr['raw_event_count']} ChunkGeneration events parsed across {chunks} chunks"

    json_result = create_json_output(
        chunks=chunks,
        stages=stages,
        seed=args.seed,
        notes=notes,
        match_results=match_result["stage_summaries"] if match_result else None,
    )

    valid, v_msg = validate_json_schema(json_result)
    if not valid:
        print(f"Warning: generated output failed internal schema check: {v_msg}", file=sys.stderr)

    formatted_json = json.dumps(json_result, indent=2)
    print(formatted_json)

    if args.output:
        out_path = os.path.abspath(args.output)
        out_dir = os.path.dirname(out_path)
        if out_dir:
            os.makedirs(out_dir, exist_ok=True)
        with open(out_path, "w", encoding="utf-8") as f:
            f.write(formatted_json + "\n")
        print(f"[jfr-baseline] JSON summary written to {out_path}", file=sys.stderr)

    return 0


if __name__ == "__main__":
    sys.exit(main())
