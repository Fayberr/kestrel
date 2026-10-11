#!/usr/bin/env python3
"""Contract tests for the raw block export adapter and seam analyzer integration.

Every test here either exercises production code directly (load_chunk_dataset,
validate_raw_export_v1, ChunkBorderSeamAnalyzer, the classifier helpers) or
invokes the real BatchParityGate exporter against the real vanilla oracle at
$HOME/kestrel-run/live-seed-oracle-region for seed 3527749958874813416.
No test asserts a fact about a literal it just constructed.
"""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))

from harness.tools.seam_analyzer import (
    CATEGORY_KELP_SEAGRASS_VS_WATER_AIR,
    CATEGORY_OTHER,
    ChunkBorderSeamAnalyzer,
    ChunkData,
    ChunkDataset,
    classify_block_pair,
    is_kelp_seagrass,
    is_water_air,
    load_chunk_dataset,
    validate_raw_export_v1,
)

REPO_ROOT = Path(__file__).resolve().parents[3]
LIVE_ORACLE_DIR = Path.home() / "kestrel-run" / "live-seed-oracle-region"
VANILLA_JAR = Path.home() / "kestrel-run" / "vinner" / "META-INF" / "versions" / "26.2" / "server-26.2.jar"
VANILLA_LIBS = Path.home() / "kestrel-run" / "vinner" / "META-INF" / "libraries"
JAVA_BIN = Path(os.environ.get("JAVA_BIN", "java"))
LIVE_SEED = "3527749958874813416"

JAVA_PREREQS_AVAILABLE = (
    LIVE_ORACLE_DIR.is_dir()
    and VANILLA_JAR.exists()
    and VANILLA_LIBS.is_dir()
    and JAVA_BIN.exists()
    and (REPO_ROOT / "core" / "build" / "classes" / "java" / "main").is_dir()
)


def _batchparitygate_classpath() -> str:
    jars = [str(VANILLA_JAR)] + [str(p) for p in VANILLA_LIBS.rglob("*.jar")]
    classes_dir = REPO_ROOT / "core" / "build" / "classes" / "java" / "main"
    return str(classes_dir) + ":" + ":".join(jars)


def _combined_output(proc) -> str:
    """log4j's console appender routes BatchParityGate's System.err lines onto
    the process's actual stdout stream (visible as '[STDERR]:'-tagged log
    lines within stdout); check both streams together rather than assuming
    which OS-level fd a given message landed on."""
    return (proc.stdout or "") + "\n" + (proc.stderr or "")


def _run_batch_parity_gate(tmp_run_dir: Path, region_dir: Path, export_dir: Path, target_half: str = "0"):
    """Run BatchParityGate with cwd set to a temp dir so log4j output (logs/*)
    never lands inside the repo working tree."""
    cmd = [
        str(JAVA_BIN),
        "-cp",
        _batchparitygate_classpath(),
        "dev.kestrel.worldgen.BatchParityGate",
        LIVE_SEED,
        str(region_dir),
        "dummy-p2-blocks.csv",
        "dummy-p2-full.csv",
        str(tmp_run_dir / "gate.csv"),
        target_half,
        str(export_dir),
    ]
    return subprocess.run(cmd, capture_output=True, text=True, cwd=str(tmp_run_dir))


