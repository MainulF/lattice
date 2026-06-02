package io.github.mainulf.lattice.ecs;

import java.util.ArrayList;
import java.util.List;

/**
 * Verifies the determinism contract (§3.5): the same scripted scenario must produce a
 * bit-identical per-tick hash sequence regardless of core count.
 *
 * <p>Phase 3: {@code at(N)} with {@code N > 1} actually dispatches declared systems to a
 * thread pool via {@link PhaseScheduler#setParallelism(int)}. The harness remains the oracle
 * with no API change from Phase 2.
 *
 * <pre>{@code
 * DeterminismHarness.replay(myScenario)
 *     .at(1).at(2).at(4).at(Runtime.getRuntime().availableProcessors())
 *     .assertWorldStateHashIdenticalAcrossAllCoreCounts();
 * }</pre>
 *
 * <p>The per-tick (not end-of-scenario) hash localises where divergence begins —
 * critical for debugging parallel violations.
 */
public final class DeterminismHarness {

    /** A self-contained reproducible simulation run. */
    public interface Scenario {
        /** Build a fresh entity store. Called once per core-count slot. */
        ComponentStore buildStore();

        /** Register systems on a freshly constructed scheduler. */
        void registerSystems(PhaseScheduler scheduler);

        /** Number of ticks to simulate. */
        int ticks();

        /** Component types included in the per-tick hash. */
        List<Class<? extends Component>> hashedComponents();
    }

    private final Scenario scenario;
    private final List<Integer> coreCounts = new ArrayList<>();

    private DeterminismHarness(Scenario scenario) {
        this.scenario = scenario;
    }

    public static DeterminismHarness replay(Scenario scenario) {
        return new DeterminismHarness(scenario);
    }

    /**
     * Add a core count to the run matrix. {@code cores > 1} dispatches disjoint declared
     * systems to a thread pool; opaque systems remain serial regardless.
     */
    public DeterminismHarness at(int cores) {
        if (cores < 1) throw new IllegalArgumentException("cores must be >= 1, got " + cores);
        coreCounts.add(cores);
        return this;
    }

    /**
     * Execute the scenario at each registered core count and assert the per-tick hash
     * sequences are bit-identical across all runs. Throws {@link AssertionError} naming
     * the first diverging tick and both hash values.
     */
    @SuppressWarnings("unchecked")
    public void assertWorldStateHashIdenticalAcrossAllCoreCounts() {
        if (coreCounts.isEmpty()) throw new IllegalStateException("Call at() before asserting.");

        List<Class<? extends Component>> typeList = scenario.hashedComponents();
        Class<? extends Component>[] types = typeList.toArray(new Class[0]);

        long[][] allLogs = new long[coreCounts.size()][];
        for (int i = 0; i < coreCounts.size(); i++) {
            allLogs[i] = runScenario(coreCounts.get(i), types);
        }

        long[] reference = allLogs[0];
        for (int run = 1; run < allLogs.length; run++) {
            long[] log = allLogs[run];
            for (int tick = 0; tick < reference.length; tick++) {
                if (reference[tick] != log[tick]) {
                    throw new AssertionError(String.format(
                        "Determinism violation at tick %d: " +
                        "%d-core run hash=%016x, %d-core run hash=%016x",
                        tick + 1,
                        coreCounts.get(0), reference[tick],
                        coreCounts.get(run), log[tick]));
                }
            }
        }
    }

    /**
     * Execute the scenario at the given core count and return the per-tick hash log.
     * {@code log[t]} is the world-state hash immediately after tick {@code t + 1}.
     * The scheduler is closed after the run to release any thread pool resources.
     */
    private long[] runScenario(int cores, Class<? extends Component>[] types) {
        ComponentStore store = scenario.buildStore();
        try (PhaseScheduler scheduler = new PhaseScheduler(store)) {
            scheduler.setParallelism(cores);
            scenario.registerSystems(scheduler);
            int ticks = scenario.ticks();
            long[] log = new long[ticks];
            for (int t = 0; t < ticks; t++) {
                scheduler.tick();
                log[t] = WorldStateHasher.hash(store, types);
            }
            return log;
        }
    }
}
