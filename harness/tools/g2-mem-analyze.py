#!/usr/bin/env python3
"""Analyze a mem-bench.ps1 output root (preresearch/g2-memory-design.md 1.3-1.5).

  g2-mem-analyze.py OUTROOT [--json OUT.json]

Per run (run.json + server.log + histogram.txt + nmt.txt + heapinfo.txt):
  - census / census-store / MemProbe lines from server.log;
  - histogram rows: BlockState[], boolean[], SectionStore$Section,
    HashMap$Node, Long (count, bytes);
  - B = the server-reported compact/dense storage model when present, with
    the legacy Section count x 20,598 B model for older evidence;
  - array cross-check: (row bytes - W0 non-store residual) / per-section
    array bytes must equal the dense Section count within 5% (design 1.4 item 2).
    The residual is measured on W0 in the same root (median); without a W0
    run the raw ratio is reported and marked unresolved;
  - NMT committed per category, heap_info committed/used;
  - jcmd verdict: "jcmd" when the histogram parsed, else "fallback". B comes
    from MemProbe when available and the legacy dense model otherwise.
Per variant: medians over reps. With W1 and W2: perChunk, fixed, the 2205
projection and the 1.5 decision row. Offline census files are compared to
the import census for exact equality.
"""

import argparse
import json
import os
import re
import statistics
import sys

SECTION_BYTES = 20_598
BS_ARRAY = 16 + 4096 * 4   # BlockState[4096], compressed oops
Z_ARRAY = 16 + 4096        # boolean[4096]
SECTION_OBJ_LEGACY = 24
SECTION_OBJ_COMPACT = 40
MIB = 1024 * 1024
BUDGET_MIB = 350.0
LINE_MIB = BUDGET_MIB / 3  # 116.7
TOLERANCE = 0.05

ROWS = {
    "blockStateArrays": "[Lnet.minecraft.world.level.block.state.BlockState;",
    "booleanArrays": "[Z",
    "sections": "dev.kestrel.loop.SectionStore$Section",
    "hashMapNodes": "java.util.HashMap$Node",
    "longs": "java.lang.Long",
}


