package io.github.mainulf.lattice.ecs;

import java.util.List;

/**
 * Read handle backed by the frozen pre-phase snapshot. A system can only observe
 * components declared in its Access.reads. Any access outside the declaration is
 * a dev-mode hard error (§3.2). Cross-region data is not reachable from this handle.
 */
public interface View {

    /** Returns the component, or null if the entity does not have it. */
    <C extends Component> C get(long entity, Class<C> type);

    boolean has(long entity, Class<? extends Component> type);

    /**
     * Returns entity IDs (sorted ascending) that have ALL of the given component types.
     * Iteration order is stable across runs — sorted by entity ID.
     */
    long[] query(Class<? extends Component>... types);

    /**
     * The deterministic per-system RNG stream (§1.4). Derived from
     * {@code (worldSeed, tick, systemId)} — never shared, never affected by thread order.
     * Use {@link LatticeRng#forEntity(long)} to obtain a per-entity child stream.
     */
    LatticeRng rng();

    /**
     * Messages delivered to this region before the current tick began (§1.6).
     * Payloads from {@code commit.message(thisRegionId, payload)} staged in the
     * previous tick, routed here by the {@link RegionCoordinator}.
     * The list is immutable for the duration of the tick.
     * Returns an empty list if no messages were delivered (default for schedulers
     * not managed by a coordinator).
     */
    default List<Object> inbox() { return List.of(); }
}
