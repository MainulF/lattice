# ROADMAP — Lattice

**Written:** 2026-06-02 (session 10)
**Anchors:** `lattice-design.md` (authoritative spec), `HANDOFF.md` (state), `DECISIONS.md` (pinned choices).
On any conflict, the design doc wins. This file is the *forward plan*; it does not restate §5, it sequences it.

---

## 0. Where we are (entry state)

| Phase | Status |
|---|---|
| 0 — Host prep | ✅ complete |
| 1 — Serial correctness skeleton | ✅ complete (100-tick replay-diff bit-identical) |
| 2 — Determinism harness + RNG/registry cleanse | ✅ complete |
| 3 — Intra-region parallelism (axis B) | ✅ PoC complete (entity-range splitting, flat hash) |
| 4 — Inter-region parallelism (axis A) | ⚠️ **PoC exit met** (4a+4c+4e); 4b/4d/GlobalTickThread remain |
| 5 — Native worldgen | ⬜ not started |
| 6 — Block cascade + redstone hook | ⬜ not started |

**49 tests green** (`DeterminismHarnessTest` 23, `EcsTest` 11, `RegionCoordinatorTest` 11, `LatticeServerTest` 4).
Peak measured speedup **3.85×** at (A=4, B=2), flat world-state hash across all configs — the thesis is demonstrated for the PoC.

**The new directive (2026-06-02):** Kotlin becomes the **primary mod-development language**; Java stays fully supported. See Milestone K and `DECISIONS.md` 2026-06-02.

---

## 1. The one architectural decision this plan adds

**The deterministic engine stays Java. Only the mod-facing API (§3) becomes Kotlin-first.**

There are two layers, and only one moves:

- **Engine (Java, untouched):** `PhaseScheduler`, `ComponentStore`/`ComponentColumn`, `Commit`/`CommitBuffer`, `RegionCoordinator`/`Region`, `LatticeRng`, `WorldStateHasher`. This is the hot deterministic tick path, already audited for `strictfp` + `StrictMath` (§1.5). Rewriting it in Kotlin would be reckless churn against a contract that is green. **Do not touch it.**
- **Mod-facing API (§3, becomes Kotlin-first):** `Component`, `LatticeSystem`, `Access`, `View`, `Commit`, `Phase`, `@SystemDef`. A Kotlin front-end wraps these *same* Java types — idiomatic for Kotlin authors, compiling down to exactly the types the scheduler already reads. Java mods keep using the Java types directly.

"Kotlin as primary mod language" = **a Kotlin front-end over the same §3 model — not a rewrite, not a contract change.** This is consistent with "design doc wins": we are adding a front-end, not contradicting §3. The §3 code examples may later be re-illustrated in Kotlin (cosmetic).

**Toolchain resolution (the only feasibility risk, already resolved):** Kotlin's max `jvmTarget` is **25** (Kotlin compiler reference, April 2026) — it cannot emit *or reliably read* bytecode 26. The engine is currently pinned to bytecode 26. Fix: **target `--release 25` across the whole project**, keep the **JDK 26 toolchain + runtime**. MC 26.1 needs Java 25+ at *runtime* (satisfied by the 26 runtime) and never needs bytecode-26 *output*. FFM (§4) is a runtime API available on JDK 22+, unaffected by bytecode target. `strictfp` is mandatory ≥ JDK 17 regardless of target, so determinism is unaffected. This is recorded as a dated **amendment** to the "bytecode 26" choice in `DECISIONS.md`, not a contradiction of it.

---

## 2. Milestone K — Kotlin mod-development API  *(next; ~3 sessions)*

**Why next, before 4b:** (a) the user elevated it; (b) the engine is stable *right now* — the ideal moment to wrap it; wrapping a mid-4b moving target would be churn; (c) it directly answers the design's named #1 risk ("projects in this space die from empty mod ecosystems far more often than from scheduler bugs" — Three Honest Tensions §3); (d) it **cannot regress the determinism contract** because it does not touch the engine — lowest-risk, high-value.

### K1 — Toolchain + build *(1 session)*
1. Drop project bytecode target to `--release 25`; **rerun all 49 tests** — the real test is "does the existing engine still compile + stay green at release 25?" Run *before* adding any Kotlin. (The only way this breaks is if the engine used a Java-26-only feature; surface it here.)
2. Add `kotlin("jvm")` plugin, pin a Kotlin 2.x version, set `jvmTarget = 25`.
3. **Interop proof:** a trivial Kotlin class calls `PhaseScheduler` / builds an `Access`, compiles, runs. Don't assume Kotlin reads the engine's bytecode-25 classfiles — prove it.
4. Decide layout: dedicated `mod-api-kotlin` source set (lighter) vs. subproject (cleaner long-term). Default to a source set unless interop forces a module.
5. **DECISIONS.md:** dated amendment (done this session — see below).

**Exit:** Kotlin compiles against the engine; 49 Java tests still green at release 25.

