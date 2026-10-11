package dev.kestrel.loop;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import net.minecraft.world.ticks.TickPriority;

import java.util.ArrayList;
import java.util.List;

import dev.kestrel.blockentity.SleeperRegistry;

/**
 * Conformance battery for Phase A plus Stage 0 hooks and the Stage 1
 * facade ({@link TickLoop} + {@link KestrelLevel} + {@link EventHooks}):
 * the in-process tick loop with no networking. Mechanics internals keep
 * vanilla types at the seam; the two facade groups below exercise the
 * Kestrel-typed public path.
 *
 * <p>What is proven (all headless, real vanilla classes):
 *
 * <ol>
 *   <li>Settled lever line: a 41-wire line powered at one end settles to the
 *       exact vanilla gradient (15, 14, ... down to 0, then 0) within 60
 *       ticks, every commit went through the real setBlock path
 *       (setBlockCount &gt; 0), and the trace stays flat for 20 further
 *       ticks (settled network does no work).</li>
 *   <li>Lever-off-collapse: flipping the lever off collapses the line back
 *       to all-zero.</li>
 *   <li>Torch line: a torch-powered 10-wire line settles with the head wire
 *       at 15 and a strictly non-increasing gradient.</li>
 *   <li>Climbing steps: wire stepped up through stone keeps power flowing
 *       (above/below conductor rule) and the top wire is powered.</li>
 *   <li>Sleep/wake wiring: a registered block entity sleeps after its idle
 *       report, wakes through the registry fan-in on its signal, and
 *       unknown signals do not wake it.</li>
 *   <li>AC flag seam: flag OFF settles the line to the vanilla gradient;
 *       flag ON settles the same final powers on the settled corpus (the
 *       seam routes without breaking the default result); the flag defaults
 *       OFF.</li>
 *   <li>Hook scaffolding (8th group): zero-subscriber mask clear with the
 *       settled gradient unaffected; registration order preserved with veto
 *       observable to later handlers; {@code BlockChange} cancel commits
 *       the write but vetoes fan-out; {@code BlockTick} cancel consumes
 *       the entry without running the body.</li>
 *   <li>Section store: unset reads air, set/get round-trips, section keys
 *       split at x=16, overwrite returns the previous state.</li>
 *   <li>Facade world layer (9th group): chunk read/write round-trips through
 *       the owning level, block-entity records reserve positions with empty
 *       bodies, a second dimension instance is isolated (same coordinates do
 *       not share sleep state), and the facade setBlock path settles the
 *       powered line to the same gradient.</li>
 *   <li>Facade hook events (10th group): {@code BlockChange} handlers see
 *       facade handles (no vanilla type named), {@code BlockTick} handlers
 *       see position plus tick target, and a {@code BlockTick} veto through
 *       the facade schedule path consumes the entry without fanning out.</li>
 * </ol>
 *
 * <p>Negative control: a naive assertion that the line settles in ONE tick
 * must fail (propagation is multi-tick through the dispatch stack); the
 * battery asserts settle-tick count is between 2 and 60, so a vacuous
 * instant-settle port fails loudly.
 *
 * <p>Exits non-zero on any mismatch.
 */
public final class TickLoopCheck {
    private TickLoopCheck() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        List<String> failures = new ArrayList<>();
        checkStore(failures);
        checkSettledLine(failures);
        checkLeverOff(failures);
        checkTorchLine(failures);
        checkSteps(failures);
        checkSleepWake(failures);
        checkAcSeam(failures);
        checkHooks(failures);
        checkFacade(failures);
        checkFacadeEvents(failures);

