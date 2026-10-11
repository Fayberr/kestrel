package dev.kestrel.ai;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Kestrel's replacement for vanilla's
 * {@code NearestLivingEntitySensor} scan plus the
 * {@code NearestVisibleLivingEntities} view it produces (analysis target E1).
 *
 * <p><b>What actually changed, and what deliberately did not.</b> The grounding
 * doc's E1 proposed "cache the LOS verdict per (observer, target) for the
 * scan's lifetime" and "distance-sort THEN clip only the nearest K". Reading
 * the real 26.2 source showed vanilla already does BOTH:
 * <ul>
 *   <li>The verdict memo exists twice over: {@code Sensing}'s per-tick
 *       seen/unseen IntSets underneath {@code TargetingConditions}, and
 *       {@code NearestVisibleLivingEntities}' own per-scan
 *       {@code Object2BooleanOpenHashMap}. See {@link LosVerdictCache}.</li>
 *   <li>Range-first ordering exists: {@code NearestLivingEntitySensor} sorts by
 *       {@code distanceToSqr} before constructing the view, and
 *       {@code findClosest}/{@code contains} walk that sorted list and
 *       short-circuit on the first match, so the nearest-K-only clipping is
 *       already what happens.</li>
 * </ul>
 * Adding a third cache or a re-sort would have been pure overhead. The real
 * remaining waste in this path is the sort itself: vanilla recomputes
 * {@code distanceToSqr} inside the comparator on every comparison. Kestrel
 * computes each distance once ({@link ScanOrder}) and keeps the permutation
 * bit-identical.
 *
 * <p>Query semantics are a faithful port of {@code NearestVisibleLivingEntities}:
 * same iteration order, same {@code filter.test(e) && losTest(e)} evaluation
 * order and short-circuit, so the number and sequence of verdict evaluations
 * is unchanged.
 */
public final class VisibleEntityScan {
    private static final VisibleEntityScan EMPTY = new VisibleEntityScan();

    private List<LivingEntity> nearbyEntities = List.of();
    private Predicate<LivingEntity> targetableOracle = ignored -> false;
    private final LosVerdictCache verdicts = new LosVerdictCache();

    public static VisibleEntityScan empty() {
        return EMPTY;
    }

    /**
     * Vanilla-equivalent of {@code NearestLivingEntitySensor.doTick}'s entity
     * collection: same AABB, same predicate, same resulting order, with each
     * element's distance computed exactly once instead of once per comparison.
     *
     * <p>The returned list is the same mutable list object vanilla would store
     * into {@code MemoryModuleType.NEAREST_LIVING_ENTITIES}, so aliasing
     * behavior is preserved.
     */
    public static List<LivingEntity> collectSorted(ServerLevel level, LivingEntity body) {
        double followRange = body.getAttributeValue(Attributes.FOLLOW_RANGE);
        AABB boundingBox = body.getBoundingBox().inflate(followRange, followRange, followRange);
        List<LivingEntity> livingEntities =
                level.getEntitiesOfClass(LivingEntity.class, boundingBox, mob -> mob != body && mob.isAlive());
        ScanOrder.sortInPlaceByKey(livingEntities, body::distanceToSqr);
        return livingEntities;
    }

    /**
     * Opens a scan over {@code distanceSorted} using vanilla's real
     * targetability test as the oracle. Every previously cached verdict is
     * dropped here: a scan sees the world as it is now, never as a previous
     * scan saw it.
     */
    public void beginScan(ServerLevel level, LivingEntity body, List<LivingEntity> distanceSorted) {
        beginScan(distanceSorted, target -> Sensor.isEntityTargetable(level, body, target));
    }

    /** Oracle-injectable form, used by the proof to drive controlled verdicts. */
    public void beginScan(List<LivingEntity> distanceSorted, Predicate<LivingEntity> targetableOracle) {
        this.nearbyEntities = distanceSorted;
        this.targetableOracle = targetableOracle;
        this.verdicts.beginScan();
    }

    /** Closes the scan and drops every verdict. */
    public void endScan() {
        this.nearbyEntities = List.of();
        this.targetableOracle = ignored -> false;
        this.verdicts.endScan();
    }

    public List<LivingEntity> nearbyEntities() {
        return nearbyEntities;
    }

    public LosVerdictCache verdictCache() {
        return verdicts;
    }

    /** Port of {@code NearestVisibleLivingEntities.findClosest}. */
    public Optional<LivingEntity> findClosest(Predicate<LivingEntity> filter) {
        for (int i = 0; i < nearbyEntities.size(); i++) {
            LivingEntity nearbyEntity = nearbyEntities.get(i);
            if (filter.test(nearbyEntity) && visible(nearbyEntity)) {
                return Optional.of(nearbyEntity);
            }
        }
        return Optional.empty();
    }

    /** Port of {@code NearestVisibleLivingEntities.findAll}, lazy like Guava's Iterables.filter. */
    public Iterable<LivingEntity> findAll(Predicate<LivingEntity> filter) {
        return () -> new FilteringIterator(nearbyEntities, filter);
    }

    /** Port of {@code NearestVisibleLivingEntities.find}. */
    public Stream<LivingEntity> find(Predicate<LivingEntity> filter) {
        return StreamSupport.stream(findAll(filter).spliterator(), false);
    }

    /** Port of {@code NearestVisibleLivingEntities.contains(LivingEntity)}. */
    public boolean contains(LivingEntity targetEntity) {
        return nearbyEntities.contains(targetEntity) && visible(targetEntity);
    }

    /** Port of {@code NearestVisibleLivingEntities.contains(Predicate)}. */
    public boolean contains(Predicate<LivingEntity> filter) {
        for (int i = 0; i < nearbyEntities.size(); i++) {
            LivingEntity nearbyEntity = nearbyEntities.get(i);
            if (filter.test(nearbyEntity) && visible(nearbyEntity)) {
                return true;
            }
        }
        return false;
    }

    /** Memoized targetability verdict, one oracle call per entity per scan. */
    public boolean visible(LivingEntity entity) {
        return verdicts.verdict(entity.getId(), entity, targetableOracle);
    }

    /**
     * Lazy filtering iterator matching Guava's {@code Iterables.filter}
     * semantics as used by vanilla's {@code findAll}: the predicate pair is
     * evaluated on demand during iteration, filter first, verdict second.
     */
    private final class FilteringIterator implements Iterator<LivingEntity> {
        private final List<LivingEntity> source;
        private final Predicate<LivingEntity> filter;
        private int index;
        private boolean hasNext;
        private LivingEntity next;

        FilteringIterator(List<LivingEntity> source, Predicate<LivingEntity> filter) {
            this.source = source;
            this.filter = filter;
        }

        @Override
        public boolean hasNext() {
            while (!hasNext && index < source.size()) {
                LivingEntity candidate = source.get(index++);
                if (filter.test(candidate) && visible(candidate)) {
                    next = candidate;
                    hasNext = true;
                }
            }
            return hasNext;
        }

        @Override
        public LivingEntity next() {
            if (!hasNext && !hasNext()) {
                throw new java.util.NoSuchElementException();
            }
            hasNext = false;
            LivingEntity result = next;
            next = null;
            return result;
        }
    }

    /** Materializes {@link #findAll} eagerly, for callers that want a list. */
    public List<LivingEntity> collectAll(Predicate<LivingEntity> filter) {
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity entity : findAll(filter)) {
            out.add(entity);
        }
        return out;
    }
}
