# Lattice

A greenfield, multicore-native Minecraft Java mod loader.

Lattice is **not** a Fabric/Forge-compatible loader. It deliberately abandons that ecosystem in
exchange for a new, borrow-checker-grade concurrency contract: a declared access system that makes
data races *unrepresentable* rather than merely *unobserved*, combined with a compute/commit phase
split that gives you core-count-invariant determinism under full parallelism.

See [`lattice-design.md`](lattice-design.md) for the full architecture and design rationale.

## Status

**Phase 0 — Host preparation.** Standing up the decompile → remap → patch → recompile pipeline
for Minecraft 26.1. No game logic yet.

## Roadmap

| Phase | Description | Status |
|---|---|---|
| 0 | Host preparation (decompile pipeline) | In progress |
| 1 | Serial correctness skeleton (ECS + phase scheduler) | Not started |
| 2 | DeterminismHarness + RNG/registry cleanse | Not started |
| 3 | Intra-region system parallelism (axis B) | Not started |
| 4 | Inter-region parallelism (axis A) | Not started |
| 5 | Native worldgen acceleration | Not started |
| 6 | Block-update cascade + redstone partition hook | Not started |

## Building

> Phase 0 in progress — build instructions will appear here once the pipeline is working.

**Prerequisites:** JDK 25 or newer (JDK 26 recommended).

```bash
./gradlew decompile    # download + decompile MC 26.1 (output is gitignored)
./gradlew applyPatches # apply Lattice patches to the decompiled working tree
./gradlew build        # compile
```

## Legal

This repository contains **only** build tooling, patch files, and Lattice's own source code.
Decompiled Minecraft source is never committed here. It is generated locally as a build step by
downloading the official Minecraft server jar from Mojang and decompiling it on your machine.