        System.out.println("TickLoopCheck: " + (failures.isEmpty() ? "PASS" : "FAIL"));
        if (!failures.isEmpty()) {
            for (String f : failures) {
                System.out.println("  " + f);
            }
            System.exit(1);
        }
    }

    // ---- fixture builders (mirror the redstone-line S/T/P arms) ----

    /** Stone floor at y=0 plus a wire line at y=1, x in [x0,x1], z fixed. */
    static TickLoop buildLine(int x0, int x1, int z) {
        SectionStore store = new SectionStore(-64, 384);
        TickLoop loop = new TickLoop(store);
        for (int x = x0 - 2; x <= x1 + 2; x++) {
            loop.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
        }
        BlockState dot = Blocks.REDSTONE_WIRE.defaultBlockState();
        for (int x = x0; x <= x1; x++) {
            loop.setBlock(new BlockPos(x, 1, z), dot, 3);
        }
        resolveWires(loop, x0, x1, z);
        return loop;
    }

    /**
     * Resolve connection sides over the line via real vanilla updateShape,
     * through the loop's own live reader (the KestrelBlockGetter snapshot
     * adapter is retired: same serving rule, live store, no copy).
     */
    static void resolveWires(TickLoop loop, int x0, int x1, int z) {
        for (int x = x0; x <= x1; x++) {
            BlockPos pos = new BlockPos(x, 1, z);
            BlockState current = loop.getBlockState(pos);
            for (Direction dir : new Direction[]{
                    Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP}) {
                BlockPos npos = pos.relative(dir);
                BlockState updated = current.updateShape(
                        loop.levelReader(),
                        loop.noOpTicks(),
                        pos, dir, npos, loop.getBlockState(npos),
                        net.minecraft.util.RandomSource.create(0));
                if (updated != current) {
                    loop.setBlock(pos, updated, 3);
                    current = updated;
                }
            }
        }
    }

    /** Powered lever at (x0-1, 1, z) facing east, then the wire line. */
    static TickLoop buildPoweredLine(int x0, int x1, int z) {
        TickLoop loop = buildLine(x0, x1, z);
        BlockState lever = Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.POWERED, true)
                .setValue(LeverBlock.FACING, Direction.EAST)
                .setValue(LeverBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR);
        BlockPos leverPos = new BlockPos(x0 - 1, 1, z);
        loop.setBlock(new BlockPos(x0 - 1, 0, z), Blocks.STONE.defaultBlockState(), 3);
        loop.setBlock(leverPos, lever, 3);
        loop.updatePowerAt(new BlockPos(x0, 1, z));
        loop.drainDispatch();
        return loop;
    }

    /** Run ticks until the wire powers stop changing or the cap hits. Returns ticks run. */
    static int settle(TickLoop loop, List<BlockPos> wires, int cap) {
        int ticks = 0;
        while (ticks < cap) {
            String before = powers(loop, wires);
            loop.tick();
            ticks++;
            if (powers(loop, wires).equals(before)) {
                break;
            }
        }
        return ticks;
    }

    static String powers(TickLoop loop, List<BlockPos> wires) {
        StringBuilder sb = new StringBuilder();
        for (BlockPos pos : wires) {
            BlockState state = loop.getBlockState(pos);
            int p = state.is(Blocks.REDSTONE_WIRE)
                    ? state.getValue(RedStoneWireBlock.POWER) : -1;
            sb.append(p).append(',');
        }
        return sb.toString();
    }

    static List<BlockPos> lineWires(int x0, int x1, int z) {
        List<BlockPos> out = new ArrayList<>();
        for (int x = x0; x <= x1; x++) {
            out.add(new BlockPos(x, 1, z));
        }
        return out;
    }

    static int powerAt(TickLoop loop, BlockPos pos) {
        return loop.getBlockState(pos).getValue(RedStoneWireBlock.POWER);
    }

    static void expect(boolean cond, String what, List<String> failures) {
        if (!cond) {
            failures.add(what);
        }
    }

    // ---- checks ----

    private static void checkStore(List<String> failures) {
        SectionStore store = new SectionStore(-64, 384);
        BlockPos pos = new BlockPos(3, 65, -7);
        expect(store.get(pos).is(Blocks.AIR), "store: unset reads air", failures);
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState prev = store.set(pos, stone);
        expect(prev.is(Blocks.AIR), "store: first set returns air", failures);
        expect(store.get(pos) == stone, "store: set/get round-trips", failures);
        expect(store.isWritten(pos), "store: written flag set", failures);
        BlockState prev2 = store.set(pos, Blocks.DIRT.defaultBlockState());
        expect(prev2 == stone, "store: overwrite returns previous", failures);
        // Section split at x=16.
        BlockPos a = new BlockPos(15, 64, 0);
        BlockPos b = new BlockPos(16, 64, 0);
        store.set(a, stone);
        store.set(b, stone);
        expect(SectionStore.keyFor(a) != SectionStore.keyFor(b),
                "store: x=15 and x=16 split sections", failures);
        expect(store.sectionCount() >= 2, "store: two sections live", failures);

        // Step 2b: imported palette storage reads without a dense cell
        // array and promotes exactly once on the first live mutation.
        SectionStore compact = new SectionStore(-64, 384);
        BlockPos compactPos = new BlockPos(2, 66, 3);
        long compactKey = SectionStore.keyFor(compactPos);
        int compactIndex = SectionStore.indexFor(compactPos);
        long[] packed = new long[256]; // 4 bits, 16 cells per long
        packed[compactIndex / 16] |= 1L << ((compactIndex % 16) * 4);
        compact.putCompact(compactKey,
                new BlockState[]{Blocks.AIR.defaultBlockState(), stone}, packed, 4);
        expect(compact.isCompact(compactKey), "store: compact section adopted", failures);
        expect(compact.get(compactPos) == stone, "store: compact read", failures);
        expect(compact.isWritten(compactPos), "store: compact non-air is written", failures);
        expect(compact.writtenCells() == 1, "store: compact written count", failures);
        expect(compact.writtenBitset(compactKey) == null,
                "store: compact has no dense written bitset", failures);
        BlockState compactPrev = compact.set(compactPos, Blocks.DIRT.defaultBlockState());
        expect(compactPrev == stone, "store: compact promotion returns previous", failures);
        expect(!compact.isCompact(compactKey) && compact.denseSectionCount() == 1,
                "store: first write promotes to dense", failures);
        expect(compact.get(compactPos).is(Blocks.DIRT),
                "store: promoted write round-trips", failures);
        expect(compact.get(new BlockPos(1, 66, 3)).is(Blocks.AIR),
                "store: promotion preserves air", failures);
    }

    private static void checkSettledLine(List<String> failures) {
        int x0 = -20, x1 = 20, z = -4;
        TickLoop loop = buildPoweredLine(x0, x1, z);
        List<BlockPos> wires = lineWires(x0, x1, z);
        expect(loop.setBlockCount() > 0, "line: corpus build committed via setBlock", failures);
        int ticks = settle(loop, wires, 60);
        // Negative control: the cascade does real multi-step dispatch work
        // (neighbor notifications fired), never zero-dispatch. An instant
        // port that skips dispatch would show zero dispatches here.
        expect(loop.neighborDispatchCount() > wires.size(),
                "line: only " + loop.neighborDispatchCount() + " dispatches for "
                        + wires.size() + " wires (no dispatch skipped)",
                failures);
        expect(ticks < 60, "line: settle took " + ticks + " ticks, expected <60", failures);
        // Vanilla gradient: 15 down by one per wire, floored at 0.
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i <= x1 - x0; i++) {
            expected.append(Math.max(0, 15 - i)).append(',');
        }
        expect(powers(loop, wires).equals(expected.toString()),
                "line: gradient " + powers(loop, wires) + ", expected " + expected, failures);
        // Settled network does no further work: 20 more ticks, no changes, no new writes.
        long writesBefore = loop.setBlockCount();
        for (int i = 0; i < 20; i++) {
            loop.tick();
        }
        expect(powers(loop, wires).equals(expected.toString()),
                "line: settled trace moved after 20 idle ticks", failures);
        expect(loop.setBlockCount() == writesBefore,
                "line: settled network wrote " + (loop.setBlockCount() - writesBefore) + " more blocks", failures);
    }

    private static void checkLeverOff(List<String> failures) {
        int x0 = -20, x1 = 20, z = -4;
        TickLoop loop = buildPoweredLine(x0, x1, z);
        List<BlockPos> wires = lineWires(x0, x1, z);
        settle(loop, wires, 60);
        // Flip the lever off and re-seed propagation from the head wire.
        BlockPos leverPos = new BlockPos(x0 - 1, 1, z);
        BlockState lever = loop.getBlockState(leverPos);
        loop.setBlock(leverPos, lever.setValue(LeverBlock.POWERED, false), 3);
        loop.updatePowerAt(new BlockPos(x0, 1, z));
        loop.drainDispatch();
        settle(loop, wires, 60);
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i <= x1 - x0; i++) {
            expected.append("0,");
        }
        expect(powers(loop, wires).equals(expected.toString()),
                "lever-off: powers " + powers(loop, wires) + ", expected all zero", failures);
    }

    private static void checkTorchLine(List<String> failures) {
        TickLoop loop = buildLine(1, 10, 8);
        loop.setBlock(new BlockPos(0, 0, 8), Blocks.STONE.defaultBlockState(), 3);
        loop.setBlock(new BlockPos(0, 1, 8), Blocks.REDSTONE_TORCH.defaultBlockState(), 3);
        List<BlockPos> wires = lineWires(1, 10, 8);
        loop.updatePowerAt(new BlockPos(1, 1, 8));
        loop.drainDispatch();
        settle(loop, wires, 60);
        int head = powerAt(loop, new BlockPos(1, 1, 8));
        expect(head == 15, "torch: head wire at " + head + ", expected 15", failures);
        int prev = 16;
        for (BlockPos pos : wires) {
            int p = powerAt(loop, pos);
            expect(p <= prev, "torch: gradient non-increasing at " + pos + " (" + p + " > " + prev + ")", failures);
            prev = p;
        }
    }

    private static void checkSteps(List<String> failures) {
        SectionStore store = new SectionStore(-64, 384);
        TickLoop loop = new TickLoop(store);
        // Mirror the P arm: wire at y=1, stone + wire stepping up.
        loop.setBlock(new BlockPos(0, 0, 12), Blocks.STONE.defaultBlockState(), 3);
        loop.setBlock(new BlockPos(0, 1, 12), Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
        loop.setBlock(new BlockPos(1, 1, 12), Blocks.STONE.defaultBlockState(), 3);
        loop.setBlock(new BlockPos(1, 2, 12), Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
        loop.setBlock(new BlockPos(2, 2, 12), Blocks.STONE.defaultBlockState(), 3);
        loop.setBlock(new BlockPos(2, 3, 12), Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
        loop.setBlock(new BlockPos(3, 3, 12), Blocks.STONE.defaultBlockState(), 3);
        loop.setBlock(new BlockPos(3, 4, 12), Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
        loop.setBlock(new BlockPos(-1, 0, 12), Blocks.STONE.defaultBlockState(), 3);
        BlockState lever = Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.POWERED, true)
                .setValue(LeverBlock.FACING, Direction.EAST)
                .setValue(LeverBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR);
        loop.setBlock(new BlockPos(-1, 1, 12), lever, 3);
        List<BlockPos> wires = List.of(
                new BlockPos(0, 1, 12), new BlockPos(1, 2, 12),
                new BlockPos(2, 3, 12), new BlockPos(3, 4, 12));
        loop.updatePowerAt(new BlockPos(0, 1, 12));
        loop.drainDispatch();
        settle(loop, wires, 60);
        expect(powerAt(loop, new BlockPos(0, 1, 12)) == 15,
                "steps: base wire unpowered", failures);
        expect(powerAt(loop, new BlockPos(3, 4, 12)) > 0,
                "steps: top wire dark (above/below rule broken)", failures);
    }

    private static void checkSleepWake(List<String> failures) {
        SectionStore store = new SectionStore(-64, 384);
        TickLoop loop = new TickLoop(store);
        BlockPos pos = new BlockPos(5, 64, 5);
        loop.registerBlockEntity(pos, SleeperRegistry.Type.FURNACE);
        loop.tick();
        expect(loop.sleepers().isAsleep(pos.asLong()),
                "sleep: idle block entity should sleep after one tick", failures);
        // Unknown signal for the type must not wake it.
        loop.wakeBlockEntity(pos, SleeperRegistry.WakeSignal.SIGN_EDITOR_WRITE);
        expect(loop.sleepers().isAsleep(pos.asLong()),
                "sleep: wrong-type signal must not wake", failures);
        loop.wakeBlockEntity(pos, SleeperRegistry.WakeSignal.FURNACE_SLOT_OTHER);
        loop.tick();
        expect(loop.sleepers().isAsleep(pos.asLong()),
                "sleep: woken body reports idle, should sleep again", failures);
        expect(loop.sleepers().firedSignals(SleeperRegistry.Type.FURNACE)
                        .contains(SleeperRegistry.WakeSignal.FURNACE_SLOT_OTHER),
                "sleep: fuel-only wake should hit the coverage ledger", failures);
    }

    private static void checkAcSeam(List<String> failures) {
        int x0 = -20, x1 = 20, z = -4;
        // Flag defaults OFF.
        TickLoop off = buildPoweredLine(x0, x1, z);
        expect(!off.acFlag().enabled(), "ac: flag should default OFF", failures);
        List<BlockPos> wires = lineWires(x0, x1, z);
        settle(off, wires, 60);
        String offPowers = powers(off, wires);
        // Flag ON: same settled finals on the settled corpus.
        TickLoop on = buildLine(x0, x1, z);
        on.acFlag().setEnabled(true);
        BlockState lever = Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.POWERED, true)
                .setValue(LeverBlock.FACING, Direction.EAST)
                .setValue(LeverBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR);
        on.setBlock(new BlockPos(x0 - 1, 0, z), Blocks.STONE.defaultBlockState(), 3);
        on.setBlock(new BlockPos(x0 - 1, 1, z), lever, 3);
        on.updatePowerAt(new BlockPos(x0, 1, z));
        on.drainDispatch();
        settle(on, wires, 60);
        expect(powers(on, wires).equals(offPowers),
                "ac: flag-ON finals " + powers(on, wires) + " != flag-OFF " + offPowers, failures);
        // Order config is honored (no throw, order retained).
        on.acFlag().setOrder(dev.kestrel.redstone.AcUpdateOrder.VERTICAL_FIRST_OUTWARD);
        expect(on.acFlag().order() == dev.kestrel.redstone.AcUpdateOrder.VERTICAL_FIRST_OUTWARD,
                "ac: order config should stick", failures);
    }

    private static void checkHooks(List<String> failures) {
        // Zero subscribers: mask clear, settled gradient unaffected.
        TickLoop bare = buildPoweredLine(-20, 20, -4);
        expect(bare.hooks().mask() == 0, "hooks: zero-subscriber mask should be 0", failures);
        List<BlockPos> wires = lineWires(-20, 20, -4);
        settle(bare, wires, 60);
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i <= 40; i++) {
            expected.append(Math.max(0, 15 - i)).append(',');
        }
        expect(powers(bare, wires).equals(expected.toString()),
                "hooks: zero-subscriber gradient moved: " + powers(bare, wires), failures);

        // Registration order: first-registered fires first; a later handler
        // still runs after an earlier cancel (veto is observable).
        TickLoop ordered = new TickLoop(new SectionStore(-64, 384));
        StringBuilder order = new StringBuilder();
        ordered.hooks().onBlockChange((loop, e) -> {
            order.append('a');
            e.cancel();
        });
        ordered.hooks().onBlockChange((loop, e) -> {
            order.append(e.cancelled() ? 'B' : 'b');
        });
        BlockPos p = new BlockPos(0, 64, 0);
        boolean accepted = ordered.setBlock(p, Blocks.STONE.defaultBlockState(), 3);
        expect(!accepted, "hooks: cancelled write should report veto", failures);
        expect(order.toString().equals("aB"),
                "hooks: order saw '" + order + "', expected aB", failures);
        expect(ordered.getBlockState(p).is(Blocks.STONE),
                "hooks: cancelled write must stay committed", failures);
        expect(ordered.neighborDispatchCount() == 0,
                "hooks: cancelled write must not fan out", failures);

        // BlockChange cancel vetoes propagation, not the commit: vetoing
        // wire writes leaves the head wire committed at 15 (the write
        // stands) while the second wire stays dark (no fan-out reached it).
        // The handler reads only facade handles; the wire-kind check crosses
        // the seam through the loop's vanilla-typed read (test code, not
        // plugin code, may name vanilla types).
        TickLoop veto = buildLine(-20, 20, -4);
        // The exact Level.setBlock shape pass can legitimately dispatch while
        // constructing the fixture. This assertion concerns only the vetoed action.
        veto.hooks().onBlockChange((loop, e) -> {
            if (loop.getBlockState(e.pos().toVanilla()).getBlock() == Blocks.REDSTONE_WIRE) {
                e.cancel();
            }
        });
        BlockState lever = Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.POWERED, true)
                .setValue(LeverBlock.FACING, Direction.EAST)
                .setValue(LeverBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR);
        veto.setBlock(new BlockPos(-21, 0, -4), Blocks.STONE.defaultBlockState(), 3);
        veto.setBlock(new BlockPos(-21, 1, -4), lever, 3);
        veto.resetNeighborDispatchCount();
        veto.updatePowerAt(new BlockPos(-20, 1, -4));
        veto.drainDispatch();
        settle(veto, wires, 60);
        expect(powerAt(veto, new BlockPos(-20, 1, -4)) == 15,
                "hooks: vetoed head-wire write should stay committed at 15", failures);
        expect(powerAt(veto, new BlockPos(-19, 1, -4)) == 0,
                "hooks: veto must stop propagation past the head wire", failures);
        expect(veto.neighborDispatchCount() == 0,
                "hooks: vetoed cascade dispatched " + veto.neighborDispatchCount(), failures);

        // BlockTick cancel consumes the entry without running the body. The
        // torch stands on a redstone block, so its body (RedstoneTorchBlock.tick)
        // turns it off with flags 3, which fans out; a vanilla torch tick with
        // nothing to change writes nothing. Fan-out count therefore
        // distinguishes body-ran from body-skipped.
        TickLoop tickVeto = buildLine(1, 10, 8);
        BlockPos torchPos = new BlockPos(0, 1, 8);
        tickVeto.setBlock(new BlockPos(0, 0, 8), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        tickVeto.setBlock(torchPos, Blocks.REDSTONE_TORCH.defaultBlockState(), 3);
        tickVeto.hooks().onBlockTick((loop, e) -> e.cancel());
        tickVeto.scheduleTick(torchPos, Blocks.REDSTONE_TORCH, 1,
                net.minecraft.world.ticks.TickPriority.NORMAL);
        long dispatchesBefore = tickVeto.neighborDispatchCount();
        for (int i = 0; i < 4; i++) {
            tickVeto.tick();
        }
        expect(tickVeto.neighborDispatchCount() == dispatchesBefore,
                "hooks: vetoed torch tick must not fan out", failures);
        expect(!tickVeto.willTickThisTick(torchPos, Blocks.REDSTONE_TORCH),
                "hooks: vetoed entry must be consumed, not re-queued", failures);
        // Positive control: same setup without the veto fans out (body ran).
        TickLoop tickControl = buildLine(1, 10, 8);
        tickControl.setBlock(new BlockPos(0, 0, 8), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        tickControl.setBlock(torchPos, Blocks.REDSTONE_TORCH.defaultBlockState(), 3);
        tickControl.scheduleTick(torchPos, Blocks.REDSTONE_TORCH, 1,
                net.minecraft.world.ticks.TickPriority.NORMAL);
        long controlBefore = tickControl.neighborDispatchCount();
        for (int i = 0; i < 4; i++) {
            tickControl.tick();
        }
        expect(tickControl.neighborDispatchCount() > controlBefore,
                "hooks: unvetoed torch tick should fan out (positive control)", failures);
    }

    private static void checkFacade(List<String> failures) {
        // Chunk read/write through the owning level, Kestrel types only.
        KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
        KestrelChunk chunk = level.chunk(0, 0);
        KestrelPos pos = new KestrelPos(3, 65, -7);
        KestrelState stone = KestrelState.wrap(Blocks.STONE.defaultBlockState());
        level.setBlock(pos, stone);
        expect(level.isWritten(pos), "facade: written flag set", failures);
        expect(chunk.get(3, 65, -7).unwrap() == stone.unwrap(),
                "facade: chunk read sees level write", failures);
        KestrelState dirt = KestrelState.wrap(Blocks.DIRT.defaultBlockState());
        KestrelState prev = chunk.set(3, 65, -7, dirt);
        expect(prev.unwrap() == stone.unwrap(),
                "facade: chunk overwrite returns previous", failures);
        expect(level.getBlockState(pos).unwrap() == dirt.unwrap(),
                "facade: level read sees chunk write", failures);
        expect(chunk.dimension() == KestrelLevel.Dimension.OVERWORLD,
                "facade: chunk carries its dimension", failures);

        // Block-entity records: positions reserved, bodies empty.
        KestrelPos bePos = new KestrelPos(5, 64, 5);
        level.registerBlockEntity(bePos, SleeperRegistry.Type.FURNACE);
        KestrelLevel.BlockEntityRecord rec = level.blockEntityAt(bePos);
        expect(rec != null && rec.type() == SleeperRegistry.Type.FURNACE,
                "facade: block-entity record reserved", failures);
        expect(level.blockEntityAt(new KestrelPos(6, 64, 5)) == null,
                "facade: unregistered position has no record", failures);

        // Per-dimension isolation: same coordinates, separate sleep state.
        KestrelLevel nether = new KestrelLevel(KestrelLevel.Dimension.NETHER, -64, 384);
        nether.registerBlockEntity(bePos, SleeperRegistry.Type.FURNACE);
        TickLoop overLoop = new TickLoop(level);
        TickLoop netherLoop = new TickLoop(nether);
        overLoop.tick();
        expect(level.isAsleep(bePos), "facade: overworld ticker sleeps", failures);
        expect(!nether.isAsleep(bePos), "facade: nether ticker unaffected", failures);
        netherLoop.tick();
        expect(nether.isAsleep(bePos), "facade: nether ticker sleeps on its own loop", failures);

        // Facade setBlock path settles the powered line to the same gradient.
        KestrelLevel lineLevel = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
        TickLoop lineLoop = new TickLoop(lineLevel);
        KestrelState stoneState = KestrelState.wrap(Blocks.STONE.defaultBlockState());
        KestrelState dotState = KestrelState.wrap(Blocks.REDSTONE_WIRE.defaultBlockState());
        for (int x = -22; x <= 22; x++) {
            lineLoop.setBlock(new KestrelPos(x, 0, -4), stoneState, 3);
        }
        for (int x = -20; x <= 20; x++) {
            lineLoop.setBlock(new KestrelPos(x, 1, -4), dotState, 3);
        }
        resolveWires(lineLoop, -20, 20, -4);
        KestrelState leverState = KestrelState.wrap(Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.POWERED, true)
                .setValue(LeverBlock.FACING, Direction.EAST)
                .setValue(LeverBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR));
        lineLoop.setBlock(new KestrelPos(-21, 0, -4), stoneState, 3);
        lineLoop.setBlock(new KestrelPos(-21, 1, -4), leverState, 3);
        lineLoop.updatePowerAt(new KestrelPos(-20, 1, -4));
        lineLoop.drainDispatch();
        List<BlockPos> wires = lineWires(-20, 20, -4);
        settle(lineLoop, wires, 60);
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i <= 40; i++) {
            expected.append(Math.max(0, 15 - i)).append(',');
        }
        expect(powers(lineLoop, wires).equals(expected.toString()),
                "facade: facade-built line gradient " + powers(lineLoop, wires), failures);
    }

    private static void checkFacadeEvents(List<String> failures) {
        // BlockChange handlers see facade handles only: compile-time proof is
        // the handler body below naming no vanilla type; runtime proof is the
        // veto still committing the write while stopping fan-out.
        TickLoop loop = new TickLoop(new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384));
        StringBuilder seen = new StringBuilder();
        loop.hooks().onBlockChange((l, e) -> {
            seen.append(e.pos().x()).append(',').append(e.flags()).append(';');
            e.cancel();
        });
        KestrelPos p = new KestrelPos(0, 64, 0);
        KestrelState stone = KestrelState.wrap(Blocks.STONE.defaultBlockState());
        boolean accepted = loop.setBlock(p, stone, 3);
        expect(!accepted, "facade-events: cancelled facade write should report veto", failures);
        expect(seen.toString().equals("0,3;"),
                "facade-events: handler saw '" + seen + "'", failures);
        expect(loop.level().isWritten(p),
                "facade-events: vetoed facade write stays committed", failures);
        expect(loop.neighborDispatchCount() == 0,
                "facade-events: vetoed facade write must not fan out", failures);

        // BlockTick handlers see position plus tick target through the facade
        // schedule path; a veto consumes the entry without fanning out.
        TickLoop tickLoop = new TickLoop(new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384));
        tickLoop.setBlock(new KestrelPos(0, 0, 8), stone, 3);
        tickLoop.setBlock(new KestrelPos(0, 1, 8),
                KestrelState.wrap(Blocks.REDSTONE_TORCH.defaultBlockState()), 3);
        StringBuilder tickSeen = new StringBuilder();
        tickLoop.hooks().onBlockTick((l, e) -> {
            tickSeen.append(e.pos().x()).append(',').append(e.target().kind()).append(';');
            e.cancel();
        });
        KestrelPos torchPos = new KestrelPos(0, 1, 8);
        tickLoop.scheduleTick(torchPos,
                new KestrelTickTarget(KestrelTickTarget.Kind.REDSTONE_TORCH,
                        KestrelTickTarget.Priority.NORMAL),
                1);
        long before = tickLoop.neighborDispatchCount();
        for (int i = 0; i < 4; i++) {
            tickLoop.tick();
        }
        expect(tickSeen.toString().equals("0,REDSTONE_TORCH;"),
                "facade-events: tick handler saw '" + tickSeen + "'", failures);
        expect(tickLoop.neighborDispatchCount() == before,
                "facade-events: vetoed facade tick must not fan out", failures);
        expect(!tickLoop.willTickThisTick(torchPos,
                new KestrelTickTarget(KestrelTickTarget.Kind.REDSTONE_TORCH,
                        KestrelTickTarget.Priority.NORMAL)),
                "facade-events: vetoed facade entry must be consumed", failures);
    }

    /** Connection-side sanity: the resolved line wires connect east-west. */
    static void checkConnections(TickLoop loop, int x0, int x1, int z, List<String> failures) {
        BlockPos mid = new BlockPos((x0 + x1) / 2, 1, z);
        BlockState state = loop.getBlockState(mid);
        expect(state.getValue(RedStoneWireBlock.WEST).isConnected()
                        && state.getValue(RedStoneWireBlock.EAST).isConnected(),
                "line: mid wire should connect W+E, got " + state, failures);
        RedstoneSide north = state.getValue(RedStoneWireBlock.NORTH);
        expect(north == RedstoneSide.NONE,
                "line: mid wire north should be NONE, got " + north, failures);
    }
}
