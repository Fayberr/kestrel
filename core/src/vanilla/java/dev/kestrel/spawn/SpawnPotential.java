package dev.kestrel.spawn;

import java.util.ArrayList;
import java.util.List;

/**
 * Step 6's charge math: a port of vanilla's {@code PotentialCalculator}
 * ({@code PotentialCalculator.java}) plus the two {@code SpawnState} methods
 * that use it, as pure functions over scalar snapshots.
 *
 * <p><b>Transcribed from vanilla 26.2, not re-derived.</b>
 *
 * <ul>
 *   <li>{@code addCharge(pos, charge)} is a no-op for {@code charge == 0.0}
 *       and appends a {@code PointCharge} otherwise. Charges are never
 *       removed within a tick; the calculator is rebuilt per tick by
 *       {@code createState}.</li>
 *   <li>{@code getPotentialEnergyChange(pos, charge)} returns 0.0 for zero
 *       charge, else {@code charge * sum(point.charge / sqrt(distSqr))}
 *       over all stored points, where a zero-distance point contributes
 *       {@code POSITIVE_INFINITY} ({@code PointCharge.getPotentialChange}:
 *       {@code distSqr == 0.0 ? INFINITY : charge / sqrt(distSqr)}).</li>
 *   <li>{@code SpawnState.canSpawn} ({@code NaturalSpawner.java:494}):
 *       records {@code lastCheckedPos/lastCheckedType} and the biome cost's
 *       charge ({@code lastCharge}, 0.0 when cost is null), then returns
 *       {@code getPotentialEnergyChange(testPos, charge) <= energyBudget}.
 *       Note the boundary: vanilla uses {@code <=}, so equality spawns.</li>
 *   <li>{@code SpawnState.afterSpawn} ({@code NaturalSpawner.java:509}):
 *       reuses {@code lastCharge} only when {@code pos.equals(lastCheckedPos)
 *       && type == lastCheckedType} (reference equality on the type);
 *       otherwise re-reads the biome cost (or 0.0 when null). Then
 *       {@code addCharge(pos, charge)}, {@code mobCounts.addTo}, and
 *       {@code localMobCapCalculator.addMob}. This class owns the charge
 *       half; counts and caps live in {@link SpawnCaps}.</li>
 * </ul>
 *
 * <p>Positions are Minecrat block coordinates packed as three ints; the port
 * uses {@code long[]} snapshots of {@code (x, y, z)} triples plus a parallel
 * {@code double[]} of charges, so the battery can drive thousands of
 * randomized trials without any block-position type.
 *
 * <p><b>What this is NOT.</b> It is not an indexed or gridded acceleration
 * (O(charges) scan, exactly like vanilla, in Leg 2). Any future index must
 * prove trace identity against this class first; that is the point of
 * porting the slow version exactly before optimizing it.
 *
 * <p>No Minecraft classes are touched: plain {@code javac}/{@code java},
 * any machine. Thread-safety: single tick-thread only. No synchronization.
 */
public final class SpawnPotential {

    private final List<long[]> positions = new ArrayList<>();
    private final List<Double> charges = new ArrayList<>();

    /** Ports {@code PotentialCalculator.addCharge}: ignores zero charge. */
    public void addCharge(long x, long y, long z, double charge) {
        if (charge != 0.0) {
            positions.add(new long[]{x, y, z});
            charges.add(charge);
        }
    }

    /** Number of stored point charges (vanilla exposes no such count; the battery uses it). */
    public int chargeCount() {
        return positions.size();
    }

    /**
     * Ports {@code PotentialCalculator.getPotentialEnergyChange}.
     *
     * @return {@code charge * sum(pointCharge / sqrt(distSqr))}, 0.0 for zero
     *         charge, {@code charge * INFINITY} when a stored point sits at
     *         exactly the query position
     */
    public double getPotentialEnergyChange(long x, long y, long z, double charge) {
        if (charge == 0.0) {
            return 0.0;
        }
        double potentialChange = 0.0;
        for (int i = 0; i < positions.size(); i++) {
            long[] p = positions.get(i);
            double dx = (double) p[0] - x;
            double dy = (double) p[1] - y;
            double dz = (double) p[2] - z;
            double distSqr = dx * dx + dy * dy + dz * dz;
            double pointChange = distSqr == 0.0
                ? Double.POSITIVE_INFINITY
                : charges.get(i) / Math.sqrt(distSqr);
            potentialChange += pointChange;
        }
        return potentialChange * charge;
    }

    /**
     * Ports the {@code SpawnState.canSpawn} decision for one candidate: true
     * iff the potential energy change is within budget ({@code <=}).
     *
     * @param hasCost false models a null {@code MobSpawnCost} (charge 0.0,
     *                always spawns); true uses {@code charge}/{@code budget}
     */
    public boolean canSpawn(long x, long y, long z, boolean hasCost, double charge, double budget) {
        if (!hasCost) {
            return true;
        }
        return getPotentialEnergyChange(x, y, z, charge) <= budget;
    }

    /**
     * Ports the {@code SpawnState.afterSpawn} charge-aliasing rule: returns
     * the charge to apply for a spawn at {@code (x,y,z)} of type
     * {@code typeId} given the last {@code canSpawn} probe
     * ({@code lastX/lastY/lastZ}, {@code lastTypeId}, {@code lastCharge}).
     * Reuses {@code lastCharge} only on position-equality AND
     * type-identity; otherwise falls back to {@code freshCharge} (the
     * re-read biome cost, or 0.0 when null). Type identity is modeled as int
     * equality on caller-supplied ids, matching vanilla's reference
     * comparison on distinct type objects.
     */
    public static double resolveAfterSpawnCharge(
        long x, long y, long z, int typeId,
        long lastX, long lastY, long lastZ, int lastTypeId, double lastCharge,
        double freshCharge
    ) {
        if (x == lastX && y == lastY && z == lastZ && typeId == lastTypeId) {
            return lastCharge;
        }
        return freshCharge;
    }
}
