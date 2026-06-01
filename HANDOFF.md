# HANDOFF — Phase 1 in progress

**Written:** 2026-06-01 (session 2 update)
**Session:** Phase 1 seam + build wiring + VanillaStateHasher
**Next instance:** read `DECISIONS.md`, `CLAUDE.md`, and this file for state.

---

## 1. Where we are

**Phase 0 complete.** **Phase 1 seam infrastructure complete.**

The MinecraftServer seam is wired and smoke-tested. `VanillaStateHasher` exists.
The next step is **baseline capture** (two reproducible vanilla runs → identical per-tick hashes), then carving the first subsystem.

### Critical build facts (do NOT rediscover)

- `net.minecraft:joined:26.1` is on the `compileClasspath` — we can import MC classes in `src/main/java`.
- `runServer` depends on `classes` — our compiled output IS on the runtime classpath.
- `patchedMc` source set compiles only listed files from `work/server/`, output prepended to `runServer` classpath → patched classes shadow the MC jar.
- To add a new patched file: add its path to `java.include(...)` in `build.gradle.kts`, edit in `work/server/`, commit in `work/server/` git, run `./gradlew rebuildPatches`.

### Still needed to exit Phase 1

1. **Baseline capture** — run server with fixed seed, no mob spawning, scripted deterministic entity population, ~100 ticks; print `VanillaStateHasher.hash(server.getAllLevels())` each tick. Run twice; confirm per-tick hashes are identical. Capture as `baseline-hash.log`.
2. **First carved subsystem** — item entity gravity/movement; remove from opaque tick, register declared `MovementSystem`; diff hash vs baseline.
3. **§1.6 analog test** at item scale (Phase 2 gate, but think now).

### How to wire VanillaStateHasher into the server

`MinecraftServer` has `getAllLevels()` (returns `Iterable<ServerLevel>`) and `this.tickCount`.
Add a per-tick call in `LatticeServer.tick()` or a temporary hook in `MinecraftServer.tickServer()`.
Print to stdout: `System.out.printf("tick=%d hash=%016x%n", tick, hash)`.

---

## 2. What was built session 1 (ECS engine)

---

## 2. What was built session 2 (seam + wiring)

| File | What changed |
|---|---|
| `build.gradle.kts` | `patchedMc` source set + `runServer` classpath prepend |
| `src/main/java/.../VanillaStateHasher.java` | FNV-1a hash over `ServerLevel.getAllEntities()`, sorted by `getId()`, fields: x/y/z + deltaMovement x/y/z |
| `patches/server/0002-Add-Lattice-scheduler-seam...patch` | `LatticeServer` field + `lattice.tick(lambda)` in `MinecraftServer.tickServer()` |

---

## 3. What was built session 1 (ECS engine)

### ECS engine (`src/main/java/io/github/mainulf/lattice/ecs/`)

All types are pure Java, zero MC dependency, compile and test via `./gradlew build`.

| File | Role |
|---|---|
| `Component.java` | Marker interface. Implementations must be immutable records. |
| `Phase.java` | Enum: `INPUT → AI → MOVEMENT → COLLISION → BLOCK_FX → LIGHTING → NETWORK` |
| `Access.java` | Static access declaration (`reads`/`writes` component types). `Access.OPAQUE` for undeclared systems. Includes `conflictsWith()`. |
| `View.java` | Read interface backed by frozen pre-phase snapshot. |
| `Commit.java` | Write interface: staged writes applied at phase-end in deterministic order. |
| `LatticeSystem.java` | System interface: `run(View, Commit)`. |
| `SystemDef.java` | `@SystemDef(phase, priority)` annotation. |
| `ComponentColumn.java` | Sorted `long[]` + parallel `Object[]` column. Binary-search lookup. No HashMap. |
| `ComponentStore.java` | One column per component type (lazy). Entity IDs sorted. `snapshot()` deep-copies. |
| `SnapshotView.java` | View impl: reads from snapshot copy, enforces declared access. |
| `CommitBuffer.java` | Commit impl: stages `SetOp`/`RemoveOp`/`SpawnOp`/`KillOp` records. |
| `PhaseScheduler.java` | Serial scheduler. Per-phase: snapshot → run all systems → apply commits in `(priority, systemId, entityId, type)` order. Detects intra-phase declared-system conflicts as hard errors (§3.4). Opaque systems may coexist freely. |
| `WorldStateHasher.java` | FNV-1a 64-bit hasher. Iterates entity IDs sorted. Extracts record components via reflection at full double precision (`doubleToRawLongBits`). |

### Integration seam (`src/main/java/io/github/mainulf/lattice/`)

| File | Role |
|---|---|
| `LatticeServer.java` | Owns a persistent `PhaseScheduler`. Registers the vanilla tick as one opaque system ONCE in the constructor; each `tick(Runnable)` call swaps the closure and runs the scheduler. |

### Tests (15 total, all green)

`EcsTest.java` covers: snapshot isolation (opaque systems), cross-phase visibility, multi-phase pipeline, spawn/kill lifecycle, access enforcement (read/write violations throw), intra-phase declared conflict detection, and the determinism hash oracle (same scenario 3×, identical hash).

