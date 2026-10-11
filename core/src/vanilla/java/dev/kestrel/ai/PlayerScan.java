package dev.kestrel.ai;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * Kestrel's replacement for vanilla's {@code PlayerSensor} (analysis target E4).
 *
 * <p>Vanilla builds four collections per scan out of {@code level.players()}:
 * a filtered+sorted {@code players} list, a {@code visiblePlayers} list, a
 * {@code visibleAttackablePlayers} list, and reads element 0 of two of them.
 * That is four stream pipelines, three list allocations, a comparator that
 * recomputes {@code distanceToSqr} on every comparison, and two full
 * {@code TargetingConditions.test} evaluations per player, each of which
 * recomputes the same distance and the same visibility percentage.
 *
 * <p>Kestrel computes the (targetable, attackable) pair ONCE per player in a
 * single ordered pass and projects the memories from it. Only two of the three
 * lists are ever materialized, because vanilla never stores
 * {@code visiblePlayers} anywhere: it only reads its first element.
 *
 * <p><b>The clip count is unchanged, and that is not a miss.</b> The raycast
 * behind these tests is already memoized per (mob, target) per tick by
 * vanilla's {@code Sensing} (see {@link LosVerdictCache}), so the second
 * condition evaluation was never paying for a second raycast. What Kestrel
 * removes is the redundant scalar work around it: one distance computation per
 * player instead of one per comparison plus two more inside the conditions,
 * one visibility-percent evaluation instead of two, one attack-target memory
 * probe instead of two, and no stream pipelines.
 *
 * <p><b>Evaluation-order parity.</b> Vanilla derives
 * {@code visibleAttackablePlayers} by filtering {@code visiblePlayers}, so
 * attackability is tested ONLY for players that are already targetable.
 * Kestrel does the same, so the set of condition evaluations is identical, not
 * merely the outcome.
 */
public final class PlayerScan {

    private PlayerScan() {
    }

    /** The four values vanilla's PlayerSensor writes, in vanilla's write order. */
    public static final class Projection<T> {
        public final List<T> nearestPlayers;
        public final T nearestVisiblePlayer;
        public final List<T> nearestVisibleAttackablePlayers;
        public final T nearestVisibleAttackablePlayer;

        Projection(List<T> nearestPlayers,
                   T nearestVisiblePlayer,
                   List<T> nearestVisibleAttackablePlayers,
                   T nearestVisibleAttackablePlayer) {
            this.nearestPlayers = nearestPlayers;
            this.nearestVisiblePlayer = nearestVisiblePlayer;
            this.nearestVisibleAttackablePlayers = nearestVisibleAttackablePlayers;
            this.nearestVisibleAttackablePlayer = nearestVisibleAttackablePlayer;
        }
    }

    /**
     * Single-pass projection of the three derived memory values from an
     * already distance-sorted candidate list.
     *
     * <p>Generic on purpose: the identity being proven is list algebra, so the
     * proof can drive it with controlled oracles instead of live entities.
     * {@code attackable} is consulted only where {@code targetable} held,
     * exactly as vanilla's chained stream filters do.
     */
    public static <T> Projection<T> project(List<T> distanceSorted,
                                            Predicate<? super T> targetable,
                                            Predicate<? super T> attackable) {
        T nearestVisible = null;
        List<T> visibleAttackable = null;

        for (int i = 0; i < distanceSorted.size(); i++) {
            T candidate = distanceSorted.get(i);
            if (!targetable.test(candidate)) {
                continue;
            }
            if (nearestVisible == null) {
                nearestVisible = candidate;
            }
            if (attackable.test(candidate)) {
                if (visibleAttackable == null) {
                    visibleAttackable = new ArrayList<>();
                }
                visibleAttackable.add(candidate);
            }
        }

        List<T> attackableList = visibleAttackable == null
                ? List.of()
                : Collections.unmodifiableList(visibleAttackable);
        T nearestVisibleAttackable = attackableList.isEmpty() ? null : attackableList.get(0);
        return new Projection<>(distanceSorted, nearestVisible, attackableList, nearestVisibleAttackable);
    }

