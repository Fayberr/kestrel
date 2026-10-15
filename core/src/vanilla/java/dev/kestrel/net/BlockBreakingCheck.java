package dev.kestrel.net;

import dev.kestrel.loop.KestrelLevel;
import dev.kestrel.loop.TickLoop;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ToolMaterial;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.network.HashedStack;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Gate for the G18 survival block-break state machine ({@link BlockBreaking})
 * over a real loopback session, with the vanilla block tags bound from the
 * extracted datapack ({@link ServedTags}, the fallback binding).
 *
 * <p>Runs in its own JVM: binding tags mutates the global block registry's
 * holders, which no other check should observe.
 *
 * <p>Args: optional extracted vanilla datapack dir (default
 * {@code -Dkestrel.datapack}, else {@code ~/kestrel-run/packdump/data}).
 */
public final class BlockBreakingCheck {
    private BlockBreakingCheck() {
    }

    private static final int START = BlockBreaking.START_DESTROY_BLOCK;
    private static final int ABORT = BlockBreaking.ABORT_DESTROY_BLOCK;
    private static final int STOP = BlockBreaking.STOP_DESTROY_BLOCK;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        List<String> failures = new ArrayList<>();

        Path packDir = Path.of(args.length > 0 ? args[0] : System.getProperty("kestrel.datapack",
                System.getProperty("user.home") + "/kestrel-run/packdump/data"));
        if (!Files.isDirectory(packDir)) {
            System.out.println("BlockBreakingCheck: FAIL");
            System.out.println("  no vanilla datapack at " + packDir);
            System.exit(1);
        }
        List<Datapack.RegistryEntries> packs = Datapack.load(packDir, SyncedRegistries.KEYS,
                ServedTags.blockStaticOrder());
        int bound = ServedTags.bindBlockTags(packs);
        expect(bound > 100, "tags: bound " + bound + " block tags", failures);
        expect(Blocks.STONE.defaultBlockState().is(BlockTags.MINEABLE_WITH_PICKAXE),
                "tags: stone is mineable/pickaxe", failures);
        expect(Blocks.DIRT.defaultBlockState().is(BlockTags.MINEABLE_WITH_SHOVEL)
                        && !Blocks.DIRT.defaultBlockState().is(BlockTags.MINEABLE_WITH_PICKAXE),
                "tags: dirt is mineable/shovel only", failures);
        BuiltInRegistries.ITEM.listElements().forEach(holder -> {
            if (!holder.areComponentsBound()) {
                holder.bindComponents(DataComponents.COMMON_ITEM_COMPONENTS);
            }
        });
        expect(woodenPickaxe().isCorrectToolForDrops(Blocks.STONE.defaultBlockState()),
                "tags: wooden pickaxe is the correct tool for stone", failures);
        expect(!woodenPickaxe().isCorrectToolForDrops(Blocks.IRON_ORE.defaultBlockState()),
                "tags: wooden pickaxe is not the correct tool for iron ore", failures);

        checkCrackWire(failures);
        checkCrackRange(failures);
        checkLoopback(failures);
        checkSpawnProtection(failures);

