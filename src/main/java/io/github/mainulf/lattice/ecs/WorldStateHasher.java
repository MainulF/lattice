package io.github.mainulf.lattice.ecs;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Produces a deterministic hash of the world state for use as a replay-diff oracle (§3.5).
 *
 * <h2>Hash guarantees</h2>
 * <ul>
 *   <li>Same inputs → same output every run (no identity hashCode, no HashMap iteration).
 *   <li>Entity iteration order is ID-sorted (guaranteed by {@link ComponentStore#entityIds()}).
 *   <li>Component type order is name-sorted, so call-site order doesn't matter.
 *   <li>Doubles are hashed at full 64-bit precision via {@link Double#doubleToRawLongBits}
 *       by reflecting over record components — no 64→32 fold.
 *   <li>NaN is NOT canonicalized ({@code doubleToRawLongBits} preserves payload bits).
 *       This is intentional: an accidental NaN in a position/velocity is a bug we want
 *       to detect, not silently fold to a canonical value.
 * </ul>
 *
 * <h2>Component type requirements</h2>
 * Components should be Java records. Record components (fields) are extracted via
 * {@link Class#getRecordComponents()} and hashed field-by-field with type-appropriate
 * precision. Non-record components fall back to {@code hashCode()} (32-bit fold —
 * acceptable only for non-numeric fields like String tags).
 *
 * <h2>Limitations in Phase 1</h2>
 * The ECS store is empty until subsystems are carved out of the vanilla tick. Use this
 * to hash ECS state as it grows. A separate vanilla-state hasher (over MC's
 * {@code ServerLevel} entity list) is needed for the Phase-1 replay-diff oracle.
 */
public final class WorldStateHasher {

    // FNV-1a 64-bit parameters
    private static final long FNV_PRIME  = 0x00000100000001B3L;
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;

    private WorldStateHasher() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Hash world state for the given component types. Component types are sorted
     * by canonical name so call-site order doesn't affect the result.
     */
    @SafeVarargs
    public static long hash(ComponentStore store, Class<? extends Component>... componentTypes) {
        return hash(store, List.of(componentTypes));
    }

    public static long hash(ComponentStore store, List<Class<? extends Component>> componentTypes) {
        Class<?>[] sorted = componentTypes.toArray(Class[]::new);
        Arrays.sort(sorted, Comparator.comparing(Class::getName));

        long h = FNV_OFFSET;
        for (long entityId : store.entityIds()) {
            h = fnv(h, entityId);
            for (Class<?> type : sorted) {
                @SuppressWarnings("unchecked")
                Component c = store.get(entityId, (Class<? extends Component>) type);
                h = fnv(h, c != null ? hashComponent(c) : 0L);
            }
        }
        return h;
    }

    // ── Component hashing ─────────────────────────────────────────────────────

    /**
     * Hash a single component at full field precision. Uses record reflection
     * to extract each field individually — doubles get 64-bit treatment, not
     * the 32-bit fold that {@link Object#hashCode} produces.
     */
    static long hashComponent(Component c) {
        long h = FNV_OFFSET;
        RecordComponent[] rcs = c.getClass().getRecordComponents();
        if (rcs == null) {
            // Non-record fallback: type name + hashCode (32-bit fold — acceptable for tags)
            h = fnv(h, c.getClass().getName().hashCode());
            return fnv(h, c.hashCode());
        }
        h = fnv(h, c.getClass().getName().hashCode()); // type tag for polymorphic safety
        for (RecordComponent rc : rcs) {
            try {
                var accessor = rc.getAccessor();
                accessor.setAccessible(true); // needed for non-public record types
                Object val = accessor.invoke(c);
                h = fnv(h, fieldHash(val));
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("Cannot hash record component " + rc.getName(), e);
            }
        }
        return h;
    }

    /**
     * Hash a single field value at full precision. Doubles and floats use raw bits
     * (no canonicalization of NaN). Strings use {@link String#hashCode} which is
     * deterministic by the Java spec. Unknown types fall back to {@code hashCode()}.
     */
    private static long fieldHash(Object val) {
        return switch (val) {
            case null          -> 0L;
            case Double  d     -> Double.doubleToRawLongBits(d);
            case Float   f     -> Float.floatToRawIntBits(f);
            case Long    l     -> l;
            case Integer i     -> i;
            case Short   s     -> s;
            case Byte    b     -> b;
            case Boolean b     -> b ? 1L : 0L;
            case String  s     -> s.hashCode(); // spec-guaranteed deterministic
            default            -> val.hashCode();
        };
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private static long fnv(long hash, long value) {
        hash ^= value;
        return hash * FNV_PRIME;
    }
}
