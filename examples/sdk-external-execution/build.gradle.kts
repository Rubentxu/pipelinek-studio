// R2.1 (2026-10-11): external fixture project for the Step & Plugin
// Lab. This project is the canonical "SDK-agnostic, externally built"
// fixture that Studio's catalog/admission pipeline must accept
// without modification to Studio core.
//
// Spec: docs/02-specifications/STEP_PLUGIN_LAB.md §TestKit +
//       ROADMAP R2 item 1 ("Construir un proyecto fixture externo
//       usando la versión publicada del Plugin SDK").
//
// Scope: this project produces THREE JARs via plain `Jar` tasks
// (no Kotlin compilation, no `pipelinek-plugin-api` dependency
// resolution). The static inspector reads MANIFEST.MF and
// META-INF/services/* as raw files; it does NOT classload. That
// means the fixture can prove the contract (AAT-PLG-001..007) even
// when the SDK jar is not on the build classpath — the canonical
// R2.1 property is "the catalog can discover, classify, and
// inspect" without ever loading a plugin class.
//
// The three JARs:
//
//   1. valid-plugin-0.1.0.jar
//        - Implementation-Title:        "scm-git"
//        - Implementation-Version:      "0.1.0"
//        - Implementation-Vendor:       "dev.rubentxu.pipeline.v2"  (matches SDK vendor)
//        - META-INF/services/.../StepCodec: "com.example.scmm Git StepCodec"
//        - META-INF/pipelinek/plugin.properties: capability=source-control
//        - NO initializer
//      AAT-020 happy path.
//
//   2. incompatible-api-plugin-99.0.0.jar
//        - Implementation-Title:        "scm-svn"
//        - Implementation-Version:      "99.0.0"                    (way out of SDK range)
//        - Implementation-Vendor:       "dev.rubentxu.pipeline.v2"  (vendor matches)
//        - META-INF/services/.../StepCodec: present
//      AAT-021 unhappy path (version out of range).
//
//   3. canary-initializer-plugin-0.1.0.jar
//        - Implementation-Title:        "noisy-plugin"
//        - Implementation-Version:      "0.1.0"
//        - Implementation-Vendor:       "dev.rubentxu.pipeline.v2"
//        - META-INF/services/.../StepCodec: present
//        - META-INF/pipelinek/canary-marker.txt: "CANARY_RAN"
//        - ALSO ships a fake .class file in a non-services path that,
//          if classloaded, would expose the canary marker. The static
//          inspector MUST NOT classload → the marker must never appear
//          in any output from inspection.
//      AAT-021 explicit: "canario de inicializador no corre".

plugins {
    `java-base`
}

val sdkVendor: String = "dev.rubentxu.pipeline.v2"
// Default range for the valid + canary plugins: SDK 0.x.y is in
// range, anything 1.0.0+ is out (the half-open interval excludes
// 1.0.0 itself). The incompatible-api plugin overrides this with
// a range that requires a FUTURE SDK (the 2.x line) — the SDK
// pinned at 0.x.y is NOT in that range, so the plugin is
// refused. This is the R2.2 semantic: the header is the source
// of truth, not the plugin's own version.
val sdkVersionRange: String = "[0.0.0,1.0.0)"
val incompatibleSdkVersionRange: String = "[2.0.0,3.0.0)"

// Each Jar task is registered inline (no helper) because the
// `tasks.register<Jar>(...) { ... }` form inside a helper function
// did not surface the tasks in `gradlew tasks` — they were created
// in a deferred configuration context that the Gradle DSL did not
// resolve. Inlining keeps the task graph simple and predictable.

tasks.register<org.gradle.api.tasks.bundling.Jar>("jarValidPlugin") {
    archiveFileName.set("valid-plugin-0.1.0.jar")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    manifest {
        attributes(
            "Manifest-Version" to "1.0",
            "Implementation-Title" to "valid-plugin",
            "Implementation-Version" to "0.1.0",
            "Implementation-Vendor" to sdkVendor,
            "X-PipelineK-Sdk-Version-Range" to sdkVersionRange,
        )
    }
    from("src/valid-plugin") {
        include("META-INF/**")
    }
}

tasks.register<org.gradle.api.tasks.bundling.Jar>("jarIncompatibleApiPlugin") {
    archiveFileName.set("incompatible-api-plugin-99.0.0.jar")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    manifest {
        attributes(
            "Manifest-Version" to "1.0",
            "Implementation-Title" to "incompatible-api-plugin",
            "Implementation-Version" to "99.0.0",
            "Implementation-Vendor" to sdkVendor,
            "X-PipelineK-Sdk-Version-Range" to incompatibleSdkVersionRange,
        )
    }
    from("src/incompatible-api-plugin") {
        include("META-INF/**")
    }
}

tasks.register<org.gradle.api.tasks.bundling.Jar>("jarCanaryInitializerPlugin") {
    archiveFileName.set("canary-initializer-plugin-0.1.0.jar")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    manifest {
        attributes(
            "Manifest-Version" to "1.0",
            "Implementation-Title" to "canary-initializer-plugin",
            "Implementation-Version" to "0.1.0",
            "Implementation-Vendor" to sdkVendor,
            "X-PipelineK-Sdk-Version-Range" to sdkVersionRange,
        )
    }
    from("src/canary-initializer-plugin") {
        include("META-INF/**")
    }
    // Canary marker: a file that, if classloaded, would expose the
    // marker. The static inspector MUST NOT classload → the marker
    // must never appear in any output from inspection. We ship the
    // marker as a TEXT file in META-INF/pipelinek (NOT in services
    // or as a .class). The text file is metadata, not bytecode.
    from("src/canary-initializer-plugin/canary-marker.txt") {
        into("META-INF/pipelinek")
    }
    // Ship a FAKE .class file in a non-services path. If the static
    // inspector ever classloaded the JAR, the JVM would resolve this
    // class and the canary marker would surface. The test pins that
    // this never happens.
    from("src/canary-initializer-plugin/CanaryInit.class") {
        into("com/example")
    }
}

// Wire the `assemble` task (from `java-base`) to depend on our
// three custom jar tasks. Without this, `assemble` is a no-op
// because `java-base` alone does not register a default `jar`
// task that the assemble lifecycle hooks would pick up.
afterEvaluate {
    tasks.named("assemble") {
        dependsOn(
            "jarValidPlugin",
            "jarIncompatibleApiPlugin",
            "jarCanaryInitializerPlugin",
        )
    }
}
