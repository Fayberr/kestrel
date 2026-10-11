package dev.kestrel.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.HashMap;
import java.util.Map;

/**
 * Minimal in-memory {@code BlockGetter}, backed by a plain
 * {@code HashMap<BlockPos, BlockState>}. This is deliberately NOT the
 * future "own code" storage subsystem (chunk sections, region files,
 * persistence, per {@code README.md}'s architecture). It exists only to
 * give vanilla's real {@code BlockState.getCollisionShape(BlockGetter,
 * BlockPos)} something real to query neighbor state from, so optimization
 * #1's fallback path (the ~5.2% of blockstates plus every
 * neighbor-dependent shape, e.g. fences/walls/redstone dust) can be
 * proven against real vanilla logic instead of guessed. See
 * {@code preresearch/core-block-model-design.md}.
 *
 * <p>Known, deliberate limitations for this proving-ground scope:
 * <ul>
 *   <li>{@link #getBlockEntity} always returns {@code null}. Verified via
 *       {@code javap} against the real vanilla classes that
 *       {@code getShape}/{@code getCollisionShape} on
 *       {@code BlockBehaviour.BlockStateBase} do not call
 *       {@code getBlockEntity} for shape computation, so this is safe for
 *       the blockstates this project's fixtures currently use (including
 *       hopper and powder snow cauldron). Would need real support before
 *       adding any block whose *shape* genuinely depends on its block
 *       entity's data.</li>
 *   <li>No chunk sections, no persistence, no multiple dimensions - one
 *       flat map, bounded only by whatever {@link #setBlock} calls
 *       populate it with. Unset positions read as air.</li>
 * </ul>
 *
 * <p>Stage 1: this class is a thin adapter, not storage. The canonical
 * world layer is {@code dev.kestrel.loop.KestrelLevel}; the map-backed
 * constructor below exists only for the collision fixture path
 * ({@code CollisionStressFixture}, {@code KestrelCollideCheck}), which
 * does not tick a world. The loop path reads live from the level through
 * its own reader ({@code TickLoop.levelReader}), never through a snapshot
 * copy of this class.
 */
public final class KestrelBlockGetter implements BlockGetter {
    private final Map<BlockPos, BlockState> blocks = new HashMap<>();
    private final BlockState air = Blocks.AIR.defaultBlockState();
    private final int minY;
    private final int height;

    public KestrelBlockGetter(int minY, int height) {
        this.minY = minY;
        this.height = height;
    }

    public void setBlock(BlockPos pos, BlockState state) {
        blocks.put(pos.immutable(), state);
    }

    public void setBlock(int x, int y, int z, BlockState state) {
        setBlock(new BlockPos(x, y, z), state);
    }

    /** All positions currently stored (snapshot copy, safe to iterate while resolving). */
    public java.util.Set<BlockPos> storedPositions() {
        return new java.util.HashSet<>(blocks.keySet());
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null; // see class javadoc "Known, deliberate limitations"
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return blocks.getOrDefault(pos, air);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public int getMinY() {
        return minY;
    }
}
