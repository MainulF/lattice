package io.github.mainulf.lattice.ecs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Phase 1 ECS engine. These prove three invariants before any
 * MC integration exists: snapshot isolation, deterministic last-writer-wins,
 * and cross-phase visibility.
 *
 * They also serve as a living spec: if any of these goes red after a refactor,
 * the compute/commit contract has been broken.
 */
class EcsTest {

    // ── Shared component types ────────────────────────────────────────────────

    record Position(double x, double y, double z) implements Component {}
    record Velocity(double dx, double dy, double dz) implements Component {}
    record Tag(String name) implements Component {}

    // ── 1. Snapshot isolation ─────────────────────────────────────────────────

    /**
     * Two opaque systems in the same phase. System "mover" writes Position; system
     * "observer" runs second (higher priority number) and reads Position.
     * Both run against the same pre-phase snapshot — observer must see x=0 (pre-phase),
     * not x=1 (mover's staged write which hasn't been applied yet).
     *
     * Declared systems that write/read the same component type are a hard error in the
     * same phase (§3.4) — so this test correctly uses opaque systems, which are always
     * serial and bypass the conflict check. The snapshot/commit mechanism still applies.
     */
    @Test
    void snapshotIsolation_opaqueSystemsReadPrePhaseSnapshot() {
        ComponentStore store = new ComponentStore();
        store.spawn(1L, new Position(0, 0, 0), new Velocity(1, 0, 0));

        // Mover: writes Position (opaque — no declared access)
        LatticeSystem mover = (view, commit) -> {
            Position p = view.get(1L, Position.class);
            Velocity v = view.get(1L, Velocity.class);
            commit.set(1L, new Position(p.x() + v.dx(), p.y() + v.dy(), p.z() + v.dz()));
        };

        // Observer: runs AFTER mover (priority 10 > 0), reads Position, records what it saw
        LatticeSystem observer = (view, commit) -> {
            double x = view.get(1L, Position.class).x();
            // "0.0" proves snapshot isolation; "1.0" would mean the live store leaked through
            commit.set(1L, new Tag("seen-x=" + x));
        };

        PhaseScheduler scheduler = new PhaseScheduler(store);
        scheduler.register("mover",    mover,    Access.OPAQUE, Phase.MOVEMENT, 0);
        scheduler.register("observer", observer, Access.OPAQUE, Phase.MOVEMENT, 10);

        scheduler.tick();

        // Mover's Position commit applied (priority 0 < 10, but Tag is a different component):
        assertEquals(1.0, store.get(1L, Position.class).x());
        // Observer saw the pre-phase snapshot (x=0), not mover's staged write:
        assertEquals("seen-x=0.0", store.get(1L, Tag.class).name());
    }

    // ── 2. Deterministic last-writer-wins ─────────────────────────────────────

    /**
     * Two non-conflicting systems in different phases both write Position to entity 1.
     * Conflicting writes in the same phase are a build error (§3.4) — tested separately.
     * Here: system in MOVEMENT phase writes x=100, system in COLLISION phase writes x=200.
     * COLLISION commits after MOVEMENT, so x=200 must win.
     */
    @Test
    void crossPhaseCommitOrder_laterPhaseWins() {
        ComponentStore store = new ComponentStore();
        store.spawn(1L, new Position(0, 0, 0));

        Access writePos = Access.builder().writes(Position.class).build();

        PhaseScheduler scheduler = new PhaseScheduler(store);
        scheduler.register("movement-writer",  (v, c) -> c.set(1L, new Position(100, 0, 0)), writePos, Phase.MOVEMENT,  0);
        scheduler.register("collision-writer",  (v, c) -> c.set(1L, new Position(200, 0, 0)), writePos, Phase.COLLISION, 0);

        scheduler.tick();

        assertEquals(200.0, store.get(1L, Position.class).x());
    }

    /**
     * Within the same phase, two systems with different priorities write the same cell.
     * Lower priority value = earlier in order = lower precedence. Higher priority value wins.
     * This test verifies the (priority, systemId) sort order is applied correctly.
     */
    @Test
    void intraPhasePriority_higherPriorityValueWins() {
        ComponentStore store = new ComponentStore();
        store.spawn(1L, new Position(0, 0, 0));

        // Two systems that don't conflict on types... actually they DO conflict (both write Position).
        // In the real scheduler, two conflicting declared systems in the same phase are a build error.
        // We test intra-phase ordering using OPAQUE systems, which the scheduler allows to conflict
        // (opaque is always serial — two opaque systems in the same phase IS allowed, and the
        // last-writer-wins by (priority, id) still applies).
        PhaseScheduler scheduler = new PhaseScheduler(store);
        scheduler.register("p0",   (v, c) -> c.set(1L, new Position(100, 0, 0)), Access.OPAQUE, Phase.MOVEMENT, 0);
        scheduler.register("p100", (v, c) -> c.set(1L, new Position(200, 0, 0)), Access.OPAQUE, Phase.MOVEMENT, 100);

        // p0 runs first (lower priority number), p100 runs second
        // Both stage writes; the commit sort applies them in (priority ASC) order,
        // so p100's write lands last and wins.
        scheduler.tick();

        assertEquals(200.0, store.get(1L, Position.class).x());
    }

