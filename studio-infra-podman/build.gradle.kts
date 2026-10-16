// =============================================================================
// studio-infra-podman — Studio's Podman container adapter (SFW-H5.3)
// =============================================================================
// The Studio-side adapter that bridges the [PodmanRunnerPort] abstract
// surface (declared in studio-application/ports/Ports.kt) to a real
// `podman run` invocation. H5.3 ships the abstract surface + a stub
// adapter that is honest about the unwired state; the actual
// `podman run` invocation (the H5 UAT "podman-container run") is a
// follow-up H5.3+ unit.
//
// # Why a separate module
//
// Podman is a different infra from Fabric. The H2.2c discipline
// ("a Studio installation that talks to a Fabric server is a
// Studio installation that does not stop being a Studio installation")
// applies here too: a Studio installation that runs in a Podman
// container is a Studio installation that does not stop being a
// Studio installation. The module keeps the seams narrow.

plugins {
    kotlin("jvm")
    `maven-publish`
}

dependencies {
    implementation(project(":studio-domain"))
    implementation(project(":studio-application"))
    // B6.0: the standalone dist bundles a custom JRE; the test
    // container in CI must include the same JDK 21 baseline. The
    // `kotlin.stdlib` is the minimum; the H5.3 chain-start does
    // not pull in the Podman Java bindings (the H5.3+ follow-up
    // will, when the actual `podman run` invocation is wired).
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Generate JUnit XML reports so the SFW
    // gate counter can include the H5.3+
    // tests in the Studio suite total.
    reports {
        junitXml.required.set(true)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

// SFW F4 Post-Bump Part 17 — sources JAR in the published Maven
// artifact (same rationale as F4 Post-Bump Parts 15 and 16).
java {
    withSourcesJar()
}

// SFW F4 Post-Bump Part 17 — Maven publish for studio-infra-podman.
// Closes the third and last `studio-infra-*` consumer module in
// the product portfolio per the `distribution-first-release`
// skill's product-discovery lens. A JVM consumer wanting to use
// Studio's Podman command-runner adapter resolves
// `dev.pipelinek.studio:studio-infra-podman:1.0.0` from
// mavenLocal() instead of cloning the Studio repo.
//
// The publishing repository is `mavenLocal()` only — operator
// gates promotion to Maven Central / GitHub Packages per D-007
// (same rationale as Parts 12 and 16).
publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "dev.pipelinek.studio"
            artifactId = "studio-infra-podman"
            version = "1.0.0"

            from(components["java"])

            pom {
                name.set("Studio Podman adapter (studio-infra-podman)")
                description.set(
                    "Studio's Podman container adapter: the " +
                        "[PodmanRunnerPort] abstract surface and its " +
                        "honest-stub implementation. The module is the " +
                        "JVM consumer artifact for tooling that wants to " +
                        "reuse Studio's Podman-side adapter against a " +
                        "real Podman install on the host."
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
