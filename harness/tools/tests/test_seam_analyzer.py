#!/usr/bin/env python3
"""Unit tests for deterministic chunk-border seam analyzer."""

import json
import os
import tempfile
import unittest
from pathlib import Path

# The analyzer module to be tested
from harness.tools.seam_analyzer import (
    CATEGORY_KELP_SEAGRASS_VS_WATER_AIR,
    CATEGORY_SULFUR_VS_DEEPSLATE,
    CATEGORY_OTHER,
    ChunkBorderSeamAnalyzer,
    classify_block_pair,
    is_kelp_seagrass,
    is_water_air,
    is_sulfur_family,
    is_deepslate_family,
    load_chunk_dataset,
)


class TestSeamAnalyzerClassification(unittest.TestCase):
    """Test block category classification."""

    def test_kelp_seagrass_detection(self):
        self.assertTrue(is_kelp_seagrass("minecraft:kelp"))
        self.assertTrue(is_kelp_seagrass("kelp_plant"))
        self.assertTrue(is_kelp_seagrass("minecraft:seagrass"))
        self.assertTrue(is_kelp_seagrass("tall_seagrass"))
        self.assertFalse(is_kelp_seagrass("minecraft:water"))
        self.assertFalse(is_kelp_seagrass("minecraft:stone"))

    def test_water_air_detection(self):
        self.assertTrue(is_water_air("minecraft:water"))
        self.assertTrue(is_water_air("air"))
        self.assertTrue(is_water_air("minecraft:cave_air"))
        self.assertTrue(is_water_air("void_air"))
        self.assertFalse(is_water_air("minecraft:kelp"))
        self.assertFalse(is_water_air("minecraft:deepslate"))

    def test_sulfur_family_detection(self):
        self.assertTrue(is_sulfur_family("minecraft:sulfur_block"))
        self.assertTrue(is_sulfur_family("sulfur_wall"))
        self.assertTrue(is_sulfur_family("polished_sulfur_wall"))
        self.assertTrue(is_sulfur_family("sulfur_brick_wall"))
        self.assertTrue(is_sulfur_family("potent_sulfur"))
        self.assertTrue(is_sulfur_family("minecraft:sulfur_cube_archetype"))
        self.assertFalse(is_sulfur_family("minecraft:deepslate"))
        self.assertFalse(is_sulfur_family("minecraft:air"))

    def test_deepslate_family_detection(self):
        self.assertTrue(is_deepslate_family("minecraft:deepslate"))
        self.assertTrue(is_deepslate_family("cobbled_deepslate"))
        self.assertTrue(is_deepslate_family("deepslate_diamond_ore"))
        self.assertTrue(is_deepslate_family("deepslate_tiles"))
        self.assertFalse(is_deepslate_family("minecraft:sulfur_block"))
        self.assertFalse(is_deepslate_family("minecraft:stone"))

    def test_pair_classification(self):
        # Kelp/seagrass vs water/air (both directions)
        self.assertEqual(
            classify_block_pair("minecraft:kelp_plant", "minecraft:air"),
            CATEGORY_KELP_SEAGRASS_VS_WATER_AIR,
        )
        self.assertEqual(
            classify_block_pair("minecraft:water", "minecraft:seagrass"),
            CATEGORY_KELP_SEAGRASS_VS_WATER_AIR,
        )

        # Sulfur vs deepslate (both directions)
        self.assertEqual(
            classify_block_pair("minecraft:sulfur_block", "minecraft:deepslate"),
            CATEGORY_SULFUR_VS_DEEPSLATE,
        )
        self.assertEqual(
            classify_block_pair("minecraft:deepslate_diamond_ore", "minecraft:potent_sulfur"),
            CATEGORY_SULFUR_VS_DEEPSLATE,
        )

        # Other / unclassified
        self.assertEqual(
            classify_block_pair("minecraft:stone", "minecraft:dirt"),
            CATEGORY_OTHER,
        )


