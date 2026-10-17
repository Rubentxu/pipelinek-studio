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
    implementation(project(":studio-domain"))
    implementation(project(":studio-application"))
    // Audit P1-04 (decoupling): this module used to depend on
    // :studio-infra-fabric (and, through it, on the published
    // Fabric contract jar) because RunBindingsH2Store implemented
    // the Fabric-side `BindingLookup` seam and used Fabric's
    // `RunId` as a column value type. The persisted identity is
    // opaque and backend-agnostic, so the store now answers the
    // application-layer `UpstreamRunIdLookup` port with the
    // domain's `UpstreamRunId`, and `studio-infra-fabric` adapts
    // it (`BindingLookup.from`). Nothing here needs the Fabric
    // contract jars any more, which is what lets every other
    // module build on a clean runner.
    implementation(libs.kotlin.stdlib)
    implementation(libs.bundles.storage)
    implementation(libs.logback.classic)
    // R2.3 (2026-10-11): FilePluginAdmissionLock uses
    // kotlinx-serialization-json (transitive via ktor's
    // serialization bridge) to read/write plugin.lock.json.
    implementation(libs.ktor.serialization.kotlinx.json)

    testImplementation(libs.bundles.junit5)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.xerial.sqlite.jdbc)
}
