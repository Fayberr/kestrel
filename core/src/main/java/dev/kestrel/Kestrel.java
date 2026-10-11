package dev.kestrel;

/**
 * Placeholder entry point. No server logic yet - see core/README.md and
 * preresearch/collision-path-analysis.md for what lands here first
 * (optimization #1: entity AABB lookup + collision/push path) and why
 * nothing ships until it is proven bit-identical to vanilla via
 * harness/'s real per-tick world-state hash.
 */
public final class Kestrel {
    private Kestrel() {
    }

    public static void main(String[] args) {
        System.out.println("kestrel-core: scaffold only, no server logic yet.");
    }
}
