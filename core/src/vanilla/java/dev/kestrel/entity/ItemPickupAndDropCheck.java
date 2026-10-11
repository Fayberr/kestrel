package dev.kestrel.entity;

import dev.kestrel.collision.ShapeBoxCache;
import dev.kestrel.net.Outbound;
import dev.kestrel.net.PacketIds;
import dev.kestrel.net.Wire;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Gate check for G19 slice 3: dropping items (Q and Ctrl+Q) and picking items up.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Single drop (Q): 1 item dropped from selected slot, pickup delay 40,
 *       thrower UUID set, velocity and position matching vanilla Player.drop
 *       bit-for-bit, stats ITEM_DROPPED and DROP incremented.</li>
 *   <li>All drop (Ctrl+Q): entire selected stack dropped, selected slot cleared,
 *       stats incremented.</li>
 *   <li>Pickup delay gate: delay > 0 blocks pickup; infinite delay (32767) blocks
 *       pickup; delay == 0 allows pickup.</li>
 *   <li>Target player filter: target set to another UUID blocks pickup; target set
 *       to player UUID or null allows pickup.</li>
 *   <li>Inventory full: unstackable item stays when inventory full.</li>
 *   <li>Partial pickup: leaves remainder with correct count, updates stack,
 *       marks dirty, does not discard entity until empty.</li>
 *   <li>Packet delivery: tracking observer receives PLAY_TAKE_ITEM_ENTITY (0x7C)
 *       and PLAY_REMOVE_ENTITIES (0x4D).</li>
 *   <li>Player doTick: aiStep touch loop queries level getEntities and picks up
 *       item entity in range.</li>
 * </ul>
 */
public final class ItemPickupAndDropCheck {

    private static final long SEED = 4242424242L;

    public static void main(String[] args) throws Exception {
        // Vanilla's real per-item components (sword 1, ender pearl 16, etc.)
        dev.kestrel.worldgen.HeadlessNoise.loadStructural(SEED).close();
        expect(new ItemStack(Items.DIAMOND_SWORD).getMaxStackSize() == 1
                && new ItemStack(Items.ENDER_PEARL).getMaxStackSize() == 16, "real item components bound");

        checkDropSingleQ();
        checkDropAllCtrlQ();
        checkPickupDelayGate();
        checkTargetFilter();
        checkInventoryFullAndPartialPickup();
        checkPacketDeliveryAndTracking();
        checkPlayerDoTickTouchLoop();

        System.out.println("ItemPickupAndDropCheck: all assertions passed.");
    }

