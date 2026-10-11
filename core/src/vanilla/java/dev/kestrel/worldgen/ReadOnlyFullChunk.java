package dev.kestrel.worldgen;

import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.TickContainerAccess;

/**
 * Headless equivalent of Vanilla 26.2 {@code ImposterProtoChunk(wrapped, false)}.
 *
 * <p>Vanilla replaces every pre-FULL future in a GenerationChunkHolder with a
 * read-only ImposterProtoChunk when the chunk reaches FULL. Later neighboring
 * worldgen therefore still reads the live chunk's blocks/biomes/structures,
 * but direct ProtoChunk writes disappear: {@link #setBlockState} is a no-op
 * and {@link #getSection} exposes this wrapper's empty ProtoChunk sections.
 * The latter is important because features such as ores write through
 * BulkSectionAccess and bypass WorldGenRegion.setBlock entirely.
 *
 * <p>Kestrel keeps one mutable ProtoChunk object in its headless grid, so this
 * wrapper is intentionally ephemeral. It changes only the view observed by a
 * later WorldGenRegion; the authoritative backing chunk remains untouched.
 */
final class ReadOnlyFullChunk extends ProtoChunk {
    private final ChunkAccess wrapped;

    ReadOnlyFullChunk(
            ChunkAccess wrapped,
            LevelHeightAccessor height,
            PalettedContainerFactory containerFactory) {
        super(
                wrapped.getPos(),
                UpgradeData.EMPTY,
                height,
                containerFactory,
                wrapped.getBlendingData());
        this.wrapped = wrapped;
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return wrapped.getBlockEntity(pos);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return wrapped.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return wrapped.getFluidState(pos);
    }

    /**
     * Exact allowWrites=false behavior: do not expose the wrapped sections.
     * BulkSectionAccess writes into these private empty sections, so those
     * writes vanish exactly like they do through Vanilla's ImposterProtoChunk.
     */
    @Override
    public LevelChunkSection getSection(int sectionIndex) {
        return super.getSection(sectionIndex);
    }

    @Override
    public BlockState setBlockState(BlockPos pos, BlockState state, int flags) {
        return null;
    }

    @Override
    public void setBlockEntity(BlockEntity blockEntity) {
    }

    @Override
    public void addEntity(Entity entity) {
    }

    @Override
    public void setPersistedStatus(ChunkStatus status) {
    }

    @Override
    public LevelChunkSection[] getSections() {
        return wrapped.getSections();
    }

    @Override
    public void setHeightmap(Heightmap.Types key, long[] data) {
    }

    private static Heightmap.Types fixType(Heightmap.Types type) {
        if (type == Heightmap.Types.WORLD_SURFACE_WG) {
            return Heightmap.Types.WORLD_SURFACE;
        }
        return type == Heightmap.Types.OCEAN_FLOOR_WG
                ? Heightmap.Types.OCEAN_FLOOR
                : type;
    }

    @Override
    public Heightmap getOrCreateHeightmapUnprimed(Heightmap.Types type) {
        return wrapped.getOrCreateHeightmapUnprimed(type);
    }

    @Override
    public int getHeight(Heightmap.Types type, int x, int z) {
        return wrapped.getHeight(fixType(type), x, z);
    }

    @Override
    public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ) {
        return wrapped.getNoiseBiome(quartX, quartY, quartZ);
    }

    @Override
    public ChunkPos getPos() {
        return wrapped.getPos();
    }

    @Override
    public StructureStart getStartForStructure(Structure structure) {
        return wrapped.getStartForStructure(structure);
    }

    @Override
    public void setStartForStructure(Structure structure, StructureStart structureStart) {
    }

    @Override
    public Map<Structure, StructureStart> getAllStarts() {
        return wrapped.getAllStarts();
    }

    @Override
    public void setAllStarts(Map<Structure, StructureStart> starts) {
    }

    @Override
    public LongSet getReferencesForStructure(Structure structure) {
        return wrapped.getReferencesForStructure(structure);
    }

    @Override
    public void addReferenceForStructure(Structure structure, long reference) {
    }

    @Override
    public Map<Structure, LongSet> getAllReferences() {
        return wrapped.getAllReferences();
    }

    @Override
    public void setAllReferences(Map<Structure, LongSet> data) {
    }

    @Override
    public void markUnsaved() {
        wrapped.markUnsaved();
    }

    @Override
    public boolean canBeSerialized() {
        return false;
    }

    @Override
    public boolean tryMarkSaved() {
        return false;
    }

    @Override
    public boolean isUnsaved() {
        return false;
    }

    @Override
    public ChunkStatus getPersistedStatus() {
        // The wrapper represents a LevelChunk even when Kestrel's backing
        // ProtoChunk intentionally remains at its actual implemented stage.
        return ChunkStatus.FULL;
    }

    @Override
    public void removeBlockEntity(BlockPos pos) {
    }

    @Override
    public void markPosForPostProcessing(BlockPos blockPos) {
    }

    @Override
    public void setBlockEntityNbt(CompoundTag entityTag) {
    }

    @Override
    public CompoundTag getBlockEntityNbt(BlockPos blockPos) {
        return wrapped.getBlockEntityNbt(blockPos);
    }

    @Override
    public CompoundTag getBlockEntityNbtForSaving(
            BlockPos blockPos, HolderLookup.Provider registryAccess) {
        return wrapped.getBlockEntityNbtForSaving(blockPos, registryAccess);
    }

    @Override
    public void findBlocks(
            Predicate<BlockState> predicate,
            BiConsumer<BlockPos, BlockState> consumer) {
        wrapped.findBlocks(predicate, consumer);
    }

    @Override
    public TickContainerAccess<Block> getBlockTicks() {
        return BlackholeTickAccess.emptyContainer();
    }

    @Override
    public TickContainerAccess<Fluid> getFluidTicks() {
        return BlackholeTickAccess.emptyContainer();
    }

    @Override
    public ChunkAccess.PackedTicks getTicksForSerialization(long currentTick) {
        return wrapped.getTicksForSerialization(currentTick);
    }

    @Override
    public BlendingData getBlendingData() {
        return wrapped.getBlendingData();
    }

    @Override
    public CarvingMask getCarvingMask() {
        throw new UnsupportedOperationException("Meaningless in read-only FULL view");
    }

    @Override
    public CarvingMask getOrCreateCarvingMask() {
        throw new UnsupportedOperationException("Meaningless in read-only FULL view");
    }

    ChunkAccess wrapped() {
        return wrapped;
    }

    @Override
    public boolean isLightCorrect() {
        return wrapped.isLightCorrect();
    }

    @Override
    public void setLightCorrect(boolean isLightCorrect) {
        wrapped.setLightCorrect(isLightCorrect);
    }

    @Override
    public void fillBiomesFromNoise(
            BiomeResolver biomeResolver,
            Climate.Sampler sampler) {
    }

    @Override
    public void initializeLightSources() {
        wrapped.initializeLightSources();
    }

    @Override
    public ChunkSkyLightSources getSkyLightSources() {
        return wrapped.getSkyLightSources();
    }
}
