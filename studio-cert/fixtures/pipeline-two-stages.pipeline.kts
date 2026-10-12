// C1.5 (2026-10-12): pipeline-two-stages — golden fixture
// One stage succeeds, the second fails. The run
// must terminate FAIL with the first stage's
// outcome=success recorded.
// Used by UAT-007, AAT-DUR-003.
pipeline {
    stages {
        stage("first") {
            echo("stage-one-ok")
        }
        stage("second") {
            sh("echo MARKER_FAIL_SECOND; exit 1")
        }
    }
}
