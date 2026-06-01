# Lattice — Design Document

A multicore-native Minecraft Java mod loader. Greenfield mod contract, thread-safe host engine, determinism-first.

---

## 0. Prior art: what each got right, where each stops

The useful frame is that every existing system solves exactly **one** of three orthogonal axes and deliberately refuses the others. Lattice's whole thesis is that you have to take all three at once, plus a fourth property none of them provide.

The three axes:

- **A — spatial parallelism**: tick disjoint regions of the world on different threads.
- **B — system parallelism**: within one region, run disjoint pieces of logic on different threads.
- **C — pipeline/off-tick parallelism**: move work that isn't the simulation tick (IO, generation) off the hot thread.

The fourth property: **D — determinism**, meaning the result does not depend on how many threads ran or in what order they were dispatched.

### Folia (Paper fork, regionised multithreading) — axis A, done seriously

Folia deletes the concept of a main thread and groups nearby loaded chunks into **independent regions**, each running its own full tick loop at 20 TPS on a thread pool. The correctness model is ownership, not locking: only the thread currently ticking a region may touch data owned by that region, enforced by `TickThread` ownership checks rather than mutexes. Operations that can't be regionalised (console, connection management, global schedulers) run on a single `GlobalTickThread` that owns nothing. Regions split and merge dynamically as players move, with a careful invariant — a ticking region may not grow, must own a buffer of chunks beyond its perimeter, and may not begin ticking adjacent to another ticking region, with adjacent regions forced to eventually merge. Cross-region work goes through deferred mechanisms: `EntityScheduler`, `RegionScheduler`, `RegionizedTaskQueue`.

**Got right:** ownership-eliminates-locking is the correct foundation. The "regions never share data directly, they communicate by deferral" rule is exactly the discipline a sound design needs. The dynamic split/merge invariant is genuinely hard and they solved it.

**Where it stops:** it is purely axis A. Inside a region everything is still one serial tick loop — a single region with one dense redstone build or one heavy player base gets you nothing. It only helps workloads that *naturally spread players out* (Skyblock, large SMP), which is why Paper itself says Folia is useless for most servers. It retrofits onto Paper's existing logic, so plugins break ad hoc and there is no machine-checkable access contract — correctness is enforced by runtime ownership assertions that *crash* on violation rather than a model that makes violations unrepresentable. And it makes no determinism claim at all.

### MCMT / MCMTFabric / JMT-MCMT (and descendants like Async) — axis B, done unsoundly

The honest pseudocode from the MCMT readme is the whole design:

```
for world in worlds:        # parallelise
  for chunk in world:       # parallelise
    chunk.tickEnvironment()
  for entity in world:      # parallelise  <- almost all the win is here
    entity.tick()
  for tile in world:        # parallelise
    tile.tick()
```

It dispatches each existing vanilla loop to a thread pool and then patches the resulting data races as they're discovered — "a lot of patches to the minecraft core code to avoid concurrent access to non-concurrent objects (curse ye fastutil) or to replace them with a working concurrent alternative." Real gains, almost entirely from entity parallelism (20–50% MSPT reduction claimed), and it works "in every case I've tested," with one observed world corruption.

**Got right:** the empirical finding that **entities are where the easy parallelism lives** — they're mostly independent, mostly local, and there are a lot of them. This is the correct first target for a proof of concept.

**Where it stops:** it is race-detection-by-playtesting. There is no access declaration, no ownership model, no determinism — shared mutable state is kept and concurrency is bolted on, which is the exact inversion of Folia's (correct) "remove shared ownership so concurrency needs no locks." The author's own framing — "it might break simply by you looking at it" — is the accurate description of any design where data races are reachable in principle and merely unobserved in practice. This is the failure mode Lattice's borrow-checker philosophy exists to make impossible.

### C2ME (Concurrent Chunk Management Engine) — axis C, done with integrity

C2ME parallelises **chunk generation, IO, and loading** across cores and explicitly does not touch the tick loop or game logic, and does not alter vanilla worldgen behaviour by default. It is candid that vanilla worldgen RNG is not parallel-deterministic, so **worlds vary run-to-run even with the same seed**, and it ships runtime detectors (`CheckedThreadLocalRandom`, UWRAD) that fire when code touches world RNG from the wrong thread, treating that as a bug to report rather than to paper over.

