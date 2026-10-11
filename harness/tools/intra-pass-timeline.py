#!/usr/bin/env python3
"""Show one FEATURES pass from a capture's intra-features-timeline.tsv.

Prints the origin's feature placements in order (step and feature index) and,
between them, the light rows that other threads recorded while the pass ran
(INITIALIZE_LIGHT_TASK / INITIALIZE_LIGHT_PRE, grouped per gap, with their
targets). Use it to see where a cross-thread light task landed inside a pass,
for example when the strict gate refuses to linearize an intra-FEATURES
INITIALIZE_LIGHT.

    python intra-pass-timeline.py <trace dir> <originX> <originZ>

The timeline is sorted by its order column (rows are queued from several
threads and written at shutdown). Rows from 50 before the pass to 400 after it
are shown.
"""
import sys
from pathlib import Path


def main():
    trace = Path(sys.argv[1])
    ox, oz = sys.argv[2], sys.argv[3]
    rows = []
    with open(trace / "intra-features-timeline.tsv") as f:
        next(f)
        for line in f:
            rows.append(line.rstrip("\n").split("\t"))
    rows.sort(key=lambda p: int(p[0]))
    idx = [i for i, p in enumerate(rows) if p[1] == "FEATURE" and (p[4], p[5]) == (ox, oz)]
    if not idx:
        raise SystemExit(f"no FEATURE rows for origin {ox},{oz}")
    lo, hi = idx[0], idx[-1]
    print("pass orders", rows[lo][0], "..", rows[hi][0], "placements",
          sum(1 for i in idx if rows[i][2] == "ENTER"))
    pending = []

    def flush():
        groups = {}
        for q in pending:
            groups.setdefault((q[1], q[3]), []).append(q)
        for (kind, thread), qs in groups.items():
            targets = sorted(set(q[6] + "," + q[7] for q in qs))
            print(f"   {kind} x{len(qs)} thread={thread} orders={qs[0][0]}..{qs[-1][0]} "
                  f"targets={' '.join(targets)}")
        pending.clear()

    placement = 0
    for p in rows[max(0, lo - 50): hi + 400]:
        if p[1] == "FEATURE":
            if (p[4], p[5]) != (ox, oz):
                continue
            if p[2] == "ENTER":
                flush()
                placement += 1
                print(f"F{placement:03d} order={p[0]} step={p[10]} featureIndex={p[9]}", end="")
            else:
                print(f" exit={p[0]}")
        else:
            pending.append(p)
    flush()


if __name__ == "__main__":
    main()
