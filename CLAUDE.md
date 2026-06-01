# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

**Phase 0 complete.** The host-preparation pipeline is working. GitHub repo is live at
https://github.com/MainulF/lattice.

**Real build commands (Phase 0):**
```bash
./gradlew decompile        # download + decompile MC 26.1 (first run: ~2min, caches result)
./gradlew applyPatches     # extract decompiled sources to work/server/, apply patches/server/*.patch
./gradlew rebuildPatches   # regenerate patches/server/*.patch from commits above 'vanilla' tag
./gradlew build            # compile Lattice's own source (src/)
./gradlew runServer        # run vanilla-parity MC 26.1 server (run/runServer/ is the working dir)
```

**Note:** First time running `runServer`, VanillaGradle creates `run/runServer/eula.txt`. Edit it
to `eula=true` before re-running. `run/` is gitignored.

The design doc (`lattice-design.md`) is still the authoritative spec. On any conflict between
this file and the design doc, **the design doc wins**.

## What Lattice is

A greenfield, multicore-native Minecraft Java mod loader. It is **not** a Fabric/Forge-compatible loader — it deliberately abandons that ecosystem in exchange for a new, borrow-checker-grade concurrency contract. The thesis: occupy the box no prior system does — **A (spatial parallelism) + B (system parallelism) + C (off-tick parallelism) + D (determinism)**, on a host engine made thread-safe, behind a declared access contract that makes data races *unrepresentable* rather than merely *unobserved*.

Prior art it synthesizes (design §0): Folia (axis A, ownership-not-locking), Bevy ECS (axis B, static access-set contract), C2ME (axis C, generation/IO + detector instinct), Lithium/Starlight (reduce the serial fraction; remove global mutable state and parallelism falls out).

## The load-bearing constraint: the determinism contract (§1)

Everything else is downstream of this. **Do not weaken it without surfacing the tradeoff explicitly.**

- **Determinism strength = core-count- and schedule-invariant on a fixed binary** (§1.1, option 2). Same seed + inputs → identical world state regardless of thread count or dispatch order. *Not* cross-machine bit-identical (rejected as ruinous); *not* eventual consistency (accepted only in fenced zones like worldgen).
- **Compute/commit phase split (§1.2)** is the mechanism. A system is a pure function `(frozen immutable snapshot, derived rng) -> write_set`. **Compute:** every system reads the *same pre-phase snapshot* and stages writes thread-locally — no system observes another's writes mid-phase. **Commit:** staged writes apply in a *deterministic total order* `(phase index, system priority, region id, entity id, component id)`, last-writer-wins under that order.
- **Sequential intra-tick dependencies are expressed by separate phases**, not by reading live writes (§1.3). Default phase pipeline: `INPUT → AI → MOVEMENT → COLLISION → BLOCK_FX → LIGHTING → NETWORK`. More phases = more faithful vanilla ordering, less parallelism. This dial has no free setting.
- **RNG (§1.4):** no shared cursor. Each system instance gets a splittable stream keyed by `(worldSeed, regionId|entityId, tick, systemId)` via `SplittableGenerator` (L64X / `SplittableRandom`). Keep a C2ME/UWRAD-style detector in dev builds that hard-errors on any access to a global generator from a system thread.
- **Floating point (§1.5):** `+ - * /` are fine (strictfp mandatory since JDK 17). Use `StrictMath` (not `Math`) for `sin/cos/exp/...` on the tick path. **SIMD (Vector API) and GPU are forbidden in the deterministic tick path** — legal only in fenced determinism-tolerant zones (§4).
- **The hardest invariant (§1.6):** region split/merge may change *scheduling* but must never change *results*. A cross-region interaction must be behaviorally identical to the same interaction intra-region (same one-tick latency, same commit order, same RNG keying). Test target from day one.

## Mod-facing API model (§3)

The access declaration is the only thing the modder writes that the scheduler reads, and the handle physically cannot reach outside the declaration (Rust borrow-checker philosophy made concrete).

