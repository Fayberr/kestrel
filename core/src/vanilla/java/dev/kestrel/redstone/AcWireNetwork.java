package dev.kestrel.redstone;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Headless transcription of the Alternate Current power-propagation core
 * (SpaceWalkerRS, MIT; algorithm in
 * {@code alternate.current.wire.WireHandler}).
 *
 * <p>What AC does: instead of vanilla's recursive per-wire recompute, it
 * builds the connected-wire network around the updated wire, finds the wires
 * powered from outside the network (roots), then spreads power outward in
 * three phases: search (BFS over connections for wires needing changes),
 * depower (drain the network so power spreads efficiently), power (bring
 * each wire to its new level in priority order, highest first). Each wire
 * samples non-wire sources at most once and nearby wires at most twice, and
 * sets its world power exactly once at its final value.
 *
 * <p>Kestrel adaptation: AC's {@code WireHandler} is written against a live
 * {@code ServerLevel} (block reads, setBlock, neighbor dispatch). This class
 * factors the pure propagation algebra out so it is provable without a
 * world, in the same spirit as the step-3 {@code WireUpdateFilter}:
 *
 * <ul>
 *   <li>The caller supplies the network topology: wire positions, their
 *       current powers, outside (non-wire) power per wire, and directed
 *       offer/accept connections resolved from real vanilla state (same
 *       connection rule as AC {@code WireConnectionManager.set}: same-level
 *       horizontal wires, wires below stepped through non-conductors, wires
 *       above stepped through non-above-conductors).</li>
 *   <li>This class runs search/depower/power over that topology and returns
 *       the final power per wire plus the update sequence. World writes,
 *       neighbor dispatch, and shape updates stay the caller's job (the
 *       future live loop), exactly like the step-3 filter plans dispatches
 *       it never fires.</li>
 * </ul>
 *
 * <p>Deliberate simplifications vs AC, all documented: no node cache (headless
 * inputs are short-lived), no revalidation (topology is a snapshot), no
 * break/drop handling (wires neither break nor drop mid-propagation here;
 * the live loop owns that), no re-entrancy integration (single propagation
 * per call). Each is a live-loop concern, not propagation algebra.
 */
public final class AcWireNetwork {

    /** One wire in the caller-supplied topology snapshot. */
    public static final class WireInput {
        /** Stable id used to reference this wire in results. */
        public final int id;
        /** Power currently in the world. */
        public final int currentPower;
        /** Power from non-wire components (sampled at most once by AC). */
        public final int externalPower;

        public WireInput(int id, int currentPower, int externalPower) {
            this.id = id;
            this.currentPower = currentPower;
            this.externalPower = externalPower;
        }
    }

    /**
     * Directed connection owner -&gt; neighbor. {@code offer} means the owner
     * can provide power to the neighbor; {@code accept} means the neighbor
     * can provide power to the owner (AC {@code WireConnection}).
     */
    public static final class Link {
        public final int neighborId;
        public final int dir;
        public final boolean offer;
        public final boolean accept;

        public Link(int neighborId, int dir, boolean offer, boolean accept) {
            this.neighborId = neighborId;
            this.dir = dir;
            this.offer = offer;
            this.accept = accept;
        }
    }

    /** Final power per wire id, plus the order wires settled (highest first). */
    public static final class Result {
        public final Map<Integer, Integer> finalPower;
        public final List<Integer> settleOrder;

        Result(Map<Integer, Integer> finalPower, List<Integer> settleOrder) {
            this.finalPower = finalPower;
            this.settleOrder = settleOrder;
        }
    }

    private static final class State {
        int currentPower;
        int virtualPower;
        int externalPower;
        int flowIn;
        int flowDir;
        boolean discovered;
        boolean searched;
        boolean root;
    }

    private AcWireNetwork() {
    }

