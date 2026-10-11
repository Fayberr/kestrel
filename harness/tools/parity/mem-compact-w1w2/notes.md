# G2 step 2b compact W1/W2 memory gate

Measured 2026-09-17 on bench PC at commit `dd399c8`, using the same real
seed-4242424242 no-structures corpus and tuned JVM flags as the dense baseline.
Each W1 and W2 row is the median of three isolated runs. All six imports,
streams, histograms, NMT captures, and heap captures completed successfully.

## Verdict

The step 2b block-storage gate passes.

| Variant | Chunks | Materialized sections | Modelled block storage B | B / 116.7 MiB line | Idle RSS | Load RSS |
|---|---:|---:|---:|---:|---:|---:|
| W1, VD10 square | 441 | 5,864 | 12.79 MiB | 0.110x | 345.20 MiB | 495.05 MiB |
| W2, VD15 square | 961 | 10,146 | 22.34 MiB | 0.191x | 364.75 MiB | 539.50 MiB |

The W1 to W2 slope is 0.0184 MiB per imported chunk. Projected across 2,205
disjoint chunks for five VD10 players, compact block storage is 40.5 MiB. The
dense baseline projected 358.2 MiB. This is an 8.84x reduction in the projected
block-storage figure and leaves 309.5 MiB of the 350 MiB whole-server budget for
the rest of the server.

The imported palette census matched the offline census exactly in both variants.
All live `SectionStore$Section` object histogram bytes match the new 40-byte
object layout. A W0 run was not repeated, so dense-array residual checks remain
unresolved; that does not affect the compact B value, which comes from the
server's representation-aware model and is cross-checked by section object
counts.

## Remaining whole-server risk

This closes the step 2b storage gate, not the final step 9 whole-server memory
gate. Single-player load RSS is still 495.05 MiB for W1 and 539.50 MiB for W2,
well above the stated 350 MiB whole-server target. Idle RSS is 345.20 MiB at W1
and 364.75 MiB at W2. The next memory work must identify and reduce committed
heap, native/class overhead, and chunk-streaming transients before step 9 can
claim the whole-server target.

Raw campaign artifacts were captured under
`C:\Users\bench\bench\g2mem-compact-dd399c8`. This directory retains the
six per-run logs, histograms, NMT reports, heap reports, and run metadata. The
distilled `analysis.json`, `analysis.txt`, campaign metadata, and exact offline
censuses are committed beside this note.
