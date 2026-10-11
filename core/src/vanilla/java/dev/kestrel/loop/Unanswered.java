package dev.kestrel.loop;

/**
 * Vanilla block logic running on {@link RandomTickLevel} asked for something
 * Kestrel cannot answer yet (a sound, a spawned mob, a game event with a
 * listener in range, a chunk object). Thrown instead of returning a default,
 * so a missing mechanism is never a silent no-op. {@code owner} names the
 * roadmap item that will answer it.
 */
public final class Unanswered extends RuntimeException {
    private final String what;
    private final String owner;

    public Unanswered(String what, String owner) {
        super(what + " is not answered yet (" + owner + ")");
        this.what = what;
        this.owner = owner;
    }

    public String what() {
        return what;
    }

    public String owner() {
        return owner;
    }
}
