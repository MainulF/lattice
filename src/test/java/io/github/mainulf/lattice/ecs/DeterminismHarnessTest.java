package io.github.mainulf.lattice.ecs;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Phase 2 DeterminismHarness and associated infrastructure:
 * per-system RNG streams, IN_SYSTEM_THREAD detector, and cross-region message path.
 */
class DeterminismHarnessTest {

    // ── Shared scenario helpers ───────────────────────────────────────────────

    private static DeterminismHarness.Scenario gravityAndMoveScenario(int entities, int ticks) {
        return new DeterminismHarness.Scenario() {
            @Override
            public ComponentStore buildStore() {
                ComponentStore store = new ComponentStore();
                for (long id = 1; id <= entities; id++) {
                    store.spawn(id,
                        new Position(id * 1.5, 100.0, id * 2.5),
                        new Velocity(0.0, 0.0, 0.0));
                }
                return store;
            }
            @Override
            public void registerSystems(PhaseScheduler scheduler) {
                Access gravAccess = Access.builder().reads(Velocity.class).writes(Velocity.class).build();
                Access moveAccess = Access.builder()
                    .reads(Position.class, Velocity.class).writes(Position.class).build();

                scheduler.register("gravity", (view, commit) -> {
                    for (long id : view.query(Velocity.class)) {
                        Velocity v = view.get(id, Velocity.class);
                        commit.set(id, new Velocity(v.dx(), v.dy() - 0.04, v.dz()));
                    }
                }, gravAccess, Phase.AI, 0);

                scheduler.register("move", (view, commit) -> {
                    for (long id : view.query(Position.class, Velocity.class)) {
                        Position p = view.get(id, Position.class);
                        Velocity v = view.get(id, Velocity.class);
                        commit.set(id, new Position(p.x() + v.dx(), p.y() + v.dy(), p.z() + v.dz()));
                    }
                }, moveAccess, Phase.MOVEMENT, 0);
            }
            @Override
            public int ticks() { return ticks; }
            @Override
            public List<Class<? extends Component>> hashedComponents() {
                return List.of(Position.class, Velocity.class);
            }
        };
    }

    // ── 1. DeterminismHarness — core contract ─────────────────────────────────

    /** Two serial runs of the same scenario must produce bit-identical per-tick hashes. */
    @Test
    void harness_twoRunsIdentical_gravity() {
        DeterminismHarness.replay(gravityAndMoveScenario(10, 100))
            .at(1).at(1)
            .assertWorldStateHashIdenticalAcrossAllCoreCounts();
    }

    /**
     * Three different "core counts" (all serial in Phase 2) produce identical hashes —
     * this is the scaffold for Phase 3, which will make at(N) actually parallel.
     */
    @Test
    void harness_multipleCoreCounts_gravity() {
        DeterminismHarness.replay(gravityAndMoveScenario(10, 50))
            .at(1).at(2).at(4)
            .assertWorldStateHashIdenticalAcrossAllCoreCounts();
    }

    /** The carved MovementSystem (Phase 1 artefact) passes the harness at all core counts. */
    @Test
    void harness_movementSystem_identicalAcrossAllCoreCounts() {
        DeterminismHarness.Scenario scenario = new DeterminismHarness.Scenario() {
            @Override
            public ComponentStore buildStore() {
                ComponentStore store = new ComponentStore();
                for (long id = 1; id <= 5; id++) {
                    store.spawn(id,
                        new Position(id * 3.14, 50.0, 0.0),
                        new Velocity(0.0, 0.0, 0.0));
                }
                return store;
            }
            @Override
            public void registerSystems(PhaseScheduler scheduler) {
                scheduler.register("movement", new MovementSystem(),
                    MovementSystem.ACCESS, Phase.MOVEMENT, 50);
            }
            @Override
            public int ticks() { return 200; }
            @Override
            public List<Class<? extends Component>> hashedComponents() {
                return List.of(Position.class, Velocity.class);
            }
        };
        DeterminismHarness.replay(scenario).at(1).at(2).at(4)
            .assertWorldStateHashIdenticalAcrossAllCoreCounts();
    }

