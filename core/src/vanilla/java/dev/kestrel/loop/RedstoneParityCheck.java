package dev.kestrel.loop;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * G12 redstone parity gate: replays every {@link RedstoneScenarios} scenario
 * on a fresh {@link TickLoop} and compares the recording, line for line,
 * against the committed vanilla oracle fixtures captured by
 * {@link RedstoneOracle}.
 *
 * <p>Unlike the fluid gate, this one does not replay the scenario setup. The
 * v2 fixtures carry an {@code S} section: the post-setup state after vanilla
 * settled it, which is by construction a reachable, stable world with no
 * pending ticks. The gate loads that snapshot raw and starts at tick 0, so a
 * mismatch always points at the tick being compared rather than at setup
 * drift.
 *
 * <p>One value in the fixtures cannot be reproduced and is replayed from the
 * fixture instead, the same way {@link FluidParityCheck} replays lava's
 * random spread delays from the {@code R} lines: the {@code subTickOrder} on
 * each {@code q} line. That is vanilla's {@code Level.subTickCount}, a
 * counter that lives as long as the level and is never reset, so in a capture
 * run where all 29 scenarios share one world its value at scenario N is a
 * function of every schedule the previous N-1 scenarios made. A fresh loop
 * per scenario starts it at 0 by construction. The gate seeds the counter
 * with the fixture's first {@code q} value so the two origins line up, and
 * prints the seed it used; everything downstream of that, including every
 * difference between counter values (which is what actually orders two ticks
 * landing on the same tick at the same priority), stays a strict comparison.
 * A scenario that schedules a different number of ticks, or schedules them in
 * a different order, still fails.
 *
 * <p>Usage: {@code RedstoneParityCheck [FIXTURE_DIR] [scenario...]}. Without a
 * directory the gate looks for {@code harness/corpus/redstone/oracle} upward
 * from the working directory, then upward from the class location.
 * {@code -Dredstone.dump=DIR} writes Kestrel's own recordings there for
 * diffing. Exit 0 only when every selected scenario matches exactly.
 *
 * <p>All 29 scenarios run and report, and all 29 are required: wires, torches
 * (with burnout), repeaters, comparators, levers, buttons, observers, lamps
 * (slices 1 to 4) and the pistons (slice 5: the block-event queue, the
 * structure resolver, the moving placeholders and quasi-connectivity). See
 * section 9 of {@code preresearch/g12-redstone-preresearch.md}.
 *
 * <p>Two of the 29 close coverage gaps found after slice 5:
 * {@code piston_block_events_contention} (the drain order of two same-tick
 * block events, which {@code piston_block_events_fifo} does not pin) and
 * {@code piston_settle_power_cut} (the {@code handlingTick} flag during the
 * block-entity pass).
 */
public final class RedstoneParityCheck {
    private RedstoneParityCheck() {
    }

    static final String FIXTURE_REL = "harness/corpus/redstone/oracle";

    /** Scenarios every slice of G12 has to reproduce exactly: the original 27 plus the two gap fixtures. */
    private static final List<String> REQUIRED = List.of(
            "wire_falloff_16", "wire_branching_t", "wire_stair_climb",
            "wire_stair_occlusion", "update_order_tie",
            "torch_inversion", "torch_tower", "torch_burnout_clock",
            "repeater_delays", "repeater_locking", "repeater_pulse_stretch", "repeater_priority_drain",
            "comparator_compare", "comparator_subtract", "comparator_container", "comparator_through_block",
            "observer_single_pulse", "observer_clock",
            "lever_power_types", "button_stone_duration", "button_wood_duration", "lamp_timings",
            "piston_qc_bud", "piston_push_limit_12", "piston_slime_honey_split",
            "piston_block_drop", "piston_block_events_fifo",
            "piston_block_events_contention", "piston_settle_power_cut");

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        bindItemComponents();
        Path dir = null;
        List<String> only = new ArrayList<>();
        for (String a : args) {
            if (dir == null && Files.isDirectory(Path.of(a))) {
                dir = Path.of(a);
            } else {
                only.add(a);
            }
        }
        if (dir == null) {
            dir = locateFixtures();
        }
        if (dir == null) {
            System.out.println("RedstoneParityCheck FAIL: no fixture dir (" + FIXTURE_REL + ")");
            System.exit(1);
        }

