package dev.kestrel.net;

import dev.kestrel.loop.KestrelLevel;
import dev.kestrel.loop.TickLoop;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Gate for G20 slice 1: placing the held item ({@link BlockPlacing}), over real loopback
 * sessions. Held items, clicked faces, cursors and yaws are set up here; every expected block
 * state, packet and rejection is derived by hand from the vanilla source
 * ({@code ServerGamePacketListenerImpl.handleUseItemOn}, {@code ServerPlayerGameMode.useItemOn},
 * {@code BlockItem.place}, and each block's own {@code getStateForPlacement} / {@code setPlacedBy}),
 * not from the code under test.
 *
 * <p>The work area is the stone floor at y = 0, x and z in [-8, 8]. The placer stands at
 * (0.5, 1, 0.5), eye at y = 2.62, facing south (yaw 0); a second player (the watcher) is parked
 * at (7.5, 1, 7.5). Every scenario owns its own cells so none depends on another's leftovers.
 *
 * <p>Runs in its own JVM (it binds item prototypes and block tags).
 */
public final class BlockPlacingCheck {
    private BlockPlacingCheck() {
    }

    private static final long SEED = 4242424242L;
    private static final int DOWN = 0;
    private static final int UP = 1;
    private static final int NORTH = 2;
    private static final int SOUTH = 3;
    private static final int WEST = 4;
    private static final int EAST = 5;
    // Assigned in main once the registries are bootstrapped.
    private static BlockState STONE;
    private static BlockState AIR;
    private static BlockState VOID_AIR;
    private static final boolean VERBOSE = Boolean.getBoolean("kestrel.verbose");

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        STONE = Blocks.STONE.defaultBlockState();
        AIR = Blocks.AIR.defaultBlockState();
        VOID_AIR = Blocks.VOID_AIR.defaultBlockState();
        List<String> failures = new ArrayList<>();

        Path packDir = Path.of(args.length > 0 ? args[0] : System.getProperty("kestrel.datapack",
                System.getProperty("user.home") + "/kestrel-run/packdump/data"));
        if (!Files.isDirectory(packDir)) {
            System.out.println("BlockPlacingCheck: FAIL");
            System.out.println("  no vanilla datapack at " + packDir);
            System.exit(1);
        }
        List<Datapack.RegistryEntries> packs = Datapack.load(packDir, SyncedRegistries.KEYS,
                ServedTags.blockStaticOrder());
        int bound = ServedTags.bindBlockTags(packs);
        expect(bound > 100, "tags: bound " + bound + " block tags", failures);
        NetCheck.bindItemPrototypes();

        try (Rig r = Rig.open("Place", null)) {
            run("plain", failures, () -> plain(r, failures));
            run("consume", failures, () -> consume(r, failures));
            run("offhand", failures, () -> offhand(r, failures));
            run("replaceable", failures, () -> replaceable(r, failures));
            run("stairs", failures, () -> stairs(r, failures));
            run("logs", failures, () -> logs(r, failures));
            run("slabs", failures, () -> slabs(r, failures));
            run("doors", failures, () -> doors(r, failures));
            run("bed", failures, () -> bed(r, failures));
            run("torch", failures, () -> torch(r, failures));
            run("containers", failures, () -> containers(r, failures));
            run("boneMeal", failures, () -> boneMeal(r, failures));
            run("repeater", failures, () -> repeater(r, failures));
            run("tripwire", failures, () -> tripwire(r, failures));
            run("reach", failures, () -> reach(r, failures));
            run("cursor", failures, () -> cursor(r, failures));
            run("actionTime", failures, () -> actionTime(r, failures));
            run("cooldown", failures, () -> cooldown(r, failures));
            run("hitbox", failures, () -> hitbox(r, failures));
            run("entityBlocks", failures, () -> entityBlocks(r, failures));
            run("teleport", failures, () -> pendingTeleport(r, failures));
            run("limits", failures, () -> limits(r, failures));
            run("standIn", failures, () -> standInLevel(r, failures));
            run("notLoaded", failures, () -> notLoaded(r, failures));
            run("ordinals", failures, () -> ordinals(r, failures));
            run("ackMax", failures, () -> ackMax(r, failures));
            run("border", failures, () -> border(r, failures));
            run("scaffolding", failures, () -> scaffolding(r, failures));
            run("levelSeams", failures, () -> levelSeams(r, failures));
        }
        try (Rig r = Rig.open("Spawn", "spawn-protection=2\n")) {
            run("spawn", failures, () -> spawnProtection(r, failures));
        }

        System.out.println("BlockPlacingCheck: " + (failures.isEmpty() ? "PASS" : "FAIL"));
        if (!failures.isEmpty()) {
            for (String f : failures) {
                System.out.println("  " + f);
            }
            System.exit(1);
        }
    }

    private interface Scenario {
        void run() throws Exception;
    }

    private static void run(String name, List<String> failures, Scenario scenario) {
        System.out.println("-- " + name);
        try {
            scenario.run();
        } catch (Throwable t) {
            failures.add(name + ": threw " + t);
            t.printStackTrace(System.out);
        }
    }

    // ================================================================ scenarios

    /** A stone on the top face of the floor: placed, one consumed, ITEM_USED counted, no swing. */
    private static void plain(Rig r, List<String> f) throws Exception {
        r.hold(Items.STONE, 64);
        BlockPos floor = new BlockPos(3, 0, 3);
        BlockPos cell = floor.above();
        Click c = r.click(floor, UP);
        expect(r.at(cell) == STONE, "plain: stone placed above the floor, got " + r.at(cell), f);
        expect(r.held() == 63, "plain: one stone consumed, held " + r.held(), f);
        expect(r.used(Items.STONE) == 1, "plain: ITEM_USED counted once, got " + r.used(Items.STONE), f);
        expect(c.mine.contains(upd(floor, STONE)) && c.mine.contains(upd(cell, STONE)),
                "plain: both immediate block updates reach the placer, saw " + c.mine, f);
        expect(c.mine.contains("slot 36 minecraft:stone x63"), "plain: the hotbar slot shows 63, saw " + c.mine, f);
        expect(c.mine.stream().noneMatch(s -> s.startsWith("animate")),
                "plain: a placement (client swing) sends no animate packet, saw " + c.mine, f);
        expect(c.mine.contains("ack " + c.seq), "plain: acked with its sequence, saw " + c.mine, f);
        expect(c.theirs.equals(List.of(upd(cell, STONE))),
                "plain: the watcher sees exactly the placed block, saw " + c.theirs, f);
        order(c, "plain", f, upd(floor, STONE), upd(cell, STONE), "ack " + c.seq);
    }

    /** Consumption down to an empty hand, then an empty-hand click places nothing. */
    private static void consume(Rig r, List<String> f) throws Exception {
        r.hold(Items.STONE, 2);
        BlockPos a = new BlockPos(-2, 0, 2);
        BlockPos b = new BlockPos(-3, 0, 2);
        r.click(a, UP);
        expect(r.at(a.above()) == STONE && r.held() == 1, "consume: first of two placed, held " + r.held(), f);
        Click last = r.click(b, UP);
        expect(r.at(b.above()) == STONE && r.held() == 0, "consume: second placed, hand empty, held " + r.held(), f);
        expect(last.mine.contains("slot 36 empty"), "consume: the client's slot is emptied, saw " + last.mine, f);
        BlockPos c = new BlockPos(-2, 0, 3);
        Click none = r.click(c, UP);
        expect(r.at(c.above()) == AIR, "consume: an empty hand places nothing, got " + r.at(c.above()), f);
        expect(none.mine.equals(List.of(upd(c, STONE), upd(c.above(), AIR), "ack " + none.seq)),
                "consume: empty hand still answers with both block updates then the ack, saw " + none.mine, f);
        expect(none.theirs.isEmpty(), "consume: the watcher sees nothing, saw " + none.theirs, f);
    }

    /** The offhand places when it is the clicked hand; the main hand is untouched. */
    private static void offhand(Rig r, List<String> f) throws Exception {
        r.setMain(ItemStack.EMPTY);
        r.setOff(new ItemStack(Items.STONE, 5));
        BlockPos a = new BlockPos(2, 0, -3);
        Click c = r.click(1, a, UP);
        expect(r.at(a.above()) == STONE, "offhand: stone placed from the offhand, got " + r.at(a.above()), f);
        expect(r.off().getCount() == 4, "offhand: offhand 4 left, got " + r.off().getCount(), f);
        expect(c.mine.contains("slot 45 minecraft:stone x4"), "offhand: client slot 45 shows 4, saw " + c.mine, f);

        r.setMain(new ItemStack(Items.DIRT, 3));
        BlockPos b = new BlockPos(3, 0, -3);
        r.click(0, b, UP);
        expect(r.at(b.above()) == Blocks.DIRT.defaultBlockState(), "offhand: main hand places its own dirt, got " + r.at(b.above()), f);
        expect(r.held() == 2 && r.off().getCount() == 4, "offhand: main 2 and offhand 4, got " + r.held() + "/" + r.off().getCount(), f);

        BlockPos d = new BlockPos(4, 0, -3);
        r.click(1, d, UP);
        expect(r.at(d.above()) == STONE && r.off().getCount() == 3 && r.held() == 2,
                "offhand: offhand click places stone, not the held dirt", f);
    }

    /** A replaceable clicked block is replaced in place; a replaceable cell above a solid one too. */
    private static void replaceable(Rig r, List<String> f) throws Exception {
        r.hold(Items.STONE, 8);
        BlockState grass = Blocks.SHORT_GRASS.defaultBlockState();
        BlockPos g1 = new BlockPos(-3, 1, -3);
        r.set(g1, grass);
        Click c = r.click(g1, UP);
        expect(r.at(g1) == STONE, "replace: clicking the grass replaces it in place, got " + r.at(g1), f);
        expect(r.at(g1.above()) == AIR, "replace: nothing built above it, got " + r.at(g1.above()), f);
        expect(c.mine.contains(upd(g1, STONE)) && c.mine.contains(upd(g1.above(), AIR)),
                "replace: updates name the grass cell and the cell above, saw " + c.mine, f);

        BlockPos g2 = new BlockPos(-4, 1, -3);
        r.set(g2, grass);
        r.click(g2.below(), UP);
        expect(r.at(g2) == STONE, "replace: clicking the floor under grass places over it, got " + r.at(g2), f);
        expect(r.held() == 6, "replace: two stones consumed, held " + r.held(), f);
    }

    private static BlockState stairs(Direction facing, Half half) {
        return Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, half);
    }

    /** Stairs: facing from the placer's yaw, half from the face and the click height. */
    private static void stairs(Rig r, List<String> f) throws Exception {
        r.hold(Items.OAK_STAIRS, 32);
        r.turn(0f);
        BlockPos a = new BlockPos(4, 0, -2);
        r.click(a, UP);
        expect(r.at(a.above()) == stairs(Direction.SOUTH, Half.BOTTOM),
                "stairs: yaw 0 on a floor faces south, bottom half, got " + r.at(a.above()), f);

        BlockPos support = new BlockPos(-3, 1, -2);
        r.set(support, STONE);
        r.click(0, support, EAST, 1.0f, 0.75f, 0.5f);
        expect(r.at(support.east()) == stairs(Direction.SOUTH, Half.TOP),
                "stairs: side click above the middle gives the top half, got " + r.at(support.east()), f);

        BlockPos support2 = new BlockPos(-3, 1, 1);
        r.set(support2, STONE);
        r.click(0, support2, EAST, 1.0f, 0.25f, 0.5f);
        expect(r.at(support2.east()) == stairs(Direction.SOUTH, Half.BOTTOM),
                "stairs: side click below the middle gives the bottom half, got " + r.at(support2.east()), f);

        BlockPos ceiling = new BlockPos(4, 2, 2);
        r.set(ceiling, STONE);
        r.click(ceiling, DOWN);
        expect(r.at(ceiling.below()) == stairs(Direction.SOUTH, Half.TOP),
                "stairs: a click on the underside gives the top half, got " + r.at(ceiling.below()), f);

        r.turn(90f);
        BlockPos w = new BlockPos(-4, 0, 4);
        r.click(w, UP);
        expect(r.at(w.above()) == stairs(Direction.WEST, Half.BOTTOM),
                "stairs: yaw 90 faces west, got " + r.at(w.above()), f);
        r.turn(180f);
        BlockPos n = new BlockPos(4, 0, 4);
        r.click(n, UP);
        expect(r.at(n.above()) == stairs(Direction.NORTH, Half.BOTTOM),
                "stairs: yaw 180 faces north, got " + r.at(n.above()), f);
        r.turn(-90f);
        BlockPos e = new BlockPos(-4, 0, -4);
        r.click(e, UP);
        expect(r.at(e.above()) == stairs(Direction.EAST, Half.BOTTOM),
                "stairs: yaw -90 faces east, got " + r.at(e.above()), f);
        r.turn(0f);
    }

    private static BlockState log(Direction.Axis axis) {
        return Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, axis);
    }

    /** Logs take their axis from the clicked face. */
    private static void logs(Rig r, List<String> f) throws Exception {
        r.hold(Items.OAK_LOG, 16);
        BlockPos top = new BlockPos(0, 0, 3);
        r.click(top, UP);
        expect(r.at(top.above()) == log(Direction.Axis.Y), "logs: top face gives the y axis, got " + r.at(top.above()), f);
        BlockPos support = new BlockPos(-1, 1, -4);
        r.set(support, STONE);
        r.click(support, SOUTH);
        expect(r.at(support.south()) == log(Direction.Axis.Z),
                "logs: south face gives the z axis, got " + r.at(support.south()), f);
        r.click(support, EAST);
        expect(r.at(support.east()) == log(Direction.Axis.X),
                "logs: east face gives the x axis, got " + r.at(support.east()), f);
    }

    private static BlockState slab(SlabType type) {
        return Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, type);
    }

    /** Slabs: half from the click, doubling, and where a side click on a half-slab goes. */
    private static void slabs(Rig r, List<String> f) throws Exception {
        r.hold(Items.OAK_SLAB, 32);
        BlockPos a = new BlockPos(1, 0, 2);
        r.click(a, UP);
        expect(r.at(a.above()) == slab(SlabType.BOTTOM), "slabs: floor top gives a bottom slab, got " + r.at(a.above()), f);
        r.click(a.above(), UP);
        expect(r.at(a.above()) == slab(SlabType.DOUBLE), "slabs: a slab on a bottom slab doubles it, got " + r.at(a.above()), f);
        expect(r.at(a.above(2)) == AIR, "slabs: nothing placed above the double, got " + r.at(a.above(2)), f);

        BlockPos b = new BlockPos(4, 0, 1);
        r.click(b, UP);
        BlockPos half = b.above();
        r.click(0, half, EAST, 1.0f, 0.25f, 0.5f);
        expect(r.at(half) == slab(SlabType.BOTTOM) && r.at(half.east()) == slab(SlabType.BOTTOM),
                "slabs: a lower-half side click goes to the neighbour (bottom), got " + r.at(half) + " / " + r.at(half.east()), f);
        r.click(0, half, WEST, 0.0f, 0.75f, 0.5f);
        expect(r.at(half) == slab(SlabType.DOUBLE),
                "slabs: an upper-half side click replaces the bottom slab (double), got " + r.at(half), f);

        BlockPos ceiling = new BlockPos(-4, 2, 1);
        r.set(ceiling, STONE);
        r.click(ceiling, DOWN);
        BlockPos top = ceiling.below();
        expect(r.at(top) == slab(SlabType.TOP), "slabs: the underside gives a top slab, got " + r.at(top), f);
        r.click(top, DOWN);
        expect(r.at(top) == slab(SlabType.DOUBLE), "slabs: a click on a top slab's underside doubles it, got " + r.at(top), f);
    }

    private static BlockState door(Direction facing, DoorHingeSide hinge, DoubleBlockHalf half) {
        return Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, facing).setValue(DoorBlock.HINGE, hinge)
                .setValue(DoorBlock.HALF, half);
    }

    /** Doors: both halves, the hinge from the neighbours and the click side, refused when the top is blocked. */
    private static void doors(Rig r, List<String> f) throws Exception {
        r.hold(Items.OAK_DOOR, 16);
        BlockPos a = new BlockPos(3, 0, -1);
        Click c = r.click(0, a, UP, 0.25f, 1.0f, 0.5f);
        BlockPos pa = a.above();
        expect(r.at(pa) == door(Direction.SOUTH, DoorHingeSide.RIGHT, DoubleBlockHalf.LOWER),
                "doors: click on the west half of the cell gives a right hinge, got " + r.at(pa), f);
        expect(r.at(pa.above()) == door(Direction.SOUTH, DoorHingeSide.RIGHT, DoubleBlockHalf.UPPER),
                "doors: the upper half is written by setPlacedBy, got " + r.at(pa.above()), f);
        expect(r.held() == 15, "doors: one door item consumed, held " + r.held(), f);
        expect(c.theirs.contains(upd(pa, door(Direction.SOUTH, DoorHingeSide.RIGHT, DoubleBlockHalf.LOWER)))
                        && c.theirs.contains(upd(pa.above(), door(Direction.SOUTH, DoorHingeSide.RIGHT, DoubleBlockHalf.UPPER))),
                "doors: the watcher sees both halves, saw " + c.theirs, f);

        BlockPos b = new BlockPos(-3, 0, 0);
        r.click(0, b, UP, 0.75f, 1.0f, 0.5f);
        expect(r.at(b.above()) == door(Direction.SOUTH, DoorHingeSide.LEFT, DoubleBlockHalf.LOWER),
                "doors: click on the east half gives a left hinge, got " + r.at(b.above()), f);

        // A full block on the right (west for a south-facing door) wins over the click side.
        r.set(new BlockPos(-4, 1, -1), STONE);
        BlockPos cpos = new BlockPos(-3, 0, -1);
        r.click(0, cpos, UP, 0.75f, 1.0f, 0.5f);
        expect(r.at(cpos.above()) == door(Direction.SOUTH, DoorHingeSide.RIGHT, DoubleBlockHalf.LOWER),
                "doors: a full block on the right forces a right hinge, got " + r.at(cpos.above()), f);

        // A full block on the left (east) forces a left hinge whatever the click side.
        r.set(new BlockPos(3, 1, 0), STONE);
        BlockPos dpos = new BlockPos(2, 0, 0);
        r.click(0, dpos, UP, 0.25f, 1.0f, 0.5f);
        expect(r.at(dpos.above()) == door(Direction.SOUTH, DoorHingeSide.LEFT, DoubleBlockHalf.LOWER),
                "doors: a full block on the left forces a left hinge, got " + r.at(dpos.above()), f);

        // Refused: the cell above is taken.
        BlockPos epos = new BlockPos(3, 0, -4);
        r.set(epos.above(2), STONE);
        int before = r.held();
        Click refused = r.click(epos, UP);
        expect(r.at(epos.above()) == AIR && r.held() == before,
                "doors: refused when the upper cell is blocked, got " + r.at(epos.above()) + " held " + r.held(), f);
        expect(refused.mine.equals(List.of(upd(epos, STONE), upd(epos.above(), AIR), "ack " + refused.seq)),
                "doors: a refusal still answers with the two block updates and the ack, saw " + refused.mine, f);

        // The upper half also needs room under the build limit; covered by limits().
    }

    private static BlockState bed(BedPart part) {
        return Blocks.BED.red().defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH).setValue(BedBlock.PART, part);
    }

    /** Beds: the head goes one cell ahead of the placer; refused when that cell is taken. */
    private static void bed(Rig r, List<String> f) throws Exception {
        r.hold(Items.BED.red(), 1);
        BlockPos foot = new BlockPos(2, 1, 1);
        Click c = r.click(foot.below(), UP);
        expect(r.at(foot) == bed(BedPart.FOOT), "bed: foot placed, got " + r.at(foot), f);
        expect(r.at(foot.south()) == bed(BedPart.HEAD), "bed: head one cell ahead (south), got " + r.at(foot.south()), f);
        expect(r.held() == 0, "bed: the single bed is consumed, held " + r.held(), f);
        r.hold(Items.BED.red(), 1);
        expect(c.theirs.contains(upd(foot, bed(BedPart.FOOT))) && c.theirs.contains(upd(foot.south(), bed(BedPart.HEAD))),
                "bed: the watcher sees both halves, saw " + c.theirs, f);

        BlockPos foot2 = new BlockPos(-1, 1, 3);
        r.set(foot2.south(), STONE);
        Click refused = r.click(foot2.below(), UP);
        expect(r.at(foot2) == AIR && r.held() == 1, "bed: refused when the head cell is taken, got " + r.at(foot2), f);
        expect(refused.mine.equals(List.of(upd(foot2.below(), STONE), upd(foot2, AIR), "ack " + refused.seq)),
                "bed: refusal answers with two updates and the ack, saw " + refused.mine, f);
    }

    /** Torches: standing on top, a wall torch against a lone side support, refused in mid-air. */
    private static void torch(Rig r, List<String> f) throws Exception {
        r.hold(Items.TORCH, 8);
        BlockPos a = new BlockPos(1, 0, -2);
        r.click(a, UP);
        expect(r.at(a.above()) == Blocks.TORCH.defaultBlockState(), "torch: standing torch on the floor, got " + r.at(a.above()), f);

        BlockPos support = new BlockPos(1, 1, 5);
        r.set(support, STONE);
        r.click(support, NORTH);
        expect(r.at(support.north()) == Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.NORTH),
                "torch: wall torch on the stone's north face faces north, got " + r.at(support.north()), f);

        int before = r.held();
        BlockPos air = new BlockPos(1, 3, 3);
        Click c = r.click(air, UP);
        expect(r.at(air) == AIR && r.at(air.above()) == AIR && r.held() == before,
                "torch: refused with nothing to hold it, got " + r.at(air) + " held " + r.held(), f);
        expect(c.mine.equals(List.of(upd(air, AIR), upd(air.above(), AIR), "ack " + c.seq)),
                "torch: refusal answers with two updates and the ack, saw " + c.mine, f);
    }

    /** Hopper facing, a barrel's facing, and block entity data from the item. */
    private static void containers(Rig r, List<String> f) throws Exception {
        r.hold(Items.HOPPER, 8);
        BlockPos a = new BlockPos(-2, 0, 4);
        r.click(a, UP);
        BlockState hopperDown = Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN);
        expect(r.at(a.above()) == hopperDown, "containers: hopper on a floor faces down, got " + r.at(a.above()), f);
        expect(r.blockEntity(a.above()) instanceof net.minecraft.world.level.block.entity.HopperBlockEntity,
                "containers: the hopper has a block entity, got " + r.blockEntity(a.above()), f);

        BlockPos support = new BlockPos(-3, 1, 5);
        r.set(support, STONE);
        r.click(support, NORTH);
        expect(r.at(support.north()) == Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.SOUTH),
                "containers: a north-face click gives a hopper facing south, got " + r.at(support.north()), f);

        // BlockItem.place (:~90-110) loads BLOCK_ENTITY_DATA first and the item's components after
        // (updateBlockEntityComponents), and the barrel item carries CONTAINER = EMPTY by default
        // (Items.BARREL), so BaseContainerBlockEntity.applyImplicitComponents (:156) empties whatever
        // the raw data held. Contents come from the CONTAINER component.
        ItemStack raw = new ItemStack(Items.BARREL, 2);
        CompoundTag entry = new CompoundTag();
        entry.putByte("Slot", (byte) 3);
        entry.putString("id", "minecraft:stone");
        entry.putInt("count", 5);
        ListTag items = new ListTag();
        items.add(entry);
        CompoundTag data = new CompoundTag();
        data.put("Items", items);
        raw.set(DataComponents.BLOCK_ENTITY_DATA,
                TypedEntityData.of(net.minecraft.world.level.block.entity.BlockEntityTypes.BARREL, data));
        r.setMain(raw);
        BlockPos b = new BlockPos(3, 0, 2);
        r.click(b, UP);
        expect(r.at(b.above()) == Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.NORTH),
                "containers: a barrel faces opposite the placer's look (north), got " + r.at(b.above()), f);
        BlockEntity rawEntity = r.blockEntity(b.above());
        expect(rawEntity instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity rawBarrel
                        && rawBarrel.getItem(3).isEmpty(),
                "containers: raw block entity data Items are overwritten by the empty CONTAINER default, got "
                        + (rawEntity instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity x ? x.getItem(3) : rawEntity), f);

        ItemStack filled = new ItemStack(Items.BARREL, 2);
        java.util.List<ItemStack> contents = new java.util.ArrayList<>();
        contents.add(new ItemStack(Items.STONE, 5));
        filled.set(DataComponents.CONTAINER,
                net.minecraft.world.item.component.ItemContainerContents.fromItems(contents));
        r.setMain(filled);
        BlockPos b2 = new BlockPos(5, 0, 2);
        r.click(b2, UP);
        BlockEntity filledEntity = r.blockEntity(b2.above());
        expect(filledEntity instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity barrelEntity
                        && barrelEntity.getItem(0).is(Items.STONE) && barrelEntity.getItem(0).getCount() == 5
                        && barrelEntity.getItem(1).isEmpty(),
                "containers: the CONTAINER component fills the barrel from slot 0, got " + filledEntity, f);
    }

    /** Bone meal: the SERVER swing source (two animate packets), one consumed, level event 1505. */
    private static void boneMeal(Rig r, List<String> f) throws Exception {
        r.hold(Items.BONE_MEAL, 3);
        BlockPos wheat = new BlockPos(-2, 1, -3);
        r.set(wheat, Blocks.WHEAT.defaultBlockState());
        Click c = r.click(wheat, UP);
        BlockState grown = r.at(wheat);
        int age = grown.is(Blocks.WHEAT) ? grown.getValue(net.minecraft.world.level.block.CropBlock.AGE) : -1;
        expect(age >= 2 && age <= 5, "boneMeal: wheat grew by 2 to 5 stages, age " + age, f);
        expect(r.held() == 2, "boneMeal: one consumed, held " + r.held(), f);
        String swing = "animate " + r.p.entityId + " 0";
        expect(c.mine.stream().filter(swing::equals).count() == 2,
                "boneMeal: a SERVER-sourced success swings twice (:1377 and :1387), saw " + c.mine, f);
        String event = "event 1505@" + wheat.asLong() + ":15";
        expect(c.mine.contains(event), "boneMeal: the placer gets level event 1505, saw " + c.mine, f);
        expect(c.theirs.contains(event), "boneMeal: the watcher gets level event 1505, saw " + c.theirs, f);
        expect(c.theirs.stream().filter(swing::equals).count() == 2,
                "boneMeal: the watcher sees both swings, saw " + c.theirs, f);
        expect(r.used(Items.BONE_MEAL) == 1, "boneMeal: ITEM_USED counted, got " + r.used(Items.BONE_MEAL), f);
    }

    /** A repeater next to a redstone block schedules its first tick from setPlacedBy. */
    private static void repeater(Rig r, List<String> f) throws Exception {
        r.hold(Items.REPEATER, 2);
        BlockPos floor = new BlockPos(1, 0, -4);
        BlockPos cell = floor.above();
        r.set(cell.north(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        // DiodeBlock.updateNeighborsInFront (:181-186) tells the block in front of the output and,
        // through updateNeighborsAtExceptFromFacing, everything around that block but the repeater's
        // own side. The lamp sits two cells in front, so only that call reaches it.
        BlockPos front = cell.south();
        BlockPos lamp = front.south();
        r.set(front, STONE);
        r.set(lamp, Blocks.REDSTONE_LAMP.defaultBlockState());
        r.click(floor, UP);
        BlockState placed = r.at(cell);
        expect(placed.is(Blocks.REPEATER) && placed.getValue(RepeaterBlock.FACING) == Direction.NORTH,
                "repeater: placed facing north (input behind it), got " + placed, f);
        r.pump(8);
        expect(r.at(cell).is(Blocks.REPEATER) && r.at(cell).getValue(RepeaterBlock.POWERED),
                "repeater: the scheduled tick powered it, got " + r.at(cell), f);
        expect(r.at(lamp).is(Blocks.REDSTONE_LAMP) && r.at(lamp).getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT),
                "repeater: the powered repeater's front neighbours are updated, so the lamp two cells on lights, got "
                        + r.at(lamp), f);
    }

    /**
     * {@code TripWireHookBlock.setPlacedBy} (:105) runs {@code calculateState}, which for a hook
     * that finds its partner calls {@code notifyNeighbors}: {@code level.updateNeighborsAt} on the
     * partner and on the block behind it. A lit lamp above that block, not touching the partner,
     * hears of it only that way and unlights (a scheduled tick of 4).
     */
    private static void tripwire(Rig r, List<String> f) throws Exception {
        r.hold(Items.TRIPWIRE_HOOK, 4);
        int y = 4;
        int z = -2;
        BlockPos support1 = new BlockPos(-4, y, z);
        BlockPos hook1 = support1.east();
        BlockPos hook2 = new BlockPos(0, y, z);
        BlockPos support2 = hook2.east();
        BlockPos lamp = support1.above();
        BlockState lit = Blocks.REDSTONE_LAMP.defaultBlockState().setValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT, true);
        r.set(support1, STONE);
        r.set(support2, STONE);
        r.set(hook1.east(), Blocks.TRIPWIRE.defaultBlockState());
        r.set(hook1.east().east(), Blocks.TRIPWIRE.defaultBlockState());
        r.turn(90.0f);
        r.click(support1, EAST);
        BlockState first = r.at(hook1);
        expect(first.is(Blocks.TRIPWIRE_HOOK) && first.getValue(net.minecraft.world.level.block.TripWireHookBlock.FACING) == Direction.EAST
                        && !first.getValue(net.minecraft.world.level.block.TripWireHookBlock.ATTACHED),
                "tripwire: the first hook faces east and has no partner yet, got " + first, f);
        r.set(lamp, lit);
        r.pump(8);
        expect(r.at(lamp) == lit, "tripwire: the lamp above the support stays lit while nothing updates it, got " + r.at(lamp), f);
        r.turn(-90.0f);
        r.click(support2, WEST);
        BlockState second = r.at(hook2);
        expect(second.is(Blocks.TRIPWIRE_HOOK) && second.getValue(net.minecraft.world.level.block.TripWireHookBlock.FACING) == Direction.WEST
                        && second.getValue(net.minecraft.world.level.block.TripWireHookBlock.ATTACHED),
                "tripwire: the second hook faces west and is attached, got " + second, f);
        expect(r.at(hook1).getValue(net.minecraft.world.level.block.TripWireHookBlock.ATTACHED),
                "tripwire: the partner is attached too, got " + r.at(hook1), f);
        r.pump(8);
        expect(r.at(lamp).is(Blocks.REDSTONE_LAMP) && !r.at(lamp).getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT),
                "tripwire: the lamp above the partner's support heard the neighbour update and unlit, got " + r.at(lamp), f);
        r.turn(0.0f);
    }

    private static boolean inReach(Rig r, BlockPos pos) {
        Vec3 eye = r.standIn.getEyePosition();
        double d2 = gap(eye.x, pos.getX()) + gap(eye.y, pos.getY()) + gap(eye.z, pos.getZ());
        double range = 4.5 + 1.0;
        return d2 < range * range;
    }

    private static double gap(double v, int cell) {
        double d = v < cell ? cell - v : v > cell + 1 ? v - (cell + 1) : 0.0;
        return d * d;
    }

    /** Reach: {@code isWithinBlockInteractionRange(pos, 1.0)}, computed here from the eye position. */
    private static void reach(Rig r, List<String> f) throws Exception {
        r.hold(Items.STONE, 32);
        BlockPos[] cells = {new BlockPos(5, 0, 3), new BlockPos(5, 0, 4), new BlockPos(6, 0, 0), new BlockPos(5, 0, 0),
                new BlockPos(0, 9, 0)};
        boolean[] expected = {true, false, false, true, false};
        for (int i = 0; i < cells.length; i++) {
            BlockPos cell = cells[i];
            boolean reachable = inReach(r, cell);
            expect(reachable == expected[i], "reach: hand-computed reach of " + cell + " is " + expected[i]
                    + " (eye " + r.standIn.getEyePosition() + ")", f);
            int before = r.held();
            Click c = r.click(cell, UP);
            if (expected[i]) {
                expect(r.at(cell.above()) == STONE && r.held() == before - 1, "reach: " + cell + " in reach places", f);
            } else {
                expect(r.at(cell.above()) == AIR && r.held() == before, "reach: " + cell + " out of reach places nothing", f);
                expect(c.mine.equals(List.of("ack " + c.seq)),
                        "reach: " + cell + " out of reach gets only the ack, saw " + c.mine, f);
                expect(c.theirs.isEmpty(), "reach: nothing for the watcher, saw " + c.theirs, f);
            }
        }
    }

    /** Cursor offset from the block centre must stay below 1.0000001 on each axis. */
    private static void cursor(Rig r, List<String> f) throws Exception {
        r.hold(Items.STONE, 64);
        // The offset is (cursor - 0.5) per axis, in doubles; the limit is 1.0000001. A float step near
        // 1.5 is 1.19e-7 (so one ulp up is already over); near -0.5 it is 5.96e-8 (one ulp down is
        // still under, two is over).
        float up = Math.nextUp(1.5f);
        float insideDown = Math.nextDown(-0.5f);
        float down = Math.nextDown(insideDown);
        record Case(float x, float y, float z, boolean placed, String what) { }
        Case[] cases = {
                new Case(1.5f, 1.0f, 0.5f, true, "x at +1.0 offset"),
                new Case(up, 1.0f, 0.5f, false, "x just past +1.0000001"),
                new Case(0.5f, 1.5f, 0.5f, true, "y at +1.0 offset"),
                new Case(0.5f, up, 0.5f, false, "y just past +1.0000001"),
                new Case(-0.5f, 1.0f, 0.5f, true, "x at -1.0 offset"),
                new Case(down, 1.0f, 0.5f, false, "x just past -1.0000001"),
                new Case(insideDown, 1.0f, 0.5f, true, "x one ulp inside -1.0000001"),
                new Case(0.5f, 1.0f, 1.5f, true, "z at +1.0 offset"),
                new Case(0.5f, 1.0f, up, false, "z just past +1.0000001"),
                new Case(0.5f, down, 0.5f, false, "y just past -1.0000001"),
                new Case(0.5f, 1.0f, down, false, "z just past -1.0000001"),
        };
        for (int i = 0; i < cases.length; i++) {
            Case c = cases[i];
            // Rows z = 3 and z = -3, x from -4 to 4: all well inside the 5.5 block reach.
            BlockPos floor = new BlockPos(i % 9 - 4, 0, i < 9 ? 3 : -3);
            r.set(floor.above(), AIR);
            expect(inReach(r, floor), "cursor: " + floor + " is in reach", f);
            int before = r.held();
            Click click = r.click(0, floor, UP, c.x, c.y, c.z);
            BlockPos cell = floor.above();
            if (c.placed) {
                expect(r.at(cell) == STONE && r.held() == before - 1, "cursor: " + c.what + " places", f);
            } else {
                expect(r.at(cell) == AIR && r.held() == before, "cursor: " + c.what + " is rejected", f);
                expect(click.mine.equals(List.of("ack " + click.seq)),
                        "cursor: " + c.what + " gets only the ack, saw " + click.mine, f);
            }
        }
    }

    /**
     * {@code resetLastActionTime} (the idle timer) runs after the reach and cursor tests and
     * before everything else ({@code :1349}): a click that is out of reach or has a bad cursor
     * leaves it alone, a click that gets that far resets both Kestrel's own tick stamp and the
     * stand-in's millisecond clock, whether or not anything is placed.
     */
    private static void actionTime(Rig r, List<String> f) throws Exception {
        r.hold(Items.STONE, 8);
        java.lang.reflect.Field last = ServerPlayer.class.getDeclaredField("lastActionTime");
        last.setAccessible(true);
        BlockPos floor = freeFloor(r, cell -> true);
        BlockPos farAway = r.standIn.blockPosition().offset(12, -1, 12);

        r.p.lastActionTick = -1;
        last.setLong(r.standIn, 0L);
        r.click(farAway, UP);
        expect(r.p.lastActionTick == -1 && last.getLong(r.standIn) == 0L,
                "actionTime: an out-of-reach click does not reset the idle timer, got " + r.p.lastActionTick
                        + " and " + last.getLong(r.standIn), f);

        r.click(0, floor, UP, 1.6f, 1.0f, 0.5f);
        expect(r.p.lastActionTick == -1 && last.getLong(r.standIn) == 0L,
                "actionTime: a click with a cursor past the limit does not reset the idle timer, got "
                        + r.p.lastActionTick + " and " + last.getLong(r.standIn), f);

        long before = net.minecraft.util.Util.getMillis();
        r.click(floor, UP);
        expect(r.at(floor.above()) == STONE, "actionTime: harness: the click placed", f);
        expect(r.p.lastActionTick > 0 && r.p.lastActionTick <= r.loop.gameTime(),
                "actionTime: a click in reach stamps Kestrel's last action tick, got " + r.p.lastActionTick
                        + " at game time " + r.loop.gameTime(), f);
        expect(last.getLong(r.standIn) >= before && last.getLong(r.standIn) <= net.minecraft.util.Util.getMillis(),
                "actionTime: a click in reach resets the stand-in's last action time, got " + last.getLong(r.standIn)
                        + " from " + before, f);

        // Nothing to place still counts as activity.
        r.p.lastActionTick = -1;
        last.setLong(r.standIn, 0L);
        r.setMain(ItemStack.EMPTY);
        r.click(floor, UP);
        expect(r.p.lastActionTick > 0 && last.getLong(r.standIn) >= before,
                "actionTime: an empty-handed click in reach resets both clocks too, got " + r.p.lastActionTick
                        + " and " + last.getLong(r.standIn), f);
    }

    /** An item on cooldown does not place, and is not a placement attempt at the build limit. */
    private static void cooldown(Rig r, List<String> f) throws Exception {
        r.hold(Items.STONE, 4);
        r.standIn.getCooldowns().addCooldown(new ItemStack(Items.STONE), 400);
        BlockPos floor = new BlockPos(-1, 0, 1);
        Click c = r.click(floor, UP);
        expect(r.at(floor.above()) == AIR && r.held() == 4, "cooldown: nothing placed while on cooldown", f);
        expect(c.mine.equals(List.of(upd(floor, STONE), upd(floor.above(), AIR), "ack " + c.seq)),
                "cooldown: both block updates and the ack, saw " + c.mine, f);
        r.standIn.getCooldowns().removeCooldown(r.standIn.getCooldowns().getCooldownGroup(new ItemStack(Items.STONE)));
        r.click(floor, UP);
        expect(r.at(floor.above()) == STONE, "cooldown: places again once the cooldown is gone", f);
    }

    /** Another player standing in the cell blocks the block; it places once they leave. */
    private static void hitbox(Rig r, List<String> f) throws Exception {
        r.hold(Items.STONE, 8);
        BlockPos cell = new BlockPos(-2, 1, -1);
        r.teleport(r.w, r.other, -1.5, 1.0, -0.5);
        r.pump(3);
        Click c = r.click(cell.below(), UP);
        expect(r.at(cell) == AIR && r.held() == 8, "hitbox: another player in the cell blocks the placement, got " + r.at(cell), f);
        expect(c.mine.equals(List.of(upd(cell.below(), STONE), upd(cell, AIR), "ack " + c.seq)),
                "hitbox: refusal answers with two updates and the ack, saw " + c.mine, f);
        r.teleport(r.w, r.other, 7.5, 1.0, 7.5);
        r.pump(3);
        r.click(cell.below(), UP);
        expect(r.at(cell) == STONE, "hitbox: places once the other player has left, got " + r.at(cell), f);

        // The placer's own box blocks too.
        BlockPos feet = new BlockPos(0, 1, 0);
        int before = r.held();
        r.click(feet.below(), UP);
        expect(r.at(feet) == AIR && r.held() == before, "hitbox: the placer's own box blocks, got " + r.at(feet), f);
    }

    /**
     * A non-player entity in the cell: a falling block has {@code blocksBuilding} and stops the
     * placement ({@code Level.isUnobstructed}), an item entity does not. Both are Kestrel
     * entities reaching the stand-in level as proxies. The click is on the east face of a block
     * five up, so the cell has air under it and the falling block is still falling for the few
     * ticks the clicks take (straight above the support it would land at once).
     */
    private static void entityBlocks(Rig r, List<String> f) throws Exception {
        r.hold(Items.STONE, 8);
        BlockPos support = new BlockPos(-1, 5, -1);
        BlockPos cell = support.east();
        r.set(support, STONE);

        dev.kestrel.entity.EntityWorld world = r.server.entityWorld();
        dev.kestrel.entity.KestrelItemEntity item = world.spawnItem(cell.getX() + 0.5, cell.getY() + 0.1,
                cell.getZ() + 0.5, new ItemStack(Items.DIRT));
        expect(item != null, "entityBlocks: harness: the item entity spawned", f);
        Click c = r.click(support, EAST);
        expect(r.at(cell) == STONE && r.held() == 7, "entityBlocks: an item entity does not block, got " + r.at(cell), f);
        expect(c.mine.size() >= 3 && c.mine.subList(0, 3).equals(List.of(upd(support, STONE), upd(cell, STONE), "ack " + c.seq)),
                "entityBlocks: the item entity's placement answers with the two updates and the ack first, saw " + c.mine, f);
        if (item != null) {
            item.discard();
        }
        r.set(cell, AIR);

        dev.kestrel.entity.KestrelFallingBlockEntity falling = world.spawnFalling(cell, Blocks.SAND.defaultBlockState());
        expect(falling != null, "entityBlocks: harness: the falling block spawned", f);
        Click blocked = r.click(support, EAST);
        expect(falling != null && !falling.isRemoved() && falling.position().y > cell.getY() - 2.0,
                "entityBlocks: harness: the falling block is still in the air near the cell, at "
                        + (falling == null ? null : falling.position()), f);
        expect(r.at(cell) == AIR && r.held() == 7, "entityBlocks: a falling block in the cell blocks the placement, got " + r.at(cell), f);
        expect(blocked.mine.equals(List.of(upd(support, STONE), upd(cell, AIR), "ack " + blocked.seq)),
                "entityBlocks: the refusal answers with two updates and the ack, saw " + blocked.mine, f);
        if (falling != null) {
            falling.discard();
        }
        r.pump(1);
        r.click(support, EAST);
        expect(r.at(cell) == STONE && r.held() == 6, "entityBlocks: places once the falling block is gone, got " + r.at(cell), f);
        r.set(cell, AIR);
        r.set(support, AIR);
    }

    /** A teleport the client has not accepted: the "too high" overlay and the two updates, no placement. */
    private static void pendingTeleport(Rig r, List<String> f) throws Exception {
        r.hold(Items.STONE, 8);
        r.me.accept = false;
        r.teleport(r.p, r.me, r.p.x, r.p.y, r.p.z);
        r.pump(2);
        BlockPos floor = new BlockPos(-1, 0, -1);
        expect(r.p.teleportPending && r.me.lastTeleport > 0,
                "teleport: the server is awaiting the ack (pending " + r.p.teleportPending + ", last id " + r.me.lastTeleport + ")", f);
        Click c = r.click(floor, UP);
        expect(r.at(floor.above()) == AIR && r.held() == 8, "teleport: nothing placed while a teleport is pending", f);
        expect(c.mine.equals(List.of(tooHigh(r), upd(floor, STONE), upd(floor.above(), AIR), "ack " + c.seq)),
                "teleport: overlay, both updates, ack, saw " + c.mine + " want overlay " + tooHigh(r), f);
        r.me.accept = true;
        r.me.c.sendC(PacketIds.PLAY_ACCEPT_TELEPORT, new Wire.Writer().vint(r.me.lastTeleport).toBytes());
        r.pump(3);
        r.click(floor, UP);
        expect(r.at(floor.above()) == STONE, "teleport: places after the teleport is accepted", f);
    }

    private static String tooHigh(Rig r) throws Exception {
        return overlay(Component.translatable("build.tooHigh", r.maxY()).withStyle(net.minecraft.ChatFormatting.RED));
    }

    private static String tooLow(Rig r) throws Exception {
        return overlay(Component.translatable("build.tooLow", r.minY()).withStyle(net.minecraft.ChatFormatting.RED));
    }

    /** The overlay as a client decodes it, so argument types compare as the wire gives them. */
    private static String overlay(Component component) throws Exception {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),
                net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        ClientboundSystemChatPacket.STREAM_CODEC.encode(buf, new ClientboundSystemChatPacket(component, true));
        return "overlay " + ClientboundSystemChatPacket.STREAM_CODEC.decode(buf).content();
    }

    /** Build limits: too high / too low overlays, the double overlay on a failed top-face attempt. */
    private static void limits(Rig r, List<String> f) throws Exception {
        int maxY = r.maxY();
        int minY = r.minY();
        expect(maxY == 319 && minY == -64, "limits: the level spans -64..319, got " + minY + ".." + maxY, f);
        // The placer stands on a block at maxY - 2 so its head (maxY - 1 + 1.8) is below no block;
        // every block placed or set here is off to the side of its column.
        r.set(new BlockPos(0, maxY - 2, 0), STONE);
        r.teleport(r.p, r.me, 0.5, maxY - 1, 0.5);
        r.pump(3);
        r.hold(Items.STONE, 16);

        BlockPos above = new BlockPos(2, maxY + 1, 0);
        Click c = r.click(above, UP);
        expect(c.mine.equals(List.of(tooHigh(r), "ack " + c.seq)),
                "limits: a click above the world gets the overlay and the ack only, saw " + c.mine, f);

        BlockPos top = new BlockPos(2, maxY, 0);
        r.set(top, STONE);
        Click fail = r.click(top, UP);
        expect(r.at(top.above()).isAir() && r.held() == 16, "limits: nothing placed above the limit", f);
        long overlays = fail.mine.stream().filter(tooHigh(r)::equals).count();
        expect(overlays == 2, "limits: a failed top-face attempt at the limit sends the overlay twice, saw " + fail.mine, f);
        expect(fail.mine.contains(upd(top, STONE)) && fail.mine.contains(upd(top.above(), VOID_AIR)),
                "limits: updates name the stone and the void air above it, saw " + fail.mine, f);
        expect(fail.mine.contains("ack " + fail.seq), "limits: acked", f);

        // Not placement attempts: empty hand, a stick, an empty bucket. A water bucket is one.
        r.setMain(ItemStack.EMPTY);
        Click empty = r.click(top, UP);
        expect(empty.mine.stream().noneMatch(s -> s.startsWith("overlay")), "limits: an empty hand sends no overlay, saw " + empty.mine, f);
        r.setMain(new ItemStack(Items.STICK, 1));
        Click stick = r.click(top, UP);
        expect(stick.mine.stream().noneMatch(s -> s.startsWith("overlay")), "limits: a stick sends no overlay, saw " + stick.mine, f);
        r.setMain(new ItemStack(Items.BUCKET, 1));
        Click bucket = r.click(top, UP);
        expect(bucket.mine.stream().noneMatch(s -> s.startsWith("overlay")), "limits: an empty bucket sends no overlay, saw " + bucket.mine, f);
        r.setMain(new ItemStack(Items.WATER_BUCKET, 1));
        Click water = r.click(top, UP);
        expect(water.mine.stream().filter(tooHigh(r)::equals).count() == 2,
                "limits: a water bucket is a placement attempt (two overlays), saw " + water.mine, f);

        // On cooldown a block item is not an attempt.
        r.hold(Items.STONE, 16);
        r.standIn.getCooldowns().addCooldown(new ItemStack(Items.STONE), 400);
        Click cool = r.click(top, UP);
        expect(cool.mine.stream().noneMatch(s -> s.startsWith("overlay")),
                "limits: a stone on cooldown sends no overlay, saw " + cool.mine, f);
        r.standIn.getCooldowns().removeCooldown(r.standIn.getCooldowns().getCooldownGroup(new ItemStack(Items.STONE)));

        // A door needs its upper cell under the limit: on a block at maxY - 1 it would end at maxY + 1.
        BlockPos pillar = new BlockPos(3, maxY - 1, 0);
        r.set(pillar, STONE);
        r.hold(Items.OAK_DOOR, 2);
        Click door = r.click(pillar, UP);
        expect(r.at(pillar.above()).isAir() && r.held() == 2, "limits: a door that would not fit under the limit is refused", f);
        expect(door.mine.contains("ack " + door.seq), "limits: door attempt acked", f);

        // The bottom.
        r.set(new BlockPos(0, minY + 1, 0), STONE);
        r.teleport(r.p, r.me, 0.5, minY + 2, 0.5);
        r.pump(3);
        r.hold(Items.STONE, 16);
        Click below = r.click(new BlockPos(2, minY - 1, 0), UP);
        expect(below.mine.equals(List.of(tooLow(r), "ack " + below.seq)),
                "limits: a click below the world gets the tooLow overlay and the ack only, saw " + below.mine, f);
        BlockPos bottom = new BlockPos(2, minY, 0);
        r.set(bottom, STONE);
        Click down = r.click(bottom, DOWN);
        expect(down.mine.stream().filter(tooLow(r)::equals).count() == 1,
                "limits: a failed bottom-face attempt at the floor sends tooLow once, saw " + down.mine, f);
        expect(down.mine.contains(upd(bottom, STONE)) && down.mine.contains(upd(bottom.below(), VOID_AIR)),
                "limits: updates name the stone and the void air below it, saw " + down.mine, f);

        r.teleport(r.p, r.me, 0.5, 1.0, 0.5);
        r.pump(3);
    }

    /** Spawn protection: the overlay and both updates for a non-operator; an operator places. */
    private static void spawnProtection(Rig r, List<String> f) throws Exception {
        r.hold(Items.STONE, 16);
        // World spawn is (0, 0, 0) and the radius 2: protected when max(|dx|, |dz|) <= 2.
        BlockPos open = new BlockPos(1, 0, 2);
        Click free = r.click(open, UP);
        expect(r.at(open.above()) == STONE, "spawn: with no operators nothing is protected", f);
        expect(free.mine.stream().noneMatch(s -> s.startsWith("overlay")), "spawn: no overlay without operators", f);

        r.server.op(UUID.randomUUID(), "other-op");
        BlockPos guarded = new BlockPos(0, 0, 2);
        Click c = r.click(guarded, UP);
        String message = overlay(Component.translatable("build.spawn_protection", guarded.toShortString())
                .withStyle(net.minecraft.ChatFormatting.RED));
        expect(r.at(guarded.above()) == AIR && r.held() == 15, "spawn: nothing placed inside the radius for a non-operator", f);
        expect(c.mine.equals(List.of(message, upd(guarded, STONE), upd(guarded.above(), AIR), "ack " + c.seq)),
                "spawn: overlay, both updates, ack, saw " + c.mine + " want " + message, f);

        BlockPos edge = new BlockPos(-3, 0, 0);
        Click past = r.click(edge, UP);
        expect(r.at(edge.above()) == STONE, "spawn: one past the radius places", f);
        expect(past.mine.stream().noneMatch(s -> s.startsWith("overlay")), "spawn: no overlay past the radius", f);

        r.server.op(r.p.profile, r.p.name);
        BlockPos second = new BlockPos(-2, 0, 1);
        r.click(second, UP);
        expect(r.at(second.above()) == STONE, "spawn: an operator places inside the radius", f);
    }

    // ================================================================ harness

    /** What the clicker and the watcher saw for one click. */
    /**
     * The stand-in level as vanilla's {@code Level} answers: entity queries find the other
     * players (vanilla's entity sections hold them; Kestrel's tracker does), and a position outside
     * the world reads void air, no fluid, and refuses a write ({@code Level.java:225,366,374}).
     */
    private static void standInLevel(Rig r, List<String> f) throws Exception {
        net.minecraft.server.level.ServerLevel level = r.standIn.level();
        ServerPlayer other = r.server.bodies().body(r.w).vanillaPlayer();
        r.teleport(r.w, r.other, 3.5, 1.0, 3.5);
        r.pump(3);
        net.minecraft.world.phys.AABB near = other.getBoundingBox().inflate(0.1);
        net.minecraft.world.phys.AABB both = new net.minecraft.world.phys.AABB(-2, 0.5, -2, 6, 4, 6);
        net.minecraft.world.phys.AABB far = new net.minecraft.world.phys.AABB(-6, 0.5, -6, -4, 4, -4);

        List<net.minecraft.world.entity.Entity> found = level.getEntities((net.minecraft.world.entity.Entity) null, near, e -> true);
        expect(found.size() == 1 && found.get(0) == other, "standIn: a box around the other player finds exactly them, got " + who(found), f);
        found = level.getEntities((net.minecraft.world.entity.Entity) null, both, e -> true);
        expect(found.size() == 2 && found.contains(other) && found.contains(r.standIn),
                "standIn: a box around both finds each once, got " + who(found), f);
        found = level.getEntities(r.standIn, both, e -> true);
        expect(found.size() == 1 && found.get(0) == other, "standIn: except the placer leaves the other player, got " + who(found), f);
        found = level.getEntities(other, both, e -> true);
        expect(found.size() == 1 && found.get(0) == r.standIn, "standIn: except the other player leaves the placer, got " + who(found), f);
        found = level.getEntities((net.minecraft.world.entity.Entity) null, both, e -> false);
        expect(found.isEmpty(), "standIn: a selector that rejects everyone finds no one, got " + who(found), f);
        found = level.getEntities((net.minecraft.world.entity.Entity) null, both, e -> e == other);
        expect(found.size() == 1 && found.get(0) == other, "standIn: the selector picks the other player, got " + who(found), f);
        found = level.getEntities((net.minecraft.world.entity.Entity) null, far, e -> true);
        expect(found.isEmpty(), "standIn: a box away from both finds no one, got " + who(found), f);

        List<ServerPlayer> typed = level.getEntities(
                net.minecraft.world.level.entity.EntityTypeTest.forClass(ServerPlayer.class), both, e -> true);
        expect(typed.size() == 2 && typed.contains(other) && typed.contains(r.standIn),
                "standIn: a typed query finds both players once, got " + who(typed), f);
        typed = level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(ServerPlayer.class), both, e -> e == other);
        expect(typed.size() == 1 && typed.get(0) == other, "standIn: a typed query honours its selector, got " + who(typed), f);
        typed = level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(ServerPlayer.class), far, e -> true);
        expect(typed.isEmpty(), "standIn: a typed query away from both finds no one, got " + who(typed), f);
        List<net.minecraft.world.entity.item.ItemEntity> items = level.getEntities(
                net.minecraft.world.level.entity.EntityTypeTest.forClass(net.minecraft.world.entity.item.ItemEntity.class), both, e -> true);
        expect(items.isEmpty(), "standIn: a typed query for items does not return players, got " + who(items), f);

        // Only the flag matters to the query, so set the field and not setRemoved, whose callbacks
        // belong to a player that is really leaving.
        java.lang.reflect.Field removal = net.minecraft.world.entity.Entity.class.getDeclaredField("removalReason");
        removal.setAccessible(true);
        removal.set(other, net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_WITH_PLAYER);
        found = level.getEntities((net.minecraft.world.entity.Entity) null, both, e -> true);
        typed = level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(ServerPlayer.class), both, e -> true);
        removal.set(other, null);
        expect(found.size() == 1 && found.get(0) == r.standIn && typed.size() == 1 && typed.get(0) == r.standIn,
                "standIn: a removed player is no longer found, got " + who(found) + " and " + who(typed), f);

        // A dead player's connection stays an observer while its body is out of the tracker
        // (removePlayerBody), so the player list can name an observer with no body.
        dev.kestrel.entity.EntityTracker tracker = r.server.entityWorld().tracker();
        dev.kestrel.entity.KestrelEntity otherBody = r.server.bodies().body(r.w);
        java.lang.reflect.Field records = PlayerBodies.class.getDeclaredField("byRecord");
        records.setAccessible(true);
        PlayerBodies.Body otherRecord = (PlayerBodies.Body) ((java.util.Map<?, ?>) records.get(r.server.bodies())).get(r.w);
        expect(tracker.playerBodies().size() == 2 && tracker.playerBodies().contains(otherBody),
                "standIn: harness: both bodies are in the tracker, got " + tracker.playerBodies().size(), f);
        tracker.removePlayerBody(otherRecord.observer);
        try {
            expect(tracker.playerBodies().size() == 1 && !tracker.playerBodies().contains(otherBody),
                    "standIn: a player without a body is not in the body list, got " + tracker.playerBodies().size(), f);
            found = level.getEntities((net.minecraft.world.entity.Entity) null, both, e -> true);
            typed = level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(ServerPlayer.class), both, e -> true);
            expect(found.size() == 1 && found.get(0) == r.standIn && typed.size() == 1 && typed.get(0) == r.standIn,
                    "standIn: a player without a body is skipped, got " + who(found) + " and " + who(typed), f);
        } finally {
            tracker.addPlayerBody(otherBody, otherRecord.observer);
        }
        found = level.getEntities((net.minecraft.world.entity.Entity) null, both, e -> true);
        expect(found.size() == 2, "standIn: the body is found again once it is back, got " + who(found), f);

        BlockState voidAir = Blocks.VOID_AIR.defaultBlockState();
        BlockPos high = new BlockPos(0, r.maxY() + 81, 0);
        r.set(high, Blocks.WATER.defaultBlockState());
        expect(r.at(high).is(Blocks.WATER), "standIn: harness wrote the water above the world, got " + r.at(high), f);
        expect(level.getBlockState(high) == voidAir, "standIn: above the world reads void air, got " + level.getBlockState(high), f);
        expect(level.getFluidState(high).isEmpty(), "standIn: above the world has no fluid, got " + level.getFluidState(high), f);
        BlockPos low = new BlockPos(0, r.minY() - 1, 0);
        expect(level.getBlockState(low) == voidAir, "standIn: below the world reads void air, got " + level.getBlockState(low), f);
        expect(level.getBlockState(new BlockPos(0, r.maxY(), 0)).isAir() && level.getBlockState(new BlockPos(0, r.maxY(), 0)) != voidAir,
                "standIn: the top cell of the world is ordinary air", f);
        expect(level.getBlockState(new BlockPos(0, r.minY(), 0)).isAir() && level.getBlockState(new BlockPos(0, r.minY(), 0)) != voidAir,
                "standIn: the bottom cell of the world is ordinary air", f);
        // Level.isInValidBounds (:177-191) limits x and z by chunk: |chunk| <= ChunkPyramid.MAX_CHUNK_COORDINATE_VALUE.
        int lastChunk = net.minecraft.world.level.chunk.status.ChunkPyramid.MAX_CHUNK_COORDINATE_VALUE;
        int lastX = lastChunk * 16 + 15;
        int firstX = -lastChunk * 16;
        expect(level.getBlockState(new BlockPos(lastX + 1, 1, 0)) == voidAir,
                "standIn: past the last chunk reads void air, got " + level.getBlockState(new BlockPos(lastX + 1, 1, 0)), f);
        expect(level.getBlockState(new BlockPos(0, 1, lastX + 1)) == voidAir,
                "standIn: past the last chunk in z reads void air", f);
        expect(level.getBlockState(new BlockPos(firstX - 1, 1, 0)) == voidAir,
                "standIn: before the first chunk reads void air", f);
        expect(level.getBlockState(new BlockPos(0, 1, firstX - 1)) == voidAir,
                "standIn: before the first chunk in z reads void air", f);
        expect(level.getBlockState(new BlockPos(lastX, 1, 0)) != voidAir && level.getBlockState(new BlockPos(0, 1, lastX)) != voidAir
                && level.getBlockState(new BlockPos(firstX, 1, 0)) != voidAir && level.getBlockState(new BlockPos(0, 1, firstX)) != voidAir,
                "standIn: the last block inside each chunk limit is not void air", f);
        BlockPos refused = new BlockPos(0, r.maxY() + 1, 0);
        expect(!level.setBlock(refused, STONE, 3), "standIn: a write above the world is refused", f);
        expect(r.at(refused).isAir(), "standIn: nothing was stored above the world, got " + r.at(refused), f);
        BlockPos refusedLow = new BlockPos(0, r.minY() - 1, 0);
        expect(!level.setBlock(refusedLow, STONE, 3) && r.at(refusedLow).isAir(),
                "standIn: a write below the world is refused and nothing is stored, got " + r.at(refusedLow), f);
        BlockPos inside = new BlockPos(0, r.maxY(), 0);
        expect(level.setBlock(inside, STONE, 3) && r.at(inside) == STONE,
                "standIn: a write at the top cell is stored, got " + r.at(inside), f);
        level.setBlock(inside, Blocks.AIR.defaultBlockState(), 3);
        r.pump(2);
        r.teleport(r.w, r.other, 7.5, 1.0, 7.5);
        r.pump(3);
    }

    /** A stone floor cell in the placer's reach whose top face is still free, and which {@code where} accepts. */
    private static BlockPos freeFloor(Rig r, java.util.function.Predicate<BlockPos> where) {
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                BlockPos candidate = new BlockPos(x, 0, z);
                if (inReach(r, candidate) && r.at(candidate) == STONE && r.at(candidate.above()).isAir()
                        && where.test(candidate)) {
                    return candidate;
                }
            }
        }
        throw new IllegalStateException("harness: no free floor cell in reach");
    }

    /**
     * A face or hand ordinal outside the enum is a decode failure and a disconnect in vanilla
     * ({@code ServerboundUseItemOnPacket} reads them with {@code readEnum} and {@code readVarInt}
     * through {@code InteractionHand}/{@code Direction} codecs); Kestrel keeps the session, acks
     * and does nothing else. The ack is the only packet, and no block changes.
     */
    private static void ordinals(Rig r, List<String> f) throws Exception {
        r.hold(Items.STONE, 16);
        int[][] cases = {{2, UP}, {-1, UP}, {0, 6}, {0, -1}, {1, 6}, {0, 100}, {100, UP}};
        for (int[] c : cases) {
            BlockPos floor = freeFloor(r, cell -> true);
            int seq = ++r.seq;
            Click click = r.clickRaw(new Raw(c[0], floor, c[1], seq, false));
            String what = "ordinals: hand " + c[0] + " face " + c[1];
            expect(r.at(floor.above()).isAir() && r.held() == 16, what + " places nothing, got " + r.at(floor.above()), f);
            expect(click.mine.equals(List.of("ack " + seq)), what + " gets only the ack, saw " + click.mine, f);
            expect(click.theirs.isEmpty(), what + ": nothing for the watcher, saw " + click.theirs, f);
        }
    }

    /**
     * {@code ackBlockChangesUpTo} keeps the highest sequence and is sent once per tick
     * ({@code ServerGamePacketListenerImpl.java:1474-1480}, flushed in {@code tick}), so two clicks in
     * one tick get one ack carrying the larger sequence whatever the arrival order.
     */
    private static void ackMax(Rig r, List<String> f) throws Exception {
        r.hold(Items.STONE, 16);
        BlockPos floor = freeFloor(r, cell -> true);
        int high = r.seq + 10;
        int low = r.seq + 5;
        r.seq = high;
        Click click = r.clickRaw(new Raw(0, floor, UP, high, false), new Raw(0, floor, UP, low, false));
        List<String> acks = new ArrayList<>();
        for (String packet : click.mine) {
            if (packet.startsWith("ack ")) {
                acks.add(packet);
            }
        }
        expect(acks.equals(List.of("ack " + high)), "ackMax: one ack with the larger sequence " + high
                + " although " + low + " came last, saw " + click.mine, f);
        expect(r.at(floor.above()) == STONE, "ackMax: the first click placed, got " + r.at(floor.above()), f);
        Click later = r.clickRaw(new Raw(0, floor, UP, high + 1, false));
        expect(later.mine.contains("ack " + (high + 1)), "ackMax: the next tick acks its own sequence, saw " + later.mine, f);
    }

    /**
     * {@code ServerLevel.mayInteract} also asks the world border ({@code ServerLevel.java:871-873}).
     * Kestrel serves vanilla's default border, which no reachable cell is outside of, so the test
     * moves the stand-in level's border for the clicks: a 12-block square whose east edge runs
     * through the placer's reach (x &lt; 0 is inside). The watcher steps well inside first, since a
     * player past the border's damage buffer would be hurt, and the stand-in has no damage sources yet.
     */
    private static void border(Rig r, List<String> f) throws Exception {
        r.hold(Items.STONE, 16);
        r.teleport(r.w, r.other, -8.5, 1.0, 0.5);
        r.pump(3);
        net.minecraft.world.level.border.WorldBorder border = r.standIn.level().getWorldBorder();
        double size = border.getSize();
        double centerX = border.getCenterX();
        double centerZ = border.getCenterZ();
        border.setCenter(-6.0, 0.0);
        border.setSize(12.0);
        try {
            BlockPos inside = freeFloor(r, border::isWithinBounds);
            BlockPos outside = freeFloor(r, cell -> !border.isWithinBounds(cell));
            expect(border.isWithinBounds(inside) && !border.isWithinBounds(outside),
                    "border: harness: " + inside + " is inside and " + outside + " is outside the moved border", f);
            Click in = r.click(inside, UP);
            expect(r.at(inside.above()) == STONE, "border: a cell inside the border places, got " + r.at(inside.above()), f);
            expect(in.mine.contains("ack " + in.seq), "border: inside is acked, saw " + in.mine, f);
            int before = r.held();
            Click out = r.click(outside, UP);
            expect(r.at(outside.above()).isAir() && r.held() == before,
                    "border: a cell outside the border places nothing, got " + r.at(outside.above()), f);
            expect(out.mine.equals(List.of(tooHigh(r), upd(outside, STONE), upd(outside.above(), AIR), "ack " + out.seq)),
                    "border: outside gets the too-high overlay, both updates and the ack, saw " + out.mine, f);
            expect(out.theirs.isEmpty(), "border: nothing for the watcher, saw " + out.theirs, f);
        } finally {
            border.setCenter(centerX, centerZ);
            border.setSize(size);
        }
        r.teleport(r.w, r.other, 7.5, 1.0, 7.5);
        r.pump(3);
    }

    /**
     * The click's {@code inside} flag reaches {@code UseOnContext.isInside}, which only
     * {@code ScaffoldingBlockItem.updatePlacementContext} reads (:31): a sneaking placer clicking the top of a
     * scaffolding with {@code inside} set builds downward (and finds the floor, so nothing is placed);
     * without it, upward.
     */
    private static void scaffolding(Rig r, List<String> f) throws Exception {
        r.hold(Items.SCAFFOLDING, 16);
        BlockPos floor = freeFloor(r, cell -> r.at(cell.above().above()).isAir());
        BlockState scaffold = Blocks.SCAFFOLDING.defaultBlockState();
        r.click(floor, UP);
        expect(r.at(floor.above()).is(Blocks.SCAFFOLDING), "scaffolding: placed on the floor, got " + r.at(floor.above()), f);
        BlockPos base = floor.above();
        r.standIn.setShiftKeyDown(true);
        try {
            int before = r.held();
            Click down = r.clickRaw(new Raw(0, base, UP, ++r.seq, true));
            expect(r.at(base.above()).isAir() && r.held() == before,
                    "scaffolding: sneaking with inside set builds downward, so nothing above, got " + r.at(base.above()), f);
            Click up = r.clickRaw(new Raw(0, base, UP, ++r.seq, false));
            expect(r.at(base.above()).is(Blocks.SCAFFOLDING) && r.held() == before - 1,
                    "scaffolding: sneaking without inside builds upward, got " + r.at(base.above()), f);
            expect(down.mine.contains("ack " + down.seq) && up.mine.contains("ack " + up.seq),
                    "scaffolding: both acked, saw " + down.mine + " and " + up.mine, f);
        } finally {
            r.standIn.setShiftKeyDown(false);
        }
    }

    /**
     * The stand-in level's writes into the loop, called directly: the priority a tick keeps, and
     * the neighbour updates with and without a skipped side. A lit lamp with no power is the probe:
     * a neighbour update makes it schedule its own switch-off (4 ticks), so it unlights if it was
     * told and stays lit if it was not.
     */
    private static void levelSeams(Rig r, List<String> f) throws Exception {
        net.minecraft.server.level.ServerLevel level = r.standIn.level();
        BlockState lit = Blocks.REDSTONE_LAMP.defaultBlockState().setValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT, true);

        BlockPos normal = new BlockPos(7, 40, -7);
        BlockPos extreme = new BlockPos(7, 40, -6);
        level.scheduleTick(normal, Blocks.STONE, 30);
        level.scheduleTick(extreme, Blocks.STONE, 31, net.minecraft.world.ticks.TickPriority.EXTREMELY_HIGH);
        net.minecraft.world.ticks.TickPriority gotNormal = null;
        net.minecraft.world.ticks.TickPriority gotExtreme = null;
        long triggerNormal = 0;
        long triggerExtreme = 0;
        for (dev.kestrel.loop.TickLoop.PendingBlockTick tick : r.loop.dumpBlockTicks()) {
            if (tick.pos().equals(normal)) {
                gotNormal = tick.priority();
                triggerNormal = tick.triggerTick();
            } else if (tick.pos().equals(extreme)) {
                gotExtreme = tick.priority();
                triggerExtreme = tick.triggerTick();
            }
        }
        expect(gotNormal == net.minecraft.world.ticks.TickPriority.NORMAL,
                "levelSeams: the three-argument scheduleTick is a NORMAL tick, got " + gotNormal, f);
        expect(gotExtreme == net.minecraft.world.ticks.TickPriority.EXTREMELY_HIGH,
                "levelSeams: the four-argument scheduleTick keeps its priority, got " + gotExtreme, f);
        expect(gotNormal != null && gotExtreme != null && triggerExtreme - triggerNormal == 1,
                "levelSeams: the delays 30 and 31 land one tick apart, got " + triggerNormal + " and " + triggerExtreme, f);

        BlockPos centre = new BlockPos(-7, 40, 6);
        r.set(centre.west(), lit);
        r.set(centre.east(), lit);
        level.updateNeighborsAtExceptFromFacing(centre, Blocks.STONE, net.minecraft.core.Direction.EAST, null);
        r.pump(8);
        expect(!r.at(centre.west()).getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT),
                "levelSeams: the side that was not skipped heard the update and unlit, got " + r.at(centre.west()), f);
        expect(r.at(centre.east()).getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT),
                "levelSeams: the skipped side heard nothing and stays lit, got " + r.at(centre.east()), f);

        BlockPos all = new BlockPos(-7, 40, 2);
        r.set(all.west(), lit);
        r.set(all.east(), lit);
        r.set(all.above(), lit);
        level.updateNeighborsAt(all, Blocks.STONE, null);
        r.pump(8);
        expect(!r.at(all.west()).getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT)
                        && !r.at(all.east()).getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT)
                        && !r.at(all.above()).getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT),
                "levelSeams: updateNeighborsAt tells every side, got " + r.at(all.west()) + " " + r.at(all.east()) + " " + r.at(all.above()), f);
    }

    /** Entity ids for a failure message: {@code Entity.toString} reads the stand-in level's null level data. */
    private static String who(java.util.Collection<? extends net.minecraft.world.entity.Entity> entities) {
        List<Integer> ids = new java.util.ArrayList<>();
        for (net.minecraft.world.entity.Entity e : entities) {
            ids.add(e.getId());
        }
        return ids.toString();
    }

    /**
     * Nothing a client sends in play is acted on before it has loaded, the use-item-on
     * acknowledgement included ({@code ServerGamePacketListenerImpl.java:1342}).
     */
    private static void notLoaded(Rig r, List<String> f) throws Exception {
        r.hold(Items.STONE, 8);
        BlockPos floor = freeFloor(r, cell -> true);
        expect(floor != null, "notLoaded: harness: found a free floor cell in reach", f);
        // Kestrel keeps vanilla's clientLoadedTimeoutTimer on its own body record (PlayerBodies.Body):
        // loaded at 0, counted down once a tick. Park it far above what the clicks below will tick off.
        java.lang.reflect.Field records = PlayerBodies.class.getDeclaredField("byRecord");
        records.setAccessible(true);
        PlayerBodies.Body body = (PlayerBodies.Body) ((java.util.Map<?, ?>) records.get(r.server.bodies())).get(r.p);
        body.clientLoadedTimeout = 1000;
        expect(!r.server.bodies().clientLoaded(r.p), "notLoaded: harness: the client reads as not loaded", f);
        Click c = r.click(floor, UP);
        expect(r.at(floor.above()) == AIR && r.held() == 8, "notLoaded: nothing is placed before the client has loaded, got " + r.at(floor.above()), f);
        expect(c.mine.isEmpty(), "notLoaded: not even the ack is sent, saw " + c.mine, f);
        expect(c.theirs.isEmpty(), "notLoaded: the watcher sees nothing, saw " + c.theirs, f);
        body.clientLoadedTimeout = 0;
        expect(r.server.bodies().clientLoaded(r.p), "notLoaded: harness: the client reads as loaded again", f);
        Click again = r.click(floor, UP);
        expect(r.at(floor.above()) == STONE && r.held() == 7, "notLoaded: places once it has loaded, got " + r.at(floor.above()), f);
        expect(again.mine.contains("ack " + again.seq), "notLoaded: and acks, saw " + again.mine, f);
    }

    /** One click as it goes on the wire, with the ordinals and the sequence left to the caller. */
    private record Raw(int hand, BlockPos pos, int face, int sequence, boolean inside) {
    }

    private record Click(int seq, List<String> mine, List<String> theirs) {
    }

    private static final class Peer {
        final NetCheck.Loopback c;
        final List<Wire.Inbound> seen = new ArrayList<>();
        boolean accept = true;
        int lastTeleport = -1;

        Peer(NetCheck.Loopback c) {
            this.c = c;
        }

        void drain() throws Exception {
            try {
                c.socket.setSoTimeout(50);
                while (true) {
                    Wire.Inbound in = c.read();
                    seen.add(in);
                    if (in.id() == PacketIds.PLAY_POSITION) {
                        int id = in.reader().varInt();
                        lastTeleport = id;
                        if (accept) {
                            c.sendC(PacketIds.PLAY_ACCEPT_TELEPORT, new Wire.Writer().vint(id).toBytes());
                        }
                    } else if (in.id() == PacketIds.PLAY_KEEPALIVE_OUT) {
                        c.sendC(PacketIds.PLAY_KEEPALIVE_IN, new Wire.Writer().l(in.reader().l()).toBytes());
                    }
                }
            } catch (java.net.SocketTimeoutException e) {
                // drained
            }
        }
    }

    private static final class Rig implements AutoCloseable {
        final TickLoop loop;
        final NetServer server;
        final ChunkSender sender;
        Peer me;
        Peer other;
        KestrelPlayer p;
        KestrelPlayer w;
        ServerPlayer standIn;
        int seq = 1000;

        private Rig(TickLoop loop, NetServer server, ChunkSender sender) {
            this.loop = loop;
            this.server = server;
            this.sender = sender;
        }

        static Rig open(String tag, String properties) throws Exception {
            KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
            TickLoop loop = new TickLoop(level);
            for (int x = -8; x <= 8; x++) {
                for (int z = -8; z <= 8; z++) {
                    loop.setBlock(new BlockPos(x, 0, z), STONE, 3);
                }
            }
            NetServer server = new NetServer(loop, 0, 256, SEED);
            // The spawn is the top solid block; the player stands one above it, at y = 1.
            server.setFixedSpawn(new BlockPos(0, 0, 0));
            if (properties != null) {
                Path dir = Files.createTempDirectory("kestrel-g20");
                Path file = dir.resolve("server.properties");
                Files.writeString(file, properties);
                server.setProperties(ServerProperties.load(file));
            }
            server.start();
            ChunkSender sender = new ChunkSender(loop, NetCheck.plainsId());
            Rig rig = new Rig(loop, server, sender);
            rig.me = new Peer(NetCheck.joinLoopback(loop, server, sender, tag + "Me"));
            rig.settle(20);
            rig.me.c.sendC(PacketIds.PLAY_MOVE_STATUS, new byte[] {1});
            rig.pump(2);
            rig.other = new Peer(NetCheck.joinLoopback(loop, server, sender, tag + "Other", 2));
            rig.settle(20);
            for (KestrelPlayer each : server.players.values()) {
                if (each.name.equals(tag + "Me")) {
                    rig.p = each;
                } else {
                    rig.w = each;
                }
            }
            rig.standIn = server.bodies().body(rig.p).vanillaPlayer();
            if (!server.bodies().clientLoaded(rig.p) || !server.bodies().clientLoaded(rig.w)) {
                throw new IllegalStateException("clients not loaded");
            }
            rig.teleport(rig.w, rig.other, 7.5, 1.0, 7.5);
            rig.pump(3);
            System.out.println("  placer at " + rig.standIn.position() + " eye " + rig.standIn.getEyePosition()
                    + " box " + rig.standIn.getBoundingBox() + " blocksBuilding " + rig.standIn.blocksBuilding);
            return rig;
        }

        int maxY() {
            return standIn.level().getMaxY();
        }

        int minY() {
            return standIn.level().getMinY();
        }

        /** Join-time ticks: wait for the inbound queue every tick. */
        void settle(int ticks) throws Exception {
            for (int i = 0; i < ticks; i++) {
                NetCheck.drainInbound(server);
                KestrelServer.runIteration(server, loop, sender);
                me.drain();
                if (other != null) {
                    other.drain();
                }
            }
        }

        /** Tick, waiting for the inbound queue only on the first tick (a click was just sent). */
        void pump(int ticks) throws Exception {
            for (int i = 0; i < ticks; i++) {
                if (i == 0) {
                    NetCheck.drainInbound(server);
                }
                KestrelServer.runIteration(server, loop, sender);
                me.drain();
                if (other != null) {
                    other.drain();
                }
            }
        }

        BlockState at(BlockPos pos) {
            return loop.getBlockState(pos);
        }

        BlockEntity blockEntity(BlockPos pos) {
            return loop.blockEntities().get(pos);
        }

        void set(BlockPos pos, BlockState state) throws Exception {
            loop.setBlock(pos, state, 18);
            pump(2);
        }

        void hold(Item item, int count) throws Exception {
            NetCheck.hold(p, item, count);
            pump(2);
        }

        void setMain(ItemStack stack) throws Exception {
            p.inventory.inventory().setItem(p.inventory.selectedSlot(), stack);
            pump(2);
        }

        void setOff(ItemStack stack) throws Exception {
            p.inventory.inventory().setItem(40, stack);
            pump(2);
        }

        ItemStack off() {
            return p.inventory.inventory().getItem(40);
        }

        int held() {
            return NetCheck.heldCount(p);
        }

        int used(Item item) {
            return standIn.getStats().getValue(Stats.ITEM_USED.get(item));
        }

        /** A server-side turn of the placer, through the client's own rotation packet. */
        void turn(float yaw) throws Exception {
            me.c.sendC(PacketIds.PLAY_MOVE_ROT, new Wire.Writer().f(yaw).f(0.0f).b(1).toBytes());
            pump(2);
            if (standIn.getYRot() != net.minecraft.util.Mth.wrapDegrees(yaw)) {
                throw new IllegalStateException("harness: placer turned to " + yaw + ", is " + standIn.getYRot());
            }
        }

        /** A server teleport, as {@code handleMoveViolation} sends one. */
        void teleport(KestrelPlayer who, Peer peer, double x, double y, double z) {
            who.moveTo(x, y, z, who.yaw, who.pitch, false);
            NetServer.Session session = server.sessions.get(who.profile);
            session.send(Outbound.position(who.nextTeleportId(), x, y, z, who.yaw, who.pitch));
            server.bodies().teleported(who);
        }

        Click click(BlockPos pos, int face) throws Exception {
            return click(0, pos, face);
        }

        Click click(int hand, BlockPos pos, int face) throws Exception {
            float[] c = switch (face) {
                case DOWN -> new float[] {0.5f, 0.0f, 0.5f};
                case UP -> new float[] {0.5f, 1.0f, 0.5f};
                case NORTH -> new float[] {0.5f, 0.5f, 0.0f};
                case SOUTH -> new float[] {0.5f, 0.5f, 1.0f};
                case WEST -> new float[] {0.0f, 0.5f, 0.5f};
                default -> new float[] {1.0f, 0.5f, 0.5f};
            };
            return click(hand, pos, face, c[0], c[1], c[2]);
        }

        /** Several clicks sent back to back and ticked together; the result's sequence is the last one's. */
        Click clickRaw(Raw... raws) throws Exception {
            me.seen.clear();
            other.seen.clear();
            int expectedEvents = server.events.size() + raws.length;
            for (Raw raw : raws) {
                Wire.Writer wr = new Wire.Writer();
                wr.vint(raw.hand).l(raw.pos.asLong()).vint(raw.face).f(0.5f).f(1.0f).f(0.5f).bool(raw.inside).bool(false)
                        .vint(raw.sequence);
                me.c.sendC(PacketIds.PLAY_USE_ITEM_ON, wr.toBytes());
            }
            long deadline = System.currentTimeMillis() + 2000;
            while (server.events.size() < expectedEvents && System.currentTimeMillis() < deadline) {
                Thread.sleep(2);
            }
            pump(5);
            return new Click(raws[raws.length - 1].sequence, summarize(me.seen), summarize(other.seen));
        }

        Click click(int hand, BlockPos pos, int face, float cx, float cy, float cz) throws Exception {
            me.seen.clear();
            other.seen.clear();
            int s = ++seq;
            Wire.Writer wr = new Wire.Writer();
            wr.vint(hand).l(pos.asLong()).vint(face).f(cx).f(cy).f(cz).bool(false).bool(false).vint(s);
            me.c.sendC(PacketIds.PLAY_USE_ITEM_ON, wr.toBytes());
            pump(5);
            Click click = new Click(s, summarize(me.seen), summarize(other.seen));
            if (VERBOSE) {
                System.out.println("    click " + pos + " face " + face + " seq " + s);
                System.out.println("      mine   " + click.mine);
                System.out.println("      theirs " + click.theirs);
            }
            return click;
        }

        @Override
        public void close() throws Exception {
            try {
                me.c.socket.close();
                other.c.socket.close();
            } finally {
                server.close();
            }
        }
    }

    // ---------------------------------------------------------------- decoding

    private static String upd(BlockPos pos, BlockState state) {
        return "update " + pos.getX() + "," + pos.getY() + "," + pos.getZ() + " " + state;
    }

    private static String describe(ItemStack stack) {
        return stack.isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(stack.getItem()) + " x" + stack.getCount();
    }

    /** The packets a placement can raise, as stable strings in arrival order; everything else is dropped. */
    private static List<String> summarize(List<Wire.Inbound> seen) {
        List<String> out = new ArrayList<>();
        for (Wire.Inbound in : seen) {
            byte[] payload = in.payload();
            switch (in.id()) {
                case PacketIds.PLAY_BLOCK_UPDATE -> {
                    ClientboundBlockUpdatePacket p = ClientboundBlockUpdatePacket.STREAM_CODEC.decode(
                            registryBuf(payload));
                    out.add(upd(p.getPos(), p.getBlockState()));
                }
                case PacketIds.PLAY_SECTION_BLOCKS_UPDATE -> {
                    ClientboundSectionBlocksUpdatePacket p = ClientboundSectionBlocksUpdatePacket.STREAM_CODEC.decode(
                            registryBuf(payload));
                    p.runUpdates((pos, state) -> out.add(upd(pos, state)));
                }
                case PacketIds.PLAY_BLOCK_CHANGED_ACK -> out.add("ack "
                        + ClientboundBlockChangedAckPacket.STREAM_CODEC.decode(new FriendlyByteBuf(
                                io.netty.buffer.Unpooled.wrappedBuffer(payload))).sequence());
                case PacketIds.PLAY_ANIMATE -> {
                    ClientboundAnimatePacket p = ClientboundAnimatePacket.STREAM_CODEC.decode(new FriendlyByteBuf(
                            io.netty.buffer.Unpooled.wrappedBuffer(payload)));
                    out.add("animate " + p.getId() + " " + p.getAction());
                }
                case PacketIds.PLAY_LEVEL_EVENT -> {
                    ClientboundLevelEventPacket p = ClientboundLevelEventPacket.STREAM_CODEC.decode(
                            new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(payload)));
                    out.add("event " + p.getType() + "@" + p.getPos().asLong() + ":" + p.getData());
                }
                case PacketIds.PLAY_SYSTEM_CHAT -> {
                    ClientboundSystemChatPacket p = ClientboundSystemChatPacket.STREAM_CODEC.decode(registryBuf(payload));
                    out.add((p.overlay() ? "overlay " : "chat ") + p.content());
                }
                case PacketIds.PLAY_CONTAINER_SET_SLOT -> {
                    ClientboundContainerSetSlotPacket p = ClientboundContainerSetSlotPacket.STREAM_CODEC.decode(
                            registryBuf(payload));
                    if (p.getContainerId() == 0) {
                        out.add("slot " + p.getSlot() + " " + describe(p.getItem()));
                    }
                }
                default -> {
                }
            }
        }
        return out;
    }

    private static RegistryFriendlyByteBuf registryBuf(byte[] payload) {
        return new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(payload),
                net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
    }

    /** {@code expected} appear in {@code c.mine} in this relative order (other packets may sit between). */
    private static void order(Click c, String what, List<String> failures, String... expected) {
        int from = 0;
        for (String want : expected) {
            int at = c.mine.subList(from, c.mine.size()).indexOf(want);
            if (at < 0) {
                expect(false, what + ": packet order " + List.of(expected) + " in " + c.mine, failures);
                return;
            }
            from += at + 1;
        }
        expect(true, what + ": packet order " + List.of(expected), failures);
    }

    private static void expect(boolean cond, String what, List<String> failures) {
        System.out.println((cond ? "  ok   " : "  FAIL ") + what);
        if (!cond) {
            failures.add(what);
        }
    }
}
