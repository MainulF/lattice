package io.github.mainulf.lattice.ecs;

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
}
