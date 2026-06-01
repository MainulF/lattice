package io.github.mainulf.lattice.ecs;

/**
 * Carved movement for item entities falling in air (Phase 1 scenario).
 *
 * <p>Replicates the exact operation order from {@code ItemEntity.tick()} for the air-fall
 * branch (no fluid, not on ground, no horizontal motion):
 * <ol>
 *   <li>Apply gravity: {@code vy -= 0.04} (post-gravity velocity)</li>
 *   <li>Move: {@code pos += (vx, vy_post_gravity, vz)} — position uses post-gravity velocity</li>
 *   <li>Apply friction: {@code vx/vz *= 0.98f} (float precision, matches vanilla), {@code vy *= 0.98}</li>
 * </ol>
 *
 * <p>Both Position and Velocity are committed so friction accumulates correctly across ticks.
 * Phase 1 scope only: collision, fluid, and onGround bounce are not implemented here.
 */
@SystemDef(phase = Phase.MOVEMENT, priority = 50)
public final class MovementSystem implements LatticeSystem {

    private static final double GRAVITY     = 0.04;
    private static final double FRICTION_Y  = 0.98;
    private static final float  FRICTION_XZ = 0.98F; // matches vanilla: float literal in ItemEntity

    public static final Access ACCESS = Access.builder()
        .reads(Position.class, Velocity.class)
        .writes(Position.class, Velocity.class)
        .build();

    @Override
    public void run(View view, Commit commit) {
        for (long id : view.query(Position.class, Velocity.class)) {
            Position pos = view.get(id, Position.class);
            Velocity vel = view.get(id, Velocity.class);

            // 1. Gravity
            double vy = vel.dy() - GRAVITY;

            // 2. Move by post-gravity velocity (no collision in Phase 1 air-fall scenario)
            double nx = pos.x() + vel.dx();
            double ny = pos.y() + vy;
            double nz = pos.z() + vel.dz();

            // 3. Friction: float cast matches ItemEntity's `float friction = 0.98F` passed to Vec3.multiply
            double fx = vel.dx() * FRICTION_XZ;
            double fy = vy * FRICTION_Y;
            double fz = vel.dz() * FRICTION_XZ;

            commit.set(id, new Position(nx, ny, nz));
            commit.set(id, new Velocity(fx, fy, fz));
        }
    }
}
