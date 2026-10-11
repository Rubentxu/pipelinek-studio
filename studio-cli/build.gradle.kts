plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID


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
//
// R-VER-001 follow-up (2026-10-11): read
// the version from gradle.properties
// (single source of truth) instead of
// hardcoding the version literal here.
// The standalone tar + CycloneDX 1.5 strict
// emitter therefore automatically pick
// up the release bump without code edits.
val studioVersionB51: String =
    (project.findProperty("studio.version") as String?)
        ?: error("gradle.properties:studio.version is missing")
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

        // B5.1 follow-up (2026-10-10):
        // emit a STRICT CycloneDX 1.5
        // SBOM alongside the plain
        // sbom.json. The plain format is
        // operator-readable; the strict
        // format is consumable by
        // CycloneDX tooling (GUAC,
        // Dependency-Track, Syft, Grype,
        // etc.) that requires
        // bomFormat/specVersion/purl/
        // bom-ref/metadata per the
        // CycloneDX 1.5 spec.
        //
        // The build script can't import
        // the dev.pipelinek.studio.cli
        // package (the build script is
        // compiled before the project
        // classes are), so we emit the
        // JSON by hand. The shape is
        // pinned by CycloneDxB51Test;
        // any drift between the build
        // output and the test expectation
        // fails the suite.
        val cdxBuildAt = System.currentTimeMillis()
        val cdxSerial = "urn:uuid:" + UUID.randomUUID().toString()
        val cdxTimestamp = Instant.ofEpochMilli(cdxBuildAt)
            .toString() // ISO-8601 Z form
        val cdxRootBomRef = "pkg:maven/dev.pipelinek:studio-cli@$studioVersionB51"
        val cdxJson = buildString {
            appendLine("{")
            appendLine("""  "bomFormat": "CycloneDX",""")
            appendLine("""  "specVersion": "1.5",""")
            appendLine("""  "serialNumber": ${jsonStr(cdxSerial)},""")
            appendLine("""  "version": 1,""")
            appendLine("""  "metadata": {""")
            appendLine("""    "timestamp": ${jsonStr(cdxTimestamp)},""")
            appendLine("""    "tools": [""")
            appendLine("""      { "vendor": "Rubentxu", "name": "pipelinek-studio", "version": ${jsonStr(studioVersionB51)} }""")
            appendLine("""    ],""")
            appendLine("""    "component": {""")
            appendLine("""      "type": "application",""")
            appendLine("""      "group": "dev.pipelinek",""")
            appendLine("""      "name": "studio-cli",""")
            appendLine("""      "version": ${jsonStr(studioVersionB51)},""")
            appendLine("""      "bom-ref": ${jsonStr(cdxRootBomRef)}""")
            appendLine("""    }""")
            appendLine("""  },""")
            appendLine("""  "components": [""")
            components.forEachIndexed { i, c ->
                val comma = if (i < components.size - 1) "," else ""
                val g = c[0]; val n = c[1]; val v = c[2]; val sha = c[3]
                val purl = "pkg:maven/$g:$n@$v"
                appendLine("""    { "type": "library", "group": ${jsonStr(g)}, "name": ${jsonStr(n)}, "version": ${jsonStr(v)}, "purl": ${jsonStr(purl)}, "hashes": [ { "alg": "SHA-256", "content": "$sha" } ], "bom-ref": ${jsonStr(purl)} }$comma""")
            }
            appendLine("""  ],""")
            appendLine("""  "dependencies": [""")
            appendLine("""    { "ref": ${jsonStr(cdxRootBomRef)}, "dependsOn": [""")
            components.forEachIndexed { i, c ->
                val comma = if (i < components.size - 1) "," else ""
                val g = c[0]; val n = c[1]; val v = c[2]
                val purl = "pkg:maven/$g:$n@$v"
                appendLine("""      ${jsonStr(purl)}$comma""")
            }
            appendLine("""    ] }""")
            appendLine("""  ]""")
            appendLine("}")
        }
        Files.writeString(outDir.resolve("sbom.cdx.json"), cdxJson)

        logger.lifecycle("dist-extras: wrote ${outDir.resolve("manifest.json")} (${manifestEntries.size} files)")
        logger.lifecycle("dist-extras: wrote ${outDir.resolve("sbom.json")} (${components.size} components)")
        logger.lifecycle("dist-extras: wrote ${outDir.resolve("sbom.cdx.json")} (CycloneDX 1.5 strict)")
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
    description = "Builds a single-binary tar with a custom JRE + the studio CLI + a bundled Node."
    dependsOn("installDist", jlinkRuntime, nodeBundle)
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
        // B6.3: add a Windows .bat shim that
        // wraps the same bundled JRE. The tar
        // ships BOTH launchers; Scoop on
        // Windows picks the .bat.
        writeStandaloneBatLauncher(stageDir)
        // Copy the custom JRE.
        val jreStage = stageDir.resolve("jre")
        jreStage.toFile().mkdirs()
        copyDirectory(jreRoot, jreStage)
        // B7.0: copy the bundled Node binary.
        // The launcher's `studio mcp` path
        // uses this when present, falling
        // back to PATH only if absent.
        val nodeBundleDir = nodeBundleB70.get().asFile.toPath()
        val nodeStage = stageDir.resolve("node")
        nodeStage.toFile().mkdirs()
        val nodeEntries = Files.list(nodeBundleDir).use { stream ->
            stream.filter { !it.fileName.toString().endsWith(".tar.xz") }
                .filter { !it.fileName.toString().equals("extract") }
                .toList()
        }
        for (entry in nodeEntries) {
            Files.copy(entry, nodeStage.resolve(entry.fileName.toString()))
        }
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
        appendLine("# B7.0 (2026-10-10): STUDIO_HOME is exported so `studio mcp`")
        appendLine("# can prefer the bundled node binary over a system node.")
        appendLine("# The operator does NOT need a system JDK 21 nor a system Node 20.")
        appendLine("set -e")
        appendLine("SCRIPT_DIR=\"\$(cd \"\$(dirname \"\$0\")\" && pwd)\"")
        appendLine("STUDIO_HOME=\"\$(dirname \"\$SCRIPT_DIR\")\"")
        appendLine("JAVA_HOME=\"\$STUDIO_HOME/jre\"")
        appendLine("export JAVA_HOME")
        appendLine("export STUDIO_HOME")
        appendLine("exec \"\$JAVA_HOME/bin/java\" -cp \"\$STUDIO_HOME/lib/*\" dev.pipelinek.studio.cli.MainKt \"\$@\"")
    }
    Files.writeString(target, script)
    target.toFile().setExecutable(true, false)
}

