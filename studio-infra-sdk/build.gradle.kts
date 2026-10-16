plugins {
    kotlin("jvm")
    `maven-publish`
}


tasks.withType<Test> { useJUnitPlatform() }
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

// SFW F4 Post-Bump Part 12 — Maven SDK publish (F4-5 of the F-delta).
//
// Per docs/pipelinek-studio-fabric-delivery-2026-10-16/overlays/
// pipelinek-studio/docs/04-roadmap/FABRIC_EXECUTION_DELTA_2026-10-16.md
// F4 mandate: "publicar ... Maven SDK (no source.zip como producto)".
// The CLI standalone tarball is already published as the v1.0.0 release
// asset; the SDK JAR + POM is the JVM-consumer product and was missing.
//
// Layering: only `studio-infra-sdk` is published here. The 4 transitive
// project deps (`studio-domain`, `studio-application`, `studio-http`,
// `studio-infra-storage`) stay internal — they form the Studio
// application, not the SDK surface. A consumer adding the SDK
// coordinate will resolve the SDK JAR; if they embed the application
// surface they will pull the rest via the existing project graph
// (Studio is a single repo; the SDK JAR is for downstream JVM tooling
// that wants to reuse `PipelineKRuntimeProbe` / `LocalRunDbFactReadSource`
// / `PipelineKOutputReadPort` against a real PipelineK SDK install).
//
// Distribution route: `mavenLocal()` is wired as the only publishing
// repository here, intentionally. The actual remote registry (Maven
// Central or GitHub Packages) is operator-gated per D-007 — the
// operator gates every release artifact to a remote registry, just
// as they gate `git push` of the v1.0.0 tag. This commit only adds
// the build wiring; promotion to a remote registry is a follow-up
// that requires operator authorization.
publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "dev.pipelinek.studio"
            artifactId = "studio-infra-sdk"
            version = "1.0.0"

            from(components["java"])

            pom {
                name.set("Studio PipelineK SDK (studio-infra-sdk)")
                description.set(
                    "Studio's SDK integration with PipelineK: local runner, " +
                        "runtime probe, output/fact readers, and the H3.2 " +
                        "LocalRunDbFactReadSource. The SDK module is the JVM " +
                        "consumer artifact for tooling that wants to reuse " +
                        "Studio's PipelineK-side adapters against a real " +
                        "PipelineK SDK install."
                )
                url.set("https://github.com/Rubentxu/pipelinek-studio")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                developers {
                    developer {
                        id.set("Rubentxu")
                        name.set("Rubentxu")
                        url.set("https://github.com/Rubentxu")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/Rubentxu/pipelinek-studio.git")
                    developerConnection.set("scm:git:ssh://git@github.com/Rubentxu/pipelinek-studio.git")
                    url.set("https://github.com/Rubentxu/pipelinek-studio")
                    tag.set("v1.0.0")
                }
            }
        }
    }
    repositories {
        mavenLocal()
    }
}

dependencies {
    implementation(project(":studio-domain"))
    implementation(project(":studio-application"))
    implementation(project(":studio-http"))
    implementation(project(":studio-infra-storage"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)
    implementation(libs.coroutines.core)
    implementation(libs.ktor.server.core)
    implementation("io.ktor:ktor-server-netty:3.0.1")
    implementation("io.ktor:ktor-client-cio:3.0.1")
    implementation(libs.logback.classic)
    // R3.2 (2026-10-11): reuse the ktor-bundled kotlinx-serialization
    // runtime (already on the project classpath via studio-http) for the
    // structured JSON decoder. No new artifact needed — we read the JSON
    // tree (JsonObject / JsonArray / JsonPrimitive) and avoid the plugin
    // @Serializable path; no compiler-plugin config required.
    implementation(libs.ktor.serialization.kotlinx.json)

    testImplementation(libs.bundles.junit5)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.mockk)
    testImplementation(libs.ktor.server.test)
    // SFW-H3.2 (2026-10-13): H3.2 ships LocalRunDbFactReadSource, which
    // reads from a per-run run.db SQLite file at runtime. The JDBC API
    // must be on the main classpath so the production source compiles
    // against `java.sql` and `org.xerial:sqlite-jdbc`; the runtime path
    // is also correct (the JDBC driver is loaded by the JVM via the
    // standard `META-INF/services` lookup).
    implementation(libs.xerial.sqlite.jdbc)
}
