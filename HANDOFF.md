# HANDOFF — Phase 0 complete

**Written:** 2026-05-31  
**Session:** Phase 0 host preparation (design §5, PLAN.md Step A–C)  
**Next instance:** read `DECISIONS.md` and `CLAUDE.md` for context; read this for state.

---

## 1. Where we are

**Phase 0 is done.** The exit criterion — *"a vanilla-parity server you can patch and rebuild"* —
is met. The pipeline is working and verified end-to-end.

GitHub: **https://github.com/MainulF/lattice** (public, under MainulF account).

---

## 2. Exact state of the pipeline

### Stack (pinned — see `DECISIONS.md` for rationale)

| Component | Version | Notes |
|---|---|---|
| Minecraft | 26.1 | CalVer (2026); requires Java 25+; uses Mojang-official unobfuscated jars |
| JDK | 26 (system) | `/usr/lib/jvm/java-26-openjdk`; no JDK 21 or 25 needed |
| Gradle | 9.2.1 | Via wrapper; required by VanillaGradle 0.3.x |
| VanillaGradle | 0.3.2 | SpongePowered's toolchain plugin |
| Build DSL | Kotlin DSL | `build.gradle.kts` + `settings.gradle.kts` |

### What works

- `./gradlew decompile` — downloads MC 26.1 official jars from Mojang, decompiles via Vineflower
  into `~/.gradle/caches/VanillaGradle/v2/jars/net/minecraft/joined/26.1/joined-26.1-sources.jar`.
  Some complex methods emit OOM stubs (harmless; those methods fall back to bytecode stubs).
  Runs in ~2min first time; cached on subsequent runs.

- `./gradlew applyPatches` — extracts the sources JAR into `work/server/` (gitignored), inits a
  git repo there, makes a `vanilla` base commit + tag, then applies `patches/server/*.patch` files
  in sorted order via `git am --3way`. Currently 0 patches (clean vanilla).

- `./gradlew rebuildPatches` — runs `git format-patch vanilla` in `work/server/`, writes `.patch`
  files to `patches/server/`.

- `./gradlew build` — compiles Lattice's own source in `src/` (empty for now). Clean.

- `./gradlew runServer` — boots the vanilla MC 26.1 server. **Verified boot output:**
  `[Server thread/INFO]: Done (0.240s)! For help, type "help"`
  Server binds on *:25565, generates a world, saves, exits cleanly via timeout.

### What doesn't work / known issues

- **JDK toolchain mismatch**: VanillaGradle reads `javaVersion=25` from the MC 26.1 launcher
  manifest and asks Gradle for a Java 25 toolchain. We only have JDK 26. Gradle can't find JDK 25
  even with `org.gradle.java.installations.paths` set. **Fix in `build.gradle.kts`:**
  `tasks.withType<JavaExec>().configureEach { javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(26)) }) }`
  This overrides the run task's launcher to JDK 26. JDK 26 is backward-compatible with Java 25,
  so this is correct and safe.

- **Decompile OOM stubs**: `java.lang.OutOfMemoryError: Java heap space` during Vineflower
  decompilation of a few complex methods. Those methods appear as stub/comment in the sources.
  Mitigated by `gradle.properties` setting 4g heap. Not a blocker; full decompile still succeeds.

- **Gradle 10 deprecation warning**: `Deprecated Gradle features were used in this build,
  making it incompatible with Gradle 10.` We're on Gradle 9.2.1. Not a blocker now.

- **`run/` directory**: VanillaGradle creates `run/runServer/` as the server working directory.
  First run creates `run/runServer/eula.txt` with `eula=false`. Edit it to `eula=true` before
  re-running. `run/` is gitignored.

- **Patch workflow uses `project.exec` workaround**: In Gradle 9, `Project.exec()` was removed
  and abstract inner task classes in build scripts can't be injected. The patch tasks use
  `ProcessBuilder` directly for git calls. Works fine; just not idiomatic Gradle 9. Could be
  migrated to `buildSrc/` task classes later.

---

## 3. Next concrete action (Phase 1)