fun writeStandaloneBatLauncher(stageDir: java.nio.file.Path) {
    // B6.3 (2026-10-10): the Windows .bat
    // shim wraps the bundled jlink JRE on
    // Windows. The tar ships BOTH a bash
    // launcher (Linux/macOS) and a .bat
    // shim (Windows). Scoop on Windows
    // picks the .bat via the manifest's
    // "bin" entry.
    val target = stageDir.resolve("bin/studio.bat")
    val script = buildString {
        appendLine("@echo off")
        appendLine("rem B6.3 (2026-10-10): Windows launcher for the standalone tar.")
        appendLine("rem The operator does NOT need a system JDK 21.")
        appendLine("setlocal")
        appendLine("set SCRIPT_DIR=%~dp0")
        appendLine("set STUDIO_HOME=%SCRIPT_DIR%..")
        appendLine("set JAVA_HOME=%STUDIO_HOME%\\jre")
        appendLine("set PATH=%JAVA_HOME%\\bin;%PATH%")
        appendLine("rem B7.0 (2026-10-10): ensure the")
        appendLine("rem bundled node.exe is reachable")
        appendLine("rem before the system PATH (the")
        appendLine("rem MCP stdio adapter needs it).")
        appendLine("set PATH=%STUDIO_HOME%\\node;%PATH%")
        appendLine("\"%JAVA_HOME%\\bin\\java.exe\" -cp \"%STUDIO_HOME%\\lib\\*\" dev.pipelinek.studio.cli.MainKt %*")
    }
    Files.writeString(target, script)
}

