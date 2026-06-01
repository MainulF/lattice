package io.github.mainulf.lattice.ecs;

/**
 * Tick phase pipeline. Systems in earlier phases commit before later phases begin,
 * expressing sequential intra-tick dependencies (§1.3). More phases = more faithful
 * vanilla ordering, less parallelism — this dial has no free setting.
 */
public enum Phase {
    INPUT,
    AI,
    MOVEMENT,
    COLLISION,
    BLOCK_FX,
    LIGHTING,
    NETWORK
}
