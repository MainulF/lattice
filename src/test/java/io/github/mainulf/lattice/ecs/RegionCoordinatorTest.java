package io.github.mainulf.lattice.ecs;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Phase 4 inter-region parallelism (axis A):
 * region ownership, cross-region message routing, and the §1.6 invariant.
 */
class RegionCoordinatorTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Region makeRegion(long id) {
        ComponentStore store = new ComponentStore();
        return new Region(id, store, new PhaseScheduler(store));
    }

    private static Region makeRegion(long id, ComponentStore store) {
        return new Region(id, store, new PhaseScheduler(store));
    }

    // ── 1. Single-region sanity ───────────────────────────────────────────────

    /** A single region under the coordinator behaves identically to a bare scheduler. */
    @Test
    void singleRegion_ticksCorrectly() {
        ComponentStore store = new ComponentStore();
        store.spawn(1L, new Position(0, 100, 0), new Velocity(0, 0, 0));

        try (RegionCoordinator coord = new RegionCoordinator()) {
            Region r = makeRegion(0L, store);
            coord.addRegion(r);
            r.scheduler().register("gravity", (view, commit) -> {
                Velocity v = view.get(1L, Velocity.class);
                commit.set(1L, new Velocity(v.dx(), v.dy() - 0.04, v.dz()));
            }, Access.builder().reads(Velocity.class).writes(Velocity.class).build(),
               Phase.AI, 0);

            for (int t = 0; t < 10; t++) coord.tick();

            double expectedVy = -0.04 * 10;
            assertEquals(expectedVy, store.get(1L, Velocity.class).dy(), 1e-12);
        }
    }

    /** Duplicate region ids are rejected at registration time. */
    @Test
    void addRegion_duplicateId_throws() {
        try (RegionCoordinator coord = new RegionCoordinator()) {
            coord.addRegion(makeRegion(1L));
            assertThrows(IllegalArgumentException.class, () -> coord.addRegion(makeRegion(1L)));
        }
    }

    // ── 2. Cross-region message routing ──────────────────────────────────────

    /**
     * Entity A in region 0 sends a message to region 1 each tick.
     * Entity B in region 1 reads it from view.inbox() and records the value.
     * Asserts exact one-tick delivery latency.
     */
    @Test
    void crossRegion_messageDeliveredOneTickLater() {
        record Tag(double value) implements Component {}

        ComponentStore store0 = new ComponentStore();
        store0.spawn(1L, new Velocity(0, 0, 0));

        ComponentStore store1 = new ComponentStore();
        store1.spawn(2L, new Tag(Double.NaN));

        try (RegionCoordinator coord = new RegionCoordinator()) {
            Region r0 = makeRegion(0L, store0);
            Region r1 = makeRegion(1L, store1);
            coord.addRegion(r0);
            coord.addRegion(r1);

            // Region 0: gravity on entity 1, then messages its y-velocity to region 1
            r0.scheduler().register("gravity", (view, commit) -> {
                Velocity v = view.get(1L, Velocity.class);
                commit.set(1L, new Velocity(v.dx(), v.dy() - 0.04, v.dz()));
            }, Access.builder().reads(Velocity.class).writes(Velocity.class).build(), Phase.AI, 0);

            r0.scheduler().register("sender", (view, commit) -> {
                commit.message(1L, view.get(1L, Velocity.class).dy());
            }, Access.builder().reads(Velocity.class).build(), Phase.MOVEMENT, 0);

            // Region 1: entity 2 records first inbox message each tick (if any)
            r1.scheduler().register("receiver", (view, commit) -> {
                List<Object> msgs = view.inbox();
                if (!msgs.isEmpty()) {
                    commit.set(2L, new Tag((double) msgs.get(0)));
                }
            }, Access.builder().writes(Tag.class).build(), Phase.MOVEMENT, 0);

            // Track A's vy at end of each tick
            double[] aVyAfterTick = new double[6];
            for (int t = 0; t < 6; t++) {
                coord.tick();
                aVyAfterTick[t] = store0.get(1L, Velocity.class).dy();

                // After tick 0: B has no message yet (first message delivered at tick 1)
                if (t == 0) {
                    assertEquals(Double.NaN, store1.get(2L, Tag.class).value(), "no message on tick 0");
                }
                // After tick t >= 1: B holds value A had after tick t-1
                if (t >= 1) {
                    assertEquals(aVyAfterTick[t - 1], store1.get(2L, Tag.class).value(), 1e-15,
                        "B at tick " + (t + 1) + " must hold A's vy from tick " + t);
                }
            }
        }
    }

    /** Messages to unknown region ids are silently dropped (not an error). */
    @Test
    void message_unknownTarget_silentlyDropped() {
        ComponentStore store = new ComponentStore();
        store.spawn(1L, new Position(0, 0, 0));

        try (RegionCoordinator coord = new RegionCoordinator()) {
            Region r = makeRegion(0L, store);
            coord.addRegion(r);
            r.scheduler().register("sender", (view, commit) -> {
                commit.message(999L, "nobody home");
            }, Access.OPAQUE, Phase.MOVEMENT, 0);

            assertDoesNotThrow(() -> { coord.tick(); coord.tick(); });
        }
    }

    // ── 3. Self-messages (same-region deferred) ───────────────────────────────

    /**
     * A self-message (commit.message(ownRegionId, ...) from within region R) is
     * delivered to region R's inbox the next tick — same latency as cross-region.
     * This is the §1.6 mechanism: an interaction that messages its own region id
     * and one that messages a remote region id both incur one-tick deferred delivery.
     */
    @Test
    void selfMessage_deliveredOneTickLater() {
        record Counter(int n) implements Component {}

        ComponentStore store = new ComponentStore();
        store.spawn(1L, new Counter(0));

        try (RegionCoordinator coord = new RegionCoordinator()) {
            Region r = makeRegion(7L, store);  // region id = 7
            coord.addRegion(r);

            // Each tick, read inbox and set Counter = value received; also send counter+1
            r.scheduler().register("self-ping", (view, commit) -> {
                List<Object> inbox = view.inbox();
                int received = inbox.isEmpty() ? -1 : (int) inbox.get(0);
                commit.set(1L, new Counter(received));
                commit.message(7L, received + 1);  // message own region (id=7)
            }, Access.builder().reads(Counter.class).writes(Counter.class).build(),
               Phase.MOVEMENT, 0);

            coord.tick();  // tick 1: inbox empty → Counter=-1; sends message(7L, 0)
            assertEquals(-1, store.get(1L, Counter.class).n(), "tick 1: no inbox");

            coord.tick();  // tick 2: inbox=[0] → Counter=0; sends message(7L, 1)
            assertEquals(0, store.get(1L, Counter.class).n(), "tick 2: got self-message from tick 1");

            coord.tick();  // tick 3: inbox=[1] → Counter=1
            assertEquals(1, store.get(1L, Counter.class).n(), "tick 3: got self-message from tick 2");
        }
    }

    // ── 4. §1.6 invariant: co-regional vs cross-regional outcomes identical ──

    /**
     * The core §1.6 test.
     *
     * <p>Interaction model: entity A applies gravity every tick and sends its y-velocity to
     * entity B via {@code commit.message}. Entity B reads the inbox and records the received
     * value as a {@code SpeedTag} component. Both configs use {@code commit.message} for
     * the A→B interaction — this is the uniform deferred model that makes §1.6 hold:
     *
     * <ul>
     *   <li><b>Co-regional</b>: A and B in region 0. A messages {@code targetRegion=0}
     *       (self). Coordinator routes it back → one-tick deferred delivery.
     *   <li><b>Cross-regional</b>: A in region 0, B in region 1. A messages {@code
     *       targetRegion=1}. Coordinator routes to region 1 → one-tick deferred delivery.
     * </ul>
     *
     * <p>B's SpeedTag must be bit-identical across both configs at every tick, because both
     * paths have identical latency, identical inbox ordering, and identical RNG keying.
     *
     * <p>The comparison targets only entity B's {@code SpeedTag.value()} — not a whole-store
     * hash — because the two stores have different entity populations (storeA has entity 1+2
     * in the co-regional case vs only entity 2 in storeB for cross-regional). Hashing the
     * whole store would include entity-id contributions that legitimately differ.
     */
    /**
     * §1.6: co-regional == cross-regional == cross-regional-parallel.
     *
     * <p>Three configs must produce bit-identical per-tick values for entity B:
     * <ol>
     *   <li>Co-regional at 1 core (serial, baseline)</li>
     *   <li>Cross-regional at 1 core (serial routing — equivalence of the interaction model)</li>
     *   <li>Cross-regional at 4 cores (parallel region ticking — verifies the barrier and
     *       deterministic drain order can't introduce a race in message routing)</li>
     * </ol>
     *
     * <p>The liveness assertion (last tick B.value == expected) guards against a vacuous
     * pass where messages are silently dropped and both arrays are all-NaN.
     */
    @Test
    void section16_coRegional_vs_crossRegional_identicalBehavior() {
        int ticks = 20;
        double[] coRegional1       = runInteractionScenario(true,  1);
        double[] crossRegional1    = runInteractionScenario(false, 1);
        double[] crossRegional4    = runInteractionScenario(false, 4);

        // Liveness: after tick 1 B has NaN; after tick 2 B has first value; by tick 20 it's non-NaN.
        // Expected B.value at tick t (0-indexed, t >= 1) = -0.04 * t (A's vy from prev tick).
        double expectedLast = -0.04 * (ticks - 1);
        assertEquals(expectedLast, coRegional1[ticks - 1], 1e-12,
            "§1.6 liveness: co-regional B must have received messages by tick " + ticks);

        for (int t = 0; t < ticks; t++) {
            long a = Double.doubleToRawLongBits(coRegional1[t]);
            long b = Double.doubleToRawLongBits(crossRegional1[t]);
            long c = Double.doubleToRawLongBits(crossRegional4[t]);
            if (a != b || a != c) {
                throw new AssertionError(String.format(
                    "§1.6 violation at tick %d: co-reg1=%s, cross-reg1=%s, cross-reg4=%s",
                    t + 1, coRegional1[t], crossRegional1[t], crossRegional4[t]));
            }
        }
    }

    /**
     * Run the §1.6 interaction scenario and return the per-tick value of entity B's
     * SpeedTag (NaN if not yet set). Comparing these arrays directly tests whether
     * co-regional and cross-regional interactions produce identical outcomes.
     *
     * @param coRegional           if true, A and B share region 0 (A messages region 0 = self);
     *                             if false, A is in region 0, B is in region 1.
     * @param coordinatorParallelism cores for {@link RegionCoordinator#setParallelism} (axis A).
     */
    private double[] runInteractionScenario(boolean coRegional, int coordinatorParallelism) {
        record SpeedTag(double value) implements Component {}

        int ticks = 20;
        double[] bValues = new double[ticks];

        if (coRegional) {
            ComponentStore store = new ComponentStore();
            store.spawn(1L, new Velocity(0, 0, 0));
            store.spawn(2L, new SpeedTag(Double.NaN));

            try (RegionCoordinator coord = new RegionCoordinator()) {
                coord.setParallelism(coordinatorParallelism);
                Region r0 = new Region(0L, store, new PhaseScheduler(store));
                coord.addRegion(r0);

                r0.scheduler().register("gravity", (view, commit) -> {
                    Velocity v = view.get(1L, Velocity.class);
                    commit.set(1L, new Velocity(v.dx(), v.dy() - 0.04, v.dz()));
                }, Access.builder().reads(Velocity.class).writes(Velocity.class).build(),
                   Phase.AI, 0);

                // A messages its own region (self-message → one-tick deferred, §1.6 latency symmetry)
                r0.scheduler().register("sender", (view, commit) -> {
                    commit.message(0L, view.get(1L, Velocity.class).dy());
                }, Access.builder().reads(Velocity.class).build(), Phase.MOVEMENT, 0);

                r0.scheduler().register("receiver", (view, commit) -> {
                    List<Object> inbox = view.inbox();
                    if (!inbox.isEmpty()) {
                        commit.set(2L, new SpeedTag((double) inbox.get(0)));
                    }
                }, Access.builder().reads(SpeedTag.class).writes(SpeedTag.class).build(),
                   Phase.MOVEMENT, 1);

                for (int t = 0; t < ticks; t++) {
                    coord.tick();
                    SpeedTag tag = store.get(2L, SpeedTag.class);
                    bValues[t] = (tag != null) ? tag.value() : Double.NaN;
                }
            }
        } else {
            ComponentStore storeA = new ComponentStore();
            storeA.spawn(1L, new Velocity(0, 0, 0));
            ComponentStore storeB = new ComponentStore();
            storeB.spawn(2L, new SpeedTag(Double.NaN));

            try (RegionCoordinator coord = new RegionCoordinator()) {
                coord.setParallelism(coordinatorParallelism);
                Region r0 = new Region(0L, storeA, new PhaseScheduler(storeA));
                Region r1 = new Region(1L, storeB, new PhaseScheduler(storeB));
                coord.addRegion(r0);
                coord.addRegion(r1);

                r0.scheduler().register("gravity", (view, commit) -> {
                    Velocity v = view.get(1L, Velocity.class);
                    commit.set(1L, new Velocity(v.dx(), v.dy() - 0.04, v.dz()));
                }, Access.builder().reads(Velocity.class).writes(Velocity.class).build(),
                   Phase.AI, 0);

                r0.scheduler().register("sender", (view, commit) -> {
                    commit.message(1L, view.get(1L, Velocity.class).dy());
                }, Access.builder().reads(Velocity.class).build(), Phase.MOVEMENT, 0);

                r1.scheduler().register("receiver", (view, commit) -> {
                    List<Object> inbox = view.inbox();
                    if (!inbox.isEmpty()) {
                        commit.set(2L, new SpeedTag((double) inbox.get(0)));
                    }
                }, Access.builder().reads(SpeedTag.class).writes(SpeedTag.class).build(),
                   Phase.MOVEMENT, 1);

                for (int t = 0; t < ticks; t++) {
                    coord.tick();
                    SpeedTag tag = storeB.get(2L, SpeedTag.class);
                    bValues[t] = (tag != null) ? tag.value() : Double.NaN;
                }
            }
        }
        return bValues;
    }

    // ── 5. Parallel axis-A ticking ────────────────────────────────────────────

    /**
     * Multiple regions ticking in parallel via the coordinator pool (axis A) produce the
     * same world-state hash as serial ticking. Each region is independent (no messages),
     * so execution order cannot affect results.
     */
    @Test
    void parallelAxisA_independentRegions_identicalHashAtAllCoreCounts() {
        long[] serial   = runParallelScenario(1);
        long[] parallel = runParallelScenario(4);

        assertEquals(serial.length, parallel.length);
        for (int t = 0; t < serial.length; t++) {
            assertEquals(serial[t], parallel[t],
                "Axis-A hash mismatch at tick " + (t + 1));
        }
    }

    /** Run 4 independent regions (each with 5 entities), return XOR of all region hashes per tick. */
    private long[] runParallelScenario(int axiAParallelism) {
        int ticks = 50;
        int numRegions = 4;
        int entitiesPerRegion = 5;
        long[] log = new long[ticks];

        ComponentStore[] stores = new ComponentStore[numRegions];
        Region[] regs = new Region[numRegions];
        try (RegionCoordinator coord = new RegionCoordinator()) {
            coord.setParallelism(axiAParallelism);

            for (int ri = 0; ri < numRegions; ri++) {
                ComponentStore store = new ComponentStore();
                stores[ri] = store;
                long baseId = (long) ri * 1000 + 1;
                for (long id = baseId; id < baseId + entitiesPerRegion; id++) {
                    store.spawn(id, new Position(id, 100.0, 0), new Velocity(0, 0, 0));
                }
                Region r = new Region(ri, store, new PhaseScheduler(store));
                regs[ri] = r;
                coord.addRegion(r);

                r.scheduler().register("gravity", (view, commit) -> {
                    for (long id : view.query(Velocity.class)) {
                        Velocity v = view.get(id, Velocity.class);
                        commit.set(id, new Velocity(v.dx(), v.dy() - 0.04, v.dz()));
                    }
                }, Access.builder().reads(Velocity.class).writes(Velocity.class).build(),
                   Phase.AI, 0);

                r.scheduler().register("move", (view, commit) -> {
                    for (long id : view.query(Position.class, Velocity.class)) {
                        Position p = view.get(id, Position.class);
                        Velocity v = view.get(id, Velocity.class);
                        commit.set(id, new Position(p.x() + v.dx(), p.y() + v.dy(), p.z() + v.dz()));
                    }
                }, Access.builder().reads(Position.class, Velocity.class).writes(Position.class).build(),
                   Phase.MOVEMENT, 0);
            }

            for (int t = 0; t < ticks; t++) {
                coord.tick();
                long xorHash = 0L;
                for (ComponentStore s : stores) {
                    xorHash ^= WorldStateHasher.hash(s, Position.class, Velocity.class);
                }
                log[t] = xorHash;
            }
        }
        return log;
    }

    // ── 6. Inbox read from view.inbox() ──────────────────────────────────────

    /** view.inbox() returns an empty list when no messages were delivered. */
    @Test
    void inbox_emptyWhenNoMessages() {
        ComponentStore store = new ComponentStore();
        store.spawn(1L, new Position(0, 0, 0));
        boolean[] sawEmptyInbox = {false};

        try (RegionCoordinator coord = new RegionCoordinator()) {
            Region r = makeRegion(0L, store);
            coord.addRegion(r);
            r.scheduler().register("check", (view, commit) -> {
                sawEmptyInbox[0] = view.inbox().isEmpty();
            }, Access.OPAQUE, Phase.MOVEMENT, 0);

            coord.tick();
            assertTrue(sawEmptyInbox[0], "inbox should be empty when no messages delivered");
        }
    }

    /** view.inbox() is unmodifiable — mutation attempt throws. */
    @Test
    void inbox_isUnmodifiable() {
        ComponentStore store0 = new ComponentStore();
        store0.spawn(1L, new Position(0, 0, 0));
        ComponentStore store1 = new ComponentStore();
        store1.spawn(2L, new Position(0, 0, 0));

        try (RegionCoordinator coord = new RegionCoordinator()) {
            Region r0 = makeRegion(0L, store0);
            Region r1 = makeRegion(1L, store1);
            coord.addRegion(r0);
            coord.addRegion(r1);

            // send a message from r0 to r1
            r0.scheduler().register("sender", (view, commit) -> {
                commit.message(1L, "hello");
            }, Access.OPAQUE, Phase.MOVEMENT, 0);

            // receive in r1 and try to mutate the inbox list
            boolean[] threw = {false};
            r1.scheduler().register("try-mutate", (view, commit) -> {
                try {
                    view.inbox().clear();
                } catch (UnsupportedOperationException e) {
                    threw[0] = true;
                }
            }, Access.OPAQUE, Phase.MOVEMENT, 0);

            coord.tick();  // tick 1: message staged
            coord.tick();  // tick 2: message delivered, receiver attempts mutation

            assertTrue(threw[0], "inbox list must be unmodifiable");
        }
    }

    // ── 7. Composed A+B determinism (Phase 4 exit criterion) ─────────────────────

    /**
     * Phase 4 exit criterion (§5): axis A (coordinator parallelism) and axis B
     * (per-region entity-range splitting) active simultaneously. Verifies the flat-hash
     * invariant holds through A+B composing — the per-tick world-state hash is
     * bit-identical regardless of how parallelism is split between the two axes.
     *
     * <p>4 independent regions (25 entities each), gravity (Phase.AI) + movement
     * (Phase.MOVEMENT). Per-tick XOR of all region hashes must match for:
     * <ul>
     *   <li>(A=1, B=1) — fully serial baseline</li>
     *   <li>(A=4, B=1) — axis A only (4 regions in parallel, serial schedulers)</li>
     *   <li>(A=1, B=4) — axis B only (serial coordinator, 4-chunk entity fans)</li>
     *   <li>(A=2, B=2) — both axes composing</li>
     * </ul>
     */
    @Test
    void composedAxesAB_independentRegions_hashFlatAcrossAllConfigurations() {
        int ticks   = 50;
        long[] serial   = runComposedAxes(ticks, 1, 1);
        long[] axisA    = runComposedAxes(ticks, 4, 1);
        long[] axisB    = runComposedAxes(ticks, 1, 4);
        long[] composed = runComposedAxes(ticks, 2, 2);

        for (int t = 0; t < ticks; t++) {
            long s = serial[t], a = axisA[t], b = axisB[t], c = composed[t];
            if (s != a || s != b || s != c) {
                throw new AssertionError(String.format(
                    "A+B composition hash mismatch at tick %d: " +
                    "serial=%016x  A-only=%016x  B-only=%016x  composed=%016x",
                    t + 1, s, a, b, c));
            }
        }
    }

    /**
     * Run 4 independent regions (25 entities each) at the given coordinator and scheduler
     * parallelism. Gravity in Phase.AI + movement in Phase.MOVEMENT. Returns per-tick
     * XOR of all region hashes (Position, Velocity) — the same quantity used by
     * {@link #parallelAxisA_independentRegions_identicalHashAtAllCoreCounts}.
     */
    private long[] runComposedAxes(int ticks, int coordCores, int schedulerCores) {
        int numRegions    = 4;
        int entsPerRegion = 25;
        long[] log = new long[ticks];
        ComponentStore[] stores = new ComponentStore[numRegions];

        try (RegionCoordinator coord = new RegionCoordinator()) {
            coord.setParallelism(coordCores);

            for (int ri = 0; ri < numRegions; ri++) {
                ComponentStore store = new ComponentStore();
                stores[ri] = store;
                long base = (long) ri * 100 + 1;
                for (long id = base; id < base + entsPerRegion; id++) {
                    store.spawn(id, new Position(id, 100.0, 0), new Velocity(0, 0, 0));
                }
                PhaseScheduler sched = new PhaseScheduler(store);
                sched.setParallelism(schedulerCores);
                Region r = new Region(ri, store, sched);
                coord.addRegion(r);

                r.scheduler().register("gravity", (view, commit) -> {
                    for (long id : view.query(Velocity.class)) {
                        Velocity v = view.get(id, Velocity.class);
                        commit.set(id, new Velocity(v.dx(), v.dy() - 0.04, v.dz()));
                    }
                }, Access.builder().reads(Velocity.class).writes(Velocity.class).build(),
                   Phase.AI, 0);

                r.scheduler().register("move", (view, commit) -> {
                    for (long id : view.query(Position.class, Velocity.class)) {
                        Position p = view.get(id, Position.class);
                        Velocity v = view.get(id, Velocity.class);
                        commit.set(id, new Position(p.x() + v.dx(), p.y() + v.dy(), p.z() + v.dz()));
                    }
                }, Access.builder().reads(Position.class, Velocity.class).writes(Position.class).build(),
                   Phase.MOVEMENT, 0);
            }

            for (int t = 0; t < ticks; t++) {
                coord.tick();
                long xor = 0L;
                for (ComponentStore s : stores) {
                    xor ^= WorldStateHasher.hash(s, Position.class, Velocity.class);
                }
                log[t] = xor;
            }
        }
        return log;
    }
}