**Got right:** pick the subsystem that is *actually* embarrassingly parallel (generation: flat coordinate input → chunk output, no shared simulation state), stay inside its blast radius, and be honest that determinism is the price you pay there. The detector-instead-of-silent-corruption instinct is exactly right and Lattice should steal it wholesale.

**Where it stops:** by design it never enters the tick. It is the easy 80% precisely because generation isn't simulation. It also concedes determinism rather than engineering it — fine for generation, fatal if you copied that concession into the tick.

### Lithium (+ Starlight, Alternate Current) — the serial axis, i.e. *not* parallelism

Lithium changes no behaviour and adds no threads. It makes the *single-threaded* path faster with better algorithms and data structures: O(1)-ier collision resolution, event-driven mob brains instead of polling, a 16–22× faster point-of-interest query by replacing 16 stream-heavy retrievals with one iterator, leaner palette compaction. Starlight is the adjacent example for lighting: a *stateless* light-engine rewrite where light data lives on the chunk objects with no separate engine state, propagating levels rather than updates via increase/decrease queues. Alternate Current does the same for redstone — ~95% fewer updates, full vanilla parity, still serial.

**Got right, and why it matters here:** these reduce the **serial fraction**. In an Amdahl world your parallel speedup ceiling is set by whatever can't be parallelised, so making the serial part cheaper raises the ceiling for everyone. Lattice should treat Lithium-class work as *complementary*, not competitive — the engine cleanse below should bake in the algorithmic wins rather than leave them to a separate mod. Starlight specifically is load-bearing: its statelessness is *what makes lighting parallelisable at all* — "the chunk system [can] run light updates / generation in parallel provided the scheduling is done right." Remove the global mutable engine state and the parallelism falls out for free. That is the template for the entire cleanse.

**Where it stops:** it is explicitly single-thread. It raises the ceiling; it never crosses into multicore.

### Bevy's scheduler — axes B + the access-set contract, missing D

This is the closest thing to Lattice's intended *mechanism*. Systems are plain functions whose **parameter types declare their data access** (`Query<&Position>` = read, `Query<&mut Velocity>` = write, `Res<T>`/`ResMut<T>` for globals). The scheduler reads that declared access *without running the system*, builds a conflict graph, and automatically runs non-conflicting systems in parallel — read/read is fine, anything/write conflicts. Structural mutations (spawn/despawn/insert) can't be done concurrently, so they're deferred as **`Commands`** and applied at synchronisation points. Archetype storage keeps same-component entities contiguous (SoA) for cache-friendly iteration.

**Got right:** the declaration *is* the contract, and it's machine-readable ahead of time — this is the thing MCMT lacked. Deferred commands at sync points is precisely the compute/commit split. Access-set conflict detection makes data races a scheduling fact rather than a runtime gamble.

**Where it stops — and this is the gap Lattice must close:** Bevy gives you *safety*, not *determinism*. When two systems have conflicting access and no explicit ordering, Bevy calls it an **ambiguity** with "indeterminate execution order"; it can *detect and warn*, but resolving it is a manual chore (`before`/`after`/`ambiguous_with`). So two runs of the same Bevy app, same inputs, can diverge depending on how the thread pool happened to interleave. For a game engine that's tolerable. For an *authoritative game server* — where the world state is the source of truth that gets saved, replayed, and must be debuggable — it is not. **Bevy stops one property short of what a server needs, and that property is the hardest one.**

### Synthesis — what Lattice inherits, and the hole in the middle

| System | A spatial | B system | C off-tick | D determinism | Host modified? | Contract |
|---|---|---|---|---|---|---|
| Folia | ✔ strong | ✘ | partial | ✘ | yes (Paper patches) | runtime ownership asserts |
| MCMT | ✘ | ✔ unsound | partial | ✘ | yes (ASM, reactive) | none |
| C2ME | ✘ | ✘ | ✔ (gen/IO) | conceded | yes (mixins) | detectors |
| Lithium/Starlight | — | — | — | ✔ preserved | yes (algorithmic) | "no behaviour change" |
| Bevy | n/a | ✔ strong | ✔ | ✘ (ambiguities) | n/a (greenfield) | static access-sets |

