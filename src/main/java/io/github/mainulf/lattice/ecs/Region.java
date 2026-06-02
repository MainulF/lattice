package io.github.mainulf.lattice.ecs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A simulation region: owns one {@link ComponentStore} and one {@link PhaseScheduler}.
 * Entities in this region are ticked by its scheduler.
 *
 * <p>The {@link RegionCoordinator} drives the per-tick loop:
 * <ol>
 *   <li>{@link RegionCoordinator#tick()} ticks all regions (potentially in parallel — axis A).
 *   <li>After all regions complete, the coordinator drains their messages and routes them
 *       to target region inboxes via {@link #deliverMessages}.
 *   <li>Next tick: systems in this region read delivered messages via {@code view.inbox()}.
 * </ol>
 *
 * <p>Implements {@link AutoCloseable} — closes the underlying scheduler (shuts down its
 * thread pool, if any).
 */
public final class Region implements AutoCloseable {

    private final long id;
    private final ComponentStore store;
    private final PhaseScheduler scheduler;

    /**
     * Messages staged by {@link #deliverMessages} for delivery at the start of the
     * next tick. Populated by the coordinator; consumed by {@link #tick()}.
     */
    private List<Object> pendingInbox = new ArrayList<>();

    public Region(long id, ComponentStore store, PhaseScheduler scheduler) {
        this.id        = id;
        this.store     = store;
        this.scheduler = scheduler;
    }

    public long id() { return id; }
    public ComponentStore store() { return store; }
    public PhaseScheduler scheduler() { return scheduler; }

    /**
     * Deliver messages to this region's inbox. Called by the coordinator after all
     * regions have completed their tick, so messages are visible to systems next tick.
     * May be called multiple times (each call appends); all payloads are merged into
     * the same inbox for the next tick.
     */
    public void deliverMessages(List<Object> messages) {
        pendingInbox.addAll(messages);
    }

    /**
     * Run one tick. Passes the pending inbox (populated by {@link #deliverMessages}
     * since the last tick) to the scheduler, then resets the inbox.
     * Thread-safe for the tick itself; {@link #deliverMessages} must not be called
     * concurrently with this method.
     */
    public void tick() {
        List<Object> inbox = Collections.unmodifiableList(pendingInbox);
        pendingInbox = new ArrayList<>();
        scheduler.setInbox(inbox);
        scheduler.tick();
    }

    /**
     * Drain messages staged during the last tick for cross-region routing.
     * Delegates to the underlying {@link PhaseScheduler#drainMessages()}.
     */
    public List<CommitBuffer.MessageOp> drainMessages() {
        return scheduler.drainMessages();
    }

    @Override
    public void close() {
        scheduler.close();
    }
}
