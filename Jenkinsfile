def PBANDJELLY = ""

pipeline {
    agent { label "android-sdk && emulator" }
    environment {
        EMULATOR_PORT = "${Math.abs(new Random().nextInt(60000+5001))}"
        EMULATOR_NAME = "EO1-${EMULATOR_PORT}"
        SONAR_TOKEN = credentials('sonar_token_gitea_eo1')
        ANDROID_HOME = '/var/android-sdk'
        PATH = "${ANDROID_HOME}/tools:${ANDROID_HOME}/tools/bin:${ANDROID_HOME}/platform-tools:${PATH}"
        JAVA_OPTS = "-Dorg.gradle.daemon=false"
        GRADLE_OPTS = "-Dorg.gradle.daemon=false"
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
        stage ('Checks') {
            steps {
                script {
                    sh "emulator -accel-check"
                    sh "echo 'no' | /var/android-sdk/cmdline-tools/latest/bin/avdmanager --silent create avd --force --name ${EMULATOR_NAME} --package 'system-images;android-19;default;x86'"
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
                    sh "java --version"
                    sh "./gradlew --no-daemon --version"
                }
                script {
                    sh "sed -i 's/RunImmichTests = true/RunImmichTests = false/g' app/src/test/java/com/aphex3k/eo1/TestConfiguration.java"
                }
                script {
                    PBANDJELLY = "-PBUILD_NUMBER=${env.BUILD_NUMBER}"
                    sh "./gradlew --no-daemon --build-cache clean build test assembleDebug assembleRelease -s $PBANDJELLY -Pandroid.injected.signing.store.file=$KEYSTORE -Pandroid.injected.signing.store.password=$KEYSTORE_PASS -Pandroid.injected.signing.key.alias=$KEY_ALIAS -Pandroid.injected.signing.key.password=$KEY_PASS"
                }
            }
        }
        stage ('Post Build') {
            parallel {
                stage ('Emulator 📱') {
                    steps {
                        script {
                            sh '''
                            #!/bin/bash
                            adb devices -l
                            emulator -verbose -avd ${EMULATOR_NAME} -no-snapshot -camera-front none -camera-back none -memory 1024 -wipe-data -timezone America/Los_Angeles -no-boot-anim -screen no-touch -no-audio -no-window -partition-size 1024 -port ${EMULATOR_PORT} -no-metrics -selinux permissive -accel on -gpu off 1>/dev/null &
                            sleep 45
                            export TID=$(adb devices -l | grep ${EMULATOR_PORT} | tr -s " " | cut -d " " -f 6 | cut -d ":" -f 2)
                            adb -t $TID shell wm size 1080x1920
                            adb -t $TID shell screencap -p /data/data/screenshot_00_before_app_start.png && adb -t $TID pull /data/data/screenshot_00_before_app_start.png
                            adb -t $TID install app/build/outputs/apk/release/app-release.apk
                            adb -t $TID shell am start -n com.aphex3k.eo1/com.aphex3k.eo1.MainActivity
                            sleep 30
                            adb -t $TID shell screencap -p /data/data/screenshot_01_app_start.png && adb -t $TID pull /data/data/screenshot_01_app_start.png
                            compare -metric AE -fuzz 1 .jenkins/reference/screenshot_01_app_start.png screenshot_01_app_start.png screenshot_01_app_start_difference.png || compare -metric AE -fuzz 1 .jenkins/reference/screenshot_01_app_start_b.png screenshot_01_app_start.png screenshot_01_app_start_difference.png
                            adb -t $TID shell monkey -p com.aphex3k.eo1 -v 500 && sleep 5
                            adb -t $TID shell screencap -p /data/data/screenshot_02_post_monkey.png && adb -t $TID pull /data/data/screenshot_02_post_monkey.png
                            '''
                        }   
                    }
                }
                stage ('Scanning') {
                    steps {
                        catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
                            script {
                                if (env.CHANGE_ID) {
                                    sh "./gradlew --no-daemon sonar -Dsonar.pullrequest.base=${CHANGE_TARGET} -Dsonar.pullrequest.branch=${CHANGE_BRANCH} -Dsonar.pullrequest.key=${CHANGE_ID}"
                                } else {
                                    sh "./gradlew --no-daemon sonar -Dsonar.branch.name=${BRANCH_NAME}"
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    post {
        always {
            script {
                archiveArtifacts allowEmptyArchive: false, artifacts: 'app/build/**/*, app/src/test/java/com/aphex3k/eo1/TestConfiguration.java, screenshot*.png', excludes: '', fingerprint: true, onlyIfSuccessful: false
            }
        }
        failure {
            script {
                sh 'echo failure...'
            }
        }
        success {
            script {
                sh 'echo success...'
            }
        }
        cleanup {
            script {
                sh 'git clean -xdf'
                sh "/var/android-sdk/cmdline-tools/latest/bin/avdmanager delete avd --name ${EMULATOR_NAME}"
            }
        }
    }
}
