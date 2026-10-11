package dev.kestrel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Kestrel's replacement for the tail of vanilla's
 * {@code PoiManager.findAllClosestFirstWithType} as consumed by
 * {@code AcquirePoi} (analysis target E2).
 *
 * <p><b>What actually changed, and what deliberately did not.</b> The
 * grounding doc's E2 proposed memoizing {@code findAllClosestFirstWithType}
 * results per POI type per section-column per tick, and reusing computed
 * paths across villagers. Re-reading the real 26.2 source closed both:
 * <ul>
 *   <li>The per-behavior retry memo already exists and is per-POI-state, not
 *       per-query-shape: {@code AcquirePoi}'s per-instance
 *       {@code JitteredLinearRetry batchCache} and {@code NearestBedSensor}'s
 *       {@code batchCache}/{@code triedCount} suppress exactly the failing
 *       candidates. A shared cross-villager memo would additionally have to
 *       key the caller-owned predicates ({@code cacheTest} closes over the
 *       caller's private retry table, {@code validPoi} closes over nothing
 *       shareable: bed occupancy reads live block state, job-site predicates
 *       close over the caller's profession), so it is not implementable as a
 *       sound shared cache.</li>
 *   <li>Path reuse across villagers is unsound: {@code Path} is consumed by
 *       its owner's navigation ({@code moveTo(path, speed)}), and sharing one
 *       instance across navigations aliases mutable traversal state. The only
 *       valid sharing point would be below {@code Path} (node-level memo
 *       inside {@code PathFinder}), which is the pathfinder's own layer, not
 *       the sensor layer this class owns.</li>
 * </ul>
 *
 * <p>The real remaining waste in this path is the same shape as E1's: vanilla
 * sorts ALL matches by a comparator that recomputes {@code distSqr} on every
 * comparison, then keeps only 5 ( {@code .sorted(comparingDouble(...))}
 * followed by {@code .limit(5)} ). Kestrel computes each candidate's distance
 * once and keeps the nearest 5 with a stable top-K pass, without ever sorting
 * the full match list.
 *
 * <p>Parity: the returned set contains exactly the elements vanilla's
 * sorted-then-limit-5 stream would yield, including tie behavior. Stream
 * {@code sorted} is stable, so position {@code k} of the sorted output is the
 * element with the k-th smallest key with ties broken by encounter order; the
 * limit then takes the first 5. This selection keeps, for each rank, the same
 * encounter-order tie-break, and callers collect into a {@code Set}, so
 * downstream order is already unspecified on both sides.
 */
public final class PoiTopK {
    private PoiTopK() {
    }

    /**
     * Selects the nearest {@code k} elements of {@code candidates} by
     * {@code keyFn}, computing each key exactly once. Ties (equal keys) resolve
     * to encounter order, matching stable-sort-then-limit.
     *
     * @param candidates encounter-ordered candidates (vanilla's stream order)
     * @param keyFn      distance function, evaluated once per element
     * @param k          how many to keep ({@code AcquirePoi} uses 5)
     * @return the nearest {@code k} elements, sorted nearest-first with ties
     *         in encounter order
     */
    public static <T> List<T> selectNearest(List<T> candidates, ToDoubleFunction<? super T> keyFn, int k) {
        int n = candidates.size();
        if (k <= 0) {
            // Vanilla's limit(0) yields empty without invoking the comparator
            // at all (sorted() still runs, but no comparison is needed to know
            // the first 0 elements). Charge nothing and touch nothing.
            return new ArrayList<>();
        }
        if (k >= n) {
            // Everything survives the limit: order nearest-first, ties in
            // encounter order, keys computed once. Same as ScanOrder's contract.
            List<T> out = new ArrayList<>(candidates);
            ScanOrder.sortInPlaceByKey(out, keyFn);
            return out;
        }
        double[] keys = new double[n];
        for (int i = 0; i < n; i++) {
            keys[i] = keyFn.applyAsDouble(candidates.get(i));
        }
        keyEvaluations += n;

        // Index heap: worst of the current top-K at the root. "Worse" means
        // larger key, or same key with a LATER encounter index (so the earlier
        // encounter survives ties, matching stable sort).
        int[] heap = new int[k];
        for (int i = 0; i < k; i++) {
            heap[i] = i;
        }
        for (int i = (k >> 1) - 1; i >= 0; i--) {
            siftDown(heap, keys, i, k);
        }
        for (int i = k; i < n; i++) {
            if (isBetter(i, heap[0], keys)) {
                heap[0] = i;
                siftDown(heap, keys, 0, k);
            }
        }

        // Emit nearest-first, ties in encounter order: insertion-sort the K
        // survivor indices by (key, index) using the already-computed keys.
        // K is tiny (vanilla uses 5); no further key evaluations happen here.
        for (int i = 1; i < k; i++) {
            int v = heap[i];
            int j = i - 1;
            while (j >= 0 && isBetter(v, heap[j], keys)) {
                heap[j + 1] = heap[j];
                j--;
            }
            heap[j + 1] = v;
        }
        List<T> out = new ArrayList<>(k);
        for (int i = 0; i < k; i++) {
            out.add(candidates.get(heap[i]));
        }
        return out;
    }

    /** True if candidate {@code i} sorts strictly before current worst {@code w}. */
    private static boolean isBetter(int i, int w, double[] keys) {
        int c = Double.compare(keys[i], keys[w]);
        return c < 0 || (c == 0 && i < w);
    }

    /** True if heap position {@code a} is worse-or-equal than position {@code b}. */
    private static boolean worseThan(int a, int b, int[] heap, double[] keys) {
        return !isBetter(heap[a], heap[b], keys);
    }

    private static void siftDown(int[] heap, double[] keys, int root, int size) {
        int i = root;
        for (;;) {
            int left = (i << 1) + 1;
            if (left >= size) {
                return;
            }
            int worst = left;
            int right = left + 1;
            if (right < size && worseThan(right, left, heap, keys)) {
                worst = right;
            }
            if (!worseThan(worst, i, heap, keys)) {
                return;
            }
            int tmp = heap[i];
            heap[i] = heap[worst];
            heap[worst] = tmp;
            i = worst;
        }
    }

    /** Key computations performed by {@link #selectNearest}, for the proof and bench. */
    private static long keyEvaluations;

    public static long keyEvaluations() {
        return keyEvaluations;
    }

    public static void resetKeyEvaluations() {
        keyEvaluations = 0L;
    }
}
