package dev.kestrel.loop;

import dev.kestrel.blockentity.SleeperRegistry;
import dev.kestrel.collision.ShapeBoxCache;
import dev.kestrel.entity.EntityIds;
import dev.kestrel.entity.EntityLevel;
import dev.kestrel.entity.EntityWorld;
import dev.kestrel.entity.KestrelEntity;
import dev.kestrel.redstone.MovingPiston;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * S43 parity gate: the block-entity pass inside vanilla's empty-time guard.
 *
 * <p>Vanilla reference points:
 * <ul>
 *   <li>{@code ServerLevel.java:410}: {@code boolean isActive = this.chunkSource.hasActiveTickets();}
 *       Reads {@code TicketStorage.shouldKeepDimensionActive()} (:127-141), which is true when any ticket
 *       with {@code FLAG_KEEP_DIMENSION_ACTIVE} (8) is held, notably {@code TicketType.PLAYER_SIMULATION}
 *       ({@code TicketType.java:21}) held for players in simulation distance.</li>
 *   <li>{@code ServerLevel.java:411-413}: {@code if (isActive) { this.resetEmptyTime(); }}</li>
 *   <li>{@code ServerLevel.java:805-807}: {@code public void resetEmptyTime() { this.emptyTime = 0; }}
 *       Also called on destination level by {@code ServerPlayer.registerAndUpdateEnderPearlTicket}
 *       ({@code ServerPlayer.java:2298}) and {@code Entity.teleportCrossDimension}
 *       ({@code Entity.java:3122}).</li>
 *   <li>{@code ServerLevel.java:415-417}: {@code if (runs) { this.emptyTime++; }}
 *       Where {@code runs = tickRateManager.runsNormally()} (:359).</li>
 *   <li>{@code ServerLevel.java:419-453}: {@code if (this.emptyTime < 300) { entities; blockEntities; }}
 *       Both entity ticking (:427-449) and block-entity ticking ({@code this.tickBlockEntities()} at :451,
 *       defined at {@code Level.java:534-554}) reside inside this single guard, in that order.</li>
 * </ul>
 */
public final class EmptyLevelBlockEntityCheck {
    private EmptyLevelBlockEntityCheck() {
    }

    private static final List<String> failures = new ArrayList<>();
    private static long assertions;

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        System.out.println("[EmptyLevelBlockEntityCheck] Starting S43 empty-level block-entity gate checks...");

        checkThresholdCrossingPistonMidMove();
        checkPistonStartedPastThreshold();
        checkSleeperStatePastThreshold();
        checkPlayerPresentThroughout();
        checkPlayerJoiningAfterThresholdAndEmptyAgain();
        checkSimultaneousSwitchOff();
        checkRelativePhaseOrderWithinTick();

        if (!failures.isEmpty()) {
            System.err.println("EmptyLevelBlockEntityCheck FAILED with " + failures.size() + " failure(s):");
            for (String f : failures) {
                System.err.println("  - " + f);
            }
            System.exit(1);
        }

