import java.nio.file.Files

plugins {
    java
    id("org.spongepowered.gradle.vanilla") version "0.3.2"
}

group = "io.github.mainulf.lattice"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

minecraft {
    version("26.1")
    runs {
        server()
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// ── Patch workflow ────────────────────────────────────────────────────────────
//
// Modelled after Paper's patch pipeline:
//   applyPatches  – copies decompiled source into work/server, applies .patch files
//   rebuildPatches – turns git diffs in work/server back into patches/server/*.patch
//
// The work/ directory is gitignored; patches/ is committed.

val workDir = layout.projectDirectory.dir("work/server")
val patchDir = layout.projectDirectory.dir("patches/server")

tasks.register<Exec>("applyPatches") {
    group = "lattice"
    description = "Apply committed patches to the decompiled MC working tree"
    dependsOn("decompile")

    doFirst {
        val work = workDir.asFile
        if (!work.exists()) work.mkdirs()
    }

    // Placeholder: real implementation wires decompile output → work/, then
    // git am's each file from patches/server/. Fleshed out in Phase 0 Step C.
    commandLine("echo", "applyPatches: TODO — wire decompile output in Step C")
    isIgnoreExitValue = true
}

tasks.register<Exec>("rebuildPatches") {
    group = "lattice"
    description = "Rebuild .patch files from git diffs in the MC working tree"

    // Placeholder: real implementation runs git format-patch from work/server
    // back into patches/server/. Fleshed out in Phase 0 Step C.
    commandLine("echo", "rebuildPatches: TODO — wire in Step C")
    isIgnoreExitValue = true
}
