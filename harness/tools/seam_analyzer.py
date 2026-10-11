#!/usr/bin/env python3
"""Deterministic Chunk-Border Seam Analyzer for Kestrel vs Vanilla parity.

Analyzes block-level chunk dumps and Anvil region files (.mca) across adjacent
chunk borders to identify and rank suspicious discontinuities, with explicit
categories for:
  - kelp/seagrass versus water/air
  - sulfur-family blocks versus deepslate

Stdlib only. Deterministic ordering and output.

Usage:
  python3 harness/tools/seam_analyzer.py --kestrel <path> [--vanilla <path>] [--format table|json]
"""

import argparse
import importlib.util
import json
import os
import struct
import sys
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Set, Tuple

# Categories
CATEGORY_KELP_SEAGRASS_VS_WATER_AIR = "kelp_seagrass_vs_water_air"
CATEGORY_SULFUR_VS_DEEPSLATE = "sulfur_vs_deepslate"
CATEGORY_OTHER = "other"

# Block classification sets
KELP_SEAGRASS_NAMES = {
    "kelp",
    "kelp_plant",
    "seagrass",
    "tall_seagrass",
}

WATER_AIR_NAMES = {
    "water",
    "flowing_water",
    "air",
    "cave_air",
    "void_air",
}


def strip_namespace(block_name: str) -> str:
    """Strip 'minecraft:' or custom namespace prefix."""
    if not block_name:
        return ""
    if ":" in block_name:
        return block_name.split(":", 1)[1]
    return block_name


def strip_properties(block_name: str) -> str:
    """Strip bracketed block state properties, e.g. '[facing=north]'."""
    if not block_name:
        return ""
    if "[" in block_name:
        return block_name.split("[", 1)[0]
    return block_name


def is_kelp_seagrass(block_name: str) -> bool:
    """Return True if block is kelp or seagrass."""
    name = strip_namespace(strip_properties(block_name)).lower()
    return name in KELP_SEAGRASS_NAMES


def is_water_air(block_name: str) -> bool:
    """Return True if block is water or air."""
    name = strip_namespace(strip_properties(block_name)).lower()
    return name in WATER_AIR_NAMES


def is_sulfur_family(block_name: str) -> bool:
    """Return True if block is part of the sulfur family."""
    name = strip_namespace(strip_properties(block_name)).lower()
    return "sulfur" in name


def is_deepslate_family(block_name: str) -> bool:
    """Return True if block is deepslate or deepslate ore/variant."""
    name = strip_namespace(strip_properties(block_name)).lower()
    return "deepslate" in name


def _canonical_palette_entry(p: Any) -> str:
    """One shared canonicalization for a palette entry, used by both the MCA
    loader and the JSON loader so they can never silently drift apart:
    "namespace:name[k=v,...]" with properties sorted by key, matching
    BatchParityGate.formatBlockState's Java-side canonical form."""
    if not isinstance(p, dict):
        return str(p)
    name = p.get("Name", p.get("name", "minecraft:air"))
    props = p.get("Properties", p.get("properties"))
    if props and isinstance(props, dict):
        prop_str = ",".join(f"{k}={v}" for k, v in sorted(props.items()))
        return f"{name}[{prop_str}]"
    return name


def classify_block_pair(b1: str, b2: str) -> str:
    """Classify a pair of differing blocks across a chunk border."""
    if b1 == b2:
        return None

    # Kelp/seagrass vs water/air (either direction)
    if (is_kelp_seagrass(b1) and is_water_air(b2)) or (is_kelp_seagrass(b2) and is_water_air(b1)):
        return CATEGORY_KELP_SEAGRASS_VS_WATER_AIR

    # Sulfur-family vs deepslate (either direction)
    if (is_sulfur_family(b1) and is_deepslate_family(b2)) or (
        is_sulfur_family(b2) and is_deepslate_family(b1)
    ):
        return CATEGORY_SULFUR_VS_DEEPSLATE

    return CATEGORY_OTHER


# ---- MCA / Region loader reusing g2-region.py -----------------------------


