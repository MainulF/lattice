package io.github.mainulf.lattice.ecs;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Static access declaration that the scheduler reads without running the system.
 * Used to build the conflict graph and schedule parallel execution (Phase 3+).
 * In Phase 1 (serial) it is also used for dev-mode access enforcement at runtime.
 */
public final class Access {

    /** Opaque sentinel: conflicts with everything, runs serial on the global thread. */
    public static final Access OPAQUE = new Access(Collections.emptySet(), Collections.emptySet(), true);

    private final Set<Class<? extends Component>> reads;
    private final Set<Class<? extends Component>> writes;
    private final boolean opaque;

    private Access(Set<Class<? extends Component>> reads,
                   Set<Class<? extends Component>> writes,
                   boolean opaque) {
        this.reads = reads;
        this.writes = writes;
        this.opaque = opaque;
    }

    public boolean isOpaque() {
        return opaque;
    }

    public Set<Class<? extends Component>> reads() {
        return reads;
    }

    public Set<Class<? extends Component>> writes() {
        return writes;
    }

    /**
     * Two systems conflict if one writes a component type the other reads or writes.
     * Read/read never conflicts. Either opaque ⇒ conflict.
     */
    public boolean conflictsWith(Access other) {
        if (this.opaque || other.opaque) return true;
        for (var w : this.writes) {
            if (other.reads.contains(w) || other.writes.contains(w)) return true;
        }
        for (var w : other.writes) {
            if (this.reads.contains(w) || this.writes.contains(w)) return true;
        }
        return false;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Set<Class<? extends Component>> reads = new LinkedHashSet<>();
        private final Set<Class<? extends Component>> writes = new LinkedHashSet<>();

        @SafeVarargs
        public final Builder reads(Class<? extends Component>... types) {
            reads.addAll(Arrays.asList(types));
            return this;
        }

        @SafeVarargs
        public final Builder writes(Class<? extends Component>... types) {
            writes.addAll(Arrays.asList(types));
            return this;
        }

        public Access build() {
            return new Access(
                Collections.unmodifiableSet(new LinkedHashSet<>(reads)),
                Collections.unmodifiableSet(new LinkedHashSet<>(writes)),
                false
            );
        }
    }
}
