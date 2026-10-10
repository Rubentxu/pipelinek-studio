plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption


tasks.withType<Test> {
    useJUnitPlatform()
    // B6.0: the standalone tests read the
    // produced tarball + its launcher. The
    // test must run after the standalone
    // dist, so the artifact is present.
    dependsOn("standaloneDist")
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

// CLI build needs ktor client (CIO + content-negotiation + kotlinx-json)
dependencies {
    implementation(project(":studio-domain"))
    implementation(project(":studio-application"))
    implementation(project(":studio-http"))
    implementation(project(":studio-infra-storage"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.bundles.ktor.server)
    implementation("io.ktor:ktor-client-cio:3.0.1")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.1")
    implementation(libs.logback.classic)
    implementation(libs.coroutines.core)
    // B5.6 (2026-10-10): the studio-cli
    // uses Flyway's info() + migrate() APIs
    // to expose `studio db status` + `studio
    // db upgrade`. Flyway is also a
    // transitive dep of studio-infra-storage,
    // but `implementation` is not transitive
    // to the compile classpath, so we
    // declare it explicitly.
    implementation(libs.flyway.core)
    // SQLite JDBC is required for the
    // `jdbc:sqlite:` URL.
    implementation(libs.xerial.sqlite.jdbc)

    testImplementation(libs.bundles.junit5)
    testImplementation(libs.kotlin.test)
}

application {
    mainClass.set("dev.pipelinek.studio.cli.MainKt")
}

// B5.1 (2026-10-10): distribution extras
// (manifest.json + sbom.json). The
// `distExtras` task depends on `distTar`
// and writes the two files under
// `studio-cli/build/dist-extras/`.
val studioVersionB51 = "0.1.0-alpha.1"
val distExtrasDirB51 = layout.buildDirectory.dir("dist-extras")

val distExtras by tasks.registering {
    group = "distribution"
    description = "Generates manifest.json + sbom.json for the studio-cli distribution."
    dependsOn("distTar")
    outputs.dir(distExtrasDirB51)
    // The task captures references to
    // top-level script functions
    // (parseMavenCoordinate, sha256OfFile,
    // jsonStr). The configuration cache
    // refuses to serialize script object
    // references, so the task is opted out
    // of the cache. The task still benefits
    // from Gradle's incremental build
    // because `dependsOn("distTar")` is
    // cached.
    notCompatibleWithConfigurationCache("task captures script-level function references")

    doLast {
        val outDir = distExtrasDirB51.get().asFile.toPath()
        outDir.toFile().deleteRecursively()
        outDir.toFile().mkdirs()

        val distTarFile = layout.buildDirectory
            .file("distributions/studio-cli-$studioVersionB51.tar")
            .get().asFile
        require(distTarFile.exists()) {
            "distTar output not found: $distTarFile"
        }
        val workDir = outDir.resolve("dist")
        workDir.toFile().deleteRecursively()
        workDir.toFile().mkdirs()
        exec {
            commandLine("tar", "-xf", distTarFile.absolutePath, "-C", workDir.toString())
        }
        val distRoot = workDir.resolve("studio-cli-$studioVersionB51")

        // Walk the distribution tree and
        // collect (relpath, size, sha256)
        // entries. Sorted by relpath for
        // determinism.
        val manifestEntries = mutableListOf<Triple<String, Long, String>>()
        Files.walk(distRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .sorted(compareBy { distRoot.relativize(it).toString() })
                .forEach { path ->
                    val rel = distRoot.relativize(path).toString()
                    val size = Files.size(path)
                    val md = MessageDigest.getInstance("SHA-256")
                    Files.newInputStream(path).use { input ->
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            md.update(buf, 0, n)
                        }
                    }
                    val sha = md.digest().joinToString("") { "%02x".format(it) }
                    manifestEntries += Triple(rel, size, sha)
                }
        }
        val buildAt = System.currentTimeMillis()
        val manifestJson = buildString {
            appendLine("{")
            appendLine("""  "format": "studio-distribution-manifest",""")
            appendLine("""  "version": "$studioVersionB51",""")
            appendLine("""  "buildAt": $buildAt,""")
            appendLine("""  "files": [""")
            manifestEntries.forEachIndexed { i, (p, s, sh) ->
                val comma = if (i < manifestEntries.size - 1) "," else ""
                appendLine("""    { "path": ${jsonStr(p)}, "size": $s, "sha256": "$sh" }$comma""")
            }
            appendLine("""  ]""")
            appendLine("}")
        }
        Files.writeString(outDir.resolve("manifest.json"), manifestJson)

        // Build the SBOM from the runtime
        // classpath.
        val components = configurations.runtimeClasspath.get().files
            .filter { it.name.endsWith(".jar") }
            .mapNotNull { jar ->
                val base = jar.name.removeSuffix(".jar")
                val parts = base.split("-")
                if (parts.size < 2) null
                else {
                    val v = parts.last()
                    val n = parts.dropLast(1).joinToString("-")
                    val md = MessageDigest.getInstance("SHA-256")
                    Files.newInputStream(jar.toPath()).use { input ->
                        val buf = ByteArray(8192)
                        while (true) {
                            val k = input.read(buf)
                            if (k <= 0) break
                            md.update(buf, 0, k)
                        }
                    }
                    val sha = md.digest().joinToString("") { "%02x".format(it) }
                    listOf("(project)", n, v, sha)
                }
            }
            .sortedWith(compareBy({ it[0] }, { it[1] }, { it[2] }))
        val sbomJson = buildString {
            appendLine("{")
            appendLine("""  "format": "studio-distribution-sbom",""")
            appendLine("""  "version": "$studioVersionB51",""")
            appendLine("""  "components": [""")
            components.forEachIndexed { i, c ->
                val comma = if (i < components.size - 1) "," else ""
                val g = c[0]; val n = c[1]; val v = c[2]; val sha = c[3]
                appendLine("""    { "group": ${jsonStr(g)}, "name": ${jsonStr(n)}, "version": ${jsonStr(v)}, "sha256": "$sha" }$comma""")
            }
            appendLine("""  ]""")
            appendLine("}")
        }
        Files.writeString(outDir.resolve("sbom.json"), sbomJson)

        logger.lifecycle("dist-extras: wrote ${outDir.resolve("manifest.json")} (${manifestEntries.size} files)")
        logger.lifecycle("dist-extras: wrote ${outDir.resolve("sbom.json")} (${components.size} components)")
    }
}

fun parseMavenCoordinate(jarName: String, jarFile: java.io.File): List<String> {
    val base = jarName.removeSuffix(".jar")
    val parts = base.split("-")
    if (parts.size < 2) return emptyList()
    val version = parts.last()
    val name = parts.dropLast(1).joinToString("-")
    val md = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(jarFile.toPath()).use { input ->
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
    }
    val sha = md.digest().joinToString("") { "%02x".format(it) }
    return listOf("(project)", name, version, sha)
}

fun sha256OfFile(path: java.nio.file.Path): String {
    val md = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

fun jsonStr(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// B6.0 (2026-10-10): single-binary JVM
// distribution via jlink. The custom JRE
// is bundled in the tar; the launcher uses
// the bundled JRE, so the operator does NOT
// need a system JDK 21 to run `studio`.
//
// The jlink step is platform-specific
// (Linux jlink produces a Linux JRE; macOS
// jlink produces a macOS JRE). The build
// runs on the host's JDK, so the artifact
// is the host's platform. A multi-archive
// is a future B6.x.

val jlinkModulesB60 = listOf(
    "java.base",
    "java.desktop",
    "java.instrument",
    "java.logging",
    "java.management",
    "java.naming",
    "java.net.http",
    "java.security.jgss",
    "java.security.sasl",
    "java.sql",
    "java.xml",
    "jdk.unsupported",
)

val jlinkRuntimeB60 = layout.buildDirectory.dir("jlink-runtime")

val jlinkRuntime by tasks.registering {
    group = "distribution"
    description = "Builds a custom JRE (jlink) with the modules the studio needs."
    outputs.dir(jlinkRuntimeB60)
    notCompatibleWithConfigurationCache("task captures script-level function references")
    doLast {
        val outDir = jlinkRuntimeB60.get().asFile.toPath()
        if (Files.exists(outDir)) {
            outDir.toFile().deleteRecursively()
        }
        // Resolve the JDK's jmods directory
        // from the Gradle JDK toolchain.
        // Gradle's `javaToolchains` exposes
        // the path; we use the JAVA_HOME
        // env var as a fallback (the build
        // always runs on a JDK with jmods).
        val javaHome = System.getenv("JAVA_HOME")
            ?: error("JAVA_HOME must point to a JDK with jmods/ for the jlink step")
        val jmods = Paths.get(javaHome, "jmods")
        require(Files.isDirectory(jmods)) {
            "jmods directory not found at $jmods; the build must run on a JDK (not a JRE)"
        }
        val modules = jlinkModulesB60.joinToString(",")
        exec {
            commandLine(
                "jlink",
                "--module-path", jmods.toString(),
                "--add-modules", modules,
                "--output", outDir.toString(),
                "--strip-debug",
                "--compress=zip-9",
                "--no-header-files",
                "--no-man-pages",
            )
        }
        logger.lifecycle("jlink: produced ${outDir} (custom JRE for the studio)")
    }
}

val standaloneB60 = layout.buildDirectory.dir("standalone")

val standaloneDist by tasks.registering {
    group = "distribution"
    description = "Builds a single-binary tar with a custom JRE + the studio CLI."
    dependsOn("installDist", jlinkRuntime)
    outputs.dir(standaloneB60)
    notCompatibleWithConfigurationCache("task captures script-level function references")
    doLast {
        val outDir = standaloneB60.get().asFile.toPath()
        if (Files.exists(outDir)) {
            outDir.toFile().deleteRecursively()
        }
        outDir.toFile().mkdirs()
        val installRoot = layout.buildDirectory.dir("install/studio-cli").get().asFile.toPath()
        val jreRoot = jlinkRuntimeB60.get().asFile.toPath()
        val stageDir = outDir.resolve("studio-cli-standalone-$studioVersionB51")
        stageDir.toFile().deleteRecursively()
        stageDir.toFile().mkdirs()
        // Copy the install dir (lib/ + bin/).
        copyDirectory(installRoot, stageDir)
        // Replace the system-JRE launcher
        // with one that uses the bundled JRE.
        writeStandaloneLauncher(stageDir.resolve("bin/studio"))
        // Copy the custom JRE.
        val jreStage = stageDir.resolve("jre")
        jreStage.toFile().mkdirs()
        copyDirectory(jreRoot, jreStage)
        // Produce the tar.
        val tarFile = outDir.resolve("studio-cli-standalone-$studioVersionB51.tar")
        if (Files.exists(tarFile)) {
            Files.delete(tarFile)
        }
        exec {
            commandLine(
                "tar", "-cf", tarFile.toString(),
                "-C", outDir.toString(),
                "studio-cli-standalone-$studioVersionB51",
            )
        }
        logger.lifecycle("standalone: ${tarFile} (custom JRE + CLI, no system JDK required)")
    }
}

fun copyDirectory(src: java.nio.file.Path, dst: java.nio.file.Path) {
    Files.walk(src).use { stream ->
        stream.forEach { path ->
            val rel = src.relativize(path)
            val target = dst.resolve(rel.toString())
            if (Files.isDirectory(path)) {
                target.toFile().mkdirs()
            } else {
                target.parent.toFile().mkdirs()
                Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}

fun writeStandaloneLauncher(target: java.nio.file.Path) {
    val script = buildString {
        appendLine("#!/usr/bin/env bash")
        appendLine("# B6.0 (2026-10-10): the standalone launcher uses the bundled JRE.")
        appendLine("# The operator does NOT need a system JDK 21.")
        appendLine("set -e")
        appendLine("SCRIPT_DIR=\"\$(cd \"\$(dirname \"\$0\")\" && pwd)\"")
        appendLine("STUDIO_HOME=\"\$(dirname \"\$SCRIPT_DIR\")\"")
        appendLine("JAVA_HOME=\"\$STUDIO_HOME/jre\"")
        appendLine("export JAVA_HOME")
        appendLine("exec \"\$JAVA_HOME/bin/java\" -cp \"\$STUDIO_HOME/lib/*\" dev.pipelinek.studio.cli.MainKt \"\$@\"")
    }
    Files.writeString(target, script)
    target.toFile().setExecutable(true, false)
}