    private static void checkDropSingleQ() throws Exception {
        System.out.println("  checkDropSingleQ...");
        CheckContext ctx = new CheckContext();
        KestrelServerPlayer kPlayer = ctx.createPlayer("DropPlayer1");
        kPlayer.setPos(10.5, 64.0, 10.5);
        kPlayer.setXRot(25.0F);
        kPlayer.setYRot(45.0F);

        // Put 5 diamonds in selected slot
        int selectedSlot = kPlayer.getInventory().getSelectedSlot();
        kPlayer.getInventory().setItem(selectedSlot, new ItemStack(Items.DIAMOND, 5));

        // Seed random deterministically
        setEntityRandom(kPlayer, RandomSource.create(9999L));

        // Vanilla expected drop calculation using identical random and state
        RandomSource vRandom = RandomSource.create(9999L);
        double yHandPos = kPlayer.getEyeY() - 0.3F;
        float pow = 0.3F;
        float sinX = Mth.sin(kPlayer.getXRot() * (float) (Math.PI / 180.0));
        float cosX = Mth.cos(kPlayer.getXRot() * (float) (Math.PI / 180.0));
        float sinY = Mth.sin(kPlayer.getYRot() * (float) (Math.PI / 180.0));
        float cosY = Mth.cos(kPlayer.getYRot() * (float) (Math.PI / 180.0));
        float dir = vRandom.nextFloat() * (float) (Math.PI * 2);
        float pow2 = 0.02F * vRandom.nextFloat();
        Vec3 expectedMovement = new Vec3(
                -sinY * cosX * 0.3F + Math.cos(dir) * pow2,
                -sinX * 0.3F + 0.1F + (vRandom.nextFloat() - vRandom.nextFloat()) * 0.1F,
                cosY * cosX * 0.3F + Math.sin(dir) * pow2
        );

        // Execute drop(false) -> single item drop (Q)
        kPlayer.drop(false);

        // Verify remaining inventory has 4 diamonds
        ItemStack inSlot = kPlayer.getInventory().getItem(selectedSlot);
        expect(inSlot.is(Items.DIAMOND) && inSlot.getCount() == 4,
                "single drop: 4 diamonds remain in inventory, got " + inSlot);

        // Find spawned item in EntityWorld
        List<KestrelItemEntity> items = ctx.world.entitiesSnapshot().stream()
                .filter(e -> e instanceof KestrelItemEntity)
                .map(e -> (KestrelItemEntity) e)
                .toList();
        expect(items.size() == 1, "single drop: exactly 1 item entity spawned in world");
        KestrelItemEntity drop = items.get(0);

        // Verify spawned item stack
        expect(drop.getItem().is(Items.DIAMOND) && drop.getItem().getCount() == 1,
                "single drop: spawned item is 1 diamond, got " + drop.getItem());

        // Verify position and velocity raw bits match vanilla exactly
        expect(Double.doubleToRawLongBits(drop.getX()) == Double.doubleToRawLongBits(kPlayer.getX()),
                "single drop: X pos bit-exact");
        expect(Double.doubleToRawLongBits(drop.getY()) == Double.doubleToRawLongBits(yHandPos),
                "single drop: Y pos bit-exact");
        expect(Double.doubleToRawLongBits(drop.getZ()) == Double.doubleToRawLongBits(kPlayer.getZ()),
                "single drop: Z pos bit-exact");
        expect(Double.doubleToRawLongBits(drop.getDeltaMovement().x) == Double.doubleToRawLongBits(expectedMovement.x),
                "single drop: deltaX bit-exact");
        expect(Double.doubleToRawLongBits(drop.getDeltaMovement().y) == Double.doubleToRawLongBits(expectedMovement.y),
                "single drop: deltaY bit-exact");
        expect(Double.doubleToRawLongBits(drop.getDeltaMovement().z) == Double.doubleToRawLongBits(expectedMovement.z),
                "single drop: deltaZ bit-exact");

        // Verify pickup delay is 40 ticks (2.0s)
        expect(drop.getPickupDelay() == 40, "single drop: pickup delay is 40, got " + drop.getPickupDelay());

        // Verify thrower is set to player UUID
        expect(kPlayer.getUUID().equals(drop.getThrower()),
                "single drop: thrower UUID is player UUID, got " + drop.getThrower());

        // Verify stats
        expect(kPlayer.getStats().getValue(Stats.ITEM_DROPPED.get(Items.DIAMOND)) == 1,
                "single drop: ITEM_DROPPED stat incremented by 1");
        expect(kPlayer.getStats().getValue(Stats.CUSTOM.get(Stats.DROP)) == 1,
                "single drop: DROP stat incremented by 1");
    }