def _load_g2_region_module():
    """Load g2-region.py from the same directory if available."""
    script_dir = Path(__file__).resolve().parent
    g2_path = script_dir / "g2-region.py"
    if g2_path.exists():
        spec = importlib.util.spec_from_file_location("g2_region", g2_path)
        if spec and spec.loader:
            mod = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(mod)
            return mod
    return None


_g2 = _load_g2_region_module()


class ChunkData:
    """In-memory 3D block store for one chunk column."""

    def __init__(self, cx: int, cz: int, min_y: int = -64, height: int = 384):
        self.cx = cx
        self.cz = cz
        self.min_y = min_y
        self.height = height
        self.max_y = min_y + height - 1
        # Sparse block map: (lx, y, lz) -> block_name
        self.sparse_blocks: Dict[Tuple[int, int, int], str] = {}
        # Section data for full MCA chunks: section_y -> (palette, indices)
        self.sections: Dict[int, Tuple[List[str], Optional[List[int]]]] = {}
        self.default_block = "minecraft:air"

    def set_block(self, lx: int, y: int, lz: int, block_name: str):
        self.sparse_blocks[(lx, y, lz)] = block_name

    def get_block(self, lx: int, y: int, lz: int) -> str:
        # Check sparse override
        pos = (lx, y, lz)
        if pos in self.sparse_blocks:
            return self.sparse_blocks[pos]

        # Check section
        sy = y >> 4
        if sy in self.sections:
            palette, indices = self.sections[sy]
            if indices is None:
                # Single-value palette
                return palette[0] if palette else self.default_block
            ly = y & 15
            idx = (ly << 8) | (lz << 4) | lx
            if 0 <= idx < len(indices):
                pal_idx = indices[idx]
                if 0 <= pal_idx < len(palette):
                    return palette[pal_idx]
        return self.default_block


RAW_EXPORT_FORMAT_V1 = "kestrel-raw-block-export-v1"


