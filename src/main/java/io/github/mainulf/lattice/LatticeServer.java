package io.github.mainulf.lattice;

import io.github.mainulf.lattice.ecs.*;

/**
 * Integration point between the Lattice ECS engine and the vanilla MC server.
 *
 * <h2>Phase 1 strategy</h2>
 * The entire vanilla tick is wrapped as one {@link Access#OPAQUE} system, registered
 * <em>once</em> in the constructor. Each call to {@link #tick(Runnable)} swaps in the
 * current tick's closure and runs the scheduler. This means additional declared systems
 * registered on {@link #scheduler()} are preserved across ticks and compose correctly
 * with the opaque wrapper as subsystems are carved out.
 *
 * <h2>Migration path</h2>
 * Each ported subsystem follows this pattern:
 * <ol>
 *   <li>Remove that work from the vanilla-tick opaque system (patch {@code work/server/}).</li>
 *   <li>Register a declared {@link LatticeSystem} with a real {@link Access} in its place.</li>
 *   <li>Diff {@link WorldStateHasher} output — identical hash = correct port.</li>
 * </ol>
 *
 * <h2>MC integration (patch still needed)</h2>
 * Patch {@code work/server/net/minecraft/server/MinecraftServer.java}:
 * <pre>{@code
 * // Add field:
 * private final LatticeServer lattice = new LatticeServer();
 *
 * // In tickServer(), replace:
 * //   this.tickChildren(haveTime);
 * // with:
 *     this.lattice.tick(() -> this.tickChildren(haveTime));
 * }</pre>
 * Workflow: edit {@code work/server/}, commit, {@code ./gradlew rebuildPatches}.
 */
public final class LatticeServer {

    private final ComponentStore store;
    private final PhaseScheduler scheduler;

    // The closure for the current tick, swapped by tick() before the scheduler runs.
    private Runnable currentVanillaTick = () -> {};

    public LatticeServer() {
        this.store     = new ComponentStore();
        this.scheduler = new PhaseScheduler(store);
        // Register ONCE. As subsystems are carved out this opaque system shrinks,
        // replaced by declared LatticeSystem registrations on the same scheduler.
        scheduler.register(
            "vanilla-tick",
            (view, commit) -> currentVanillaTick.run(),
            Access.OPAQUE,
            Phase.MOVEMENT,
            0
        );
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
     * Hash the current ECS world state. Empty in Phase 1 (components migrate in
     * as subsystems are carved out of the opaque vanilla-tick wrapper).
     */
    @SafeVarargs
    public final long worldStateHash(Class<? extends Component>... componentTypes) {
        return WorldStateHasher.hash(store, componentTypes);
    }

    public ComponentStore store() {
        return store;
    }

    public PhaseScheduler scheduler() {
        return scheduler;
    }
}