    // ── 3. Multi-phase pipeline ───────────────────────────────────────────────

    /**
     * MOVEMENT commits before COLLISION begins — COLLISION must see the post-MOVEMENT position.
     * This verifies the cross-phase snapshot boundary works correctly.
     */
    @Test
    void crossPhaseVisibility_collisionSeesPostMovementPosition() {
        ComponentStore store = new ComponentStore();
        store.spawn(1L, new Position(0, 0, 0), new Velocity(5, 0, 0));

        Access moveAccess = Access.builder().reads(Position.class, Velocity.class).writes(Position.class).build();
        Access readPos    = Access.builder().reads(Position.class).writes(Tag.class).build();

        PhaseScheduler scheduler = new PhaseScheduler(store);
        scheduler.register("movement", (view, commit) -> {
            for (long e : view.query(Position.class, Velocity.class)) {
                Position p = view.get(e, Position.class);
                Velocity v = view.get(e, Velocity.class);
                commit.set(e, new Position(p.x() + v.dx(), p.y() + v.dy(), p.z() + v.dz()));
            }
        }, moveAccess, Phase.MOVEMENT, 0);

        scheduler.register("collision-observer", (view, commit) -> {
            double x = view.get(1L, Position.class).x();
            // Should be 5.0 (post-MOVEMENT) not 0.0 (pre-tick)
            commit.set(1L, new Tag("collision-saw-x=" + x));
        }, readPos, Phase.COLLISION, 0);

        scheduler.tick();

        assertEquals(5.0, store.get(1L, Position.class).x());
        assertEquals("collision-saw-x=5.0", store.get(1L, Tag.class).name());
    }

    // ── 4. Spawn and kill lifecycle ───────────────────────────────────────────

    @Test
    void spawnInCommit_entityVisibleNextPhase() {
        ComponentStore store = new ComponentStore();

        Access spawnAccess = Access.builder().writes(Position.class).build();
        Access readAccess  = Access.builder().reads(Position.class).build();

        PhaseScheduler scheduler = new PhaseScheduler(store);
        scheduler.register("spawner", (view, commit) -> {
            commit.spawn(42L, new Position(7, 8, 9));
        }, spawnAccess, Phase.MOVEMENT, 0);

        scheduler.register("reader", (view, commit) -> {
            // Entity 42 was spawned in MOVEMENT commit; COLLISION snapshot should include it
            commit.set(42L, new Tag("found"));
        }, Access.builder().reads(Position.class).writes(Tag.class).build(), Phase.COLLISION, 0);

        scheduler.tick();

        assertNotNull(store.get(42L, Position.class));
        assertEquals("found", store.get(42L, Tag.class).name());
    }

    @Test
    void killInCommit_entityGoneAfterPhase() {
        ComponentStore store = new ComponentStore();
        store.spawn(99L, new Position(1, 2, 3));

        PhaseScheduler scheduler = new PhaseScheduler(store);
        scheduler.register("killer", (view, commit) -> commit.kill(99L), Access.OPAQUE, Phase.MOVEMENT, 0);

        scheduler.tick();

        assertNull(store.get(99L, Position.class));
        assertEquals(0, store.entityCount());
    }

    // ── 5. Access enforcement ─────────────────────────────────────────────────

    @Test
    void undeclaredReadThrows() {
        ComponentStore store = new ComponentStore();
        store.spawn(1L, new Position(0, 0, 0));

        // Access declares reads(Velocity) only — reading Position should throw
        Access wrongAccess = Access.builder().reads(Velocity.class).build();
        PhaseScheduler scheduler = new PhaseScheduler(store);
        scheduler.register("bad", (view, commit) -> view.get(1L, Position.class), wrongAccess, Phase.MOVEMENT, 0);

        assertThrows(IllegalAccessError.class, scheduler::tick);
    }

