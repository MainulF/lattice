package io.github.mainulf.lattice.ecs;

/**
 * View backed by a precomputed entity chunk — a contiguous slice of the phase's sorted
 * matching-entity array, hoisted to the caller thread before the parallel fan-out.
 *
 * <p>Why hoisted: computing the type-intersection on each worker thread would pay a
 * binary-search-per-entity-per-type cost inside the hot parallel path. Hoisting it once
 * on the caller thread makes {@link #query} O(1) (just return the precomputed slice).
 *
 * <p>Correctness invariant: chunk entities were precomputed via
 * {@code store.query(access.reads ∪ access.writes)}, so they satisfy every legal
 * query type combination (access enforcement guarantees all query types are a subset
 * of the declared access). No secondary per-entity filtering is needed.
 *
 * <p>Thread safety: the live store is frozen during compute — all writes go to
 * {@link CommitBuffer}s and {@code applyCommits} runs only after the join, on the
 * caller thread. Concurrent reads from multiple {@code ChunkedView}s are therefore
 * safe as long as no structural modifications occur on any column (§1.2).
 *
 * <p>RNG caveat: raw {@code view.rng().random()} draws are unsafe across chunks
 * (all chunks of the same system share a root {@link LatticeRng} seed). Use
 * {@link LatticeRng#forEntity(long)} — a pure function of seed + entity ID — for
 * per-entity randomness. That is always safe regardless of chunk assignment.
 */
final class ChunkedView implements View {

    private final ComponentStore store;
    private final Access         access;
    private final LatticeRng     rng;
    /** Precomputed, sorted subset of this phase's entity set for this chunk. */
    private final long[]         chunkIds;

    ChunkedView(ComponentStore store, Access access, LatticeRng rng, long[] chunkIds) {
        this.store    = store;
        this.access   = access;
        this.rng      = rng;
        this.chunkIds = chunkIds;
    }

    @Override
    public <C extends Component> C get(long entity, Class<C> type) {
        checkRead(type);
        return store.get(entity, type);
    }

    @Override
    public boolean has(long entity, Class<? extends Component> type) {
        checkRead(type);
        return store.has(entity, type);
    }

    /**
     * Returns the precomputed chunk entity IDs. All entities are guaranteed to have
     * every type reachable through the declared access, so any legal query is satisfied.
     */
    @Override
    @SafeVarargs
    public final long[] query(Class<? extends Component>... types) {
        for (Class<? extends Component> t : types) checkRead(t);
        return chunkIds.clone();
    }

    @Override
    public LatticeRng rng() {
        return rng;
    }

    private void checkRead(Class<? extends Component> type) {
        if (!access.isOpaque() && !access.reads().contains(type) && !access.writes().contains(type)) {
            throw new IllegalAccessError(
                "System did not declare read access to " + type.getSimpleName() +
                " — add it to Access.builder().reads(" + type.getSimpleName() + ".class)");
        }
    }
}