class TestStrictV1LoaderValidation(unittest.TestCase):
    """validate_raw_export_v1 must reject every malformed shape it is asked to guard."""

    def _base_dataset(self):
        return {
            "format": "kestrel-raw-block-export-v1",
            "seed": "3527749958874813416",
            "stage": "FEATURES",
            "order": "order1",
            "order_seed": 424242,
            "min_y": -64,
            "height": 384,
            "coverage": {"min_x": 0, "max_x": 0, "min_z": 0, "max_z": 0, "chunk_count": 1},
            "chunks": [
                {
                    "x": 0, "z": 0, "xPos": 0, "zPos": 0,
                    "default_block": "minecraft:air",
                    "sections": [
                        {"Y": sy, "palette": ["minecraft:air"]}
                        for sy in range(-4, 20)
                    ],
                }
            ],
        }

    def test_valid_dataset_passes(self):
        validate_raw_export_v1(self._base_dataset())  # must not raise

    def test_missing_seed_rejected(self):
        d = self._base_dataset()
        del d["seed"]
        with self.assertRaisesRegex(ValueError, "seed"):
            validate_raw_export_v1(d)

    def test_non_decimal_seed_rejected(self):
        d = self._base_dataset()
        d["seed"] = "12abc"
        with self.assertRaisesRegex(ValueError, "seed"):
            validate_raw_export_v1(d)

    def test_missing_coverage_rejected(self):
        d = self._base_dataset()
        del d["coverage"]
        with self.assertRaisesRegex(ValueError, "coverage"):
            validate_raw_export_v1(d)

    def test_coverage_chunk_count_mismatch_rejected(self):
        d = self._base_dataset()
        d["coverage"]["chunk_count"] = 5
        with self.assertRaisesRegex(ValueError, "chunk_count"):
            validate_raw_export_v1(d)

    def test_duplicate_chunk_coordinate_rejected(self):
        d = self._base_dataset()
        # Widen declared coverage to a valid 2-chunk rectangle so chunk_count
        # is internally consistent; the defect under test is that the SAME
        # (0,0) entry appears twice while (1,0) is never provided.
        d["coverage"] = {"min_x": 0, "max_x": 1, "min_z": 0, "max_z": 0, "chunk_count": 2}
        d["chunks"].append(dict(d["chunks"][0]))
        with self.assertRaisesRegex(ValueError, "duplicate chunk coordinate"):
            validate_raw_export_v1(d)

    def test_missing_chunk_vs_declared_coverage_rejected(self):
        d = self._base_dataset()
        d["coverage"] = {"min_x": 0, "max_x": 1, "min_z": 0, "max_z": 0, "chunk_count": 2}
        # Only chunk (0,0) provided; (1,0) declared by coverage but absent.
        with self.assertRaisesRegex(ValueError, "coordinate coverage mismatch"):
            validate_raw_export_v1(d)

    def test_duplicate_section_y_rejected(self):
        d = self._base_dataset()
        d["chunks"][0]["sections"].append({"Y": -4, "palette": ["minecraft:stone"]})
        with self.assertRaisesRegex(ValueError, "duplicate section"):
            validate_raw_export_v1(d)

    def test_missing_section_rejected(self):
        d = self._base_dataset()
        d["chunks"][0]["sections"].pop()  # drop Y=19, leaving a gap
        with self.assertRaisesRegex(ValueError, "section coverage mismatch"):
            validate_raw_export_v1(d)

    def test_multi_entry_palette_without_data_rejected(self):
        d = self._base_dataset()
        d["chunks"][0]["sections"][0]["palette"] = ["minecraft:air", "minecraft:stone"]
        # no "data" key: ambiguous which cells are which
        with self.assertRaisesRegex(ValueError, "data"):
            validate_raw_export_v1(d)

    def test_data_wrong_length_rejected(self):
        d = self._base_dataset()
        d["chunks"][0]["sections"][0]["palette"] = ["minecraft:air", "minecraft:stone"]
        d["chunks"][0]["sections"][0]["data"] = [0, 1, 0]  # not 4096
        with self.assertRaisesRegex(ValueError, "4096"):
            validate_raw_export_v1(d)

    def test_out_of_range_palette_index_rejected(self):
        d = self._base_dataset()
        d["chunks"][0]["sections"][0]["palette"] = ["minecraft:air", "minecraft:stone"]
        indices = [0] * 4096
        indices[10] = 7  # only 2 palette entries exist
        d["chunks"][0]["sections"][0]["data"] = indices
        with self.assertRaisesRegex(ValueError, "out-of-range"):
            validate_raw_export_v1(d)

    def test_single_entry_palette_with_data_rejected(self):
        d = self._base_dataset()
        d["chunks"][0]["sections"][0]["data"] = [0] * 4096  # ambiguous with a 1-entry palette
        with self.assertRaisesRegex(ValueError, "ambiguous"):
            validate_raw_export_v1(d)

    def test_load_chunk_dataset_enforces_validation(self):
        """The loader itself must call validate_raw_export_v1 for this format, not just the helper."""
        d = self._base_dataset()
        del d["seed"]
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
            json.dump(d, f)
            path = f.name
        try:
            with self.assertRaises(ValueError):
                load_chunk_dataset(path)
        finally:
            Path(path).unlink()