`LatticeServerTest.java` covers: vanilla tick runnable invoked, persistent scheduler composes with extra declared systems, runnable swapped per tick, hasher distinguishes 1-ULP position differences.

---

## 3. Key invariants baked in from line one

- **No HashMap iteration in hot paths** — all entity IDs are `long[]` sorted arrays; component columns likewise.
- **`StrictMath` rule** — not yet needed (no transcendental functions), enforced by design notes in code.
- **Splittable RNG** — not yet implemented; Phase 2 item. Do NOT introduce `Level.random` access from any system thread.
- **Snapshot isolation** — `SnapshotView` reads the pre-phase deep copy; the live store is only mutated during `applyCommits()` after all systems in the phase have run.
- **Deterministic commit order** — `(systemPriority ASC, systemId ASC, entityId ASC, componentType.name ASC)`, last-writer-wins.

---

## 4. The next concrete actions (still Phase 1)

### 4a. Vanilla-state hasher (gating item — needed before replay-diff)

The `WorldStateHasher` hashes the **ECS store** which is empty in Phase 1. The Phase-1 exit criterion is "validated by replay-diff against the Phase-0 baseline" — this requires hashing **vanilla MC's world state**, not our (still-empty) ECS store.

Concretely needed:
- A class (e.g., `VanillaStateHasher`) that reads `ServerLevel.entityTickList`, sorts entities by stable ID/UUID, and hashes their positions/velocities via `Entity.getX/Y/Z()`.
- This requires MC dependency (use the `joined-sources.jar` classpath already on the compile path via VanillaGradle).
- A scripted headless scenario: fixed seed, `--nogui`, fixed tick count, prints hash per tick to stdout.
- Capture the vanilla (Phase-0, no Lattice) hash log as the baseline.

### 4b. MinecraftServer patch (opaque whole-tick seam)

Edit `work/server/net/minecraft/server/MinecraftServer.java`:
```java
// Add field near other server fields:
private final io.github.mainulf.lattice.LatticeServer lattice =
    new io.github.mainulf.lattice.LatticeServer();

// In tickServer(), replace:
//   this.tickChildren(haveTime);
// with:
this.lattice.tick(() -> this.tickChildren(haveTime));
```
Then: `./gradlew rebuildPatches` to write `patches/server/0001-Add-Lattice-scheduler-seam.patch`.

### 4c. First carved subsystem

After the patch is in and replay-diff is green (hash of patched server == hash of vanilla), carve out the entity movement loop:
- Scope: independent entities only (no passenger/vehicle; no proximity interactions). Item entities are ideal.
- Remove that loop from the opaque `tickChildren` block.
- Register a declared `MovementSystem` in `LatticeServer`'s constructor with `Access.builder().reads(Position, Velocity).writes(Position).build()`.
- Diff the hash — green means the port is correct.

---

## 5. Open questions / decisions deferred

- **§1.6 test** (region split/merge invariant): Phase 2/4. Keep in mind when designing the first multi-region path.
- **Splittable RNG keying** (`(seed, id, tick, systemId)`): Phase 2. Don't introduce a shared cursor now.
- **`strictfp` / `StrictMath`**: Java 17+ strict by default for `+ - * /`. Use `StrictMath` for any `sin/cos/exp` on the tick path. No violations yet.
- **OOM stubs in decompile**: a few complex methods failed to decompile (harmless; not on any hot path yet).
- **Gradle 9 `Project.exec` workaround**: `ProcessBuilder` in patch tasks works fine. Migrate to `buildSrc/` only if it causes issues.

---

## 6. Phase 0 recap (preserved)

**Stack:**

| Component | Version | Notes |
|---|---|---|
| Minecraft | 26.1 | CalVer (2026); requires Java 25+; Mojang-official unobfuscated jars |
| JDK | 26 (system) | `/usr/lib/jvm/java-26-openjdk`; no JDK 21 or 25 needed |
| Gradle | 9.2.1 | Via wrapper; required by VanillaGradle 0.3.x |
| VanillaGradle | 0.3.2 | SpongePowered's toolchain plugin |
| Build DSL | Kotlin DSL | `build.gradle.kts` + `settings.gradle.kts` |

**What works:** `./gradlew decompile`, `applyPatches`, `rebuildPatches`, `build`, `runServer`.
Verified boot: `[Server thread/INFO]: Done (0.240s)! For help, type "help"`.

**GitHub:** https://github.com/MainulF/lattice (public, `main` branch)

---

## 7. Surprising things not to rediscover

*(same as Phase 0 HANDOFF — preserved for reference)*

- MC switched to CalVer in 2026; latest stable is **26.1**, not "1.21.x".
- VanillaGradle 0.3.0 removed de-obfuscation (MC 26.1 ships with readable Mojang symbols).
- JDK 26 satisfies everything (MC requires Java 25+).
- `Gradle 9` removed `Project.exec()` — use `ProcessBuilder` in task `doLast` blocks.
- Abstract inner task classes in `build.gradle.kts` can't be instantiated — use `buildSrc/` or plain lambda tasks.
- VanillaGradle sources JAR: `~/.gradle/caches/VanillaGradle/v2/jars/net/minecraft/joined/26.1/joined-26.1-sources.jar`
