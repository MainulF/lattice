# HANDOFF — Phase 4 PoC exit: A+B composing benchmark green

**Written:** 2026-06-02 (session 9 update)
**Session:** Phase 4 (4e — A+B composing PoC): multi-region benchmark + determinism test, 48 tests green
**Next instance:** read `CLAUDE.md` and this file for state.

---

## 1. Where we are

**Phase 0 complete. Phase 1 complete. Phase 2 complete. Phase 3 complete. Phase 4: 4a+4c+4e done; 4b (dynamic split/merge) remains.**

Phase 3 PoC done this session:
- **Entity-range splitting** — `PhaseScheduler` now fans out each declared system's entity set into N contiguous chunks (one per core), each running as a separate pool task via a `ChunkedView`. This is data parallelism (MCMT axis), not task parallelism.
- **Snapshot removal** — `store.snapshot()` (a full deep-copy per phase) was eliminated. The live store is frozen during compute (all writes go to `CommitBuffer`s; `applyCommits` runs after the join on the caller thread). `ComponentStore.get()` fixed to use `columns.get()` instead of `columns.computeIfAbsent()` so concurrent reads are race-free.
- **Hoisted intersect** — `precomputeMatchingEntities()` computes the matching entity array once on the caller thread before chunk submission. `ChunkedView.query()` returns its precomputed slice in O(1). No per-entity binary search inside the hot parallel path.
- **`EcsBenchmark`** — two-scenario benchmark with 3-trial median timing and harness-verified flat-line. Run with `./gradlew runBenchmark`.

### Phase 3 PoC benchmark results (5700G / 8-core, 10k entities, 500 ticks, median of 3 trials)

Timing decomposed via `PhaseScheduler.lastTickComputeNs/CommitNs()` (measured, not estimated):

**Light (MovementSystem only, ~5 FP ops/entity):**

| Cores | MSPT | Compute (ms) | Commit (ms) | Speedup | Hash |
|---|---|---|---|---|---|
| 1 | 2.34ms | 1.29ms | 1.05ms | 1.00× | e2a90992067308dd ✓ |
| 2 | 2.00ms | 0.91ms | 1.09ms | 1.17× | identical ✓ |
| 4 | 1.74ms | 0.63ms | 1.10ms | 1.34× | identical ✓ |
| 8 | 1.68ms | 0.58ms | 1.11ms | 1.39× | identical ✓ |

**Physics+Wander (StrictMath sin/cos + forEntity RNG per entity):**

| Cores | MSPT | Compute (ms) | Commit (ms) | Speedup | Hash |
|---|---|---|---|---|---|
| 1 | 2.69ms | 1.62ms | 1.06ms | 1.00× | 139f1f5364ad0167 ✓ |
| 2 | 2.18ms | 1.08ms | 1.11ms | 1.23× | identical ✓ |
| 4 | 1.89ms | 0.77ms | 1.11ms | 1.42× | identical ✓ |
| 8 | 1.81ms | 0.67ms | 1.14ms | 1.49× | identical ✓ |

**The flat hash line** (identical at 1/2/4/8 cores, verified at each N by actually running at N cores) **is the thesis**. Folia/MCMT/Bevy cannot draw this line. Lattice can.

**Measured Amdahl decomposition (light scenario, 1 core):**
- `applyCommits()` (commit column): **~1.05ms** — MEASURED, confirmed as the serial floor. Sort + apply 20k write-ops/tick, inherently serial per §1.2 total-order.
- Parallel compute at 1 core: **~1.29ms** — entity processing, serialized here.
- Serial fraction: 1.05 / 2.34 = **45%** → Amdahl ceiling = 1/0.45 = **2.2×**.
- **But actual speedup at 8 cores is only 1.39×.** Gap explained by pool overhead:
  - Expected compute at 4 cores (pure Amdahl): 1.29/4 = 0.32ms.
  - Measured compute at 4 cores: **0.63ms**.
  - Pool/chunking overhead: **~0.31ms per tick** — precompute query (O(10k)), `Arrays.copyOfRange` for N chunks, N CommitBuffer/ChunkedView/LatticeRng objects created, thread wake-up latency.
- With zero pool overhead, 8-core speedup would be ~2.0×. Pool overhead is eating ~0.6× of the available gain.
- Heavier per-entity work (Physics+Wander) amortizes the overhead → steepens to 1.49×. The design's "~2–4×" requires full §5 scene AND reduced pool overhead.