    private static void checkDropAllCtrlQ() throws Exception {
        System.out.println("  checkDropAllCtrlQ...");
        CheckContext ctx = new CheckContext();
        KestrelServerPlayer kPlayer = ctx.createPlayer("DropAllPlayer");
        int selectedSlot = kPlayer.getInventory().getSelectedSlot();
        kPlayer.getInventory().setItem(selectedSlot, new ItemStack(Items.EMERALD, 16));

        // Execute drop(true) -> whole stack drop (Ctrl+Q)
        kPlayer.drop(true);

        // Verify inventory slot is empty
        ItemStack inSlot = kPlayer.getInventory().getItem(selectedSlot);
        expect(inSlot.isEmpty(), "drop all: selected slot is empty, got " + inSlot);

        // Verify spawned item has count 16
        List<KestrelItemEntity> items = ctx.world.entitiesSnapshot().stream()
                .filter(e -> e instanceof KestrelItemEntity)
                .map(e -> (KestrelItemEntity) e)
                .toList();
        expect(items.size() == 1, "drop all: 1 item entity spawned");
        KestrelItemEntity drop = items.get(0);
        expect(drop.getItem().is(Items.EMERALD) && drop.getItem().getCount() == 16,
                "drop all: item stack is 16 emeralds, got " + drop.getItem());
        expect(drop.getPickupDelay() == 40, "drop all: pickup delay 40");
        expect(kPlayer.getUUID().equals(drop.getThrower()), "drop all: thrower is player");
        expect(kPlayer.getStats().getValue(Stats.ITEM_DROPPED.get(Items.EMERALD)) == 16,
                "drop all: ITEM_DROPPED stat is 16");
        expect(kPlayer.getStats().getValue(Stats.CUSTOM.get(Stats.DROP)) == 1,
                "drop all: DROP stat is 1");
    }

    private static void checkPickupDelayGate() throws Exception {
        System.out.println("  checkPickupDelayGate...");
        CheckContext ctx = new CheckContext();
        KestrelServerPlayer player = ctx.createPlayer("PickupGatePlayer");

        // 1. Pickup delay > 0 blocks pickup
        KestrelItemEntity item1 = ctx.world.spawnItem(player.getX(), player.getY(), player.getZ(),
                new ItemStack(Items.GOLD_INGOT, 1), 0.0, 0.0, 0.0);
        expect(item1 != null, "spawn item1");
        item1.setPickUpDelay(10);

        item1.playerTouch(player);
        expect(!item1.isRemoved(), "pickup gate: item with delay 10 not removed");
        expect(!player.getInventory().contains(new ItemStack(Items.GOLD_INGOT)),
                "pickup gate: item with delay 10 not in inventory");

        // 2. Infinite pickup delay (32767) blocks pickup
        item1.setNeverPickUp();
        expect(item1.getPickupDelay() == KestrelItemEntity.INFINITE_PICKUP_DELAY, "infinite pickup delay set");
        item1.playerTouch(player);
        expect(!item1.isRemoved(), "pickup gate: item with infinite delay not removed");

        // 3. Pickup delay == 0 allows pickup
        item1.setNoPickUpDelay();
        expect(item1.getPickupDelay() == 0, "pickup delay is 0");
        item1.playerTouch(player);
        expect(item1.isRemoved(), "pickup gate: item with delay 0 is removed");
        expect(player.getInventory().contains(new ItemStack(Items.GOLD_INGOT)),
                "pickup gate: gold ingot picked up into inventory");
        expect(player.getStats().getValue(Stats.ITEM_PICKED_UP.get(Items.GOLD_INGOT)) == 1,
                "pickup gate: ITEM_PICKED_UP stat awarded");
    }