Nobody occupies the box Lattice wants: **A + B + C + D, on a host engine that has itself been made thread-safe, behind a declared access contract that makes races unrepresentable rather than merely unobserved.** Folia has A and the ownership discipline; Bevy has B and the access-set contract; C2ME has C and the detector instinct; Lithium/Starlight prove the cleanse method (kill global mutable state, parallelism follows) and keep D. Lattice is the union — and the one genuinely new engineering is **D under parallelism**, which is where the design must start.

---

## 1. The determinism contract (decide this before the API)

The contract is not a feature; it is the thing that makes the rest of the design coherent, so it gets pinned first.

### 1.1 What "deterministic" means here — pick the right strength

Three candidate strengths:

1. **Cross-machine bit-identical** — same seed + inputs → identical bytes on any machine. Requires fighting transcendental-function divergence, native math libs, and SIMD reassociation across CPUs. Lockstep-RTS grade. **Rejected** — the server is the single authority; you never need two machines to agree bit-for-bit, and the cost (no `Math.sin`, no SIMD anywhere in the tick) is ruinous.

2. **Core-count- and schedule-invariant on a fixed binary** — same seed + inputs → identical world state regardless of thread count or dispatch order, on one given build. **This is the contract.**

3. **Eventual consistency** — no corruption, but runs may diverge (C2ME's worldgen, Bevy's ambiguities). **Accepted only in explicitly fenced zones** (worldgen, see §4).

Choosing (2) is not aesthetic. It is what makes the design's own promises true:

- **Two-stage porting is only meaningful under (2).** The pitch is "works serial immediately, optimise to parallel incrementally." That is only an *optimisation* — rather than a *rewrite* — if serial execution and N-core execution produce the *same* world. Otherwise "incrementally parallelise" means "incrementally change behaviour," and every perf commit is a gameplay regression. Core-count invariance is the formal statement of "parallelising changed nothing."
- **Debuggability.** A crash or a wrong result reproduces at 1 core. You can always drop to serial to debug a parallel failure, because they are the same execution by contract.
- **Saves and replay.** Load a save, tick once, get the same state. Anti-cheat replay, rollback, and "why did the server do that" all need it.

### 1.2 The mechanism: compute/commit phase split

A **system** is a pure function over a frozen snapshot producing a write-set:

```
system : (read_view: immutable snapshot, rng: derived stream) -> write_set
```

Within a **phase**:

- **Compute** — every scheduled system reads from the *same pre-phase immutable snapshot* and stages writes into a thread-local buffer. **No system observes another system's writes during the phase.** This is the load-bearing invariant: because the read-view is frozen, *the order systems run in cannot affect what any of them reads*. Parallelism becomes invisible to results.
- **Commit** — staged write-sets are applied to the world in a **deterministic total order** that is a function of *declarations and stable IDs*, never of thread arrival. Concrete order: `(phase index, system priority, region id, entity id, component id)`. Last-writer-wins under that order if two write-sets touch the same cell; the "last writer" is defined by the order, so it's reproducible.

The conflict rule (inherited from Bevy, tightened): two systems conflict if one **writes** a component-type that the other **reads or writes**, *within the same region*. Read/read never conflicts. Conflicting systems either run in **different phases** or are serialised within a phase by the deterministic order — never raced.

### 1.3 The genuinely hard part: intra-tick read-after-write

Vanilla simulation is full of *sequential-within-a-tick* dependencies. Entity A shoves entity B; B's resulting position depends on A's update *this same tick*. If the snapshot is frozen for the whole phase, B reads A's *pre-tick* position and the shove resolves one tick late. That is a behaviour change.

The contract's answer, stated explicitly because it's where naive copies of Bevy break:

> **Within a phase, all reads see the pre-phase snapshot. Sequential dependencies are expressed by putting the dependent systems in *different phases*, with a commit between them.**

So the phase count is a tuning dial:

- **More phases** → more sequential commit points → more faithful vanilla intra-tick ordering, less available parallelism.
- **Fewer phases** → more parallelism, but more interactions resolve a tick late.

