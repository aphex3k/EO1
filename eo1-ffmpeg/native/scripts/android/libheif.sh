#!/bin/bash
#
# Custom ffmpeg-kit library script: build static libde265 then libheif for HEIC/HEIF.
# libde265 is a dependency only (FFmpeg n6.0 has no --enable-libde265).
# Copied into ffmpeg-kit/scripts/android/ before android.sh runs.
#

DE265_ROOT="${LIB_INSTALL_BASE}/libde265"
DE265_INCLUDE="${DE265_ROOT}/include"
DE265_LIB="${DE265_ROOT}/lib/libde265.a"

# ffmpeg-kit defaults CXXFLAGS to -fno-rtti/-fno-exceptions; both libs need RTTI.
CXX_FIXED="$(echo " ${CXXFLAGS} " | sed 's/ -fno-rtti / /g; s/ -fno-exceptions / /g')"
CXX_FIXED="${CXX_FIXED} -frtti -fexceptions"
LLVM_BIN="${ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt/${TOOLCHAIN}/bin"

build_libde265() {
  local de265_build="${FFMPEG_KIT_TMPDIR}/cmake/build/${FFMPEG_KIT_BUILD_TYPE}-${ARCH}-lts/libde265-dep"
  mkdir -p "${de265_build}" "${DE265_ROOT}" || return 1
  cd "${de265_build}" || return 1

  cmake -Wno-dev \
    -DCMAKE_VERBOSE_MAKEFILE=0 \
    -DCMAKE_C_FLAGS="${CFLAGS}" \
    -DCMAKE_CXX_FLAGS="${CXX_FIXED}" \
    -DCMAKE_EXE_LINKER_FLAGS="${LDFLAGS}" \
    -DCMAKE_SYSROOT="${ANDROID_SYSROOT}" \
    -DCMAKE_FIND_ROOT_PATH="${ANDROID_SYSROOT}" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="${DE265_ROOT}" \
    -DCMAKE_SYSTEM_NAME=Generic \
    -DCMAKE_CXX_COMPILER="${LLVM_BIN}/$CXX" \
    -DCMAKE_C_COMPILER="${LLVM_BIN}/$CC" \
    -DCMAKE_LINKER="${LLVM_BIN}/$LD" \
    -DCMAKE_AR="${LLVM_BIN}/llvm-ar" \
    -DCMAKE_RANLIB="${LLVM_BIN}/llvm-ranlib" \
    -DCMAKE_POSITION_INDEPENDENT_CODE=1 \
    -DCMAKE_SYSTEM_PROCESSOR="$(get_cmake_system_processor)" \
    -DBUILD_SHARED_LIBS=OFF \
    -DENABLE_SDL=OFF \
    -DENABLE_DECODER=ON \
    -DENABLE_ENCODER=OFF \
    "${BASEDIR}/src/libde265" || return 1

  make -j$(get_cpu_count) || return 1
  make install || return 1

  mkdir -p "${INSTALL_PKG_CONFIG_DIR}"
  if [[ -f "${DE265_ROOT}/lib/pkgconfig/libde265.pc" ]]; then
    cp "${DE265_ROOT}/lib/pkgconfig/libde265.pc" "${INSTALL_PKG_CONFIG_DIR}/" || return 1
  else
    cat > "${INSTALL_PKG_CONFIG_DIR}/libde265.pc" <<EOF
prefix=${DE265_ROOT}
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
}

# Ensure libde265 sources exist (downloaded as custom library sibling or shared src)
if [[ ! -d "${BASEDIR}/src/libde265" ]]; then
  echo -e "\n(*) libde265 sources missing at ${BASEDIR}/src/libde265\n" 1>>"${BASEDIR}"/build.log 2>&1
  echo -e "(*) Download libde265 into src/libde265 (apply-to-ffmpeg-kit registers it as library 1).\n" 1>>"${BASEDIR}"/build.log 2>&1
  return 1
fi

if [[ ! -f "${DE265_LIB}" ]]; then
  build_libde265 || return 1
fi

mkdir -p "${BUILD_DIR}" || return 1
cd "${BUILD_DIR}" || return 1

if [[ ! -f "${DE265_LIB}" ]]; then
  echo -e "\n(*) libde265 static library not found at ${DE265_LIB}\n" 1>>"${BASEDIR}"/build.log 2>&1
  return 1
fi

cmake -Wno-dev \
  -DCMAKE_VERBOSE_MAKEFILE=0 \
  -DCMAKE_C_FLAGS="${CFLAGS}" \
  -DCMAKE_CXX_FLAGS="${CXX_FIXED}" \
  -DCMAKE_EXE_LINKER_FLAGS="${LDFLAGS}" \
  -DCMAKE_SYSROOT="${ANDROID_SYSROOT}" \
  -DCMAKE_FIND_ROOT_PATH="${ANDROID_SYSROOT};${DE265_ROOT}" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX="${LIB_INSTALL_PREFIX}" \
  -DCMAKE_SYSTEM_NAME=Generic \
  -DCMAKE_CXX_COMPILER="${LLVM_BIN}/$CXX" \
  -DCMAKE_C_COMPILER="${LLVM_BIN}/$CC" \
  -DCMAKE_LINKER="${LLVM_BIN}/$LD" \
  -DCMAKE_AR="${LLVM_BIN}/llvm-ar" \
  -DCMAKE_RANLIB="${LLVM_BIN}/llvm-ranlib" \
  -DCMAKE_POSITION_INDEPENDENT_CODE=1 \
  -DCMAKE_SYSTEM_PROCESSOR="$(get_cmake_system_processor)" \
  -DBUILD_SHARED_LIBS=OFF \
  -DBUILD_TESTING=OFF \
  -DWITH_EXAMPLES=OFF \
  -DWITH_LIBDE265=ON \
  -DWITH_X265=OFF \
  -DWITH_AOM_DECODER=OFF \
  -DWITH_AOM_ENCODER=OFF \
  -DWITH_DAV1D=OFF \
  -DWITH_RAV1E=OFF \
  -DWITH_SvtEnc=OFF \
  -DWITH_JPEG_DECODER=OFF \
  -DWITH_JPEG_ENCODER=OFF \
  -DWITH_OpenJPEG_DECODER=OFF \
  -DWITH_OpenJPEG_ENCODER=OFF \
  -DLIBDE265_INCLUDE_DIR="${DE265_INCLUDE}" \
  -DLIBDE265_LIBRARY="${DE265_LIB}" \
  "${BASEDIR}/src/${LIB_NAME}" || return 1

make -j$(get_cpu_count) || return 1

make install || return 1

if [[ -f "${LIB_INSTALL_PREFIX}/lib/pkgconfig/libheif.pc" ]]; then
  cp "${LIB_INSTALL_PREFIX}/lib/pkgconfig/libheif.pc" "${INSTALL_PKG_CONFIG_DIR}" || return 1
elif [[ -f "${BUILD_DIR}/libheif.pc" ]]; then
  cp "${BUILD_DIR}/libheif.pc" "${INSTALL_PKG_CONFIG_DIR}" || return 1
else
  cat > "${INSTALL_PKG_CONFIG_DIR}/libheif.pc" <<EOF
prefix=${LIB_INSTALL_PREFIX}
exec_prefix=\${prefix}
libdir=\${exec_prefix}/lib
includedir=\${prefix}/include

Name: libheif
Description: HEIF image codec
Version: 1.17.6
Requires.private: libde265
Libs: -L\${libdir} -lheif
Libs.private: -lc++
Cflags: -I\${includedir}
EOF
fi
