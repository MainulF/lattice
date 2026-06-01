package io.github.mainulf.lattice.ecs;

/**
 * Write handle. All writes are staged and applied after the phase completes,
 * in deterministic order (§1.2). No system observes another system's same-phase
 * writes through this interface. Cross-region writes use commit.message() (Phase 4).
 */
public interface Commit {

    /** Stage a component write. Applied at phase-end in (priority, systemId, entityId, type) order. */
    <C extends Component> void set(long entity, C component);

    /** Stage a component removal. */
    void remove(long entity, Class<? extends Component> type);

    /** Stage entity creation. Applied before sets/removes/kills in the same phase. */
    void spawn(long entity, Component... initialComponents);

    /** Stage entity destruction. Applied last; kill trumps concurrent writes. */
    void kill(long entity);
}
