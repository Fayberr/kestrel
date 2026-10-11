package dev.kestrel.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Resolves neighbor-dependent block states (fences, walls, panes, bars, and
 * later redstone dust) the same way real vanilla does when blocks are placed:
 * by running each block's real {@code updateShape} against its neighbors,
 * iteratively, until no state changes anymore.
 *
 * <p>Why this exists: {@link CollisionStressFixture} builds block states via
 * {@code Block.defaultBlockState()} + {@code setValue(...)} only. That skips
 * vanilla's neighbor-aware state resolution ({@code updateShape}, which real
 * {@code /fill} and block placement trigger automatically), so connection
 * properties come out all-false even for blocks placed in a contiguous run.
 * Full-cube and non-connection-dependent shapes (stairs, slabs, snow layers,
 * hopper, cauldron) are unaffected; they never read neighbor state.
 *
 * <p>How it works: builds a dynamic-proxy {@link LevelReader} backed by the
 * given {@link KestrelBlockGetter} (only {@code getBlockState}/{@code
 * getFluidState} are meaningfully served; everything else returns
 * vanilla-tolerated defaults), plus a no-op {@link ScheduledTickAccess}
 * that records but ignores scheduled ticks (fixture states are all dry, so
 * {@code WATERLOGGED} never fires). Then sweeps every stored position,
 * calling the real {@code BlockState.updateShape} once per horizontal/UP
 * neighbor direction, and repeats full sweeps until a whole sweep changes
 * nothing. Connection state propagates at most one block per sweep per
 * direction, so convergence is bounded by the field's longest run length.
 *
 * <p>Limitations (deliberate, fixture scope):
 * <ul>
 *   <li>Downward neighbor updates are skipped: no connection logic in the
 *       fixture's blocks runs on the DOWN axis (verified against the
 *       decompiled 26.2 {@code FenceBlock.updateShape} and
 *       {@code WallBlock.updateShape}; both return early or delegate for
 *       DOWN).</li>
 *   <li>Scheduled ticks are swallowed: correct here because nothing in the
 *       fixture is waterlogged. A future fixture with waterlogged blocks
 *       would need to honor them.</li>
 * </ul>
 */
public final class NeighborStateResolver {
    private NeighborStateResolver() {
    }

    public static LevelReader asLevelReader(KestrelBlockGetter blocks) {
        InvocationHandler handler = new LevelHandler(blocks);
        return (LevelReader) Proxy.newProxyInstance(
                NeighborStateResolver.class.getClassLoader(),
                new Class<?>[]{LevelReader.class},
                handler);
    }

    public static ScheduledTickAccess noOpTicks() {
        InvocationHandler handler = (proxy, method, args) -> defaultValue(method.getReturnType());
        return (ScheduledTickAccess) Proxy.newProxyInstance(
                NeighborStateResolver.class.getClassLoader(),
                new Class<?>[]{ScheduledTickAccess.class},
                handler);
    }

