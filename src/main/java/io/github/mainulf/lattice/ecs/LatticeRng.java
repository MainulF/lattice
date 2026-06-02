package io.github.mainulf.lattice.ecs;

import java.util.SplittableRandom;

/**
 * Deterministic per-system RNG (§1.4 / §2.6).
 *
 * <p>The scheduler derives one {@code LatticeRng} per system execution from
 * {@code (worldSeed, tick, systemId)} — a pure function of stable IDs, never of thread
 * arrival order. Systems that run in parallel therefore draw from independent,
 * reproducible streams; execution order cannot change any stream's sequence.
 *
 * <p>Per-entity child streams: {@link #forEntity(long)} derives a child {@code LatticeRng}
 * from the per-system root, keyed by entity ID, without consuming the root's state.
 * Systems that iterate {@link View#query} (which returns IDs in sorted ascending order)
 * always encounter the same entity sequence, making per-entity draws reproducible.
 *
 * <p>Dev-mode detector: {@link #IN_SYSTEM_THREAD} is set by the scheduler while any
 * system is executing. Call {@link #assertNotInSystemThread()} at the entry of any
 * shared-state generator access (e.g. the vanilla {@code Level.random}) to surface
 * violations as hard errors — the C2ME / UWRAD pattern (§2.6).
 */
public final class LatticeRng {

    /** Set to {@code true} by the scheduler while a system body is executing on this thread. */
    static final ThreadLocal<Boolean> IN_SYSTEM_THREAD = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static final long TICK_MIX      = 0x517cc1b727220a95L;
    private static final long SYSTEM_MIX    = 0x9e3779b97f4a7c15L;
    private static final long ENTITY_MIX    = 0x6c62272e07bb0142L;

    private final long fixedSeed;
    private final SplittableRandom root;

    private LatticeRng(long seed) {
        this.fixedSeed = seed;
        this.root      = new SplittableRandom(seed);
    }

    /**
     * Derive the per-system RNG from {@code (worldSeed, tick, systemId)}.
     * Called by the scheduler once per system execution.
     */
    static LatticeRng forSystem(long worldSeed, long tick, String systemId) {
        long seed = worldSeed
            ^ (tick    * TICK_MIX)
            ^ ((long) systemId.hashCode() * SYSTEM_MIX);
        return new LatticeRng(seed);
    }

    /**
     * Derive a child stream keyed to {@code entityId}. Pure function of this stream's
     * seed and the entity ID — order-independent, safe to call multiple times for
     * the same entity to obtain the same child stream.
     *
     * <p>Note: {@code + ENTITY_MIX} ensures entity 0 yields a seed distinct from the root
     * (otherwise {@code 0 * ENTITY_MIX == 0} would leave the XOR unchanged).
     */
    public LatticeRng forEntity(long entityId) {
        return new LatticeRng(fixedSeed ^ (entityId * ENTITY_MIX + ENTITY_MIX));
    }

    /** The underlying {@link SplittableRandom} for direct draw calls. */
    public SplittableRandom random() {
        return root;
    }

    /**
     * Assert that the current thread is not inside a Lattice system execution.
     * Wire this into any access to a shared mutable generator to surface violations early.
     * No-op in production builds where the flag is never set.
     */
    public static void assertNotInSystemThread() {
        if (Boolean.TRUE.equals(IN_SYSTEM_THREAD.get())) {
            throw new IllegalStateException(
                "Shared RNG accessed from a Lattice system thread. " +
                "Use view.rng() for a deterministic per-system stream (§1.4).");
        }
    }

    /** Returns {@code true} while executing inside a Lattice system on this thread. */
    public static boolean isInSystemThread() {
        return Boolean.TRUE.equals(IN_SYSTEM_THREAD.get());
    }
}
