def PBANDJELLY = ""

pipeline {
    agent any
    environment { 
        SONAR_TOKEN = credentials('sonar_token_gitea_eo1')
        ANDROID_HOME = '/var/android-sdk'
        PATH = "${ANDROID_HOME}/tools:${ANDROID_HOME}/tools/bin:${ANDROID_HOME}/platform-tools:${PATH}"
    }

    stages {
        stage ('Description') {
            steps {
                script {
                    currentBuild.displayName = "Electric Objects Replacement App (build #${env.BUILD_NUMBER})"

                    def text = ""
                    for (changeSetList in currentBuild.changeSets) {
                        for (changeSet in changeSetList) {                            
                            text += "- ${changeSet.msg}\n"
                        }
                    }
                    currentBuild.description = text
                }
            }
        }
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
            environment {
                KEYSTORE = credentials('keystore-eo1')
                KEY_PASS = credentials('keystore-eo1-key-password')
                KEYSTORE_PASS = credentials('keystore-eo1-key-store-password')
                KEY_ALIAS = 'EO1'                
            }
            steps {
                script {
                    PBANDJELLY = "-PBUILD_NUMBER=${env.BUILD_NUMBER}"
                    
                    sh "./gradlew clean build test assembleDebug assembleRelease -s $PBANDJELLY -Pandroid.injected.signing.store.file=$KEYSTORE -Pandroid.injected.signing.store.password=$KEYSTORE_PASS -Pandroid.injected.signing.key.alias=$KEY_ALIAS -Pandroid.injected.signing.key.password=$KEY_PASS"
                }
            }
        }
        stage ('Scanning') {
            steps {
                script {
                    try {
                        sh "./gradlew sonar -Dsonar.pullrequest.base=${CHANGE_TARGET} -Dsonar.pullrequest.branch=${CHANGE_BRANCH} -Dsonar.pullrequest.key=${CHANGE_ID}"
                    } catch (Exception e) {
                        sh "./gradlew sonar -Dsonar.branch.name=${BRANCH_NAME}"
                    }
                    
                }
            }
        }
        stage ('Archiving') {
            steps {
                script {
                    archiveArtifacts allowEmptyArchive: false, artifacts: 'app/build/outputs/apk/**/*,app/build/outputs/logs/*,app/build/reports/**/*,app/build/test-results/**/*', excludes: '', fingerprint: true, onlyIfSuccessful: true                
                }       
            }         
        }
    }
    post {
        cleanup {
            script {
                sh 'git clean -xdf'
            }
        }
        failure {
            script {
                sh 'echo failure...'
            }
        }
        always {
            script {
                sh 'echo always...'
            }
        }
        success {
            script {
                sh 'echo success...'
            }
        }
    }
}