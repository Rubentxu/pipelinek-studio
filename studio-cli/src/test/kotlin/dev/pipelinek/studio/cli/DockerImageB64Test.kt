package dev.pipelinek.studio.cli

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B6.4 (2026-10-10): the Dockerfile
 * contract tests.
 *
 * The S5 mandate says the studio ships an
 * "instalador local". B6.0 delivered a
 * single-binary tar via jlink. B6.4
 * packages that tar into a multi-stage
 * Docker image. The Dockerfile is the
 * wire contract for the image; the tests
 * pin:
 *  1. The Dockerfile is multi-stage
 *     (builder + runtime).
 *  2. The builder compiles the B6.0
 *     standalone tar via gradle.
 *  3. The runtime uses a JRE 21 alpine
 *     base (the smallest standard base
 *     image that runs the bundled jlink
 *     JRE).
 *  4. The standalone tar is copied from
 *     the builder stage to the runtime
 *     image.
 *  5. The data dir is a VOLUME at
 *     /var/lib/pipelinek-studio (the
 *     standard linux data dir).
 *  6. The EXPOSE ports are 7778 (HTTPS)
 *     + 7777 (HTTP via --insecure-http).
 *  7. The ENTRYPOINT is the studio
 *     binary; the CMD starts the server
 *     on 0.0.0.0 with the data dir.
 *  8. The image runs as a non-root user
 *     (uid 1000) for container security.
 *  9. The README documents the build +
 *     run flow.
 *
 * The actual `docker build` is NOT run in
 * this test (Docker is not required to
 * run the test suite). The B6.4
 * certification is the operator's
 * release-time step.
 */
class DockerImageB64Test {

    private fun dockerDir(): Path {
        var cwd: Path? = Path.of("").toAbsolutePath()
        repeat(5) {
            val candidate = cwd!!.resolve("docker")
            if (Files.isDirectory(candidate)) return candidate
            cwd = cwd.parent
        }
        throw AssertionError("docker/ not found from cwd=${Path.of("").toAbsolutePath()}")
    }

    @Test
    fun `B6_4 Dockerfile exists and is multi-stage (builder + runtime)`() {
        val dockerfile = dockerDir().resolve("Dockerfile").toFile()
        assertTrue(
            dockerfile.isFile,
            "Dockerfile must live at docker/Dockerfile, missing at $dockerfile",
        )
        val text = dockerfile.readText()
        // Multi-stage: at least two FROM
        // lines. The builder compiles; the
        // runtime ships.
        val fromCount = Regex("^FROM ", RegexOption.MULTILINE).findAll(text).count()
        assertTrue(
            fromCount >= 2,
            "Dockerfile must be multi-stage (>=2 FROM), got $fromCount FROMs:\n$text",
        )
        assertTrue(
            "AS builder" in text,
            "first stage must be named 'builder', got:\n$text",
        )
        assertTrue(
            "AS runtime" in text,
            "second stage must be named 'runtime', got:\n$text",
        )
    }

    @Test
    fun `B6_4 builder stage compiles the B6_0 standalone tar via gradle`() {
        val text = dockerDir().resolve("Dockerfile").toFile().readText()
        // The builder must:
        //  1. use a JDK 21 base
        //  2. COPY the project source
        //  3. RUN :studio-cli:standaloneDist
        assertTrue(
            Regex("FROM eclipse-temurin:21-jdk AS builder").containsMatchIn(text),
            "builder must use eclipse-temurin:21-jdk, got:\n$text",
        )
        assertTrue(
            "COPY" in text,
            "builder must COPY the project source, got:\n$text",
        )
        assertTrue(
            ":studio-cli:standaloneDist" in text,
            "builder must run :studio-cli:standaloneDist, got:\n$text",
        )
    }

    @Test
    fun `B6_4 runtime stage uses JRE 21 alpine + copies the standalone tar from the builder`() {
        val text = dockerDir().resolve("Dockerfile").toFile().readText()
        // The runtime must:
        //  1. use a JRE 21 alpine base
        //  2. COPY --from=builder the standalone tar
        assertTrue(
            Regex("FROM eclipse-temurin:21-jre-alpine AS runtime").containsMatchIn(text),
            "runtime must use eclipse-temurin:21-jre-alpine, got:\n$text",
        )
        assertTrue(
            Regex("COPY --from=builder[^\\n]*studio-cli/build/standalone/").containsMatchIn(text),
            "runtime must COPY --from=builder the standalone tar from studio-cli/build/standalone/, got:\n$text",
        )
    }

