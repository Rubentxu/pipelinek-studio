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
}

tasks.withType<Test> { useJUnitPlatform() }
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    // Studio-side deps — the port and the result types use Studio's
    // domain identifiers (RunId, SnapshotId, ScenarioId), not Fabric's.
    implementation(project(":studio-domain"))
    implementation(project(":studio-application"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.core)
    // H2.4 (2026-10-13): ConfigRuntimeBackendSelector logs the
    // selected backend on every select() call so an operator can
    // audit which runs used which backend.
    implementation(libs.logback.classic)

    // Fabric contracts only — no control-plane, no worker, no proto,
    // no gRPC. The composite build resolves these via the sibling
    // PipelinekFabric repo (see settings.gradle.kts).
    // H2.2c: :run-control + :model. H2.2c+1 adds :observation for
    // RunIndex + ProjectionSource + RunSummaryResult + FactReadResult.
    implementation("io.jenkins.pipelinek.fabric:run-control")
    implementation("io.jenkins.pipelinek.fabric:model")
    implementation("io.jenkins.pipelinek.fabric:observation")

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