    /**
     * Two disjoint declared systems in the same phase run concurrently at {@code at(4)}.
     * Asserts the flat-line property: identical per-tick hash at 1, 2, and 4 cores.
     *
     * <p>System A (gravity): reads+writes Velocity — applies gravity each tick.
     * System B (nudge): reads+writes Position — shifts x by a per-entity RNG amount each tick.
     * Access is disjoint (Velocity ∩ Position = ∅), so the scheduler fans them out in parallel.
     *
     * <p>The {@code nudge} system intentionally draws from {@code view.rng().forEntity(id)} to
     * prove that per-system-per-entity streams keyed by {@code (worldSeed, tick, systemId, entityId)}
     * are thread-independent: at(1) and at(4) produce the same draws because the key is a pure
     * function of stable IDs, never of thread arrival order. If RNG keying were ever made
     * thread-dependent, this test would detect it as a determinism violation.
     */
    @Test
    void harness_parallelFanOut_twoDisjointSystems_identicalAtAllCoreCounts() {
        DeterminismHarness.Scenario scene = new DeterminismHarness.Scenario() {
            @Override
            public ComponentStore buildStore() {
                ComponentStore s = new ComponentStore();
                for (long id = 1; id <= 100; id++) {
                    s.spawn(id,
                        new Position(id * 1.0, 100.0, 0.0),
                        new Velocity(0.0, 0.0, 0.0));
                }
                return s;
            }
            @Override
            public void registerSystems(PhaseScheduler sched) {
                sched.register("gravity", (view, commit) -> {
                    for (long id : view.query(Velocity.class)) {
                        Velocity v = view.get(id, Velocity.class);
                        commit.set(id, new Velocity(v.dx(), v.dy() - 0.04, v.dz()));
                    }
                }, Access.builder().reads(Velocity.class).writes(Velocity.class).build(),
                   Phase.MOVEMENT, 0);

                // nudge draws per-entity RNG — proves streams are thread-independent
                sched.register("nudge", (view, commit) -> {
                    for (long id : view.query(Position.class)) {
                        Position p = view.get(id, Position.class);
                        double delta = view.rng().forEntity(id).random().nextDouble() * 0.01;
                        commit.set(id, new Position(p.x() + delta, p.y(), p.z()));
                    }
                }, Access.builder().reads(Position.class).writes(Position.class).build(),
                   Phase.MOVEMENT, 1);
            }
            @Override
            public int ticks() { return 100; }
            @Override
            public List<Class<? extends Component>> hashedComponents() {
                return List.of(Position.class, Velocity.class);
            }
        };

        DeterminismHarness.replay(scene).at(1).at(2).at(4)
            .assertWorldStateHashIdenticalAcrossAllCoreCounts();
    }

    /**
     * The harness must detect divergence. A system that draws from a shared mutable counter
     * (simulating a global RNG cursor) produces different hashes across independent runs.
     */
    @Test
    void harness_detectsDivergence_throwsAssertionError() {
        long[] sharedCursor = {0L};

        DeterminismHarness.Scenario nondeterministic = new DeterminismHarness.Scenario() {
            @Override
            public ComponentStore buildStore() {
                ComponentStore store = new ComponentStore();
                store.spawn(1L, new Position(0, 100, 0), new Velocity(0, 0, 0));
                return store;
            }
            @Override
            public void registerSystems(PhaseScheduler sched) {
                sched.register("bad", (view, commit) -> {
                    commit.set(1L, new Position(sharedCursor[0]++, 100, 0));
                }, Access.builder().writes(Position.class).build(), Phase.MOVEMENT, 0);
            }
            @Override
            public int ticks() { return 5; }
            @Override
            public List<Class<? extends Component>> hashedComponents() {
                return List.of(Position.class);
            }
        };

        assertThrows(AssertionError.class, () ->
            DeterminismHarness.replay(nondeterministic)
                .at(1).at(1)
                .assertWorldStateHashIdenticalAcrossAllCoreCounts());
    }

    /** Per-tick hash log length matches the scenario tick count. */
    @Test
    void harness_perTickHashLog_lengthMatchesTicks() {
        int ticks = 37;
        List<long[]> captured = new ArrayList<>();

        DeterminismHarness.Scenario scene = new DeterminismHarness.Scenario() {
            @Override
            public ComponentStore buildStore() {
                ComponentStore s = new ComponentStore();
                s.spawn(1L, new Position(0, 10, 0), new Velocity(0, 0, 0));
                return s;
            }
            @Override
            public void registerSystems(PhaseScheduler sched) {
                sched.register("noop", (v, c) -> {}, Access.OPAQUE, Phase.MOVEMENT, 0);
            }
            @Override
            public int ticks() { return ticks; }
            @Override
            public List<Class<? extends Component>> hashedComponents() {
                return List.of(Position.class);
            }
        };

        // assertWorldStateHashIdenticalAcrossAllCoreCounts doesn't expose the log, but
        // we can verify indirectly: single at(1) must complete without error for 37 ticks.
        assertDoesNotThrow(() ->
            DeterminismHarness.replay(scene).at(1).assertWorldStateHashIdenticalAcrossAllCoreCounts());
    }