    /**
     * Bind the {@code FENCES} / {@code WOODEN_FENCES} / {@code WALLS} block
     * tags from the real registry, so {@code state.is(tag)} inside
     * {@code FenceBlock}/{@code WallBlock} connection logic answers the way
     * it does on a real server. Call once after {@code Bootstrap.bootStrap()}.
     *
     * <p>Background: {@code Bootstrap.bootStrap()} registers blocks but binds
     * no tags (that happens later during datapack/world loading). Without
     * this, every {@code state.is(BlockTags.FENCES)} check returns false, so
     * same-type connections never form and the fixture stays all-disconnected
     * even though {@code updateShape} itself runs correctly.
     */
    public static void bindConnectionTags() {
        Map<TagKey<Block>, List<Holder<Block>>> tags = new HashMap<>();
        tags.put(
                net.minecraft.tags.BlockTags.FENCES,
                List.of(holderOf("oak_fence"), holderOf("nether_brick_fence")));
        tags.put(
                net.minecraft.tags.BlockTags.WOODEN_FENCES,
                List.of(
                        holderOf("oak_fence"),
                        holderOf("acacia_fence"),
                        holderOf("dark_oak_fence"),
                        holderOf("pale_oak_fence"),
                        holderOf("spruce_fence"),
                        holderOf("birch_fence"),
                        holderOf("jungle_fence"),
                        holderOf("crimson_fence"),
                        holderOf("warped_fence"),
                        holderOf("mangrove_fence"),
                        holderOf("bamboo_fence"),
                        holderOf("cherry_fence")));
        tags.put(
                net.minecraft.tags.BlockTags.WALLS,
                List.of(
                        holderOf("cobblestone_wall"),
                        holderOf("mossy_cobblestone_wall"),
                        holderOf("brick_wall"),
                        holderOf("prismarine_wall"),
                        holderOf("red_sandstone_wall"),
                        holderOf("mossy_stone_brick_wall"),
                        holderOf("granite_wall"),
                        holderOf("stone_brick_wall"),
                        holderOf("nether_brick_wall"),
                        holderOf("andesite_wall"),
                        holderOf("red_nether_brick_wall"),
                        holderOf("sandstone_wall"),
                        holderOf("end_stone_brick_wall"),
                        holderOf("diorite_wall"),
                        holderOf("blackstone_wall"),
                        holderOf("polished_blackstone_brick_wall"),
                        holderOf("polished_blackstone_wall"),
                        holderOf("cobbled_deepslate_wall"),
                        holderOf("polished_deepslate_wall"),
                        holderOf("deepslate_tile_wall"),
                        holderOf("deepslate_brick_wall"),
                        holderOf("mud_brick_wall"),
                        holderOf("tuff_wall"),
                        holderOf("polished_tuff_wall"),
                        holderOf("tuff_brick_wall"),
                        holderOf("resin_brick_wall"),
                        holderOf("cinnabar_wall"),
                        holderOf("polished_cinnabar_wall"),
                        holderOf("cinnabar_brick_wall"),
                        holderOf("sulfur_wall"),
                        holderOf("polished_sulfur_wall"),
                        holderOf("sulfur_brick_wall")));
        bindTags(tags);
        // Gates connect to fences/walls too, but the fixture corpus contains
        // none, so their tags are deliberately not bound here.
    }