This is a real, unavoidable tradeoff and the design should make it a *per-interaction* decision rather than a global one. The default tick is a fixed phase pipeline (e.g. `INPUT → AI → MOVEMENT → COLLISION → BLOCK_FX → LIGHTING → NETWORK`); a system declares which phase it belongs to, and a dependency that must resolve same-tick forces its producer into an earlier phase. Where one-tick latency is acceptable (the vast majority of mob interactions), you save the sync point. Where it isn't (some redstone, some collision), you pay it.

### 1.4 RNG under the contract

Vanilla pulls from a shared `Level.random` in tick order; parallelise the tick and the *draw order* becomes nondeterministic — this is precisely the failure C2ME's UWRAD detector exists to catch. The fix is to remove the shared cursor entirely:

- Each system instance gets a **splittable stream** derived deterministically from `(worldSeed, regionId | entityId, tick, systemId)` via `RandomGenerator.SplittableGenerator` (`SplittableRandom` / L64X-family), which is designed for exactly this fork-per-task pattern.
- Draws no longer share a global cursor, so draw *order* across threads is irrelevant — every stream is a pure function of its deterministic key.
- Keep a C2ME-style detector in dev builds: any access to a shared/global generator from a system thread is a hard error, surfaced to the offending mod.

### 1.5 Floating point — what's safe in the deterministic path

Java FP is reproducible by default for basic arithmetic since strictfp became mandatory (JEP 306, JDK 17), so `+ - * /` on the tick path are fine and core-count-invariant. The hazards, and the rule for each:

- `Math.sin/cos/exp/...` are only specified to ~1–2 ulp and may differ; **use `StrictMath`** (bit-reproducible) or precomputed tables in any system on the deterministic tick path.
- **Vector API / native math (SVML/SLEEF)** will not match scalar bit-for-bit. Therefore SIMD and GPU are **forbidden in the deterministic tick path** and **allowed only in fenced determinism-tolerant zones** (§4). This cleanly partitions where acceleration is legal.

### 1.6 The subtlest correctness risk — region boundaries must not change outcomes

Folia's regions split and merge based on player movement. Under contract (2) this creates a sharp obligation: **the partition may change *scheduling* but must never change *results*.** Whether two interacting entities are in one region or two must produce identical world state. That means cross-region interaction semantics (the deferred-message path) must be *behaviourally identical* to the same interaction happening intra-region — same one-tick latency, same commit order, same RNG keying. If an interaction resolves same-tick when co-regional but next-tick when cross-regional, the world diverges when a region happens to split mid-fight. This is the single hardest invariant in the whole design and it must be a test target from day one (§5, Phase 2/4), not an afterthought.

---

## 2. Subsystem cleanse plan

The method is uniform and is the Starlight lesson generalised: **don't make the structure thread-safe; remove the shared mutable state so thread-safety is unnecessary.** Ownership and immutability eliminate locks; locks are the thing MCMT drowned in. For each subsystem: the single-threaded assumption → why it breaks → the replacement.

### 2.1 World / chunk access
**Assumption:** `Level`/`ServerLevel` is a single mutable god-object; `getBlockState`, `setBlock`, and the entity lists assume one thread.
**Breaks because:** every system on every region would contend on the same object; block reads and writes interleave with no ordering.
**Replace with:** dissolve `Level` into **per-region world-views**. Block *reads* in the compute phase go through the frozen snapshot; block *writes* go through `Commit`. Chunk storage (`PalettedContainer`) becomes region-owned and is either snapshotted per tick or copy-on-write at region boundaries. Cross-region block access is not an API — it's a deferred cross-region message (§2.8 path). No system ever holds a reference to another region's world.

