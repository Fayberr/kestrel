package dev.kestrel.blockentity;

/**
 * Idle predicates for step 5's full ten-type sleep set.
 *
 * <p>Each predicate is the exact condition under which the vanilla tick body
 * is a provable no-op, read off the 26.2 decompile (Vineflower over the
 * nested server-26.2 jar; fresh decompile 2026-09-11 during implementation,
 * cross-checked against {@code preresearch/block-entity-tick-analysis.md}
 * item 3). Stated as pure functions over scalar snapshots so they run on
 * plain {@code javac}/{@code java} with no vanilla classes, and so the
 * proof battery ({@link SleeperCheck}) can drive them without a live level.
 *
 * <p><b>RNG admission.</b> A skipped tick that would have drawn from
 * {@code level.getRandom()} renumbers every later draw in the level, which
 * the parity contract treats as a total failure. Every predicate below is
 * RNG-neutral on its idle path; each javadoc says why. The beehive is the
 * instructive case: its ambient-sound draw sits behind the same
 * {@code stored.isEmpty()} condition as the predicate itself, so an empty
 * hive provably draws nothing.
 *
 * <p><b>Loot-table guard.</b> {@code RandomizableContainerBlockEntity}
 * unpacks a pending loot table lazily on first touch, drawing loot RNG and
 * filling slots. A sleeper with {@code lootTable} still set is therefore
 * NOT provably idle. Furnace, shulker, and crafter are randomizable
 * containers, so their predicates take a {@code lootTableSet} flag and
 * require it clear. The remaining types are not containers and need no
 * guard.
 */
public final class SleepPredicates {
    private SleepPredicates() {
    }

    /**
     * Furnace idle (covers furnace, blast furnace, smoker: all three share
     * {@code AbstractFurnaceBlockEntity.serverTick} via
     * {@code createFurnaceTicker}).
     *
     * <p>Source: {@code AbstractFurnaceBlockEntity.serverTick}
     * (analysis doc item 3, {@code :139}; implementation decompile
     * {@code src/AbstractFurnaceBlockEntity.java:101}).
     *
     * <p>Derivation: {@code litTimeRemaining == 0} skips the decrement and
     * forces {@code wasLit == isLit == false}, so the {@code setBlock(LIT)}
     * tail cannot fire. An empty input or fuel slot makes
     * {@code isLit || (hasFuel && hasIngredient)} false. Then only the
     * {@code else if (cookingTimer > 0)} branch could still write, and
     * {@code cookingTimer == 0} kills it: the branch decrements via
     * {@code Mth.clamp(timer - 2, ...)}, a real state change, which is why
     * the timer clause is load-bearing and not redundant. {@code changed}
     * stays false so no {@code setChanged}.
     *
     * <p>Deliberate gap (analysis doc item 3): a furnace with both slots
     * filled but no matching recipe is also a no-op, but proving that costs
     * the {@code quickCheck.getRecipeFor} call this predicate exists to
     * avoid. Not claimed.
     *
     * <p>RNG: the idle path draws nothing. Recipe lookup, burn, and fuel
     * consumption all sit inside the skipped branch.
     *
     * @param litTimeRemaining {@code litTimeRemaining} field
     * @param cookingTimer {@code cookingTimer} field
     * @param slot0Empty input slot empty
     * @param slot1Empty fuel slot empty
     * @param lootTableSet pending loot table present (must be false)
     */
    public static boolean furnaceIdle(int litTimeRemaining, int cookingTimer,
            boolean slot0Empty, boolean slot1Empty, boolean lootTableSet) {
        if (lootTableSet) {
            return false;
        }
        return litTimeRemaining == 0 && cookingTimer == 0 && (slot0Empty || slot1Empty);
    }

    /**
     * Sign idle.
     *
     * <p>Source: {@code SignBlockEntity.tick} (analysis doc item 3;
     * implementation decompile {@code src/SignBlockEntity.java:265}), in
     * full:
     * <pre>{@code
     * UUID playerWhoMayEdit = signBlockEntity.getPlayerWhoMayEdit();
     * if (playerWhoMayEdit != null) {
     *    signBlockEntity.clearInvalidPlayerWhoMayEdit(signBlockEntity, level, playerWhoMayEdit);
     * }
     * }</pre>
     * The field is non-null only between opening a sign for editing and the
     * edit landing or the player walking out of range. Null means the body
     * is one read and one comparison.
     *
     * <p>Wake hook warning (analysis doc item 4):
     * {@code setAllowedPlayerEditor} ({@code :232}) is a bare field write:
     * neither opening a sign nor the edit landing calls
     * {@code setChanged()}. A {@code setChanged}-only wake set silently
     * misses it.
     *
     * <p>RNG: the idle path draws nothing.
     *
     * @param playerWhoMayEditNull {@code playerWhoMayEdit == null}
     */
    public static boolean signIdle(boolean playerWhoMayEditNull) {
        return playerWhoMayEditNull;
    }

