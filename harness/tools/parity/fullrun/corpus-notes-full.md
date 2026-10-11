# Full-run notes (verified 2026-09-13)

Seed 4242424242, structures OFF, port 25574, `pause-when-empty-seconds=0`.

## What actually ran on bench PC
- `seed-4242424242-nostruct` (19:12 UTC manifest, -Seconds 1200 wall):
  49x49 square, csvRows 2809, probeRows 9307 (P1 3249 / P2 3249 / P3 2809),
  16 .mca via dimensions path, wave1+wave2+shutdown all true, cleanStop true,
  pausedEmpty 0, loadErrors 0. SELFCHECK-equivalent fields all green.
  HONEST GAP: csv 2809 vs 2401 requested (~1.17x ,  the watch logs every
  generated chunk incl. neighbours, not just the forced square; same shape
  as pilot2a's 419 rows from 81 forced). probeRows 9307 with the same
  1:1:0.86 P1/P2/P3 split, not independently re-verified row-by-row.
  NOT committed: manifest.json + full csvs + .mca binaries stay on bench PC
  (`C:\Users\bench\kestrel\corpus\seed-4242424242-nostruct\`).
- `validate` (18:50 UTC manifest, -Seconds 240 wall): 9x9 re-run of the
  fixed script as a control. csvRows 169, probeRows 747
  (P1 289 / P2 289 / P3 169 = 169 forced + 120 neighbours ,  same pyramid
  shape as pilot2a's 419 from 81). Manifest staged at
  `harness/tools/parity/fullrun/manifest.json`. 4 .mca on bench PC.
- Pi re-hash of one .mca: NOT done (no .mca was copied to the Pi; hashes
  are Get-FileHash on the bench PC only). Manifest honesty rests on the staged
  manifest + SELFCHECK field consistency, not an independent re-hash.

## Step-2 prep
NOT run. The W1 import + memory census needs the full corpus .mca files,
which were never copied off bench PC. Next: copy 16 .mca to Pi, run
`KestrelServer --import` on the W1 square with --spawn/--no-save/--mem-probe/
--block-hash-csv, record census + B vs 116.7 MiB budget.

## Merged with this note
- corpus-gen.ps1: `pause-when-empty-seconds=0` + `pausedEmpty` manifest
  field + SELFCHECK problem (commit 92742ee). This was the
  root cause of every pilot's wave2 miss (playerless server fell asleep
  at 60 s, tick counter froze, tick-1200 schedule could never fire).
- Staged: `harness/tools/parity/fullrun/manifest.json` (validation
  control run). Full-run manifest + csvs + .mca stay on bench PC (paths above).

## Remainder (honest)
1. Full-run csv/probe row counts are read by hand from bench PC, not
   committed evidence (csvs stay on bench PC per the no-binaries rule).
2. No Pi-side sha256 re-hash of any .mca yet.
3. Step-2 W1/W2 import verdict still open ,  needs the .mca files on the Pi.
4. The gen-route matrix (MSPT saturation gate for step 9) is unvalidated.
5. The step-3 design doc is still unwritten.
