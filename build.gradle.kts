import java.util.zip.ZipInputStream

plugins {
    java
    id("org.spongepowered.gradle.vanilla") version "0.3.2"
}

group = "io.github.mainulf.lattice"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

val mcVersion = "26.1"

minecraft {
    version(mcVersion)
    runs {
        server()
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(26)
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

fun git(workDir: File, vararg args: String) {
    val proc = ProcessBuilder("git", *args)
        .directory(workDir)
        .redirectErrorStream(true)
        .start()
    proc.inputStream.bufferedReader().lines().forEach { logger.lifecycle("  $it") }
    val exit = proc.waitFor()
    if (exit != 0) throw GradleException("git ${args.joinToString(" ")} failed (exit $exit)")
}

fun unzip(jarFile: File, destDir: File) {
    ZipInputStream(jarFile.inputStream().buffered()).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            val target = destDir.resolve(entry.name)
            if (entry.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                target.outputStream().buffered().use { out -> zip.copyTo(out) }
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }
}

// ── Patch workflow ────────────────────────────────────────────────────────────
//
// applyPatches  – extracts decompiled sources to work/server/, inits git, applies .patch files
// rebuildPatches – turns commits above 'vanilla' tag in work/server/ back into patches/server/*.patch
//
// work/ is gitignored (decompiled MC source — never committed, per PLAN §2).
// patches/ is committed (legal — contains only diffs).

val workServerDir: File = layout.projectDirectory.dir("work/server").asFile
val patchServerDir: File = layout.projectDirectory.dir("patches/server").asFile

val sourcesJarFile: File by lazy {
    gradle.gradleUserHomeDir
        .resolve("caches/VanillaGradle/v2/jars/net/minecraft/joined/$mcVersion/joined-$mcVersion-sources.jar")
}

// VanillaGradle reads javaVersion=25 from MC 26.1's launcher manifest and locks runServer
// to a Java 25 toolchain. We only have JDK 26 installed; override to use it.
tasks.withType<JavaExec>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(26))
    })
}

// ── Patched MC source set ──────────────────────────────────────────────────────
// Compiles only the MC files we've modified (listed by include patterns below).
// Output is prepended to runServer's classpath so our versions shadow the jar.
// Compile classpath inherits from main (MC jar + our own compiled classes).
val patchedMcSourceSet = sourceSets.create("patchedMc") {
    java.srcDir("work/server")
    java.include(
        "net/minecraft/server/MinecraftServer.java"
        // Add more files here as we patch them (one per line, comma-separated).
    )
    compileClasspath += sourceSets.main.get().compileClasspath + sourceSets.main.get().output
}

// Prepend patchedMc output so our patched classes take precedence over the MC jar.
tasks.named<JavaExec>("runServer") {
    classpath = files(patchedMcSourceSet.output.classesDirs) + classpath
    dependsOn(patchedMcSourceSet.compileJavaTaskName)
}

tasks.register("applyPatches") {
    group = "lattice"
    description = "Extract decompiled MC sources to work/server and apply committed patches"
    dependsOn("decompile")

    doLast {
        val jar = sourcesJarFile
        require(jar.exists()) {
            "Sources JAR not found: ${jar.absolutePath}\nRun ./gradlew decompile first."
        }

        // Clean slate — reproducible working tree.
        workServerDir.deleteRecursively()
        workServerDir.mkdirs()

        logger.lifecycle("Extracting ${jar.name} to ${workServerDir.relativeTo(projectDir)}/")
        unzip(jar, workServerDir)

        // Initialise a git repo and make the vanilla base commit.
        git(workServerDir.parentFile, "init", workServerDir.name)
        git(workServerDir, "add", "-A")
        git(workServerDir, "commit", "-m",
            "Vanilla MC $mcVersion (unmodified decompile)\n\nBase commit — do not include in patches/server/.")
        git(workServerDir, "tag", "vanilla")

        // Apply patches.
        val patches = patchServerDir.listFiles()
            ?.filter { it.extension == "patch" }
            ?.sortedBy { it.name }
            .orEmpty()

        if (patches.isEmpty()) {
            logger.lifecycle("No patches — working tree is vanilla MC $mcVersion.")
        } else {
            git(workServerDir, *buildList {
                add("am"); add("--3way")
                addAll(patches.map { it.absolutePath })
            }.toTypedArray())
            logger.lifecycle("Applied ${patches.size} patch(es).")
        }

        logger.lifecycle("Patched working tree: ${workServerDir.absolutePath}")
    }
}

tasks.register("rebuildPatches") {
    group = "lattice"
    description = "Regenerate patches/server/*.patch from commits above the 'vanilla' tag"

    doLast {
        require(workServerDir.resolve(".git").exists()) {
            "work/server is not a git repo — run ./gradlew applyPatches first."
        }

        patchServerDir.listFiles()?.filter { it.extension == "patch" }?.forEach { it.delete() }

        git(workServerDir, "format-patch", "vanilla",
            "--output-directory", patchServerDir.absolutePath,
            "--zero-commit", "--full-index", "--no-signature", "--no-stat", "-N",
            "--src-prefix=a/", "--dst-prefix=b/")

        val count = patchServerDir.listFiles()?.count { it.extension == "patch" } ?: 0
        logger.lifecycle("Rebuilt $count patch(es) in patches/server/")
    }
}