    /**
     * Shulker box idle.
     *
     * <p>Source: {@code ShulkerBoxBlockEntity.tick} ({@code :63}) into
     * {@code updateAnimation} ({@code :70}). The CLOSED branch (ordinal 0)
     * is {@code progressOld = progress; progress = 0.0F}. Idle iff the
     * status is CLOSED and both floats are already zero, making the tick a
     * provable no-op write of identical values.
     *
     * <p>The predicate correctly excludes the one settling tick right after
     * CLOSING completes: that tick flips the status to CLOSED but still has
     * a stale nonzero {@code progressOld} to zero out.
     *
     * <p>Wake hook warning (analysis doc item 4): the open/close path is
     * {@code startOpen}/{@code stopOpen}, which only send
     * {@code blockEvent}; the tick-driving {@code animationStatus} flips in
     * {@code triggerEvent} ({@code :142}), which no {@code setChanged}
     * observer sees.
     *
     * <p>RNG: the CLOSED branch draws nothing. The open/close sounds draw
     * {@code level.getRandom()} but live in {@code startOpen}/
     * {@code stopOpen}, not in the tick body.
     *
     * @param closed {@code animationStatus == CLOSED}
     * @param progress {@code progress} field
     * @param progressOld {@code progressOld} field
     * @param lootTableSet pending loot table present (must be false)
     */
    public static boolean shulkerIdle(boolean closed, float progress, float progressOld,
            boolean lootTableSet) {
        if (lootTableSet) {
            return false;
        }
        return closed && progress == 0.0F && progressOld == 0.0F;
    }

    /**
     * Brewing stand idle (cheap sufficient prefix, not the full condition).
     *
     * <p>Source: {@code BrewingStandBlockEntity.serverTick}
     * (implementation decompile {@code :69}). The full body ends with the
     * potion-bits {@code HAS_BOTTLE} reconciliation, which needs an
     * allocation plus a block read the predicate exists to avoid. So this
     * claims only the sufficient prefix: {@code brewTime == 0}, no
     * refuel write ({@code fuel <= 0} with brewing fuel in slot 4), and not
     * brewable. A stand passing the prefix might still flip a bottle bit
     * next tick; that only errs toward counting idle in the census, never
     * toward skipping work in production, because the production sleep
     * evaluation runs the predicate against live state after the real tick
     * and any bits flip wakes via the container path first.
     *
     * <p>RNG: the idle path draws nothing.
     *
     * @param brewTime {@code brewTime} field
     * @param fuel {@code fuel} field
     * @param fuelSlotIsBrewingFuel slot 4 holds brewing fuel
     * @param brewable {@code isBrewable} result
     */
    public static boolean brewingStandIdle(int brewTime, int fuel,
            boolean fuelSlotIsBrewingFuel, boolean brewable) {
        if (brewTime != 0) {
            return false;
        }
        if (fuel <= 0 && fuelSlotIsBrewingFuel) {
            return false;
        }
        return !brewable;
    }

    /**
     * Beehive idle.
     *
     * <p>Source: {@code BeehiveBlockEntity.serverTick} (implementation
     * decompile {@code :271}): {@code tickOccupants} iterates
     * {@code stored}, and an empty list means the loop body never runs and
     * {@code changed} stays false; then the ambient-sound draw sits behind
     * {@code !stored.isEmpty()}, so an empty hive draws nothing. Both the
     * work and the RNG share the one condition.
     *
     * <p>Wake hook warning (analysis doc item 4): {@code storeBee}
     * ({@code :178}) appends silently (chunk load, NBT restore), and
     * resident {@code BeeData.tick()} countdowns can mature while asleep.
     * The wake set needs the occupant-mutator signals, not just
     * {@code setChanged}.
     *
     * <p>RNG: provably none on the idle path (see above).
     *
     * @param storedEmpty {@code stored.isEmpty()}
     */
    public static boolean beehiveIdle(boolean storedEmpty) {
        return storedEmpty;
    }

