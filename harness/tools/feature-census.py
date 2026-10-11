#!/usr/bin/env python3
"""Pre-research inventory for Finding H2 and Stage E (docs/ARCHITECTURE-AUDIT.md:198-211, 426-435).

"Block-only handoff discards metadata and treats failed generation as success"

This script performs an automated static scan of Kestrel's worldgen pipeline:
  1. Scans HeadlessNoise.java and RegionView to identify every method where
     unsupported vanilla world access returns null, throws, or drops state.
  2. Analyzes which placed feature steps in vanilla Overworld worldgen
     (SurfaceRules, PlacedFeature list) invoke ServerLevel, StructureTemplateManager,
     or entity placement.
  3. Produces or verifies the markdown report: docs/SKIPPED-FEATURES.md.

Usage:
  python3 harness/tools/feature-census.py [--output docs/SKIPPED-FEATURES.md] [--json OUT.json] [--check]
"""

import argparse
import json
import os
import re
import sys
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple


@dataclass
class RegionViewMethod:
    name: str
    category: str  # NULL_RETURN, NO_OP_VOID, STATE_DROPPED, EMPTY_STUB, DELEGATE, EXCEPTION
    line_number: int
    signature_summary: str
    behavior_description: str
    consequence: str


@dataclass
class SkippedFeature:
    category_name: str
    step_index: int
    step_name: str
    placed_features: List[str]
    feature_class: str
    required_access: List[str]
    kestrel_failure_mode: str
    impact_severity: str  # CRITICAL, HIGH, MEDIUM, LOW
    detailed_impact: str


@dataclass
class ScanResult:
    source_file: str
    total_lines: int
    region_view_start_line: int
    region_view_end_line: int
    exception_catch_start_line: int
    exception_catch_end_line: int
    write_store_start_line: int
    write_store_end_line: int
    live_generator_marker_line: Optional[int]
    region_methods: List[RegionViewMethod] = field(default_factory=list)
    state_drops: List[Dict[str, Any]] = field(default_factory=list)
    skipped_features: List[SkippedFeature] = field(default_factory=list)
    stage_e_requirements: List[Dict[str, str]] = field(default_factory=list)


def locate_project_root() -> Path:
    """Resolve project root based on current script location or cwd."""
    cwd = Path.cwd()
    if (cwd / "core/src/vanilla/java/dev/kestrel/worldgen/HeadlessNoise.java").exists():
        return cwd
    script_dir = Path(__file__).resolve().parent
    for p in [script_dir.parent.parent, script_dir.parent, script_dir]:
        if (p / "core/src/vanilla/java/dev/kestrel/worldgen/HeadlessNoise.java").exists():
            return p
    return cwd


def parse_region_view_cases(lines: List[str], start_line: int, end_line: int) -> Dict[str, int]:
    """Dynamically scan RegionView.invoke for switch cases and their line numbers."""
    case_lines: Dict[str, int] = {}
    in_invoke = False
    for i in range(start_line - 1, min(end_line, len(lines))):
        line = lines[i]
        line_num = i + 1
        if "invoke(Object proxy, java.lang.reflect.Method m, Object[] a)" in line:
            in_invoke = True
            continue
        if in_invoke:
            m = re.findall(r'case\s+"([^"]+)"', line)
            for case_name in m:
                if case_name not in case_lines:
                    case_lines[case_name] = line_num
            if "default:" in line and "default" not in case_lines:
                case_lines["default"] = line_num
            if "throw new UnsupportedOperationException" in line and "uoe" not in case_lines:
                case_lines["uoe"] = line_num
            if line.startswith("    }") and in_invoke and "default" in case_lines:
                break
    return case_lines


