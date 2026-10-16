// =============================================================================
// studio-build-logic/settings.gradle.kts — composite build for the plugin
// =============================================================================
//
// The `studio-build-logic` module is loaded as a composite build by the
// root `settings.gradle.kts` via `pluginManagement { includeBuild(...) }`.
// As a composite build, it must have its own `settings.gradle.kts` and
// its own root project name (it cannot share the parent's root).
//
// The plugin coordinates (id = `dev.pipelinek.studio.fabric-contracts`)
// are derived from the package + filename of the Kotlin source files in
// `src/main/kotlin/dev/pipelinek/studio/build/logic/`. The
// `kotlin-dsl` plugin auto-registers the plugin under that id.
rootProject.name = "studio-build-logic"
