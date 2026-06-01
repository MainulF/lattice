package io.github.mainulf.lattice.ecs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Serial phase scheduler (Phase 1). Runs all registered systems in
 * (Phase ordinal, priority ASC, systemId ASC) order. Before each phase a snapshot
 * is taken; all systems in the phase read it. After all systems in the phase have
 * run their commits are applied in deterministic order: last writer wins per
 * (systemPriority ASC, systemId ASC, entityId ASC, componentType.name ASC).
 *
 * <p>Phase 3 will replace the inner loop with parallel fan-out over disjoint-access
 * systems while keeping the same commit-order contract.
 *
 * <p>Unresolved intra-phase conflicts among declared systems are detected here and
 * are a hard error — shipping an ambiguity would violate §3.4.
 */
public final class PhaseScheduler {

    private record Registration(
        String id,
        LatticeSystem system,
        Access access,
        Phase phase,
        int priority
    ) {}

    private final List<Registration> registrations = new ArrayList<>();
    private final ComponentStore store;

    public PhaseScheduler(ComponentStore store) {
        this.store = store;
    }

    /**
     * Register a system with an explicit id, access declaration, phase, and priority.
     * Systems registered with {@link Access#OPAQUE} run serial with full store access.
     */
    public void register(String id, LatticeSystem system, Access access, Phase phase, int priority) {
        registrations.add(new Registration(id, system, access, phase, priority));
    }

    /**
     * Convenience: reflect the {@link SystemDef} annotation and the public static
     * {@code ACCESS} field from the class and register accordingly.
     */
    public void register(String id, LatticeSystem system) {
        Class<?> cls = system.getClass();
        SystemDef def = cls.getAnnotation(SystemDef.class);
        if (def == null) {
            throw new IllegalArgumentException(cls.getName() + " is missing @SystemDef");
        }
        Access access;
        try {
            access = (Access) cls.getField("ACCESS").get(null);
        } catch (NoSuchFieldException e) {
            access = Access.OPAQUE;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read ACCESS field on " + cls.getName(), e);
        }
        register(id, system, access, def.phase(), def.priority());
    }

    /** Run one full tick: every phase in enum order, serial. */
    public void tick() {
        for (Phase phase : Phase.values()) {
            runPhase(phase);
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void runPhase(Phase phase) {
        List<Registration> inPhase = registrations.stream()
            .filter(r -> r.phase() == phase)
            .sorted(Comparator.comparingInt(Registration::priority)
                .thenComparing(Registration::id))
            .toList();

        if (inPhase.isEmpty()) return;

        validateNoAmbiguities(inPhase);

        ComponentStore snapshot = store.snapshot();

        List<CommitBuffer> buffers = new ArrayList<>(inPhase.size());
        for (Registration reg : inPhase) {
            CommitBuffer buf = new CommitBuffer(reg.id(), reg.priority(), reg.access());
            reg.system().run(new SnapshotView(snapshot, reg.access()), buf);
            buffers.add(buf);
        }

        applyCommits(buffers);
    }

    /**
     * Detect unresolved conflicts among DECLARED systems in the same phase.
     * Opaque systems are always serial — they may coexist in any phase freely.
     * A conflict between two declared systems is a hard error (§3.4): shipping
     * it would violate the determinism contract (the scheduler would have no safe
     * way to order the conflicting writes without an explicit phase boundary).
     */
    private void validateNoAmbiguities(List<Registration> inPhase) {
        for (int i = 0; i < inPhase.size(); i++) {
            for (int j = i + 1; j < inPhase.size(); j++) {
                Registration a = inPhase.get(i);
                Registration b = inPhase.get(j);
                if (a.access().isOpaque() || b.access().isOpaque()) continue;
                if (a.access().conflictsWith(b.access())) {
                    throw new IllegalStateException(
                        "Unresolved intra-phase conflict between systems '" + a.id() +
                        "' and '" + b.id() + "' in phase " + a.phase() +
                        ". Assign them to separate phases (§3.4).");
                }
            }
        }
    }

    /**
     * Apply all staged commits to the live store in the deterministic order (§1.2):
     *   1. Spawns (no ordering needed — new entities, no conflict possible)
     *   2. Sets/Removes: (systemPriority ASC, systemId ASC, entityId ASC, componentType.name ASC)
     *      — last in this order wins for same (entity, type) cell
     *   3. Kills — trump all writes (kill always wins)
     */
    private void applyCommits(List<CommitBuffer> buffers) {
        // 1. Spawns
        for (CommitBuffer buf : buffers) {
            for (CommitBuffer.SpawnOp op : buf.spawns()) {
                store.spawn(op.entity(), op.components());
            }
        }

        // 2a. Sets — collect, sort, apply last-writer-wins
        List<CommitBuffer.SetOp> allSets = new ArrayList<>();
        for (CommitBuffer buf : buffers) allSets.addAll(buf.sets());
        allSets.sort(Comparator
            .comparingInt(CommitBuffer.SetOp::systemPriority)
            .thenComparing(CommitBuffer.SetOp::systemId)
            .thenComparingLong(CommitBuffer.SetOp::entity)
            .thenComparing(op -> op.value().getClass().getName()));
        for (CommitBuffer.SetOp op : allSets) {
            store.set(op.entity(), op.value());
        }

        // 2b. Removes
        List<CommitBuffer.RemoveOp> allRemoves = new ArrayList<>();
        for (CommitBuffer buf : buffers) allRemoves.addAll(buf.removes());
        allRemoves.sort(Comparator
            .comparingInt(CommitBuffer.RemoveOp::systemPriority)
            .thenComparing(CommitBuffer.RemoveOp::systemId)
            .thenComparingLong(CommitBuffer.RemoveOp::entity)
            .thenComparing(op -> op.type().getName()));
        for (CommitBuffer.RemoveOp op : allRemoves) {
            store.remove(op.entity(), op.type());
        }

        // 3. Kills — last; kill trumps writes
        List<CommitBuffer.KillOp> allKills = new ArrayList<>();
        for (CommitBuffer buf : buffers) allKills.addAll(buf.kills());
        allKills.sort(Comparator
            .comparingInt(CommitBuffer.KillOp::systemPriority)
            .thenComparing(CommitBuffer.KillOp::systemId)
            .thenComparingLong(CommitBuffer.KillOp::entity));
        for (CommitBuffer.KillOp op : allKills) {
            store.kill(op.entity());
        }
    }
}
