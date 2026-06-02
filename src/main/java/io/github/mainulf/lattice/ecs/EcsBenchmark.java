package io.github.mainulf.lattice.ecs;

import java.util.Arrays;
import java.util.List;

/**
 * Phase 3 PoC benchmark — entity-range parallelism speedup curve.
 *
 * <p>Two scenarios are measured to isolate the Amdahl dynamics:
 * <ol>
 *   <li><b>Light</b> — {@link MovementSystem} only (~5 FP ops/entity). Reveals the
 *       deterministic commit-apply as the serial floor.
 *   <li><b>Physics+Wander</b> — movement PLUS per-entity RNG wander and
 *       {@link StrictMath} sin/cos per entity. The parallel fraction grows and the
 *       speedup curve steepens toward the design §5 claim of ~2–4× on a dense scene.
 * </ol>
 *
 * <p>Both scenarios simultaneously verify the flat-line property (world-state hash
 * identical at every core count) — the determinism curve in the §5 PoC spec.
 *
 * <p>Timing is decomposed into compute (submit→join) and commit (applyCommits) via
 * {@link PhaseScheduler#lastTickComputeNs()} / {@link PhaseScheduler#lastTickCommitNs()}.
 *
 * <p>Run via: {@code ./gradlew runBenchmark}
 */
public final class EcsBenchmark {

    private static final int ENTITIES      = 10_000;
    private static final int WARMUP_TICKS  = 300;
    private static final int MEASURE_TICKS = 500;
    private static final int TRIALS        = 3;    // median of 3 reduces JIT noise
    private static final int[] CORE_COUNTS = {1, 2, 4, 8};

    private EcsBenchmark() {}

    /** Registers systems on an already-created, already-parallelism-set scheduler. */
    @FunctionalInterface
    interface SystemRegistrar {
        void register(PhaseScheduler sched);
    }

    public static void main(String[] args) {
        System.out.printf("Lattice Phase 3 PoC — entity-range parallelism%n");
        System.out.printf("Entities: %,d | Warmup: %d | Measure: %d ticks | Trials: %d%n%n",
            ENTITIES, WARMUP_TICKS, MEASURE_TICKS, TRIALS);

        runScenario("Light (MovementSystem only, ~5 FP ops/entity)",
            EcsBenchmark::registerLight);

        System.out.println();

        runScenario("Physics+Wander (movement + StrictMath sin/cos + forEntity RNG/entity)",
            EcsBenchmark::registerHeavy);

        System.out.printf("%n%nAmdahl note: the serial floor is the deterministic commit-apply (§1.2)%n" +
            "  — sort + apply %,d write-ops/tick, inherently serial by design.%n" +
            "  Heavier per-entity work raises the parallel fraction; the curve steepens.%n%n" +
            "Flat hash line = provably identical world state at every core count.%n" +
            "  Folia/MCMT/Bevy cannot provide this guarantee. Lattice can.%n", ENTITIES * 2);
    }

    // ── Scenario runner ───────────────────────────────────────────────────────