    @Test
    fun `B6_4 data dir is a VOLUME at the standard linux path (var lib pipelinek-studio)`() {
        // R6.6.1 (2026-10-12): the test reads BOTH
        // the multi-stage Dockerfile (kept for
        // reference) and the runtime-only Dockerfile
        // (used by docker/build.sh after the host
        // prebuilds the standalone tar). The contract
        // is the same: a VOLUME at /var/lib/pipelinek-studio
        // owned by the non-root user.
        val dockerfiles = listOf("Dockerfile", "Dockerfile.runtime").map {
            dockerDir().resolve(it).toFile()
        }.filter { it.isFile }
        require(dockerfiles.isNotEmpty()) { "no Dockerfile in docker/" }
        for (f in dockerfiles) {
            val text = f.readText()
            assertTrue(
                "VOLUME [\"/var/lib/pipelinek-studio\"]" in text,
                "data dir must be a VOLUME at /var/lib/pipelinek-studio in ${f.name}, got:\n$text",
            )
            assertTrue(
                "mkdir -p /var/lib/pipelinek-studio" in text,
                "data dir must be created via mkdir -p in ${f.name}, got:\n$text",
            )
            // The original multi-stage Dockerfile
            // uses 'chown -R 1000:1000 ...' (UID 1000
            // IS the studio user). The runtime-only
            // Dockerfile uses 'chown -R 1000:1000' for
            // the same reason. Both are valid.
            assertTrue(
                "chown -R 1000:1000 /var/lib/pipelinek-studio" in text,
                "data dir must be owned by uid 1000 (the studio user) in ${f.name}, got:\n$text",
            )
        }
    }

    @Test
    fun `B6_4 EXPOSE ports are 7778 (HTTPS) + 7777 (HTTP via insecure-http)`() {
        val text = dockerDir().resolve("Dockerfile").toFile().readText()
        assertTrue(
            Regex("EXPOSE\\s+7778\\s+7777").containsMatchIn(text),
            "EXPOSE must include 7778 (HTTPS) + 7777 (HTTP), got:\n$text",
        )
    }

    @Test
    fun `B6_4 ENTRYPOINT is studio binary and CMD starts server with data dir flag`() {
        val text = dockerDir().resolve("Dockerfile").toFile().readText()
        // The entrypoint must be the studio
        // binary (from PATH).
        assertTrue(
            Regex("ENTRYPOINT\\s*\\[\\s*\"studio\"\\s*]").containsMatchIn(text),
            "ENTRYPOINT must be ['studio'], got:\n$text",
        )
        // The CMD must start the server on
        // 0.0.0.0 with the data dir at the
        // volume path.
        assertTrue(
            Regex("CMD\\s*\\[\\s*\"start\".*\"--host\".*\"0\\.0\\.0\\.0\"").containsMatchIn(text),
            "CMD must include start --host 0.0.0.0, got:\n$text",
        )
        assertTrue(
            "--data-dir" in text && "/var/lib/pipelinek-studio" in text,
            "CMD must pass --data-dir /var/lib/pipelinek-studio, got:\n$text",
        )
    }

    @Test
    fun `B6_4 image runs as a non-root user (uid 1000, the studio user)`() {
        val dockerfiles = listOf("Dockerfile", "Dockerfile.runtime").map {
            dockerDir().resolve(it).toFile()
        }.filter { it.isFile }
        require(dockerfiles.isNotEmpty()) { "no Dockerfile in docker/" }
        for (f in dockerfiles) {
            val text = f.readText()
            // Both Dockerfiles run as UID 1000
            // (the canonical 'first human user'
            // on every Linux host). The original
            // multi-stage Dockerfile declares
            // the username 'studio' via adduser;
            // the runtime-only Dockerfile just
            // sets USER 1000. Both are valid.
            assertTrue(
                "USER 1000" in text,
                "image must run as uid 1000 (the studio user) in ${f.name}, got:\n$text",
            )
        }
    }

    @Test
    fun `B6_4 README documents the build + run flow + the move-out note`() {
        val readme = dockerDir().resolve("README.md").toFile().readText()
        // R6.6.1 (2026-10-12): the README
        // documents the Podman-friendly
        // build flow (./docker/build.sh) as
        // well as direct docker build. The
        // contract is: any canonical build
        // command is documented.
        assertTrue(
            "docker build" in readme || "build.sh" in readme || "podman build" in readme,
            "README must document a build command (docker / podman / build.sh)",
        )
        assertTrue(
            "docker run" in readme || "podman run" in readme,
            "README must document a run command (docker / podman)",
        )
        assertTrue(
            "EXPOSE 7778 7777" in readme || "7778:7778" in readme || "-p 7778:7778" in readme,
            "README must document the exposed ports",
        )
        assertTrue(
            "move-out" in readme.lowercase() ||
                "self-contained" in readme.lowercase() ||
                "standalone" in readme.lowercase(),
            "README must document that the image is self-contained / standalone",
        )
    }
}
