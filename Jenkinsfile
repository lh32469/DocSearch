@Library('GitHub') _

def buildPodYml = libraryResource 'buildPodMaven25.yml'

project = "docs"
branch = ""

pipeline {

  options {
    // Discard everything except the last 10 builds
    buildDiscarder(logRotator(numToKeepStr: '10'))
    // Don't build the same branch concurrently
    disableConcurrentBuilds()

    // Cleanup orphaned branch Kubernetes namespace
    branchTearDownExecutor 'Cleanup'
  }

  agent {
    kubernetes {
      yaml buildPodYml
    }
  }

  stages {

    stage("Building") {
      steps {

        // Build Image Step
        mavenBuild(args: "-DskipTests")

        script {
          branch = env.BRANCH_NAME.toLowerCase()
          registry = "registry.container-registry:5000"
          println "Project/Branch = " + project + "/" + branch
        }

        // Build and push Docker image
        dockerBuild("${registry}/${project}:${branch}")

      }
    }

    stage("Deploy") {
      steps {
        // Deploy application
        deploy(
            project: project,
            branch: branch
         )

        ingress(
            project: project,
            branch: branch
        )

      }
    }

  }
}