### K2 — Idiomatic Kotlin API surface *(1 session)*
- `Component` Kotlin idiom: `data class Position(...) : Component` with `val` props (immutability by construction — stronger than Java records permit).
- Reified extensions over `View`/`Commit`: `view.get<Position>(id)`, `view.query<Position, Velocity>()`, `commit.set(id, …)` — erase the `::class.java` boilerplate.
- **Access DSL:** `access { reads<Position>(); writes<Velocity>() }` building the identical `Access` object the Java builder produces.
- System authoring from Kotlin (interface impl and/or a `system(phase, priority) { view, commit -> }` builder).
- **`lattice.math`** — `StrictMath` wrappers (`sin`, `cos`, `exp`, …) as the idiomatic safe transcendental path for Kotlin mods. (The trap: `kotlin.math.sin` delegates to `java.lang.Math`, **not** `StrictMath`, silently violating §1.5.)

**Tests (this is the milestone's acceptance gate):**
- ⭐ **Cross-language equivalence:** a Kotlin `MovementSystem` twin produces a **bit-identical per-tick world-state hash** to the Java `MovementSystem`, through the existing `DeterminismHarness` at `.at(1).at(2).at(4)`. This single test *is* the proof the Kotlin layer is pure convenience that changed no behavior — the Kotlin analogue of the Phase-1 replay-diff. **Gate, not nice-to-have.**
- Access DSL builds an `Access` `equals`/conflict-identical to the Java builder's.
- Reified `query`/`get` return identical results to the Java calls.

### K3 — Porting guide + example Kotlin mod *(1 session)*
- A small example Kotlin mod (gravity + wander) exercising the full surface end-to-end.
- `KOTLIN_MODDING.md`: the §3.3 enforcement gradient in Kotlin terms (opaque → declared → parallel), plus the **determinism do/don't list** (see §7 below).
- **Tests:** example mod runs through the harness at 1/2/4 cores, flat hash.

**Milestone K exit:** Kotlin is a first-class authoring path with a green cross-language equivalence gate, a worked example, and a porting doc. Java authoring unaffected.

---

## 3. Milestone S — Live server integration  *(~2–3 sessions; do before Phase 6 is meaningful)*

**The "cover everything" gap:** the multi-region parallel engine is proven in tests + benchmark but is **not wired into live `MinecraftServer.tickServer`** — only the single Phase-1 `MovementSystem` carve is. This is implicit in HANDOFF; promote it to a named milestone.

- MC entities ↔ ECS region stores: sync-in before `coordinator.tick()`, apply-out after (generalize the Phase-1 `syncFromMC`/`applyToMC` pattern to N regions and N components).
- `RegionCoordinator` driven from the patched tick loop (extend patch 0004 family).
- **`GlobalTickThread`** (Folia's pattern): player join, world save, console — owns no chunks, runs the non-regionalizable work (§2.2).
- `LatticeServer` → `AutoCloseable` (the standing open item) and propagate pool shutdown.

**Tests:** a live multi-region server run reproduces a recorded baseline hash sequence (the Phase-1 replay-diff method, extended to the regionized live path); harness still green.

*Ordering note:* K and S are independent and can interleave. K is sequenced first per the directive; S can begin in parallel if a second work-stream opens.

---

## 4. Milestone 4b — Dynamic split/merge  *(HIGH VARIANCE; 3–5 sessions — the design's hardest invariant)*

§1.6 / Tension #1: region boundaries may change *scheduling* but **never** *results*. **Test-first — the oracle precedes the mechanism.**

- **4b.0 — Write the oracle test FIRST.** A scenario that *splits a region at tick K* must produce a world-state hash sequence **identical** to (a) the same scenario that never splits, and (b) the existing static-two-region run. The split/merge code is only correct if it passes this. Do not write split machinery before this test exists and fails for the right reason.
- **4b.1 — Atomicity:** split/merge happens only at a phase/commit boundary; no system ever observes a half-split world.
- **4b.2 — Pure assignment:** post-split entity→region mapping is a pure function of position / stable id, never thread arrival.
- **4b.3 — RNG keying audit (the subtle trap):** §1.4 keys streams by `(worldSeed, regionId | entityId, tick, systemId)`. Entity-scoped streams key on `entityId` → split-invariant, safe. Any **region-scoped** stream keys on `regionId` — which *changes on split* → its draws diverge exactly when a region splits mid-scene (the §1.6 "fight straddles a split" failure). Audit for region-scoped RNG systems; if any exist, re-key or document the constraint.
- **4b.4 — Folia invariant:** a ticking region may not grow; may not begin ticking adjacent to another ticking region; adjacent regions are forced to merge; own a buffer of chunks beyond the perimeter.

**Tests:** the 4b.0 oracle + property tests for each invariant + harness at N cores. Flat hash through a split and a merge is the exit gate.

---

## 5. Milestone 4d — Subsystem cleanses  *(~2 sessions)*

- **§2.3 entity-column single-writer:** mostly enforcement + docs — archetype columns are single-writer by construction (region owns its store). Add a dev-mode ownership assertion analogous to the existing `IN_SYSTEM_THREAD` detector.
- **§2.4 stateless lighting:** a `LIGHTING`-phase declared system, `read(BlockState in radius)` / `write(LightData)`, no global engine state (Starlight model). Cross-region light propagation is a deferred message (one-tick latency, acceptable per §2.4).

**Tests:** lighting system flat hash at 1/N cores; cross-region light propagation behaviorally identical to intra-region (the §1.6 discipline applied to lighting).

---

## 6. Phases 5 & 6 — sketched; design §5 is authoritative

Detailed planning from here would be false precision. Sequence and headline only:

### Phase 5 — Native worldgen acceleration *(~3–4 sessions)*
FFM (`Arena`/`MemorySegment`) off-heap SoA buffers sharing the ECS column layout → scalar Java noise kernel as **oracle and fallback** → optional Vector API (soft dep, `--add-modules jdk.incubator.vector`) and optional native/GPU shim, both behind a runtime capability probe. Worldgen is the *only* contract-legal acceleration target (§4.1). **Tests:** scalar oracle bit-check; accelerated-vs-scalar within fenced tolerance; capability-absent fallback verified.

### Phase 6 — Block-update cascade + redstone partition hook *(4+ sessions; scoped honestly)*
Block updates enqueue rather than recurse; `BLOCK_FX` sub-phases; firing order a deterministic function of position. Partition hook for *independent* networks; **within one coupled network it stays serial — the redstone wall (§2.5), not solved, by the nature of the problem.** Adopt Alternate Current-style update reduction to lower the serial cost. **Tests:** independent-network parallelism flat hash; within-network serial correctness. *Prerequisite: Milestone S (block updates need the live tick loop to be meaningful).*

---

## 7. Cross-cutting test strategy

The `DeterminismHarness` is the gate at **every** milestone — green means "this change altered no behavior," the project's core promise. Every milestone adds tests *of its own kind*:

| Milestone | New test kind | The thing it proves |
|---|---|---|
| K | Cross-language equivalence (Kotlin twin == Java hash) | Kotlin layer is pure convenience |
| S | Live-server replay-diff (regionized) | Engine works in the real tick loop |
| 4b | Split/merge oracle (split == never-split == static) | §1.6 — partition is outcome-neutral |
| 4d | Lighting flat-hash + cross==intra region | Cleanse preserved determinism |
| 5 | Scalar-oracle bit-check + fenced tolerance | Acceleration stays inside the fence |
| 6 | Independent-network parallel; within-network serial | Honest redstone limit |

**Kotlin determinism do/don't (goes in `KOTLIN_MODDING.md`):**
- ❌ **`kotlin.math.{sin,cos,exp,…}` on the tick path** — delegates to `java.lang.Math`, not `StrictMath` (§1.5). ✅ Use `lattice.math.*`. *Enforcement is honest:* a runtime detector here is harder than the RNG/thread detector (you can't cheaply intercept a static `Math.sin` without bytecode instrumentation) — so it's wrappers + doc + optional lint, **not** a runtime flag.
- ❌ **Coroutines on the deterministic tick path** — scheduling nondeterminism, same reasoning as SIMD/GPU (§1.5). Legal only in fenced off-tick zones (§4 IO/gen).
- ⚠️ **`data class` holding mutable references** (e.g. a `MutableList`) — breaks snapshot isolation. Keep components deeply immutable (`val` + immutable types). *Lower severity.*
- ⚠️ **Branching on collection iteration order** (`hashMapOf` etc.) — the commit sort already neutralizes iteration order *on the commit path*, so this only bites if mod *logic* branches on it. *Lower severity.*

---

## 8. Timeline (session-based, anchored to 2026-06-02)

Sessions, not calendar days — the repo works in sessions. Calendar is a rough guide assuming ~1 session/day cadence.

| Window | Milestone | Sessions | Confidence |
|---|---|---|---|
| **now → +3** | **K — Kotlin mod API** | 3 | High (no engine changes) |
| +3 → +6 | S — Live server integration | 2–3 | Medium |
| +6 → +11 | **4b — Dynamic split/merge** | 3–5 | **Low (hardest invariant — high variance)** |
| +11 → +13 | 4d — Cleanses | 2 | Medium |
| +13 → +17 | 5 — Native worldgen | 3–4 | Medium |
| +17 → +21+ | 6 — Block cascade + redstone hook | 4+ | Low (scoped honestly) |

**4b is the high-variance item** — the design itself calls it "the single hardest invariant in the whole design." Treat its estimate as a placeholder, not a commitment; the 4b.0 oracle test will reveal the true difficulty early.

---

## 9. Immediate next action

Begin **K1**: drop to `--release 25`, rerun the 49 tests (must stay green), then add the Kotlin plugin and prove interop. Nothing downstream proceeds until that baseline is green.