**What would push the curve toward 2–4×:**
1. Pass `(array, startIdx, endIdx)` into `ChunkedView` instead of cloning — eliminates 80KB/tick/system of chunk allocation (biggest reducible overhead).
2. Heavier per-entity work (full §5 scene: AI + collision + RNG). Compute dominates at ~5ns/entity; to amortize the 0.31ms pool overhead at 4 cores, need ~62ns/entity — achievable with real AI.
3. These are Phase 4 optimizations, not blockers for Phase 3 exit.

**Phase 3 exit criterion (§5):** PoC first half — measured speedup on a dense scene, harness still green. ✓ **Done.**

Deferred from Phase 3: entity-column cleanse (§2.3) and lighting cleanse (§2.4). These are Phase 4 pre-work, not required for the Phase 3 PoC exit.

**Phase 4 started (session 8):** `Region` + `RegionCoordinator` + `View.inbox()` + §1.6 test green at 1 and 4 coordinator cores. Dynamic split/merge (4b) and A+B composing PoC (4e) remain.

### Test counts (all green)

| Suite | Tests |
|---|---|
| `DeterminismHarnessTest` | 23 |
| `EcsTest` | 11 |
| `RegionCoordinatorTest` | 10 |
| `LatticeServerTest` | 4 |
| **Total** | **48** |

---

## 2. What was built session 9 (Phase 4 — 4e A+B composing PoC)

### New test (`src/test/java/.../ecs/RegionCoordinatorTest.java`)

| Test | Description |
|---|---|
| `composedAxesAB_independentRegions_hashFlatAcrossAllConfigurations` | Phase 4 exit criterion: A+B composing does not break flat-hash invariant. 4 regions × 25 entities, gravity (Phase.AI) + movement (Phase.MOVEMENT). Per-tick XOR hash identical across (A=1,B=1), (A=4,B=1), (A=1,B=4), (A=2,B=2). |

### Modified benchmark (`src/main/java/.../ecs/EcsBenchmark.java`)

Added `runMultiRegionScenario()` + helpers (`buildMultiRegionStores`, `setupMultiRegionCoord`, `xorHash`):
- 4 regions × 2500 entities each = 10k total (same physical scale as Phase 3).
- Determinism check: final-tick XOR hash identical across 5 `(A,B)` configs (not per-tick — avoids polluting timing with reflection overhead).
- Speedup table: 5 `(A,B)` configs, store build outside timing window (same pattern as Phase 3 `runScenario`).

### Phase 4e benchmark results (5700G / 8-core, 4 regions × 2500 entities, MovementSystem)

| Coord A | Sched B | A×B | MSPT | Speedup | Hash |
|---|---|---|---|---|---|
| 1 | 1 | 1 | 2.31ms | 1.00× | 3d98ce7ad6ab2880 ✓ |
| 4 | 1 | 4 | 0.65ms | 3.55× | identical ✓ |
| 1 | 8 | 8 | 1.77ms | 1.31× | identical ✓ |
| 4 | 2 | 8 | 0.60ms | **3.85×** | identical ✓ |
| 2 | 4 | 8 | 0.97ms | 2.39× | identical ✓ |

**Key findings:**
- **A-axis dominates for multi-region workloads.** A-only (4,1) gets 3.55× vs B-only (1,8) at 1.31×. Reason: with 4 independent regions, the serial commit fraction is `per_region_commit / per_region_tick` — much smaller than single-region because each region commits only 2500 entities (not 10k). Distributing entities across regions reduces the Amdahl serial floor without any B-axis at all.
- **A+B (4,2) is the peak at 3.85×** — B-axis provides a small additional gain within each region's compute phase. The `(A=4,B=2)` config outperforms `(A=4,B=1)` because each region's 2500 entities split into 2 parallel chunks.
- **A+B (2,4) = 2.39× is weaker** — with only 2 regions in parallel, the coordinator's serial overhead between region-pairs limits the benefit of B=4 within each pair.
- **The flat hash line holds across all A+B configs** — the thesis is demonstrated for the composed case.

**Compare to Phase 3 single-region B-only at 8 cores: 1.38×** — Phase 4 multi-region A=4 already achieves 3.55×, i.e. ~2.6× more than the best single-region result. The multi-region decomposition fundamentally shifts the Amdahl ceiling by fragmenting the serial commit.

---

## 2b. What was built session 8 (Phase 4 — Region + RegionCoordinator)

### New source files (`src/main/java/io/github/mainulf/lattice/ecs/`)