    /**
     * Full vanilla-bound scan: same inputs, same memories, same order as
     * {@code PlayerSensor.doTick}.
     *
     * <p>{@code followDistance} is read once and reused as the range filter,
     * the sort key basis and the targeting range. That is sound because
     * {@code Sensor.updateTargetingConditionRanges} sets every static
     * targeting condition's range to this exact value immediately before
     * {@code doTick} runs, and {@code PlayerSensor.getFollowDistance} reads the
     * same attribute. {@code MobAiProofCheck} asserts those preconditions
     * against the real classes so a future version that breaks them fails
     * loudly instead of diverging silently.
     */
    public static void scan(ServerLevel level, LivingEntity body) {
        double followDistance = body.getAttributeValue(Attributes.FOLLOW_RANGE);
        double rangeSq = followDistance * followDistance;
        Brain<?> brain = body.getBrain();

        List<? extends Player> allPlayers = level.players();
        List<Player> players = new ArrayList<>();
        for (int i = 0; i < allPlayers.size(); i++) {
            Player player = allPlayers.get(i);
            if (player.isSpectator()) {
                continue;
            }
            // Vanilla: body.closerThan(player, followDistance), which is
            // position().closerThan(...) == distanceToSqr < d*d. Identical
            // double, computed once and reused as the sort key and as the
            // targeting range check below.
            if (body.distanceToSqr(player) < rangeSq) {
                players.add(player);
            }
        }
        ScanOrder.sortInPlaceByKey(players, body::distanceToSqr);

        Projection<Player> projection = project(
                players,
                player -> isTargetable(body, brain, player, followDistance),
                player -> isAttackable(body, brain, player, followDistance));

        brain.setMemory(MemoryModuleType.NEAREST_PLAYERS, projection.nearestPlayers);
        brain.setMemory(MemoryModuleType.NEAREST_VISIBLE_PLAYER, projection.nearestVisiblePlayer);
        brain.setMemory(MemoryModuleType.NEAREST_VISIBLE_ATTACKABLE_PLAYERS,
                projection.nearestVisibleAttackablePlayers);
        brain.setMemory(MemoryModuleType.NEAREST_VISIBLE_ATTACKABLE_PLAYER,
                projection.nearestVisibleAttackablePlayer);
    }

    /**
     * Inlined equivalent of {@code Sensor.isEntityTargetable} (non-combat
     * conditions, range = follow distance, invisibility tested unless the
     * target is already the attack target, line of sight checked).
     */
    static boolean isTargetable(LivingEntity body, Brain<?> brain, LivingEntity target, double range) {
        if (body == target) {
            return false;
        }
        if (!target.canBeSeenByAnyone()) {
            return false;
        }
        boolean isAttackTarget = brain.isMemoryValue(MemoryModuleType.ATTACK_TARGET, target);
        return withinVisibilityRange(body, target, range, !isAttackTarget) && hasLineOfSight(body, target);
    }

    /**
     * Inlined equivalent of {@code Sensor.isEntityAttackable} (combat
     * conditions). Combat adds the alliance and attackability gates; the range
     * and line-of-sight halves are identical to the non-combat form.
     */
    static boolean isAttackable(LivingEntity body, Brain<?> brain, LivingEntity target, double range) {
        if (body == target) {
            return false;
        }
        if (!target.canBeSeenByAnyone()) {
            return false;
        }
        if (!body.canAttack(target) || body.isAlliedTo(target)) {
            return false;
        }
        boolean isAttackTarget = brain.isMemoryValue(MemoryModuleType.ATTACK_TARGET, target);
        return withinVisibilityRange(body, target, range, !isAttackTarget) && hasLineOfSight(body, target);
    }

    /** {@code TargetingConditions.test}'s range half, verbatim. */
    private static boolean withinVisibilityRange(LivingEntity body,
                                                 LivingEntity target,
                                                 double range,
                                                 boolean testInvisible) {
        if (range <= 0.0) {
            return true;
        }
        double modifier = testInvisible ? target.getVisibilityPercent(body) : 1.0;
        double visibilityDistance = Math.max(range * modifier, 2.0);
        double distanceToSqr = body.distanceToSqr(target.getX(), target.getY(), target.getZ());
        return distanceToSqr <= visibilityDistance * visibilityDistance;
    }

    /**
     * {@code TargetingConditions.test}'s line-of-sight half, verbatim. Note
     * that only a {@code Mob} has a {@code Sensing} memo, and a non-Mob
     * targeter skips the check entirely, exactly as vanilla does.
     */
    private static boolean hasLineOfSight(LivingEntity body, LivingEntity target) {
        return !(body instanceof Mob mob) || mob.getSensing().hasLineOfSight(target);
    }
}