class TestSeamAnalyzerAnalysis(unittest.TestCase):
    """Test chunk border seam detection, vanilla comparison, and ranking."""

    def setUp(self):
        fixtures_dir = Path(__file__).resolve().parent / "fixtures"
        self.kestrel_fixture = fixtures_dir / "kestrel_sample_chunks.json"
        self.vanilla_fixture = fixtures_dir / "vanilla_sample_chunks.json"

    def test_seam_detection_from_fixtures(self):
        analyzer = ChunkBorderSeamAnalyzer()
        results = analyzer.analyze_paths(
            kestrel_path=str(self.kestrel_fixture),
            vanilla_path=str(self.vanilla_fixture),
        )

        # We expect at least:
        # 1. Kelp/air discontinuity on X-border between (0,0) and (1,0) - NOT in vanilla
        # 2. Sulfur/deepslate discontinuity on Z-border between (0,0) and (0,1) - NOT in vanilla
        # 3. Seagrass/water discontinuity on Z-border between (1,0) and (1,1) - IS in vanilla
        self.assertGreaterEqual(len(results), 3)

        # Verify required output fields on every result
        for r in results:
            self.assertIn("category", r)
            self.assertIn("chunk_a", r)
            self.assertIn("chunk_b", r)
            self.assertIn("axis", r)
            self.assertIn("border", r)
            self.assertIn("block_pair", r)
            self.assertIn("count", r)
            self.assertIn("world_coord", r)
            self.assertIn("exists_in_vanilla", r)
            self.assertIsInstance(r["exists_in_vanilla"], bool)
            self.assertIsInstance(r["count"], int)
            self.assertGreater(r["count"], 0)

    def test_ranking_logic(self):
        analyzer = ChunkBorderSeamAnalyzer()
        results = analyzer.analyze_paths(
            kestrel_path=str(self.kestrel_fixture),
            vanilla_path=str(self.vanilla_fixture),
        )

        # Discontinuities unique to Kestrel (not in vanilla) with target categories
        # must be ranked ahead of discontinuities that also exist in vanilla.
        ranks = [r["rank"] for r in results]
        self.assertEqual(ranks, list(range(1, len(results) + 1)))

        top_result = results[0]
        self.assertFalse(top_result["exists_in_vanilla"])
        self.assertIn(
            top_result["category"],
            [CATEGORY_KELP_SEAGRASS_VS_WATER_AIR, CATEGORY_SULFUR_VS_DEEPSLATE],
        )

    def test_specific_border_checks(self):
        analyzer = ChunkBorderSeamAnalyzer()
        results = analyzer.analyze_paths(
            kestrel_path=str(self.kestrel_fixture),
            vanilla_path=str(self.vanilla_fixture),
        )

        # Find sulfur vs deepslate
        sulfur_results = [r for r in results if r["category"] == CATEGORY_SULFUR_VS_DEEPSLATE]
        self.assertTrue(len(sulfur_results) >= 1)
        sr = sulfur_results[0]
        self.assertEqual(sr["axis"], "Z")
        self.assertEqual(sr["chunk_a"], [0, 0])
        self.assertEqual(sr["chunk_b"], [0, 1])
        self.assertFalse(sr["exists_in_vanilla"])
        self.assertEqual(sum(r["count"] for r in sulfur_results), 4)
        self.assertEqual(sr["count"], 2)  # top pair has count 2 (sulfur_block <-> deepslate)

        # Find seagrass in vanilla
        seagrass_results = [
            r for r in results
            if r["category"] == CATEGORY_KELP_SEAGRASS_VS_WATER_AIR and r["exists_in_vanilla"]
        ]
        self.assertEqual(len(seagrass_results), 1)
        sgr = seagrass_results[0]
        self.assertEqual(sgr["axis"], "Z")
        self.assertEqual(sgr["chunk_a"], [1, 0])
        self.assertEqual(sgr["chunk_b"], [1, 1])
        self.assertTrue(sgr["exists_in_vanilla"])
        self.assertEqual(sgr["count"], 2)

    def test_json_and_table_formatting(self):
        analyzer = ChunkBorderSeamAnalyzer()
        results = analyzer.analyze_paths(
            kestrel_path=str(self.kestrel_fixture),
            vanilla_path=str(self.vanilla_fixture),
        )
        json_str = analyzer.to_json(results)
        parsed = json.loads(json_str)
        self.assertEqual(len(parsed), len(results))

        table_str = analyzer.to_table(results)
        self.assertIn("RANK", table_str)
        self.assertIn("CATEGORY", table_str)
        self.assertIn("CHUNK BORDER", table_str)
        self.assertIn("kelp_seagrass_vs_water_air", table_str)
        self.assertIn("sulfur_vs_deepslate", table_str)


