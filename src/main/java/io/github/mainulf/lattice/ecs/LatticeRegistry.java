package io.github.mainulf.lattice.ecs;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Immutable-after-freeze registry (§2.7).
 *
 * <p>During the bootstrap phase entries are added via {@link #register}. Once
 * {@link #freeze()} is called the registry becomes deeply read-only: any subsequent
 * registration attempt throws {@link IllegalStateException}. The frozen registry is
 * freely shareable across all threads with zero synchronisation.
 *
 * <p>Two additional invariants are enforced at the point of registration:
 * <ul>
 *   <li>Registration from a Lattice system thread is always forbidden — registries
 *       must be fully populated before the first tick begins (§2.7).</li>
 *   <li>Duplicate keys throw immediately rather than silently overwriting.</li>
 * </ul>
 *
 * @param <K> registry key type (e.g. {@code ResourceLocation})
 * @param <V> registry value type (e.g. block, item, biome descriptor)
 */
public final class LatticeRegistry<K, V> {

    private final Map<K, V> entries = new LinkedHashMap<>();
    private volatile boolean frozen = false;

    /** Register a key → value mapping. Throws if already frozen or from a system thread. */
    public void register(K key, V value) {
        checkMutable();
        if (entries.containsKey(key)) {
            throw new IllegalArgumentException("Duplicate registry key: " + key);
        }
        entries.put(key, value);
    }

    /**
     * Freeze the registry. After this call all writes throw; reads are lock-free.
     * Safe to call multiple times (idempotent).
     */
    public void freeze() {
        frozen = true;
    }

    /** Lookup a value by key. Always safe, including from system threads. */
    public V get(K key) {
        return entries.get(key);
    }

    /** Returns {@code true} if the key is registered. */
    public boolean contains(K key) {
        return entries.containsKey(key);
    }

    /**
     * Returns an unmodifiable view of all registered keys.
     * Safe to call from any thread after freezing.
     */
    public Set<K> keys() {
        return Collections.unmodifiableSet(entries.keySet());
    }

    /** Returns {@code true} if the registry has been frozen. */
    public boolean isFrozen() {
        return frozen;
    }

    private void checkMutable() {
        if (LatticeRng.isInSystemThread()) {
            throw new IllegalStateException(
                "Registry mutation from a Lattice system thread is forbidden (§2.7). " +
                "Register all entries during bootstrap, before the first tick.");
        }
        if (frozen) {
            throw new IllegalStateException(
                "Registry is frozen — no further registration is permitted (§2.7).");
        }
    }
}
