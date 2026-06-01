package io.github.mainulf.lattice.ecs;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-region component storage. One sorted {@link ComponentColumn} per component type,
 * created lazily on first use. Entity IDs are kept in a sorted long[] for deterministic
 * iteration. Single-writer by construction in Phase 1 (one region = one thread).
 */
public final class ComponentStore {

    // Insertion-ordered so snapshot() preserves column order deterministically.
    private final Map<Class<?>, ComponentColumn<?>> columns = new LinkedHashMap<>();

    private long[] entityIds = new long[64];
    private int entityCount  = 0;

    // ── Entity lifecycle ──────────────────────────────────────────────────────

    public void spawn(long entity, Component... components) {
        insertEntity(entity);
        for (Component c : components) {
            setRaw(entity, c);
        }
    }

    public void kill(long entity) {
        removeEntity(entity);
        for (ComponentColumn<?> col : columns.values()) {
            col.remove(entity);
        }
    }

    // ── Component access ──────────────────────────────────────────────────────

    public <C extends Component> void set(long entity, C component) {
        setRaw(entity, component);
    }

    public <C extends Component> C get(long entity, Class<C> type) {
        return column(type).get(entity);
    }

    public boolean has(long entity, Class<? extends Component> type) {
        ComponentColumn<?> col = columns.get(type);
        return col != null && col.has(entity);
    }

    public <C extends Component> void remove(long entity, Class<C> type) {
        ComponentColumn<?> col = columns.get(type);
        if (col != null) col.remove(entity);
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    /** Returns sorted entity IDs that have ALL of the requested component types. */
    @SafeVarargs
    public final long[] query(Class<? extends Component>... types) {
        if (types.length == 0) return Arrays.copyOf(entityIds, entityCount);
        ComponentColumn<?>[] cols = new ComponentColumn<?>[types.length];
        for (int i = 0; i < types.length; i++) {
            ComponentColumn<?> col = columns.get(types[i]);
            if (col == null || col.size() == 0) return new long[0];
            cols[i] = col;
        }
        return ComponentColumn.intersect(cols);
    }

    // ── Snapshot ──────────────────────────────────────────────────────────────

    /**
     * Returns a deep copy of all columns. The copy is independent — mutations to
     * either store after this call do not affect the other.
     */
    public ComponentStore snapshot() {
        ComponentStore snap = new ComponentStore();
        for (Map.Entry<Class<?>, ComponentColumn<?>> entry : columns.entrySet()) {
            snap.columns.put(entry.getKey(), entry.getValue().snapshot());
        }
        snap.entityIds    = Arrays.copyOf(entityIds, entityIds.length);
        snap.entityCount  = entityCount;
        return snap;
    }

    // ── Inspection ────────────────────────────────────────────────────────────

    public long[] entityIds() {
        return Arrays.copyOf(entityIds, entityCount);
    }

    public int entityCount() {
        return entityCount;
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private <C extends Component> void setRaw(long entity, C component) {
        Class<?> type = component.getClass();
        ComponentColumn<C> col = (ComponentColumn<C>) columns.computeIfAbsent(type, k -> new ComponentColumn<>());
        col.set(entity, component);
    }

    @SuppressWarnings("unchecked")
    private <C extends Component> ComponentColumn<C> column(Class<C> type) {
        return (ComponentColumn<C>) columns.computeIfAbsent(type, k -> new ComponentColumn<>());
    }

    private void insertEntity(long entity) {
        int idx = Arrays.binarySearch(entityIds, 0, entityCount, entity);
        if (idx >= 0) return;
        int ins = -(idx + 1);
        if (entityCount == entityIds.length) {
            entityIds = Arrays.copyOf(entityIds, entityIds.length * 2);
        }
        System.arraycopy(entityIds, ins, entityIds, ins + 1, entityCount - ins);
        entityIds[ins] = entity;
        entityCount++;
    }

    private void removeEntity(long entity) {
        int idx = Arrays.binarySearch(entityIds, 0, entityCount, entity);
        if (idx < 0) return;
        System.arraycopy(entityIds, idx + 1, entityIds, idx, entityCount - idx - 1);
        entityCount--;
    }
}