    private static void runScenario(String name, SystemRegistrar registrar) {
        System.out.printf("=== %s ===%n%n", name);

        // Determinism: flat-line hash check at 1/2/4/8 cores
        System.out.print("  Verifying determinism contract... ");
        DeterminismHarness.Scenario scene = new DeterminismHarness.Scenario() {
            @Override public ComponentStore buildStore() { return EcsBenchmark.buildStore(); }
            @Override public void registerSystems(PhaseScheduler s) { registrar.register(s); }
            @Override public int ticks() { return 50; }
            @Override public List<Class<? extends Component>> hashedComponents() {
                return List.of(Position.class, Velocity.class);
            }
        };
        boolean flat;
        try {
            DeterminismHarness.replay(scene).at(1).at(2).at(4).at(8)
                .assertWorldStateHashIdenticalAcrossAllCoreCounts();
            flat = true;
            System.out.println("PASS — hash identical at 1/2/4/8 cores");
        } catch (AssertionError e) {
            flat = false;
            System.out.println("FAIL — " + e.getMessage());
        }
        System.out.println();

        // Timing header
        System.out.printf("  %-8s  %-13s  %-12s  %-12s  %-8s  %s%n",
            "Cores", "MSPT (median)", "Compute (ms)", "Commit (ms)", "Speedup", "Hash (N cores)");
        System.out.println("  " + "-".repeat(75));

        double baseline = Double.NaN;
        long   refHash  = 0L;

        for (int cores : CORE_COUNTS) {
            double[] mspts      = new double[TRIALS];
            double[] computeMs  = new double[TRIALS];
            double[] commitMs   = new double[TRIALS];

            for (int trial = 0; trial < TRIALS; trial++) {
                // Warmup — drives JIT on a fresh store, result discarded
                ComponentStore warmup = buildStore();
                try (PhaseScheduler s = newScheduler(warmup, cores, registrar)) {
                    for (int t = 0; t < WARMUP_TICKS; t++) s.tick();
                }

                // Measurement — fresh store so every trial is from t=0
                ComponentStore store = buildStore();
                long sumComputeNs = 0, sumCommitNs = 0;
                try (PhaseScheduler s = newScheduler(store, cores, registrar)) {
                    long start = System.nanoTime();
                    for (int t = 0; t < MEASURE_TICKS; t++) {
                        s.tick();
                        sumComputeNs += s.lastTickComputeNs();
                        sumCommitNs  += s.lastTickCommitNs();
                    }
                    mspts[trial]     = (System.nanoTime() - start) / 1_000_000.0 / MEASURE_TICKS;
                    computeMs[trial] = sumComputeNs / 1_000_000.0 / MEASURE_TICKS;
                    commitMs[trial]  = sumCommitNs  / 1_000_000.0 / MEASURE_TICKS;
                }
            }

            // Final hash: run at the actual core count so the ✓ is a real per-cores check
            ComponentStore hashStore = buildStore();
            long finalHash;
            try (PhaseScheduler s = newScheduler(hashStore, cores, registrar)) {
                for (int t = 0; t < MEASURE_TICKS; t++) s.tick();
            }
            finalHash = WorldStateHasher.hash(hashStore, Position.class, Velocity.class);

            Arrays.sort(mspts);
            Arrays.sort(computeMs);
            Arrays.sort(commitMs);
            double median    = mspts[TRIALS / 2];
            double compute   = computeMs[TRIALS / 2];
            double commit    = commitMs[TRIALS / 2];

            if (cores == 1) { baseline = median; refHash = finalHash; }

            double speedup  = baseline / median;
            String hashMark = flat && (finalHash == refHash) ? "✓" : "✗ DIVERGED";

            System.out.printf("  %-8d  %-13.4f  %-12.4f  %-12.4f  %-8.2fx  %016x %s%n",
                cores, median, compute, commit, speedup, finalHash, hashMark);
        }
        System.out.println();
        System.out.println("  Compute = submit→join (parallel); Commit = applyCommits (serial by design §1.2).");
        System.out.println("  Serial fraction ≈ Commit / (Compute + Commit). Amdahl ceiling = 1 / serial_fraction.");
    }

    // ── System registrars ─────────────────────────────────────────────────────

    private static void registerLight(PhaseScheduler sched) {
        sched.register("movement", new MovementSystem(), MovementSystem.ACCESS, Phase.MOVEMENT, 50);
    }

    /**
     * Physics + per-entity wander merged into one Phase.MOVEMENT system.
     * Merging avoids a second applyCommits() (which would add to the serial floor);
     * the extra StrictMath + RNG work per entity is purely in the parallel compute phase.
     * Represents the §5 PoC scene: "gravity + movement + AI" for each entity.
     */
    private static void registerHeavy(PhaseScheduler sched) {
        Access access = Access.builder()
            .reads(Position.class, Velocity.class)
            .writes(Position.class, Velocity.class)
            .build();
        sched.register("physics-wander", (view, commit) -> {
            for (long id : view.query(Position.class, Velocity.class)) {
                Position pos = view.get(id, Position.class);
                Velocity vel = view.get(id, Velocity.class);

                // Wander AI: per-entity RNG + StrictMath (deterministic across cores
                // because forEntity is a pure function of seed + entity ID)
                LatticeRng rng  = view.rng().forEntity(id);
                double angle    = rng.random().nextDouble() * StrictMath.PI * 2.0;
                double wander   = rng.random().nextDouble() * 0.002;
                double ndx      = vel.dx() + StrictMath.cos(angle) * wander;
                double ndz      = vel.dz() + StrictMath.sin(angle) * wander;

                // Gravity + integrate
                double nvy = vel.dy() - 0.04;
                commit.set(id, new Position(pos.x() + ndx, pos.y() + nvy, pos.z() + ndz));
                commit.set(id, new Velocity(ndx * 0.98f, nvy * 0.98, ndz * 0.98f));
            }
        }, access, Phase.MOVEMENT, 50);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static PhaseScheduler newScheduler(ComponentStore store, int cores, SystemRegistrar r) {
        PhaseScheduler s = new PhaseScheduler(store);
        s.setParallelism(cores);
        r.register(s);
        return s;
    }

    private static ComponentStore buildStore() {
        ComponentStore store = new ComponentStore();
        for (long id = 1; id <= ENTITIES; id++) {
            store.spawn(id,
                new Position(id * 0.5, 200.0, id * 0.3),
                new Velocity(0.0, 0.0, 0.0));
        }
        return store;
    }
}