| File | Role |
|---|---|
| `Region.java` | Thin wrapper: long id + ComponentStore + PhaseScheduler + pendingInbox. `deliverMessages()` appends to pending; `tick()` atomically passes pending inbox to scheduler and resets. `drainMessages()` delegates to scheduler. `AutoCloseable`. |
| `RegionCoordinator.java` | Per-tick protocol: tick all regions (pool if `setParallelism > 1`, axis A) → global `awaitAll` barrier → drain messages in sorted-region-id order → route to target inboxes. Self-messages (A messages own region id) routed back identically. `AutoCloseable`. |

### Modified source files (`src/main/java/io/github/mainulf/lattice/ecs/`)

| File | Change |
|---|---|
| `View.java` | Added `default List<Object> inbox()` — the receive-side API for cross-region messages. Default returns `List.of()` so existing code/tests need no changes. |
| `SnapshotView.java` | Accepts `List<Object> inbox` in constructor; exposes it via `inbox()`. |
| `ChunkedView.java` | Accepts `List<Object> inbox` in constructor; exposes it via `inbox()`. |
| `PhaseScheduler.java` | Added `currentInbox` field + `setInbox(List<Object>)` method. `tick()` captures inbox at tick start, resets to empty, passes to each `runPhase` call → view constructors. |

### New test file (`src/test/java/.../ecs/`)

| File | Tests |
|---|---|
| `RegionCoordinatorTest.java` | 9 tests: single-region sanity, duplicate-id guard, cross-region one-tick delivery, unknown-target drop, self-message one-tick delivery, §1.6 co/cross/parallel identical, axis-A parallel hash, inbox-empty, inbox-unmodifiable. |

---

## 2b. What was built session 7 (Phase 3 PoC)

### New source files (`src/main/java/io/github/mainulf/lattice/ecs/`)

| File | Role |
|---|---|
| `ChunkedView.java` | Implements `View` backed by a precomputed entity chunk. `query()` returns the precomputed slice in O(1) (hoisted intersect). Concurrent reads on the live store are safe because the store is frozen during compute. |
| `EcsBenchmark.java` | Two-scenario MSPT benchmark: light (MovementSystem) and heavy (Physics+Wander with StrictMath + forEntity RNG). 3-trial median, harness-verified flat-line, run via `./gradlew runBenchmark`. |

### Modified source files (`src/main/java/io/github/mainulf/lattice/ecs/`)

| File | Change |
|---|---|
| `ComponentStore.java` | `get()` fixed to use `columns.get(type)` instead of `column(type)` (which used `computeIfAbsent`). Concurrent reads from multiple pool threads are now race-free. |
| `PhaseScheduler.java` | `store.snapshot()` removed from `runPhase()` — live store passed directly (frozen during compute by CommitBuffer design). `runPhaseParallel()` now implements entity-range splitting: `precomputeMatchingEntities()` hoists the type-intersection to the caller thread; N chunk tasks are submitted per declared system via `executeSystemChunk()` using `ChunkedView`. Opaque systems still run serial on caller thread. |

### `build.gradle.kts`

Added `runBenchmark` task (`JavaExec` → `EcsBenchmark.main`).

---

## 2c. What was built session 6 (Phase 3 tasks 4a–4d)

### Modified source files (`src/main/java/io/github/mainulf/lattice/ecs/`)

| File | Change |
|---|---|
| `PhaseScheduler.java` | `implements AutoCloseable`; `setParallelism(int)` creates `ExecutorService`; `runPhase` routes to `runPhaseSerial` or `runPhaseParallel`; `executeSystem` helper extracts system body; `IN_SYSTEM_THREAD` gated on `!isOpaque()`, uses `remove()` in `finally`. |
| `DeterminismHarness.java` | `runScenario` calls `scheduler.setParallelism(cores)`; wraps scheduler in try-with-resources for pool cleanup. |

### Modified source files (`src/main/java/io/github/mainulf/lattice/`)

| File | Change |
|---|---|
| `LatticeServer.java` | Added `setWorldSeed(long)` delegation to `scheduler.setWorldSeed()`. |

### New patch

| File | Role |
|---|---|
| `patches/server/0004-Phase-3-wire-worldSeed-into-LatticeServer-at-tick-1.patch` | Wires `lattice.setWorldSeed(worldGenSettings.options().seed())` at tick 1 in `MinecraftServer.tickServer()`. |

### Modified test file

