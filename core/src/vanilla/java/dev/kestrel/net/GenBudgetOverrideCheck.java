package dev.kestrel.net;

import dev.kestrel.loop.KestrelLevel;
import dev.kestrel.loop.TickLoop;
import dev.kestrel.worldgen.HeadlessNoise;
import dev.kestrel.worldgen.LiveGenerator;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/**
 * Regression gate for {@code --gen-budget-ms} (dev/test convenience,
 * see {@link ChunkSender#DEFAULT_GEN_BUDGET_NANOS}). Proves two things:
 * the default budget still throttles a solo fresh-chunk generation the
 * same way it did before this override existed, and
 * {@link ChunkSender#setGenBudgetNanos} actually changes the live gating
 * decision {@link ChunkSender#genBudgetLeft()} makes, not just a stored
 * number nothing reads.
 *
 * <p>Run (Pi, JDK 25, after {@code ./gradlew build -PvanillaJar=... -PvanillaLibs=...}):
 * <pre>
 * java -cp build/classes/java/main:VANILLA_JAR:VANILLA_LIBS/* \
 *     dev.kestrel.net.GenBudgetOverrideCheck
 * </pre>
 */
public final class GenBudgetOverrideCheck {
    private GenBudgetOverrideCheck() {
    }

    private static final long SEED = 4242424242L;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
        TickLoop loop = new TickLoop(level);
        HeadlessNoise.Driver driver = HeadlessNoise.load(SEED);
        LiveGenerator gen = new LiveGenerator(driver);
        ChunkSender sender = new ChunkSender(loop, 1);
        sender.setGenerator(gen);

        boolean ok = true;

        ok &= expect(sender.genBudgetLeft(), "budget starts open before any generation this tick");

        // One real fresh-chunk generation call (~231ms on the Pi per the
        // ChunkSender comment), which alone exceeds the default 40ms
        // budget. This is the exact throttling behavior the default has
        // always had; this check only proves it survived the refactor
        // from a constant to a defaulted instance field.
        sender.sectionBytes(9, 9);
        ok &= expect(!sender.genBudgetLeft(),
                "default budget (40ms) is exhausted after one real fresh-chunk generation, same tick");

        // Raising the override must change the live gating decision in
        // the SAME tick (loop.gameTime() has not advanced), proving
        // setGenBudgetNanos is actually wired into genBudgetLeft() and
        // not just stored and ignored.
        sender.setGenBudgetNanos(10_000_000_000L); // 10s, far above one chunk's cost
        ok &= expect(sender.genBudgetLeft(),
                "raising the override reopens the budget in the same tick");

        // Lowering it back to something smaller than what was already
        // spent this tick must close it again.
        sender.setGenBudgetNanos(1L);
        ok &= expect(!sender.genBudgetLeft(),
                "lowering the override below nanos already spent this tick closes the budget");

        System.out.println(ok ? "[gen-budget] PASS" : "[gen-budget] FAIL");
        if (!ok) {
            System.exit(1);
        }
    }

    private static boolean expect(boolean cond, String what) {
        System.out.println((cond ? "[gen-budget] OK: " : "[gen-budget] FAIL: ") + what);
        return cond;
    }
}