    private static void checkTargetFilter() throws Exception {
        System.out.println("  checkTargetFilter...");
        CheckContext ctx = new CheckContext();
        KestrelServerPlayer player = ctx.createPlayer("TargetPlayer");
        UUID otherPlayer = UUID.randomUUID();

        // 1. Target set to another player UUID blocks pickup
        KestrelItemEntity targetedItem = ctx.world.spawnItem(player.getX(), player.getY(), player.getZ(),
                new ItemStack(Items.IRON_INGOT, 1), 0.0, 0.0, 0.0);
        expect(targetedItem != null, "spawn targetedItem");
        targetedItem.setNoPickUpDelay();
        targetedItem.setTarget(otherPlayer);

        targetedItem.playerTouch(player);
        expect(!targetedItem.isRemoved(), "target filter: targeted to other player, not picked up");
        expect(!player.getInventory().contains(new ItemStack(Items.IRON_INGOT)),
                "target filter: not in inventory");

        // 2. Target set to this player UUID allows pickup
        targetedItem.setTarget(player.getUUID());
        targetedItem.playerTouch(player);
        expect(targetedItem.isRemoved(), "target filter: targeted to this player, picked up");
        expect(player.getInventory().contains(new ItemStack(Items.IRON_INGOT)),
                "target filter: picked up into inventory");

        // 3. Null target allows pickup by anyone
        KestrelItemEntity untargetedItem = ctx.world.spawnItem(player.getX(), player.getY(), player.getZ(),
                new ItemStack(Items.COPPER_INGOT, 1), 0.0, 0.0, 0.0);
        expect(untargetedItem != null, "spawn untargetedItem");
        untargetedItem.setNoPickUpDelay();
        untargetedItem.setTarget(null);

        untargetedItem.playerTouch(player);
        expect(untargetedItem.isRemoved(), "target filter: null target picked up");
        expect(player.getInventory().contains(new ItemStack(Items.COPPER_INGOT)),
                "target filter: copper ingot in inventory");
    }

    private static void checkInventoryFullAndPartialPickup() throws Exception {
        System.out.println("  checkInventoryFullAndPartialPickup...");
        CheckContext ctx = new CheckContext();
        KestrelServerPlayer player = ctx.createPlayer("InventoryFullPlayer");
        Inventory inv = player.getInventory();

        // Fill all 36 main inventory slots with Dirt
        for (int i = 0; i < 36; i++) {
            inv.setItem(i, new ItemStack(Items.DIRT, 64));
        }

        // Try picking up an unstackable item (Diamond Sword)
        KestrelItemEntity swordItem = ctx.world.spawnItem(player.getX(), player.getY(), player.getZ(),
                new ItemStack(Items.DIAMOND_SWORD, 1), 0.0, 0.0, 0.0);
        expect(swordItem != null, "spawn swordItem");
        swordItem.setNoPickUpDelay();

        swordItem.playerTouch(player);
        expect(!swordItem.isRemoved(), "full inventory: unstackable item not picked up");
        expect(swordItem.getItem().getCount() == 1, "full inventory: sword count remains 1");

        // Partial pickup:
        // Set slot 35 to have 50 Cobblestone (space for 14 more up to 64)
        inv.setItem(35, new ItemStack(Items.COBBLESTONE, 50));

        // Drop 30 Cobblestone
        KestrelItemEntity cobbleItem = ctx.world.spawnItem(player.getX(), player.getY(), player.getZ(),
                new ItemStack(Items.COBBLESTONE, 30), 0.0, 0.0, 0.0);
        expect(cobbleItem != null, "spawn cobbleItem");
        cobbleItem.setNoPickUpDelay();
        cobbleItem.getEntityData().packDirty(); // as the tracker's send after the spawn would

        cobbleItem.playerTouch(player);
        expect(!cobbleItem.isRemoved(), "partial pickup: item not removed (count remains)");
        expect(cobbleItem.getItem().getCount() == 16,
                "partial pickup: remaining count on ground is 16 (30 - 14), got " + cobbleItem.getItem().getCount());
        expect(inv.getItem(35).getCount() == 64,
                "partial pickup: inventory slot filled to 64, got " + inv.getItem(35).getCount());
        expect(!cobbleItem.getEntityData().isDirty(),
                "partial pickup: stack shrunk in place, entity data not dirty (ItemEntity.java:345, no setItem)");
        // Vanilla quirk: Inventory.add (Inventory.java:274-288) compares against the
        // count at the start of its LAST loop pass, so a partial fill returns false.
        // playerTouch then skips take, the stat and onItemPickup (ItemEntity.java:345).
        expect(player.getStats().getValue(Stats.ITEM_PICKED_UP.get(Items.COBBLESTONE)) == 0,
                "partial pickup: Inventory.add returned false, no ITEM_PICKED_UP stat, got "
                        + player.getStats().getValue(Stats.ITEM_PICKED_UP.get(Items.COBBLESTONE)));

        // Now free slot 0 and pick up the remainder
        inv.setItem(0, ItemStack.EMPTY);
        cobbleItem.playerTouch(player);
        expect(cobbleItem.isRemoved(), "partial pickup: second touch after slot freed removes item");
        expect(inv.getItem(0).is(Items.COBBLESTONE) && inv.getItem(0).getCount() == 16,
                "partial pickup: slot 0 receives remaining 16 cobblestone");
    }

