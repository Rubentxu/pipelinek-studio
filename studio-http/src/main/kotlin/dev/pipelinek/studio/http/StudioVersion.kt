package dev.pipelinek.studio.http

// R-VER-001 follow-up (2026-10-11): the Studio version is the single
// literal string below. Pinning it here keeps the /api/version response
// honest about which release the running server belongs to. The
// VersionCoherenceB0Test (studio-cli) fails CI if this string diverges
// from gradle.properties:studio.version.
//
// Why a separate file: Kotlin requires imports at the top of the file
// and declarations after them. The constant is consumed by
// StudioHttpModule (/api/version response) and stays co-located
// inside studio-http so the test surface is module-local.
// `internal` would keep it module-local (studio-http) which would
// prevent the cross-module coherence test from reading it. The
// const is a single literal string, not API surface; treating it
// as `public` is consistent with the CLI's StudioCliVersion.VERSION
// (also `public`).
const val STUDIO_VERSION: String = "1.0.0-rc.14"