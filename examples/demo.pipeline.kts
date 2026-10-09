// Fixture referencial. Verificar sintaxis exacta del DSL en el SDK consumido.
pipeline {
    stages {
        stage("Build") {
            steps {
                sh("echo 'Hola desde PipelineK Studio'")
            }
        }
    }
}