def read(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read()
    except OSError:
        return None


def parse_kv(line):
    return dict(re.findall(r"(\w+)=([^\s]+)", line))


def parse_server_log(text):
    out = {"census": None, "censusStore": None, "mem": [], "streamStart": None,
           "streamComplete": None, "blocksHash": None}
    for raw in (text or "").splitlines():
        i = raw.find("[kestrel] ")
        if i < 0:
            continue
        line = raw[i + len("[kestrel] "):].strip()
        if line.startswith("census "):
            out["census"] = line
        elif line.startswith("census-store "):
            out["censusStore"] = parse_kv(line)
        elif line.startswith("mem "):
            out["mem"].append(parse_kv(line))
        elif line.startswith("stream start"):
            out["streamStart"] = parse_kv(line)
        elif line.startswith("stream complete"):
            out["streamComplete"] = parse_kv(line)
        elif line.startswith("import blocksHash="):
            out["blocksHash"] = parse_kv(line).get("blocksHash")
    return out


def parse_histogram(text):
    if not text:
        return None
    rows = {}
    for line in text.splitlines():
        m = re.match(r"\s*\d+:\s+(\d+)\s+(\d+)\s+(\S+)", line)
        if not m:
            continue
        for key, name in ROWS.items():
            if m.group(3) == name:
                rows[key] = {"count": int(m.group(1)), "bytes": int(m.group(2))}
    if "blockStateArrays" not in rows:
        return None
    for key in ROWS:
        rows.setdefault(key, {"count": 0, "bytes": 0})
    return rows


def parse_nmt(text):
    if not text or "Native Memory Tracking" not in text:
        return None
    out = {}
    m = re.search(r"Total: reserved=(\d+)KB, committed=(\d+)KB", text)
    if m:
        out["totalCommittedMiB"] = round(int(m.group(2)) / 1024, 1)
    for m in re.finditer(r"-\s+([A-Za-z][A-Za-z ]*?) \(reserved=(\d+)KB, committed=(\d+)KB", text):
        out[m.group(1).strip().replace(" ", "") + "CommittedMiB"] = round(int(m.group(3)) / 1024, 1)
    return out


def parse_heap_info(text):
    if not text:
        return None
    total = used = 0
    found = False
    for m in re.finditer(r"^\s*(\w[\w ]*?)\s+total (\d+)K, used (\d+)K", text, re.M):
        found = True
        total += int(m.group(2))
        used += int(m.group(3))
    if not found:
        return None
    return {"committedMiB": round(total / 1024, 1), "usedMiB": round(used / 1024, 1)}


def median(xs):
    xs = [x for x in xs if x is not None]
    return round(statistics.median(xs), 2) if xs else None


def analyze_run(run_dir):
    meta = json.loads(read(os.path.join(run_dir, "run.json")) or "{}") if os.path.exists(
        os.path.join(run_dir, "run.json")) else {}
    log = parse_server_log(read(os.path.join(run_dir, "server.log")))
    hist = parse_histogram(read(os.path.join(run_dir, "histogram.txt")))
    r = {
        "tag": meta.get("tag", os.path.basename(run_dir)),
        "variant": meta.get("variant", os.path.basename(run_dir).split("-")[0]),
        "ok": meta.get("ok", True),
        "error": meta.get("error"),
        "rssIdleMiB": median(meta.get("rssIdleMiB", [])),
        "rssLoadMiB": median(meta.get("rssLoadMiB", [])),
        "bootSeconds": meta.get("bootSeconds"),
        "census": log["census"],
        "censusStore": log["censusStore"],
        "streamRadius": (log["streamStart"] or {}).get("radius"),
        "streamChunks": (log["streamComplete"] or {}).get("chunks"),
        "blocksHash": log["blocksHash"],
        "histogram": hist,
        "nmt": parse_nmt(read(os.path.join(run_dir, "nmt.txt"))),
        "heapInfo": parse_heap_info(read(os.path.join(run_dir, "heapinfo.txt"))),
        "heapPostLoad": parse_heap_info(read(os.path.join(run_dir, "heap-postload.txt"))),
    }
    first = next((m for m in log["mem"] if m.get("label", "").startswith("post-")), None)
    last = log["mem"][-1] if log["mem"] else None
    src = last or first
    r["memPostLoad"] = first
    r["memLast"] = last
    if hist:
        r["method"] = "jcmd"
        r["sections"] = hist["sections"]["count"]
    else:
        r["method"] = "fallback"
        r["sections"] = int(src["sections"]) if src else None
    r["compactSections"] = int(src["compact"]) if src and "compact" in src else None
    r["denseSections"] = int(src["dense"]) if src and "dense" in src else r["sections"]
    if src and "modelBMiB" in src:
        r["B_MiB"] = round(float(src["modelBMiB"]), 2)
    else:
        r["B_MiB"] = (round(r["sections"] * SECTION_BYTES / MIB, 2)
                      if r["sections"] is not None else None)
    return r


def cross_check(r, residual):
    h = r["histogram"]
    if not h:
        return None
    n = r["denseSections"]
    section_obj = (SECTION_OBJ_COMPACT if r["compactSections"] is not None
                   else SECTION_OBJ_LEGACY)
    out = {"sectionObjBytesOk":
           h["sections"]["bytes"] == r["sections"] * section_obj}
    for key, per in (("blockStateArrays", BS_ARRAY), ("booleanArrays", Z_ARRAY)):
        raw_eq = h[key]["bytes"] / per
        res = residual.get(key) if residual else None
        net_eq = (h[key]["bytes"] - res) / per if res is not None else None
        dev = abs(net_eq - n) / n if (net_eq is not None and n) else None
        out[key] = {"rawEquivSections": round(raw_eq, 2),
                    "residualBytes": res,
                    "netEquivSections": round(net_eq, 2) if net_eq is not None else None,
                    "deviation": round(dev, 4) if dev is not None else None,
                    "withinTolerance": (dev <= TOLERANCE) if dev is not None else None}
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("root")
    ap.add_argument("--json", default="")
    args = ap.parse_args()

    runs = []
    for name in sorted(os.listdir(args.root)):
        d = os.path.join(args.root, name)
        if os.path.isdir(d) and (os.path.exists(os.path.join(d, "run.json"))
                                 or os.path.exists(os.path.join(d, "server.log"))):
            runs.append(analyze_run(d))
    if not runs:
        sys.exit("no runs under " + args.root)

    # W0 non-store residual per array row (median over W0 reps with a histogram).
    residual = {}
    w0 = [r for r in runs if r["variant"] == "W0" and r["histogram"]]
    for key, per in (("blockStateArrays", BS_ARRAY), ("booleanArrays", Z_ARRAY)):
        vals = [r["histogram"][key]["bytes"] - r["denseSections"] * per for r in w0]
        if vals:
            residual[key] = int(statistics.median(vals))
    for r in runs:
        r["crossCheck"] = cross_check(r, residual)

    offline = {}
    for name in os.listdir(args.root):
        m = re.match(r"offline-census-(\w+)\.txt$", name)
        if m:
            text = read(os.path.join(args.root, name)) or ""
            line = next((l.strip() for l in text.splitlines() if l.startswith("census ")), None)
            offline[m.group(1)] = line

    variants = {}
    for r in runs:
        v = variants.setdefault(r["variant"], {"runs": []})
        v["runs"].append(r["tag"])
    for name, v in variants.items():
        rs = [r for r in runs if r["variant"] == name and r["ok"]]
        v["B_MiB"] = median([r["B_MiB"] for r in rs])
        v["sections"] = median([r["sections"] for r in rs])
        v["rssIdleMiB"] = median([r["rssIdleMiB"] for r in rs])
        v["rssLoadMiB"] = median([r["rssLoadMiB"] for r in rs])
        v["heapCommittedMiB"] = median([(r["heapInfo"] or {}).get("committedMiB") for r in rs])
        v["heapUsedMiB"] = median([(r["heapInfo"] or {}).get("usedMiB") for r in rs])
        v["heapPostLoadCommittedMiB"] = median([(r["heapPostLoad"] or {}).get("committedMiB") for r in rs])
        v["nmtTotalCommittedMiB"] = median([(r["nmt"] or {}).get("totalCommittedMiB") for r in rs])
        v["methods"] = sorted({r["method"] for r in rs})
        v["B_vs_line"] = round(v["B_MiB"] / LINE_MIB, 3) if v["B_MiB"] is not None else None
        censuses = {r["census"] for r in rs if r["census"]}
        v["censusStable"] = len(censuses) <= 1
        v["census"] = next(iter(censuses), None)
        if name in offline:
            v["offlineCensus"] = offline[name]
            v["censusMatchesOffline"] = (v["census"] == offline[name])
        store = next((r["censusStore"] for r in rs if r["censusStore"]), None)
        if store:
            v["sectionsPerChunk"] = float(store.get("perChunk", 0))
            v["airOnlyShare"] = float(store.get("airOnlyShare", 0))
        checks = [r["crossCheck"] for r in rs if r["crossCheck"]]
        if checks:
            v["crossCheckOk"] = all(
                c["sectionObjBytesOk"] and (
                    c["blockStateArrays"]["withinTolerance"] is None
                    or (c["blockStateArrays"]["withinTolerance"]
                        and c["booleanArrays"]["withinTolerance"]))
                for c in checks)
            v["crossCheckResolved"] = all(c["booleanArrays"]["withinTolerance"] is not None for c in checks)

    report = {"residualBytes": residual, "variants": variants, "runs": runs}

    if "W1" in variants and "W2" in variants and variants["W1"]["B_MiB"] and variants["W2"]["B_MiB"]:
        b1 = variants["W1"]["B_MiB"]
        b2 = variants["W2"]["B_MiB"]
        per_chunk = (b2 - b1) / 520
        fixed = b1 - 441 * per_chunk
        proj = per_chunk * 2205
        compact_gate = any((r.get("compactSections") or 0) > 0 for r in runs)
        if any(not variants[v].get("crossCheckOk", True) for v in ("W1", "W2")):
            decision = "STOP: histogram disagrees with the storage model - re-ground design 0.1"
        elif compact_gate and b1 <= LINE_MIB and proj <= BUDGET_MIB:
            decision = "Step 2b compact memory gate PASS"
        elif compact_gate:
            decision = "Step 2b compact memory gate FAIL"
        elif b1 > LINE_MIB:
            decision = "Step 2b compact (rule fires: B > 116.7 MiB)"
        elif proj <= BUDGET_MIB:
            decision = "No 2b now; do 2.5 cheap wins inside step 2; re-check at step 9"
        else:
            decision = "Rule does not fire; record step-9 risk; schedule 2b before step 9 exit 3"
        report["slope"] = {"perChunkMiB": round(per_chunk, 4), "fixedMiB": round(fixed, 2),
                           "fixedWithin5pct": abs(fixed) <= 0.05 * b1,
                           "projection2205MiB": round(proj, 1), "decision": decision}

    print("residual (W0 non-store bytes):", residual or "none (no W0 histogram in this root)")
    for name, v in sorted(variants.items()):
        print("%-4s B=%s MiB (%.3fx line) sections=%s perChunk=%s airOnly=%s rssIdle=%s rssLoad=%s "
              "heap committed/used=%s/%s nmt=%s method=%s cross=%s resolved=%s offline=%s" % (
                  name, v["B_MiB"], v["B_vs_line"] or 0, v["sections"], v.get("sectionsPerChunk"),
                  v.get("airOnlyShare"), v["rssIdleMiB"], v["rssLoadMiB"], v["heapCommittedMiB"],
                  v["heapUsedMiB"], v["nmtTotalCommittedMiB"], ",".join(v["methods"]),
                  v.get("crossCheckOk"), v.get("crossCheckResolved"), v.get("censusMatchesOffline")))
    for r in runs:
        c = r["crossCheck"]
        if c:
            print("  %s sections=%s BS[] raw=%s net=%s dev=%s | [Z raw=%s net=%s dev=%s | Section bytes ok=%s" % (
                r["tag"], r["sections"], c["blockStateArrays"]["rawEquivSections"],
                c["blockStateArrays"]["netEquivSections"], c["blockStateArrays"]["deviation"],
                c["booleanArrays"]["rawEquivSections"], c["booleanArrays"]["netEquivSections"],
                c["booleanArrays"]["deviation"], c["sectionObjBytesOk"]))
        if not r["ok"]:
            print("  %s FAILED: %s" % (r["tag"], r["error"]))
    if "slope" in report:
        print("slope:", report["slope"])
    out = args.json or os.path.join(args.root, "analysis.json")
    with open(out, "w") as f:
        json.dump(report, f, indent=2)
    print("wrote", out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