    /**
     * Run the AC search/depower/power phases over the given topology.
     *
     * @param wires wire inputs by id
     * @param links outgoing links by wire id (insertion order = discovery order)
     * @param roots ids of wires requiring re-evaluation (AC root set)
     * @param order update order used for connection traversal
     * @return final powers and settle order
     */
    public static Result propagate(Map<Integer, WireInput> wires,
            Map<Integer, List<Link>> links, Set<Integer> roots, AcUpdateOrder order) {
        Map<Integer, State> states = new HashMap<>();
        for (Map.Entry<Integer, WireInput> e : wires.entrySet()) {
            State s = new State();
            s.currentPower = e.getValue().currentPower;
            s.virtualPower = e.getValue().currentPower;
            s.externalPower = e.getValue().externalPower;
            s.flowIn = 0;
            s.flowDir = 0;
            states.put(e.getKey(), s);
        }

        // Phase 1: search. BFS from roots over offer-links in update order;
        // a neighbor joins the search when its recompute differs (AC
        // searchNetwork + findPower + needsUpdate + search).
        Deque<Integer> search = new ArrayDeque<>();
        for (int root : roots) {
            discover(states, root);
            findPower(states, links, root, false);
            if (needsUpdate(states.get(root))) {
                enqueueSearch(states, search, root, true, backupFlow(states, links, root));
            }
        }
        while (!search.isEmpty()) {
            int id = search.poll();
            State s = states.get(id);
            for (Link link : orderedLinks(links.get(id), order, s.flowDir)) {
                if (!link.offer) {
                    continue;
                }
                State n = states.get(link.neighborId);
                if (n == null || n.searched) {
                    continue;
                }
                discoverId(states, link.neighborId);
                findPower(states, links, link.neighborId, false);
                State after = states.get(link.neighborId);
                if (after.virtualPower < after.currentPower) {
                    // AC re-checks external power when wire power dropped,
                    // to bound how low the wire can fall.
                    findPower(states, links, link.neighborId, false);
                }
                if (needsUpdate(after)) {
                    enqueueSearch(states, search, link.neighborId, false, link.dir);
                }
            }
        }

        // Phase 2: depower. Drain searched wires so power spreads from the
        // sources (AC depowerNetwork). Wires with no power stay below minimum
        // until a neighbor offers them power.
        List<Integer> searched = new ArrayList<>();
        for (Map.Entry<Integer, State> e : states.entrySet()) {
            if (e.getValue().searched) {
                searched.add(e.getKey());
            }
        }
        Deque<Integer> updates = new ArrayDeque<>();
        Set<Integer> queued = new HashSet<>();
        for (int id : searched) {
            State s = states.get(id);
            findPower(states, links, id, true);
            if (s.root || s.virtualPower > 0) {
                queueWire(states, updates, queued, id);
            } else {
                s.virtualPower--;
            }
        }

        // Phase 3: power. Highest power first (AC PriorityQueue, descending);
        // each wire determines flow, transmits to neighbors, settles once.
        Map<Integer, Integer> finals = new HashMap<>();
        List<Integer> settleOrder = new ArrayList<>();
        for (Map.Entry<Integer, WireInput> e : wires.entrySet()) {
            finals.put(e.getKey(), e.getValue().currentPower);
        }
        while (!updates.isEmpty()) {
            int id = pollHighest(states, updates, queued);
            State s = states.get(id);
            if (!needsUpdate(s)) {
                continue;
            }
            findPowerFlow(s);
            // Transmit to neighbors in flow order (AC transmitPower).
            for (Link link : orderedLinks(links.get(id), order, s.flowDir)) {
                if (!link.offer) {
                    continue;
                }
                State n = states.get(link.neighborId);
                if (n == null) {
                    continue;
                }
                int power = Math.max(0, s.virtualPower - 1);
                if (offerPower(n, power, link.dir)) {
                    queueWire(states, updates, queued, link.neighborId);
                }
            }
            s.currentPower = clamp(s.virtualPower);
            finals.put(id, s.currentPower);
            settleOrder.add(id);
        }

        return new Result(finals, settleOrder);
    }

    private static void discover(Map<Integer, State> states, int id) {
        discoverId(states, id);
    }

    private static void discoverId(Map<Integer, State> states, int id) {
        State s = states.get(id);
        if (s == null || s.discovered) {
            return;
        }
        s.discovered = true;
        s.searched = false;
        s.virtualPower = s.currentPower;
    }