`DeterminismHarnessTest.java` (23 tests, was 22):
- Fixed `detector_throwsInsideSystem` → declared system (opaque no longer sets flag)
- Fixed `registry_mutationFromSystemThreadForbidden` → declared system
- Updated `detector_flagClearedAfterTick`, `detector_flagClearedAfterThrow` → declared system
- **New:** `harness_parallelFanOut_twoDisjointSystems_identicalAtAllCoreCounts` — two disjoint declared systems (gravity + nudge) in Phase.MOVEMENT, `nudge` draws per-entity RNG via `view.rng().forEntity(id)`, proves RNG streams are thread-independent at `.at(1).at(2).at(4)`.

---

## 3. Critical facts (do NOT rediscover)

All facts from Phase 2 HANDOFF (§3) still apply. Below are Phase 3 additions.

### Parallel path design (session 7 — entity-range splitting)

`PhaseScheduler.runPhaseParallel(inPhase)` — no snapshot parameter (live store passed directly):
- For **opaque** systems: runs `executeSystem` synchronously on the caller thread (unchanged).
- For **declared** systems:
  1. **Hoisted intersect**: `precomputeMatchingEntities(reg)` calls `store.query(reads ∪ writes)` on the caller thread, producing the full sorted entity array in O(N).
  2. **Chunk submission**: splits the array into `min(parallelism, entityCount)` contiguous slices; submits each as a separate pool task via `executeSystemChunk(reg, chunk)`.
  3. **Join**: `future.get()` in submission order — **DETERMINISM LOAD-BEARING**: contiguous in-order chunks + the commit sort reproduce the same total order at every core count.
- `applyCommits(buffers)` sorts all write-ops by `(priority, systemId, entityId, type)` — unchanged. Execution order cannot affect commit order.

**Why no snapshot:** commits are deferred to `CommitBuffer`s; `applyCommits` runs only after the join, on the caller thread. The live store is frozen during compute. The happens-before chain (prev phase `applyCommits` → `pool.submit` → task) ensures pool threads see correct inter-phase committed state.

**`ComponentStore.get()` fix:** uses `columns.get(type)` (not `computeIfAbsent`). Only `setRaw()` (called from `applyCommits`, main thread) structurally modifies columns. Concurrent reads are safe on a structurally-stable `LinkedHashMap`.