    /**
     * Campfire idle, per ticker.
     *
     * <p>Source: {@code CampfireBlockEntity.cookTick} (LIT,
     * implementation decompile {@code :54}) and {@code cooldownTick}
     * (unlit, {@code :86}). Lit: the loop sets {@code changed} only for a
     * nonempty food slot, so all four slots empty means no write and no
     * {@code setChanged}. Unlit: the loop writes only for a slot with
     * {@code cookingProgress > 0}, so all-zero progress means no write.
     *
     * <p>Wake hook warning (analysis doc item 4): {@code items} is a plain
     * field, not a {@code Container}, so there is no {@code setItem} to
     * intercept. Wake comes from the block entity's own mutators
     * ({@code placeFood}, {@code clearContent}) plus {@code setBlock(LIT)}
     * swaps.
     *
     * <p>RNG: neither idle path draws. The particle ticker
     * ({@code particleTick}) draws but is client-side; the server never
     * runs it.
     *
     * @param lit LIT state
     * @param allSlotsEmpty all four food slots empty (lit case)
     * @param allProgressZero all four cookingProgress values zero (unlit case)
     */
    public static boolean campfireIdle(boolean lit, boolean allSlotsEmpty,
            boolean allProgressZero) {
        return lit ? allSlotsEmpty : allProgressZero;
    }

    /**
     * Crafter idle.
     *
     * <p>Source: {@code CrafterBlockEntity.serverTick} (implementation
     * decompile {@code :201}): the body decrements only while the counter
     * stays {@code >= 0}, i.e. only when it entered positive. At zero or
     * below the whole body is one read and one comparison.
     *
     * <p>Wake hook warning (analysis doc item 4): the wake itself is
     * {@code CrafterBlock.dispenseFrom} setting
     * {@code craftingTicksRemaining = 6} and flipping {@code CRAFTING} via
     * {@code setBlock} directly (block decompile {@code :159-160}), never
     * {@code setChanged}. Plus the inherited container mutation path, plus
     * the loot-table guard below.
     *
     * <p>RNG: the idle path draws nothing.
     *
     * @param craftingTicksRemaining {@code craftingTicksRemaining} field
     * @param lootTableSet pending loot table present (must be false)
     */
    public static boolean crafterIdle(int craftingTicksRemaining, boolean lootTableSet) {
        if (lootTableSet) {
            return false;
        }
        return craftingTicksRemaining <= 0;
    }

    /**
     * Sculk catalyst idle.
     *
     * <p>Source: {@code SculkCatalystBlockEntity.serverTick}
     * ({@code :20}) into {@code SculkSpreader.updateCursors}
     * ({@code :128}), which opens with {@code if (!cursors.isEmpty())}
     * and does nothing otherwise. Empty cursors means the whole body is
     * one emptiness check.
     *
     * <p>Wake hook warning (analysis doc item 4): the only writer is
     * {@code CatalystListener.handleGameEvent} ({@code addCursors}), plus
     * a direct {@code setBlock(PULSE)}. No {@code setChanged} path exists
     * at all. The wake hook must observe the game-event listener.
     *
     * <p>RNG: {@code updateCursors} takes {@code level.getRandom()} but
     * consumes it only inside the nonempty body.
     *
     * @param cursorsEmpty {@code sculkSpreader.cursors.isEmpty()}
     */
    public static boolean sculkCatalystIdle(boolean cursorsEmpty) {
        return cursorsEmpty;
    }

    /**
     * Sculk sensor / shrieker idle (both dispatch
     * {@code VibrationSystem.Ticker.tick}).
     *
     * <p>Source: {@code VibrationSystem$Ticker.tick} (implementation
     * decompile {@code :17}). With no vibration in flight and no queued
     * candidate, the select call returns empty and the in-flight branch is
     * skipped: no particle send, no receive, no {@code onDataChanged}. A
     * candidate whose tick is not yet due still schedules, so only "no
     * candidate at all" counts as idle.
     *
     * <p>Wake hook warning (analysis doc item 4): the arrival is
     * {@code scheduleVibration} ({@code addCandidate}) in the game-event
     * listener, before any tick. Same listener-observation requirement as
     * the catalyst.
     *
     * <p>RNG: no draw on the idle path.
     *
     * @param currentVibrationNull {@code data.currentVibration == null}
     * @param noCandidate {@code chosenCandidate(gameTime)} empty
     */
    public static boolean vibrationIdle(boolean currentVibrationNull, boolean noCandidate) {
        return currentVibrationNull && noCandidate;
    }
}
