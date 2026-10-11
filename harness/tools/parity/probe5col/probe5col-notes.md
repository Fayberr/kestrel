# 5-Column Oracle Probe Notes (Control Run)

**Date**: 2026-09-13 / 2026-09-14  
**Corpus**: `C:\Users\bench\kestrel\corpus\5col` (bench PC)  
**Configuration**: Seed 4242424242, `HalfSide=4`, `CoreHalfSide=2` (9x9 chunk square, 81 starts), `Seconds=240`, `Port=25577`, structures OFF (`GenerateStructures=false`).

---

## 1. Agent Jar Freshness Verdict
- **Prior Jar on bench PC**: `LastWriteTime = 13.09.2026 20:19:57` (pre-dated commit `b540583` at 20:22:56).
  - Inspection of `HashProbe.java` and `HashProbe.class` on bench PC confirmed it emitted only 4 columns (`chunk_x,chunk_z,cut,hash`) without `blocks_hash`.
  - **Verdict**: **STALE** (predated commit `b540583`).
- **Rebuild & Redeployment**:
  - Rebuilt locally in `harness/agent` with Java 25 via Gradle wrapper (`./gradlew build -x test`).
  - Bytecode verified via `javap` to confirm `chunk_x,chunk_z,cut,hash,blocks_hash` header and 5-field record emission.
  - Deployed `kestrel-harness-agent-0.1.0.jar` (264,816 B) and `kestrel-harness-boot.jar` (69,880 B, sha256 `f3e98e3b775297ac02f678d4782876a984aa22f0a742d354365f5552bcccdc9b`) to `C:\Users\bench\kestrel\harness\agent\build\libs\`.
  - Synced Java sources under `harness/agent/src` to bench PC as well.

---

## 2. Control Corpus Run
- Command run on bench PC:
  ```powershell
  pwsh -File C:\Users\bench\kestrel\harness\tools\corpus-gen.ps1 -HalfSide 4 -CoreHalfSide 2 -Seconds 240 -Port 25577 -OutDir C:\Users\bench\kestrel\corpus\5col
  ```
- **Pre-flight Box Check**: `Get-Process java` verified clear (no Minecraft servers or Gradle daemons holding ports). Port 25577 verified in free / non-listening state.
- **Wave Progression**:
  - `wave1`: True (core 5x5 = 25 chunks forceloaded)
  - `wave2`: True (outer ring up to 9x9 = 81 chunks forceloaded)
  - `shutdown`: True (clean console `stop` command issued and acknowledged)
  - `cleanStop`: True (`stopSent: true`, `killedByBackstop: false`)
- **Selfcheck**:
  ```
  CORPUS_SELFCHECK PASS (4 region files, 169 csv rows, 747 probe rows)
  ```

---

## 3. 5-Column Probe Confirmation & Cut Statistics
- **Header Line (verbatim)**:
  ```
  chunk_x,chunk_z,cut,hash,blocks_hash
  ```
- **Total Probe Rows**: 747 (0 empty / null `blocks_hash` values)
- **Cut Counts**:
  - `P1` (NOISE complete): **289**
  - `P2` (CARVERS exit): **289**
  - `P3` (LIGHT/FULL exit): **169**
  *(Matches expected generation pyramid: 169 FULL chunks + 120 neighbor chunks at earlier generation stages).*
- **Sample Rows per Cut**:
  - **P1**: `-1,0,P1,a03b90914a307c19,5e0b15a53e66bed8`
  - **P2**: `-2,-2,P2,9f728ac980035c62,aeb7ab9889425edf`
  - **P3**: `0,0,P3,a09854ef86b231c3,5c92143229e13dd7`

---

## 4. Timing & Performance Ratios
- **Generation Window**: 240.0 seconds wall-clock (`Seconds=240`).
- **Ticks Processed**: 3,365 ticks recorded in `ticks.log` across the run.
- **Average Tick Rate**: ~14.0 TPS during active chunk generation.
- **Region Files**: 4 `.mca` files generated (`r.-1.-1.mca`, `r.-1.0.mca`, `r.0.-1.mca`, `r.0.0.mca`) at `world\dimensions\minecraft\overworld\region`.
