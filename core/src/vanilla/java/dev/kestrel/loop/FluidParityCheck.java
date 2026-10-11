package dev.kestrel.loop;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * G11 fluid parity gate: replays every {@link FluidScenarios} scenario on a
 * fresh {@link TickLoop} and compares the recorded snapshots (box cells per
 * tick, pending fluid-tick queue, lava random draws) line for line against
 * the committed vanilla oracle fixtures captured by {@link FluidOracle}.
 *
 * <p>Usage: {@code FluidParityCheck [FIXTURE_DIR] [scenario...]}. Without a
 * directory the gate looks for {@code harness/corpus/fluid-flow/oracle}
 * upward from the working directory, then upward from the class location.
 * Lava's random 4x spread delay is replayed from the fixture's {@code R}
 * lines, so the comparison is deterministic; a scenario that draws more or
 * fewer values than vanilla fails on the {@code R} line.
 *
 * <p>{@code -Dfluid.dump=DIR} writes Kestrel's own recordings there for
 * diffing. Exit 0 when every scenario matches exactly, 1 otherwise.
 */
public final class FluidParityCheck {
    private FluidParityCheck() {
    }

    static final String FIXTURE_REL = "harness/corpus/fluid-flow/oracle";

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        Path dir = null;
        List<String> only = new ArrayList<>();
        for (String a : args) {
            if (dir == null && Files.isDirectory(Path.of(a))) {
                dir = Path.of(a);
            } else {
                only.add(a);
            }
        }
        if (dir == null) {
            dir = locateFixtures();
        }
        if (dir == null) {
            System.out.println("FluidParityCheck FAIL: no fixture dir (" + FIXTURE_REL + ")");
            System.exit(1);
        }
        int pass = 0;
        int fail = 0;
        for (FluidScenarios.Scenario s : FluidScenarios.all()) {
            if (!only.isEmpty() && !only.contains(s.name())) {
                continue;
            }
            Path fixture = dir.resolve(s.name() + ".txt");
            if (!Files.exists(fixture)) {
                System.out.println("  " + s.name() + ": FAIL (no fixture " + fixture + ")");
                fail++;
                continue;
            }
            List<String> expected = Files.readAllLines(fixture);
            List<String> actual = replay(s, draws(expected));
            String dump = System.getProperty("fluid.dump");
            if (dump != null) {
                Files.createDirectories(Path.of(dump));
                Files.write(Path.of(dump, s.name() + ".txt"), actual);
            }
            String diff = firstDiff(expected, actual);
            if (diff == null) {
                System.out.println("  " + s.name() + ": PASS (" + actual.size() + " lines)");
                pass++;
            } else {
                System.out.println("  " + s.name() + ": FAIL " + diff);
                fail++;
            }
        }
        System.out.println("FluidParityCheck " + (fail == 0 ? "PASS" : "FAIL") + " " + pass + "/" + (pass + fail));
        System.exit(fail == 0 ? 0 : 1);
    }

    static Path locateFixtures() {
        List<Path> roots = new ArrayList<>();
        roots.add(Path.of("").toAbsolutePath());
        try {
            roots.add(Path.of(FluidParityCheck.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
        } catch (Exception ignored) {
            // no code source: working directory only
        }
        for (Path root : roots) {
            for (Path p = root; p != null; p = p.getParent()) {
                Path cand = p.resolve(FIXTURE_REL);
                if (Files.isDirectory(cand)) {
                    return cand;
                }
            }
        }
        return null;
    }

    /** Every lava draw in the fixture, in order. */
    static ArrayDeque<Integer> draws(List<String> fixture) {
        ArrayDeque<Integer> out = new ArrayDeque<>();
        for (String line : fixture) {
            if (line.startsWith("R ")) {
                for (String v : line.substring(2).trim().split(" ")) {
                    out.add(Integer.parseInt(v));
                }
            }
        }
        return out;
    }

    /** Run one scenario on a fresh loop, recording in the shared format. */
    static List<String> replay(FluidScenarios.Scenario s, ArrayDeque<Integer> draws) {
        KestrelLevel level = s.nether()
                ? new KestrelLevel(KestrelLevel.Dimension.NETHER, 0, 256)
                : new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
        TickLoop loop = new TickLoop(level);
        ReplayRandom random = new ReplayRandom(draws);
        loop.fluidFlow().setRandom(random);
        // Vanilla's world clock is well past 0 when the oracle runs; start
        // Kestrel at an arbitrary non-zero time too (only relative triggers
        // are compared).
        loop.setGameTime(1000);
        for (FluidScenarios.Cell c : s.setup()) {
            loop.setBlock(c.pos(), c.state(), FluidScenarios.RAW_FLAGS);
        }
        long base = loop.gameTime();
        FluidScenarios.Recorder rec = new FluidScenarios.Recorder(s);
        apply(loop, s, 0);
        rec.record(0, (x, y, z) -> loop.getBlockState(new BlockPos(x, y, z)), queue(loop, base), random.take());
        for (int t = 1; t <= s.ticks(); t++) {
            loop.tick();
            if (loop.gameTime() != base + t) {
                throw new IllegalStateException("clock drift: " + loop.gameTime() + " != " + (base + t));
            }
            rec.record(t, (x, y, z) -> loop.getBlockState(new BlockPos(x, y, z)), queue(loop, base), random.take());
            apply(loop, s, t);
        }
        return rec.lines();
    }

    private static void apply(TickLoop loop, FluidScenarios.Scenario s, int tick) {
        for (FluidScenarios.Action a : s.actions()) {
            if (a.tick() == tick) {
                loop.setBlock(a.pos(), a.state(), a.flags());
            }
        }
    }

    private static List<String> queue(TickLoop loop, long base) {
        List<String> out = new ArrayList<>();
        for (TickLoop.QueuedFluidTick q : loop.dumpFluidQueue()) {
            BlockPos p = q.pos().toVanilla();
            if (FluidScenarios.inBox(p)) {
                out.add(FluidScenarios.tickLine(p, q.fluid(), q.triggerTick() - base, q.priority()));
            }
        }
        return out;
    }

    /** First differing line with a little context, or null when identical. */
    static String firstDiff(List<String> expected, List<String> actual) {
        int n = Math.min(expected.size(), actual.size());
        for (int i = 0; i < n; i++) {
            if (!expected.get(i).equals(actual.get(i))) {
                return "at line " + (i + 1) + " (" + tickOf(expected, i) + ")\n      vanilla: " + expected.get(i)
                        + "\n      kestrel: " + actual.get(i);
            }
        }
        if (expected.size() != actual.size()) {
            return "length vanilla=" + expected.size() + " kestrel=" + actual.size()
                    + (n < expected.size() ? "\n      vanilla next: " + expected.get(n)
                    : "\n      kestrel next: " + actual.get(n));
        }
        return null;
    }

    private static String tickOf(List<String> lines, int idx) {
        for (int i = idx; i >= 0; i--) {
            if (lines.get(i).startsWith("T ")) {
                return lines.get(i);
            }
        }
        return "header";
    }

    /**
     * Level-random stand-in that hands lava the oracle's draws in order and
     * logs what it handed out (per tick, for the {@code R} line). Past the
     * recorded sequence it answers from a seeded source, which surfaces as
     * an {@code R} mismatch.
     */
    static final class ReplayRandom implements RandomSource {
        private final ArrayDeque<Integer> draws;
        private final RandomSource fallback = RandomSource.create(42L);
        private final List<Integer> taken = new ArrayList<>();

        ReplayRandom(ArrayDeque<Integer> draws) {
            this.draws = draws;
        }

        List<Integer> take() {
            List<Integer> out = List.copyOf(taken);
            taken.clear();
            return out;
        }

        @Override
        public int nextInt(int bound) {
            Integer v = draws.poll();
            int r = v != null ? v : fallback.nextInt(bound);
            taken.add(r);
            return r;
        }

        @Override
        public RandomSource fork() {
            return fallback.fork();
        }

        @Override
        public net.minecraft.world.level.levelgen.PositionalRandomFactory forkPositional() {
            return fallback.forkPositional();
        }

        @Override
        public void setSeed(long seed) {
        }

        @Override
        public int nextInt() {
            return fallback.nextInt();
        }

        @Override
        public long nextLong() {
            return fallback.nextLong();
        }

        @Override
        public boolean nextBoolean() {
            return fallback.nextBoolean();
        }

        @Override
        public float nextFloat() {
            return fallback.nextFloat();
        }

        @Override
        public double nextDouble() {
            return fallback.nextDouble();
        }

        @Override
        public double nextGaussian() {
            return fallback.nextGaussian();
        }
    }
}