        int pass = 0;
        int fail = 0;
        int sliceFail = 0;
        for (RedstoneScenarios.Scenario s : RedstoneScenarios.all()) {
            if (!only.isEmpty() && !only.contains(s.name())) {
                continue;
            }
            boolean required = REQUIRED.contains(s.name());
            String tag = required ? " [required]" : "";
            Path fixture = dir.resolve(s.name() + ".txt");
            if (!Files.exists(fixture)) {
                System.out.println("  " + s.name() + tag + ": FAIL (no fixture " + fixture + ")");
                fail++;
                if (required) {
                    sliceFail++;
                }
                continue;
            }
            List<String> expected = Files.readAllLines(fixture);
            List<String> actual;
            long seed = subTickSeed(expected);
            try {
                actual = replay(s, expected);
            } catch (RuntimeException e) {
                System.out.println("  " + s.name() + tag + ": FAIL (" + e + ")");
                fail++;
                if (required) {
                    sliceFail++;
                }
                continue;
            }
            String dump = System.getProperty("redstone.dump");
            if (dump != null) {
                Files.createDirectories(Path.of(dump));
                Files.write(Path.of(dump, s.name() + ".txt"), actual);
            }
            String diff = firstDiff(expected, actual);
            if (diff == null) {
                System.out.println("  " + s.name() + tag + ": PASS (" + actual.size() + " lines"
                        + (seed != 0 ? ", subTickOrder seeded at " + seed : "") + ")");
                pass++;
            } else {
                System.out.println("  " + s.name() + tag + ": FAIL " + diff);
                fail++;
                if (required) {
                    sliceFail++;
                }
            }
        }
        System.out.println("RedstoneParityCheck " + (fail == 0 ? "PASS" : "FAIL")
                + " " + pass + "/" + (pass + fail)
                + " (required failures: " + sliceFail + ")");
        System.exit(fail == 0 ? 0 : 1);
    }

    /**
     * Bind the common item component map to every item.
     *
     * <p>{@code Bootstrap.bootStrap()} registers items but leaves their
     * component maps unbound; vanilla binds them during a datapack reload
     * ({@code ReloadableServerResources} runs
     * {@code DataComponentInitializers}), which is why the oracle boots a
     * real server. Constructing any {@link net.minecraft.world.item.ItemStack}
     * before that throws "Components not bound yet", and
     * {@code RedstoneScenarios.all()} builds stacks for the container
     * scenarios, so the scenario list is not even constructible without this.
     *
     * <p>Running the real initializers here is not an option: several of them
     * resolve datapack tags (for instance {@code Item.Properties.fireResistant}
     * looks up {@code #minecraft:is_fire}) that only exist after a resource
     * reload. {@code DataComponents.COMMON_ITEM_COMPONENTS} is the base every
     * plain item's map is built on, and it carries the one component the
     * redstone path reads: {@code MAX_STACK_SIZE} 64, used by
     * {@code BaseContainerBlockEntity.setItem} ({@code limitSize}) and by
     * {@code AbstractContainerMenu.getRedstoneSignalFromContainer}. An empty
     * map would read max stack 1 and turn every 64-stack into a single item.
     * The scenarios only put stone in containers, whose real map agrees on
     * that value.
     */
    private static void bindItemComponents() {
        BuiltInRegistries.ITEM.listElements().forEach(
                holder -> holder.bindComponents(net.minecraft.core.component.DataComponents.COMMON_ITEM_COMPONENTS));
    }

    static Path locateFixtures() {
        List<Path> roots = new ArrayList<>();
        roots.add(Path.of("").toAbsolutePath());
        try {
            roots.add(Path.of(RedstoneParityCheck.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()));
        } catch (Exception ignored) {
            // no code source: working directory only
        }
        for (Path root : roots) {
            for (Path p = root; p != null; p = p.getParent()) {
                Path cand = p.resolve(FIXTURE_REL);
                if (Files.isDirectory(cand)) {
                    return cand;
                }
            }
        }
        return null;
    }

    // =====================================================================
    // Replay
    // =====================================================================

    /**
     * Run one scenario on a fresh loop seeded from the fixture's {@code S}
     * snapshot, recording in the oracle's own format (cell deltas, the
     * pending-tick queue, and the {@code E} lines for the block events
     * enqueued since the previous recording).
     *
     * <p>Action timing follows {@link RedstoneOracle}: a tick-0 action lands
     * before {@code T 0} is recorded, a tick-t action for t &gt; 0 lands
     * after {@code T t}.
     */
    static List<String> replay(RedstoneScenarios.Scenario s, List<String> fixture) {
        KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
        TickLoop loop = new TickLoop(level);
        // The E lines come from the engine's enqueue log, which is off in the
        // running world (an unbounded log would be a leak).
        loop.redstone().setBlockEventLogEnabled(true);
        // Vanilla's clock is far past 0 when the oracle runs; only relative
        // trigger ticks are compared, so any non-zero origin will do.
        loop.setGameTime(1000);
        loop.setSubTickOrder(subTickSeed(fixture));

        Map<Long, BlockState> settled = parseSettled(fixture);
        for (Map.Entry<Long, BlockState> e : settled.entrySet()) {
            loop.setBlock(BlockPos.of(e.getKey()), e.getValue(), RedstoneScenarios.RAW_FLAGS);
        }
        // The raw writes created the block entities; load their settled
        // contents from the BE lines, again with no updates (S is stable).
        loadBlockEntities(loop, parseBlockEntityLines(fixture));

        long base = loop.gameTime();
        RedstoneScenarios.Recorder rec = new RedstoneScenarios.Recorder(s);
        // The S section is reproduced verbatim from the fixture: it is the
        // input to this replay, not an output of it, so echoing it keeps the
        // line numbering aligned with the fixture and makes the first real
        // difference the first behavioural one.
        rec.recordSettled(settled, parseBlockEntityLines(fixture));

        apply(loop, s, 0);
        rec.record(0, (x, y, z) -> loop.getBlockState(new BlockPos(x, y, z)), queue(loop, base), events(loop, base));
        for (int t = 1; t <= s.ticks(); t++) {
            loop.tick();
            if (loop.gameTime() != base + t) {
                throw new IllegalStateException("clock drift: " + loop.gameTime() + " != " + (base + t));
            }
            rec.record(t, (x, y, z) -> loop.getBlockState(new BlockPos(x, y, z)), queue(loop, base),
                    events(loop, base));
            apply(loop, s, t);
        }
        return rec.lines();
    }

    /**
     * Block events enqueued since the previous call, in the oracle's own
     * {@code E} line format.
     *
     * <p>{@code RedstoneOracle} logs an event the moment it is added to
     * {@code ServerLevel.blockEvents} (its {@code EventLoggingSet} override),
     * stamped with {@code level.getGameTime() - base}. {@link TickLoop}
     * records the same pair, so a replay reproduces the lines exactly; the
     * take-and-clear is what makes an event appear in the recording of the
     * tick it was enqueued on rather than the tick it ran.
     */
    private static List<String> events(TickLoop loop, long base) {
        List<String> out = new ArrayList<>();
        for (dev.kestrel.redstone.VanillaRedstone.EnqueuedBlockEvent e
                : loop.redstone().takeEnqueuedBlockEvents()) {
            String id = BuiltInRegistries.BLOCK.getKey(e.block()).getPath();
            out.add("E " + (e.gameTime() - base) + " " + e.pos().getX() + " " + e.pos().getY() + " "
                    + e.pos().getZ() + " " + id + " " + e.paramA() + " " + e.paramB());
        }
        return out;
    }

    /**
     * Apply a scenario's actions for one tick. The oracle drives these
     * against a real {@code ServerLevel}; the same semantics are routed into
     * the redstone engine here rather than re-stated, so a change to
     * {@link RedstoneScenarios} cannot silently diverge between the two.
     */
    private static void apply(TickLoop loop, RedstoneScenarios.Scenario s, int tick) {
        for (RedstoneScenarios.Action a : s.actions()) {
            if (a.tick() != tick) {
                continue;
            }
            dev.kestrel.redstone.VanillaRedstone redstone = loop.redstone();
            switch (a) {
                case RedstoneScenarios.SetBlockAction set -> redstone.setBlock(
                        new BlockPos(set.x(), set.y(), set.z()), set.state(), set.flags());
                case RedstoneScenarios.UseAction use -> {
                    BlockPos pos = new BlockPos(use.x(), use.y(), use.z());
                    BlockState st = loop.getBlockState(pos);
                    if (st.getBlock() instanceof LeverBlock) {
                        redstone.leverPull(pos, st);
                    } else if (st.getBlock() instanceof ButtonBlock button) {
                        redstone.buttonPress(pos, st, ticksToStayPressed(button));
                    } else if (st.is(Blocks.REDSTONE_WIRE)) {
                        redstone.wireUseWithoutItem(pos, st);
                    } else {
                        throw new IllegalStateException("Cannot USE block at " + pos + ": " + st);
                    }
                }
                case RedstoneScenarios.ItemAction item -> {
                    // RedstoneScenarios.ItemAction.apply: Container.setItem (whose
                    // setChanged pokes comparators once), then an explicit
                    // Level.updateNeighbourForOutputSignal (a second poke).
                    BlockPos pos = new BlockPos(item.x(), item.y(), item.z());
                    if (redstone.containerSetItem(pos, item.slot(), item.stack().copy())) {
                        redstone.updateNeighbourForOutputSignal(pos, loop.getBlockState(pos).getBlock());
                    }
                }
            }
        }
    }

    /**
     * Apply the {@code S} section's {@code BE} lines to the live block
     * entities: {@code output=<n>} to a comparator, {@code slot<i>=<id>:<count>}
     * to a container. Raw state, no setChanged side effects.
     */
    private static void loadBlockEntities(TickLoop loop, List<String> beLines) {
        for (String line : beLines) {
            String[] parts = line.split(" ");
            BlockPos pos = new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]));
            String kv = parts[4];
            int eq = kv.indexOf('=');
            String key = kv.substring(0, eq);
            String value = kv.substring(eq + 1);
            net.minecraft.world.level.block.entity.BlockEntity be = loop.blockEntities().get(pos);
            if (key.equals("output")
                    && be instanceof net.minecraft.world.level.block.entity.ComparatorBlockEntity comparator) {
                comparator.setOutputSignal(Integer.parseInt(value));
            } else if (key.startsWith("slot") && be instanceof net.minecraft.world.Container container) {
                int colon = value.lastIndexOf(':');
                Identifier id = Identifier.tryParse(value.substring(0, colon));
                int count = Integer.parseInt(value.substring(colon + 1));
                net.minecraft.world.item.Item it = BuiltInRegistries.ITEM.getValue(id);
                container.setItem(Integer.parseInt(key.substring(4)), new net.minecraft.world.item.ItemStack(it, count));
            } else {
                throw new IllegalStateException("BE line with no matching block entity: " + line);
            }
        }
    }

    /**
     * {@code ButtonBlock.ticksToStayPressed}: a private field on the block,
     * derived here from the block set type the same way the vanilla block
     * definitions do (stone-family 20, wooden 30).
     */
    private static int ticksToStayPressed(ButtonBlock button) {
        return button == Blocks.STONE_BUTTON || button == Blocks.POLISHED_BLACKSTONE_BUTTON ? 20 : 30;
    }

    /** Pending block ticks inside the recorded box, in vanilla drain order. */
    private static List<String> queue(TickLoop loop, long base) {
        List<String> out = new ArrayList<>();
        for (TickLoop.PendingBlockTick t : loop.dumpBlockTicks()) {
            if (RedstoneScenarios.inBox(t.pos())) {
                out.add(RedstoneScenarios.blockTickLine(t.pos(), t.block(),
                        t.triggerTick() - base, t.priority().getValue(), t.subTickOrder()));
            }
        }
        return out;
    }

    // =====================================================================
    // Fixture parsing
    // =====================================================================

    /**
     * The fixture's first {@code q} line's {@code subTickOrder}, used to align
     * the sub-tick counter's origin (see the class javadoc). Zero when the
     * scenario never records a pending tick, which leaves the counter at
     * vanilla's own starting value.
     */
    static long subTickSeed(List<String> fixture) {
        for (String line : fixture) {
            if (line.startsWith("q ")) {
                String[] parts = line.trim().split(" ");
                return Long.parseLong(parts[parts.length - 1]);
            }
        }
        return 0;
    }

    /** The {@code S} section's cells, keyed like {@code Recorder.last}. */
    static Map<Long, BlockState> parseSettled(List<String> fixture) {
        Map<Long, BlockState> out = new HashMap<>();
        boolean inSettled = false;
        for (String line : fixture) {
            if (line.equals("S")) {
                inSettled = true;
                continue;
            }
            if (line.startsWith("T ")) {
                break;
            }
            if (inSettled && line.startsWith("C ")) {
                String[] parts = line.substring(2).split(" ", 4);
                int x = Integer.parseInt(parts[0]);
                int y = Integer.parseInt(parts[1]);
                int z = Integer.parseInt(parts[2]);
                out.put(BlockPos.asLong(x, y, z), parseState(parts[3]));
            }
        }
        return out;
    }

    /** The {@code S} section's {@code BE} lines, echoed back unchanged. */
    static List<String> parseBlockEntityLines(List<String> fixture) {
        List<String> out = new ArrayList<>();
        boolean inSettled = false;
        for (String line : fixture) {
            if (line.equals("S")) {
                inSettled = true;
                continue;
            }
            if (line.startsWith("T ")) {
                break;
            }
            if (inSettled && line.startsWith("BE ")) {
                out.add(line);
            }
        }
        return out;
    }

    /**
     * Parse a {@code BlockState.toString()} rendering, the form the recorder
     * writes: {@code Block{minecraft:redstone_wire}[east=side,power=0,...]}.
     */
    static BlockState parseState(String text) {
        int open = text.indexOf('{');
        int close = text.indexOf('}', open);
        if (open < 0 || close < 0) {
            throw new IllegalArgumentException("not a block state: " + text);
        }
        Identifier id = Identifier.tryParse(text.substring(open + 1, close));
        Block block = id == null ? null : BuiltInRegistries.BLOCK.getValue(id);
        if (block == null) {
            throw new IllegalArgumentException("unknown block in: " + text);
        }
        BlockState state = block.defaultBlockState();
        int props = text.indexOf('[', close);
        if (props < 0) {
            return state;
        }
        String body = text.substring(props + 1, text.lastIndexOf(']'));
        if (body.isEmpty()) {
            return state;
        }
        for (String pair : body.split(",")) {
            int eq = pair.indexOf('=');
            String name = pair.substring(0, eq);
            String value = pair.substring(eq + 1);
            Property<?> property = block.getStateDefinition().getProperty(name);
            if (property == null) {
                throw new IllegalArgumentException("unknown property " + name + " in: " + text);
            }
            state = withValue(state, property, value);
        }
        return state;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState withValue(BlockState state, Property<?> property, String value) {
        Property raw = property;
        java.util.Optional<?> parsed = raw.getValue(value);
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("bad value " + value + " for property " + property.getName());
        }
        return state.setValue(raw, (Comparable) parsed.get());
    }

    // =====================================================================
    // Diffing
    // =====================================================================

    /** First differing line with a little context, or null when identical. */
    static String firstDiff(List<String> expected, List<String> actual) {
        int n = Math.min(expected.size(), actual.size());
        for (int i = 0; i < n; i++) {
            if (!expected.get(i).equals(actual.get(i))) {
                return "at line " + (i + 1) + " (" + tickOf(expected, i) + ")\n      vanilla: " + expected.get(i)
                        + "\n      kestrel: " + actual.get(i);
            }
        }
        if (expected.size() != actual.size()) {
            return "length vanilla=" + expected.size() + " kestrel=" + actual.size()
                    + (n < expected.size() ? "\n      vanilla next: " + expected.get(n)
                    : "\n      kestrel next: " + actual.get(n));
        }
        return null;
    }

    private static String tickOf(List<String> lines, int idx) {
        for (int i = idx; i >= 0; i--) {
            if (lines.get(i).startsWith("T ")) {
                return lines.get(i);
            }
        }
        return "S";
    }
}
