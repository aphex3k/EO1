#!/bin/bash
#
# Custom ffmpeg-kit library script: build static libde265 for HEIC/HEIF via libheif.
# Uses CMake (avoids host automake/aclocal). Copied into ffmpeg-kit/scripts/android/.
#

mkdir -p "${BUILD_DIR}" || return 1
cd "${BUILD_DIR}" || return 1

# ffmpeg-kit defaults CXXFLAGS to -fno-rtti/-fno-exceptions; libde265 needs RTTI.
DE265_CXXFLAGS="$(echo " ${CXXFLAGS} " | sed 's/ -fno-rtti / /g; s/ -fno-exceptions / /g')"
DE265_CXXFLAGS="${DE265_CXXFLAGS} -frtti -fexceptions"

cmake -Wno-dev \
  -DCMAKE_VERBOSE_MAKEFILE=0 \
  -DCMAKE_C_FLAGS="${CFLAGS}" \
  -DCMAKE_CXX_FLAGS="${DE265_CXXFLAGS}" \
  -DCMAKE_EXE_LINKER_FLAGS="${LDFLAGS}" \
  -DCMAKE_SYSROOT="${ANDROID_SYSROOT}" \
  -DCMAKE_FIND_ROOT_PATH="${ANDROID_SYSROOT}" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX="${LIB_INSTALL_PREFIX}" \
  -DCMAKE_SYSTEM_NAME=Generic \
  -DCMAKE_CXX_COMPILER="${ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt/${TOOLCHAIN}/bin/$CXX" \
  -DCMAKE_C_COMPILER="${ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt/${TOOLCHAIN}/bin/$CC" \
  -DCMAKE_LINKER="${ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt/${TOOLCHAIN}/bin/$LD" \
  -DCMAKE_AR="${ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt/${TOOLCHAIN}/bin/llvm-ar" \
  -DCMAKE_RANLIB="${ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt/${TOOLCHAIN}/bin/llvm-ranlib" \
  -DCMAKE_POSITION_INDEPENDENT_CODE=1 \
  -DCMAKE_SYSTEM_PROCESSOR="$(get_cmake_system_processor)" \
  -DBUILD_SHARED_LIBS=OFF \
  -DENABLE_SDL=OFF \
  -DENABLE_DECODER=ON \
  -DENABLE_ENCODER=OFF \
  "${BASEDIR}/src/${LIB_NAME}" || return 1

make -j$(get_cpu_count) || return 1

make install || return 1

# Ensure pkg-config file is available for FFmpeg / libheif
if [[ -f "${LIB_INSTALL_PREFIX}/lib/pkgconfig/libde265.pc" ]]; then
  cp "${LIB_INSTALL_PREFIX}/lib/pkgconfig/libde265.pc" "${INSTALL_PKG_CONFIG_DIR}" || return 1
elif [[ -f "${BUILD_DIR}/libde265.pc" ]]; then
  cp "${BUILD_DIR}/libde265.pc" "${INSTALL_PKG_CONFIG_DIR}" || return 1
else
  cat > "${INSTALL_PKG_CONFIG_DIR}/libde265.pc" <<EOF
prefix=${LIB_INSTALL_PREFIX}
exec_prefix=\${prefix}
libdir=\${exec_prefix}/lib
includedir=\${prefix}/include

Name: libde265
Description: H.265/HEVC video decoder
Version: 1.0.15
Libs: -L\${libdir} -lde265
Libs.private: -lc++
Cflags: -I\${includedir}
EOF
fi
