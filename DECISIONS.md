# DECISIONS — Lattice

Lightweight ADR log. Append a dated entry for every pinned choice.

---

## 2026-06-02 — Kotlin as the primary mod-development language

Kotlin becomes the **primary mod-authoring language**; Java stays fully supported. This is a
front-end decision, not a contract change (see `ROADMAP.md` §1).

### Engine stays Java; only the §3 mod-facing API goes Kotlin-first

The deterministic engine (`PhaseScheduler`, `ComponentStore`, `Commit`/`CommitBuffer`,
`RegionCoordinator`/`Region`, `LatticeRng`, `WorldStateHasher`) **remains Java** — it is the hot
tick path, already audited for `strictfp` + `StrictMath` (§1.5), and green. A Kotlin front-end
wraps the *same* §3 types (`Component`, `LatticeSystem`, `Access`, `View`, `Commit`, `Phase`),
compiling down to exactly the types the scheduler already reads. Consistent with "design doc wins":
adding a front-end, not contradicting §3.

### Bytecode target dropped to `--release 25` (amends the "JDK 26" choice below)

Kotlin's max `jvmTarget` is **25** (Kotlin compiler reference, April 2026) — it cannot emit or
reliably read bytecode 26. Resolution: **target `--release 25` across the whole project**, keep the
**JDK 26 toolchain + runtime** unchanged. This is sound because:
- MC 26.1 requires Java 25+ at *runtime* (the 26 runtime satisfies it); it never needs bytecode-26 *output*.
- FFM (§4) is a runtime API on JDK 22+ — unaffected by bytecode target.
- `strictfp` is mandatory since JDK 17 regardless of target — determinism is unaffected.

So this **amends, not contradicts**, the 2026-05-31 "JDK 26" entry: JDK 26 stays as toolchain and
runtime; only the emitted bytecode version drops to 25, necessitated by the Kotlin requirement.
Validation gate: the existing 49 tests must stay green after the `--release 25` change, *before* any
Kotlin is added (ROADMAP K1).

### Kotlin version: pinned 2.x, `jvmTarget = 25` (exact patch confirmed in K1)

Kotlin 2.x via the `kotlin("jvm")` Gradle plugin. `kotlin-stdlib` ships with the loader. Exact patch
version pinned during K1 after confirming clean interop with the bytecode-25 engine classfiles.

### Kotlin determinism rules (enforced by wrappers + doc, see ROADMAP §7)

`kotlin.math.{sin,cos,…}` delegates to `java.lang.Math`, not `StrictMath` — forbidden on the tick
path; `lattice.math.*` StrictMath wrappers are the safe path. Coroutines forbidden on the
deterministic tick path (scheduling nondeterminism, same reasoning as SIMD/GPU §1.5).

---

## 2026-05-31 — Phase 0 initial toolchain

### Minecraft version: 26.1

Mojang switched to CalVer in 2026. `26.1` (released March 2026, latest patch `26.1.2`) is the
current stable release. Chosen because VanillaGradle 0.3.x specifically targets it and because it
is the newest stable release with solid toolchain support. MC 26.1 requires **Java 25** to run.

### JDK: 26 (system — no additional JDK needed)

JDK 26.0.1 is already on PATH. MC 26.1 requires Java 25+, so JDK 26 satisfies everything:
- Running the Gradle daemon (Gradle 9 requires JVM 17+)
- Compiling/running the decompiled server (MC 26.1 requires Java 25+)
- VanillaGradle 0.3.x (requires Java 25)
- Future Lattice engine code (design requires JDK ≥22 for FFM — JDK 26 is fine)

No JDK 21 needed. Do not change `JAVA_HOME` or `archlinux-java` settings.

### Decompile/remap toolchain: VanillaGradle 0.3.2 (SpongePowered)

VanillaGradle 0.3.x handles the full pipeline: download the official Mojang-unobfuscated MC jar,
decompile via Vineflower. Note: **0.3.0 removed de-obfuscation** — MC 26.1 ships with official
readable symbols from Mojang, so no manual remap step is needed.

VanillaGradle 0.2.x was considered but rejected: it has dependency resolution errors on MC 1.21+
(per upstream issue #149), and MC is now on CalVer (26.x) anyway which 0.2.x does not support.

### Gradle: 9.2.1

Required by VanillaGradle 0.3.x. Gradle 9.2.1 requires JVM 17+ for its daemon (JDK 26 satisfies
this). Using Gradle wrapper pinned to 9.2.1 — no system Gradle installation required after
bootstrapping.

### Kotlin DSL for build scripts

`build.gradle.kts` / `settings.gradle.kts`. Better IDE support and type-safety over Groovy DSL.
No reason to use Groovy for a greenfield project.

### GitHub: MainulF account, public repo `lattice`

Repository lives under the `MainulF` GitHub account (Mainul Fahad, mfahad70@stuy.edu).
Visibility: public. Patches-not-source model makes a public repo legally safe (per PLAN §2).
Confirmed with user 2026-05-31.
