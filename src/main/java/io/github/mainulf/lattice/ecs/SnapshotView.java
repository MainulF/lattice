package io.github.mainulf.lattice.ecs;

/** View backed by an immutable snapshot of the store taken before the phase began. */
final class SnapshotView implements View {

    private final ComponentStore snapshot;
    private final Access access;

    SnapshotView(ComponentStore snapshot, Access access) {
        this.snapshot = snapshot;
        this.access   = access;
    }

    @Override
    public <C extends Component> C get(long entity, Class<C> type) {
        checkRead(type);
        return snapshot.get(entity, type);
    }

    @Override
    public boolean has(long entity, Class<? extends Component> type) {
        checkRead(type);
        return snapshot.has(entity, type);
    }

    @Override
    @SafeVarargs
    public final long[] query(Class<? extends Component>... types) {
        for (Class<? extends Component> t : types) checkRead(t);
        return snapshot.query(types);
    }

    private void checkRead(Class<? extends Component> type) {
        if (!access.isOpaque() && !access.reads().contains(type) && !access.writes().contains(type)) {
            throw new IllegalAccessError(
                "System did not declare read access to " + type.getSimpleName() +
                " — add it to Access.builder().reads(" + type.getSimpleName() + ".class)");
        }
    }
}