    private static void checkPacketDeliveryAndTracking() throws Exception {
        System.out.println("  checkPacketDeliveryAndTracking...");
        // Fixed, not read from PacketIds: GameProtocols.CLIENTBOUND_TEMPLATE lists
        // CLIENTBOUND_TAKE_ITEM_ENTITY at index 124 (bundle at 0).
        expect(PacketIds.PLAY_TAKE_ITEM_ENTITY == 0x7C,
                "take item entity packet id is 0x7C, got " + PacketIds.PLAY_TAKE_ITEM_ENTITY);
        CheckContext ctx = new CheckContext();
        KestrelServerPlayer player = ctx.createPlayer("PacketPlayer");
        player.setPos(5.0, 64.0, 5.0);

        TestObserver observer = new TestObserver(2001, 5.0, 5.0);
        ctx.tracker.addPlayer(observer);

        KestrelItemEntity item = ctx.world.spawnItem(5.0, 64.0, 5.0,
                new ItemStack(Items.REDSTONE, 3), 0.0, 0.0, 0.0);
        expect(item != null, "spawn redstone");
        item.setNoPickUpDelay();

        // Observer should have received PLAY_ADD_ENTITY
        expect(observer.hasPacket(PacketIds.PLAY_ADD_ENTITY), "observer saw item spawn");
        observer.captured.clear();

        // Player picks up item
        item.playerTouch(player);

        // Observer must receive PLAY_TAKE_ITEM_ENTITY (0x7C)
        List<Outbound.Packet> takePackets = observer.getPackets(PacketIds.PLAY_TAKE_ITEM_ENTITY);
        expect(!takePackets.isEmpty(), "observer received PLAY_TAKE_ITEM_ENTITY (0x7C)");
        Wire.Reader r = new Wire.Reader(takePackets.get(0).payload());
        int itemId = r.varInt();
        int playerId = r.varInt();
        int amount = r.varInt();
        expect(itemId == item.getId(), "take packet item ID matches");
        expect(playerId == player.getId(), "take packet player ID matches");
        expect(amount == 3, "take packet amount matches 3, got " + amount);

        // Observer must receive PLAY_REMOVE_ENTITIES (0x4D)
        List<Outbound.Packet> removePackets = observer.getPackets(PacketIds.PLAY_REMOVE_ENTITIES);
        expect(!removePackets.isEmpty(), "observer received PLAY_REMOVE_ENTITIES (0x4D)");
        Wire.Reader rr = new Wire.Reader(removePackets.get(0).payload());
        int count = rr.varInt();
        boolean foundId = false;
        for (int k = 0; k < count; k++) {
            if (rr.varInt() == item.getId()) {
                foundId = true;
                break;
            }
        }
        expect(foundId, "remove packet contains item ID " + item.getId());
    }