### 2.2 The tick loop
**Assumption:** `MinecraftServer.tick()` is one thread running a fixed sequence: time → weather → per-level {chunk ticks, entity ticks, block-entity ticks, scheduled ticks}.
**Breaks because:** it is the single-thread bottleneck the whole project exists to kill, and its hardcoded ordering is invisible to any scheduler.
**Replace with:** **two nested axes of parallelism.**
- *Across regions* (Folia axis A): each region runs its own tick loop on the pool, no main thread.
- *Within a region* (Bevy axis B): the per-region tick is **not** a hardcoded loop but a run through the phase scheduler over registered systems.
Neither Folia nor Bevy does both; the synthesis is the point. Server-global work (player join, save coordination, console) runs on a `GlobalTickThread` that owns no chunks (Folia's pattern).

### 2.3 fastutil / non-concurrent structures
**Assumption:** `Long2ObjectOpenHashMap`, `Int2ObjectOpenHashMap`, array-backed entity lists everywhere — fast, single-thread, not concurrent. MCMT's central agony.
**Breaks because:** concurrent access corrupts them; swapping each for a concurrent variant (MCMT's reactive approach) is whack-a-mole and slow.
**Replace with:** the ECS storage model **deletes the problem instead of solving it**. Component data lives in **archetype columns (SoA arrays) owned by exactly one region**, touched only by that region's ticking thread — single-writer by construction, so the structure *needs no concurrency at all* and stays a plain fastutil array (you keep Lithium-grade single-thread speed). The handful of genuinely shared structures (the chunk index, registries) become immutable/persistent or are accessed only on the global thread. Principle: single-owner, not thread-safe.

### 2.4 Lighting
**Assumption:** the light engine holds global mutable queue state and propagates sequentially.
**Breaks because:** shared queue state serialises every light update across the world.
**Replace with:** **Starlight's stateless design.** Light data lives on chunk objects with no separate engine state; propagate *levels* not *updates*, via increase/decrease queues. Statelessness makes non-adjacent chunk light updates independent — they parallelise per region "provided the scheduling is done right." Light propagation is a `LIGHTING`-phase system with `read(BlockState in radius)` / `write(LightData)`. Propagation crossing a region boundary becomes a deferred cross-region update at one-tick latency, which is acceptable because vanilla lighting is already visibly async. (Mojang adopted two Starlight ideas in 1.20 — level-propagation and dedicated skylight logic — so the host already trends this way.)

### 2.5 Scheduled-tick / block-update queues
**Assumption:** per-level `LevelTicks`/`ServerTickList` for block ticks (fluids, crops, redstone) plus the **synchronous neighbour-update cascade** — place a block, it synchronously updates neighbours, which synchronously update theirs, to a fixpoint, all inline.
**Breaks because:** the synchronous recursive cascade is unschedulable and is the redstone hot path.
**Replace with:** scheduled ticks become **region-owned queues drained in a phase**; *which* ticks fire is computed against the frozen snapshot, their *effects* are staged to `Commit`. The synchronous cascade is broken into the phase model: a block update **enqueues** rather than recursing, processed in a bounded number of `BLOCK_FX` sub-phases per tick (or as a region-local fixpoint on one thread). Firing order must be a **deterministic function of position**, never thread arrival.
**Honest limit — this is the redstone wall, and it is not solved here.** A single redstone/logistics network is a connected update graph that must reach a fixpoint; each step depends on the last, so it is **not parallelisable within the network** — it is a global reduction. The engine can only: (a) run that network's fixpoint on one thread while *other regions* proceed in parallel, and (b) expose a **partition hook** so a logistics mod that *knows* its network decomposes (e.g. independent AE2 subnets) can hand the scheduler its own partition. Within one big coupled network you are serial, by the nature of the problem, and the design respects that rather than pretending otherwise. Adopt Alternate Current's lesson in the meantime: make the *serial* network reduction far cheaper (fewer redundant updates) to raise the Amdahl ceiling.

### 2.6 RNG
**Assumption:** shared `Level.random`, drawn in tick order.
**Breaks because:** parallel ticking randomises draw order → nondeterminism.
**Replace with:** §1.4 — per-region/per-entity splittable streams keyed by `(seed, id, tick, systemId)`, plus the UWRAD-style detector.

### 2.7 Registries
**Assumption:** registries (blocks, items, biomes) are mutable during bootstrap, then "frozen."
**Breaks because:** any runtime mutation from a system thread is an unsynchronised write to globally shared state.
**Replace with:** mostly an *enforcement* job, since registries are effectively immutable post-startup. After the load phase, registries become **deeply immutable and freely shareable read-only across all threads with zero synchronisation**. Dynamic (datapack) registries freeze at world load. Runtime registration — which the greenfield contract can simply *forbid* as ad-hoc mutation — is only legal through a global-phase commit, never from a region thread.

### 2.8 Event bus
**Assumption:** Forge/Fabric buses are synchronous, single-thread, and let handlers mutate world state mid-dispatch.
**Breaks because:** a handler mutating the world during parallel ticking is an unsynchronised cross-region write and a determinism hole (handler order would depend on dispatch thread).
**Replace with:** two event shapes, neither allowing synchronous world mutation.
- **Observation events** — delivered read-only with the frozen snapshot; a handler may compute and **stage writes via `Commit`**, nothing more.
- **Decision events** (cancellation, value transforms) — resolved *within a phase* with **deterministic handler ordering** by `(priority, stable mod id)`, producing a value, not a side effect.
This is a real API break with real porting cost and should be stated to mod authors plainly rather than hidden.

---

## 3. Mod-facing concurrency API spec

Design stance: **the access declaration is the only thing the modder writes that the scheduler reads, and the handle physically cannot reach outside the declaration.** That is the Rust borrow-checker philosophy made concrete — not "please don't touch that," but "the slice you were handed does not contain that."

### 3.1 Core nouns

- **Component** — a plain data struct, one per concern (`Position`, `Velocity`, `Health`). Stored in archetype columns (SoA), region-owned.
- **Entity** — an opaque stable id; stability matters because it's part of the deterministic commit order.
- **System** — a function `(View, Commit) -> void` plus a *static* `Access` and a `Phase`.
- **View** — the read handle. Exposes **only** the components in the declared read-set, scoped to **one region**.
- **Commit** — the write handle. Stages writes; never mutates live world state.
- **Resource** — a region-scoped or global singleton (region tick state; immutable registries are global read-only).

### 3.2 A system, concretely

```java
@LatticeSystem(phase = Phase.MOVEMENT, priority = 100)
public final class GravitySystem implements System {

    // The contract. Read by the scheduler WITHOUT running the system,
    // so the conflict graph is built ahead of time.
    public static final Access ACCESS = Access.builder()
        .reads(Mass.class, Grounded.class)
        .writes(Velocity.class)
        .build();

    @Override
    public void run(View view, Commit commit) {
        // view.query only yields the declared component types; asking for
        // Position here is a compile error (typed query) or a dev-mode
        // throw (erased query) -- the handle does not expose it.
        for (var e : view.query(Velocity.class, Mass.class, Grounded.class)) {
            if (!view.get(e, Grounded.class).value()) {
                var v = view.get(e, Velocity.class);          // from frozen snapshot
                commit.set(e, new Velocity(v.x(), v.y() - G, v.z())); // staged, not applied
            }
        }
    }
}
```

The three invariants this encodes, restated as enforceable API rules:

1. **`view` reads only the pre-phase snapshot.** It is impossible to read another system's same-phase write; the type prevents it because no live reference is exposed.
2. **`commit` is the only write path**, and commits apply in the deterministic order of §1.2 at phase end.
3. **`view` is region-local.** There is no method on `View` that returns another region's data. Cross-region work is `commit.message(targetRegion, msg)`, delivered next tick (the §1.6 latency, kept identical to the intra-region path so partitioning can't change outcomes).

### 3.3 Enforcement gradient (the porting on-ramp)

This is the "performance-enforcement is a gradient" decision made API-real:

- **Undeclared system** — `Access.opaque()` (or simply no `ACCESS`). The scheduler treats it as conflicting with everything, runs it **serial on the global tick thread with full world access**. Slow, but **always correct, and works the instant you port a mod**. This is Stage 1.
- **Declared system** — a real `Access`. Eligible for parallel scheduling against every other system whose access is disjoint. This is Stage 2, done one system at a time.

So a port is: drop it in (serial, correct), then incrementally annotate hot systems with access-sets and watch them earn parallelism — and by §1.1 the world state is *identical* at every step, so each annotation is a pure optimisation you can verify by replay-diff.

### 3.4 The scheduler's job (derived, not configured)

At registration the scheduler:
1. reads every `ACCESS` and `Phase`;
2. builds the per-region conflict graph (write-vs-read/write on shared component types);
3. computes a phase schedule: within each phase, a maximal set of mutually-disjoint systems runs concurrently on the pool; conflicting systems are pushed to later phases or serialised by deterministic order;
4. flags **ambiguities** (Bevy's term) — conflicting systems with no phase separation — but unlike Bevy **does not ship with them unresolved**: an unresolved intra-phase conflict is a *build error*, because shipping it would violate contract (2). The modder resolves by assigning phases.

The two parallelism axes compose: regions run on the pool (A); within each region the phase scheduler fans out disjoint systems (B). Region thread allocation follows Folia's guidance — cap at ~80% of cores so the pool, IO, and native callbacks aren't starved.

### 3.5 Determinism as a first-class test, not a hope

The API ships with a harness, because a contract you can't test is a wish:

```java
DeterminismHarness.replay(scenario)
    .at(1).at(2).at(4).at(coresAvailable())
    .assertWorldStateHashIdenticalAcrossAllCoreCounts();
```

This is the single most important tool in the project (§5, Phase 2). It runs the same scripted scenario at every core count and hashes world state each tick. Green means parallelism changed nothing. Red means a system lied about its access, an RNG stream leaked, or a cross-region path diverged from its intra-region twin.

---

## 4. Native / GPU acceleration (fenced, optional, CPU-baseline-mandatory)

### 4.1 Why worldgen noise is the right and only first target

Worldgen noise is the ideal kernel: **flat input (coordinates) → flat output (density field), no shared simulation state, embarrassingly data-parallel** — and worldgen is *already* a fenced determinism-tolerant zone (C2ME established that vanilla worldgen varies run-to-run and that this is acceptable). Because it's outside the deterministic tick contract (§1.5), SIMD reassociation and GPU reordering are *legal* here. Nothing in the tick path qualifies, so worldgen is not just the easy first target — it is, for now, the *only* place acceleration is contract-legal.

### 4.2 Substrate: FFM, off-heap, SoA

- **FFM (Foreign Function & Memory API, JEP 454) is finalised in JDK 22** — production-stable, safe to hard-depend on. Use `Arena` + `MemorySegment` for off-heap flat buffers and `Linker` downcall handles to call a native kernel (or a thin C shim over CUDA/Vulkan compute).
- **Off-heap rationale:** no GC pressure for large buffers, a stable address for native/GPU DMA, and an SoA layout that *both* SIMD and GPU want — which is the *same* layout as the ECS archetype columns, so kernel buffers and component storage share a representation.

### 4.3 CPU baseline is mandatory and is the oracle

Most servers have no GPU. Therefore:
- Every kernel has a **scalar Java reference implementation** that is both the fallback and the **correctness oracle** — it defines the semantics; the accelerated paths must match it within the tolerance the fenced zone allows.
- Native/GPU is selected at runtime by a **capability probe**; absence is normal, not an error.
- **CPU SIMD via the Vector API is a *soft* dependency, with an accuracy caveat to state honestly:** the Vector API is **still incubating** (JEP 529, eleventh incubation, targeted JDK 26 as of late 2025; it will incubate until Project Valhalla preview features land). It requires `--add-modules jdk.incubator.vector`, the API may still shift, and it is **not** bit-identical to scalar. So: use it to accelerate the *CPU* worldgen kernel inside the fence, pin the JDK, and never let it become load-bearing for anything the deterministic tick path touches. FFM you may build on; the Vector API you keep at arm's length.

---

## 5. Phased roadmap, and the single proof-of-concept

The ordering is deliberate: **correctness and the determinism harness come before any parallelism**, because the harness is what proves each later phase didn't break anything. You cannot safely build parallelism you can't test for divergence.

### Phase 0 — Host preparation
Stand up the Paperweight-style pipeline on Mojang's mappings: decompile → remap → patch → recompile into a **buildable deobfuscated server you control the source of**. No threading yet.
*Exit:* vanilla-parity server you can patch.

### Phase 1 — Serial correctness skeleton
Implement ECS storage + phase scheduler + `Commit`, but run **everything serial** via the undeclared-system path. Port the vanilla tick into systems that all run serial.
*Exit:* serial Lattice reproduces vanilla behaviour, validated by replay-diff against the Phase-0 baseline. **This is the determinism oracle existing before any concurrency.**

### Phase 2 — Determinism harness + RNG/registry cleanse
Build `DeterminismHarness` (§3.5). Cleanse RNG to splittable streams (§2.6) and freeze registries (§2.7). Implement the cross-region message path and **immediately test §1.6** — assert an interaction's outcome is identical whether co-regional or cross-regional.
*Exit:* a *declared* system produces identical state at 1 and N cores; the contract is testable and green before a single perf optimisation exists.

### Phase 3 — Intra-region system parallelism (axis B)
Turn on the scheduler so declared, disjoint-access systems run concurrently within one region. Cleanse the structures those systems touch: entity archetype columns (§2.3), stateless lighting (§2.4).
*Exit:* PoC, first half — measured speedup on a dense scene at fixed core count, harness still green.

### Phase 4 — Inter-region parallelism (axis A)
Add region ownership, dynamic split/merge with Folia's invariant, the `GlobalTickThread`, and cross-region deferral. Both axes now live and composing.
*Exit:* PoC, full — see below.

### Phase 5 — Native acceleration
FFM off-heap buffers + worldgen noise kernel (§4): scalar baseline + optional SIMD/GPU behind a capability probe, scalar verified as oracle.
*Exit:* worldgen throughput scales with the optional accelerator; scalar fallback bit-checked.

### Phase 6 — The hard path, scoped honestly
Block-update cascade folded into the phase model (§2.5); the partition hook for redstone/logistics so a mod can declare independent sub-networks. No claim of within-network parallelism — only across independent networks, only with the mod's own partition exposed.
*Exit:* independent-network parallelism demonstrated; within-network serial, by design.

### The single PoC workload — one dense scene, 1 → N cores

**Scenario: a fixed-seed entity arena.** A bounded region (or a band of adjacent regions) populated with ~10,000 simple entities — item entities plus basic-AI mobs — ticking gravity, movement, collision, and AI, driven by a deterministic scripted input track.

Why this scene and not another:
- It targets the empirically-proven easy win — **MCMT showed almost all real gains come from entities**, so it's a credible, comparable workload, not a strawman.
- It exercises everything that matters at once: per-entity systems with disjoint declared access (B), the commit phase (position/velocity writes), splittable RNG (mob decisions), and — by spreading the field across adjacent regions — region ownership and the split/merge path (A).
- It has a clean serial baseline, a single headline metric, and it **avoids the redstone trap** while still being genuinely dense.

**The headline result is two curves on one chart:**
1. **MSPT vs. core count (1 → N)** — the speedup curve. Expectation set honestly: the design's own Amdahl ceiling is **~2–4× on dense scenes**, not linear, and this entity scene is close to best-case; a redstone-dense scene would be flat and that is correct, not a failure.
2. **World-state hash vs. core count** — a *flat line*. Identical at every core count.

That second line is the entire thesis in one measurement: **speedup with provably zero behaviour change.** Folia can't draw line 2 (no determinism claim), MCMT can't draw it (races), Bevy can't guarantee it (ambiguities). Drawing both lines together is what no prior system has done.

---

## Three honest tensions to keep in view

These aren't relitigations of the settled decisions; they're the downstream costs those decisions impose, and the design is stronger for naming them.

1. **Determinism vs. dynamic regions is the real correctness frontier (§1.6).** The moment region boundaries can shift mid-tick, every cross-region interaction must be behaviourally indistinguishable from its intra-region form, or the world diverges when a fight happens to straddle a split. This is subtler than any single subsystem cleanse and deserves the most test pressure.

2. **The phase-count dial has no free setting (§1.3).** Faithful vanilla intra-tick sequencing wants many sync points; parallelism wants few. Some interactions will resolve a tick late and some will force a sync that caps scaling. This is a permanent per-interaction judgement call, not a thing that gets solved once.

3. **The technical risk is lower than the ecosystem risk.** A multicore engine with no mods is a very fast empty server. The hard problem isn't the threading — it's whether enough mods get authored against a brand-new, borrow-checker-grade API after Fabric/Forge compatibility was deliberately abandoned. Projects in this space die from empty mod ecosystems far more often than from scheduler bugs. Worth saying once, plainly, and worth a deliberate answer (reference mods, a porting toolkit, a flagship modpack) somewhere on the roadmap.
