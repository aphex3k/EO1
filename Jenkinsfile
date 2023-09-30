pipeline {
    agent any
    environment { 
        ANDROID_HOME = '/var/android-sdk'
        PATH = "${ANDROID_HOME}/tools:${ANDROID_HOME}/tools/bin:${ANDROID_HOME}/platform-tools:${PATH}"
    }

    stages {
        stage('Checkout') {
            steps {
                withCredentials([gitUsernamePassword(credentialsId: 'gitea-jenkins', gitToolName: 'Default')]) {
                    checkout scm
                    echo 'Clean'
                    sh 'git clean -xdf'
                }                
            }
        }
        stage ('Building Android 🤖') {
            steps {
                script {
                    sh "./gradlew assembleRelease assembleDebug bundleRelease bundleDebug -s"
                }
            }         
        }
        stage('Test') {
            steps {
                script {
                    sh "./gradlew test"
                }
            }
        }
        stage('Deploy') {
            steps {
                echo 'Deploying....'
            }
        }
    }
}