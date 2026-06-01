# DECISIONS — Lattice

Lightweight ADR log. Append a dated entry for every pinned choice.

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
