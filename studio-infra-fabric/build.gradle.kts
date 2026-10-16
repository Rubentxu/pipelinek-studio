// =============================================================================
// studio-infra-fabric — Studio's Fabric client (SFW-H2.2c)
// =============================================================================
// The Studio-side adapter that bridges Studio's domain surface to Fabric's
// wire surface, packaged as a Gradle module that depends on **Fabric's
// contracts only**.
//
// # Why contracts only
//
// The whole point of this module is to keep the substitution honest: a
// Studio installation that talks to a Fabric server is a Studio
// installation that does not stop being a Studio installation. The way
// that holds is to depend on Fabric's **published contract** (the
// `RunControl` interface, the `StartRunRequest` shape, the
// `CommandResult` envelope) and to never, ever, reach for
// `:adapters:control-api:client` — which would drag in
// `:adapters:control-api:proto`, the gRPC generator, and the wire.
//
// # Why composite build
//
// Studio and Fabric are sibling repositories. A composite build
// (`includeBuild("../PipelinekFabric")` in Studio's settings.gradle.kts)
// lets `:studio-infra-fabric` resolve `io.jenkins.pipelinek.fabric:run-control`
// without a publish step. The cost is that the iteration cycle re-evaluates
// Fabric's gradle scripts on every change; the win is that there is no
// "I forgot to republish" failure mode.
//
// # What this module ships in H2.2c
//
// The **first** of the four ports the H2.2c contract declares:
// `FabricRunnerPort` + `FabricRunnerAdapter` (submit + cancel).
// The other three (`FabricRunReadPort`, `FabricOutputReadPort`,
// `FabricRunBindingReconciler`) land in H2.2c+1..+3.
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

// SFW F4 Post-Bump Part 16 — sources JAR in the published Maven
// artifact (same rationale as F4 Post-Bump Part 15 on
// :studio-infra-sdk). Conventional for JVM libraries per the
// `distribution-first-release` skill.
java {
    withSourcesJar()
}

dependencies {
    // Studio-side deps — the port and the result types use Studio's
    // domain identifiers (RunId, SnapshotId, ScenarioId), not Fabric's.
    implementation(project(":studio-domain"))
    implementation(project(":studio-application"))
    // SFW H1 Phase B: the
    // FabricContainerLauncher uses
    // PodmanCommandRunner (an
    // abstract port in
    // studio-infra-podman) to start
    // the Fabric server container.
    // The dep arrow is intentional:
    // Fabric integration uses Podman
    // as a transport, not the other
    // way around.
    implementation(project(":studio-infra-podman"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.core)
    // H2.4 (2026-10-13): ConfigRuntimeBackendSelector logs the
    // selected backend on every select() call so an operator can
    // audit which runs used which backend.
    implementation(libs.logback.classic)

    // Fabric contracts only — no control-plane, no worker, no proto,
    // H2.2c: :run-control + :model. H2.2c+1 adds :observation for
    // RunIndex + ProjectionSource + RunSummaryResult + FactReadResult.
    // The version is pinned in
    // `gradle/libs.versions.toml` and
    // matches the alpha.8 release of
    // PipelinekFabric. The contracts
    // are published to the local Maven
    // repo (`~/.m2/repository`) by
    // `./gradlew publishToMavenLocal`
    // in the sibling repo.
    implementation("io.jenkins.pipelinek.fabric:run-control:${libs.versions.fabric.get()}")
    implementation("io.jenkins.pipelinek.fabric:model:${libs.versions.fabric.get()}")
    implementation("io.jenkins.pipelinek.fabric:observation:${libs.versions.fabric.get()}")

    testImplementation(libs.bundles.junit5)
    testImplementation(libs.kotlin.test)
    // SFW-H2.3 (2026-10-13): the end-to-end integration test
    // (RunBindingsH2StoreIntegrationTest) wires the H2.2c
    // write port to the H2.3 SQLite-backed store. The dep
    // is testImplementation only; the production code in
    // :studio-infra-fabric does not depend on the storage
    // layer (the 4 ports take a BindingLookup function,
    // and the storage layer is one of many possible
    // implementations).
    testImplementation(project(":studio-infra-storage"))
}

// R3 SFW H1 Phase A.2 Part 3: hook `publishFabricContractsLocal`
// as a pre-step of compileKotlin. Without this hook, a developer
// who deletes ~/.m2/repository (or pulls a branch that bumps
// `libs.versions.fabric`) would see a confusing
// `Could not find io.jenkins.pipelinek.fabric:run-control:.`
// error at compile time. The hook makes
// `publishFabricContractsLocal` a transitive dep of every
// compileKotlin in this module — including the test compile.
//
// Why this module only (and not the whole build):
// Only :studio-infra-fabric depends on Fabric contracts. The
// other modules depend transitively, but Gradle resolves the
// contracts from the local Maven repo at configuration time
// for any module that depends on this one. Hooking just this
// module is the surgical fix; the dep will already be in the
// local repo by the time any other module compiles.
tasks.named("compileKotlin") {
    dependsOn(":publishFabricContractsLocal")
}
tasks.named("compileTestKotlin") {
    dependsOn(":publishFabricContractsLocal")
}

// SFW F4 Post-Bump Part 16 — Maven publish for studio-infra-fabric.
// Sibling to F4 Post-Bump Part 12 (:studio-infra-sdk) and Part 15
// (sources JAR for the SDK). The Fabric integration is the
// second of the three `studio-infra-*` consumer modules
// (sdk, fabric, podman) and was the missing piece of the
// product portfolio. A JVM consumer wanting to talk to a Fabric
// gRPC server now resolves this artifact instead of having to
// clone the Studio repo.
//
// The publishing repository is `mavenLocal()` only — the
// operator gates promotion to Maven Central / GitHub Packages
// per D-007 (same rationale as F4 Post-Bump Part 12).
publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "dev.pipelinek.studio"
            artifactId = "studio-infra-fabric"
            version = "1.0.0"

            from(components["java"])

            pom {
                name.set("Studio Fabric integration (studio-infra-fabric)")
                description.set(
                    "Studio's Fabric client integration: H2.2c " +
                        "FabricRunnerPort + FabricRunnerAdapter, the " +
                        "canonical StudioFabricGateway + adapter, the " +
                        "ConfigRuntimeBackendSelector, and the " +
                        "FabricContainerLauncher for podman-driven " +
                        "server boot. The module is the JVM consumer " +
                        "artifact for tooling that wants to reuse " +
                        "Studio's Fabric-side adapters against a real " +
                        "Fabric server."
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
