package io.github.mainulf.lattice;

import io.github.mainulf.lattice.ecs.*;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LatticeServerTest {

    record Pos(double x, double y, double z) implements Component {}

    // ── Opaque vanilla-tick seam ──────────────────────────────────────────────

    @Test
    void tick_invokesVanillaTickRunnable() {
        LatticeServer server = new LatticeServer();
        AtomicInteger callCount = new AtomicInteger();

        server.tick(callCount::incrementAndGet);
        server.tick(callCount::incrementAndGet);
        server.tick(callCount::incrementAndGet);

        assertEquals(3, callCount.get(), "vanilla tick runnable must be called once per tick");
    }

    @Test
    void tick_persistentSchedulerComposesWithExtraSystem() {
        LatticeServer server = new LatticeServer();

        // Register a real ECS system alongside the opaque vanilla-tick wrapper.
        // If LatticeServer created a throwaway scheduler each tick, this registration
        // would be silently ignored. Here we verify it is not.
        server.store().spawn(1L, new Pos(0, 0, 0));
        Access writePos = Access.builder().writes(Pos.class).build();
        server.scheduler().register("mover", (view, commit) ->
            commit.set(1L, new Pos(99, 0, 0)), writePos, Phase.COLLISION, 0);

        server.tick(() -> {}); // vanilla tick does nothing

        assertEquals(99.0, server.store().get(1L, Pos.class).x(),
            "system registered on persistent scheduler must run each tick");
    }

    @Test
    void tick_runnableSwappedEachTick() {
        LatticeServer server = new LatticeServer();
        AtomicInteger counter = new AtomicInteger();

        server.tick(() -> counter.addAndGet(1));
        server.tick(() -> counter.addAndGet(10));
        server.tick(() -> counter.addAndGet(100));

        assertEquals(111, counter.get(), "each tick must use the current runnable");
    }

    // ── WorldStateHasher precision ────────────────────────────────────────────

    /**
     * Two positions that differ only in the 53rd bit of the double (below Float precision).
     * Java record hashCode folds to 32 bits and would see them as equal under some inputs;
     * the record-reflection hasher must distinguish them at full 64-bit precision.
     */
    @Test
    void worldStateHasher_distinguishesSubFloatDifference() {
        ComponentStore storeA = new ComponentStore();
        ComponentStore storeB = new ComponentStore();

        double base = 1.0;
        double tiny = Math.ulp(base); // one ULP difference

        storeA.spawn(1L, new Pos(base, 0, 0));
        storeB.spawn(1L, new Pos(base + tiny, 0, 0));

        long hashA = WorldStateHasher.hash(storeA, Pos.class);
        long hashB = WorldStateHasher.hash(storeB, Pos.class);

        assertNotEquals(hashA, hashB,
            "hasher must distinguish positions differing by 1 ULP");
    }
}
