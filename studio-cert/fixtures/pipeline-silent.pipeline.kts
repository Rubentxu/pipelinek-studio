// C1.5 (2026-10-12): pipeline-silent — golden fixture
// A Step that produces no stdout / no stderr.
// Used by UAT-008.
pipeline {
    stages {
        stage("silent") {
            sh("true")
        }
    }
}