def validate_raw_export_v1(data: Dict[str, Any]) -> None:
    """Strict-reject malformed kestrel-raw-block-export-v1 payloads.

    Only applies to this explicit format: legacy sparse JSON dumps and the
    generic chunk-list shape stay permissive (existing behavior). A v1
    payload must be internally consistent enough that no downstream cell
    read can silently fall back to a fabricated default.
    """
    seed = data.get("seed")
    if not isinstance(seed, str) or not seed or not seed.lstrip("-").isdigit():
        raise ValueError(f"kestrel-raw-block-export-v1: invalid or missing 'seed' (decimal string required): {seed!r}")

    min_y = data.get("min_y")
    height = data.get("height")
    if not isinstance(min_y, int) or not isinstance(height, int) or height <= 0 or height % 16 != 0:
        raise ValueError(f"kestrel-raw-block-export-v1: invalid or missing min_y/height: min_y={min_y!r} height={height!r}")
    min_section_y = min_y >> 4
    num_sections = height >> 4
    expected_section_ys = set(range(min_section_y, min_section_y + num_sections))

    coverage = data.get("coverage")
    if not isinstance(coverage, dict):
        raise ValueError("kestrel-raw-block-export-v1: missing 'coverage' metadata")
    required_coverage_keys = ("min_x", "max_x", "min_z", "max_z", "chunk_count")
    for key in required_coverage_keys:
        if not isinstance(coverage.get(key), int):
            raise ValueError(f"kestrel-raw-block-export-v1: coverage missing/invalid integer field {key!r}: {coverage!r}")
    if coverage["min_x"] > coverage["max_x"] or coverage["min_z"] > coverage["max_z"]:
        raise ValueError(f"kestrel-raw-block-export-v1: coverage bounds inverted: {coverage!r}")
    expected_coords = {
        (x, z)
        for x in range(coverage["min_x"], coverage["max_x"] + 1)
        for z in range(coverage["min_z"], coverage["max_z"] + 1)
    }
    if coverage["chunk_count"] != len(expected_coords):
        raise ValueError(
            f"kestrel-raw-block-export-v1: coverage.chunk_count {coverage['chunk_count']} does not match "
            f"declared bounds ({len(expected_coords)} chunks implied)"
        )

    raw_chunks = data.get("chunks")
    if not isinstance(raw_chunks, list) or not raw_chunks:
        raise ValueError("kestrel-raw-block-export-v1: missing or empty 'chunks' list")

    seen_coords: Set[Tuple[int, int]] = set()
    for entry in raw_chunks:
        if not isinstance(entry, dict):
            raise ValueError(f"kestrel-raw-block-export-v1: chunk entry is not an object: {entry!r}")
        cx, cz = entry.get("x"), entry.get("z")
        if not isinstance(cx, int) or not isinstance(cz, int):
            raise ValueError(f"kestrel-raw-block-export-v1: chunk entry missing signed integer x/z: {entry!r}")
        if entry.get("xPos") != cx or entry.get("zPos") != cz:
            raise ValueError(f"kestrel-raw-block-export-v1: x/z and xPos/zPos disagree for chunk {(cx, cz)}: {entry!r}")
        if (cx, cz) in seen_coords:
            raise ValueError(f"kestrel-raw-block-export-v1: duplicate chunk coordinate {(cx, cz)}")
        seen_coords.add((cx, cz))

        sections = entry.get("sections")
        if not isinstance(sections, list):
            raise ValueError(f"kestrel-raw-block-export-v1: chunk {(cx, cz)} missing 'sections' list")
        seen_ys: Set[int] = set()
        for sec in sections:
            if not isinstance(sec, dict) or "Y" not in sec:
                raise ValueError(f"kestrel-raw-block-export-v1: chunk {(cx, cz)} has a malformed section entry: {sec!r}")
            sy = sec["Y"]
            if sy in seen_ys:
                raise ValueError(f"kestrel-raw-block-export-v1: chunk {(cx, cz)} has duplicate section Y={sy}")
            seen_ys.add(sy)

            palette = sec.get("palette")
            if not isinstance(palette, list) or not palette or not all(isinstance(p, str) for p in palette):
                raise ValueError(f"kestrel-raw-block-export-v1: chunk {(cx, cz)} section Y={sy} has an invalid palette: {palette!r}")

            indices = sec.get("data")
            if len(palette) == 1:
                if indices is not None:
                    raise ValueError(
                        f"kestrel-raw-block-export-v1: chunk {(cx, cz)} section Y={sy} has a single-entry "
                        f"palette but still carries a 'data' array (ambiguous, must be omitted)"
                    )
            else:
                if not isinstance(indices, list) or len(indices) != 4096:
                    raise ValueError(
                        f"kestrel-raw-block-export-v1: chunk {(cx, cz)} section Y={sy} palette has "
                        f"{len(palette)} entries but 'data' is not a 4096-element array"
                    )
                bad = [i for i in indices if not isinstance(i, int) or i < 0 or i >= len(palette)]
                if bad:
                    raise ValueError(
                        f"kestrel-raw-block-export-v1: chunk {(cx, cz)} section Y={sy} has out-of-range "
                        f"palette index/indices (palette size {len(palette)}): first bad value {bad[0]!r}"
                    )

        if seen_ys != expected_section_ys:
            missing = expected_section_ys - seen_ys
            extra = seen_ys - expected_section_ys
            raise ValueError(
                f"kestrel-raw-block-export-v1: chunk {(cx, cz)} section coverage mismatch "
                f"(missing={sorted(missing)}, unexpected={sorted(extra)})"
            )

    if seen_coords != expected_coords:
        missing = expected_coords - seen_coords
        extra = seen_coords - expected_coords
        raise ValueError(
            f"kestrel-raw-block-export-v1: chunk coordinate coverage mismatch vs declared bounds "
            f"(missing={sorted(missing)}, unexpected={sorted(extra)})"
        )