        System.out.println("EmptyLevelBlockEntityCheck: PASS (" + assertions + " assertions verified)");
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) {
            failures.add(message);
        }
    }

    /**
     * Vanilla 26.2 reference model of ServerLevel.tick (:408-453).
     */
    static final class VanillaEmptyLevelModel {
        int emptyTime = 0;

        /**
         * ServerLevel.tick:
         * :410 boolean isActive = this.chunkSource.hasActiveTickets();
         * :411-413 if (isActive) { this.resetEmptyTime(); } (:806 this.emptyTime = 0)
         * :415-417 if (runs) { this.emptyTime++; }
         * :419 if (this.emptyTime < 300) { entities; blockEntities; }
         */
        void tick(boolean isActive, boolean runs) {
            if (isActive) {
                emptyTime = 0; // ServerLevel.java:412, :806
            }
            if (runs) {
                emptyTime++;   // ServerLevel.java:416
            }
        }

        boolean ticksEntitiesAndBlockEntities() {
            return emptyTime < 300; // ServerLevel.java:419
        }
    }

    private static final class TestEntityLevel implements EntityLevel {
        private final TickLoop loop;
        private final ShapeBoxCache shapeCache = new ShapeBoxCache();

        TestEntityLevel(TickLoop loop) {
            this.loop = loop;
        }

        @Override public BlockGetter blocks() { return loop.blockGetter(); }
        @Override public ShapeBoxCache shapeCache() { return shapeCache; }
        @Override public int getMinY() { return loop.store().getMinY(); }
        @Override public LevelReader levelReader() { return loop.entityLevelReader(); }
        @Override public boolean setBlock(BlockPos pos, BlockState state, int flags) {
            return loop.setBlock(pos, state, flags);
        }
        @Override public void levelEvent(int type, BlockPos pos, int data) {
            loop.emitLevelEvent(type, pos, data);
        }
        @Override public void landingBlockUpdate(KestrelEntity entity, BlockPos pos, BlockState state) { }
        @Override public RandomSource random() { return loop.levelRandom(); }
        @Override public GameRules gameRules() { return loop.gameRules(); }
        @Override public void playSound(double x, double y, double z, Holder<SoundEvent> sound,
                SoundSource source, float volume, float pitch) { }
        @Override public void entityEvent(KestrelEntity entity, byte event) { }
    }

    private static final class ProbeEntity extends KestrelEntity {
        int tickCount = 0;
        Runnable onTick;

        ProbeEntity() {
            super(EntityIds.claim(), UUID.randomUUID(), EntityTypes.INTERACTION);
        }

        @Override
        public void tick(EntityLevel level) {
            tickCount++;
            if (onTick != null) {
                onTick.run();
            }
        }
    }

    /**
     * Scenario 1: A moving piston placed just before the guard closes advances
     * on the ticks vanilla would and then stops on exactly the same tick vanilla
     * stops (off by one in either direction is a failure).
     */
    private static void checkThresholdCrossingPistonMidMove() {
        TickLoop loop = new TickLoop(new SectionStore(-64, 384));
        VanillaEmptyLevelModel oracle = new VanillaEmptyLevelModel();
        loop.setActiveTickets(() -> false);

        // Run ticks 1..297. EmptyTime advances 1..297.
        for (int t = 1; t <= 297; t++) {
            oracle.tick(false, true);
            loop.tick();
            check(loop.emptyTime() == oracle.emptyTime,
                    "Scenario 1: emptyTime desync at tick " + t + ": kestrel=" + loop.emptyTime()
                            + " vanilla=" + oracle.emptyTime);
        }

        // Place a moving piston at tick 297 (before tick 298 runs).
        BlockPos pos = new BlockPos(10, 64, 10);
        loop.setBlock(pos, Blocks.MOVING_PISTON.defaultBlockState(), 3);
        MovingPiston mp = new MovingPiston(pos, Blocks.STONE.defaultBlockState(), Direction.EAST, true, false);
        loop.blockEntities().putMovingPiston(mp);

        check(mp.getProgress(1.0F) == 0.0F, "Scenario 1: piston initial progress must be 0.0");
        check(!mp.isRemoved(), "Scenario 1: piston initially not removed");

        // Tick 298: emptyTime increments 297 -> 298 < 300. Piston advances 0.0 -> 0.5.
        oracle.tick(false, true);
        loop.tick();
        check(loop.emptyTime() == 298, "Scenario 1: tick 298 emptyTime=" + loop.emptyTime());
        check(mp.getProgress(1.0F) == 0.5F,
                "Scenario 1: tick 298 piston should advance to 0.5, got " + mp.getProgress(1.0F));
        check(!mp.isRemoved(), "Scenario 1: tick 298 piston should not be removed");
        check(loop.getBlockState(pos).is(Blocks.MOVING_PISTON), "Scenario 1: tick 298 block is MOVING_PISTON");

        // Tick 299: emptyTime increments 298 -> 299 < 300. Piston advances 0.5 -> 1.0.
        oracle.tick(false, true);
        loop.tick();
        check(loop.emptyTime() == 299, "Scenario 1: tick 299 emptyTime=" + loop.emptyTime());
        check(mp.getProgress(1.0F) == 1.0F,
                "Scenario 1: tick 299 piston should advance to 1.0, got " + mp.getProgress(1.0F));
        check(!mp.isRemoved(), "Scenario 1: tick 299 piston should not be removed");
        check(loop.getBlockState(pos).is(Blocks.MOVING_PISTON), "Scenario 1: tick 299 block is MOVING_PISTON");

        // Tick 300: emptyTime increments 299 -> 300.
        // ServerLevel.java:419: emptyTime < 300 is FALSE (300 < 300 == false). Guard closes!
        oracle.tick(false, true);
        loop.tick();
        check(loop.emptyTime() == 300, "Scenario 1: tick 300 emptyTime=" + loop.emptyTime());
        check(!oracle.ticksEntitiesAndBlockEntities(), "Scenario 1: oracle guard closed at tick 300");
        check(mp.getProgress(1.0F) == 1.0F,
                "Scenario 1: tick 300 piston MUST STOP at 1.0 (guard closed), got " + mp.getProgress(1.0F));
        check(!mp.isRemoved(), "Scenario 1: tick 300 piston MUST NOT settle/be removed (guard closed)");
        check(loop.getBlockState(pos).is(Blocks.MOVING_PISTON),
                "Scenario 1: tick 300 block MUST remain MOVING_PISTON (not settled)");

        // Ticks 301..310: stays stopped.
        for (int t = 301; t <= 310; t++) {
            oracle.tick(false, true);
            loop.tick();
            check(loop.emptyTime() == t, "Scenario 1: tick " + t + " emptyTime=" + loop.emptyTime());
            check(mp.getProgress(1.0F) == 1.0F,
                    "Scenario 1: tick " + t + " piston must remain at 1.0, got " + mp.getProgress(1.0F));
            check(!mp.isRemoved(), "Scenario 1: tick " + t + " piston must remain unremoved");
            check(loop.getBlockState(pos).is(Blocks.MOVING_PISTON),
                    "Scenario 1: tick " + t + " block must remain MOVING_PISTON");
        }
    }

    /**
     * Scenario 2: A moving piston placed while the level is already empty past
     * the threshold does not advance at all.
     */
    private static void checkPistonStartedPastThreshold() {
        TickLoop loop = new TickLoop(new SectionStore(-64, 384));
        loop.setActiveTickets(() -> false);

        // Run 310 ticks with no active tickets.
        for (int t = 1; t <= 310; t++) {
            loop.tick();
        }
        check(loop.emptyTime() == 310, "Scenario 2: emptyTime should be 310, got " + loop.emptyTime());

        // Place a moving piston at tick 310.
        BlockPos pos = new BlockPos(20, 64, 20);
        loop.setBlock(pos, Blocks.MOVING_PISTON.defaultBlockState(), 3);
        MovingPiston mp = new MovingPiston(pos, Blocks.OAK_PLANKS.defaultBlockState(), Direction.UP, true, false);
        loop.blockEntities().putMovingPiston(mp);

        // Run 50 more ticks past threshold (ticks 311..360).
        for (int t = 311; t <= 360; t++) {
            loop.tick();
            check(mp.getProgress(0.0F) == 0.0F,
                    "Scenario 2: tick " + t + " piston past threshold must not advance, progress="
                            + mp.getProgress(0.0F));
            check(!mp.isRemoved(), "Scenario 2: tick " + t + " piston past threshold must not be removed");
            check(mp.getLastTicked() == 0,
                    "Scenario 2: tick " + t + " piston past threshold lastTicked must remain 0");
            check(loop.getBlockState(pos).is(Blocks.MOVING_PISTON),
                    "Scenario 2: tick " + t + " block must remain MOVING_PISTON");
        }
    }

    /**
     * Scenario 3: The sleeper pass does not run past the threshold (proven from
     * the sleeper registry's own state, not from a test counter).
     */
    private static void checkSleeperStatePastThreshold() {
        TickLoop loop = new TickLoop(new SectionStore(-64, 384));
        loop.setActiveTickets(() -> false);

        BlockPos furnacePos = new BlockPos(30, 64, 30);
        loop.registerBlockEntity(furnacePos, SleeperRegistry.Type.FURNACE);
        long id = furnacePos.asLong();

        // While emptyTime < 300, running a tick runs sleepTick, which calls reportIdle(id, true).
        // That transitions the sleeper into asleep = true.
        loop.tick(); // tick 1
        check(loop.sleepers().isAsleep(id),
                "Scenario 3: sleeper should be asleep after tick 1 (emptyTime=" + loop.emptyTime() + ")");

        // Advance to tick 305 (past threshold).
        for (int t = 2; t <= 305; t++) {
            loop.tick();
        }
        check(loop.emptyTime() == 305, "Scenario 3: emptyTime=" + loop.emptyTime());
        check(loop.sleepers().isAsleep(id), "Scenario 3: sleeper still asleep at tick 305");

        // Wake the sleeper via a valid wake signal (FURNACE_SLOT0).
        loop.wakeBlockEntity(furnacePos, SleeperRegistry.WakeSignal.FURNACE_SLOT0);
        check(!loop.sleepers().isAsleep(id), "Scenario 3: sleeper must be awake immediately after wake signal");

        // Run 25 ticks while emptyTime >= 300 (ticks 306..330).
        // Since emptyTime >= 300, the sleeper pass does not run (beginPass and sleepTick are skipped).
        // Therefore, reportIdle is never called, and the sleeper MUST REMAIN AWAKE.
        for (int t = 306; t <= 330; t++) {
            loop.tick();
            check(!loop.sleepers().isAsleep(id),
                    "Scenario 3: tick " + t + " sleeper MUST remain awake while guard is closed (emptyTime="
                            + loop.emptyTime() + ")");
        }

        // Re-enter player: activeTickets becomes true.
        boolean[] active = {true};
        loop.setActiveTickets(() -> active[0]);

        // Run 1 tick: resetEmptyTime resets emptyTime to 0, then increments to 1 (< 300).
        // The sleeper pass runs, sleepTick evaluates idle, and the sleeper goes back to sleep!
        loop.tick(); // tick 331
        check(loop.emptyTime() == 1, "Scenario 3: emptyTime should reset to 1, got " + loop.emptyTime());
        check(loop.sleepers().isAsleep(id),
                "Scenario 3: sleeper must transition back to asleep on the tick active tickets resume");
    }

    /**
     * Scenario 4: A player in the level prevents the guard from ever closing,
     * over thousands of ticks, and the piston settles.
     */
    private static void checkPlayerPresentThroughout() {
        TickLoop loop = new TickLoop(new SectionStore(-64, 384));
        VanillaEmptyLevelModel oracle = new VanillaEmptyLevelModel();
        loop.setActiveTickets(() -> true);

        BlockPos pos = new BlockPos(40, 64, 40);
        loop.setBlock(pos, Blocks.MOVING_PISTON.defaultBlockState(), 3);
        MovingPiston mp = new MovingPiston(pos, Blocks.GOLD_BLOCK.defaultBlockState(), Direction.WEST, true, false);
        loop.blockEntities().putMovingPiston(mp);

        BlockPos signPos = new BlockPos(42, 64, 42);
        loop.registerBlockEntity(signPos, SleeperRegistry.Type.SIGN);
        long signId = signPos.asLong();

        // Run 2,000 ticks with player present.
        for (int t = 1; t <= 2000; t++) {
            oracle.tick(true, true);
            loop.tick();

            check(loop.emptyTime() == 1,
                    "Scenario 4: emptyTime must stay 1 when activeTickets=true, got " + loop.emptyTime()
                            + " at tick " + t);
            check(loop.emptyTime() == oracle.emptyTime,
                    "Scenario 4: emptyTime desync from vanilla oracle at tick " + t);

            if (t == 1) {
                check(mp.getProgress(1.0F) == 0.5F, "Scenario 4: tick 1 piston progress=0.5");
            } else if (t == 2) {
                check(mp.getProgress(1.0F) == 1.0F, "Scenario 4: tick 2 piston progress=1.0");
            } else if (t == 3) {
                check(mp.isRemoved(), "Scenario 4: tick 3 piston must settle when player present");
                check(loop.getBlockState(pos).is(Blocks.GOLD_BLOCK),
                        "Scenario 4: tick 3 block must settle into GOLD_BLOCK");
            }

            // Every 250 ticks, wake the sign; it must sleep on the very next tick.
            if (t % 250 == 0) {
                loop.wakeBlockEntity(signPos, SleeperRegistry.WakeSignal.SIGN_EDITOR_WRITE);
                check(!loop.sleepers().isAsleep(signId),
                        "Scenario 4: sign must wake at tick " + t);
            } else if (t % 250 == 1) {
                check(loop.sleepers().isAsleep(signId),
                        "Scenario 4: sign must return to sleep at tick " + t);
            }
        }
    }

    /**
     * Scenario 5: A player joining after the threshold causes execution to resume
     * on the tick the counter resets, and a level emptied again re-closes.
     */
    private static void checkPlayerJoiningAfterThresholdAndEmptyAgain() {
        TickLoop loop = new TickLoop(new SectionStore(-64, 384));
        VanillaEmptyLevelModel oracle = new VanillaEmptyLevelModel();
        boolean[] playerOnline = {false};
        loop.setActiveTickets(() -> playerOnline[0]);

        // Run 320 empty ticks.
        for (int t = 1; t <= 320; t++) {
            oracle.tick(false, true);
            loop.tick();
        }
        check(loop.emptyTime() == 320, "Scenario 5: emptyTime at tick 320=" + loop.emptyTime());

        // Place a moving piston at progress 0.0 waiting to advance.
        BlockPos pos = new BlockPos(50, 64, 50);
        loop.setBlock(pos, Blocks.MOVING_PISTON.defaultBlockState(), 3);
        MovingPiston mp = new MovingPiston(pos, Blocks.DIAMOND_BLOCK.defaultBlockState(), Direction.SOUTH, true, false);
        // Step once while empty: it should NOT advance.
        loop.blockEntities().putMovingPiston(mp);
        loop.tick();
        check(mp.getProgress(1.0F) == 0.0F, "Scenario 5: piston placed while empty must not advance");

        // Player joins at tick 322.
        playerOnline[0] = true;
        oracle.tick(true, true);
        loop.tick(); // tick 322
        check(loop.emptyTime() == 1, "Scenario 5: emptyTime must reset to 1 on join, got " + loop.emptyTime());
        check(loop.emptyTime() == oracle.emptyTime, "Scenario 5: emptyTime matches oracle on join");
        check(mp.getProgress(1.0F) == 0.5F,
                "Scenario 5: piston must resume on join tick, got progress=" + mp.getProgress(1.0F));

        // Advance next 2 ticks with player online: piston should settle.
        loop.tick(); // tick 323 -> progress 1.0
        check(mp.getProgress(1.0F) == 1.0F, "Scenario 5: progress=1.0 at tick 323");
        loop.tick(); // tick 324 -> settles
        check(mp.isRemoved(), "Scenario 5: piston settled at tick 324");
        check(loop.getBlockState(pos).is(Blocks.DIAMOND_BLOCK), "Scenario 5: block settled into DIAMOND_BLOCK");

        // Player stays for 40 ticks.
        for (int t = 325; t <= 365; t++) {
            oracle.tick(true, true);
            loop.tick();
            check(loop.emptyTime() == 1, "Scenario 5: emptyTime stays 1 while player online");
        }

        // Player leaves at tick 366. Level is empty again!
        playerOnline[0] = false;
        // Run 297 empty ticks.
        for (int t = 1; t <= 297; t++) {
            oracle.tick(false, true);
            loop.tick();
            check(loop.emptyTime() == t + 1,
                    "Scenario 5: emptyTime after re-emptying tick " + t + "=" + loop.emptyTime());
        }
        check(loop.emptyTime() == 298, "Scenario 5: emptyTime should be 298");

        // Place a second moving piston at emptyTime 298.
        BlockPos pos2 = new BlockPos(55, 64, 55);
        loop.setBlock(pos2, Blocks.MOVING_PISTON.defaultBlockState(), 3);
        MovingPiston mp2 = new MovingPiston(pos2, Blocks.EMERALD_BLOCK.defaultBlockState(), Direction.NORTH, true, false);
        loop.blockEntities().putMovingPiston(mp2);

        // Tick with emptyTime -> 299 (< 300): advances to 0.5.
        oracle.tick(false, true);
        loop.tick();
        check(loop.emptyTime() == 299, "Scenario 5: emptyTime=299");
        check(mp2.getProgress(1.0F) == 0.5F, "Scenario 5: mp2 advances to 0.5 at emptyTime 299");

        // Tick with emptyTime -> 300 (>= 300): guard closes! Must NOT advance!
        oracle.tick(false, true);
        loop.tick();
        check(loop.emptyTime() == 300, "Scenario 5: emptyTime=300");
        check(!oracle.ticksEntitiesAndBlockEntities(), "Scenario 5: oracle guard closed at 300");
        check(mp2.getProgress(1.0F) == 0.5F,
                "Scenario 5: mp2 must stop at 0.5 at emptyTime 300, got " + mp2.getProgress(1.0F));
        check(!mp2.isRemoved(), "Scenario 5: mp2 must not be removed at emptyTime 300");
    }

    /**
     * Scenario 6: The entity phase and the block-entity phase switch off on the
     * same tick, not one tick apart.
     */
    private static void checkSimultaneousSwitchOff() {
        TickLoop loop = new TickLoop(new SectionStore(-64, 384));
        VanillaEmptyLevelModel oracle = new VanillaEmptyLevelModel();
        EntityWorld world = new EntityWorld(new TestEntityLevel(loop), null);
        loop.setEntityWorld(world);
        loop.setActiveTickets(() -> false);

        ProbeEntity probe = new ProbeEntity();
        world.addFreshEntity(probe);

        BlockPos furnacePos = new BlockPos(60, 64, 60);
        loop.registerBlockEntity(furnacePos, SleeperRegistry.Type.FURNACE);
        long id = furnacePos.asLong();

        int lastEntityTick = 0;
        int lastBlockEntityTick = 0;

        for (int t = 1; t <= 315; t++) {
            int entityBefore = probe.tickCount;
            // Wake furnace before tick so we can observe if sleep evaluation runs this tick.
            loop.wakeBlockEntity(furnacePos, SleeperRegistry.WakeSignal.FURNACE_SLOT0);
            check(!loop.sleepers().isAsleep(id), "Scenario 6: furnace awake before tick " + t);

            oracle.tick(false, true);
            loop.tick();

            boolean entityTicked = (probe.tickCount > entityBefore);
            boolean blockEntityTicked = loop.sleepers().isAsleep(id);

            check(entityTicked == oracle.ticksEntitiesAndBlockEntities(),
                    "Scenario 6: entity ticking mismatch at tick " + t + ": kestrel=" + entityTicked
                            + " oracle=" + oracle.ticksEntitiesAndBlockEntities());
            check(blockEntityTicked == oracle.ticksEntitiesAndBlockEntities(),
                    "Scenario 6: block-entity ticking mismatch at tick " + t + ": kestrel="
                            + blockEntityTicked + " oracle=" + oracle.ticksEntitiesAndBlockEntities());
            check(entityTicked == blockEntityTicked,
                    "Scenario 6: entity and block-entity must switch together at tick " + t
                            + " (entity=" + entityTicked + ", blockEntity=" + blockEntityTicked + ")");

            if (entityTicked) {
                lastEntityTick = t;
            }
            if (blockEntityTicked) {
                lastBlockEntityTick = t;
            }
        }

        check(lastEntityTick == 299,
                "Scenario 6: last entity tick must be 299, got " + lastEntityTick);
        check(lastBlockEntityTick == 299,
                "Scenario 6: last block-entity tick must be 299, got " + lastBlockEntityTick);
        check(lastEntityTick == lastBlockEntityTick,
                "Scenario 6: entity phase and block-entity phase must switch off on the exact same tick");
    }

    /**
     * Scenario 7: Relative phase order within the tick.
     * Entities tick before block entities inside the guard.
     */
    private static void checkRelativePhaseOrderWithinTick() {
        TickLoop loop = new TickLoop(new SectionStore(-64, 384));
        EntityWorld world = new EntityWorld(new TestEntityLevel(loop), null);
        loop.setEntityWorld(world);
        loop.setActiveTickets(() -> true);

        BlockPos furnacePos = new BlockPos(70, 64, 70);
        loop.registerBlockEntity(furnacePos, SleeperRegistry.Type.FURNACE);
        long id = furnacePos.asLong();

        List<String> eventOrder = new ArrayList<>();
        ProbeEntity probe = new ProbeEntity();
        probe.onTick = () -> {
            // When entity ticks, the block-entity pass must NOT have run yet.
            // Since furnace was woken before tick, it must still be awake here!
            eventOrder.add("entity");
            check(!loop.sleepers().isAsleep(id),
                    "Scenario 7: entity phase must run BEFORE block-entity pass (sleeper still awake)");
        };
        world.addFreshEntity(probe);

        loop.setAfterEntityTick(() -> {
            // Hook right after entity tick and before block entities.
            eventOrder.add("after_entity_hook");
            check(!loop.sleepers().isAsleep(id),
                    "Scenario 7: afterEntityTick must run BEFORE block-entity pass (sleeper still awake)");
        });

        for (int round = 1; round <= 10; round++) {
            eventOrder.clear();
            loop.wakeBlockEntity(furnacePos, SleeperRegistry.WakeSignal.FURNACE_SLOT0);

            loop.tick();

            // After tick, the sleeper pass has completed, so the furnace is asleep.
            check(loop.sleepers().isAsleep(id),
                    "Scenario 7: sleeper must be asleep after full tick");
            check(eventOrder.size() == 2,
                    "Scenario 7: expected 2 events recorded, got " + eventOrder.size());
            check(eventOrder.get(0).equals("entity"),
                    "Scenario 7: first event must be entity, got " + eventOrder.get(0));
            check(eventOrder.get(1).equals("after_entity_hook"),
                    "Scenario 7: second event must be after_entity_hook, got " + eventOrder.get(1));
        }
    }
}
