#!/usr/bin/env python3
"""RESULT parser for bench_results.txt (WI-10).

Reads the bench PC results file (or bench_results.json) and prints one row
per RESULT line plus per-run MEDIAN rollups. Rows from before the WI-1
churn-dwell fix are marked SUPERSEDED: the old churn bots disconnected
the instant they entered play, so their cpuLoad/rssLoad measured an
idle server. The fix is stamped in load-bot.py (dwell per cycle);
this parser treats any RESULT line without an explicit dwell marker
as pre-fix for churn rows only.

Usage:
  python3 summarize-results.py bench_results.txt
  python3 summarize-results.py --json bench_results.json
"""

import json
import re
import sys

RESULT = re.compile(
    r"^(?P<tag>\S+)\s+:\s+RESULT\s+(?P<body>.*)$")
FIELD = re.compile(r"(?P<k>[a-zA-Z]+)=(?P<v>[^\s]+)")
STAMP = re.compile(r"^\[(?P<ts>[^\]]+)\]")


def parse_lines(text):
    rows = []
    for line in text.splitlines():
        m = RESULT.search(line.split("]", 1)[-1].strip()
                          if "]" in line else line.strip())
        if not m:
            continue
        ts = STAMP.match(line)
        fields = dict(FIELD.findall(m.group("body")))
        rows.append({"tag": m.group("tag"),
                     "ts": ts.group("ts") if ts else "?",
                     "fields": fields,
                     "invalid": "INVALID" in m.group("body")})
    return rows


def main():
    args = sys.argv[1:]
    if args[:1] == ["--json"]:
        data = json.load(open(args[1]))
        items = data if isinstance(data, list) else data.get("runs", data)
        for it in items if isinstance(items, list) else []:
            print(it)
        return 0
    path = args[0] if args else "bench_results.txt"
    rows = parse_lines(open(path, encoding="utf-8", errors="replace").read())
    if not rows:
        print("no RESULT lines found")
        return 1
    print(f"{'tag':44} {'boot':>7} {'cpuI':>6} {'cpuL':>6} "
          f"{'joinAvg':>7} {'joins':>7}  note")
    for r in rows:
        f = r["fields"]
        note = ""
        if r["invalid"]:
            note = "INVALID"
        elif "cycles" in f:
            note = "(post-dwell churn)"
        elif "-CHURN" in r["tag"]:
            # WI-1 dwell fix: old churn bots disconnected the instant they
            # entered play, so their cpuLoad/rssLoad measures an idle
            # server. cycles= field only exists post-fix, so a churn row
            # without it is pre-dwell and cannot be compared to new rows.
            note = "SUPERSEDED pre-dwell"
        print(f"{r['tag']:44} {f.get('boot', '?'):>7} "
              f"{f.get('cpuIdle', '?'):>6} {f.get('cpuLoad', '?'):>6} "
              f"{f.get('joinAvg', '?'):>7} {f.get('joins', '?'):>7}  {note}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