    private static void checkPlayerDoTickTouchLoop() throws Exception {
        System.out.println("  checkPlayerDoTickTouchLoop...");
        CheckContext ctx = new CheckContext();
        KestrelServerPlayer player = ctx.createPlayer("DoTickPlayer");
        player.setPos(20.0, 64.0, 20.0);

        // Spawn item within pickup area (0.5 blocks away)
        KestrelItemEntity item = ctx.world.spawnItem(20.2, 64.0, 20.2,
                new ItemStack(Items.LAPIS_LAZULI, 7), 0.0, 0.0, 0.0);
        expect(item != null, "spawn lapis item");
        item.setNoPickUpDelay();

        // Run player.doTick() -> calls aiStep -> gets nearby entities -> calls touch -> playerTouch
        player.doTick();

        expect(item.isRemoved(), "doTick: lapis item picked up via player aiStep touch loop");
        expect(player.getInventory().contains(new ItemStack(Items.LAPIS_LAZULI)),
                "doTick: lapis in player inventory");
        expect(player.getStats().getValue(Stats.ITEM_PICKED_UP.get(Items.LAPIS_LAZULI)) == 7,
                "doTick: ITEM_PICKED_UP stat is 7");
    }

    private static void setEntityRandom(Entity entity, RandomSource random) throws Exception {
        Field f = Entity.class.getDeclaredField("random");
        f.setAccessible(true);
        f.set(entity, random);
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class CheckContext {
        final TestLevel level;
        final EntityTracker tracker;
        final EntityWorld world;

        CheckContext() {
            this.level = new TestLevel();
            this.tracker = new EntityTracker(10);
            this.world = new EntityWorld(level, tracker);
            level.world = world;
        }

        KestrelServerPlayer createPlayer(String name) {
            UUID uuid = UUID.randomUUID();
            VanillaEntityLevel vLevel = VanillaEntityLevel.of(level);
            return new KestrelServerPlayer(vLevel, new GameProfile(uuid, name),
                    ClientInformation.createDefault(), null);
        }
    }

    private static final class TestLevel implements EntityLevel {
        EntityWorld world;
        final RandomSource random = RandomSource.create(SEED);
        final GameRules gameRules = new GameRules(net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS);

        private final BlockGetter blocks = new BlockGetter() {
            @Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) { return null; }
            @Override public BlockState getBlockState(BlockPos pos) { return Blocks.AIR.defaultBlockState(); }
            @Override public FluidState getFluidState(BlockPos pos) { return net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState(); }
            @Override public int getMinY() { return -64; }
            @Override public int getHeight() { return 384; }
        };

        @Override public BlockGetter blocks() { return blocks; }
        @Override public ShapeBoxCache shapeCache() { return null; }
        @Override public int getMinY() { return -64; }
        @Override public LevelReader levelReader() { throw new UnsupportedOperationException(); }
        @Override public boolean setBlock(BlockPos pos, BlockState state, int flags) { return false; }
        @Override public void levelEvent(int type, BlockPos pos, int data) { }
        @Override public void landingBlockUpdate(KestrelEntity entity, BlockPos pos, BlockState state) { }
        @Override public RandomSource random() { return random; }
        @Override public GameRules gameRules() { return gameRules; }
        @Override public void playSound(double x, double y, double z, Holder<SoundEvent> sound, SoundSource source, float volume, float pitch) { }
        @Override public void entityEvent(KestrelEntity entity, byte event) { }
        @Override public @Nullable EntityWorld world() { return world; }
        @Override public boolean addFreshVanillaEntity(Entity entity) { return world != null && world.addFreshVanillaEntity(entity); }
    }

    private static final class TestObserver implements TrackingObserver {
        final int id;
        final double x;
        final double z;
        final List<Outbound.Packet> captured = new ArrayList<>();

        TestObserver(int id, double x, double z) {
            this.id = id;
            this.x = x;
            this.z = z;
        }

        @Override public int entityId() { return id; }
        @Override public double x() { return x; }
        @Override public double y() { return 64.0; }
        @Override public double z() { return z; }
        @Override public int viewDistance() { return 10; }
        @Override public boolean isChunkTracked(int chunkX, int chunkZ) { return true; }
        @Override public void send(Outbound.Packet packet) { captured.add(packet); }

        boolean hasPacket(int packetId) {
            return captured.stream().anyMatch(p -> p.id() == packetId);
        }

        List<Outbound.Packet> getPackets(int packetId) {
            return captured.stream().filter(p -> p.id() == packetId).toList();
        }
    }
}