val nodeBundleB70 = layout.buildDirectory.dir("node-bundle")
// The input dir is the PROJECT ROOT's
// build/node-bundle-input/ (not the
// module's). scripts/b7.0-fetch-node.sh
// uses git rev-parse --show-toplevel as
// the default PROJECT_ROOT so the cache
// is shared across modules. Resolving
// here via rootProject.layout keeps both
// sides in sync.
val nodeBundleInputB70 = rootProject.layout.buildDirectory.dir("node-bundle-input")

// B7.0 (2026-10-10): bundle a Node 20
// binary inside the standalone tar so the
// operator does NOT need to install Node
// separately for `studio mcp`. The
// tarball is pre-fetched by
// scripts/b7.0-fetch-node.sh into
// build/node-bundle-input/ because the
// Gradle JVM sandbox blocks URL access
// to remote hosts (the host shell can
// curl, but the daemon can't reach the
// network). The fetch script is
// idempotent and caches the tarball
// across builds.
val nodeBundleVersionB70 = "v20.18.0"

// nodeBundleFetch runs the fetch script
// and is depended on by nodeBundle so
// the input dir always exists when
// nodeBundle's input snapshot is taken.
val nodeBundleFetch by tasks.registering {
    group = "distribution"
    description = "Pre-fetches the Node 20 tarball via scripts/b7.0-fetch-node.sh."
    outputs.dir(nodeBundleInputB70)
    doLast {
        val outDir = nodeBundleInputB70.get().asFile.toPath()
        outDir.toFile().mkdirs()
        exec {
            workingDir = project.rootDir
            commandLine("bash", "${project.rootDir}/scripts/b7.0-fetch-node.sh")
        }
    }
    notCompatibleWithConfigurationCache("runs an external script that hits the network")
}