- **Component** — plain data struct, stored in archetype columns (SoA), region-owned.
- **System** — `(View, Commit) -> void` plus a static `Access` (declared `reads`/`writes` of component types) and a `Phase`.
- **View** — read handle, exposes only declared read-set, scoped to one region, serves the frozen snapshot.
- **Commit** — only write path; stages writes, never mutates live state. Cross-region work is `commit.message(targetRegion, msg)`, delivered next tick.
- **Enforcement gradient (§3.3) — the porting on-ramp:** undeclared system → `Access.opaque()` → runs serial on the global thread with full world access (slow, always correct, works the instant you port). Declared system → eligible for parallel scheduling against disjoint systems. By the contract, world state is identical at every step, so each annotation is a *pure optimisation verifiable by replay-diff*.
- **Unresolved intra-phase conflicts are a build error** (§3.4), unlike Bevy which ships ambiguities unresolved — shipping one would violate the contract.

## Subsystem cleanse method (§2)

Uniform method (the Starlight lesson generalised): **don't make structures thread-safe; remove the shared mutable state so thread-safety is unnecessary.** Single-owner, not locks. Key moves: dissolve `Level` into per-region world-views; ECS archetype columns are single-writer by construction (keeps fastutil single-thread speed, deletes the concurrency problem rather than solving it); stateless Starlight-style lighting; region-owned scheduled-tick queues drained in a phase; immutable post-startup registries; event bus split into read-only observation events (stage via Commit) and deterministically-ordered decision events.

**The redstone wall (§2.5) is explicitly not solved:** a coupled redstone/logistics network is a serial global reduction. The engine parallelises *across* independent networks and exposes a partition hook for mods that know their decomposition; *within* one network it stays serial, by the nature of the problem.

## Native/GPU acceleration (§4) — fenced and optional

- Only contract-legal target for now: **worldgen noise** (flat coords → density field, already determinism-tolerant per C2ME). Nothing in the tick path qualifies.
- Substrate: **FFM (JEP 454, final in JDK 22)** — safe to hard-depend on; `Arena`/`MemorySegment` off-heap SoA buffers sharing the ECS column layout.
- **Every kernel must have a scalar Java reference implementation** that is both fallback and correctness oracle. Native/GPU selected by runtime capability probe (absence is normal).
- **Vector API is a soft dependency only** (still incubating, JEP 529, not bit-identical to scalar) — never load-bearing for the tick path.

## Roadmap (§5) — correctness and the determinism harness come before any parallelism

0. Host prep — Paperweight-style decompile→remap→patch→recompile to a buildable deobfuscated server on Mojang mappings. No threading.
1. Serial correctness skeleton — ECS + phase scheduler + Commit, everything serial via the undeclared path; replay-diff against the Phase-0 baseline (this is the determinism oracle).
2. `DeterminismHarness` (§3.5) + RNG/registry cleanse + cross-region message path; test §1.6 immediately.
3. Intra-region system parallelism (axis B) + entity-column / lighting cleanse.
4. Inter-region parallelism (axis A) — region ownership, dynamic split/merge, `GlobalTickThread`.
5. Native worldgen acceleration.
6. Block-update cascade into phases + redstone partition hook.

**Proof-of-concept workload:** fixed-seed entity arena (~10k simple entities) ticking gravity/movement/collision/AI on a scripted input track. Headline = two curves: MSPT vs core count (expect ~2–4× on dense scenes, *not* linear — this is the honest Amdahl ceiling), and world-state-hash vs core count (a *flat line* — identical at every core count). The flat line is the whole thesis.

## Working norms for this repo

- The `DeterminismHarness` (replay a scenario at 1/2/4/N cores, assert identical world-state hash each tick) is the single most important tool — treat any divergence as a contract violation (a system lied about its access, an RNG stream leaked, or a cross-region path diverged from its intra-region twin), not as flakiness.
- Implementation language is Java; target a pinned modern JDK (≥22 for FFM). Confirm the exact JDK before adding toolchain config.