    private static Holder<Block> holderOf(String id) {
        try {
            // Registry.getValue(Identifier) touches the Keyable supertype,
            // whose class file (DFU/serialization lib) is not on the fixture
            // classpath. Use the ResourceKey path instead, which resolves
            // through the registry's own holder lookup only.
            Method getValueKey = null;
            for (Method m : BuiltInRegistries.BLOCK.getClass().getMethods()) {
                if (m.getName().equals("getValue")
                        && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].getSimpleName().equals("ResourceKey")) {
                    getValueKey = m;
                    break;
                }
            }
            if (getValueKey == null) {
                throw new IllegalStateException("No getValue(ResourceKey) on block registry");
            }
            Object resourceKey = net.minecraft.resources.ResourceKey.create(
                    BuiltInRegistries.BLOCK.key(),
                    Identifier.fromNamespaceAndPath("minecraft", id));
            Block block = (Block) getValueKey.invoke(BuiltInRegistries.BLOCK, resourceKey);
            return BuiltInRegistries.BLOCK.wrapAsHolder(block);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to resolve block holder: " + id, e);
        }
    }

    /**
     * Bind tags onto each block holder directly ({@code Reference.bindTags},
     * package-private). The registry itself is frozen after
     * {@code Bootstrap.bootStrap()}, so the public {@code MappedRegistry.bindTags}
     * path throws {@code IllegalStateException: Registry is already frozen}.
     * Per-holder binding is exactly what the frozen path does internally
     * (each tag's holders get the tag added), just without the registry-level
     * write guard. Reset-then-bind so repeat calls (tests, rebuilds) stay
     * idempotent rather than accumulating stale tags.
     */
    private static final Method REFERENCE_BIND_TAGS = findReferenceBindTags();

    private static Method findReferenceBindTags() {
        try {
            Method m = Class.forName("net.minecraft.core.Holder$Reference")
                    .getDeclaredMethod("bindTags", java.util.Collection.class);
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to locate Holder.Reference.bindTags", e);
        }
    }

    private static void bindTags(Map<TagKey<Block>, List<Holder<Block>>> tags) {
        try {
            // Group tags per holder first, so each holder is touched once.
            Map<Holder<Block>, java.util.Set<TagKey<Block>>> perHolder = new HashMap<>();
            for (Map.Entry<TagKey<Block>, List<Holder<Block>>> e : tags.entrySet()) {
                for (Holder<Block> h : e.getValue()) {
                    perHolder.computeIfAbsent(h, k -> new java.util.HashSet<>()).add(e.getKey());
                }
            }
            // Reset tags To avoid stale-tag accumulation across repeat calls,
            // clear the holder's current tag set before binding (same field
            // bindTags appends To internally).
            java.lang.reflect.Field tagsField =
                    Class.forName("net.minecraft.core.Holder$Reference").getDeclaredField("tags");
            tagsField.setAccessible(true);
            for (Map.Entry<Holder<Block>, java.util.Set<TagKey<Block>>> e : perHolder.entrySet()) {
                Holder<Block> holder = e.getKey();
                // Holders start with an immutable empty tag set; replace it
                // with a fresh mutable copy instead of clearing in place.
                tagsField.set(holder, new java.util.HashSet<TagKey<Block>>());
                REFERENCE_BIND_TAGS.invoke(holder, e.getValue());
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to bind connection tags", e);
        }
    }

    /**
     * Sweep all stored positions through real vanilla {@code updateShape}
     * until a full sweep changes nothing. Returns the number of sweeps run.
     */
    public static int resolveAll(KestrelBlockGetter blocks) {
        LevelReader level = asLevelReader(blocks);
        ScheduledTickAccess ticks = noOpTicks();
        RandomSource random = RandomSource.create(0);
        Direction[] axes = {
                Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP,
        };
        int sweeps = 0;
        boolean changed;
        do {
            changed = false;
            sweeps++;
            for (BlockPos pos : blocks.storedPositions()) {
                BlockState current = blocks.getBlockState(pos);
                for (Direction dir : axes) {
                    BlockPos neighbourPos = pos.relative(dir);
                    BlockState neighbourState = blocks.getBlockState(neighbourPos);
                    BlockState updated = current.updateShape(
                            level, ticks, pos, dir, neighbourPos, neighbourState, random);
                    if (updated != current) {
                        blocks.setBlock(pos, updated);
                        current = updated;
                        changed = true;
                    }
                }
            }
        } while (changed && sweeps < 100);
        return sweeps;
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == void.class) {
            return null;
        }
        if (type == float.class || type == double.class) {
            return 0.0;
        }
        return 0;
    }

    private static final class LevelHandler implements InvocationHandler {
        private final KestrelBlockGetter blocks;

        LevelHandler(KestrelBlockGetter blocks) {
            this.blocks = blocks;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if (name.equals("getBlockState") && args.length == 1) {
                return blocks.getBlockState((BlockPos) args[0]);
            }
            if (name.equals("getFluidState") && args.length == 1) {
                return blocks.getFluidState((BlockPos) args[0]);
            }
            if (name.equals("getBlockEntity") && args.length >= 1) {
                return null;
            }
            if (name.equals("getHeight") || name.equals("getMinY")) {
                return name.equals("getMinY") ? blocks.getMinY() : blocks.getHeight();
            }
            if (name.equals("isClientSide")) {
                return false;
            }
            if (name.equals("toString")) {
                return "KestrelLevelReaderProxy";
            }
            if (name.equals("hashCode")) {
                return System.identityHashCode(proxy);
            }
            if (name.equals("equals")) {
                return proxy == args[0];
            }
            return defaultValue(method.getReturnType());
        }
    }
}
