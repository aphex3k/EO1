def PBANDJELLY = ""

pipeline {
    agent { label "android-sdk && emulator" }
    environment {
        // Even console ports 5554..5584 (16 slots). ADB's usable adb-port range is ~5555-5586.
        EMULATOR_PORT = "${5554 + 2 * (Math.abs(Integer.parseInt(env.BUILD_NUMBER) % 16))}"
        EMULATOR_NAME = "EO1-${EMULATOR_PORT}"
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
                    sh """
                    set -eu
                    echo 'no' | /var/android-sdk/cmdline-tools/latest/bin/avdmanager --silent create avd --force --name ${EMULATOR_NAME} --package 'system-images;android-19;default;x86'
                    AVD_INI="\$HOME/.android/avd/${EMULATOR_NAME}.ini"
                    CONFIG="\$(grep -E '^path=' "\$AVD_INI" | head -1 | cut -d= -f2-)/config.ini"
                    test -f "\$CONFIG"
                    set_ini() {
                      key="\$1"; value="\$2"
                      if grep -qE "^\${key}=" "\$CONFIG"; then
                        tmp="\$(mktemp)"
                        awk -v k="\$key" -v v="\$value" 'BEGIN{FS=OFS="="} \$1==k{\$0=k"="v} {print}' "\$CONFIG" >"\$tmp"
                        mv "\$tmp" "\$CONFIG"
                      else
                        echo "\${key}=\${value}" >>"\$CONFIG"
                      fi
                    }
                    set_ini hw.ramSize 1024
                    set_ini hw.lcd.width 1080
                    set_ini hw.lcd.height 1920
                    set_ini hw.initialOrientation portrait
                    set_ini hw.cpu.ncore 2
                    set_ini hw.camera.back None
                    set_ini hw.camera.front None
                    set_ini hw.audioInput no
                    set_ini hw.audioOutput no
                    set_ini hw.gpu.enabled yes
                    set_ini hw.gpu.mode auto
                    set_ini skin.dynamic no
                    set_ini skin.name 1080x1920
                    set_ini skin.path 1080x1920
                    echo "Patched \$CONFIG"
                    """
                }
            }
        }
        stage ('Build FFmpeg Native') {
            steps {
                withCredentials([gitUsernamePassword(credentialsId: 'gitea-jenkins', gitToolName: 'Default')]) {
                    sh '''
                        set -eu
                        AAR=eo1-ffmpeg/libs/ffmpeg-kit-min-gpl-lts.aar
                        if [ "${REBUILD_FFMPEG_NATIVE:-0}" = "1" ]; then
                            echo "REBUILD_FFMPEG_NATIVE=1: rebuilding FFmpeg native AAR"
                            rm -rf ffmpeg-kit-build
                            git clone --depth 1 https://gitea.codingmerc.com/michael/ffmpeg-kit.git ffmpeg-kit-build
                            cd ffmpeg-kit-build
                            export ANDROID_SDK_ROOT=/var/android-sdk
                            export ANDROID_NDK_ROOT="${ANDROID_SDK_ROOT}/ndk/22.1.7171670"
                            test -d "${ANDROID_NDK_ROOT}"
                            ./android.sh --lts --enable-gpl --enable-x264 \
                              --disable-x86 --disable-x86-64
                            mkdir -p ../eo1-ffmpeg/libs
                            cp prebuilt/bundle-android-aar-lts/ffmpeg-kit/ffmpeg-kit.aar "../${AAR}"
                        else
                            echo "Using committed FFmpeg AAR (set REBUILD_FFMPEG_NATIVE=1 to rebuild)"
                        fi
                        test -f "${AAR}"
                    '''
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
        stage ('Emulator 📱') {
            steps {
                script {
                    sh '''
                            set -eu
                            SERIAL="emulator-${EMULATOR_PORT}"
                            BOOT_TIMEOUT_SEC=180
                            EMU_LOG="emulator.log"
                            adb start-server
                            adb devices -l
                            : >"${EMU_LOG}"
                            emulator -verbose -avd "${EMULATOR_NAME}" -no-snapshot -camera-front none -camera-back none -memory 1024 -wipe-data -timezone America/Los_Angeles -no-boot-anim -screen no-touch -no-audio -no-window -partition-size 1024 -port "${EMULATOR_PORT}" -no-metrics -selinux permissive -gpu auto >"${EMU_LOG}" 2>&1 &
                            EMU_PID=$!
                            device_online() {
                              adb devices | grep -qE "^${SERIAL}[[:space:]]+device$"
                            }
                            dump_emu_log() {
                              echo "----- emulator.log (tail) -----"
                              tail -n 200 "${EMU_LOG}" || true
                              echo "----- FATAL lines -----"
                              grep -E 'FATAL|ERROR|crash' "${EMU_LOG}" || true
                            }
                            echo "Waiting for ${SERIAL} (timeout ${BOOT_TIMEOUT_SEC}s)..."
                            i=0
                            dead_streak=0
                            while [ "${i}" -lt "${BOOT_TIMEOUT_SEC}" ]; do
                              if device_online; then
                                break
                              fi
                              if kill -0 "${EMU_PID}" 2>/dev/null; then
                                dead_streak=0
                              else
                                dead_streak=$((dead_streak + 1))
                                if [ "${dead_streak}" -ge 5 ]; then
                                  echo "Emulator process exited early; log:"
                                  dump_emu_log
                                  exit 1
                                fi
                              fi
                              i=$((i + 1))
                              sleep 1
                            done
                            if ! device_online; then
                              echo "Emulator ${SERIAL} did not appear within ${BOOT_TIMEOUT_SEC}s; log:"
                              dump_emu_log
                              exit 1
                            fi
                            echo "Waiting for boot completed on ${SERIAL}..."
                            dead_streak=0
                            while [ "${i}" -lt "${BOOT_TIMEOUT_SEC}" ]; do
                              if device_online; then
                                dead_streak=0
                                boot="$(adb -s "${SERIAL}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
                                if [ "${boot}" = "1" ]; then
                                  break
                                fi
                              elif kill -0 "${EMU_PID}" 2>/dev/null; then
                                dead_streak=0
                                echo "adb: ${SERIAL} not in device state yet; still waiting..."
                              else
                                dead_streak=$((dead_streak + 1))
                                if [ "${dead_streak}" -ge 5 ]; then
                                  echo "Emulator disappeared during boot; log:"
                                  dump_emu_log
                                  adb devices -l || true
                                  exit 1
                                fi
                              fi
                              i=$((i + 1))
                              sleep 1
                            done
                            boot="$(adb -s "${SERIAL}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
                            if [ "${boot}" != "1" ]; then
                              echo "Emulator ${SERIAL} did not finish booting within ${BOOT_TIMEOUT_SEC}s; log:"
                              dump_emu_log
                              adb devices -l || true
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
    }
    post {
        always {
            script {
                archiveArtifacts allowEmptyArchive: false, artifacts: 'app/build/**/*, app/src/test/java/com/aphex3k/eo1/TestConfiguration.java, screenshot*.png, emulator.log', excludes: '', fingerprint: true, onlyIfSuccessful: false
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
