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
    // SFW-H2.3 (2026-10-13): the H2.3 store implements
    // BindingLookup, the seam the four studio-infra-fabric
    // ports (H2.2c..+3) consume. The dep direction is
    // "storage backs the fabric port"; the four ports are
    // unchanged.
    implementation(project(":studio-infra-fabric"))
    // The store uses Fabric's RunId as a column value
    // type. The dep is declared here (not transitively
    // from :studio-infra-fabric) because the contract
    // dep is implementation, not api.
    implementation("io.jenkins.pipelinek.fabric:model")
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