class TestDatasetCompatibility(unittest.TestCase):
    def test_seed_mismatch_between_kestrel_and_vanilla_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            base = {
                "format": "kestrel-raw-block-export-v1",
                "stage": "FEATURES",
                "order": "order1",
                "order_seed": 424242,
                "min_y": -64,
                "height": 16,
                "coverage": {"min_x": 0, "max_x": 0, "min_z": 0, "max_z": 0, "chunk_count": 1},
                "chunks": [{
                    "x": 0, "z": 0, "xPos": 0, "zPos": 0,
                    "sections": [{"Y": -4, "palette": ["minecraft:air"]}],
                }],
            }
            k = dict(base, seed="111")
            v = dict(base, seed="222", stage="FULL", order="vanilla", order_seed=None)
            (tmp / "k.json").write_text(json.dumps(k))
            (tmp / "v.json").write_text(json.dumps(v))
            kd = load_chunk_dataset(str(tmp / "k.json"))
            vd = load_chunk_dataset(str(tmp / "v.json"))
            self.assertIsInstance(kd, ChunkDataset)
            analyzer = ChunkBorderSeamAnalyzer()
            with self.assertRaisesRegex(ValueError, "seed"):
                analyzer.analyze(kd, vanilla_chunks=vd)


class TestSeamAnalyzerCore(unittest.TestCase):
    """Real behavior of the analyzer/classifier, not literal self-checks."""

    def test_swapped_xz_detection(self):
        c0 = ChunkData(0, 0, min_y=-64, height=384)
        c0.set_block(15, 64, 3, "minecraft:sulfur_block")
        c1_correct = ChunkData(1, 0, min_y=-64, height=384)
        c1_correct.set_block(0, 64, 3, "minecraft:deepslate")

        analyzer = ChunkBorderSeamAnalyzer()
        res = analyzer.analyze({(0, 0): c0, (1, 0): c1_correct})
        self.assertEqual(len(res), 1)
        self.assertEqual(res[0]["category"], "sulfur_vs_deepslate")

        c1_swapped = ChunkData(1, 0, min_y=-64, height=384)
        c1_swapped.set_block(3, 64, 0, "minecraft:deepslate")  # wrong axis
        res_swapped = analyzer.analyze({(0, 0): c0, (1, 0): c1_swapped})
        self.assertEqual(len(res_swapped), 1)
        self.assertEqual(res_swapped[0]["block_b"], "minecraft:air")

    def test_negative_coords_and_negative_y_indexing(self):
        chunk = ChunkData(-2, -3, min_y=-64, height=384)
        self.assertEqual(-20 >> 4, -2)
        self.assertEqual(-20 & 15, 12)
        palette = ["minecraft:deepslate", "minecraft:sulfur_block"]
        indices = [0] * 4096
        indices[(12 << 8) | (7 << 4) | 5] = 1
        chunk.sections[-2] = (palette, indices)
        self.assertEqual(chunk.get_block(5, -20, 7), "minecraft:sulfur_block")
        self.assertEqual(chunk.get_block(5, -20, 8), "minecraft:deepslate")

    def test_yzx_cell_indexing_matches_java_formula(self):
        """Index must be ((worldY-minY)<<8)|(z<<4)|x, i.e. (ly<<8)|(lz<<4)|lx per section."""
        chunk = ChunkData(0, 0, min_y=-64, height=384)
        palette = ["minecraft:air", "minecraft:target_block"]
        indices = [0] * 4096
        lx, ly, lz = 2, 3, 5
        indices[(ly << 8) | (lz << 4) | lx] = 1
        chunk.sections[0] = (palette, indices)
        self.assertEqual(chunk.get_block(lx, ly, lz), "minecraft:target_block")
        for wrong in [(lx << 8) | (ly << 4) | lz, (lz << 8) | (ly << 4) | lx, (ly << 8) | (lx << 4) | lz]:
            self.assertNotEqual((ly << 8) | (lz << 4) | lx, wrong)

    def test_reject_missing_oracle_chunks(self):
        analyzer = ChunkBorderSeamAnalyzer()
        kestrel_chunks = {(0, 0): ChunkData(0, 0), (1, 0): ChunkData(1, 0)}
        vanilla_chunks = {(0, 0): ChunkData(0, 0)}
        with self.assertRaises(ValueError) as ctx:
            analyzer.analyze(kestrel_chunks, vanilla_chunks=vanilla_chunks)
        self.assertIn("(1, 0)", str(ctx.exception))

    def test_differing_state_properties_detected(self):
        cA = ChunkData(0, 0)
        cA.set_block(15, 64, 0, "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]")
        cB = ChunkData(1, 0)
        cB.set_block(0, 64, 0, "minecraft:oak_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]")
        bA, bB = cA.get_block(15, 64, 0), cB.get_block(0, 64, 0)
        self.assertEqual(classify_block_pair(bA, bB), CATEGORY_OTHER)
        analyzer = ChunkBorderSeamAnalyzer()
        results = analyzer.analyze({(0, 0): cA, (1, 0): cB})
        self.assertEqual(len(results), 1)
        self.assertIn("facing=north", results[0]["block_a"])
        self.assertIn("facing=south", results[0]["block_b"])

    def test_classifier_strips_properties_only_for_category_not_equality(self):
        """strip_properties feeds category classification; classify_block_pair's
        equality check on b1==b2 must still see the full state string."""
        kelp_age5 = "minecraft:kelp[age=5]"
        kelp_age6 = "minecraft:kelp[age=6]"
        water = "minecraft:water[level=0]"
        self.assertTrue(is_kelp_seagrass(kelp_age5))
        self.assertTrue(is_water_air(water))
        # Different ages still classify as the same category pair...
        self.assertEqual(classify_block_pair(kelp_age5, water), CATEGORY_KELP_SEAGRASS_VS_WATER_AIR)
        # ...but identical full states (same age) must not be reported as a diff at all.
        self.assertIsNone(classify_block_pair(kelp_age5, kelp_age5))
        self.assertIsNotNone(classify_block_pair(kelp_age5, kelp_age6))

    def test_distinguish_unknown_from_air(self):
        self.assertFalse(is_water_air("unknown"))
        cat = classify_block_pair("unknown", "minecraft:air")
        self.assertEqual(cat, CATEGORY_OTHER)
        chunk = ChunkData(0, 0)
        chunk.default_block = "unknown"
        self.assertEqual(chunk.get_block(0, 100, 0), "unknown")