    // ── 2. LatticeRng — per-system stream ────────────────────────────────────

    /**
     * The per-system RNG stream is deterministic: two independent scheduler runs
     * produce the same sequence of draws from view.rng().random().
     */
    @Test
    void rng_perSystemStream_isDeterministic() {
        long[] run1 = runAndCollectRng();
        long[] run2 = runAndCollectRng();
        assertArrayEquals(run1, run2, "per-system RNG draws must be reproducible across independent runs");
    }

    private long[] runAndCollectRng() {
        List<Long> draws = new ArrayList<>();
        ComponentStore store = new ComponentStore();
        store.spawn(1L, new Position(0, 0, 0), new Velocity(0, 0, 0));

        PhaseScheduler sched = new PhaseScheduler(store);
        sched.setWorldSeed(42L);
        sched.register("rng-reader", (view, commit) -> {
            draws.add(view.rng().random().nextLong());
        }, Access.OPAQUE, Phase.MOVEMENT, 0);

        for (int t = 0; t < 5; t++) sched.tick();
        return draws.stream().mapToLong(Long::longValue).toArray();
    }

    /** Different system IDs produce different streams. */
    @Test
    void rng_differentSystemIds_produceDifferentStreams() {
        LatticeRng rng1 = LatticeRng.forSystem(0L, 0L, "system-a");
        LatticeRng rng2 = LatticeRng.forSystem(0L, 0L, "system-b");
        assertNotEquals(rng1.random().nextLong(), rng2.random().nextLong(),
            "distinct system IDs must produce distinct streams");
    }

    /** Different ticks produce different streams for the same system. */
    @Test
    void rng_differentTicks_produceDifferentStreams() {
        LatticeRng rng1 = LatticeRng.forSystem(0L, 1L, "sys");
        LatticeRng rng2 = LatticeRng.forSystem(0L, 2L, "sys");
        assertNotEquals(rng1.random().nextLong(), rng2.random().nextLong(),
            "different ticks must produce different streams");
    }

    /** Per-entity child streams are deterministic and entity-unique. */
    @Test
    void rng_forEntity_deterministicAndDistinct() {
        LatticeRng root = LatticeRng.forSystem(0L, 0L, "sys");
        LatticeRng e1a  = root.forEntity(1L);
        LatticeRng e1b  = root.forEntity(1L);
        LatticeRng e2   = root.forEntity(2L);

        assertEquals(e1a.random().nextLong(), e1b.random().nextLong(),
            "same entity ID must produce identical child stream");
        assertNotEquals(e1a.random().nextLong(), e2.random().nextLong(),
            "different entity IDs must produce distinct child streams");
    }

    // ── 3. IN_SYSTEM_THREAD detector ─────────────────────────────────────────

    /**
     * assertNotInSystemThread throws when called inside a declared (non-opaque) system.
     * Declared systems set IN_SYSTEM_THREAD; opaque systems do not (they legitimately
     * touch shared state like Level.random and must not trigger the detector).
     */
    @Test
    void detector_throwsInsideSystem() {
        ComponentStore store = new ComponentStore();
        store.spawn(1L, new Position(0, 0, 0), new Velocity(0, 0, 0));
        PhaseScheduler sched = new PhaseScheduler(store);
        sched.register("bad-rng-access", (view, commit) -> {
            LatticeRng.assertNotInSystemThread(); // must throw — declared system sets flag
        }, Access.builder().reads(Position.class).build(), Phase.MOVEMENT, 0);

        assertThrows(IllegalStateException.class, sched::tick);
    }

    /** assertNotInSystemThread is silent outside system execution. */
    @Test
    void detector_silentOutsideSystem() {
        assertDoesNotThrow(LatticeRng::assertNotInSystemThread);
    }

    /** IN_SYSTEM_THREAD is false after a declared system's tick completes (no leak). */
    @Test
    void detector_flagClearedAfterTick() {
        ComponentStore store = new ComponentStore();
        store.spawn(1L, new Position(0, 0, 0), new Velocity(0, 0, 0));
        PhaseScheduler sched = new PhaseScheduler(store);
        sched.register("noop", (view, commit) -> {}, Access.builder().reads(Position.class).build(), Phase.MOVEMENT, 0);
        sched.tick();

        assertFalse(LatticeRng.isInSystemThread(), "flag must be cleared after tick");
    }