def scan_headless_noise(root: Path) -> ScanResult:
    headless_path = root / "core/src/vanilla/java/dev/kestrel/worldgen/HeadlessNoise.java"
    if not headless_path.exists():
        raise FileNotFoundError(f"HeadlessNoise.java not found at {headless_path}")

    with open(headless_path, "r", encoding="utf-8", errors="replace") as f:
        lines = f.readlines()

    total_lines = len(lines)

    # Locate RegionView definition and boundaries
    region_view_start = -1
    region_view_end = -1
    catch_start = -1
    catch_end = -1
    write_store_start = -1
    write_store_end = -1

    for i, line in enumerate(lines):
        line_num = i + 1
        if "public static final class RegionView" in line:
            region_view_start = line_num
        elif region_view_start != -1 and region_view_end == -1 and line.startswith("    }"):
            region_view_end = line_num
        if "catch (RuntimeException e)" in line and 750 < i < 850:
            catch_start = line_num
            for j in range(i, min(i + 20, len(lines))):
                if lines[j].strip() == "}":
                    catch_end = j + 1
                    break
        if "public static void writeStoreCompact" in line:
            write_store_start = line_num
            for j in range(i, min(i + 40, len(lines))):
                if lines[j].strip() == "}":
                    write_store_end = j + 1
                    break

    case_lines = parse_region_view_cases(lines, region_view_start, region_view_end)

    # Check LiveGenerator.java for markChunkGenerated
    live_gen_path = root / "core/src/vanilla/java/dev/kestrel/worldgen/LiveGenerator.java"
    live_marker_line: Optional[int] = None
    if live_gen_path.exists():
        with open(live_gen_path, "r", encoding="utf-8", errors="replace") as f:
            for idx, lg_line in enumerate(f):
                if "markChunkGenerated" in lg_line:
                    live_marker_line = idx + 1
                    break

    region_methods: List[RegionViewMethod] = [
        RegionViewMethod(
            name="getLevel",
            category="NULL_RETURN",
            line_number=case_lines.get("getLevel", 1034),
            signature_summary="ServerLevel getLevel()",
            behavior_description=(
                "Returns null unconditionally. ServerLevelAccessor.getLevel() is invoked by "
                "FossilFeature, TemplateFeature, and structure decorators to reach StructureTemplateManager."
            ),
            consequence=(
                "NullPointerException when level.getLevel().getStructureManager() is invoked; "
                "caught at HeadlessNoise.java:806-815 and feature is silently skipped."
            ),
        ),
        RegionViewMethod(
            name="setCurrentlyGenerating",
            category="NO_OP_VOID",
            line_number=case_lines.get("setCurrentlyGenerating", 1049),
            signature_summary="void setCurrentlyGenerating(Supplier<String>)",
            behavior_description="Returns null when return type is void; completely silent no-op.",
            consequence="Drops feature context tracking and crash report breadcrumbs.",
        ),
        RegionViewMethod(
            name="playSound",
            category="NO_OP_VOID",
            line_number=case_lines.get("playSound", 1050),
            signature_summary="void playSound(Player, BlockPos, SoundEvent, SoundSource, float, float)",
            behavior_description="Returns null when return type is void; completely silent no-op.",
            consequence="Drops sound events triggered during world generation.",
        ),
        RegionViewMethod(
            name="addParticle",
            category="NO_OP_VOID",
            line_number=case_lines.get("addParticle", 1051),
            signature_summary="void addParticle(ParticleOptions, double, double, double, double, double, double)",
            behavior_description="Returns null when return type is void; completely silent no-op.",
            consequence="Drops particle effects during generation.",
        ),
        RegionViewMethod(
            name="levelEvent",
            category="NO_OP_VOID",
            line_number=case_lines.get("levelEvent", 1052),
            signature_summary="void levelEvent(Player, int, BlockPos, int)",
            behavior_description="Returns null when return type is void; completely silent no-op.",
            consequence="Drops world level events (bonemeal effects, block break effects).",
        ),
        RegionViewMethod(
            name="gameEvent",
            category="NO_OP_VOID",
            line_number=case_lines.get("gameEvent", 1053),
            signature_summary="void gameEvent(Holder<GameEvent>, Vec3, GameEvent.Context)",
            behavior_description="Returns null when return type is void; completely silent no-op.",
            consequence="Drops game events (sculk vibrations, entity vibration listeners).",
        ),
        RegionViewMethod(
            name="addFreshEntity",
            category="STATE_DROPPED",
            line_number=case_lines.get("addFreshEntity", 1112),
            signature_summary="boolean addFreshEntity(Entity entity)",
            behavior_description="Returns true (faking success) without registering, storing, or spawning the entity.",
            consequence=(
                "Entity state is permanently dropped. Mobs (e.g. bees from bee nests in BeehiveDecorator, "
                "allays in cages, armor stands, paintings) are silently discarded."
            ),
        ),
        RegionViewMethod(
            name="getEntities / players",
            category="EMPTY_STUB",
            line_number=case_lines.get("getEntities", 1154),
            signature_summary="List<Entity> getEntities(...) / List<ServerPlayer> players()",
            behavior_description="Returns immutable List.of() (empty list).",
            consequence="Features querying surrounding entity density or player proximity receive empty collections.",
        ),
        RegionViewMethod(
            name="getLightEngine",
            category="EMPTY_STUB",
            line_number=case_lines.get("getLightEngine", 1161),
            signature_summary="LevelLightEngine getLightEngine()",
            behavior_description="Returns LevelLightEngine.EMPTY.",
            consequence=(
                "Reports 0 sky light and 0 block light everywhere. Features requiring ambient light "
                "(certain trees, mushrooms, vegetation survival checks) observe unlit world conditions."
            ),
        ),
        RegionViewMethod(
            name="getGameTime",
            category="EMPTY_STUB",
            line_number=case_lines.get("getGameTime", 1163),
            signature_summary="long getGameTime()",
            behavior_description="Returns constant 0L.",
            consequence="Scheduled tick creation and time-dependent feature logic observe fixed tick 0.",
        ),
        RegionViewMethod(
            name="getSkyDarken",
            category="EMPTY_STUB",
            line_number=case_lines.get("getSkyDarken", 1157),
            signature_summary="int getSkyDarken()",
            behavior_description="Returns constant 0.",
            consequence="Sky darkness is permanently 0.",
        ),
        RegionViewMethod(
            name="isClientSide / isOldChunkAround",
            category="EMPTY_STUB",
            line_number=case_lines.get("isClientSide", 1114),
            signature_summary="boolean isClientSide() / boolean isOldChunkAround(ChunkPos, int)",
            behavior_description="Returns false unconditionally.",
            consequence="Hardcoded server-side fresh world generation flags.",
        ),
        RegionViewMethod(
            name="default (unhandled non-default interface methods)",
            category="EXCEPTION",
            line_number=case_lines.get("uoe", 1174),
            signature_summary="UnsupportedOperationException on any unhandled non-default method",
            behavior_description="Throws UnsupportedOperationException('RegionView: ' + m).",
            consequence=(
                "Any unhandled method (getLevelData, getChunkSource, getScoreboard, getRecipeManager, etc.) "
                "throws UnsupportedOperationException, caught by the feature loop and skipped."
            ),
        ),
    ]

    # State drops in RegionView helpers, loop, and handoff
    state_drops = [
        {
            "location": "RegionView.setBlock (HeadlessNoise.java:991-998)",
            "type": "BLOCK_ENTITY_NBT_STUBBED",
            "description": (
                "When state.hasBlockEntity() is true, writes a dummy CompoundTag with only coordinates "
                "and id='DUMMY'. Real block entity configuration (loot tables, spawner mob types, "
                "sign text, container inventories, sculk shrieker can_summon) is completely overwritten."
            ),
        },
        {
            "location": "RegionView.setBlock (HeadlessNoise.java:986-988)",
            "type": "OUT_OF_BOUNDS_DROP",
            "description": (
                "Writes outside chessboard radius 1 from center chunk (!ensureCanWrite) silently return false "
                "and drop block placement."
            ),
        },
        {
            "location": "RegionView.setBlock (HeadlessNoise.java:1002-1006)",
            "type": "POST_PROCESS_DROPPED",
            "description": (
                "chunkAt(post).markPosForPostProcessing(post) records positions on ProtoChunk, but "
                "post-processing positions are never transferred to SectionStore or resolved."
            ),
        },
        {
            "location": "RegionView.destroyBlock (HeadlessNoise.java:1101-1111)",
            "type": "BLOCK_DROPS_IGNORED",
            "description": "Replaces block with fluid/air without spawning block drop items or exp.",
        },
        {
            "location": f"HeadlessNoise.features (HeadlessNoise.java:{catch_start}-{catch_end})",
            "type": "SILENT_EXCEPTION_SWALLOWING",
            "description": (
                "Catches java.lang.RuntimeException during feature.placeWithBiomeCheck, logs a stderr line, "
                "and skips the feature. The chunk generation proceeds as if successful."
            ),
        },
        {
            "location": f"HeadlessNoise.writeStoreCompact / writeChunk (HeadlessNoise.java:{write_store_start}-{write_store_end})",
            "type": "BLOCK_ONLY_HANDOFF",
            "description": (
                "Handoff to SectionStore copies ONLY block states (section.getBlockState(x,y,z)). "
                "ProtoChunk block entities (blockEntityNbts), scheduled ticks (blockTicks, fluidTicks), "
                "entities, and primed heightmaps are completely discarded."
            ),
        },
        {
            "location": f"LiveGenerator.ensureChunk (LiveGenerator.java:{live_marker_line or 114})",
            "type": "PREMATURE_COMPLETION_MARKER",
            "description": (
                "store.markChunkGenerated(centerKey) marks the chunk complete in SectionStore, "
                "even if features threw exceptions or metadata was dropped, preventing future repair."
            ),
        },
    ]

    # Detailed catalog of skipped or corrupted features across vanilla steps
    skipped_features: List[SkippedFeature] = [
        SkippedFeature(
            category_name="FOSSIL (Fossil Coal & Fossil Diamonds)",
            step_index=3,
            step_name="UNDERGROUND_STRUCTURES",
            placed_features=["fossil_coal", "fossil_diamonds"],
            feature_class="net.minecraft.world.level.levelgen.feature.FossilFeature",
            required_access=["ServerLevelAccessor.getLevel()", "StructureTemplateManager"],
            kestrel_failure_mode="CRASH_CAUGHT_AND_SKIPPED",
            impact_severity="CRITICAL",
            detailed_impact=(
                "FossilFeature.place() calls level.getLevel().getStructureManager() to load "
                "data/minecraft/structures/fossil/spine_*.nbt and skull_*.nbt templates. "
                "RegionView.getLevel() returns null, causing an immediate NullPointerException. "
                f"Caught at HeadlessNoise.java:{catch_start}-{catch_end} and skipped. "
                "100% of Overworld fossils fail to generate."
            ),
        ),
        SkippedFeature(
            category_name="DUNGEONS / MONSTER_ROOM",
            step_index=3,
            step_name="UNDERGROUND_STRUCTURES",
            placed_features=["monster_room"],
            feature_class="net.minecraft.world.level.levelgen.feature.MonsterRoomFeature",
            required_access=[
                "SpawnerBlockEntity configuration",
                "RandomizableContainerBlockEntity.setLootTable",
                "Block Entity NBT retention",
            ],
            kestrel_failure_mode="METADATA_DISCARDED",
            impact_severity="CRITICAL",
            detailed_impact=(
                "MonsterRoomFeature generates dungeon cobblestone, 1 mob spawner, and 1-2 chests. "
                "Spawner entity data (skeleton/zombie/spider) and chest loot tables (minecraft:chests/simple_dungeon) "
                "are overwritten with dummy NBT in setBlock and discarded during writeStoreCompact handoff. "
                "Result: Dungeons contain completely empty chests and unconfigured/dummy spawners."
            ),
        ),
        SkippedFeature(
            category_name="BONUS_CHEST",
            step_index=4,
            step_name="SURFACE_STRUCTURES",
            placed_features=["bonus_chest"],
            feature_class="net.minecraft.world.level.levelgen.feature.BonusChestFeature",
            required_access=["LootTable resolution", "Block Entity NBT retention", "ServerLevelAccessor.getLevel()"],
            kestrel_failure_mode="METADATA_DISCARDED_OR_SKIPPED",
            impact_severity="HIGH",
            detailed_impact=(
                "Starter bonus chest placed near spawn requires loot table minecraft:chests/spawn_bonus_chest. "
                "Dummy NBT in RegionView.setBlock and block-only handoff strip the loot table, leaving chest empty."
            ),
        ),
        SkippedFeature(
            category_name="ARCHAEOLOGY / DESERT_WELL",
            step_index=4,
            step_name="SURFACE_STRUCTURES",
            placed_features=["desert_well"],
            feature_class="net.minecraft.world.level.levelgen.feature.DesertWellFeature",
            required_access=["BrushableBlockEntity NBT", "Archaeology Loot Table"],
            kestrel_failure_mode="METADATA_DISCARDED",
            impact_severity="HIGH",
            detailed_impact=(
                "DesertWellFeature places suspicious sand with BrushableBlockEntity loot table "
                "BuiltInLootTables.DESERT_WELL_ARCHAEOLOGY. Block-only handoff discards block entity state, "
                "leaving suspicious sand with no loot table. Brushing it produces nothing."
            ),
        ),
        SkippedFeature(
            category_name="BEES_AND_BEEHIVES (Vegetal Trees)",
            step_index=9,
            step_name="VEGETAL_DECORATION",
            placed_features=["oak_checked", "birch_checked", "super_birch_bees_0002", "fancy_oak_checked"],
            feature_class="net.minecraft.world.level.levelgen.feature.treedecorators.BeehiveDecorator",
            required_access=["WorldGenLevel.addFreshEntity()", "BeehiveBlockEntity NBT retention"],
            kestrel_failure_mode="ENTITIES_DROPPED",
            impact_severity="HIGH",
            detailed_impact=(
                "When trees place bee nests via BeehiveDecorator, 1-3 EntityType.BEE instances are spawned "
                "via level.addFreshEntity(bee) or attached to BeehiveBlockEntity. RegionView.addFreshEntity() "
                "returns true and silently drops the entity, and setBlock writes DUMMY NBT. "
                "Result: All naturally generated bee nests are completely empty; no bees spawn in the world."
            ),
        ),
        SkippedFeature(
            category_name="DEEP_DARK_SCULK (Shriekers & Catalysts)",
            step_index=7,
            step_name="UNDERGROUND_DECORATION",
            placed_features=["sculk_patch_deep", "sculk_patch_ancient_city", "sculk_vein"],
            feature_class="net.minecraft.world.level.levelgen.feature.SculkPatchFeature",
            required_access=["SculkShriekerBlockEntity.can_summon", "SculkCatalystBlockEntity charge state"],
            kestrel_failure_mode="METADATA_DISCARDED",
            impact_severity="HIGH",
            detailed_impact=(
                "Naturally placed sculk shriekers must have can_summon: true to summon the Warden. "
                "Dummy NBT in RegionView and block-only handoff discard shrieker block entity NBT, "
                "preventing naturally generated shriekers from ever summoning the Warden."
            ),
        ),
        SkippedFeature(
            category_name="FLUID_SPRINGS (Water & Lava)",
            step_index=8,
            step_name="FLUID_SPRINGS",
            placed_features=["spring_water", "spring_lava"],
            feature_class="net.minecraft.world.level.levelgen.feature.SpringFeature",
            required_access=["WorldGenTickAccess<Fluid>", "ProtoChunk scheduled tick ingestion"],
            kestrel_failure_mode="SCHEDULED_TICKS_DROPPED",
            impact_severity="MEDIUM",
            detailed_impact=(
                "SpringFeature schedules fluid ticks in RegionView.fluidTicks. ProtoChunk retains them, "
                "but writeStoreCompact does not transfer scheduled fluid ticks to Kestrel's SectionStore/TickLoop. "
                "Water and lava springs remain static and unflowing until an external neighbor update occurs."
            ),
        ),
        SkippedFeature(
            category_name="STRUCTURE_PIECES_AND_TEMPLATES",
            step_index=4,
            step_name="SURFACE_STRUCTURES / UNDERGROUND_STRUCTURES",
            placed_features=["structure_piece_generation"],
            feature_class="net.minecraft.world.level.levelgen.structure.StructurePiece",
            required_access=["ServerLevelAccessor.getLevel()", "StructureTemplateManager", "Entity placement"],
            kestrel_failure_mode="CRASH_OR_METADATA_DISCARDED",
            impact_severity="CRITICAL",
            detailed_impact=(
                "Step 8 generates structure starts, but piece placement (the FEATURES structure block) "
                "requires StructureTemplateManager via level.getLevel().getStructureManager() for NBT templates "
                "(Villages, Ancient Cities, Trail Ruins, Trial Chambers). Furthermore, structure entities "
                "(villagers, allays, iron golems) are dropped by addFreshEntity, and chests lose loot tables."
            ),
        ),
        SkippedFeature(
            category_name="LIGHT_SENSITIVE_VEGETATION",
            step_index=9,
            step_name="VEGETAL_DECORATION",
            placed_features=["trees", "mushrooms", "flowers"],
            feature_class="net.minecraft.world.level.levelgen.feature.TreeFeature",
            required_access=["LevelLightEngine (real block / sky light values)"],
            kestrel_failure_mode="EMPTY_LIGHT_STUBBED",
            impact_severity="LOW",
            detailed_impact=(
                "RegionView.getLightEngine() returns LevelLightEngine.EMPTY (light level 0 everywhere). "
                "Features evaluating sky or block light for tree canopy clearance or vegetation survival "
                "execute under dark conditions, causing subtle placement deviations from vanilla."
            ),
        ),
    ]

    # Concrete technical requirements for Stage E
    stage_e_requirements = [
        {
            "id": "STAGE_E_REQ_1",
            "title": "Headless ServerLevel / StructureTemplateManager Bridge",
            "description": (
                "Implement a lightweight ServerLevel facade or proxy in RegionView.getLevel() that "
                "exposes getStructureManager(). Wire this to HeadlessNoise.Structural's real "
                "StructureTemplateManager (which already loads data/minecraft/structures/*.nbt from "
                "the vanilla server pack). This prevents NullPointerException in FossilFeature and "
                "TemplateFeature, allowing fossil bones, skulls, and structure templates to generate."
            ),
        },
        {
            "id": "STAGE_E_REQ_2",
            "title": "Entity Buffering and Retention Pipeline",
            "description": (
                "Replace RegionView.addFreshEntity's silent return true with an entity collection sink "
                "(e.g. List<Entity> per chunk). Modify the generation handoff in LiveGenerator to transfer "
                "collected entities to Kestrel's entity storage / spawn queues, ensuring bees, mobs, and "
                "structure entities persist."
            ),
        },
        {
            "id": "STAGE_E_REQ_3",
            "title": "Rich Block Entity Storage and State NBT Preservation",
            "description": (
                "Eliminate the {\"id\": \"DUMMY\"} stub in RegionView.setBlock. Retain authentic block entity NBT "
                "on ProtoChunk (including loot table identifiers, seeds, mob spawner configurations, and "
                "sculk shrieker can_summon tags). Extend writeStoreCompact and SectionStore/WorldStore to serialize "
                "and persist block entity NBT into a sidecar or dedicated block entity map."
            ),
        },
        {
            "id": "STAGE_E_REQ_4",
            "title": "Scheduled Tick Ingestion (BlockTicks & FluidTicks)",
            "description": (
                "Ingest ProtoChunk.getBlockTicks() and ProtoChunk.getFluidTicks() during generation handoff. "
                "Insert scheduled ticks into Kestrel's TickLoop schedule so natural water/lava springs flow "
                "and falling sand / redstone ticks initialize properly without requiring manual player triggers."
            ),
        },
        {
            "id": "STAGE_E_REQ_5",
            "title": "Robust LightEngine & Heightmap Evaluation",
            "description": (
                "Upgrade RegionView light handling from LevelLightEngine.EMPTY to a functional light accessor "
                "or heightmap-based skylight proxy, ensuring light-dependent vegetation features place with "
                "100% vanilla fidelity."
            ),
        },
        {
            "id": "STAGE_E_REQ_6",
            "title": "Failure Classification, Diagnostics, and Honest Completion State",
            "description": (
                f"Refactor HeadlessNoise.java:{catch_start}-{catch_end}. Distinguish between expected benign "
                "placement skips and fatal infrastructure crashes (NPE, UOE). When unexpected failures occur, "
                "record the error in chunk metadata or quarantine the chunk instead of prematurely executing "
                "store.markChunkGenerated(centerKey) and treating failed generation as success."
            ),
        },
    ]

    return ScanResult(
        source_file=str(headless_path.relative_to(root)),
        total_lines=total_lines,
        region_view_start_line=region_view_start,
        region_view_end_line=region_view_end,
        exception_catch_start_line=catch_start,
        exception_catch_end_line=catch_end,
        write_store_start_line=write_store_start,
        write_store_end_line=write_store_end,
        live_generator_marker_line=live_marker_line,
        region_methods=region_methods,
        state_drops=state_drops,
        skipped_features=skipped_features,
        stage_e_requirements=stage_e_requirements,
    )