val nodeBundle by tasks.registering {
    group = "distribution"
    description = "Extracts a Node 20 binary from a pre-fetched tarball for the standalone tar."
    dependsOn(nodeBundleFetch)
    inputs.dir(nodeBundleInputB70)
        .withPropertyName("nodeBundleInputB70")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(nodeBundleB70)
    doLast {
        val outDir = nodeBundleB70.get().asFile.toPath()
        if (Files.exists(outDir)) {
            outDir.toFile().deleteRecursively()
        }
        outDir.toFile().mkdirs()
        // B7.0 (2026-10-10): only Linux x86_64
        // is bundled for now. The B7.0
        // contract is "a Node binary ships
        // in the tar"; the cross-platform
        // matrix (macOS + Windows) is a
        // B7.x follow-up that needs a CI
        // matrix.
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        // B7.x (2026-10-10): the platform
        // resolution also picks the archive
        // extension and the extraction
        // strategy. Linux + macOS ship
        // .tar.xz (we xz-decompress + tar-
        // extract in a single pipeline);
        // Windows ships .zip (we unzip
        // the bin/ subtree). The CI matrix
        // builds the standalone tar on
        // each of the 5 supported
        // platforms.
        data class Platform(
            val tag: String,
            val binaryName: String,
            val archiveExt: String,
        )
        val platform = when {
            os.contains("linux") && arch.contains("amd64") -> Platform("linux-x64", "node", "tar.xz")
            os.contains("linux") && arch.contains("aarch64") -> Platform("linux-arm64", "node", "tar.xz")
            os.contains("mac") && arch.contains("amd64") -> Platform("darwin-x64", "node", "tar.xz")
            os.contains("mac") && arch.contains("aarch64") -> Platform("darwin-arm64", "node", "tar.xz")
            os.contains("windows") -> Platform("win-x64", "node.exe", "zip")
            else -> error("unsupported platform for Node bundle: os=$os arch=$arch")
        }
        val archiveName = "node-$nodeBundleVersionB70-${platform.tag}.${platform.archiveExt}"
        val sourceArchive = nodeBundleInputB70.get().asFile.toPath().resolve(archiveName)
        require(Files.isRegularFile(sourceArchive)) {
            "nodeBundle: pre-fetched archive not found at $sourceArchive. " +
                "Run scripts/b7.0-fetch-node.sh first (idempotent; " +
                "downloads to build/node-bundle-input/)."
        }
        val sourceSize = Files.size(sourceArchive)
        require(sourceSize > 1_000_000L) {
            "nodeBundle: pre-fetched archive at $sourceArchive is only " +
                "$sourceSize bytes — expected > 1MB. Re-run " +
                "scripts/b7.0-fetch-node.sh to redownload."
        }
        logger.lifecycle("nodeBundle: consuming pre-fetched $archiveName (${sourceSize}B)")
        // Extract just the bin/ subtree into
        // outDir. The tarball's structure is
        // node-vX.Y.Z-<platform>/{bin,include,...};
        // the .zip has the same layout.
        val extract = outDir.resolve("extract")
        extract.toFile().mkdirs()
        when (platform.archiveExt) {
            "tar.xz" -> {
                // Gradle's `exec` doesn't
                // source the shell, so
                // xz/tar may not be on PATH
                // inside the daemon. Resolve
                // the absolute paths to both
                // utilities, falling back to
                // the plain names.
                val xzPath = listOf("/home/linuxbrew/.linuxbrew/bin/xz", "/usr/bin/xz")
                    .firstOrNull { Files.isExecutable(Paths.get(it)) }
                val tarPath = listOf("/usr/bin/tar", "/bin/tar")
                    .firstOrNull { Files.isExecutable(Paths.get(it)) } ?: "tar"
                if (xzPath != null) {
                    // xz -dc streams the
                    // decompressed tar to
                    // stdout, and tar -xf -
                    // reads from stdin. A
                    // single shell pipeline
                    // keeps both halves in
                    // the same working dir.
                    exec {
                        commandLine(
                            "bash", "-c",
                            "${xzPath} -dc < ${sourceArchive} | ${tarPath} -xf - -C ${extract}",
                        )
                    }
                } else {
                    exec {
                        commandLine(tarPath, "-xJf", sourceArchive.toString(), "-C", extract.toString())
                    }
                }
            }
            "zip" -> {
                // Windows: unzip the .zip
                // into the extract dir.
                // The .zip's structure is
                // node-vX.Y.Z-win-x64/{bin,include,...}.
                val unzipPath = listOf("/usr/bin/unzip", "/bin/unzip")
                    .firstOrNull { Files.isExecutable(Paths.get(it)) } ?: "unzip"
                exec {
                    commandLine(unzipPath, "-q", sourceArchive.toString(), "-d", extract.toString())
                }
            }
            else -> error("unsupported archive extension: ${platform.archiveExt}")
        }
        // The extracted dir name depends on
        // the version; find it.
        val extracted = Files.list(extract).use { stream ->
            stream.filter { Files.isDirectory(it) }.findFirst().orElse(null)
        } ?: error("could not find extracted node dir under $extract")
        // Copy the node binary to outDir/node
        // (or outDir/node.exe on Windows).
        val srcNode = extracted.resolve("bin/${platform.binaryName}")
        require(Files.isRegularFile(srcNode)) {
            "expected $srcNode to exist after extract"
        }
        Files.copy(srcNode, outDir.resolve(platform.binaryName))
        outDir.resolve(platform.binaryName).toFile().setExecutable(true, false)
        logger.lifecycle("nodeBundle: extracted ${platform.binaryName} (${Files.size(outDir.resolve(platform.binaryName))}B)")
    }
    notCompatibleWithConfigurationCache("runs shell tools to extract a tarball")
}