    private static void findPower(Map<Integer, State> states,
            Map<Integer, List<Link>> links, int id, boolean ignoreSearched) {
        State s = states.get(id);
        s.virtualPower = s.externalPower;
        s.flowIn = 0;
        if (s.externalPower < 15) {
            List<Link> ls = links.get(id);
            if (ls != null) {
                for (Link link : ls) {
                    if (!link.accept) {
                        continue;
                    }
                    State n = states.get(link.neighborId);
                    if (n == null || (ignoreSearched && n.searched)) {
                        continue;
                    }
                    int power = Math.max(0, n.virtualPower - 1);
                    offerPower(s, power, AcDirections.opposite(link.dir));
                }
            }
        }
    }

    /**
     * AC {@code WireNode.offerPower}: equal power records flow without
     * changing level; higher power takes over and resets flow; lower power
     * is ignored. Returns true when the wire newly accepts power.
     */
    static boolean offerPower(State s, int power, int dir) {
        if (power == s.virtualPower) {
            s.flowIn |= (1 << dir);
            return false;
        }
        if (power > s.virtualPower) {
            s.virtualPower = power;
            s.flowIn = (1 << dir);
            return true;
        }
        return false;
    }

    private static boolean needsUpdate(State s) {
        return s.virtualPower != s.currentPower;
    }

    private static void enqueueSearch(Map<Integer, State> states,
            Deque<Integer> search, int id, boolean root, int backupFlow) {
        State s = states.get(id);
        search.offer(id);
        s.root = root;
        s.searched = true;
        s.flowDir = backupFlow;
    }

    private static int backupFlow(Map<Integer, State> states,
            Map<Integer, List<Link>> links, int id) {
        int flowTotal = 0;
        List<Link> ls = links.get(id);
        if (ls != null) {
            for (Link link : ls) {
                flowTotal |= (1 << link.dir);
            }
        }
        int flow = AcFlowTable.flowOut(flowTotal);
        return flow >= 0 ? flow : 0;
    }

    private static void queueWire(Map<Integer, State> states,
            Deque<Integer> updates, Set<Integer> queued, int id) {
        State s = states.get(id);
        if (needsUpdate(s)) {
            if (queued.add(id)) {
                updates.offer(id);
            }
        } else {
            findPowerFlow(s);
            // Transmit immediately like AC queueWire's else-branch; the
            // neighbor offer may re-queue this wire via offerPower.
            // (Re-queue handled by callers checking offerPower's return.)
        }
    }

    private static void findPowerFlow(State s) {
        int flow = AcFlowTable.flowOut(s.flowIn);
        if (flow >= 0) {
            s.flowDir = flow;
        }
    }

    private static List<Link> orderedLinks(List<Link> ls, AcUpdateOrder order, int flowDir) {
        if (ls == null || ls.isEmpty()) {
            return List.of();
        }
        int[] card = order.cardinalNeighbors(flowDir);
        Map<Integer, Integer> rank = new HashMap<>();
        for (int i = 0; i < card.length; i++) {
            rank.put(card[i], i);
        }
        List<Link> out = new ArrayList<>(ls);
        out.sort((a, b) -> Integer.compare(
                rank.getOrDefault(a.dir, 99), rank.getOrDefault(b.dir, 99)));
        return out;
    }

    private static int pollHighest(Map<Integer, State> states, Deque<Integer> updates,
            Set<Integer> queued) {
        int best = -1;
        int bestPower = Integer.MIN_VALUE;
        for (int id : updates) {
            int p = states.get(id).virtualPower;
            if (p > bestPower) {
                bestPower = p;
                best = id;
            }
        }
        updates.remove(best);
        // AC's PriorityQueue unlinks on poll, so a later re-offer reinserts.
        // Without this removal, a post-settle offer would be silently dropped
        // by the queued-set and the wire would keep a stale power.
        queued.remove(best);
        return best;
    }

    private static int clamp(int power) {
        return Math.max(0, Math.min(15, power));
    }
}
