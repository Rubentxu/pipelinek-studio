// C1.5 (2026-10-12): pipeline-hello — golden fixture
// The simplest possible successful pipeline.
// Used by UAT-001, UAT-002, UAT-013, AAT-ARCH-004.
pipeline {
    stages {
        stage("hello") {
            echo("hello from pipeline-kotlin v2")
        }
    }
}