**`ChunkedView.query()` is O(1):** precomputed chunk entities all satisfy any legal query type (they were filtered to the system's full access — a superset of any query the access-enforcement allows). No per-entity binary search inside the hot path.

**RNG caution:** raw `view.rng().random()` draws are unsafe across chunks (shared root seed, concurrent access). `view.rng().forEntity(id)` is always safe — pure function of seed + entity ID, identical regardless of chunk.

### Pool lifecycle

`PhaseScheduler` is `AutoCloseable`. `DeterminismHarness.runScenario` wraps it in try-with-resources. For production, `LatticeServer` holds the scheduler — add `AutoCloseable` to `LatticeServer` and propagate `close()` if needed in Phase 4+.

### Detector scope change (§4c)

`IN_SYSTEM_THREAD` is now set **only for declared (non-opaque) systems**. Impact:
- Opaque systems (vanilla tick) no longer trigger `assertNotInSystemThread()` — correct, since the vanilla tick legitimately accesses `Level.random`.
- Declared parallel systems still trigger it — correct, they must use `view.rng()` instead.
- `LatticeRegistry.register()` in an opaque system no longer throws — if a future scenario requires protecting the registry from opaque systems too, use a separate flag.

### worldSeed gap — CLOSED for production path

`LatticeServer.setWorldSeed()` wired at tick 1 via patch 0004. RNG streams in production are now keyed correctly. No test depends on this (no declared system draws RNG in the live server path yet), but the wiring is in place.

---

## 4. What Phase 4 still needs (inter-region parallelism, axis A)

Design §5 Phase 4 exit: "PoC, full — both axes A and B live and composing."

### 4a. Region ownership model — DONE (static regions only)

`Region` and `RegionCoordinator` built. Entities are manually assigned to regions for now.
**Remaining**: `GlobalTickThread` (player join, save, console) — deferred until real MC integration.

### 4b. Dynamic split/merge with Folia's invariant — NOT DONE

Regions split and merge based on entity density / player proximity. Invariant: a ticking region may not begin ticking adjacent to another ticking region; adjacent regions are forced to merge. Regions may not grow while ticking. This is the hardest part of Phase 4.

### 4c. Cross-region deferral (§1.6 test target) — DONE

`View.inbox()` receive-side API added. `RegionCoordinator` routes messages from all regions after the per-tick global barrier, in deterministic sorted-region-id order. §1.6 test green at 1 and 4 coordinator cores.

**Uniform interaction model (§1.6 key):** both co-regional and cross-regional cases use `commit.message(targetRegionId, payload)` + `view.inbox()`. A messages its own region id in the co-regional case; coordinator routes it back. Identical one-tick deferred latency. This is what makes region boundaries outcome-neutral.

### 4d. Entity-column cleanse (§2.3) and lighting cleanse (§2.4) — DEFERRED

§2.3: archetype columns are single-writer by construction (region owns its store). Formal cleanse is just documentation + enforcement. §2.4: stateless Starlight-style lighting — a `LIGHTING`-phase declared system, no global engine state.

### 4e. PoC exit: both axes composing — DONE

`RegionCoordinatorTest.composedAxesAB_independentRegions_hashFlatAcrossAllConfigurations` asserts the flat-hash invariant across (A=1,B=1), (A=4,B=1), (A=1,B=4), (A=2,B=2). `EcsBenchmark.runMultiRegionScenario()` shows the speedup curve: peak **3.85×** at (A=4, B=2). See session 9 results in §2 above.

The Phase 4 PoC exit criterion ("both axes A and B live and composing") is met. The two headline curves are available:
1. MSPT vs (A,B) config — peak 3.85× at (A=4,B=2), exceeding the Phase 3 B-only ceiling (1.38×).
2. Hash vs config — flat line (`3d98ce7ad6ab2880`) at all configs.

---

## 5. Phase 2 critical facts (preserved)

All facts from Phase 2 HANDOFF §3 preserved here by reference. Key Phase 1 facts:
- `net.minecraft:joined:26.1` on `compileClasspath`, `runServer` depends on `classes`.
- `patchedMc` source set: add path to `java.include(...)` in `build.gradle.kts` to add patched files.
- **ItemEntity.java is in patchedMc.** Gravity/move gated on `LatticeServer.MOVEMENT_CARVED`.
- **World type is superflat.** Items fall through air for deterministic physics.
- **PLAYER_LOADING ticket** (flags=2) avoids double-ticking. FORCED ticket was the bug.
- **MovementSystem operation order**: (1) `vy -= 0.04` gravity, (2) `pos += vel` move, (3) `vel *= (0.98F, 0.98, 0.98F)` friction. `0.98F` on x/z is float; `0.98` on y is double.
- **Sync ordering**: `syncFromMC` BEFORE `lattice.tick()`, `applyToMC` AFTER.

---

## 6. Phase recap — Phase 2 exit state (preserved)

Phase 2 exit criterion met: declared `MovementSystem` produces identical per-tick hash at `.at(1).at(2).at(4)` — harness green before any parallelism. All 37 tests green.

---

## 7. Phase 1 recap (preserved)

Phase 1 exit criterion met:
```
./gradlew runServer -PlatticeTickLimit=100                    # vanilla baseline
./gradlew runServer -PlatticeTickLimit=100 -PlatticeMoveCarve=1  # carved — IDENTICAL
```
Both produce bit-identical 100-tick hash sequences. Baseline at `run/baseline-hash.log`.

---

## 8. Phase 0 recap (preserved)

**Stack:**

| Component | Version | Notes |
|---|---|---|
| Minecraft | 26.1 | CalVer (2026); requires Java 25+; Mojang-official unobfuscated jars |
| JDK | 26 (system) | `/usr/lib/jvm/java-26-openjdk`; no JDK 21 or 25 needed |
| Gradle | 9.2.1 | Via wrapper; required by VanillaGradle 0.3.x |
| VanillaGradle | 0.3.2 | SpongePowered's toolchain plugin |
| Build DSL | Kotlin DSL | `build.gradle.kts` + `settings.gradle.kts` |

**Surprising things not to rediscover:**
- MC switched to CalVer in 2026; latest stable is **26.1**, not "1.21.x".
- VanillaGradle 0.3.0 removed de-obfuscation (MC 26.1 ships with Mojang symbols).
- JDK 26 satisfies everything (MC requires Java 25+).
- `Gradle 9` removed `Project.exec()` — use `ProcessBuilder` in task `doLast` blocks.
- VanillaGradle sources JAR: `~/.gradle/caches/VanillaGradle/v2/jars/net/minecraft/joined/26.1/joined-26.1-sources.jar`