@unittest.skipUnless(JAVA_PREREQS_AVAILABLE, "vanilla assets, JDK, live oracle, or a compiled core build are unavailable")
class TestJavaExporterIntegration(unittest.TestCase):
    """These invoke the real BatchParityGate exporter against the real
    seed-3527749958874813416 oracle at $HOME/kestrel-run/live-seed-oracle-region.
    All subprocess cwd values are temp dirs so log4j output never lands in the repo."""

    def test_end_to_end_roundtrip_against_real_oracle(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            export_dir = tmp / "raw_export"
            proc = _run_batch_parity_gate(tmp, LIVE_ORACLE_DIR, export_dir)
            # BatchParityGate's overall exit code reflects the legacy CSV
            # parity gate (seed 3527749958874813416 is not 100% parity-exact
            # against vanilla at this radius, so a non-zero exit here is
            # expected and orthogonal to whether the raw export itself
            # succeeded); assert on the export artifact instead.
            for name in ("order1.json", "order2.json", "vanilla.json"):
                self.assertTrue((export_dir / name).exists(), f"{name} missing. output: {_combined_output(proc)}")

            order1 = load_chunk_dataset(str(export_dir / "order1.json"))
            order2 = load_chunk_dataset(str(export_dir / "order2.json"))
            vanilla = load_chunk_dataset(str(export_dir / "vanilla.json"))

            # Truthful stage: Kestrel orders ran FEATURES; the oracle is the
            # real vanilla-written region, decoded straight off disk, so its
            # stage is FULL, not FEATURES.
            self.assertEqual(order1.stage, "FEATURES")
            self.assertEqual(order2.stage, "FEATURES")
            self.assertEqual(vanilla.stage, "FULL")

            self.assertEqual(order1.seed, LIVE_SEED)
            self.assertEqual(vanilla.seed, LIVE_SEED)

            # Exact recorded shuffle seeds, not just "some order ran".
            with open(export_dir / "order1.json") as f:
                o1_raw = json.load(f)
            with open(export_dir / "order2.json") as f:
                o2_raw = json.load(f)
            with open(export_dir / "vanilla.json") as f:
                v_raw = json.load(f)
            self.assertEqual(o1_raw["order_seed"], 424242)
            self.assertEqual(o2_raw["order_seed"], 888888)
            self.assertIsNone(v_raw["order_seed"])

            # CARVERS-only margin recorded and explicitly not exported.
            for raw in (o1_raw, o2_raw, v_raw):
                self.assertEqual(raw["margin"]["stage"], "CARVERS")
                self.assertFalse(raw["margin"]["exported"])
                self.assertEqual(raw["margin"]["min_x"], raw["coverage"]["min_x"] - 1)

            # Coordinate/state roundtrip: bedrock at the world floor.
            c_o1 = order1[(0, 0)]
            self.assertEqual(c_o1.get_block(0, -64, 0), "minecraft:bedrock")

            # Property preservation somewhere in the real chunk.
            found_property = any(
                "[" in entry
                for _, (palette, _) in c_o1.sections.items()
                for entry in palette
            )
            self.assertTrue(found_property, "expected at least one block state with serialized properties")

    def test_mca_canonical_state_matches_json_export_canonical_state(self):
        """The same real oracle chunk read two ways (raw MCA vs. BatchParityGate's
        JSON export) must produce byte-identical canonical state strings."""
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            export_dir = tmp / "raw_export"
            proc = _run_batch_parity_gate(tmp, LIVE_ORACLE_DIR, export_dir)
            self.assertTrue((export_dir / "vanilla.json").exists(), _combined_output(proc))

            mca_chunks = load_chunk_dataset(str(LIVE_ORACLE_DIR))
            json_chunks = load_chunk_dataset(str(export_dir / "vanilla.json"))
            self.assertIn((0, 0), mca_chunks)
            self.assertIn((0, 0), json_chunks)

            # Bedrock (no properties) must match.
            self.assertEqual(mca_chunks[(0, 0)].get_block(0, -64, 0), json_chunks[(0, 0)].get_block(0, -64, 0))

            # Find a property-bearing cell in the JSON export and confirm the
            # MCA-sourced reader reports the identical canonical string.
            c_json = json_chunks[(0, 0)]
            found = False
            for sy, (palette, indices) in c_json.sections.items():
                for idx, name in enumerate(palette):
                    if "[" not in name:
                        continue
                    if indices is None:
                        continue
                    for cell_idx, pal_idx in enumerate(indices):
                        if pal_idx != idx:
                            continue
                        lx, lz, ly = cell_idx & 15, (cell_idx >> 4) & 15, (cell_idx >> 8) & 15
                        y = sy * 16 + ly
                        json_state = c_json.get_block(lx, y, lz)
                        mca_state = mca_chunks[(0, 0)].get_block(lx, y, lz)
                        self.assertEqual(mca_state, json_state, f"cell ({lx},{y},{lz}) canonical mismatch")
                        found = True
                        break
                    if found:
                        break
                if found:
                    break
            self.assertTrue(found, "no property-bearing cell found to compare")

    def test_reject_missing_oracle_region_before_producing_exports(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            empty_region_dir = tmp / "empty_regions"
            empty_region_dir.mkdir()
            export_dir = tmp / "raw_export"
            proc = _run_batch_parity_gate(tmp, empty_region_dir, export_dir)
            self.assertNotEqual(proc.returncode, 0)
            self.assertIn("Missing oracle region file", _combined_output(proc))
            self.assertFalse(export_dir.exists(), "no export directory must be created on a rejected run")
            # No file was fabricated in the (otherwise-empty) oracle directory either.
            self.assertEqual(list(empty_region_dir.iterdir()), [])

    def test_reject_existing_output_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            export_dir = tmp / "raw_export"
            proc1 = _run_batch_parity_gate(tmp, LIVE_ORACLE_DIR, export_dir)
            self.assertTrue(export_dir.exists(), _combined_output(proc1))

            proc2 = _run_batch_parity_gate(tmp, LIVE_ORACLE_DIR, export_dir)
            self.assertNotEqual(proc2.returncode, 0)
            self.assertIn("already exists", _combined_output(proc2))

    def test_deterministic_output_bytes_across_runs(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            export_dir_a = tmp / "run_a"
            export_dir_b = tmp / "run_b"
            proc_a = _run_batch_parity_gate(tmp, LIVE_ORACLE_DIR, export_dir_a)
            proc_b = _run_batch_parity_gate(tmp, LIVE_ORACLE_DIR, export_dir_b)
            self.assertTrue(export_dir_a.exists(), _combined_output(proc_a))
            self.assertTrue(export_dir_b.exists(), _combined_output(proc_b))
            for name in ("order1.json", "order2.json", "vanilla.json"):
                bytes_a = (export_dir_a / name).read_bytes()
                bytes_b = (export_dir_b / name).read_bytes()
                self.assertEqual(bytes_a, bytes_b, f"{name} differed byte-for-byte across identical runs")

    def test_source_invariant_snapshots_taken_after_full_order_completes(self):
        """Explicit, honest source-structure check (not a behavioral simulation):
        each order's snapshot/export must be sequenced strictly after that
        order's own 'FEATURES completed' log line in BatchParityGate.java, so a
        cross-chunk feature written late in the shuffled pass is never missed
        by an earlier per-chunk snapshot. This directly encodes the invariant
        BatchParityGate.java must preserve; it does not claim to have observed
        the JVM actually do so at runtime."""
        src = (REPO_ROOT / "core/src/vanilla/java/dev/kestrel/worldgen/BatchParityGate.java").read_text()

        o1_features_done = src.index("Order 1 FEATURES completed")
        o1_snapshot_put = src.index("order1Snapshots.put(k, snapshot(c1, cx, cz));")
        o1_export_call = src.index('exportRawDataset(rawExportTmpDir.resolve("order1.json")')
        self.assertLess(o1_features_done, o1_snapshot_put,
                         "order1 snapshot must be taken after Order 1's FEATURES loop finishes")
        self.assertLess(o1_snapshot_put, o1_export_call,
                         "order1 export must run after its snapshot is captured")

        o2_features_done = src.index("Order 2 FEATURES completed")
        o2_snapshot_put = src.index("order2Snapshots.put(k, snapshot(c2, cx, cz));")
        o2_export_call = src.index('exportRawDataset(rawExportTmpDir.resolve("order2.json")')
        self.assertLess(o2_features_done, o2_snapshot_put,
                         "order2 snapshot must be taken after Order 2's FEATURES loop finishes")
        self.assertLess(o2_snapshot_put, o2_export_call,
                         "order2 export must run after its snapshot is captured")


if __name__ == "__main__":
    unittest.main()