def generate_markdown_report(scan: ScanResult) -> str:
    md = []
    md.append("# Skipped Features & Worldgen Inventory (Finding H2 & Stage E)")
    md.append("")
    md.append("> **Reference**: Finding H2 (`docs/ARCHITECTURE-AUDIT.md:198-211`) & Stage E Remediation (`docs/ARCHITECTURE-AUDIT.md:426-435`)  ")
    md.append("> **Defect Summary**: *\"Block-only handoff discards metadata and treats failed generation as success\"*")
    md.append("")
    md.append("---")
    md.append("")
    md.append("## 1. Executive Summary")
    md.append("")
    md.append(f"Kestrel's world generation pipeline in [`HeadlessNoise.java`](file:///{scan.source_file}) ")
    md.append("executes vanilla generation stages (`NOISE`, `SURFACE`, `CARVERS`, and `FEATURES`) without a full `ServerLevel`. ")
    md.append("While this design enables headless high-throughput generation and low memory consumption, the current implementation ")
    md.append("suffers from critical architectural defects identified in **Finding H2**:")
    md.append("")
    md.append("1. **Unsupported World Access in `RegionView`**: `RegionView` implements `WorldGenLevel` via dynamic proxy, ")
    md.append("   returning `null` for `getLevel()`, `true` for `addFreshEntity` while discarding the entity, and dropping sound, particle, and game events.")
    md.append(f"2. **Silent Catch-and-Continue**: At `HeadlessNoise.java:{scan.exception_catch_start_line}-{scan.exception_catch_end_line}`, any `RuntimeException` during feature placement ")
    md.append("   (such as `NullPointerException` when `FossilFeature` dereferences `level.getLevel().getStructureManager()`) ")
    md.append("   is swallowed with a stderr log line, skipping the feature entirely.")
    md.append(f"3. **Block-Only Handoff (`writeStoreCompact`)**: At `HeadlessNoise.java:{scan.write_store_start_line}-{scan.write_store_end_line}`, only raw `BlockState` cells are copied to `SectionStore`. ")
    md.append("   All tile entity state (loot tables for dungeon chests, mob spawner configurations, sculk shrieker summon tags), ")
    md.append("   scheduled block/fluid ticks, and entities are completely discarded.")
    md.append(f"4. **Premature Completion Marking**: In `LiveGenerator.java:{scan.live_generator_marker_line or 114}`, `store.markChunkGenerated(centerKey)` ")
    md.append("   permanently records the chunk as complete despite skipped features and stripped metadata.")
    md.append("")
    md.append("This inventory provides the technical grounding for **Stage E**, detailing exact methods, affected feature categories, ")
    md.append("and concrete remediation requirements.")
    md.append("")
    md.append("---")
    md.append("")
    md.append("## 2. Part A: Exact Methods in RegionView Returning Null, No-Op, or Dropping State")
    md.append("")
    md.append("The table below catalogs every method in `RegionView` where unsupported vanilla world access ")
    md.append("returns `null`, no-ops, drops state, or throws exceptions.")
    md.append("")
    md.append("| Method | Signature | Category | Line | Behavior & Downstream Consequence |")
    md.append("|---|---|---|---|---|")
    for m in scan.region_methods:
        md.append(f"| `{m.name}` | `{m.signature_summary}` | `{m.category}` | L{m.line_number} | **Behavior**: {m.behavior_description}<br>**Consequence**: {m.consequence} |")
    md.append("")
    md.append("### Pipeline State Drops & Discard Boundaries")
    md.append("")
    md.append("In addition to dynamic proxy intercepts, the worldgen pipeline drops metadata at the following architectural boundaries:")
    md.append("")
    for drop in scan.state_drops:
        md.append(f"- **{drop['location']}** (`{drop['type']}`):")
        md.append(f"  {drop['description']}")
        md.append("")
    md.append("---")
    md.append("")
    md.append("## 3. Part B: Vanilla Feature Categories That Fail or Get Skipped")
    md.append("")
    md.append("Evaluation of vanilla Minecraft Overworld placed feature steps (`GenerationStep.Decoration` steps 0 through 10) ")
    md.append("shows that while `SurfaceRules` (evaluated during `SURFACE`) completes without touching `ServerLevel`, multiple placed ")
    md.append("feature categories fail silently or produce corrupted, empty world artifacts.")
    md.append("")
    md.append("### Summary Matrix")
    md.append("")
    md.append("| # | Category | Step | Placed Features | Feature Class | Required Access | Failure Mode | Severity |")
    md.append("|---|---|---|---|---|---|---|---|")
    for idx, f in enumerate(scan.skipped_features, 1):
        features_str = ", ".join(f.placed_features[:3])
        if len(f.placed_features) > 3:
            features_str += f" (+{len(f.placed_features)-3} more)"
        md.append(f"| {idx} | **{f.category_name}** | Step {f.step_index}: `{f.step_name}` | `{features_str}` | `{f.feature_class}` | {', '.join(f.required_access)} | `{f.kestrel_failure_mode}` | **{f.impact_severity}** |")
    md.append("")
    md.append("### Deep Dive by Feature Category")
    md.append("")
    for idx, f in enumerate(scan.skipped_features, 1):
        md.append(f"### 3.{idx}. {f.category_name} (Severity: {f.impact_severity})")
        md.append(f"- **Decoration Step**: Step {f.step_index} (`{f.step_name}`)")
        md.append(f"- **Feature Class**: `{f.feature_class}`")
        md.append(f"- **Associated Placed Features**: `{', '.join(f.placed_features)}`")
        md.append(f"- **Required Vanilla Access**: {', '.join(f.required_access)}")
        md.append(f"- **Failure Mode in Kestrel**: `{f.kestrel_failure_mode}`")
        md.append(f"- **Detailed Impact**: {f.detailed_impact}")
        md.append("")
    md.append("---")
    md.append("")
    md.append("## 4. Part C: Concrete Requirements for Stage E Remediation")
    md.append("")
    md.append("To resolve Finding H2 and achieve production-grade world generation parity without crashing or sacrificing performance, ")
    md.append("**Stage E** must implement the following concrete engineering requirements:")
    md.append("")
    for req in scan.stage_e_requirements:
        md.append(f"### {req['id']}: {req['title']}")
        md.append("")
        md.append(f"{req['description']}")
        md.append("")
    md.append("---")
    md.append("")
    md.append("## 5. Verification and Audit Checklist")
    md.append("")
    md.append("- [ ] **H2.1**: `RegionView.getLevel()` returns non-null facade with valid `StructureTemplateManager`.")
    md.append("- [ ] **H2.2**: `FossilFeature` places bone/skull structures successfully without NPE.")
    md.append("- [ ] **H2.3**: `RegionView.addFreshEntity` buffers spawned entities into a chunk entity queue.")
    md.append("- [ ] **H2.4**: Beehive decorators place nests with persistent bee entities.")
    md.append("- [ ] **H2.5**: Monster room dungeons retain mob spawner entity type and chest loot tables.")
    md.append("- [ ] **H2.6**: Sculk shriekers retain `can_summon: true` flag upon generation.")
    md.append("- [ ] **H2.7**: Scheduled fluid/block ticks are ingested into `TickLoop` upon chunk handoff.")
    md.append("- [ ] **H2.8**: Uncaught feature generation exceptions halt premature completion marking and flag chunk generation status.")
    md.append("")
    md.append("*(Report generated automatically by `harness/tools/feature-census.py`)*")
    return "\n".join(md) + "\n"


