// C1.5 (2026-10-12): pipeline-malicious-text — golden fixture
// Step emits text that looks like HTML, ANSI escapes, and URLs.
// The studio UI must render the text as data, not as a directive.
// Used by UAT-019, AAT-SEC-002.
pipeline {
    stages {
        stage("untrusted-text") {
            echo("<script>alert('xss')</script>")
            echo("\u001B[31mRED\u001B[0m https://attacker.example/steal?token=xyz")
        }
    }
}