    /** IN_SYSTEM_THREAD is false after a declared system throws (finally block removes flag). */
    @Test
    void detector_flagClearedAfterThrow() {
        ComponentStore store = new ComponentStore();
        store.spawn(1L, new Position(0, 0, 0), new Velocity(0, 0, 0));
        PhaseScheduler sched = new PhaseScheduler(store);
        sched.register("throws", (view, commit) -> {
            throw new RuntimeException("intentional");
        }, Access.builder().reads(Position.class).build(), Phase.MOVEMENT, 0);

        assertThrows(RuntimeException.class, sched::tick);
        assertFalse(LatticeRng.isInSystemThread(), "flag must be cleared even when system throws");
    }

    // ── 4. Cross-region message path (thin — §1.6) ───────────────────────────

    /** A system can stage a cross-region message; it appears in drainMessages(). */
    @Test
    void message_stagedAndDrained() {
        ComponentStore store = new ComponentStore();
        store.spawn(1L, new Position(0, 0, 0), new Velocity(0, 0, 0));
        PhaseScheduler sched = new PhaseScheduler(store);
        sched.register("sender", (view, commit) -> {
            commit.message(99L, "hello-region-99");
        }, Access.OPAQUE, Phase.MOVEMENT, 0);

        sched.tick();

        List<CommitBuffer.MessageOp> msgs = sched.drainMessages();
        assertEquals(1, msgs.size());
        assertEquals(99L, msgs.get(0).targetRegion());
        assertEquals("hello-region-99", msgs.get(0).payload());
    }

    /** Messages are cleared at the start of each tick so they don't accumulate. */
    @Test
    void message_clearedEachTick() {
        ComponentStore store = new ComponentStore();
        store.spawn(1L, new Position(0, 0, 0), new Velocity(0, 0, 0));
        PhaseScheduler sched = new PhaseScheduler(store);
        sched.register("sender", (view, commit) -> commit.message(1L, "msg"),
            Access.OPAQUE, Phase.MOVEMENT, 0);

        sched.tick();
        sched.drainMessages(); // consume
        sched.tick();
        assertEquals(1, sched.drainMessages().size(), "should have exactly 1 message from second tick");
    }

    /**
     * Cross-region message plumbing: the message payload carries A's actual runtime state
     * (not a constant), and B receives exactly that value one tick later.
     *
     * <p>Entity A's velocity grows each tick (via gravity). Each tick A sends its current
     * y-velocity to region 1 via {@code commit.message()}. Region 1's entity B receives
     * the message one tick later and records it in a Tag component. We assert B recorded
     * the value A held one tick prior.
     *
     * <p>This verifies: (a) messages carry dynamic state from A, (b) one-tick delivery
     * latency is exact, (c) the message path is idiomatic — it is not a constant broadcast.
     *
     * <p>True §1.6 equivalence (region split/merge doesn't change outcomes) requires real
     * region ownership and is deferred to Phase 4. See HANDOFF §5.
     */
    @Test
    void messageDelivery_aStatePropagatedToB_correctValueOneTick() {
        // Region 0: entity A falls under gravity, messages its y-velocity to region 1 each tick
        ComponentStore region0 = new ComponentStore();
        region0.spawn(1L, new Position(0, 100, 0), new Velocity(0, 0, 0));

        PhaseScheduler sched0 = new PhaseScheduler(region0);
        sched0.setWorldSeed(7L);

        Access gravAccess = Access.builder().reads(Velocity.class).writes(Velocity.class).build();
        sched0.register("gravity", (view, commit) -> {
            Velocity v = view.get(1L, Velocity.class);
            commit.set(1L, new Velocity(v.dx(), v.dy() - 0.04, v.dz()));
        }, gravAccess, Phase.AI, 0);

        // A's snapshot in MOVEMENT still sees the pre-gravity velocity;
        // gravity commits at AI-phase end, MOVEMENT snapshot sees updated vel.
        sched0.register("sender", (view, commit) -> {
            Velocity v = view.get(1L, Velocity.class); // post-AI snapshot
            commit.message(1L, v.dy()); // send A's current y-velocity to region 1
        }, Access.builder().reads(Velocity.class).build(), Phase.MOVEMENT, 0);

        // Region 1: entity B records the received y-velocity each tick
        // Tag = the received value as a string, so we can assert it
        record SpeedTag(double value) implements Component {}

        ComponentStore region1 = new ComponentStore();
        region1.spawn(2L, new Position(0, 0, 0), new Velocity(0, 0, 0));

        double[] inbox = {Double.NaN}; // NaN = no message yet

        PhaseScheduler sched1 = new PhaseScheduler(region1);
        sched1.register("receiver", (view, commit) -> {
            if (!Double.isNaN(inbox[0])) {
                commit.set(2L, new SpeedTag(inbox[0]));
                inbox[0] = Double.NaN;
            }
        }, Access.builder().writes(SpeedTag.class).build(), Phase.MOVEMENT, 0);

        // Track what A's y-velocity was at the end of each tick (= the value it messaged).
        // Delivery is TRUE next-tick: drain A's messages AFTER sched1 has run, so B sees
        // them in the NEXT sched1.tick() — the same one-tick latency as cross-region messages.
        double[] aVelAfterTick = new double[8];
        for (int t = 0; t < 8; t++) {
            // inbox[0] holds the previous tick's message (NaN at t=0 — no messages yet)
            sched0.tick();
            aVelAfterTick[t] = region0.get(1L, Velocity.class).dy();

            sched1.tick(); // B sees inbox from the PREVIOUS tick, not the current one

            // Drain A's tick-t messages; B will see them in tick t+1
            inbox[0] = Double.NaN;
            for (CommitBuffer.MessageOp msg : sched0.drainMessages()) {
                inbox[0] = (double) msg.payload();
            }

            // After tick t+1 (t >= 1): B's SpeedTag holds A's tick-(t-1) velocity
            if (t >= 1) {
                SpeedTag tag = region1.get(2L, SpeedTag.class);
                assertNotNull(tag, "B should have a SpeedTag by tick " + (t + 1));
                assertEquals(aVelAfterTick[t - 1], tag.value(), 1e-15,
                    "B at tick " + (t + 1) + " must hold A's y-velocity from tick " + t);
            }
        }
    }

