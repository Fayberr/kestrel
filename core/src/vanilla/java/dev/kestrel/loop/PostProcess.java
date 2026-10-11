package dev.kestrel.loop;

import it.unimi.dsi.fastutil.shorts.ShortList;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Chunk-activation post-processing: vanilla 26.2
 * {@code LevelChunk.postProcessGeneration} ({@code LevelChunk.java:575-613}),
 * run over the live {@link TickLoop} when a chunk is activated (see
 * {@link ChunkActivation} for when that is).
 *
 * <p>During generation, {@code WorldGenRegion.setBlock} and the noise,
 * surface, carver, structure and feature paths mark positions on the chunk
 * ({@code ChunkAccess.markPosForPostProcessing}). Kestrel keeps those marks in
 * {@link KestrelLevel#postProcessing}. The pass walks them section index by
 * section index, and within a section in list order. For each position it
 * reads the block state once, then, in vanilla's order:
 *
 * <ol>
 *   <li>if that state holds a fluid, ticks the fluid
 *       ({@code FluidState.tick} is {@code FlowingFluid.tick}, which is
 *       {@link FluidFlow#tickFluid}, Kestrel's gated transcription: vanilla's
 *       own method needs a {@code ServerLevel});</li>
 *   <li>if the state read in step 0 is a {@link LiquidBlock}, runs
 *       {@code LiquidBlock.tick} (the bubble-column check), otherwise runs
 *       vanilla's own {@link Block#updateFromNeighbourShapes} on that state and,
 *       when the result differs, sets it with flags {@link #FLAGS}.</li>
 * </ol>
 *
 * Both branches use the state read before the fluid tick, exactly as vanilla
 * does, even when the fluid tick has since replaced the block. Each section's
 * list is cleared after its walk, so the chunk leaves the pass with nothing
 * pending.
 *
 * <p>Vanilla's own code runs wherever the state allows it:
 * {@code updateFromNeighbourShapes} (and through it every block's
 * {@code updateShape}, {@code canSurvive}, tag reads and light reads) and
 * {@code BubbleColumnBlock.updateColumn} run unmodified against
 * {@link PassLevel}, a {@link LevelAccessor} over this loop. Its reads are the
 * loop's cells, its schedules go into the loop's real block and fluid tick
 * queues ({@link TickLoop#loopTicks}), its writes go through
 * {@link TickLoop#setBlock}, its random is the loop's level random, and its
 * light reads come from the light engine the caller supplies (the generator's
 * engine on the live server, the same data vanilla's main thread reads from
 * the threaded engine's visible storage). Any other {@code LevelAccessor}
 * method a block reaches for fails loudly instead of answering a silent zero.
 *
 * <p>Not modeled, each a Kestrel-wide gap rather than a pass one: the pending
 * block-entity promotion and {@code UpgradeData.upgrade} that close vanilla's
 * method (Kestrel keeps no pending block-entity NBT and no upgrade data);
 * drops when flowing water replaces a block ({@link FluidFlow} documents
 * them as no-ops); and the neighbour side effects {@link TickLoop#setBlock}
 * does not model for non-fluid blocks (water the pass sets flowing over sand
 * or gravel schedules no {@code FallingBlock} tick, see the known gap in
 * {@code PostProcessParityCheck}).
 *
 * <p>Thread-safety: tick thread only.
 */
public final class PostProcess {

    /**
     * {@code LevelChunk.postProcessGeneration}'s setBlock flags: 256 (skip
     * block-entity side effects) | 16 (known shape: no neighbour shape
     * updates) | 4 (no client rerender). No flag 1 (no neighbour updates) and
     * no flag 2 (vanilla sends nothing, because the chunk is not sent yet).
     */
    public static final int FLAGS = 276;

    /** One committed state change during a pass, in commit order. */
    public record Write(BlockPos pos, BlockState oldState, BlockState newState) {
    }

    private PostProcess() {
    }

    /**
     * Run the pass for one chunk and clear its pending lists.
     *
     * @param light the light engine the pass reads brightness from; required,
     *        because {@code MushroomBlock.canSurvive} (and any other
     *        light-dependent survival rule) is part of the pass
     * @param strict rethrow a position that fails (gates) instead of logging
     *        it and moving on to the next position (the live server)
     * @return every committed write the pass caused, in commit order,
     *         including the fluid flow it started, for the light glue
     */
    public static List<Write> run(TickLoop loop, int cx, int cz, LevelLightEngine light, boolean strict) {
        if (light == null) {
            throw new IllegalArgumentException("the post-processing pass needs a light engine");
        }
        long key = ChunkPos.pack(cx, cz);
        ShortList[] lists = loop.level().postProcessing(key);
        List<Write> writes = new ArrayList<>();
        if (lists == null) {
            return writes;
        }
        ChunkPos chunkPos = new ChunkPos(cx, cz);
        int minSectionY = loop.level().getMinY() >> 4;
        LevelAccessor level = PassLevel.create(loop, light);
        loop.setWriteObserver((pos, oldState, newState) -> writes.add(new Write(pos, oldState, newState)));
        try {
            for (int sectionIndex = 0; sectionIndex < lists.length; sectionIndex++) {
                ShortList section = lists[sectionIndex];
                if (section == null) {
                    continue;
                }
                for (int i = 0; i < section.size(); i++) {
                    BlockPos pos = ProtoChunk.unpackOffsetCoordinates(
                            section.getShort(i), minSectionY + sectionIndex, chunkPos);
                    try {
                        processPosition(loop, level, pos);
                    } catch (RuntimeException e) {
                        if (strict) {
                            throw e;
                        }
                        System.out.println("[kestrel] post-processing failed at " + pos.toShortString()
                                + " in chunk " + cx + "," + cz + ": " + e);
                    }
                }
                section.clear();
            }
        } finally {
            loop.setWriteObserver(null);
        }
        // Every list is empty now, which drops the chunk's entry.
        loop.level().putPostProcessing(key, lists);
        return writes;
    }

    /** The loop body of {@code postProcessGeneration} for one marked position. */
    private static void processPosition(TickLoop loop, LevelAccessor level, BlockPos pos) {
        BlockState blockState = loop.getBlockState(pos);
        FluidState fluidState = blockState.getFluidState();
        if (!fluidState.isEmpty()) {
            loop.fluidFlow().tickFluid(pos, fluidState.getType());
        }
        if (blockState.getBlock() instanceof LiquidBlock) {
            liquidTick(level, blockState, pos);
        } else {
            BlockState blockStateNew = Block.updateFromNeighbourShapes(blockState, level, pos);
            if (blockStateNew != blockState) {
                level.setBlock(pos, blockStateNew, FLAGS);
            }
        }
    }

    /**
     * {@code LiquidBlock.tick} ({@code LiquidBlock.java:161-167}); the vanilla
     * method takes a {@code ServerLevel}, so its two statements are written out
     * here and the column update itself is vanilla's.
     */
    private static void liquidTick(LevelAccessor level, BlockState state, BlockPos pos) {
        if (shouldBubbleColumnOccupy(state)) {
            BlockState stateBelow = level.getBlockState(pos.below());
            BubbleColumnBlock.updateColumn(Blocks.BUBBLE_COLUMN, level, pos, stateBelow);
        }
    }

    /** {@code LiquidBlock.shouldBubbleColumnOccupy} ({@code LiquidBlock.java:192-194}, private). */
    private static boolean shouldBubbleColumnOccupy(BlockState state) {
        FluidState fluid = state.getFluidState();
        return fluid.is(FluidTags.BUBBLE_COLUMN_CAN_OCCUPY) && fluid.isSource() && fluid.isFull();
    }

    /**
     * The {@code ServerLevel} the pass would have run against, as far as the
     * pass reaches into it. A JDK proxy so every default method of
     * {@link LevelAccessor} runs vanilla's own body on top of the answers
     * below; an abstract method with no answer here throws.
     */
    static final class PassLevel implements InvocationHandler {
        private final TickLoop loop;
        private final LevelLightEngine light;
        private final net.minecraft.world.level.ScheduledTickAccess ticks;

        private PassLevel(TickLoop loop, LevelLightEngine light) {
            this.loop = loop;
            this.light = light;
            this.ticks = loop.loopTicks();
        }

        static LevelAccessor create(TickLoop loop, LevelLightEngine light) {
            return (LevelAccessor) Proxy.newProxyInstance(PostProcess.class.getClassLoader(),
                    new Class<?>[]{LevelAccessor.class}, new PassLevel(loop, light));
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            int n = args == null ? 0 : args.length;
            switch (name) {
                case "getBlockState":
                    if (n == 1) {
                        return loop.getBlockState((BlockPos) args[0]);
                    }
                    break;
                case "getFluidState":
                    if (n == 1) {
                        return loop.getBlockState((BlockPos) args[0]).getFluidState();
                    }
                    break;
                case "getBlockEntity":
                    if (n == 1) {
                        return loop.loopGetter().getBlockEntity((BlockPos) args[0]);
                    }
                    break;
                case "setBlock":
                    if (n == 3 || n == 4) {
                        return setBlock((BlockPos) args[0], (BlockState) args[1], (Integer) args[2]);
                    }
                    break;
                case "getRandom":
                    return loop.fluidFlow().random();
                case "getLightEngine":
                    return light;
                case "getBlockTicks":
                    return ticks.getBlockTicks();
                case "getFluidTicks":
                    return ticks.getFluidTicks();
                case "createTick":
                    if (n == 3) {
                        return ticks.createTick((BlockPos) args[0], args[1], (Integer) args[2]);
                    }
                    if (n == 4) {
                        return ticks.createTick((BlockPos) args[0], args[1], (Integer) args[2],
                                (net.minecraft.world.ticks.TickPriority) args[3]);
                    }
                    break;
                case "getMinY":
                    return loop.level().getMinY();
                case "getHeight":
                    if (n == 0) {
                        return loop.level().getHeight();
                    }
                    break;
                case "isClientSide":
                    return false;
                case "toString":
                    return "PostProcessLevel";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    break;
            }
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            throw new UnsupportedOperationException(
                    "post-processing reached LevelAccessor." + name + " with " + n
                            + " argument(s), which Kestrel's pass level does not serve");
        }

        /**
         * {@code Level.setBlock}'s answer: false outside the build height and
         * when nothing changes ({@code LevelChunk.setBlockState} returns null
         * for an identical state), otherwise the loop's write.
         */
        private boolean setBlock(BlockPos pos, BlockState state, int flags) {
            if (pos.getY() < loop.level().getMinY()
                    || pos.getY() >= loop.level().getMinY() + loop.level().getHeight()) {
                return false;
            }
            if (loop.getBlockState(pos) == state) {
                return false;
            }
            loop.setBlock(pos, state, flags);
            return true;
        }
    }
}
