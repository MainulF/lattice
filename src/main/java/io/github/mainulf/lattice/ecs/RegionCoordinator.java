package io.github.mainulf.lattice.ecs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Coordinates ticking of multiple {@link Region}s and routes cross-region messages.
 *
 * <h2>Per-tick protocol</h2>
 * <ol>
 *   <li><b>Tick all regions</b> — if {@code parallelism > 1}, regions run concurrently on
 *       the coordinator's own pool (axis A). Each region uses its own scheduler pool for
 *       intra-region entity-range splitting (axis B). The two axes compose independently.
 *   <li><b>Global barrier</b> — all regions complete tick N before any message delivery for
 *       tick N+1. This is the determinism load-bearing barrier: it prevents any region from
 *       observing a tick-N message before every source region has staged its tick-N messages.
 *   <li><b>Drain messages</b> — regions are drained in sorted region-id order. This is
 *       the deterministic routing invariant: inbox delivery order at the target is a pure
 *       function of source region ids, never of thread arrival order.
 *   <li><b>Route to target inboxes</b> — messages are grouped by target region id and
 *       delivered via {@link Region#deliverMessages}. Self-messages (A messages its own
 *       region) are routed back identically — this is the §1.6 latency-symmetry mechanism:
 *       intra-region and cross-region interactions both incur one-tick deferred delivery.
 * </ol>
 *
 * <h2>§1.6 invariant (region boundaries must not change outcomes)</h2>
 * <p>If interaction A→B goes through {@code commit.message(targetRegionId, payload)} in
 * both the co-regional and cross-regional cases, the routing protocol above guarantees
 * identical one-tick latency, identical inbox ordering, and identical RNG keying
 * (per-entity streams are region-id-independent — see §1.4). The world state is therefore
 * identical regardless of whether A and B are in the same region or different ones.
 *
 * <p>Implements {@link AutoCloseable} — closes all registered regions and the coordinator pool.
 */
public final class RegionCoordinator implements AutoCloseable {

    /**
     * Regions in insertion order. Deterministic drain order is achieved by sorting region
     * ids at drain time, not by relying on insertion order.
     */
    private final Map<Long, Region> regions = new LinkedHashMap<>();

    private int parallelism = 1;
    private ExecutorService pool = null;

    /**
     * Register a region. Must be called before the first tick.
     * @throws IllegalArgumentException if a region with this id already exists.
     */
    public void addRegion(Region region) {
        if (regions.containsKey(region.id())) {
            throw new IllegalArgumentException("Duplicate region id: " + region.id());
        }
        regions.put(region.id(), region);
    }

    /**
     * Set the number of parallel threads for ticking regions concurrently (axis A).
     * {@code 1} (default) ticks regions serially on the calling thread.
     * Do not call mid-tick.
     */
    public void setParallelism(int cores) {
        if (cores < 1) throw new IllegalArgumentException("cores must be >= 1, got " + cores);
        if (pool != null) { pool.shutdown(); pool = null; }
        parallelism = cores;
        if (cores > 1) pool = Executors.newFixedThreadPool(cores);
    }

    /**
     * Run one tick across all regions: tick → barrier → drain → route.
     * See class-level javadoc for the protocol.
     */
    public void tick() {
        // 1. Tick all regions, possibly in parallel (axis A).
        if (pool != null) {
            List<Future<?>> futures = new ArrayList<>(regions.size());
            for (Region r : regions.values()) {
                futures.add(pool.submit(r::tick));
            }
            awaitAll(futures);
        } else {
            for (Region r : regions.values()) r.tick();
        }

        // 2. Drain messages in deterministic region-id order.
        List<Long> sortedIds = new ArrayList<>(regions.keySet());
        Collections.sort(sortedIds);

        List<CommitBuffer.MessageOp> allMessages = new ArrayList<>();
        for (long id : sortedIds) {
            allMessages.addAll(regions.get(id).drainMessages());
        }

        // 3. Route to target region inboxes, grouped by target.
        //    Self-messages (targetRegion == sourceRegion) are routed back identically —
        //    same one-tick latency as cross-region messages (§1.6 latency symmetry).
        Map<Long, List<Object>> byTarget = new LinkedHashMap<>();
        for (CommitBuffer.MessageOp msg : allMessages) {
            byTarget.computeIfAbsent(msg.targetRegion(), k -> new ArrayList<>())
                    .add(msg.payload());
        }
        for (Map.Entry<Long, List<Object>> entry : byTarget.entrySet()) {
            Region target = regions.get(entry.getKey());
            if (target != null) {
                target.deliverMessages(entry.getValue());
            }
            // Messages targeting unknown regions are silently dropped (region may not
            // exist yet during split/merge; Phase 4b will handle the gap window).
        }
    }

    @Override
    public void close() {
        if (pool != null) { pool.shutdown(); pool = null; }
        for (Region r : regions.values()) r.close();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static void awaitAll(List<Future<?>> futures) {
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Coordinator interrupted waiting for region tick", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) throw re;
                if (cause instanceof Error err) throw err;
                throw new RuntimeException("Region tick threw", cause);
            }
        }
    }
}