def _check_dataset_compatibility(a: Any, b: Any) -> None:
    """Reject comparisons across datasets whose declared seed/height/min_y disagree.

    Only fires when both sides carry metadata (ChunkDataset from a JSON
    load); MCA-sourced or legacy plain-dict datasets carry no such
    metadata and are left to existing permissive behavior.
    """
    if not isinstance(a, ChunkDataset) or not isinstance(b, ChunkDataset):
        return
    for field_name in ("seed", "height", "min_y"):
        av, bv = getattr(a, field_name), getattr(b, field_name)
        if av is not None and bv is not None and av != bv:
            raise ValueError(
                f"Dataset metadata mismatch on {field_name!r}: kestrel={av!r} vanilla={bv!r} "
                f"(refusing to compare datasets from different seeds/geometries)"
            )


class ChunkDataset(dict):
    """Chunk dictionary with dataset metadata (seed, stage, order, coverage, min_y, height)."""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.seed: Optional[str] = None
        self.stage: Optional[str] = None
        self.order: Optional[str] = None
        self.coverage: Optional[Dict[str, Any]] = None
        self.min_y: int = -64
        self.height: int = 384
        self.format: Optional[str] = None


def load_chunk_dataset(path_str: str) -> Dict[Tuple[int, int], ChunkData]:
    """Load chunk dataset from a file or directory.

    Supported formats:
      1. Anvil .mca region file or directory containing region/*.mca files.
      2. JSON chunk dump (kestrel-chunk-dump-v1, kestrel-raw-block-export-v1, or generic chunk list).
    """
    path = Path(path_str).resolve()
    if not path.exists():
        raise FileNotFoundError(f"Input path not found: {path}")

    chunks: ChunkDataset = ChunkDataset()

    if path.is_file() and path.suffix.lower() == ".json":
        # Load JSON chunk dump
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        if isinstance(data, dict) and data.get("format") == RAW_EXPORT_FORMAT_V1:
            validate_raw_export_v1(data)
        min_y = data.get("min_y", -64)
        height = data.get("height", 384)
        if isinstance(data, dict):
            chunks.seed = str(data["seed"]) if "seed" in data else None
            chunks.stage = data.get("stage")
            chunks.order = data.get("order")
            chunks.coverage = data.get("coverage")
            chunks.min_y = min_y
            chunks.height = height
            chunks.format = data.get("format")
            raw_chunks = data.get("chunks", [])
        else:
            raw_chunks = data

        for c_entry in raw_chunks:
            cx = c_entry.get("xPos", c_entry.get("x", 0))
            cz = c_entry.get("zPos", c_entry.get("z", 0))
            chunk = ChunkData(cx, cz, min_y, height)
            chunk.default_block = c_entry.get("default_block", "minecraft:air")

            # Load sections if present
            sections = c_entry.get("sections", [])
            for sec in sections:
                sy = sec.get("Y", sec.get("y"))
                if sy is None:
                    continue
                raw_palette = sec.get("palette", [])
                palette = [_canonical_palette_entry(p) for p in raw_palette]

                indices = sec.get("data", sec.get("indices"))
                if indices is None or len(indices) == 0:
                    chunk.sections[sy] = (palette, None)
                else:
                    chunk.sections[sy] = (palette, indices)

            # Load sparse blocks if present
            blocks = c_entry.get("blocks", {})
            for coord_str, bname in blocks.items():
                parts = [int(v.strip()) for v in coord_str.split(",")]
                if len(parts) == 3:
                    # Could be local (0..15) or world coords
                    px, py, pz = parts
                    lx = px & 15 if (px < 0 or px > 15) else px
                    lz = pz & 15 if (pz < 0 or pz > 15) else pz
                    chunk.set_block(lx, py, lz, bname)

            chunks[(cx, cz)] = chunk
        return chunks

    # Otherwise treat as MCA region file or world directory
    mca_files: List[Path] = []
    if path.is_file() and path.suffix.lower() == ".mca":
        mca_files.append(path)
    elif path.is_dir():
        reg_dir = path / "region" if (path / "region").is_dir() else path
        mca_files.extend(sorted(reg_dir.glob("*.mca")))

    if not mca_files:
        raise ValueError(f"No .mca or .json chunk dump found at: {path}")

    if _g2 is None:
        raise RuntimeError("g2-region.py reader module could not be loaded for MCA parsing.")

    for mca_path in mca_files:
        for root in _g2.read_region(str(mca_path)):
            cx = root.get("xPos")
            cz = root.get("zPos")
            if cx is None or cz is None:
                continue
            chunk = ChunkData(cx, cz)
            for sec in root.get("sections", []):
                sy_val = sec.get("Y", 0)
                sy = getattr(sy_val, "value", sy_val)
                bs = sec.get("block_states")
                if not isinstance(bs, dict) or "palette" not in bs:
                    continue
                # Canonical form must match the JSON exporter's
                # formatBlockState (BatchParityGate.java): name plus
                # comma-joined "k=v" properties sorted by key. Dropping
                # Properties here (as the previous name-only palette did)
                # silently collapsed every state-bearing block (e.g.
                # "deepslate[axis=y]") down to its bare name, so a real MCA
                # oracle and a JSON export of the identical chunk would
                # disagree on chunks that actually match.
                palette = [_canonical_palette_entry(p) for p in bs["palette"]]
                if len(palette) == 1:
                    chunk.sections[sy] = (palette, None)
                elif "data" in bs:
                    raw_data = bs["data"].value if hasattr(bs["data"], "value") else bs["data"]
                    bits = _g2.disc_bits(len(palette))
                    indices = _g2.unpack(raw_data, bits, len(palette))
                    chunk.sections[sy] = (palette, indices)
            chunks[(cx, cz)] = chunk

    return chunks


