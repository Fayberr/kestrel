package dev.kestrel.ai;

import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Decorate-sort-undecorate ordering for sensor scans: sorts by a key that is
 * computed exactly ONCE per element, producing a permutation bit-identical to
 * vanilla's {@code list.sort(Comparator.comparingDouble(keyFn))}.
 *
 * <p>Why this exists: vanilla's sensor scans sort with a comparator that
 * recomputes the key on every comparison. {@code NearestLivingEntitySensor}
 * does {@code livingEntities.sort(Comparator.comparingDouble(body::distanceToSqr))}
 * and {@code PlayerSensor} does the same over {@code level.players()}, so each
 * of the ~n*log2(n) comparisons pays two {@code distanceToSqr} calls: about
 * 2*n*log2(n) distance computations where n suffice. At n=64 that is ~768
 * computations instead of 64.
 *
 * <p>Parity: the output permutation is identical for ALL inputs, not merely
 * "usually". {@code Comparator.comparingDouble} compares with
 * {@link Double#compare}, and {@code List.sort} is stable (TimSort). This
 * merge sort is stable and compares the precomputed keys with the same
 * {@link Double#compare}, so ties resolve to the same left-run-first order,
 * and -0.0/+0.0/NaN order the same way. Entity positions cannot change during
 * the sort, so precomputing the keys observes exactly the values the
 * comparator would have observed. {@code MobAiProofCheck} proves this
 * empirically against the real comparator over adversarial tie-heavy input.
 */
public final class ScanOrder {
    private ScanOrder() {
    }

    /** Key computations performed by {@link #sortInPlaceByKey}, for the proof and bench. */
    private static long keyEvaluations;

    public static long keyEvaluations() {
        return keyEvaluations;
    }

    public static void resetKeyEvaluations() {
        keyEvaluations = 0L;
    }

    /**
     * Sorts {@code list} in place by {@code keyFn}, calling {@code keyFn}
     * exactly {@code list.size()} times. Same contract as
     * {@code list.sort(Comparator.comparingDouble(keyFn))}, including
     * stability, and same requirement that the list be mutable (vanilla sorts
     * the {@code ArrayList} returned by {@code getEntitiesOfClass} /
     * {@code Collectors.toList()} in place, so Kestrel keeps that aliasing:
     * the SAME list object is the one stored into the memory module).
     */
    @SuppressWarnings("unchecked")
    public static <T> void sortInPlaceByKey(List<T> list, ToDoubleFunction<? super T> keyFn) {
        int n = list.size();
        if (n < 2) {
            // No comparison happens, so vanilla's comparator computes no key
            // either. Charge nothing and touch nothing.
            return;
        }
        Object[] elements = list.toArray();
        double[] keys = new double[n];
        for (int i = 0; i < n; i++) {
            keys[i] = keyFn.applyAsDouble((T) elements[i]);
        }
        keyEvaluations += n;
        sortByKey(elements, keys, n);
        for (int i = 0; i < n; i++) {
            list.set(i, (T) elements[i]);
        }
    }

    /**
     * Stable bottom-up merge sort of {@code elements[0..n)} by {@code keys[0..n)}.
     * Both arrays are permuted together. Ties keep the left run first, matching
     * {@code List.sort}'s stability.
     */
    static void sortByKey(Object[] elements, double[] keys, int n) {
        Object[] eSrc = elements;
        double[] kSrc = keys;
        Object[] eDst = new Object[n];
        double[] kDst = new double[n];

        for (int width = 1; width < n; width <<= 1) {
            int step = width << 1;
            for (int lo = 0; lo < n; lo += step) {
                int mid = Math.min(lo + width, n);
                int hi = Math.min(lo + step, n);
                int i = lo;
                int j = mid;
                int o = lo;
                while (i < mid && j < hi) {
                    if (Double.compare(kSrc[i], kSrc[j]) <= 0) {
                        eDst[o] = eSrc[i];
                        kDst[o] = kSrc[i];
                        i++;
                    } else {
                        eDst[o] = eSrc[j];
                        kDst[o] = kSrc[j];
                        j++;
                    }
                    o++;
                }
                while (i < mid) {
                    eDst[o] = eSrc[i];
                    kDst[o] = kSrc[i];
                    i++;
                    o++;
                }
                while (j < hi) {
                    eDst[o] = eSrc[j];
                    kDst[o] = kSrc[j];
                    j++;
                    o++;
                }
            }
            Object[] eSwap = eSrc;
            eSrc = eDst;
            eDst = eSwap;
            double[] kSwap = kSrc;
            kSrc = kDst;
            kDst = kSwap;
        }

        if (eSrc != elements) {
            System.arraycopy(eSrc, 0, elements, 0, n);
            System.arraycopy(kSrc, 0, keys, 0, n);
        }
    }
}
