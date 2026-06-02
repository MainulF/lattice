package io.github.mainulf.lattice.ecs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Phase scheduler with entity-range parallel fan-out (Phase 3).
 *
 * <h2>Parallelism model</h2>
 * <p>Default {@code parallelism=1} is fully serial — identical behaviour to Phase 1/2.
 * Call {@link #setParallelism(int)} with {@code cores > 1} to fan out declared systems:
 * each system's matching entity set is split into N contiguous chunks (one per core) and
 * submitted to a fixed thread pool. This is entity-range (data) parallelism — the same
 * system logic runs in parallel over disjoint entity slices. Opaque systems always run
 * serial on the caller thread regardless of the parallelism setting.
 *
 * <h2>Snapshot removal</h2>
 * <p>No per-phase store snapshot copy is made. The live store is safe to read concurrently
 * during compute because all writes are deferred to thread-local {@link CommitBuffer}s;
 * {@link #applyCommits} runs only after the join, on the caller thread. Between phases,
 * {@code applyCommits} updates the live store, so the next phase naturally sees the
 * committed writes from the previous phase.
 *
 * <h2>Determinism</h2>
 * <p>Commit order is independent of execution order: {@link #applyCommits} sorts all write
 * ops by {@code (systemPriority, systemId, entityId, componentType)} before applying,
 * so the result is identical at every core count. Entity chunks are contiguous in-order
 * slices of the sorted entity array — the commit sort then reproduces the same total
 * order regardless of which thread processed which chunk.
 *
 * <p>Implements {@link AutoCloseable} — call {@link #close()} (or use try-with-resources)
 * to shut down the thread pool when done.
 *
 * <p>Unresolved intra-phase conflicts among declared systems are a hard error (§3.4).
 */
public final class PhaseScheduler implements AutoCloseable {

    private record Registration(
        String id,
        LatticeSystem system,
        Access access,
        Phase phase,
        int priority
    ) {}

    private final List<Registration> registrations = new ArrayList<>();
    private final ComponentStore store;
    private long worldSeed  = 0L;
    private long tickCount  = 0L;
    private int  parallelism = 1;
    private ExecutorService pool = null;

    // Per-tick timing accumulators (summed across all phases, reset each tick).
    // computeNs = time inside submit→join (parallel compute); commitNs = time inside applyCommits.
    private long accComputeNs;
    private long accCommitNs;
    private long lastComputeNs;
    private long lastCommitNs;

    /** Pending cross-region messages from the last completed tick. Cleared at tick start. */
    private final List<CommitBuffer.MessageOp> pendingMessages = new ArrayList<>();

    /**
     * Messages delivered to this region before the current tick (populated by
     * {@link RegionCoordinator} or {@link Region#deliverMessages}). Immutable during
     * execution; reset to empty at tick start.
     */
    private List<Object> currentInbox = Collections.emptyList();

    public PhaseScheduler(ComponentStore store) {
        this.store = store;
    }

    /**
     * Set the number of threads used for parallel entity-range fan-out within a phase.
     * {@code cores=1} (the default) keeps fully serial behaviour.
     * {@code cores>1} creates a fixed thread pool; each declared system's entity set is
     * split into at most {@code cores} chunks that run concurrently. Opaque systems always
     * run serial on the caller thread.
     * Safe to call before the first tick; do not call mid-tick.
     */
    public void setParallelism(int cores) {
        if (cores < 1) throw new IllegalArgumentException("cores must be >= 1, got " + cores);
        if (pool != null) {
            pool.shutdown();
            pool = null;
        }
        parallelism = cores;
        if (cores > 1) {
            pool = Executors.newFixedThreadPool(cores);
        }
    }

    /** Shut down the thread pool if one was created via {@link #setParallelism}. */
    @Override
    public void close() {
        if (pool != null) {
            pool.shutdown();
            pool = null;
        }
    }

    /**
     * Set the world seed used to key per-system RNG streams (§1.4).
     * Must be called before the first tick; changing mid-run voids determinism.
     */
    public void setWorldSeed(long seed) {
        this.worldSeed = seed;
    }

    /** Returns the number of ticks this scheduler has completed. */
    public long tickCount() {
        return tickCount;
    }

    /**
     * Set the inbox for the upcoming tick. Called by {@link RegionCoordinator} (or
     * {@link Region#deliverMessages}) before {@link #tick()}. The list must not be
     * mutated after this call; it is exposed read-only to systems via
     * {@link View#inbox()}.
     */
    public void setInbox(List<Object> inbox) {
        this.currentInbox = inbox != null ? Collections.unmodifiableList(inbox) : Collections.emptyList();
    }

    /**
     * Drain and return cross-region messages staged during the last tick.
     * The region infrastructure (Phase 4) calls this to route messages to their
     * target schedulers before the next tick begins.
     */
    public List<CommitBuffer.MessageOp> drainMessages() {
        List<CommitBuffer.MessageOp> out = new ArrayList<>(pendingMessages);
        pendingMessages.clear();
        return out;
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

    /** Run one full tick: every phase in enum order. */
    public void tick() {
        pendingMessages.clear();
        accComputeNs = 0;
        accCommitNs  = 0;
        // Capture the inbox snapshot for this tick; reset for next tick.
        List<Object> tickInbox = currentInbox;
        currentInbox = Collections.emptyList();
        for (Phase phase : Phase.values()) {
            runPhase(phase, tickInbox);
        }
        tickCount++;
        lastComputeNs = accComputeNs;
        lastCommitNs  = accCommitNs;
    }

    /** Nanoseconds spent in the submit→join (parallel compute) during the last tick. */
    public long lastTickComputeNs() { return lastComputeNs; }

    /** Nanoseconds spent in {@code applyCommits} (serial commit) during the last tick. */
    public long lastTickCommitNs()  { return lastCommitNs; }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void runPhase(Phase phase, List<Object> inbox) {
        List<Registration> inPhase = registrations.stream()
            .filter(r -> r.phase() == phase)
            .sorted(Comparator.comparingInt(Registration::priority)
                .thenComparing(Registration::id))
            .toList();

        if (inPhase.isEmpty()) return;

        validateNoAmbiguities(inPhase);

        // No snapshot copy: writes go to CommitBuffers; applyCommits runs after the join
        // on the caller thread, so the live store is frozen during compute (§1.2).
        long t0 = System.nanoTime();
        List<CommitBuffer> buffers = (pool != null)
            ? runPhaseParallel(inPhase, inbox)
            : runPhaseSerial(inPhase, inbox);
        long t1 = System.nanoTime();
        applyCommits(buffers);
        long t2 = System.nanoTime();

        accComputeNs += t1 - t0;
        accCommitNs  += t2 - t1;
    }

    private List<CommitBuffer> runPhaseSerial(List<Registration> inPhase, List<Object> inbox) {
        List<CommitBuffer> buffers = new ArrayList<>(inPhase.size());
        for (Registration reg : inPhase) {
            buffers.add(executeSystem(reg, inbox));
        }
        return buffers;
    }

    /**
     * Entity-range parallel fan-out. For each declared system:
     * <ol>
     *   <li>Compute the matching entity array on the caller thread (hoisted intersect —
     *       avoids per-entity binary searches inside the hot parallel path).
     *   <li>Split that sorted array into at most {@code parallelism} contiguous chunks.
     *   <li>Submit one task per chunk to the pool; each task runs the system over its slice
     *       via a {@link ChunkedView} that returns the precomputed slice in O(1).
     * </ol>
     * Opaque systems run synchronously on the caller thread regardless.
     *
     * <p>DETERMINISM LOAD-BEARING: chunks are contiguous in-order slices of the sorted
     * entity array, collected back in submission order. The commit sort
     * {@code (priority, systemId, entityId, type)} then reproduces the same total order
     * at every core count, making execution order invisible to results.
     */
    private List<CommitBuffer> runPhaseParallel(List<Registration> inPhase, List<Object> inbox) {
        List<Future<CommitBuffer>> futures = new ArrayList<>();

        for (Registration reg : inPhase) {
            if (reg.access().isOpaque()) {
                futures.add(CompletableFuture.completedFuture(executeSystem(reg, inbox)));
            } else {
                // Hoist entity-type intersection to caller thread.
                long[] matching = precomputeMatchingEntities(reg);
                int m = matching.length;
                if (m == 0) continue;
                int chunks    = Math.min(parallelism, m);
                int chunkSize = (m + chunks - 1) / chunks;
                for (int start = 0; start < m; start += chunkSize) {
                    long[] chunk = Arrays.copyOfRange(matching, start, Math.min(start + chunkSize, m));
                    futures.add(pool.submit(() -> executeSystemChunk(reg, chunk, inbox)));
                }
            }
        }

        List<CommitBuffer> buffers = new ArrayList<>(futures.size());
        for (Future<CommitBuffer> f : futures) {
            try {
                buffers.add(f.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Scheduler interrupted during parallel phase execution", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) throw re;
                if (cause instanceof Error err) throw err;
                throw new RuntimeException("System threw during parallel phase execution", cause);
            }
        }
        return buffers;
    }

    /**
     * Precompute the entity IDs that match this system's full declared access (reads ∪ writes).
     * Run on the caller thread before chunk submission so workers pay O(1) for query().
     */
    @SuppressWarnings("unchecked")
    private long[] precomputeMatchingEntities(Registration reg) {
        Set<Class<? extends Component>> all = new LinkedHashSet<>(reg.access().reads());
        all.addAll(reg.access().writes());
        if (all.isEmpty()) return store.entityIds();
        return store.query(all.toArray(new Class[0]));
    }

    /**
     * Execute a system over the full entity set (serial path and opaque parallel path).
     * Sets {@code IN_SYSTEM_THREAD} only for declared (non-opaque) systems.
     */
    private CommitBuffer executeSystem(Registration reg, List<Object> inbox) {
        LatticeRng   rng = LatticeRng.forSystem(worldSeed, tickCount, reg.id());
        CommitBuffer buf = new CommitBuffer(reg.id(), reg.priority(), reg.access());
        boolean isDeclared = !reg.access().isOpaque();
        if (isDeclared) LatticeRng.IN_SYSTEM_THREAD.set(Boolean.TRUE);
        try {
            reg.system().run(new SnapshotView(store, reg.access(), rng, inbox), buf);
        } finally {
            if (isDeclared) LatticeRng.IN_SYSTEM_THREAD.remove();
        }
        return buf;
    }

    /**
     * Execute a system over a precomputed entity chunk (entity-range parallel path).
     * Always a declared (non-opaque) system, so IN_SYSTEM_THREAD is always set.
     */
    private CommitBuffer executeSystemChunk(Registration reg, long[] chunkEntities, List<Object> inbox) {
        LatticeRng   rng = LatticeRng.forSystem(worldSeed, tickCount, reg.id());
        CommitBuffer buf = new CommitBuffer(reg.id(), reg.priority(), reg.access());
        LatticeRng.IN_SYSTEM_THREAD.set(Boolean.TRUE);
        try {
            reg.system().run(new ChunkedView(store, reg.access(), rng, chunkEntities, inbox), buf);
        } finally {
            LatticeRng.IN_SYSTEM_THREAD.remove();
        }
        return buf;
    }

    /**
     * Detect unresolved conflicts among DECLARED systems in the same phase.
     * Opaque systems are always serial and may coexist in any phase freely.
     * A conflict between two declared systems is a hard error (§3.4).
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

        // 3. Kills — trump writes
        List<CommitBuffer.KillOp> allKills = new ArrayList<>();
        for (CommitBuffer buf : buffers) allKills.addAll(buf.kills());
        allKills.sort(Comparator
            .comparingInt(CommitBuffer.KillOp::systemPriority)
            .thenComparing(CommitBuffer.KillOp::systemId)
            .thenComparingLong(CommitBuffer.KillOp::entity));
        for (CommitBuffer.KillOp op : allKills) {
            store.kill(op.entity());
        }

        // 4. Cross-region messages — accumulate for drainMessages(); not applied locally
        for (CommitBuffer buf : buffers) {
            pendingMessages.addAll(buf.messages());
        }
    }
}
