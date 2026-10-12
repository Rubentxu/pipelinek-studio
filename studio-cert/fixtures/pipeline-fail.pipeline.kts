// C1.5 (2026-10-12): pipeline-fail — golden fixture
// A Step that writes a known marker to stdout then exits 1.
// Used by UAT-006, AAT-DUR-002.
pipeline {
    stages {
        stage("fail-with-marker") {
            sh("echo MARKER_FAIL_STDOUT; exit 1")
        }
    }
}