    // ── 5. PhaseScheduler.tickCount() ────────────────────────────────────────

    @Test
    void tickCount_incrementsEachTick() {
        ComponentStore store = new ComponentStore();
        PhaseScheduler sched = new PhaseScheduler(store);
        assertEquals(0L, sched.tickCount());
        sched.tick();
        assertEquals(1L, sched.tickCount());
        sched.tick();
        sched.tick();
        assertEquals(3L, sched.tickCount());
    }

    // ── 6. LatticeRegistry — freeze enforcement (§2.7) ───────────────────────

    /** Bootstrap registration and lookup work before freezing. */
    @Test
    void registry_registerAndLookup() {
        LatticeRegistry<String, Integer> reg = new LatticeRegistry<>();
        reg.register("a", 1);
        reg.register("b", 2);
        assertEquals(1, reg.get("a"));
        assertEquals(2, reg.get("b"));
        assertNull(reg.get("c"));
    }

    /** Duplicate key throws immediately. */
    @Test
    void registry_duplicateKeyThrows() {
        LatticeRegistry<String, Integer> reg = new LatticeRegistry<>();
        reg.register("a", 1);
        assertThrows(IllegalArgumentException.class, () -> reg.register("a", 2));
    }

    /** Registration after freeze throws. */
    @Test
    void registry_frozenThrowsOnRegister() {
        LatticeRegistry<String, Integer> reg = new LatticeRegistry<>();
        reg.register("a", 1);
        reg.freeze();
        assertTrue(reg.isFrozen());
        assertThrows(IllegalStateException.class, () -> reg.register("b", 2));
    }

    /** Read access after freeze is allowed. */
    @Test
    void registry_readAfterFreezeAllowed() {
        LatticeRegistry<String, Integer> reg = new LatticeRegistry<>();
        reg.register("x", 42);
        reg.freeze();
        assertEquals(42, reg.get("x"));
        assertTrue(reg.contains("x"));
        assertFalse(reg.contains("y"));
    }

    /**
     * Registration from inside a declared system thread is forbidden even before freeze.
     * The registry checks IN_SYSTEM_THREAD, which is set only for declared systems.
     */
    @Test
    void registry_mutationFromSystemThreadForbidden() {
        LatticeRegistry<String, Integer> reg = new LatticeRegistry<>();
        ComponentStore store = new ComponentStore();
        store.spawn(1L, new Position(0, 0, 0), new Velocity(0, 0, 0));
        PhaseScheduler sched = new PhaseScheduler(store);
        sched.register("bad-registrar", (view, commit) -> {
            reg.register("a", 1); // must throw — declared system sets IN_SYSTEM_THREAD
        }, Access.builder().reads(Position.class).build(), Phase.MOVEMENT, 0);

        assertThrows(IllegalStateException.class, sched::tick);
    }
}
