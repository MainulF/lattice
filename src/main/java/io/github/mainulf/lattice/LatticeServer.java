package io.github.mainulf.lattice;

import io.github.mainulf.lattice.ecs.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Integration point between the Lattice ECS engine and the vanilla MC server.
 *
 * <h2>Phase 1 strategy</h2>
 * The entire vanilla tick is wrapped as one {@link Access#OPAQUE} system, registered
 * <em>once</em> in the constructor. Each call to {@link #tick(Runnable)} swaps in the
 * current tick's closure and runs the scheduler. Additional declared systems registered
 * on the scheduler are preserved across ticks and compose correctly with the opaque wrapper.
 *
 * <h2>Carved movement (Phase 1)</h2>
 * When {@link #MOVEMENT_CARVED} is set (via {@code -Dlattice.movementCarved}):
 * <ol>
 *   <li>Call {@link #syncFromMC(Iterable)} before {@link #tick(Runnable)} to load
 *       current MC positions/velocities into the ECS store.</li>
 *   <li>After {@link #tick(Runnable)}, call {@link #applyToMC(Iterable)} to write the
 *       ECS-computed positions/velocities back to MC entities.</li>
 *   <li>{@code ItemEntity.tick()} skips {@code applyGravity()} and the move/friction
 *       block when {@code MOVEMENT_CARVED} is set.</li>
 * </ol>
 *
 * <h2>Migration path</h2>
 * Each ported subsystem follows this pattern:
 * <ol>
 *   <li>Remove that work from the vanilla-tick opaque system (patch {@code work/server/}).</li>
 *   <li>Register a declared {@link LatticeSystem} with a real {@link Access} in its place.</li>
 *   <li>Diff {@link WorldStateHasher} output — identical hash = correct port.</li>
 * </ol>
 */
public final class LatticeServer {

    /** Set via {@code -Dlattice.movementCarved} to enable the MovementSystem carve. */
    public static final boolean MOVEMENT_CARVED =
        System.getProperty("lattice.movementCarved") != null;

    private final ComponentStore store;
    private final PhaseScheduler scheduler;

    private Runnable currentVanillaTick = () -> {};

    public LatticeServer() {
        this.store     = new ComponentStore();
        this.scheduler = new PhaseScheduler(store);
        scheduler.register(
            "vanilla-tick",
            (view, commit) -> currentVanillaTick.run(),
            Access.OPAQUE,
            Phase.MOVEMENT,
            0
        );
        if (MOVEMENT_CARVED) {
            scheduler.register("movement", new MovementSystem(), MovementSystem.ACCESS,
                Phase.MOVEMENT, 50);
        }
    }

    /**
     * Run one tick. {@code vanillaTick} wraps the current tick's
     * {@code tickChildren(haveTime)} call.
     */
    public void tick(Runnable vanillaTick) {
        this.currentVanillaTick = vanillaTick;
        scheduler.tick();
    }

    /**
     * Sync current MC entity positions/velocities into the ECS store.
     * Call this BEFORE {@link #tick(Runnable)} so the MOVEMENT-phase snapshot
     * sees fresh data. Safe to call every tick — idempotent for existing entities.
     */
    public void syncFromMC(Iterable<? extends Entity> entities) {
        for (Entity e : entities) {
            long id = e.getId();
            Vec3 v = e.getDeltaMovement();
            store.spawn(id,
                new Position(e.getX(), e.getY(), e.getZ()),
                new Velocity(v.x, v.y, v.z));
        }
    }

    /**
     * Write ECS-computed positions and velocities back to MC entities.
     * Call this AFTER {@link #tick(Runnable)} so entities reflect the committed values
     * before the remainder of {@code ItemEntity.tick()} runs.
     */
    public void applyToMC(Iterable<? extends Entity> entities) {
        for (Entity e : entities) {
            long id = e.getId();
            Position pos = store.get(id, Position.class);
            Velocity vel = store.get(id, Velocity.class);
            if (pos != null) e.setPos(pos.x(), pos.y(), pos.z());
            if (vel != null) e.setDeltaMovement(vel.dx(), vel.dy(), vel.dz());
        }
    }

    /**
     * Hash the current ECS world state.
     */
    @SafeVarargs
    public final long worldStateHash(Class<? extends Component>... componentTypes) {
        return WorldStateHasher.hash(store, componentTypes);
    }

    /**
     * Set the world seed used to key per-system RNG streams (§1.4).
     * Call this once at server startup (before the first tick) so declared systems that
     * draw from {@code view.rng()} get streams keyed by the actual world seed.
     * Wired from {@code MinecraftServer} via the Phase 3 patch.
     */
    public void setWorldSeed(long seed) {
        scheduler.setWorldSeed(seed);
    }

    public ComponentStore store() {
        return store;
    }

    public PhaseScheduler scheduler() {
        return scheduler;
    }
}