class TestSeamAnalyzerAnvilMca(unittest.TestCase):
    """Test reading Anvil .mca region files (reusing g2-region synthetic generator)."""

    def test_mca_roundtrip_analysis(self):
        import importlib.util
        g2_path = Path(__file__).resolve().parent.parent / "g2-region.py"
        spec = importlib.util.spec_from_file_location("g2_region", g2_path)
        g2_region = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(g2_region)

        with tempfile.TemporaryDirectory() as tmpdir:
            kestrel_world = Path(tmpdir) / "kestrel_world"
            vanilla_world = Path(tmpdir) / "vanilla_world"

            # Create synthetic vanilla world using g2-region (half=1 covers 3x3 chunks including (0,0) and (1,0))
            class SynthArgs:
                def __init__(self, out, half=1, center="0,0"):
                    self.out = str(out)
                    self.half = half
                    self.center = center

            g2_region.cmd_synth(SynthArgs(vanilla_world, half=1, center="0,0"))

            # Create synthetic kestrel world with 2 chunks (0,0) and (1,0)
            k_reg_dir = kestrel_world / "region"
            k_reg_dir.mkdir(parents=True, exist_ok=True)
            v_reg_dir = vanilla_world / "region"

            # Generate chunks (0,0) and (1,0)
            c00 = g2_region.synth_chunk(0, 0)
            c10 = g2_region.synth_chunk(1, 0)

            # In kestrel c00, inject kelp at x=15, y=65, z=0 (where neighbor in c10 is air)
            # Section Y=4 spans y=64..79
            for sec in c00["sections"]:
                sy = getattr(sec.get("Y"), "value", sec.get("Y"))
                if sy == 4:
                    sec["block_states"]["palette"].append({"Name": "minecraft:kelp_plant"})
                    kelp_id = len(sec["block_states"]["palette"]) - 1
                    raw = g2_region.unpack(
                        sec["block_states"]["data"].value,
                        g2_region.disc_bits(len(sec["block_states"]["palette"]) - 1),
                        len(sec["block_states"]["palette"]) - 1,
                    )
                    # local y = 65 - 64 = 1, lx = 15, lz = 0
                    idx = 15 | (0 << 4) | (1 << 8)
                    raw[idx] = kelp_id
                    bits = g2_region.disc_bits(len(sec["block_states"]["palette"]))
                    sec["block_states"]["data"] = g2_region.Typed(
                        g2_region.TAG_LONG_ARRAY, g2_region.pack(raw, bits)
                    )

            k_chunks = {(0, 0): c00, (1, 0): c10}
            g2_region.write_region(str(k_reg_dir / "r.0.0.mca"), k_chunks)

            # Now run analyzer on kestrel_world vs vanilla_world
            analyzer = ChunkBorderSeamAnalyzer()
            results = analyzer.analyze_paths(
                kestrel_path=str(k_reg_dir / "r.0.0.mca"),
                vanilla_path=str(v_reg_dir / "r.0.0.mca"),
            )

            kelp_results = [r for r in results if r["category"] == CATEGORY_KELP_SEAGRASS_VS_WATER_AIR]
            self.assertTrue(len(kelp_results) >= 1)
            kr = kelp_results[0]
            self.assertEqual(kr["axis"], "X")
            self.assertEqual(kr["chunk_a"], [0, 0])
            self.assertEqual(kr["chunk_b"], [1, 0])
            self.assertFalse(kr["exists_in_vanilla"])


