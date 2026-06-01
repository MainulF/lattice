package io.github.mainulf.lattice.ecs;

/**
 * Marker interface for ECS component types. Implementations should be immutable records.
 * Mutable components break snapshot isolation and the compute/commit contract.
 */
public interface Component {}
