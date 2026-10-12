// C1.5 (2026-10-12): pipeline-unicode — golden fixture
// Writes a UTF-8 string that mixes 2/3/4-byte codepoints.
// Used by UAT-017.
pipeline {
    stages {
        stage("unicode") {
            echo("café — 漢字 — 🦊")
        }
    }
}
