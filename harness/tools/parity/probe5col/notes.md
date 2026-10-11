# 5-column oracle evidence (verified 2026-09-14)

Small control run `5col` on bench PC (9x9, seed 4242424242, port 25577,
current agent jar): manifest SELFCHECK PASS, probe header
`chunk_x,chunk_z,cut,hash,blocks_hash`, P1 289 / P2 289 / P3 169, zero blank
blocks_hash. Staged: manifest.json (full) + probe-sample.csv (20 rows).
Full csv + 4 .mca stay on bench PC
(`C:\Users\bench\kestrel\corpus\5col\`); .mca copies on the Pi at
`~/kestrel-corpus/probe5col/` (4/4 sha256 OK vs manifest).

Formal gates run on the Pi against this oracle:
- P1 blocks-only gate (HeadlessNoise NOISE slice vs oracle blocks_hash):
  24/24 on shuffled 9x9 chunks. Step-3 exit: GREEN.
- Parallel driver: P1 equal across 1/2/4/8 threads (merged 0950fbc).
