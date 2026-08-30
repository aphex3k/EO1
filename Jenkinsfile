def PBANDJELLY = ""

pipeline {
    agent { label "android-sdk && emulator" }
    environment {
        // Even console ports 5554..5584 (16 slots). ADB's usable adb-port range is ~5555-5586.
        EMULATOR_PORT = "${5554 + 2 * (Math.abs(Integer.parseInt(env.BUILD_NUMBER) % 16))}"
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
                            set -eu
                            SERIAL="emulator-${EMULATOR_PORT}"
                            BOOT_TIMEOUT_SEC=180
                            EMU_LOG="emulator.log"
                            adb devices -l
                            : >"${EMU_LOG}"
                            emulator -verbose -avd "${EMULATOR_NAME}" -no-snapshot -camera-front none -camera-back none -memory 1024 -wipe-data -timezone America/Los_Angeles -no-boot-anim -screen no-touch -no-audio -no-window -partition-size 1024 -port "${EMULATOR_PORT}" -no-metrics -selinux permissive -accel on -gpu swiftshader_indirect >"${EMU_LOG}" 2>&1 &
                            EMU_PID=$!
                            device_online() {
                              adb devices | grep -qE "^${SERIAL}[[:space:]]+device$"
                            }
                            echo "Waiting for ${SERIAL} (timeout ${BOOT_TIMEOUT_SEC}s)..."
                            i=0
                            while [ "${i}" -lt "${BOOT_TIMEOUT_SEC}" ]; do
                              if device_online; then
                                break
                              fi
                              if ! kill -0 "${EMU_PID}" 2>/dev/null && ! device_online; then
                                echo "Emulator process exited early; log:"
                                tail -n 100 "${EMU_LOG}" || true
                                exit 1
                              fi
                              i=$((i + 1))
                              sleep 1
                            done
                            if ! device_online; then
                              echo "Emulator ${SERIAL} did not appear within ${BOOT_TIMEOUT_SEC}s; log:"
                              tail -n 100 "${EMU_LOG}" || true
                              exit 1
                            fi
                            echo "Waiting for boot completed on ${SERIAL}..."
                            while [ "${i}" -lt "${BOOT_TIMEOUT_SEC}" ]; do
                              if ! device_online; then
                                if ! kill -0 "${EMU_PID}" 2>/dev/null; then
                                  echo "Emulator disappeared during boot; log:"
                                  tail -n 100 "${EMU_LOG}" || true
                                  exit 1
                                fi
                              else
                                boot="$(adb -s "${SERIAL}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
                                if [ "${boot}" = "1" ]; then
                                  break
                                fi
                              fi
                              i=$((i + 1))
                              sleep 1
                            done
                            boot="$(adb -s "${SERIAL}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
                            if [ "${boot}" != "1" ]; then
                              echo "Emulator ${SERIAL} did not finish booting within ${BOOT_TIMEOUT_SEC}s; log:"
                              tail -n 100 "${EMU_LOG}" || true
                              exit 1
                            fi
                            adb -s "${SERIAL}" shell wm size 1080x1920
                            adb -s "${SERIAL}" shell screencap -p /data/data/screenshot_00_before_app_start.png && adb -s "${SERIAL}" pull /data/data/screenshot_00_before_app_start.png
                            adb -s "${SERIAL}" install app/build/outputs/apk/release/app-release.apk
                            adb -s "${SERIAL}" shell am start -n com.aphex3k.eo1/com.aphex3k.eo1.MainActivity
                            sleep 30
                            adb -s "${SERIAL}" shell screencap -p /data/data/screenshot_01_app_start.png && adb -s "${SERIAL}" pull /data/data/screenshot_01_app_start.png
                            compare -metric AE -fuzz 1 .jenkins/reference/screenshot_01_app_start.png screenshot_01_app_start.png screenshot_01_app_start_difference.png || compare -metric AE -fuzz 1 .jenkins/reference/screenshot_01_app_start_b.png screenshot_01_app_start.png screenshot_01_app_start_difference.png
                            adb -s "${SERIAL}" shell monkey -p com.aphex3k.eo1 -v 500 && sleep 5
                            adb -s "${SERIAL}" shell screencap -p /data/data/screenshot_02_post_monkey.png && adb -s "${SERIAL}" pull /data/data/screenshot_02_post_monkey.png
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