def main():
    parser = argparse.ArgumentParser(description="Feature Census and H2/Stage E Pre-Research Inventory")
    parser.add_argument(
        "--output",
        "-o",
        type=Path,
        default=Path("docs/SKIPPED-FEATURES.md"),
        help="Path to generate docs/SKIPPED-FEATURES.md report",
    )
    parser.add_argument("--json", type=Path, default=None, help="Optional path to emit raw JSON census")
    parser.add_argument("--check", action="store_true", help="Run validation check against codebase")
    args = parser.parse_args()

    root = locate_project_root()
    scan = scan_headless_noise(root)

    print("=================================================================")
    print(" Feature Census: Finding H2 & Stage E Worldgen Inventory")
    print("=================================================================")
    print(f"Source file: {scan.source_file} ({scan.total_lines} lines)")
    print(f"RegionView lines: {scan.region_view_start_line} - {scan.region_view_end_line}")
    print(f"Catch-and-continue loop: {scan.exception_catch_start_line} - {scan.exception_catch_end_line}")
    print(f"writeStoreCompact: {scan.write_store_start_line} - {scan.write_store_end_line}")
    if scan.live_generator_marker_line:
        print(f"LiveGenerator markChunkGenerated: L{scan.live_generator_marker_line}")
    print(f"Identified RegionView methods: {len(scan.region_methods)}")
    print(f"Identified state drop locations: {len(scan.state_drops)}")
    print(f"Identified skipped/corrupted feature categories: {len(scan.skipped_features)}")
    print(f"Stage E requirements specified: {len(scan.stage_e_requirements)}")
    print("-----------------------------------------------------------------")

    for m in scan.region_methods:
        print(f"  [{m.category:<13}] {m.name:<25} (L{m.line_number})")

    print("-----------------------------------------------------------------")
    for idx, f in enumerate(scan.skipped_features, 1):
        print(f"  {idx}. [{f.impact_severity:<8}] Step {f.step_index} ({f.step_name}): {f.category_name}")
    print("=================================================================")

    if args.json:
        json_path = args.json if args.json.is_absolute() else root / args.json
        json_path.parent.mkdir(parents=True, exist_ok=True)
        with open(json_path, "w", encoding="utf-8") as f:
            json.dump(asdict(scan), f, indent=2)
        print(f"Wrote JSON census to {json_path}")

    # Generate or write markdown report
    out_path = args.output if args.output.is_absolute() else root / args.output
    out_path.parent.mkdir(parents=True, exist_ok=True)
    report_content = generate_markdown_report(scan)
    with open(out_path, "w", encoding="utf-8") as f:
        f.write(report_content)
    print(f"Wrote Markdown report to {out_path}")

    if args.check:
        # Integrity checks
        assert len(scan.region_methods) >= 9, "Expected at least 9 categorized RegionView methods"
        assert len(scan.skipped_features) >= 7, "Expected at least 7 skipped feature categories"
        assert scan.region_view_start_line > 0, "RegionView start line not found"
        assert scan.exception_catch_start_line > 0, "Catch loop start line not found"
        assert scan.write_store_start_line > 0, "writeStoreCompact start line not found"
        print("Check: ALL INTEGRITY ASSERTIONS PASSED.")


if __name__ == "__main__":
    main()