class TestSeamAnalyzerDivergenceReporting(unittest.TestCase):
    """Reported coordinates of a partially-matching finding must point at a real divergence.

    A finding aggregates every border instance sharing (category, axis, chunk pair,
    block pair). When a vanilla dataset is supplied, some of those instances can match
    vanilla exactly while others diverge, which is what 0 < vanilla_match_count < count
    means. The coordinates the finding reports are what a consumer investigates, so they
    have to come from the diverging instances, not from the whole group.

    Shaped after the real seed-3527749958874813416 sulfur/deepslate findings on the
    (-6,6) | (-5,6) X border, where the lowest-y instance of a "genuinely diverging"
    finding is in fact a position at which Kestrel and vanilla agree on both sides.
    """

    DEEPSLATE = "minecraft:deepslate[axis=y]"
    SULFUR = "minecraft:sulfur"

    # X border between chunk (-6,6) (lx 15) and chunk (-5,6) (lx 0), at lz 0.
    MATCHING_Y = -20     # Kestrel sulfur|deepslate, vanilla sulfur|deepslate -> agrees
    DIVERGING_Y = -17    # Kestrel sulfur|deepslate, vanilla sulfur|sulfur    -> diverges

    def _write_dataset(self, path, chunks):
        payload = {
            "format": "kestrel-chunk-dump-v1",
            "min_y": -64,
            "height": 384,
            "chunks": [
                {
                    "xPos": cx,
                    "zPos": cz,
                    "default_block": self.DEEPSLATE,
                    "blocks": blocks,
                }
                for (cx, cz), blocks in chunks.items()
            ],
        }
        path.write_text(json.dumps(payload))
        return str(path)

    def _analyze(self, tmp):
        kestrel = self._write_dataset(
            Path(tmp) / "kestrel.json",
            {
                (-6, 6): {
                    f"15,{self.MATCHING_Y},0": self.SULFUR,
                    f"15,{self.DIVERGING_Y},0": self.SULFUR,
                },
                (-5, 6): {},
            },
        )
        vanilla = self._write_dataset(
            Path(tmp) / "vanilla.json",
            {
                (-6, 6): {
                    f"15,{self.MATCHING_Y},0": self.SULFUR,
                    f"15,{self.DIVERGING_Y},0": self.SULFUR,
                },
                # Vanilla keeps sulfur going across the border at DIVERGING_Y, so
                # Kestrel's deepslate there is a real divergence; at MATCHING_Y
                # vanilla has the same sulfur|deepslate pair Kestrel has.
                (-5, 6): {f"0,{self.DIVERGING_Y},0": self.SULFUR},
            },
        )
        analyzer = ChunkBorderSeamAnalyzer()
        results = analyzer.analyze_paths(kestrel_path=kestrel, vanilla_path=vanilla)
        findings = [r for r in results if r["category"] == CATEGORY_SULFUR_VS_DEEPSLATE]
        self.assertEqual(len(findings), 1, f"expected one aggregated finding, got {findings}")
        return findings[0]

    def test_partially_matching_finding_is_detected(self):
        with tempfile.TemporaryDirectory() as tmp:
            finding = self._analyze(tmp)
        self.assertEqual(finding["count"], 2)
        self.assertEqual(finding["vanilla_match_count"], 1)
        self.assertFalse(finding["exists_in_vanilla"])

    def test_world_coord_points_at_a_diverging_instance(self):
        with tempfile.TemporaryDirectory() as tmp:
            finding = self._analyze(tmp)
        self.assertEqual(
            finding["world_coord"],
            [-81, self.DIVERGING_Y, 96],
            "world_coord must be a position where Kestrel and vanilla actually differ, "
            "not the lowest-y instance of the group (which here matches vanilla exactly)",
        )

    def test_diverging_instances_are_reported_separately(self):
        with tempfile.TemporaryDirectory() as tmp:
            finding = self._analyze(tmp)
        self.assertEqual(finding["diverging_count"], 1)
        self.assertEqual(finding["diverging_y_min"], self.DIVERGING_Y)
        self.assertEqual(finding["diverging_y_max"], self.DIVERGING_Y)
        self.assertEqual(
            finding["diverging_coordinates"],
            [{"pos_a": [-81, self.DIVERGING_Y, 96], "pos_b": [-80, self.DIVERGING_Y, 96]}],
        )

    def test_group_wide_y_range_still_spans_every_instance(self):
        """count/y_min/y_max keep their group-wide meaning; only the coordinates narrow."""
        with tempfile.TemporaryDirectory() as tmp:
            finding = self._analyze(tmp)
        self.assertEqual(finding["y_min"], self.MATCHING_Y)
        self.assertEqual(finding["y_max"], self.DIVERGING_Y)

    def test_without_vanilla_dataset_coordinates_stay_group_wide(self):
        """With nothing to cross-check against, every instance is still a candidate."""
        with tempfile.TemporaryDirectory() as tmp:
            kestrel = self._write_dataset(
                Path(tmp) / "kestrel.json",
                {
                    (-6, 6): {
                        f"15,{self.MATCHING_Y},0": self.SULFUR,
                        f"15,{self.DIVERGING_Y},0": self.SULFUR,
                    },
                    (-5, 6): {},
                },
            )
            analyzer = ChunkBorderSeamAnalyzer()
            results = analyzer.analyze_paths(kestrel_path=kestrel)
        finding = [r for r in results if r["category"] == CATEGORY_SULFUR_VS_DEEPSLATE][0]
        self.assertEqual(finding["world_coord"], [-81, self.MATCHING_Y, 96])
        self.assertEqual(finding["diverging_count"], 0)
        self.assertEqual(finding["diverging_coordinates"], [])

    def test_z_border_reports_the_diverging_instance(self):
        """The equivalent Z-border lookup also selects the real divergence."""
        with tempfile.TemporaryDirectory() as tmp:
            kestrel = self._write_dataset(
                Path(tmp) / "kestrel.json",
                {
                    (6, -6): {
                        f"0,{self.MATCHING_Y},15": self.SULFUR,
                        f"0,{self.DIVERGING_Y},15": self.SULFUR,
                    },
                    (6, -5): {},
                },
            )
            vanilla = self._write_dataset(
                Path(tmp) / "vanilla.json",
                {
                    (6, -6): {
                        f"0,{self.MATCHING_Y},15": self.SULFUR,
                        f"0,{self.DIVERGING_Y},15": self.SULFUR,
                    },
                    (6, -5): {f"0,{self.DIVERGING_Y},0": self.SULFUR},
                },
            )
            analyzer = ChunkBorderSeamAnalyzer()
            results = analyzer.analyze_paths(kestrel_path=kestrel, vanilla_path=vanilla)

        findings = [r for r in results if r["category"] == CATEGORY_SULFUR_VS_DEEPSLATE]
        self.assertEqual(len(findings), 1, f"expected one aggregated finding, got {findings}")
        finding = findings[0]
        self.assertEqual(finding["world_coord"], [96, self.DIVERGING_Y, -81])
        self.assertEqual(
            finding["diverging_coordinates"],
            [{"pos_a": [96, self.DIVERGING_Y, -81], "pos_b": [96, self.DIVERGING_Y, -80]}],
        )


if __name__ == "__main__":
    unittest.main()
