package io.github.mainulf.lattice;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Hashes vanilla MC world state for the Phase-1 replay-diff oracle (§3.5).
 *
 * <p>All entities across all supplied levels are pooled, sorted by stable network
 * ID (assigned in spawn order, reset per-level-load), then hashed position and
 * velocity at full 64-bit double precision via FNV-1a.
 *
 * <p>Fields hashed per entity: {@code getId()}, {@code getX/Y/Z()},
 * {@code getDeltaMovement().x/y/z}.  Velocity is included because a movement
 * subsystem port that gets position right but corrupts residual velocity should
 * still show up as a divergence.
 *
 * <p>Stability preconditions: fixed seed, no mob spawning, no random-tick
 * sources, no players — see the Phase-1 scenario design in HANDOFF.md.
 */
public final class VanillaStateHasher {

    private static final long FNV_PRIME  = 0x00000100000001B3L;
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;

    private VanillaStateHasher() {}

    /**
     * Hash entity state across all supplied levels.
     */
    public static long hash(Iterable<? extends ServerLevel> levels) {
        List<Entity> entities = new ArrayList<>();
        for (ServerLevel level : levels) {
            for (Entity e : level.getAllEntities()) {
                entities.add(e);
            }
        }
        entities.sort((a, b) -> Integer.compare(a.getId(), b.getId()));

        long h = FNV_OFFSET;
        for (Entity e : entities) {
            h = fnv(h, e.getId());
            h = fnv(h, Double.doubleToRawLongBits(e.getX()));
            h = fnv(h, Double.doubleToRawLongBits(e.getY()));
            h = fnv(h, Double.doubleToRawLongBits(e.getZ()));
            Vec3 v = e.getDeltaMovement();
            h = fnv(h, Double.doubleToRawLongBits(v.x));
            h = fnv(h, Double.doubleToRawLongBits(v.y));
            h = fnv(h, Double.doubleToRawLongBits(v.z));
        }
        return h;
    }

    private static long fnv(long hash, long value) {
        hash ^= value;
        return hash * FNV_PRIME;
    }
}