        System.out.println("BlockBreakingCheck: " + (failures.isEmpty() ? "PASS" : "FAIL"));
        if (!failures.isEmpty()) {
            for (String f : failures) {
                System.out.println("  " + f);
            }
            System.exit(1);
        }
    }

    private static void checkLoopback(List<String> failures) {
        NetServer server = null;
        try {
            BlockState stone = Blocks.STONE.defaultBlockState();
            BlockState air = Blocks.AIR.defaultBlockState();
            KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
            TickLoop loop = new TickLoop(level);
            for (int x = -8; x <= 8; x++) {
                for (int z = -8; z <= 8; z++) {
                    loop.setBlock(new BlockPos(x, 0, z), stone, 3);
                }
            }
            server = new NetServer(loop, 0, 256, 4242424242L);
            // G19: vanilla's loot tables, as LiveRandomTicks installs them live.
            try (dev.kestrel.worldgen.HeadlessNoise.Structural structural =
                    dev.kestrel.worldgen.HeadlessNoise.loadStructural(4242424242L)) {
                net.minecraft.core.RegistryAccess access = structural.driver().access();
                net.minecraft.world.RandomSequences sequences = new net.minecraft.world.RandomSequences();
                server.setLootRegistries(access,
                        dev.kestrel.loop.LootTables.load(access, structural.resources()),
                        key -> sequences.get(key, 4242424242L),
                        key -> Math.abs(net.minecraft.world.level.ChunkPos.getX(key)) <= 1
                                && Math.abs(net.minecraft.world.level.ChunkPos.getZ(key)) <= 1);
            }
            server.start();
            ChunkSender sender = new ChunkSender(loop, NetCheck.plainsId());
            NetCheck.Loopback client = NetCheck.joinLoopback(loop, server, sender, "BreakBot");
            NetCheck.pump(loop, server, sender, client, 20);
            expect(server.players.size() == 1, "join: server tracks the breaker", failures);
            KestrelPlayer p = server.players.values().iterator().next();
            ServerPlayer standIn = server.bodies().body(p).vanillaPlayer();
            expect(server.bodies().clientLoaded(p), "join: client loaded", failures);

            // On the ground: vanilla's airborne divisor (Player.getDestroySpeed) must not apply.
            client.sendC(PacketIds.PLAY_MOVE_STATUS, new byte[] {1});
            NetCheck.pump(loop, server, sender, client, 2);
            expect(standIn.onGround(), "join: breaker on ground after MOVE_STATUS", failures);

            int fx = (int) Math.floor(standIn.getX());
            int fy = (int) Math.floor(standIn.getY());
            int fz = (int) Math.floor(standIn.getZ());
            System.out.println("  breaker at " + standIn.position() + " eye " + standIn.getEyePosition());
            BlockBreaking bb = p.blockBreaking;
            int seq = 100;

            // 1. Insta-mine on START (the ghost-block bug): short grass breaks
            // on START alone, the client is acked and sees air.
            BlockPos grass = new BlockPos(fx + 2, fy, fz);
            loop.setBlock(grass, Blocks.SHORT_GRASS.defaultBlockState(), 18);
            client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(START, grass, ++seq));
            List<Wire.Inbound> seen = NetCheck.pump(loop, server, sender, client, 3);
            expect(loop.getBlockState(grass) == air,
                    "insta: short grass broken on START, got " + loop.getBlockState(grass), failures);
            expect(sawAck(seen, seq) || awaitAck(loop, server, sender, client, seq), "insta: START acked", failures);
            expect(sawBlockUpdate(seen, grass, air), "insta: client told the grass is air", failures);
            expect(!bb.isDestroyingBlock(), "insta: no destroy in progress", failures);

            // 2. STOP without a START (Kestrel's old path broke here): no break, acked.
            BlockPos lone = new BlockPos(fx - 2, fy, fz);
            loop.setBlock(lone, Blocks.DIRT.defaultBlockState(), 18);
            client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(STOP, lone, ++seq));
            seen = NetCheck.pump(loop, server, sender, client, 3);
            expect(loop.getBlockState(lone) == Blocks.DIRT.defaultBlockState(),
                    "stop-only: block survives STOP without START", failures);
            expect(sawAck(seen, seq) || awaitAck(loop, server, sender, client, seq), "stop-only: acked", failures);

            // 3. Early STOP on dirt: delayed destroy finishing on the exact
            // vanilla tick (ServerPlayerGameMode.tick, :109-120).
            BlockPos dirt = new BlockPos(fx, fy, fz + 2);
            loop.setBlock(dirt, Blocks.DIRT.defaultBlockState(), 18);
            float dirtPerTick = Blocks.DIRT.defaultBlockState().getDestroyProgress(standIn, standIn.level(), dirt);
            expect(dirtPerTick == 1.0F / 0.5F / 30.0F,
                    "dirt: bare-hand on-ground progress 1/0.5/30, got " + dirtPerTick, failures);
            // START, a few ticks of digging, then an early STOP: the delayed
            // destroy keeps counting from the START tick (delayedTickStart =
            // destroyProgressStart, :235).
            client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(START, dirt, ++seq));
            NetCheck.pump(loop, server, sender, client, 1);
            int dirtStart = bb.destroyProgressStart();
            NetCheck.pump(loop, server, sender, client, 3);
            client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(STOP, dirt, ++seq));
            seen = NetCheck.pump(loop, server, sender, client, 1);
            expect(bb.hasDelayedDestroy() && loop.getBlockState(dirt) == Blocks.DIRT.defaultBlockState(),
                    "delayed: early STOP schedules a delayed destroy, " + bb, failures);
            int need = 0;
            while (dirtPerTick * (need + 1) < 1.0F) {
                need++;
            }
            int expectedBreakTick = dirtStart + need;
            int brokeAt = -1;
            for (int i = 0; i < need + 5 && brokeAt < 0; i++) {
                seen.addAll(NetCheck.pump(loop, server, sender, client, 1));
                if (loop.getBlockState(dirt) == air) {
                    brokeAt = bb.gameTicks();
                }
            }
            expect(brokeAt == expectedBreakTick,
                    "delayed: dirt breaks on game tick " + expectedBreakTick + ", broke at " + brokeAt, failures);
            expect(!bb.hasDelayedDestroy(), "delayed: delayed destroy cleared", failures);
            expect(sawAck(seen, seq), "delayed: STOP acked", failures);

            // 4. Late STOP: progress past 0.7 breaks immediately.
            BlockPos dirt2 = new BlockPos(fx, fy, fz - 2);
            loop.setBlock(dirt2, Blocks.DIRT.defaultBlockState(), 18);
            client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(START, dirt2, ++seq));
            // Stop with the progress near 0.8: past vanilla's 0.7 early-finish
            // tolerance (:224-228) but short of a full break.
            NetCheck.pump(loop, server, sender, client, 1);
            int lateStart = bb.destroyProgressStart();
            while (dirtPerTick * (bb.gameTicks() - lateStart + 2) < 0.8F) {
                NetCheck.pump(loop, server, sender, client, 1);
            }
            expect(loop.getBlockState(dirt2) == Blocks.DIRT.defaultBlockState() && bb.isDestroyingBlock(),
                    "late: block held while destroying without STOP", failures);
            client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(STOP, dirt2, ++seq));
            seen = NetCheck.pump(loop, server, sender, client, 1);
            float lateProgress = dirtPerTick * (bb.gameTicks() - lateStart + 1);
            expect(lateProgress >= 0.7F && lateProgress < 1.0F,
                    "late: STOP landed between 0.7 and 1, progress " + lateProgress, failures);
            expect(loop.getBlockState(dirt2) == air && !bb.hasDelayedDestroy() && !bb.isDestroyingBlock(),
                    "late: STOP past 0.7 breaks at once, " + bb, failures);
            expect(sawAck(seen, seq) || awaitAck(loop, server, sender, client, seq), "late: STOP acked", failures);

            // 5. ABORT: no break.
            BlockPos aborted = new BlockPos(fx + 2, fy, fz + 2);
            loop.setBlock(aborted, stone, 18);
            client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(START, aborted, ++seq));
            client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(ABORT, aborted, ++seq));
            seen = NetCheck.pump(loop, server, sender, client, 4);
            expect(loop.getBlockState(aborted) == stone && !bb.isDestroyingBlock() && !bb.hasDelayedDestroy(),
                    "abort: no break and no destroy in progress, " + bb, failures);
            expect(sawAck(seen, seq) || awaitAck(loop, server, sender, client, seq), "abort: acked", failures);

            // 6. START on a second block while destroying the first: the
            // first is re-sent to the breaker (:206-209).
            BlockPos first = new BlockPos(fx - 2, fy, fz - 2);
            BlockPos second = new BlockPos(fx - 2, fy, fz + 2);
            loop.setBlock(first, stone, 18);
            loop.setBlock(second, stone, 18);
            client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(START, first, ++seq));
            NetCheck.pump(loop, server, sender, client, 1);
            client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(START, second, ++seq));
            seen = NetCheck.pump(loop, server, sender, client, 2);
            expect(sawBlockUpdate(seen, first, stone), "switch: first block re-sent on second START", failures);
            expect(bb.isDestroyingBlock() && bb.destroyPos().equals(second),
                    "switch: now destroying the second block, " + bb, failures);
            client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(ABORT, second, ++seq));
            NetCheck.pump(loop, server, sender, client, 1);

            // 7. Too far: nothing happens, no correction, still acked (:155-156).
            BlockPos far = new BlockPos(fx + 7, fy, fz + 7);
            loop.setBlock(far, Blocks.SHORT_GRASS.defaultBlockState(), 18);
            NetCheck.pump(loop, server, sender, client, 2); // consume the setup write's own publication
            client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(START, far, ++seq));
            seen = NetCheck.pump(loop, server, sender, client, 3);
            expect(loop.getBlockState(far) == Blocks.SHORT_GRASS.defaultBlockState(),
                    "far: out-of-reach grass survives", failures);
            expect(sawAck(seen, seq) || awaitAck(loop, server, sender, client, seq), "far: acked", failures);
            expect(!sawAnyBlockUpdate(seen, far), "far: no correction sent", failures);

            // 8. Correct tool from the bound tags: a wooden pickaxe mines
            // stone at 2/1.5/30 per tick, and a STOP after 25 ticks breaks it
            // (bare hand would be 1/1.5/100, far below 0.7 by then).
            p.inventory.inventory().setItem(p.inventory.selectedSlot(), woodenPickaxe());
            BlockPos mined = new BlockPos(fx + 2, fy, fz - 2);
            loop.setBlock(mined, stone, 18);
            float pickPerTick = stone.getDestroyProgress(standIn, standIn.level(), mined);
            expect(pickPerTick == 2.0F / 1.5F / 30.0F,
                    "pickaxe: stone progress 2/1.5/30, got " + pickPerTick, failures);
            client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(START, mined, ++seq));
            NetCheck.pump(loop, server, sender, client, 25);
            client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(STOP, mined, ++seq));
            seen = NetCheck.pump(loop, server, sender, client, 1);
            expect(loop.getBlockState(mined) == air, "pickaxe: stone broken after 25 ticks, " + bb, failures);
            // Keep the following ticks' packets for the wear checks (awaitAck would drop them).
            seen.addAll(NetCheck.pump(loop, server, sender, client, 3));
            expect(sawAck(seen, seq), "pickaxe: STOP acked", failures);
            checkWear(loop, server, sender, client, p, standIn, fx, fy, fz, seq, seen, failures);
            seq += 10;
            p.inventory.inventory().setItem(p.inventory.selectedSlot(), ItemStack.EMPTY);

            // 10. Crack stages (ServerLevel.destroyBlockProgress): a second
            // player beside the breaker sees every stage vanilla sends, the
            // breaker none. Before the seagrass, whose water would slow digging.
            checkCracks(loop, server, sender, client, p, standIn, fx, fy, fz, seq, failures);
            seq += 100;

            // 9. Level.removeBlock leaves the fluid's legacy block: seagrass
            // becomes water, not air. Last, because the water flows.
            BlockPos sea = new BlockPos(fx + 3, fy, fz + 3);
            loop.setBlock(sea, Blocks.SEAGRASS.defaultBlockState(), 18);
            client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(START, sea, ++seq));
            seen = NetCheck.pump(loop, server, sender, client, 1);
            expect(loop.getBlockState(sea) == Blocks.WATER.defaultBlockState(),
                    "fluid: seagrass leaves a water source, got " + loop.getBlockState(sea), failures);
            expect(sawAck(seen, seq) || awaitAck(loop, server, sender, client, seq), "fluid: acked", failures);

            expect(server.players.size() == 2, "both sessions survive every action", failures);
            client.socket.close();
        } catch (Exception e) {
            failures.add("loopback: threw " + e);
            e.printStackTrace(System.out);
        } finally {
            if (server != null) {
                server.close();
            }
        }
    }

    /**
     * Spawn protection ({@code DedicatedServer.isUnderSpawnProtection},
     * {@code DedicatedServer.java:523-539}) with {@code spawn-protection=2}
     * around a fixed spawn at (0, 1, 0). With an empty ops list nothing is
     * protected (Kestrel's live state until S36). With an operator other than
     * the breaker, START inside the radius leaves the block, answers with
     * vanilla's red {@code build.spawn_protection} overlay
     * ({@code ServerPlayer.sendSpawnProtectionMessage}) and no block update,
     * and is still acked; one block past the radius breaks. An operator
     * breaker is never protected against.
     */
    private static void checkSpawnProtection(List<String> failures) {
        NetServer server = null;
        try {
            BlockState stone = Blocks.STONE.defaultBlockState();
            KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
            TickLoop loop = new TickLoop(level);
            for (int x = -8; x <= 8; x++) {
                for (int z = -8; z <= 8; z++) {
                    loop.setBlock(new BlockPos(x, 0, z), stone, 3);
                }
            }
            server = new NetServer(loop, 0, 256, 4242424242L);
            server.setFixedSpawn(new BlockPos(0, 1, 0));
            Path dir = Files.createTempDirectory("kestrel-g18-spawn");
            Path file = dir.resolve("server.properties");
            Files.writeString(file, "spawn-protection=2\n");
            server.setProperties(ServerProperties.load(file));
            server.start();
            ChunkSender sender = new ChunkSender(loop, NetCheck.plainsId());
            NetCheck.Loopback client = NetCheck.joinLoopback(loop, server, sender, "SpawnBot");
            NetCheck.pump(loop, server, sender, client, 20);
            client.sendC(PacketIds.PLAY_MOVE_STATUS, new byte[] {1});
            NetCheck.pump(loop, server, sender, client, 2);
            KestrelPlayer p = server.players.values().iterator().next();
            ServerPlayer standIn = server.bodies().body(p).vanillaPlayer();
            expect(standIn.getBlockX() == 0 && standIn.getBlockZ() == 0,
                    "spawn: breaker joined at the fixed spawn's column, at " + standIn.blockPosition(), failures);
            BlockState grass = Blocks.SHORT_GRASS.defaultBlockState();
            int seq = 500;

            BlockPos open = new BlockPos(2, 1, 0);
            loop.setBlock(open, grass, 18);
            NetCheck.pump(loop, server, sender, client, 2);
            client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(START, open, ++seq));
            List<Wire.Inbound> seen = NetCheck.pump(loop, server, sender, client, 3);
            expect(loop.getBlockState(open).isAir(),
                    "spawn: no ops, nothing protected (grass at radius breaks)", failures);
            expect(spawnMessages(seen).isEmpty(), "spawn: no ops, no message", failures);

            server.op(java.util.UUID.randomUUID(), "other-op");
            BlockPos guarded = new BlockPos(0, 1, 2);
            loop.setBlock(guarded, grass, 18);
            NetCheck.pump(loop, server, sender, client, 2);
            client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(START, guarded, ++seq));
            seen = NetCheck.pump(loop, server, sender, client, 3);
            expect(loop.getBlockState(guarded) == grass, "spawn: grass at the radius survives a non-op", failures);
            net.minecraft.network.chat.Component expected = net.minecraft.network.chat.Component.translatable(
                    "build.spawn_protection", guarded.toShortString()).withStyle(net.minecraft.ChatFormatting.RED);
            List<String> messages = spawnMessages(seen);
            expect(messages.equals(List.of("overlay " + expected)),
                    "spawn: red build.spawn_protection overlay, saw " + messages + " expected overlay " + expected, failures);
            expect(!sawAnyBlockUpdate(seen, guarded), "spawn: no block update on START", failures);
            expect(sawAck(seen, seq) || awaitAck(loop, server, sender, client, seq), "spawn: protected START acked", failures);
            expect(!p.blockBreaking.isDestroyingBlock(), "spawn: no dig in progress", failures);

            // ServerPlayerGameMode.java:160-166 returns before the hit effects (:190-199): a
            // protected redstone ore is not lit by the hit.
            BlockPos guardedOre = new BlockPos(0, 1, -2);
            BlockState dark = Blocks.REDSTONE_ORE.defaultBlockState();
            loop.setBlock(guardedOre, dark, 18);
            NetCheck.pump(loop, server, sender, client, 2);
            loop.setLevelRandom(RandomSource.create(SEED_ORE));
            client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(START, guardedOre, ++seq));
            seen = NetCheck.pump(loop, server, sender, client, 3);
            expect(loop.getBlockState(guardedOre) == dark && !sawAnyBlockUpdate(seen, guardedOre),
                    "spawn: a protected ore is not lit by the hit", failures);
            expect(loop.levelRandom().nextLong() == RandomSource.create(SEED_ORE).nextLong(),
                    "spawn: a protected START draws nothing from the level random", failures);
            loop.setBlock(guardedOre, Blocks.AIR.defaultBlockState(), 18);

            // DedicatedServer.java:536-538: both axes contribute to the radius.
            BlockPos pastZ = new BlockPos(1, 1, -3);
            loop.setBlock(pastZ, grass, 18);
            NetCheck.pump(loop, server, sender, client, 2);
            client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(START, pastZ, ++seq));
            seen = NetCheck.pump(loop, server, sender, client, 3);
            expect(loop.getBlockState(pastZ).isAir(),
                    "spawn: inside in x, past the radius in z breaks for a non-op", failures);
            expect(spawnMessages(seen).isEmpty(), "spawn: no message past the z radius", failures);

            BlockPos past = new BlockPos(-3, 1, 1);
            loop.setBlock(past, grass, 18);
            NetCheck.pump(loop, server, sender, client, 2);
            client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(START, past, ++seq));
            seen = NetCheck.pump(loop, server, sender, client, 3);
            expect(loop.getBlockState(past).isAir(), "spawn: one past the radius breaks for a non-op", failures);
            expect(spawnMessages(seen).isEmpty(), "spawn: no message past the radius", failures);

            server.op(p.profile, p.name);
            client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(START, guarded, ++seq));
            seen = NetCheck.pump(loop, server, sender, client, 3);
            expect(loop.getBlockState(guarded).isAir(), "spawn: an operator breaks inside the radius", failures);
            expect(spawnMessages(seen).isEmpty(), "spawn: no message for an operator", failures);
            client.socket.close();
        } catch (Exception e) {
            failures.add("spawn: threw " + e);
            e.printStackTrace(System.out);
        } finally {
            if (server != null) {
                server.close();
            }
        }
    }

    /** System chat packets, decoded by vanilla's codec, as "overlay|chat component". */
    private static List<String> spawnMessages(List<Wire.Inbound> seen) {
        List<String> out = new ArrayList<>();
        for (Wire.Inbound in : seen) {
            if (in.id() == PacketIds.PLAY_SYSTEM_CHAT) {
                net.minecraft.network.RegistryFriendlyByteBuf buf = new net.minecraft.network.RegistryFriendlyByteBuf(
                        io.netty.buffer.Unpooled.wrappedBuffer(in.payload()),
                        net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
                net.minecraft.network.protocol.game.ClientboundSystemChatPacket packet =
                        net.minecraft.network.protocol.game.ClientboundSystemChatPacket.STREAM_CODEC.decode(buf);
                out.add((packet.overlay() ? "overlay " : "chat ") + packet.content());
            }
        }
        return out;
    }

    /**
     * Tool wear ({@code ServerPlayerGameMode.java:293}, {@code ItemStack.mineBlock}):
     * the stone just broken with a fresh wooden pickaxe ({@code seen} holds
     * that break's packets) took one point of damage
     * ({@code Tool.damagePerBlock}) on the held stack, the client's hotbar
     * slot carries it, and {@code ITEM_USED} counts the use. Short grass
     * (destroy speed 0, {@code Item.mineBlock}) costs no durability but still
     * counts a use. The wrong tool (a pickaxe on a snow layer) still wears.
     * The last point breaks the tool: the slot empties, the
     * break event 47 (main hand) goes to the breaker, and {@code ITEM_BROKEN}
     * counts it.
     */
    private static void checkWear(TickLoop loop, NetServer server, ChunkSender sender, NetCheck.Loopback client,
            KestrelPlayer p, ServerPlayer standIn, int fx, int fy, int fz, int seq, List<Wire.Inbound> seen,
            List<String> failures) throws Exception {
        int slot = 36 + p.inventory.selectedSlot();
        net.minecraft.stats.Stat<net.minecraft.world.item.Item> used =
                net.minecraft.stats.Stats.ITEM_USED.get(Items.WOODEN_PICKAXE);
        net.minecraft.stats.Stat<net.minecraft.world.item.Item> broken =
                net.minecraft.stats.Stats.ITEM_BROKEN.get(Items.WOODEN_PICKAXE);
        net.minecraft.stats.ServerStatsCounter stats = ((net.minecraft.server.level.ServerPlayer) standIn).getStats();
        ItemStack held = standIn.getMainHandItem();
        expect(held.is(Items.WOODEN_PICKAXE) && held.getDamageValue() == 1,
                "wear: pickaxe damage 1 after one stone, held " + held + " damage " + held.getDamageValue(), failures);
        List<ItemStack> sent = sentSlot(seen, slot);
        expect(!sent.isEmpty() && sent.get(sent.size() - 1).is(Items.WOODEN_PICKAXE)
                        && sent.get(sent.size() - 1).getDamageValue() == 1,
                "wear: client's slot " + slot + " shows damage 1, sent " + describeStacks(sent), failures);
        expect(stats.getValue(used) == 1, "wear: ITEM_USED 1 after one stone, got " + stats.getValue(used), failures);

        BlockPos grass = new BlockPos(fx - 3, fy, fz - 3);
        loop.setBlock(grass, Blocks.SHORT_GRASS.defaultBlockState(), 18);
        client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(START, grass, ++seq));
        NetCheck.pump(loop, server, sender, client, 3);
        expect(loop.getBlockState(grass).isAir(), "wear: grass broken with the pickaxe", failures);
        held = standIn.getMainHandItem();
        expect(held.is(Items.WOODEN_PICKAXE) && held.getDamageValue() == 1,
                "wear: grass (destroy speed 0) costs no durability, damage " + held.getDamageValue(), failures);
        expect(stats.getValue(used) == 2, "wear: grass still counts ITEM_USED, got " + stats.getValue(used), failures);

        // Snow layer: hardness 0.1, needs a shovel for drops. The wrong tool
        // still wears (mineBlock runs before the canDestroy test, :292-296).
        BlockPos snow = new BlockPos(fx - 3, fy, fz + 3);
        loop.setBlock(snow, Blocks.SNOW.defaultBlockState(), 18);
        expect(!standIn.hasCorrectToolForDrops(Blocks.SNOW.defaultBlockState()),
                "wear: a pickaxe is not the correct tool for snow", failures);
        client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(START, snow, ++seq));
        NetCheck.pump(loop, server, sender, client, 12);
        client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(STOP, snow, ++seq));
        NetCheck.pump(loop, server, sender, client, 3);
        expect(loop.getBlockState(snow).isAir(), "wear: snow layer broken", failures);
        held = standIn.getMainHandItem();
        expect(held.is(Items.WOODEN_PICKAXE) && held.getDamageValue() == 2,
                "wear: the wrong tool still wears (snow), damage " + held.getDamageValue(), failures);
        expect(stats.getValue(used) == 3, "wear: snow counts ITEM_USED, got " + stats.getValue(used), failures);

        ItemStack last = woodenPickaxe();
        last.setDamageValue(last.getMaxDamage() - 1);
        p.inventory.inventory().setItem(p.inventory.selectedSlot(), last);
        NetCheck.pump(loop, server, sender, client, 2);
        BlockPos stone = new BlockPos(fx + 3, fy, fz - 3);
        loop.setBlock(stone, Blocks.STONE.defaultBlockState(), 18);
        client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(START, stone, ++seq));
        NetCheck.pump(loop, server, sender, client, 25);
        client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(STOP, stone, ++seq));
        List<Wire.Inbound> after = NetCheck.pump(loop, server, sender, client, 3);
        expect(loop.getBlockState(stone).isAir(), "wear: stone broken with the last point", failures);
        expect(standIn.getMainHandItem().isEmpty(),
                "wear: the worn-out pickaxe is gone, held " + standIn.getMainHandItem(), failures);
        List<ItemStack> emptied = sentSlot(after, slot);
        expect(!emptied.isEmpty() && emptied.get(emptied.size() - 1).isEmpty(),
                "wear: client's slot " + slot + " emptied, sent " + describeStacks(emptied), failures);
        List<Integer> events = entityEvents(after, p.entityId);
        expect(events.equals(List.of(47)),
                "wear: break event 47 (main hand) to the breaker, saw " + events, failures);
        expect(stats.getValue(broken) == 1, "wear: ITEM_BROKEN 1, got " + stats.getValue(broken), failures);
        expect(stats.getValue(used) == 4, "wear: ITEM_USED 4, got " + stats.getValue(used), failures);
    }

    /** Every stack sent for {@code slot} of the player's own menu (container 0), decoded by vanilla's codec. */
    private static List<ItemStack> sentSlot(List<Wire.Inbound> seen, int slot) {
        List<ItemStack> out = new ArrayList<>();
        for (Wire.Inbound in : seen) {
            if (in.id() == PacketIds.PLAY_CONTAINER_SET_SLOT) {
                net.minecraft.network.RegistryFriendlyByteBuf buf = new net.minecraft.network.RegistryFriendlyByteBuf(
                        io.netty.buffer.Unpooled.wrappedBuffer(in.payload()),
                        net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
                net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket packet =
                        net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket.STREAM_CODEC.decode(buf);
                if (packet.getContainerId() == 0 && packet.getSlot() == slot) {
                    out.add(packet.getItem());
                }
            }
        }
        return out;
    }

    private static String describeStacks(List<ItemStack> stacks) {
        List<String> out = new ArrayList<>();
        for (ItemStack stack : stacks) {
            out.add(stack.isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(stack.getItem()) + "@" + stack.getDamageValue());
        }
        return out.toString();
    }

    /** Entity events (ClientboundEntityEventPacket: int id, byte event) for {@code entityId}. */
    private static List<Integer> entityEvents(List<Wire.Inbound> seen, int entityId) throws Exception {
        List<Integer> out = new ArrayList<>();
        for (Wire.Inbound in : seen) {
            if (in.id() == PacketIds.PLAY_ENTITY_EVENT) {
                Wire.Reader r = in.reader();
                int id = r.i();
                int event = r.b();
                if (id == entityId) out.add(event);
            }
        }
        return out;
    }

    /**
     * The crack scenarios, with a watcher joined beside the breaker. Every
     * stage the watcher sees is decoded as {@code pos:stage}; the breaker's
     * stream must hold none ({@code destroyBlockProgress} skips the
     * breaker's own id).
     */
    private static void checkCracks(TickLoop loop, NetServer server, ChunkSender sender, NetCheck.Loopback client,
            KestrelPlayer p, ServerPlayer standIn, int fx, int fy, int fz, int seq, List<String> failures)
            throws Exception {
        BlockBreaking bb = p.blockBreaking;
        BlockState dirtState = Blocks.DIRT.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        NetCheck.Loopback watcher = NetCheck.joinLoopback(loop, server, sender, "CrackWatcher", 2);
        List<Wire.Inbound> mine = new ArrayList<>();
        List<Wire.Inbound> theirs = new ArrayList<>();
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 20);
        expect(server.players.size() == 2, "cracks: watcher joined", failures);
        KestrelPlayer w = null;
        for (KestrelPlayer each : server.players.values()) {
            if (each != p) {
                w = each;
            }
        }
        expect(w != null && server.bodies().clientLoaded(w), "cracks: watcher loaded", failures);
        System.out.println("  watcher at " + (w == null ? "?" : w.x + "," + w.y + "," + w.z));
        theirs.clear();

        // a. Dig dirt by hand, then ABORT: stage 0 on START, each change of
        // (int)(p * (ticks + 1) * 10) while digging (dirt by hand repeats a
        // stage every third tick, which is not resent), then -1.
        BlockPos a = new BlockPos(fx + 1, fy, fz - 2);
        loop.setBlock(a, dirtState, 18);
        float perTick = dirtState.getDestroyProgress(standIn, standIn.level(), a);
        client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(START, a, ++seq));
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 6);
        client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(ABORT, a, ++seq));
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 2);
        List<String> seenA = cracks(theirs, p.entityId, failures);
        List<String> wantA = null;
        for (int ticks = 1; ticks <= 12 && !seenA.equals(wantA); ticks++) {
            wantA = new ArrayList<>();
            int last = (int) (perTick * 10.0F);
            wantA.add(a.asLong() + ":" + last);
            for (int k = 1; k <= ticks; k++) {
                int state = (int) (perTick * (k + 1) * 10.0F);
                if (state != last) {
                    wantA.add(a.asLong() + ":" + state);
                    last = state;
                }
            }
            wantA.add(a.asLong() + ":255");
        }
        expect(seenA.equals(wantA) && seenA.size() >= 4,
                "cracks: dig then abort sends vanilla's stages, saw " + seenA + " want " + wantA, failures);
        expect(loop.getBlockState(a) == dirtState && !bb.isDestroyingBlock(), "cracks: aborted dirt stays", failures);
        theirs.clear();

        // b. The block turns to air under the digger: -1 on the next tick.
        BlockPos b = new BlockPos(fx - 1, fy, fz + 2);
        loop.setBlock(b, dirtState, 18);
        client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(START, b, ++seq));
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 2);
        loop.setBlock(b, air, 18);
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 2);
        List<String> seenB = cracks(theirs, p.entityId, failures);
        expect(!seenB.isEmpty() && seenB.get(0).equals(b.asLong() + ":0")
                        && seenB.get(seenB.size() - 1).equals(b.asLong() + ":255") && !bb.isDestroyingBlock(),
                "cracks: block gone mid-dig clears the crack, saw " + seenB, failures);
        theirs.clear();

        // c. ABORT at another block than the one being dug: -1 at the dug
        // block, then -1 at the aborted one.
        BlockPos c = new BlockPos(fx + 1, fy, fz + 2);
        BlockPos other = new BlockPos(fx - 1, fy, fz - 2);
        loop.setBlock(c, dirtState, 18);
        client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(START, c, ++seq));
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 1);
        client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(ABORT, other, ++seq));
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 2);
        List<String> seenC = cracks(theirs, p.entityId, failures);
        int n = seenC.size();
        expect(n >= 3 && seenC.get(0).equals(c.asLong() + ":0")
                        && seenC.get(n - 2).equals(c.asLong() + ":255")
                        && seenC.get(n - 1).equals(other.asLong() + ":255"),
                "cracks: mismatched abort clears both, saw " + seenC, failures);
        theirs.clear();

        // d. A late STOP (progress at least 0.7) clears the crack, then breaks.
        BlockPos d = new BlockPos(fx - 2, fy, fz + 1);
        loop.setBlock(d, dirtState, 18);
        client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(START, d, ++seq));
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 11);
        expect(bb.isDestroyingBlock() && perTick * (bb.gameTicks() - bb.destroyProgressStart() + 1) >= 0.7F
                        && perTick * (bb.gameTicks() - bb.destroyProgressStart() + 1) < 1.0F,
                "cracks: late-stop setup in [0.7, 1), " + bb, failures);
        client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(STOP, d, ++seq));
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 2);
        List<String> seenD = cracks(theirs, p.entityId, failures);
        expect(loop.getBlockState(d) == air && !seenD.isEmpty()
                        && seenD.get(seenD.size() - 1).equals(d.asLong() + ":255"),
                "cracks: late STOP clears the crack and breaks, saw " + seenD, failures);
        theirs.clear();

        // e. An early STOP finishes on a later tick; vanilla sends no -1 then.
        BlockPos e = new BlockPos(fx + 2, fy, fz - 1);
        loop.setBlock(e, dirtState, 18);
        client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(START, e, ++seq));
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 3);
        client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(STOP, e, ++seq));
        for (int i = 0; i < 30 && loop.getBlockState(e) != air; i++) {
            pumpBoth(loop, server, sender, client, watcher, mine, theirs, 1);
        }
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 2);
        List<String> seenE = cracks(theirs, p.entityId, failures);
        int finalTicks = 1;
        while (perTick * (finalTicks + 1) < 1.0F) {
            finalTicks++;
        }
        int finalStage = (int) (perTick * (finalTicks + 1) * 10.0F);
        expect(loop.getBlockState(e) == air && !seenE.isEmpty() && !seenE.contains(e.asLong() + ":255")
                        && seenE.get(seenE.size() - 1).equals(e.asLong() + ":" + finalStage),
                "cracks: delayed destroy ends on its last stage, no clear, saw " + seenE, failures);

        expect(cracks(mine, p.entityId, failures).isEmpty(), "cracks: the breaker is sent none of its own", failures);

        // f. A finished break plays vanilla's level event 2001 (the break
        // sound and particles) to everyone within 64 but the breaker:
        // Block.playerWillDestroy -> spawnDestroyParticles ->
        // ServerLevel.levelEvent(player, ...) (Block.java:511-516,
        // ServerLevel.java:1110-1122).
        mine.clear();
        theirs.clear();
        BlockPos f = new BlockPos(fx - 1, fy, fz + 1);
        BlockState shortGrass = Blocks.SHORT_GRASS.defaultBlockState();
        loop.setBlock(f, shortGrass, 18);
        client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(START, f, ++seq));
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 3);
        expect(loop.getBlockState(f).isAir(), "effect: short grass broken on START", failures);
        expect(levelEvents(theirs, failures).equals(List.of("2001@" + f.asLong() + ":" + Block.getId(shortGrass))),
                "effect: watcher gets one 2001 with the grass's id, saw " + levelEvents(theirs, failures), failures);
        expect(levelEvents(mine, failures).isEmpty(),
                "effect: the breaker gets no 2001 of its own, saw " + levelEvents(mine, failures), failures);

        // g. Tall grass: vanilla's DoublePlantBlock.playerWillDestroy runs
        // (DoublePlantBlock.java:98-108); the lower half's 2001 skips the
        // breaker, the server keeps running and both halves end as air.
        mine.clear();
        theirs.clear();
        BlockPos g = new BlockPos(fx + 1, fy, fz + 1);
        BlockState lower = Blocks.TALL_GRASS.defaultBlockState();
        BlockState upper = lower.setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
        loop.setBlock(g, lower, 18);
        loop.setBlock(g.above(), upper, 18);
        client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(START, g, ++seq));
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 3);
        System.out.println("  tall grass: watcher " + levelEvents(theirs, failures) + " breaker "
                + levelEvents(mine, failures));
        expect(loop.getBlockState(g).isAir() && loop.getBlockState(g.above()).isAir(),
                "effect: both tall grass halves gone, now " + loop.getBlockState(g) + " / "
                        + loop.getBlockState(g.above()), failures);
        // The lower half's 2001 comes from playerWillDestroy (breaker left
        // out); the upper half then goes through the neighbour shape update's
        // Level.destroyBlock, whose 2001 has no source, so both get that one.
        String lowerEvent = "2001@" + g.asLong() + ":" + Block.getId(lower);
        String upperEvent = "2001@" + g.above().asLong() + ":" + Block.getId(upper);
        expect(levelEvents(theirs, failures).equals(List.of(lowerEvent, upperEvent)),
                "effect: watcher gets the lower half's 2001, then the upper's", failures);
        expect(levelEvents(mine, failures).equals(List.of(upperEvent)),
                "effect: the breaker gets only the upper half's", failures);
        expect(server.players.size() == 2, "effect: both sessions survive tall grass", failures);

        // h. G19 drops through the whole path: ServerPlayerGameMode.destroyBlock
        // (:287-299) -> Block.playerDestroy -> dropResources -> popResource.
        // Stone with a wooden pickaxe (the correct tool) drops one cobblestone
        // (loot_table/blocks/stone.json, no silk touch), spawned for both players.
        p.inventory.inventory().setItem(p.inventory.selectedSlot(), woodenPickaxe());
        mine.clear();
        theirs.clear();
        BlockPos h = new BlockPos(fx - 1, fy, fz - 1);
        loop.setBlock(h, Blocks.STONE.defaultBlockState(), 18);
        java.util.Set<Integer> beforeH = itemIds(items(server, h));
        client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(START, h, ++seq));
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 25);
        client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(STOP, h, ++seq));
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 3);
        expect(loop.getBlockState(h).isAir(), "drops: stone broken with the wooden pickaxe", failures);
        List<dev.kestrel.entity.KestrelItemEntity> stoneDrops = fresh(items(server, h), beforeH);
        expect(stoneDrops.size() == 1 && stoneDrops.get(0).getItem().is(Items.COBBLESTONE)
                        && stoneDrops.get(0).getItem().getCount() == 1,
                "drops: stone drops exactly one cobblestone, got " + describe(stoneDrops), failures);
        if (stoneDrops.size() == 1) {
            dev.kestrel.entity.KestrelItemEntity drop = stoneDrops.get(0);
            java.util.Map<Integer, double[]> watcherItems = addedItems(theirs, failures);
            java.util.Map<Integer, double[]> breakerItems = addedItems(mine, failures);
            expect(watcherItems.containsKey(drop.getId()), "drops: watcher sees the cobblestone spawn, saw "
                    + watcherItems.keySet(), failures);
            expect(breakerItems.containsKey(drop.getId()), "drops: breaker sees the cobblestone spawn, saw "
                    + breakerItems.keySet(), failures);
            // Block.popResource (Block.java:413-420): centre +-0.25 (the spawn position, before
            // the item's own motion carries it), y at the centre minus half the item's height.
            double[] at = watcherItems.get(drop.getId());
            if (at != null) {
                expect(Math.abs(at[0] - (h.getX() + 0.5)) <= 0.25 + 1.0E-9
                        && Math.abs(at[2] - (h.getZ() + 0.5)) <= 0.25 + 1.0E-9
                        && Math.abs(at[1] - (h.getY() + 0.5 - 0.125)) <= 0.25 + 1.0E-9,
                        "drops: popped within 0.25 of the block centre, at "
                                + java.util.Arrays.toString(at), failures);
            }
            drop.setPickUpDelay(300);
        }

        // i. Iron ore with a wooden pickaxe is not the correct tool
        // (incorrect_for_wooden_tool): hasCorrectToolForDrops is false, so
        // playerDestroy never runs and nothing drops (:294-296).
        BlockPos i = new BlockPos(fx + 1, fy, fz - 1);
        loop.setBlock(i, Blocks.IRON_ORE.defaultBlockState(), 18);
        java.util.Set<Integer> beforeI = itemIds(allItems(server));
        mine.clear();
        theirs.clear();
        client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(START, i, ++seq));
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 110);
        client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(STOP, i, ++seq));
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 3);
        expect(loop.getBlockState(i).isAir(), "drops: iron ore broken with the wooden pickaxe, now "
                + loop.getBlockState(i), failures);
        List<dev.kestrel.entity.KestrelItemEntity> oreDrops = fresh(allItems(server), beforeI);
        expect(oreDrops.isEmpty(), "drops: iron ore with the wrong tool drops nothing, got "
                + describe(oreDrops), failures);
        java.util.Set<Integer> spawnedForWatcher = new java.util.TreeSet<>(addedItems(theirs, failures).keySet());
        spawnedForWatcher.removeAll(beforeI);
        expect(spawnedForWatcher.isEmpty(), "drops: watcher sees no new item spawn for iron ore, saw "
                + spawnedForWatcher + " (already present " + beforeI + ")", failures);
        expect(server.players.size() == 2, "drops: both sessions survive", failures);

        // j. G19 slice 2 on the live loop: a second cobblestone dropped beside the
        // first merges into it (ItemEntity.mergeWithNeighbours, :215-227) within
        // two 40-tick merge windows, and the watcher is told the merged one left.
        // The second spawns at rest 0.2 away (inside the 0.5 inflate): a random pop
        // velocity could carry it out of range, where vanilla would not merge either.
        List<dev.kestrel.entity.KestrelItemEntity> resting = fresh(items(server, h), beforeH);
        expect(resting.size() == 1, "merge: the stone's cobblestone is still there, got " + describe(resting), failures);
        if (resting.size() == 1) {
            dev.kestrel.entity.KestrelItemEntity first = resting.get(0);
            dev.kestrel.entity.KestrelItemEntity second = server.entityWorld().spawnItem(first.getX() + 0.2,
                    first.getY(), first.getZ(), new ItemStack(Items.COBBLESTONE), 0.0, 0.0, 0.0);
            expect(second != null, "merge: second cobblestone spawned", failures);
            // Out of the breaker's reach for the whole window: since slice 3 the
            // breaker's touch loop (Player.aiStep, :454-471) would pick it up at delay 0.
            if (second != null) second.setPickUpDelay(300);
            mine.clear();
            theirs.clear();
            pumpBoth(loop, server, sender, client, watcher, mine, theirs, 85);
            List<dev.kestrel.entity.KestrelItemEntity> after = fresh(items(server, h), beforeH);
            expect(after.size() == 1 && after.get(0).getItem().is(Items.COBBLESTONE)
                            && after.get(0).getItem().getCount() == 2,
                    "merge: one cobblestone x2 remains, got " + describe(after) + " " + mergeState(first, second),
                    failures);
            if (second != null && after.size() == 1) {
                int gone = after.get(0) == first ? second.getId() : first.getId();
                expect(removedIds(theirs).contains(gone), "merge: watcher told entity " + gone + " left, saw "
                        + removedIds(theirs), failures);
            }
        }

        // k. G19 slice 3 on the live loop: dropping with Q (DROP_ITEM, action 4)
        // spawns the item entity for the watcher, and picking it up sends the
        // take packet (0x7C) and remove packet (0x4D) to both clients, and updates
        // the breaker's hotbar slot.
        p.inventory.inventory().setItem(p.inventory.selectedSlot(), new ItemStack(Items.APPLE, 5));
        mine.clear();
        theirs.clear();
        client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(4, BlockPos.ZERO, ++seq));
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 3);
        expect(p.inventory.inventory().getItem(p.inventory.selectedSlot()).getCount() == 4,
                "q-drop: hotbar count decremented from 5 to 4, got "
                        + p.inventory.inventory().getItem(p.inventory.selectedSlot()).getCount(), failures);
        List<dev.kestrel.entity.KestrelItemEntity> droppedApples = allItems(server).stream()
                .filter(it -> it.getItem().is(Items.APPLE))
                .toList();
        expect(droppedApples.size() == 1, "q-drop: apple entity spawned in world, got "
                + describe(droppedApples), failures);
        if (droppedApples.size() == 1) {
            dev.kestrel.entity.KestrelItemEntity apple = droppedApples.get(0);
            expect(apple.getPickupDelay() >= 37 && apple.getPickupDelay() <= 40,
                    "q-drop: pickup delay is around 40, got " + apple.getPickupDelay(), failures);
            expect(p.profile.equals(apple.getThrower()), "q-drop: thrower is breaker", failures);
            expect(addedItems(theirs, failures).containsKey(apple.getId()),
                    "q-drop: watcher sees apple spawn", failures);

            // Pickup: clear delay and place at breaker's feet so aiStep's touch picks it up
            apple.setPickUpDelay(0);
            apple.setPos(standIn.getX(), standIn.getY(), standIn.getZ());
            mine.clear();
            theirs.clear();
            pumpBoth(loop, server, sender, client, watcher, mine, theirs, 3);

            expect(apple.isRemoved(), "pickup: apple entity discarded after pickup", failures);
            expect(p.inventory.inventory().getItem(p.inventory.selectedSlot()).getCount() == 5,
                    "pickup: hotbar count restored to 5, got "
                            + p.inventory.inventory().getItem(p.inventory.selectedSlot()).getCount(), failures);

            List<TakePacket> watcherTakes = takePackets(theirs);
            List<TakePacket> breakerTakes = takePackets(mine);
            expect(!watcherTakes.isEmpty() && watcherTakes.get(0).itemId == apple.getId()
                            && watcherTakes.get(0).playerId == p.entityId,
                    "pickup: watcher received take packet (0x7C) for apple, saw " + watcherTakes, failures);
            expect(!breakerTakes.isEmpty() && breakerTakes.get(0).itemId == apple.getId()
                            && breakerTakes.get(0).playerId == p.entityId,
                    "pickup: breaker received take packet (0x7C) for apple, saw " + breakerTakes, failures);
            expect(removedIds(theirs).contains(apple.getId()),
                    "pickup: watcher received remove packet (0x4D)", failures);
            expect(removedIds(mine).contains(apple.getId()),
                    "pickup: breaker received remove packet (0x4D)", failures);
            expect(sawSetSlot(mine, 36 + p.inventory.selectedSlot()),
                    "pickup: breaker received slot update (0x14)", failures);
        }

        // l. G17 slice 4 on the live loop: container click protocol.
        // 1. Client sends a real container click with correct prediction: pick up stack from hotbar 0 (menu slot 36).
        //    Server slots change, carried changes, client receives NO corrective packet.
        p.inventory.inventory().setItem(0, new ItemStack(Items.STONE, 64));
        p.inventory.inventory().setItem(1, ItemStack.EMPTY);
        p.inventory.broadcastChanges(ignored -> {});
        int clickStateId = p.inventory.menu().getStateId();
        mine.clear();
        theirs.clear();
        Int2ObjectMap<HashedStack> pickPrediction = new Int2ObjectOpenHashMap<>();
        pickPrediction.put(36, HashedStack.EMPTY);
        HashedStack carriedStone = HashedStack.create(new ItemStack(Items.STONE, 64), HASHER);
        byte[] pickPayload = containerClick(0, clickStateId, (short) 36, (byte) 0,
                ContainerInput.PICKUP, pickPrediction, carriedStone);
        client.sendC(PacketIds.PLAY_CONTAINER_CLICK, pickPayload);
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 3);
        expect(p.inventory.inventory().getItem(0).isEmpty(),
                "click: hotbar 0 emptied after pickup", failures);
        expect(p.inventory.carried().is(Items.STONE) && p.inventory.carried().getCount() == 64,
                "click: server carried holds stone x64", failures);
        expect(!sawSetSlot(mine, 36),
                "click: client received no corrective set-slot packet on correct prediction", failures);
        expect(!sawSetCursor(mine),
                "click: client received no corrective set-cursor packet on correct prediction", failures);

        // 2. Client puts down stack into slot 37 with mismatched prediction (predicts dirt instead of stone).
        //    Server puts stone in slot 37, carried emptied; client receives corrective set-slot (0x14).
        mine.clear();
        theirs.clear();
        clickStateId = p.inventory.menu().getStateId();
        Int2ObjectMap<HashedStack> wrongPrediction = new Int2ObjectOpenHashMap<>();
        wrongPrediction.put(37, HashedStack.create(new ItemStack(Items.DIRT, 64), HASHER));
        byte[] putPayload = containerClick(0, clickStateId, (short) 37, (byte) 0,
                ContainerInput.PICKUP, wrongPrediction, HashedStack.EMPTY);
        client.sendC(PacketIds.PLAY_CONTAINER_CLICK, putPayload);
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 3);
        expect(p.inventory.inventory().getItem(1).is(Items.STONE) && p.inventory.inventory().getItem(1).getCount() == 64,
                "click: hotbar 1 holds stone x64", failures);
        expect(p.inventory.carried().isEmpty(),
                "click: carried is empty after placing stack", failures);
        expect(sawSetSlot(mine, 37),
                "click: client received corrective set-slot packet on mismatched prediction", failures);

        // 3. Client clicks outside window (-999) with carried stack -> spawns item in world, watcher sees it.
        p.inventory.setCarried(new ItemStack(Items.DIAMOND, 5));
        p.inventory.broadcastChanges(ignored -> {});
        mine.clear();
        theirs.clear();
        clickStateId = p.inventory.menu().getStateId();
        byte[] outsidePayload = containerClick(0, clickStateId, (short) -999, (byte) 0,
                ContainerInput.PICKUP, new Int2ObjectOpenHashMap<>(), HashedStack.EMPTY);
        client.sendC(PacketIds.PLAY_CONTAINER_CLICK, outsidePayload);
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 3);
        expect(p.inventory.carried().isEmpty(),
                "outside-click: carried stack dropped and emptied", failures);
        List<dev.kestrel.entity.KestrelItemEntity> droppedDiamonds = allItems(server).stream()
                .filter(it -> it.getItem().is(Items.DIAMOND))
                .toList();
        expect(droppedDiamonds.size() == 1,
                "outside-click: diamond spawned in world, got " + describe(droppedDiamonds), failures);
        if (droppedDiamonds.size() == 1) {
            expect(addedItems(theirs, failures).containsKey(droppedDiamonds.get(0).getId()),
                    "outside-click: watcher sees dropped diamond spawn", failures);
        }

        // m. G18 slice 4: START runs the hit effects (EnchantmentHelper.onHitBlock, BlockState.attack).
        checkHitEffects(loop, server, sender, client, watcher, mine, theirs, p, standIn, fx, fy, fz, seq, failures);
    }

    /** The level-random seeds the hit-effect scenarios use; the numbers mean nothing. */
    private static final long SEED_STONE = 0x5107E5L;
    private static final long SEED_ORE = 0x0DE5L;
    private static final long SEED_AIR = 0xA17L;
    private static final long SEED_NOTE = 0x1017EL;
    /** First seed the order scenario tries; it walks up until the two orders disagree. */
    private static final long SEED_ORDER = 0x0DD5L;

    /**
     * G18 slice 4, {@code ServerPlayerGameMode.handleBlockBreakAction} START
     * ({@code :186-199}): {@code EnchantmentHelper.onHitBlock}, then
     * {@code blockState.attack}, then the destroy progress of the state read
     * before either.
     *
     * <p>Stone with a pickaxe must be unchanged (no write, no random draw, no
     * wear). Redstone ore lights on the hit and draws the level random for its
     * particle offsets; the lit state reaches the breaker and a watcher, the
     * section is flagged randomly ticking, and vanilla's own {@code randomTick}
     * turns it back. A note block queues its block event. A dragon egg teleports
     * by vanilla's 1000-offset search over the level random, checked against an
     * independent transcription of the search. Air runs nothing. An enchantment
     * with a {@code hit_block} effect proves {@code onHitBlock} runs on the held
     * stack, for solid blocks only.
     */
    private static void checkHitEffects(TickLoop loop, NetServer server, ChunkSender sender,
            NetCheck.Loopback client, NetCheck.Loopback watcher, List<Wire.Inbound> mine, List<Wire.Inbound> theirs,
            KestrelPlayer p, ServerPlayer standIn, int fx, int fy, int fz, int seq, List<String> failures)
            throws Exception {
        BlockBreaking bb = p.blockBreaking;
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        int sel = p.inventory.selectedSlot();
        p.inventory.inventory().setItem(sel, ItemStack.EMPTY);
        client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(ABORT, bb.destroyPos(), ++seq));
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 3);
        System.out.println(" G18 slice 4: hit effects on START");

        // a. Stone with a pickaxe: the two hit effects change nothing.
        BlockPos stonePos = new BlockPos(fx - 3, fy, fz - 3);
        loop.setBlock(stonePos, stone, 18);
        p.inventory.inventory().setItem(sel, woodenPickaxe());
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 2);
        loop.setLevelRandom(RandomSource.create(SEED_STONE));
        mine.clear();
        theirs.clear();
        client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(START, stonePos, ++seq));
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 2);
        List<String> stoneCracks = cracks(theirs, p.entityId, failures);
        expect(bb.isDestroyingBlock() && stonePos.equals(bb.destroyPos()) && loop.getBlockState(stonePos) == stone,
                "hit: stone with a pickaxe is dug, not changed, " + bb, failures);
        expect(!stoneCracks.isEmpty() && stoneCracks.get(0).equals(stonePos.asLong() + ":0"),
                "hit: stone first crack stage 0, saw " + stoneCracks, failures);
        expect(!sawAnyBlockUpdate(mine, stonePos) && !sawAnyBlockUpdate(theirs, stonePos),
                "hit: stone sends no block update", failures);
        expect(p.inventory.inventory().getItem(sel).getDamageValue() == 0, "hit: stone START wears no tool", failures);
        expect(loop.levelRandom().nextLong() == RandomSource.create(SEED_STONE).nextLong(),
                "hit: stone START draws nothing from the level random", failures);
        client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(ABORT, stonePos, ++seq));
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 2);
        loop.setBlock(stonePos, air, 18);
        p.inventory.inventory().setItem(sel, ItemStack.EMPTY);

        // b. Redstone ore lights up, drawing the level random for its particle offsets.
        BlockPos orePos = new BlockPos(fx + 3, fy, fz - 3);
        BlockState ore = Blocks.REDSTONE_ORE.defaultBlockState();
        BlockState lit = ore.setValue(net.minecraft.world.level.block.RedStoneOreBlock.LIT, true);
        loop.setBlock(orePos, ore, 18);
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 2);
        int exposed = exposedFaces(loop, orePos);
        RandomSource oreWant = RandomSource.create(SEED_ORE);
        for (int i = 0; i < exposed * 2; i++) {
            oreWant.nextFloat(); // RedStoneOreBlock.spawnParticles: two off-axis floats per open face
        }
        long oreSection = SectionPos.asLong(orePos);
        expect(!loop.store().isRandomlyTicking(oreSection) || loop.store().tickingBlockCount(oreSection) == 0,
                "hit: unlit ore does not make its section randomly ticking", failures);
        loop.setLevelRandom(RandomSource.create(SEED_ORE));
        mine.clear();
        theirs.clear();
        client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(START, orePos, ++seq));
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 2);
        expect(loop.getBlockState(orePos) == lit, "hit: redstone ore lit by the hit, got " + loop.getBlockState(orePos),
                failures);
        // The breaker's socket can deliver a tick behind the watcher's (the loopback reader
        // gives up after 50 ms), so give it a few more ticks before calling the update lost.
        for (int late = 0; late < 8 && !sawBlockUpdate(mine, orePos, lit); late++) {
            pumpBoth(loop, server, sender, client, watcher, mine, theirs, 1);
        }
        expect(sawBlockUpdate(mine, orePos, lit), "hit: breaker told the ore is lit", failures);
        expect(sawBlockUpdate(theirs, orePos, lit), "hit: watcher told the ore is lit", failures);
        expect(loop.levelRandom().nextLong() == oreWant.nextLong(),
                "hit: ore draws two floats per open face (" + exposed + " open) and nothing else", failures);
        expect(loop.store().isRandomlyTicking(oreSection), "hit: lit ore flags its section randomly ticking",
                failures);
        List<String> oreCracks = cracks(theirs, p.entityId, failures);
        expect(!oreCracks.isEmpty() && oreCracks.get(0).equals(orePos.asLong() + ":0"),
                "hit: ore still starts digging at stage 0, saw " + oreCracks, failures);
        // Vanilla's own randomTick (what the random-tick pass calls) turns it back off.
        mine.clear();
        theirs.clear();
        lit.randomTick((net.minecraft.server.level.ServerLevel) standIn.level(), orePos, RandomSource.create(1L));
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 2);
        expect(loop.getBlockState(orePos) == ore, "hit: random tick unlights the ore, got " + loop.getBlockState(orePos),
                failures);
        expect(sawBlockUpdate(theirs, orePos, ore), "hit: watcher told the ore is dark again", failures);
        // A hit on an already lit ore still draws, but writes nothing.
        client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(ABORT, orePos, ++seq));
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 2);
        loop.setBlock(orePos, lit, 18);
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 2);
        RandomSource litWant = RandomSource.create(SEED_ORE + 1);
        for (int i = 0; i < exposedFaces(loop, orePos) * 2; i++) {
            litWant.nextFloat();
        }
        loop.setLevelRandom(RandomSource.create(SEED_ORE + 1));
        mine.clear();
        theirs.clear();
        client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(START, orePos, ++seq));
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 2);
        expect(loop.getBlockState(orePos) == lit && !sawAnyBlockUpdate(mine, orePos) && !sawAnyBlockUpdate(theirs, orePos),
                "hit: already lit ore is left alone, no block update", failures);
        expect(loop.levelRandom().nextLong() == litWant.nextLong(), "hit: already lit ore still draws its particles",
                failures);
        client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(ABORT, orePos, ++seq));
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 2);
        loop.setBlock(orePos, air, 18);

        // c. Note block: the event is queued for the block-event phase (the sound is G67).
        BlockPos notePos = new BlockPos(fx + 1, fy, fz - 3);
        BlockState note = Blocks.NOTE_BLOCK.defaultBlockState();
        loop.setBlock(notePos, note, 18);
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 2);
        loop.setLevelRandom(RandomSource.create(SEED_NOTE));
        loop.redstone().setBlockEventLogEnabled(true);
        mine.clear();
        theirs.clear();
        client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(START, notePos, ++seq));
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 2);
        List<dev.kestrel.redstone.VanillaRedstone.EnqueuedBlockEvent> events = loop.redstone().takeEnqueuedBlockEvents();
        loop.redstone().setBlockEventLogEnabled(false);
        expect(events.size() == 1 && events.get(0).pos().equals(notePos) && events.get(0).block() == Blocks.NOTE_BLOCK
                        && events.get(0).paramA() == 0 && events.get(0).paramB() == 0,
                "hit: note block queues blockEvent(pos, note_block, 0, 0), saw " + events, failures);
        expect(loop.redstone().pendingBlockEventCount() == 0, "hit: the block-event phase drained the queue", failures);
        expect(loop.getBlockState(notePos) == note && !sawAnyBlockUpdate(mine, notePos),
                "hit: note block itself is unchanged", failures);
        expect(loop.levelRandom().nextLong() == RandomSource.create(SEED_NOTE).nextLong(),
                "hit: note block draws nothing from the level random", failures);
        expect(server.players.size() == 2, "hit: note block did not drop a session", failures);
        client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(ABORT, notePos, ++seq));
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 2);
        loop.setBlock(notePos, air, 18);

        // d. Dragon egg: vanilla's 1000-offset search over the level random.
        BlockPos eggPos = new BlockPos(fx - 1, fy, fz - 3);
        BlockState egg = Blocks.DRAGON_EGG.defaultBlockState();
        for (long seed : new long[] {1L, 2L, 3L}) {
            loop.setBlock(eggPos, egg, 18);
            pumpBoth(loop, server, sender, client, watcher, mine, theirs, 2);
            EggSearch want = eggSearch(loop, eggPos, seed);
            loop.setLevelRandom(RandomSource.create(seed));
            mine.clear();
            theirs.clear();
            client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(START, eggPos, ++seq));
            pumpBoth(loop, server, sender, client, watcher, mine, theirs, 6);
            String tag = "hit: egg seed " + seed + " (" + want.tries + " tries) ";
            if (want.dest == null) {
                expect(loop.getBlockState(eggPos) == egg, tag + "no spot found, egg stays", failures);
            } else {
                expect(loop.getBlockState(want.dest) == egg, tag + "egg lands at " + want.dest + ", got "
                        + loop.getBlockState(want.dest), failures);
                expect(loop.getBlockState(eggPos) == air, tag + "old place is air, got " + loop.getBlockState(eggPos),
                        failures);
                expect(sawBlockUpdate(mine, want.dest, egg) && sawBlockUpdate(theirs, want.dest, egg),
                        tag + "both clients see the egg arrive", failures);
                expect(sawBlockUpdate(mine, eggPos, air) && sawBlockUpdate(theirs, eggPos, air),
                        tag + "both clients see the old place empty", failures);
            }
            expect(loop.levelRandom().nextLong() == want.random.nextLong(), tag + "level random drawn exactly as vanilla",
                    failures);
            // Progress is the pre-attack egg's (stage 0), and the next tick finds air: -1.
            List<String> eggCracks = cracks(theirs, p.entityId, failures);
            if (want.dest != null) {
                expect(eggCracks.equals(List.of(eggPos.asLong() + ":0", eggPos.asLong() + ":255")) && !bb.isDestroyingBlock(),
                        tag + "crack stage 0 from the egg, then cleared, saw " + eggCracks + " " + bb, failures);
            }
            loop.setBlock(want.dest == null ? eggPos : want.dest, air, 18);
            client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(ABORT, eggPos, ++seq));
            pumpBoth(loop, server, sender, client, watcher, mine, theirs, 2);
        }

        // e. Air: nothing to hit. Vanilla's progress stays 1.0, so the crack starts at stage 10.
        BlockPos airPos = new BlockPos(fx + 2, fy + 1, fz - 3);
        loop.setBlock(airPos, air, 18);
        loop.setLevelRandom(RandomSource.create(SEED_AIR));
        mine.clear();
        theirs.clear();
        client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(START, airPos, ++seq));
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 2);
        List<String> airCracks = cracks(theirs, p.entityId, failures);
        expect(airCracks.equals(List.of(airPos.asLong() + ":10", airPos.asLong() + ":255")),
                "hit: air START is vanilla's progress 1.0 (stage 10), then cleared, saw " + airCracks, failures);
        expect(loop.levelRandom().nextLong() == RandomSource.create(SEED_AIR).nextLong(),
                "hit: air START draws nothing from the level random", failures);

        // e2. Out of reach: vanilla returns before any hit effect (:154-157), so the ore stays dark.
        BlockPos farPos = new BlockPos(fx + 8, fy, fz + 8);
        loop.setBlock(farPos, ore, 18);
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 2);
        expect(!standIn.isWithinBlockInteractionRange(farPos, 1.0), "hit: far ore setup is out of reach", failures);
        loop.setLevelRandom(RandomSource.create(SEED_AIR + 1));
        mine.clear();
        theirs.clear();
        client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(START, farPos, ++seq));
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 4);
        expect(loop.getBlockState(farPos) == ore && !sawAnyBlockUpdate(mine, farPos) && !sawAnyBlockUpdate(theirs, farPos),
                "hit: an ore out of reach is not lit", failures);
        expect(loop.levelRandom().nextLong() == RandomSource.create(SEED_AIR + 1).nextLong(),
                "hit: an out-of-reach START draws nothing", failures);
        expect(!bb.isDestroyingBlock(), "hit: an out-of-reach START digs nothing, " + bb, failures);
        loop.setBlock(farPos, air, 18);

        // f. onHitBlock runs on the held stack, for a solid block only. The stack never reaches
        // a client here (its registry-less enchantment could not be encoded), so START is driven
        // directly on a stand-in world and the hand is emptied before the next tick.
        BlockPos firePos = new BlockPos(fx + 1, fy, fz + 1);
        loop.setBlock(firePos, stone, 18);
        BlockBreaking.World direct = new BlockBreaking.World() {
            @Override public BlockState getBlockState(BlockPos pos) {
                return loop.getBlockState(pos);
            }

            @Override public boolean removeBlock(BlockPos pos) {
                throw new IllegalStateException("a START that only hits must not break " + pos);
            }

            @Override public void sendBlockUpdate(BlockPos pos) { }

            @Override public boolean isUnderSpawnProtection(BlockPos pos) {
                return false;
            }

            @Override public void destroyBlockProgress(BlockPos pos, int progress) { }
        };
        try {
            p.inventory.inventory().setItem(sel, igniterPickaxe());
            standIn.setRemainingFireTicks(0);
            new BlockBreaking().handleBlockBreakAction(standIn, direct, firePos, START);
            expect(standIn.getRemainingFireTicks() == 100,
                    "hit: onHitBlock ignites from the held stack's hit_block effect, ticks " + standIn.getRemainingFireTicks(),
                    failures);
            standIn.setRemainingFireTicks(0);
            new BlockBreaking().handleBlockBreakAction(standIn, direct, airPos, START);
            expect(standIn.getRemainingFireTicks() == 0, "hit: no onHitBlock for an air block", failures);
            p.inventory.inventory().setItem(sel, ItemStack.EMPTY);
            new BlockBreaking().handleBlockBreakAction(standIn, direct, firePos, START);
            expect(standIn.getRemainingFireTicks() == 0, "hit: nothing held, nothing ignites", failures);
        } finally {
            p.inventory.inventory().setItem(sel, ItemStack.EMPTY);
            standIn.setRemainingFireTicks(0);
            loop.setBlock(firePos, air, 18);
        }

        // f2. The order of the two hit effects, seen through the one stream they share. A hit_block
        // effect gated by random_chance(0.5) draws one float from the level random; redstone ore's
        // attack then draws two floats per open face. Vanilla runs onHitBlock first, so the effect
        // sees the first float of the stream; run the other way round it would see the float after
        // the particle draws. The seeds are searched so that the two orders disagree, once with the
        // effect passing in vanilla's order and once failing, and the expectation comes from the
        // RandomSource itself, not from Kestrel.
        BlockPos orderPos = new BlockPos(fx + 3, fy, fz - 3);
        BlockState orderOre = Blocks.REDSTONE_ORE.defaultBlockState();
        loop.setBlock(orderPos, orderOre, 18);
        int orderFaces = exposedFaces(loop, orderPos);
        ItemStack chancePickaxe = igniterPickaxe(java.util.Optional.of(
                net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition.randomChance(0.5F)
                        .build()));
        try {
            for (boolean vanillaIgnites : new boolean[] {true, false}) {
                long seed = SEED_ORDER;
                while (true) {
                    RandomSource probe = RandomSource.create(seed);
                    boolean first = probe.nextFloat() < 0.5F;
                    RandomSource later = RandomSource.create(seed);
                    for (int i = 0; i < orderFaces * 2; i++) {
                        later.nextFloat();
                    }
                    boolean afterParticles = later.nextFloat() < 0.5F;
                    if (first == vanillaIgnites && afterParticles != first) {
                        break;
                    }
                    seed++;
                }
                RandomSource wantRandom = RandomSource.create(seed);
                for (int i = 0; i < 1 + orderFaces * 2; i++) {
                    wantRandom.nextFloat(); // the chance draw, then the particle draws
                }
                loop.setBlock(orderPos, orderOre, 18);
                loop.setLevelRandom(RandomSource.create(seed));
                p.inventory.inventory().setItem(sel, chancePickaxe);
                standIn.setRemainingFireTicks(0);
                new BlockBreaking().handleBlockBreakAction(standIn, direct, orderPos, START);
                expect((standIn.getRemainingFireTicks() == 100) == vanillaIgnites,
                        "hit: onHitBlock runs before attack, so its random_chance sees the first float of the stream"
                                + " (seed " + seed + ", " + orderFaces + " open faces, want ignite=" + vanillaIgnites
                                + ", fire ticks " + standIn.getRemainingFireTicks() + ")", failures);
                expect(loop.getBlockState(orderPos) == orderOre.setValue(
                                net.minecraft.world.level.block.RedStoneOreBlock.LIT, true),
                        "hit: the ore is lit after the ordered hit, got " + loop.getBlockState(orderPos), failures);
                expect(loop.levelRandom().nextLong() == wantRandom.nextLong(),
                        "hit: one chance draw then two floats per open face, nothing more (seed " + seed + ")",
                        failures);
            }
        } finally {
            p.inventory.inventory().setItem(sel, ItemStack.EMPTY);
            standIn.setRemainingFireTicks(0);
            loop.setBlock(orderPos, air, 18);
        }
        client.sendC(PacketIds.PLAY_PLAYER_ACTION, action(ABORT, airPos, ++seq));
        pumpBoth(loop, server, sender, client, watcher, mine, theirs, 3);
        expect(server.players.size() == 2, "hit: both sessions survive the hit effects", failures);
    }

    /** Faces of {@code pos} whose neighbour is not a solid render (what {@code spawnParticles} draws for). */
    private static int exposedFaces(TickLoop loop, BlockPos pos) {
        int n = 0;
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            if (!loop.getBlockState(pos.relative(d)).isSolidRender()) {
                n++;
            }
        }
        return n;
    }

    /** The outcome of {@code DragonEggBlock.teleport}'s search, and the random after it. */
    private static final class EggSearch {
        BlockPos dest;
        int tries;
        RandomSource random;
    }

    /**
     * An independent transcription of {@code DragonEggBlock.teleport}'s search ({@code :55-61}) over
     * the loop's blocks: up to 1000 offsets in vanilla's draw order, the first air spot with solid
     * ground below, inside the default border and the build height (-64 to 320).
     */
    private static EggSearch eggSearch(TickLoop loop, BlockPos pos, long seed) {
        EggSearch out = new EggSearch();
        out.random = RandomSource.create(seed);
        net.minecraft.world.level.border.WorldBorder border = new net.minecraft.world.level.border.WorldBorder();
        for (int i = 0; i < 1000; i++) {
            out.tries = i + 1;
            BlockPos test = pos.offset(out.random.nextInt(16) - out.random.nextInt(16),
                    out.random.nextInt(8) - out.random.nextInt(8), out.random.nextInt(16) - out.random.nextInt(16));
            if (loop.getBlockState(test).isAir() && !loop.getBlockState(test.below()).isAir()
                    && border.isWithinBounds(test) && test.getY() >= -64 && test.getY() < 320) {
                out.dest = test;
                return out;
            }
        }
        return out;
    }

    /**
     * A wooden pickaxe whose one enchantment sets the wielder on fire for 5 seconds on
     * {@code hit_block} (no requirements). Not in any registry, which is why it is never sent.
     */
    private static ItemStack igniterPickaxe() {
        return igniterPickaxe(java.util.Optional.empty());
    }

    /**
     * The same pickaxe with a requirement on the effect. A {@code random_chance} requirement draws
     * one float from the level random when {@code onHitBlock} evaluates it, which is what makes the
     * order of {@code onHitBlock} and {@code attack} visible: both draw from that one stream.
     */
    private static ItemStack igniterPickaxe(
            java.util.Optional<net.minecraft.world.level.storage.loot.predicates.LootItemCondition> requirements) {
        net.minecraft.world.item.enchantment.Enchantment igniter = new net.minecraft.world.item.enchantment.Enchantment(
                net.minecraft.network.chat.Component.literal("gate igniter"),
                new net.minecraft.world.item.enchantment.Enchantment.EnchantmentDefinition(
                        net.minecraft.core.HolderSet.empty(), java.util.Optional.empty(), 1, 1,
                        net.minecraft.world.item.enchantment.Enchantment.constantCost(1),
                        net.minecraft.world.item.enchantment.Enchantment.constantCost(1), 0,
                        List.of(net.minecraft.world.entity.EquipmentSlotGroup.MAINHAND)),
                net.minecraft.core.HolderSet.empty(),
                net.minecraft.core.component.DataComponentMap.builder()
                        .set(net.minecraft.world.item.enchantment.EnchantmentEffectComponents.HIT_BLOCK,
                                List.<net.minecraft.world.item.enchantment.ConditionalEffect<
                                        net.minecraft.world.item.enchantment.effects.EnchantmentEntityEffect>>of(
                                        new net.minecraft.world.item.enchantment.ConditionalEffect<>(
                                                new net.minecraft.world.item.enchantment.effects.Ignite(
                                                        net.minecraft.world.item.enchantment.LevelBasedValue.constant(5.0F)),
                                                requirements)))
                        .build());
        net.minecraft.world.item.enchantment.ItemEnchantments.Mutable enchantments =
                new net.minecraft.world.item.enchantment.ItemEnchantments.Mutable(
                        net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
        enchantments.set(net.minecraft.core.Holder.direct(igniter), 1);
        ItemStack stack = woodenPickaxe();
        stack.set(DataComponents.ENCHANTMENTS, enchantments.toImmutable());
        return stack;
    }

    private static String mergeState(dev.kestrel.entity.KestrelItemEntity... items) {
        StringBuilder out = new StringBuilder();
        for (dev.kestrel.entity.KestrelItemEntity e : items) {
            if (e == null) continue;
            out.append("[id=").append(e.getId()).append(" pos=").append(e.position()).append(" tc=").append(e.tickCount)
                    .append(" age=").append(e.getAge()).append(" delay=").append(e.getPickupDelay())
                    .append(" mergable=").append(e.isMergable()).append(" removed=").append(e.isRemoved())
                    .append(" max=").append(e.getItem().getMaxStackSize()).append("]");
        }
        if (items.length == 2 && items[0] != null && items[1] != null) {
            out.append(" same=").append(ItemStack.isSameItemSameComponents(items[0].getItem(), items[1].getItem()))
                    .append(" areMergable=").append(dev.kestrel.entity.KestrelItemEntity.areMergable(items[0].getItem(),
                            items[1].getItem()));
        }
        return out.toString();
    }

    /** Entity ids in ClientboundRemoveEntitiesPacket bodies (varInt list). */
    private static List<Integer> removedIds(List<Wire.Inbound> seen) throws Exception {
        List<Integer> out = new ArrayList<>();
        for (Wire.Inbound in : seen) {
            if (in.id() == PacketIds.PLAY_REMOVE_ENTITIES) {
                Wire.Reader r = in.reader();
                int n = r.varInt();
                for (int k = 0; k < n; k++) out.add(r.varInt());
            }
        }
        return out;
    }

    record TakePacket(int itemId, int playerId, int amount) {}

    private static List<TakePacket> takePackets(List<Wire.Inbound> seen) throws Exception {
        List<TakePacket> out = new ArrayList<>();
        for (Wire.Inbound in : seen) {
            if (in.id() == PacketIds.PLAY_TAKE_ITEM_ENTITY) {
                Wire.Reader r = in.reader();
                out.add(new TakePacket(r.varInt(), r.varInt(), r.varInt()));
            }
        }
        return out;
    }

    private static boolean sawSetSlot(List<Wire.Inbound> seen, int slot) throws Exception {
        for (Wire.Inbound in : seen) {
            if (in.id() == PacketIds.PLAY_CONTAINER_SET_SLOT) {
                Wire.Reader r = in.reader();
                r.varInt(); // containerId
                r.varInt(); // stateId
                int s = r.s();
                if (s == slot) return true;
            }
        }
        return false;
    }

    private static boolean sawSetCursor(List<Wire.Inbound> seen) {
        for (Wire.Inbound in : seen) {
            if (in.id() == PacketIds.PLAY_SET_CURSOR_ITEM) {
                return true;
            }
        }
        return false;
    }

    private static final net.minecraft.network.HashedPatchMap.HashGenerator HASHER = component -> {
        com.mojang.serialization.DynamicOps<com.google.common.hash.HashCode> ops =
                net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
                        .createSerializationContext(net.minecraft.util.HashOps.CRC32C_INSTANCE);
        return ((com.google.common.hash.HashCode) component.encodeValue(ops)
                .getOrThrow(msg -> new IllegalArgumentException("Failed to hash " + component + ": " + msg)))
                .asInt();
    };

    private static byte[] containerClick(int containerId, int stateId, short slotNum, byte buttonNum,
            ContainerInput containerInput, Int2ObjectMap<HashedStack> changedSlots,
            HashedStack carriedItem) {
        net.minecraft.network.protocol.game.ServerboundContainerClickPacket packet =
                new net.minecraft.network.protocol.game.ServerboundContainerClickPacket(
                        containerId, stateId, slotNum, buttonNum, containerInput, changedSlots, carriedItem);
        io.netty.buffer.ByteBuf bytes = io.netty.buffer.Unpooled.buffer();
        net.minecraft.network.RegistryFriendlyByteBuf out = new net.minecraft.network.RegistryFriendlyByteBuf(
                bytes, net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        net.minecraft.network.protocol.game.ServerboundContainerClickPacket.STREAM_CODEC.encode(out, packet);
        byte[] result = new byte[bytes.readableBytes()];
        bytes.getBytes(bytes.readerIndex(), result);
        bytes.release();
        return result;
    }

    private static java.util.Set<Integer> itemIds(List<dev.kestrel.entity.KestrelItemEntity> items) {
        java.util.Set<Integer> out = new java.util.HashSet<>();
        for (dev.kestrel.entity.KestrelItemEntity item : items) out.add(item.getId());
        return out;
    }

    private static List<dev.kestrel.entity.KestrelItemEntity> fresh(
            List<dev.kestrel.entity.KestrelItemEntity> items, java.util.Set<Integer> before) {
        List<dev.kestrel.entity.KestrelItemEntity> out = new ArrayList<>();
        for (dev.kestrel.entity.KestrelItemEntity item : items) {
            if (!before.contains(item.getId())) out.add(item);
        }
        return out;
    }

    /** Every live Kestrel item entity in the check's arena. */
    private static List<dev.kestrel.entity.KestrelItemEntity> allItems(NetServer server) {
        List<dev.kestrel.entity.KestrelItemEntity> out = new ArrayList<>();
        server.entityWorld().sections().getEntities(new net.minecraft.world.phys.AABB(-64, -64, -64, 64, 320, 64),
                e -> {
                    if (e instanceof dev.kestrel.entity.KestrelItemEntity item && !item.isRemoved()) out.add(item);
                });
        return out;
    }

    /** Kestrel item entities whose position lies within 0.75 of the block's centre column. */
    private static List<dev.kestrel.entity.KestrelItemEntity> items(NetServer server, BlockPos pos) {
        List<dev.kestrel.entity.KestrelItemEntity> out = new ArrayList<>();
        server.entityWorld().sections().getEntities(new net.minecraft.world.phys.AABB(pos).inflate(0.75, 2.0, 0.75),
                e -> {
                    if (e instanceof dev.kestrel.entity.KestrelItemEntity item && !item.isRemoved()) out.add(item);
                });
        return out;
    }

    private static String describe(List<dev.kestrel.entity.KestrelItemEntity> items) {
        List<String> out = new ArrayList<>();
        for (dev.kestrel.entity.KestrelItemEntity item : items) {
            out.add(BuiltInRegistries.ITEM.getKey(item.getItem().getItem()) + "x" + item.getItem().getCount());
        }
        return out.toString();
    }

    /** Item entities added (ClientboundAddEntityPacket with the item type): id -> spawn x, y, z. */
    private static java.util.Map<Integer, double[]> addedItems(List<Wire.Inbound> seen, List<String> failures)
            throws Exception {
        java.util.Map<Integer, double[]> out = new java.util.LinkedHashMap<>();
        int itemType = BuiltInRegistries.ENTITY_TYPE.getId(net.minecraft.world.entity.EntityTypes.ITEM);
        for (Wire.Inbound in : seen) {
            if (in.id() == PacketIds.PLAY_ADD_ENTITY) {
                Wire.Reader r = in.reader();
                int id = r.varInt();
                r.l();
                r.l();
                if (r.varInt() == itemType) out.put(id, new double[] {r.d(), r.d(), r.d()});
            }
        }
        return out;
    }

    /** Level events as "type@pos:data", checking each body decodes whole. */
    private static List<String> levelEvents(List<Wire.Inbound> seen, List<String> failures) throws Exception {
        List<String> out = new ArrayList<>();
        for (Wire.Inbound in : seen) {
            if (in.id() == PacketIds.PLAY_LEVEL_EVENT) {
                Wire.Reader r = in.reader();
                int type = r.i();
                long pos = r.l();
                int data = r.i();
                boolean global = r.bool();
                if (global || r.hasMore()) {
                    failures.add("effect: level event global=" + global + " trailing=" + r.hasMore());
                }
                out.add(type + "@" + pos + ":" + data);
            }
        }
        return out;
    }

    /**
     * The block-destruction packets in {@code seen} as {@code pos:stage};
     * each must name the breaker and decode to exactly the vanilla layout.
     */
    private static List<String> cracks(List<Wire.Inbound> seen, int breakerId, List<String> failures)
            throws Exception {
        List<String> out = new ArrayList<>();
        for (Wire.Inbound in : seen) {
            if (in.id() == PacketIds.PLAY_BLOCK_DESTRUCTION) {
                Wire.Reader r = in.reader();
                int id = r.varInt();
                long pos = r.l();
                int stage = r.b();
                if (id != breakerId || r.hasMore()) {
                    failures.add("cracks: packet for id " + id + " (breaker " + breakerId + "), trailing=" + r.hasMore());
                }
                out.add(pos + ":" + stage);
            }
        }
        return out;
    }

    /** Ticks with both clients drained (teleport accepted, keepalives answered). */
    private static void pumpBoth(TickLoop loop, NetServer server, ChunkSender sender, NetCheck.Loopback a,
            NetCheck.Loopback b, List<Wire.Inbound> seenA, List<Wire.Inbound> seenB, int ticks) throws Exception {
        for (int i = 0; i < ticks; i++) {
            NetCheck.drainInbound(server);
            KestrelServer.runIteration(server, loop, sender);
            drain(a, seenA);
            drain(b, seenB);
        }
    }

    private static void drain(NetCheck.Loopback client, List<Wire.Inbound> seen) throws Exception {
        try {
            client.socket.setSoTimeout(50);
            while (true) {
                Wire.Inbound in = client.read();
                seen.add(in);
                if (in.id() == PacketIds.PLAY_POSITION && client.playTeleportId < 0) {
                    client.playTeleportId = in.reader().varInt();
                    client.sendC(PacketIds.PLAY_ACCEPT_TELEPORT,
                            new Wire.Writer().vint(client.playTeleportId).toBytes());
                    client.sendC(PacketIds.PLAY_PLAYER_LOADED, new byte[0]);
                } else if (in.id() == PacketIds.PLAY_KEEPALIVE_OUT) {
                    client.sendC(PacketIds.PLAY_KEEPALIVE_IN,
                            new Wire.Writer().l(in.reader().l()).toBytes());
                }
            }
        } catch (java.net.SocketTimeoutException e) {
            // drained
        }
    }

    /**
     * The packet id is the clientbound template's own for
     * {@code minecraft:block_destruction}, and the payload is byte for byte
     * what vanilla's {@code ClientboundBlockDestructionPacket.STREAM_CODEC}
     * writes, including stage -1 as 0xFF.
     */
    private static void checkCrackWire(List<String> failures) {
        int[] listed = {-1};
        net.minecraft.network.protocol.game.GameProtocols.CLIENTBOUND_TEMPLATE.details().listPackets((type, id) -> {
            if (type == net.minecraft.network.protocol.game.GamePacketTypes.CLIENTBOUND_BLOCK_DESTRUCTION) {
                listed[0] = id;
            }
        });
        expect(listed[0] == PacketIds.PLAY_BLOCK_DESTRUCTION,
                "wire: block_destruction id " + PacketIds.PLAY_BLOCK_DESTRUCTION + ", vanilla lists " + listed[0], failures);
        BlockPos pos = new BlockPos(-30000000 + 7, -64, 29999984);
        int[][] cases = {{1, 0}, {300, 9}, {2097152, -1}, {5, 10}};
        for (int[] c : cases) {
            io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer();
            net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket.STREAM_CODEC.encode(
                    new net.minecraft.network.FriendlyByteBuf(buf),
                    new net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket(c[0], pos, c[1]));
            byte[] vanilla = new byte[buf.readableBytes()];
            buf.readBytes(vanilla);
            buf.release();
            byte[] ours = Outbound.blockDestruction(c[0], pos.asLong(), c[1]).payload();
            expect(java.util.Arrays.equals(vanilla, ours), "wire: block_destruction(" + c[0] + ", " + c[1]
                    + ") bytes match vanilla's codec", failures);
        }
    }

    /**
     * {@code ServerLevel.destroyBlockProgress}'s recipients
     * ({@code ServerLevel.java:1024-1035}): the block at (10, 64, 10);
     * players in list order: one 31.9 blocks away on x (receives), one
     * exactly 32 away (the test is {@code <}, so not), one beside the block
     * in another dimension (not), the breaker (never, by entity id), and one
     * more in range (receives, after the first). The distance is from the
     * block's corner, not its centre: a player at x = 10 - 31.95 is in range.
     */
    private static void checkCrackRange(List<String> failures) {
        record Player(String name, Object dimension, int entityId, double x, double y, double z, List<String> log)
                implements BlockDestructionBroadcast.Receiver {
            @Override public void send(Outbound.Packet packet) {
                log.add(name + "=" + packet.id() + ":" + java.util.HexFormat.of().formatHex(packet.payload()));
            }
        }
        List<String> log = new ArrayList<>();
        Object overworld = "overworld";
        Object nether = "the_nether";
        int breaker = 42;
        List<Player> players = List.of(
                new Player("near", overworld, 1, 10.0 + 31.9, 64.0, 10.0, log),
                new Player("edge", overworld, 2, 10.0, 64.0, 10.0 + 32.0, log),
                new Player("elsewhere", nether, 3, 10.0, 64.0, 10.0, log),
                new Player("breaker", overworld, breaker, 10.0, 64.0, 10.0, log),
                new Player("corner", overworld, 5, 10.0 - 31.95, 64.0, 10.0, log),
                new Player("last", overworld, 6, 12.0, 64.0, 12.0, log));
        BlockPos pos = new BlockPos(10, 64, 10);
        BlockDestructionBroadcast.broadcast(players, overworld, breaker, pos, 3);
        Outbound.Packet packet = Outbound.blockDestruction(breaker, pos.asLong(), 3);
        String bytes = packet.id() + ":" + java.util.HexFormat.of().formatHex(packet.payload());
        List<String> expected = List.of("near=" + bytes, "corner=" + bytes, "last=" + bytes);
        expect(log.equals(expected), "range: recipients " + log + " expected " + expected, failures);
    }

    /**
     * A wooden pickaxe carrying vanilla's tool component, built exactly as
     * {@code ToolMaterial.applyToolProperties} ({@code ToolMaterial.java:44-56})
     * builds it for {@code Items.WOODEN_PICKAXE}. This check boots without a
     * resource load (the live server gets real item components from the
     * generator's {@code WorldLoader.load}), so items carry only the common
     * map and the stack gets the component set on it directly; the rules
     * resolve through the block tags {@link ServedTags} bound.
     */
    private static ItemStack woodenPickaxe() {
        ItemStack stack = new ItemStack(Items.WOODEN_PICKAXE);
        // Item.Properties.durability (Item.java:412-416) with WOOD's 59.
        stack.set(DataComponents.MAX_DAMAGE, ToolMaterial.WOOD.durability());
        stack.set(DataComponents.MAX_STACK_SIZE, 1);
        stack.set(DataComponents.DAMAGE, 0);
        stack.set(DataComponents.TOOL, new Tool(List.of(
                Tool.Rule.deniesDrops(BuiltInRegistries.BLOCK.get(BlockTags.INCORRECT_FOR_WOODEN_TOOL).orElseThrow()),
                Tool.Rule.minesAndDrops(BuiltInRegistries.BLOCK.get(BlockTags.MINEABLE_WITH_PICKAXE).orElseThrow(),
                        ToolMaterial.WOOD.speed())),
                1.0F, 1, true));
        return stack;
    }

    /** ServerboundPlayerActionPacket: varint action, BlockPos long, face byte (up), varint sequence. */
    private static byte[] action(int action, BlockPos pos, int sequence) {
        return new Wire.Writer().vint(action).l(pos.asLong()).b(1).vint(sequence).toBytes();
    }

    private static boolean sawBlockUpdate(List<Wire.Inbound> seen, BlockPos pos, BlockState state) throws Exception {
        for (Wire.Inbound in : seen) {
            if (in.id() == PacketIds.PLAY_BLOCK_UPDATE) {
                Wire.Reader r = in.reader();
                if (r.l() == pos.asLong() && r.varInt() == Block.getId(state)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean sawAnyBlockUpdate(List<Wire.Inbound> seen, BlockPos pos) throws Exception {
        for (Wire.Inbound in : seen) {
            if (in.id() == PacketIds.PLAY_BLOCK_UPDATE && in.reader().l() == pos.asLong()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Keep pumping until the ack for {@code sequence} arrives (at most 10
     * ticks). The ack is written at the end of the tick that handled the
     * action; the loopback reader can stop before the socket delivers it.
     */
    private static boolean awaitAck(TickLoop loop, NetServer server, ChunkSender sender,
            NetCheck.Loopback client, int sequence) throws Exception {
        for (int i = 0; i < 10; i++) {
            if (sawAck(NetCheck.pump(loop, server, sender, client, 1), sequence)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sawAck(List<Wire.Inbound> seen, int sequence) throws Exception {
        for (Wire.Inbound in : seen) {
            if (in.id() == PacketIds.PLAY_BLOCK_CHANGED_ACK && in.reader().varInt() == sequence) {
                return true;
            }
        }
        return false;
    }

    private static void expect(boolean cond, String what, List<String> failures) {
        System.out.println((cond ? "  ok   " : "  FAIL ") + what);
        if (!cond) {
            failures.add(what);
        }
    }
}
