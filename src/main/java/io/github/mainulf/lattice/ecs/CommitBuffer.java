package io.github.mainulf.lattice.ecs;

import java.util.ArrayList;
import java.util.List;

/**
 * Accumulates staged writes from one system execution. Applied to the live store
 * after all systems in the phase have run, in deterministic order (§1.2).
 */
final class CommitBuffer implements Commit {

    record SetOp(String systemId, int systemPriority, long entity, Component value) {}
    record RemoveOp(String systemId, int systemPriority, long entity, Class<? extends Component> type) {}
    record SpawnOp(long entity, Component[] components) {}
    record KillOp(String systemId, int systemPriority, long entity) {}

    private final String systemId;
    private final int    systemPriority;
    private final Access access;

    private final List<SetOp>    sets    = new ArrayList<>();
    private final List<RemoveOp> removes = new ArrayList<>();
    private final List<SpawnOp>  spawns  = new ArrayList<>();
    private final List<KillOp>   kills   = new ArrayList<>();

    CommitBuffer(String systemId, int systemPriority, Access access) {
        this.systemId       = systemId;
        this.systemPriority = systemPriority;
        this.access         = access;
    }

    @Override
    public <C extends Component> void set(long entity, C component) {
        if (!access.isOpaque() && !access.writes().contains(component.getClass())) {
            throw new IllegalAccessError(
                "System '" + systemId + "' did not declare write access to " +
                component.getClass().getSimpleName());
        }
        sets.add(new SetOp(systemId, systemPriority, entity, component));
    }

    @Override
    public void remove(long entity, Class<? extends Component> type) {
        removes.add(new RemoveOp(systemId, systemPriority, entity, type));
    }

    @Override
    public void spawn(long entity, Component... initialComponents) {
        spawns.add(new SpawnOp(entity, initialComponents.clone()));
    }

    @Override
    public void kill(long entity) {
        kills.add(new KillOp(systemId, systemPriority, entity));
    }

    List<SetOp>    sets()    { return sets;    }
    List<RemoveOp> removes() { return removes; }
    List<SpawnOp>  spawns()  { return spawns;  }
    List<KillOp>   kills()   { return kills;   }
}