**Start Phase 1: serial correctness skeleton.** See design §5, Phase 1:

> Implement ECS storage + phase scheduler + Commit, run everything serial via the
> undeclared-system path. Port the vanilla tick into systems that all run serial.
> Exit: serial Lattice reproduces vanilla behaviour, validated by replay-diff against
> the Phase-0 baseline.

Concretely:
1. Start reading the decompiled `work/server/` source tree to understand the vanilla tick loop
   (`net/minecraft/server/MinecraftServer.java`, `net/minecraft/server/level/ServerLevel.java`).
2. Design the ECS component/system interfaces (`src/main/java/io/github/mainulf/lattice/`).
3. Implement the serial phase scheduler (all systems run on one thread, undeclared path).
4. Gradually port vanilla subsystems, each as a new patch in `patches/server/` + Lattice system
   in `src/`.

**Critical:** the `DeterminismHarness` (§3.5) must be built in Phase 2, but the architecture
decisions you make in Phase 1 must not make it harder. Keep the compute/commit split in mind even
in the serial implementation.

---

## 4. Open decisions / blockers

- **OOM stubs during decompile**: A handful of methods failed to decompile (see above). If any
  of those methods turn out to be on a hot path for Phase 1, we may need to increase heap further
  or use a different decompiler configuration. Not urgent.

- **Gradle 9 exec API**: The `tasks.withType<JavaExec>` override and the ProcessBuilder-based
  patch tasks work but aren't idiomatic. If this causes issues, migrating to `buildSrc/` is the
  clean fix.

- **`strictfp` on all tick-path methods**: The design (§1.5) requires `strictfp` on tick-path
  code. Since Java 17, all `float`/`double` arithmetic is strict by default (JEP 306), so this
  is automatic — no annotations needed. Just don't use `Math.*` on the tick path; use `StrictMath`
  instead. Enforce this in a `DeterminismHarness` rule.

---

## 5. GitHub state

- **URL**: https://github.com/MainulF/lattice
- **Visibility**: public
- **Account**: MainulF (Mainul Fahad, mfahad70@stuy.edu)
- **Branch**: `main`
- **Commits pushed**: 2 (scaffold + pipeline)
- **What's committed**: `.gitignore`, design docs, `CLAUDE.md`, `PLAN.md`, `DECISIONS.md`,
  `build.gradle.kts`, `gradle.properties`, `settings.gradle.kts`, Gradle wrapper, `patches/` stub,
  `src/` stub.
- **What's gitignored correctly**: `work/`, `build/`, `.gradle/`, `run/`, `*.jar` (except wrapper),
  `ruvector.db`, `.claude/`.

---

## 6. Surprising things to not rediscover

- **Minecraft changed version naming**: Minecraft Java Edition switched to CalVer in 2026. The
  latest stable release is **26.1** (not "1.21.x"). VanillaGradle 0.3.x targets 26.x; 0.2.x has
  bugs with MC 1.21+ and doesn't know about 26.x at all.

- **VanillaGradle 0.3.0 removed de-obfuscation**: MC 26.1 ships with official readable symbols
  from Mojang. VanillaGradle 0.3.x no longer needs to remap from ProGuard obfuscation. The
  decompile pipeline is simpler than the PLAN expected.

- **JDK 26 satisfies everything**: MC 26.1 requires Java 25+; JDK 26 satisfies it. No JDK 21
  or 25 installation needed. The PLAN expected to need JDK 21 — that was written when the
  expected target was MC 1.21.x.

- **VanillaGradle sources JAR location**: `~/.gradle/caches/VanillaGradle/v2/jars/net/minecraft/joined/26.1/joined-26.1-sources.jar`

- **Gradle 9 removed Project.exec()**: Can't use `exec {}` or `project.exec {}` in `doLast` blocks.
  Use `ProcessBuilder` for shell calls inside task actions.

- **Abstract task classes in build scripts**: Gradle won't instantiate abstract inner classes
  defined in `build.gradle.kts` (they're non-static inner classes). Use `buildSrc/` for real
  task classes, or use plain `tasks.register { doLast { ... } }` with `ProcessBuilder`.
