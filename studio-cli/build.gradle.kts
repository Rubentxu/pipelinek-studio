plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

import java.security.MessageDigest
import java.nio.file.Files


tasks.withType<Test> { useJUnitPlatform() }
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
