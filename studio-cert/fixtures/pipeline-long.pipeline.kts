// C1.5 (2026-10-12): pipeline-long — golden fixture
// Sleeps long enough for the runner to issue a cancel.
// Used by UAT-010, UAT-012.
pipeline {
    stages {
        stage("long") {
            sh("sleep 30")
        }
    }
}
