// C1.5 (2026-10-12): pipeline-channels — golden fixture
// Writes a distinct marker to stdout and to stderr.
// Used by UAT-018.
pipeline {
    stages {
        stage("two-channels") {
            sh("echo MARKER_STDOUT; echo MARKER_STDERR 1>&2")
        }
    }
}