# ---- Analyzer Core --------------------------------------------------------


@dataclass
class DiscontinuityInstance:
    axis: str  # "X" or "Z"
    chunk_a: Tuple[int, int]
    chunk_b: Tuple[int, int]
    world_pos_a: Tuple[int, int, int]
    world_pos_b: Tuple[int, int, int]
    block_a: str
    block_b: str
    category: str


@dataclass
class RankedDiscontinuity:
    rank: int
    category: str
    axis: str
    chunk_a: List[int]
    chunk_b: List[int]
    border: str
    block_a: str
    block_b: str
    block_pair: str
    count: int
    world_coord: List[int]  # Sample [x, y, z]
    y_min: int
    y_max: int
    exists_in_vanilla: bool
    vanilla_match_count: int
    coordinates: List[Dict[str, Any]] = field(default_factory=list)


class ChunkBorderSeamAnalyzer:
    """Analyzes chunk borders for discontinuities and ranks them deterministically."""

    def __init__(self, min_y: int = -64, height: int = 384):
        self.min_y = min_y
        self.height = height
        self.max_y = min_y + height - 1

    def analyze(
        self,
        kestrel_chunks: Dict[Tuple[int, int], ChunkData],
        vanilla_chunks: Optional[Dict[Tuple[int, int], ChunkData]] = None,
        category_filter: Optional[str] = None,
        min_count: int = 1,
    ) -> List[Dict[str, Any]]:
        """Identify, categorize, cross-check with vanilla, and rank chunk-border discontinuities."""
        if vanilla_chunks is not None:
            _check_dataset_compatibility(kestrel_chunks, vanilla_chunks)
            missing_oracle = [c for c in sorted(kestrel_chunks.keys()) if c not in vanilla_chunks]
            if missing_oracle:
                raise ValueError(
                    f"Missing oracle chunks in vanilla dataset: {missing_oracle}"
                )

        instances: List[DiscontinuityInstance] = []

        sorted_chunks = sorted(kestrel_chunks.keys())

        for cx, cz in sorted_chunks:
            chunk_a = kestrel_chunks[(cx, cz)]

            # 1. Check East (+X) border: chunk (cx, cz) and chunk (cx+1, cz)
            if (cx + 1, cz) in kestrel_chunks:
                chunk_b = kestrel_chunks[(cx + 1, cz)]
                lx_a = 15
                lx_b = 0
                wx_a = cx * 16 + lx_a
                wx_b = (cx + 1) * 16 + lx_b
                for lz in range(16):
                    wz = cz * 16 + lz
                    for y in range(self.min_y, self.max_y + 1):
                        b_a = chunk_a.get_block(lx_a, y, lz)
                        b_b = chunk_b.get_block(lx_b, y, lz)
                        if b_a != b_b:
                            cat = classify_block_pair(b_a, b_b)
                            if cat:
                                instances.append(
                                    DiscontinuityInstance(
                                        axis="X",
                                        chunk_a=(cx, cz),
                                        chunk_b=(cx + 1, cz),
                                        world_pos_a=(wx_a, y, wz),
                                        world_pos_b=(wx_b, y, wz),
                                        block_a=b_a,
                                        block_b=b_b,
                                        category=cat,
                                    )
                                )

            # 2. Check South (+Z) border: chunk (cx, cz) and chunk (cx, cz+1)
            if (cx, cz + 1) in kestrel_chunks:
                chunk_b = kestrel_chunks[(cx, cz + 1)]
                lz_a = 15
                lz_b = 0
                wz_a = cz * 16 + lz_a
                wz_b = (cz + 1) * 16 + lz_b
                for lx in range(16):
                    wx = cx * 16 + lx
                    for y in range(self.min_y, self.max_y + 1):
                        b_a = chunk_a.get_block(lx, y, lz_a)
                        b_b = chunk_b.get_block(lx, y, lz_b)
                        if b_a != b_b:
                            cat = classify_block_pair(b_a, b_b)
                            if cat:
                                instances.append(
                                    DiscontinuityInstance(
                                        axis="Z",
                                        chunk_a=(cx, cz),
                                        chunk_b=(cx, cz + 1),
                                        world_pos_a=(wx, y, wz_a),
                                        world_pos_b=(wx, y, wz_b),
                                        block_a=b_a,
                                        block_b=b_b,
                                        category=cat,
                                    )
                                )

        # Aggregate instances by (category, axis, chunk_a, chunk_b, block_a, block_b)
        groups: Dict[Tuple[str, str, Tuple[int, int], Tuple[int, int], str, str], List[DiscontinuityInstance]] = {}
        for inst in instances:
            key = (inst.category, inst.axis, inst.chunk_a, inst.chunk_b, inst.block_a, inst.block_b)
            groups.setdefault(key, []).append(inst)

        aggregated: List[Dict[str, Any]] = []

        for (cat, axis, ca, cb, ba, bb), inst_list in groups.items():
            if category_filter and category_filter != "all" and cat != category_filter:
                continue

            count = len(inst_list)
            if count < min_count:
                continue

            # Sort coordinates deterministically
            inst_list.sort(key=lambda it: (it.world_pos_a[1], it.world_pos_a[2], it.world_pos_a[0]))
            y_min = min(it.world_pos_a[1] for it in inst_list)
            y_max = max(it.world_pos_a[1] for it in inst_list)

            # Check whether the same discontinuity exists in vanilla.
            # A group mixes instances that match vanilla with instances that do
            # not, so track the diverging ones individually: they are the only
            # positions worth investigating, and reporting a coordinate drawn
            # from the whole group points a reader at a cell where Kestrel and
            # vanilla agree exactly whenever 0 < vanilla_match_count < count.
            exists_in_vanilla = False
            vanilla_match_count = 0
            diverging: List[DiscontinuityInstance] = []

            if vanilla_chunks and ca in vanilla_chunks and cb in vanilla_chunks:
                v_ca = vanilla_chunks[ca]
                v_cb = vanilla_chunks[cb]
                for it in inst_list:
                    if axis == "X":
                        lx_a, lx_b = 15, 0
                        lz = it.world_pos_a[2] & 15
                        y = it.world_pos_a[1]
                        v_ba = v_ca.get_block(lx_a, y, lz)
                        v_bb = v_cb.get_block(lx_b, y, lz)
                    else:  # Z axis
                        lx = it.world_pos_a[0] & 15
                        lz_a, lz_b = 15, 0
                        y = it.world_pos_a[1]
                        v_ba = v_ca.get_block(lx, y, lz_a)
                        v_bb = v_cb.get_block(lx, y, lz_b)

                    if v_ba == ba and v_bb == bb:
                        vanilla_match_count += 1
                    else:
                        diverging.append(it)

                # If vanilla matches the discontinuity at these positions
                exists_in_vanilla = vanilla_match_count > 0 and (vanilla_match_count == count)

            # Coordinates describe the diverging instances once there are any;
            # with no vanilla dataset to cross-check against, every instance is
            # still a candidate and the group-wide sample stands.
            reported = diverging if diverging else inst_list
            sample_coord = list(reported[0].world_pos_a)

            coords_detail = [
                {
                    "pos_a": list(it.world_pos_a),
                    "pos_b": list(it.world_pos_b),
                }
                for it in reported[:20]  # sample up to 20 coordinates
            ]
            diverging_coords = [
                {
                    "pos_a": list(it.world_pos_a),
                    "pos_b": list(it.world_pos_b),
                }
                for it in diverging[:20]
            ]

            aggregated.append(
                {
                    "category": cat,
                    "axis": axis,
                    "chunk_a": list(ca),
                    "chunk_b": list(cb),
                    "border": f"({ca[0]},{ca[1]}) | ({cb[0]},{cb[1]})",
                    "block_a": ba,
                    "block_b": bb,
                    "block_pair": f"{ba} <-> {bb}",
                    "count": count,
                    "world_coord": sample_coord,
                    "y_min": y_min,
                    "y_max": y_max,
                    "exists_in_vanilla": exists_in_vanilla,
                    "vanilla_match_count": vanilla_match_count,
                    "coordinates": coords_detail,
                    "diverging_count": len(diverging),
                    "diverging_y_min": min((it.world_pos_a[1] for it in diverging), default=None),
                    "diverging_y_max": max((it.world_pos_a[1] for it in diverging), default=None),
                    "diverging_coordinates": diverging_coords,
                }
            )

        # Deterministic Ranking
        # Priority order:
        #   1. Suspicion tier:
        #      Tier 0: Target category (kelp/sulfur) AND NOT in vanilla
        #      Tier 1: Other category AND NOT in vanilla
        #      Tier 2: Target category AND in vanilla
        #      Tier 3: Other category AND in vanilla
        #   2. Count descending
        #   3. Category name
        #   4. Chunk coords, axis, block names, y_min
        def sort_key(item):
            cat = item["category"]
            in_v = item["exists_in_vanilla"]
            is_target = cat in (
                CATEGORY_KELP_SEAGRASS_VS_WATER_AIR,
                CATEGORY_SULFUR_VS_DEEPSLATE,
            )
            if is_target and not in_v:
                tier = 0
            elif not is_target and not in_v:
                tier = 1
            elif is_target and in_v:
                tier = 2
            else:
                tier = 3

            return (
                tier,
                -item["count"],
                item["category"],
                item["chunk_a"][0],
                item["chunk_a"][1],
                item["chunk_b"][0],
                item["chunk_b"][1],
                item["axis"],
                item["block_a"],
                item["block_b"],
                item["y_min"],
            )

        aggregated.sort(key=sort_key)

        # Assign 1-based ranks
        for idx, item in enumerate(aggregated, 1):
            item["rank"] = idx

        return aggregated

    def analyze_paths(
        self,
        kestrel_path: str,
        vanilla_path: Optional[str] = None,
        category_filter: Optional[str] = None,
        min_count: int = 1,
    ) -> List[Dict[str, Any]]:
        """Load datasets from disk and perform analysis."""
        kestrel_chunks = load_chunk_dataset(kestrel_path)
        vanilla_chunks = load_chunk_dataset(vanilla_path) if vanilla_path else None
        return self.analyze(
            kestrel_chunks,
            vanilla_chunks,
            category_filter=category_filter,
            min_count=min_count,
        )

    def to_json(self, results: List[Dict[str, Any]]) -> str:
        """Format results as JSON."""
        return json.dumps(results, indent=2)

    def to_table(self, results: List[Dict[str, Any]]) -> str:
        """Format results as human-readable aligned table."""
        if not results:
            return "No discontinuities detected across chunk borders."

        headers = [
            ("RANK", 5),
            ("CATEGORY", 29),
            ("CHUNK BORDER", 17),
            ("AXIS", 4),
            ("BLOCK PAIR", 45),
            ("COUNT", 6),
            ("SAMPLE COORD", 18),
            ("IN VANILLA?", 11),
        ]
        header_str = " ".join(f"{name:<{w}}" for name, w in headers)
        divider = " ".join("-" * w for _, w in headers)

        lines = [header_str, divider]
        for r in results:
            coord_str = f"({r['world_coord'][0]},{r['world_coord'][1]},{r['world_coord'][2]})"
            pair_str = r["block_pair"]
            if len(pair_str) > 45:
                pair_str = pair_str[:42] + "..."
            in_v_str = "YES" if r["exists_in_vanilla"] else "NO"

            row = (
                f"{r['rank']:<5} "
                f"{r['category']:<29} "
                f"{r['border']:<17} "
                f"{r['axis']:<4} "
                f"{pair_str:<45} "
                f"{r['count']:<6} "
                f"{coord_str:<18} "
                f"{in_v_str:<11}"
            )
            lines.append(row)

        summary_line = (
            f"\nTotal discontinuities identified: {len(results)} "
            f"| Target categories: {sum(1 for r in results if r['category'] in (CATEGORY_KELP_SEAGRASS_VS_WATER_AIR, CATEGORY_SULFUR_VS_DEEPSLATE))} "
            f"| Discrepancies vs Vanilla: {sum(1 for r in results if not r['exists_in_vanilla'])}"
        )
        lines.append(summary_line)
        return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(
        description="Deterministic chunk-border seam analyzer for Kestrel vs Vanilla parity."
    )
    parser.add_argument(
        "--kestrel",
        "-k",
        required=True,
        help="Path to Kestrel world directory, .mca region file, or JSON chunk dump.",
    )
    parser.add_argument(
        "--vanilla",
        "-v",
        default=None,
        help="Optional path to Vanilla world directory, .mca region file, or JSON chunk dump.",
    )
    parser.add_argument(
        "--category",
        "-c",
        default="all",
        choices=["all", CATEGORY_KELP_SEAGRASS_VS_WATER_AIR, CATEGORY_SULFUR_VS_DEEPSLATE, CATEGORY_OTHER],
        help="Filter results by category.",
    )
    parser.add_argument(
        "--min-count",
        "-m",
        type=int,
        default=1,
        help="Minimum block pair count threshold.",
    )
    parser.add_argument(
        "--format",
        "-f",
        default="table",
        choices=["table", "json"],
        help="Output format (default: table).",
    )
    parser.add_argument(
        "--output",
        "-o",
        default=None,
        help="Optional file path to write results to.",
    )

    args = parser.parse_args()

    analyzer = ChunkBorderSeamAnalyzer()
    try:
        results = analyzer.analyze_paths(
            kestrel_path=args.kestrel,
            vanilla_path=args.vanilla,
            category_filter=args.category,
            min_count=args.min_count,
        )
    except Exception as e:
        sys.stderr.write(f"Error during seam analysis: {e}\n")
        return 1

    if args.format == "json":
        output_text = analyzer.to_json(results)
    else:
        output_text = analyzer.to_table(results)

    if args.output:
        out_path = Path(args.output).resolve()
        out_path.parent.mkdir(parents=True, exist_ok=True)
        with open(out_path, "w", encoding="utf-8") as f:
            f.write(output_text + "\n")
        print(f"Results successfully written to {out_path}")
    else:
        print(output_text)

    return 0


if __name__ == "__main__":
    sys.exit(main())
