package io.github.mainulf.lattice.ecs;

import java.util.Arrays;

/**
 * Sorted (entity-id, value) column. Entities are always kept sorted ascending so
 * iteration order is deterministic and intersection is an O(n) merge walk.
 * Uses binary search for point lookups — O(log n).
 * No HashMap, no hash-iteration order anywhere (§1.2 determinism invariant).
 */
final class ComponentColumn<C> {

    private long[] ids;
    private Object[] values;
    private int size;

    ComponentColumn() {
        ids = new long[16];
        values = new Object[16];
        size = 0;
    }

    private ComponentColumn(long[] ids, Object[] values, int size) {
        this.ids = ids.clone();
        this.values = Arrays.copyOf(values, values.length);
        this.size = size;
    }

    ComponentColumn<C> snapshot() {
        return new ComponentColumn<>(ids, values, size);
    }

    @SuppressWarnings("unchecked")
    C get(long entity) {
        int idx = Arrays.binarySearch(ids, 0, size, entity);
        return idx < 0 ? null : (C) values[idx];
    }

    boolean has(long entity) {
        return Arrays.binarySearch(ids, 0, size, entity) >= 0;
    }

    void set(long entity, C value) {
        int idx = Arrays.binarySearch(ids, 0, size, entity);
        if (idx >= 0) {
            values[idx] = value;
            return;
        }
        int ins = -(idx + 1);
        ensureCapacity(size + 1);
        System.arraycopy(ids,    ins, ids,    ins + 1, size - ins);
        System.arraycopy(values, ins, values, ins + 1, size - ins);
        ids[ins]    = entity;
        values[ins] = value;
        size++;
    }

    void remove(long entity) {
        int idx = Arrays.binarySearch(ids, 0, size, entity);
        if (idx < 0) return;
        int tail = size - idx - 1;
        System.arraycopy(ids,    idx + 1, ids,    idx, tail);
        System.arraycopy(values, idx + 1, values, idx, tail);
        values[--size] = null;
    }

    int size() {
        return size;
    }

    long idAt(int i) {
        return ids[i];
    }

    /** Returns sorted entity IDs present in ALL given columns (merge-intersect, O(total size)). */
    static long[] intersect(ComponentColumn<?>[] columns) {
        if (columns.length == 0) return new long[0];

        // Iterate the smallest column, probe others via binary search.
        int minIdx = 0;
        for (int i = 1; i < columns.length; i++) {
            if (columns[i].size < columns[minIdx].size) minIdx = i;
        }
        ComponentColumn<?> smallest = columns[minIdx];

        long[] result = new long[smallest.size];
        int resultSize = 0;

        outer:
        for (int i = 0; i < smallest.size; i++) {
            long id = smallest.ids[i];
            for (int c = 0; c < columns.length; c++) {
                if (c == minIdx) continue;
                if (!columns[c].has(id)) continue outer;
            }
            result[resultSize++] = id;
        }
        return Arrays.copyOf(result, resultSize);
    }

    private void ensureCapacity(int needed) {
        if (needed <= ids.length) return;
        int cap = Math.max(needed, ids.length * 2);
        ids    = Arrays.copyOf(ids,    cap);
        values = Arrays.copyOf(values, cap);
    }
}
