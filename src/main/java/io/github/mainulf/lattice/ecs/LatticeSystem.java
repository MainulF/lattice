package io.github.mainulf.lattice.ecs;

/**
 * The unit of logic in the ECS. Implementors declare a static {@code ACCESS} field
 * and are annotated with {@link SystemDef}. The scheduler reads ACCESS without
 * running the system to build the conflict graph (§3.4).
 *
 * <pre>{@code
 * @SystemDef(phase = Phase.MOVEMENT, priority = 100)
 * public final class GravitySystem implements LatticeSystem {
 *     public static final Access ACCESS = Access.builder()
 *         .reads(Mass.class, Grounded.class)
 *         .writes(Velocity.class)
 *         .build();
 *
 *     @Override
 *     public void run(View view, Commit commit) { ... }
 * }
 * }</pre>
 */
public interface LatticeSystem {
    void run(View view, Commit commit);
}
