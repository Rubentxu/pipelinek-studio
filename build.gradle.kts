// Root build — all real work happens in module subprojects.
// Per-module Kotlin compiler options are set in each subproject's build.gradle.kts
// because the Kotlin plugin must already be applied there.
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.0" apply false
}

allprojects {
    group = "dev.pipelinek.studio"
    version = "0.1.0-alpha.1"
}

tasks.register("cleanAll") {
    group = "build"
    description = "Clean all subprojects build dirs."
    dependsOn(subprojects.map { it.tasks.named("clean") })
}