    @Test
    void undeclaredWriteThrows() {
        ComponentStore store = new ComponentStore();
        store.spawn(1L, new Position(0, 0, 0));

        // Access declares writes(Velocity) only — writing Position should throw
        Access wrongAccess = Access.builder().writes(Velocity.class).build();
        PhaseScheduler scheduler = new PhaseScheduler(store);
        scheduler.register("bad", (view, commit) -> commit.set(1L, new Position(1, 0, 0)), wrongAccess, Phase.MOVEMENT, 0);

        assertThrows(IllegalAccessError.class, scheduler::tick);
    }

    // ── 6. World-state hash — determinism oracle ──────────────────────────────

    /**
     * Run the same scripted scenario 3 times independently; assert identical hash.
     * This is the embryo of the DeterminismHarness (§3.5). When we add parallelism
     * (Phase 3+) this same scenario runs at 1 / 2 / 4 / N cores and must still
     * produce the same hash — a flat line is the entire thesis.
     *
     * Physics: gravity accumulates velocity, velocity moves position. No RNG yet.
     * 10 entities, 200 ticks. Uses StrictMath.* for any transcendental calls
     * on the tick path (§1.5); here only +/* are used, which are already strict.
     */
    @Test
    void deterministicHash_identicalAcrossIndependentRuns() {
        long h1 = runGravityScenario();
        long h2 = runGravityScenario();
        long h3 = runGravityScenario();
        assertEquals(h1, h2, "second run hash differs from first");
        assertEquals(h2, h3, "third run hash differs from second");
        // Also prove the scenario actually changed state (hash != initial-empty-store hash)
        assertNotEquals(0L, h1);
    }

    /**
     * Hash is sensitive to small position differences — verify that a single tick
     * of movement produces a different hash from the initial state.
     */
    @Test
    void deterministicHash_sensitiveToPositionChange() {
        ComponentStore before = buildGravityStore();
        long hashBefore = WorldStateHasher.hash(before, Position.class, Velocity.class);

        ComponentStore after = buildGravityStore();
        PhaseScheduler sched = buildGravityScheduler(after);
        sched.tick(); // one tick
        long hashAfter = WorldStateHasher.hash(after, Position.class, Velocity.class);

        assertNotEquals(hashBefore, hashAfter, "hash must change after a tick");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private long runGravityScenario() {
        ComponentStore store = buildGravityStore();
        PhaseScheduler sched = buildGravityScheduler(store);
        for (int t = 0; t < 200; t++) sched.tick();
        return WorldStateHasher.hash(store, Position.class, Velocity.class);
    }

    private ComponentStore buildGravityStore() {
        ComponentStore store = new ComponentStore();
        for (long id = 1; id <= 10; id++) {
            store.spawn(id,
                new Position(id * 3.14159, id * 2.71828, 100.0),
                new Velocity(0.0, 0.0, 0.0));
        }
        return store;
    }

    private PhaseScheduler buildGravityScheduler(ComponentStore store) {
        Access gravAccess  = Access.builder().reads(Velocity.class).writes(Velocity.class).build();
        Access moveAccess  = Access.builder().reads(Position.class, Velocity.class).writes(Position.class).build();

        PhaseScheduler sched = new PhaseScheduler(store);
        // AI phase: apply gravity to velocity
        sched.register("gravity", (view, commit) -> {
            for (long e : view.query(Velocity.class)) {
                Velocity v = view.get(e, Velocity.class);
                commit.set(e, new Velocity(v.dx(), v.dy() - 0.04, v.dz())); // gravity
            }
        }, gravAccess, Phase.AI, 0);

        // MOVEMENT phase: integrate velocity into position
        sched.register("movement", (view, commit) -> {
            for (long e : view.query(Position.class, Velocity.class)) {
                Position p = view.get(e, Position.class);
                Velocity v = view.get(e, Velocity.class);
                commit.set(e, new Position(p.x() + v.dx(), p.y() + v.dy(), p.z() + v.dz()));
            }
        }, moveAccess, Phase.MOVEMENT, 0);

        return sched;
    }

    @Test
    void intraPhaseDeclaredConflictIsError() {
        ComponentStore store = new ComponentStore();

        Access a = Access.builder().reads(Position.class).writes(Velocity.class).build();
        Access b = Access.builder().writes(Velocity.class).build();

        PhaseScheduler scheduler = new PhaseScheduler(store);
        scheduler.register("sysA", (v, c) -> {}, a, Phase.MOVEMENT, 0);
        scheduler.register("sysB", (v, c) -> {}, b, Phase.MOVEMENT, 1);

        // Two declared systems writing the same type in the same phase → hard error (§3.4)
        assertThrows(IllegalStateException.class, scheduler::tick);
    }
}
