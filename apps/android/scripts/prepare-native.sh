#!/usr/bin/env bash
set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly ANDROID_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
readonly NATIVE_MEDIA_ROOT="${ANDROID_ROOT}/native-media"
# The staged cdylib is built from agent-core, which links native-media and
# re-exports its triplex_media_* FFI alongside the triplex_agent_* surface.
readonly AGENT_CORE_ROOT="${ANDROID_ROOT}/agent"
readonly ABI="${1:-arm64-v8a}"
readonly API_LEVEL="${TRIPLEX_ANDROID_API_LEVEL:-29}"
readonly DEPS_ROOT="${TRIPLEX_NATIVE_DEPS_DIR:-${ANDROID_ROOT}/.native-deps}"
readonly STAGE_ROOT="${DEPS_ROOT}/install/${ABI}"
readonly SOURCE_ROOT="${DEPS_ROOT}/source"
readonly BUILD_ROOT="${DEPS_ROOT}/build/${ABI}"
readonly PJSIP_TAG="2.17"
readonly PJSIP_COMMIT="5a457451fa2712ba18e12b01738e8ff3af2b26fd"
readonly MBEDTLS_VERSION="3.6.6"
readonly RUST_TOOLCHAIN="1.95.0"
readonly MBEDTLS_SHA256="8fb65fae8dcae5840f793c0a334860a411f884cc537ea290ce1c52bb64ca007a"
readonly MBEDTLS_URL="https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-${MBEDTLS_VERSION}/mbedtls-${MBEDTLS_VERSION}.tar.bz2"

case "${ABI}" in
    arm64-v8a)
        readonly RUST_TARGET="aarch64-linux-android"
        readonly NDK_CLANG_PREFIX="aarch64-linux-android"
        ;;
    armeabi-v7a)
        readonly RUST_TARGET="armv7-linux-androideabi"
        readonly NDK_CLANG_PREFIX="armv7a-linux-androideabi"
        ;;
    x86_64)
        readonly RUST_TARGET="x86_64-linux-android"
        readonly NDK_CLANG_PREFIX="x86_64-linux-android"
        ;;
    *)
        echo "Unsupported ABI: ${ABI}" >&2
        exit 2
        ;;
esac

for tool in cargo cmake curl git make rustup shasum tar; do
    if ! command -v "${tool}" >/dev/null 2>&1; then
        echo "Required tool is missing: ${tool}" >&2
        exit 2
    fi
done

readonly NDK_ROOT="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "${NDK_ROOT}" || ! -f "${NDK_ROOT}/source.properties" ]]; then
    echo "Set ANDROID_NDK_HOME or ANDROID_NDK_ROOT to an installed Android NDK." >&2
    exit 2
fi

toolchain_bin=""
for candidate in \
    "${NDK_ROOT}/toolchains/llvm/prebuilt/darwin-x86_64/bin" \
    "${NDK_ROOT}/toolchains/llvm/prebuilt/linux-x86_64/bin"; do
    if [[ -x "${candidate}/${NDK_CLANG_PREFIX}${API_LEVEL}-clang" ]]; then
        toolchain_bin="${candidate}"
        break
    fi
done
if [[ -z "${toolchain_bin}" ]]; then
    echo "Could not resolve the NDK LLVM toolchain for ${ABI}." >&2
    exit 2
fi
readonly TOOLCHAIN_BIN="${toolchain_bin}"
readonly TARGET_CLANG="${TOOLCHAIN_BIN}/${NDK_CLANG_PREFIX}${API_LEVEL}-clang"

mkdir -p "${SOURCE_ROOT}" "${BUILD_ROOT}" "${STAGE_ROOT}"

readonly MBEDTLS_ARCHIVE="${SOURCE_ROOT}/mbedtls-${MBEDTLS_VERSION}.tar.bz2"
if [[ ! -f "${MBEDTLS_ARCHIVE}" ]]; then
    curl --fail --location --proto '=https' --tlsv1.2 \
        "${MBEDTLS_URL}" --output "${MBEDTLS_ARCHIVE}.partial"
    mv "${MBEDTLS_ARCHIVE}.partial" "${MBEDTLS_ARCHIVE}"
fi
actual_mbedtls_sha="$(shasum -a 256 "${MBEDTLS_ARCHIVE}" | awk '{print $1}')"
if [[ "${actual_mbedtls_sha}" != "${MBEDTLS_SHA256}" ]]; then
    echo "Mbed TLS archive checksum mismatch." >&2
    exit 3
fi

readonly MBEDTLS_SOURCE="${SOURCE_ROOT}/mbedtls-${MBEDTLS_VERSION}"
if [[ ! -d "${MBEDTLS_SOURCE}" ]]; then
    tar -xjf "${MBEDTLS_ARCHIVE}" -C "${SOURCE_ROOT}"
fi

cmake \
    -S "${MBEDTLS_SOURCE}" \
    -B "${BUILD_ROOT}/mbedtls" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="${NDK_ROOT}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="${ABI}" \
    -DANDROID_PLATFORM="android-${API_LEVEL}" \
    -DENABLE_PROGRAMS=OFF \
    -DENABLE_TESTING=OFF \
    -DUSE_SHARED_MBEDTLS_LIBRARY=OFF \
    -DUSE_STATIC_MBEDTLS_LIBRARY=ON \
    -DCMAKE_INSTALL_PREFIX="${STAGE_ROOT}"
cmake --build "${BUILD_ROOT}/mbedtls" --parallel
cmake --install "${BUILD_ROOT}/mbedtls"

readonly PJSIP_SOURCE="${SOURCE_ROOT}/pjproject-${ABI}"
if [[ ! -d "${PJSIP_SOURCE}/.git" ]]; then
    git clone --branch "${PJSIP_TAG}" --depth 1 \
        https://github.com/pjsip/pjproject.git "${PJSIP_SOURCE}"
fi
actual_pjsip_commit="$(git -C "${PJSIP_SOURCE}" rev-parse HEAD)"
if [[ "${actual_pjsip_commit}" != "${PJSIP_COMMIT}" ]]; then
    echo "PJSIP checkout is not the pinned 2.17 commit." >&2
    exit 3
fi
install -m 0644 "${SCRIPT_DIR}/pjsip-config-site.h" \
    "${PJSIP_SOURCE}/pjlib/include/pj/config_site.h"

(
    cd "${PJSIP_SOURCE}"
    export ANDROID_NDK_ROOT="${NDK_ROOT}"
    export APP_PLATFORM="android-${API_LEVEL}"
    export TARGET_ABI="${ABI}"
    export CFLAGS="-I${STAGE_ROOT}/include"
    export CXXFLAGS="-I${STAGE_ROOT}/include"
    export LDFLAGS="-L${STAGE_ROOT}/lib"
    # Never let host Homebrew packages satisfy an Android cross-compile probe.
    # Mbed TLS installs target-scoped .pc files into the staging prefix above.
    export PKG_CONFIG_PATH=""
    export PKG_CONFIG_LIBDIR="${STAGE_ROOT}/lib/pkgconfig"
    ./configure-android --use-ndk-cflags \
        --prefix="${STAGE_ROOT}" \
        --with-mbedtls="${STAGE_ROOT}" \
        --disable-pjsua2 \
        --disable-sound \
        --disable-video \
        --disable-android-mediacodec \
        --disable-speex-aec \
        --disable-l16-codec \
        --disable-gsm-codec \
        --disable-g722-codec \
        --disable-g7221-codec \
        --disable-speex-codec \
        --disable-ilbc-codec \
        --disable-opencore-amr \
        --disable-silk \
        --disable-opus \
        --disable-bcg729 \
        --disable-lyra \
        --disable-libwebrtc \
        --disable-libyuv
    make clean
    make dep
    make --jobs="$(sysctl -n hw.logicalcpu 2>/dev/null || getconf _NPROCESSORS_ONLN)"
    make install
)

"${TARGET_CLANG}" \
    -DPJ_AUTOCONF=1 \
    -DPJ_IS_BIG_ENDIAN=0 \
    -DPJ_IS_LITTLE_ENDIAN=1 \
    -I"${STAGE_ROOT}/include" \
    -fsyntax-only "${SCRIPT_DIR}/native-feature-check.c"

rustup toolchain install "${RUST_TOOLCHAIN}" --profile minimal
rustup target add "${RUST_TARGET}" --toolchain "${RUST_TOOLCHAIN}"
readonly RUST_LINKER_ENV="CARGO_TARGET_$(echo "${RUST_TARGET}" | tr '[:lower:]-' '[:upper:]_')_LINKER"
readonly PINNED_RUSTC="$(rustup which --toolchain "${RUST_TOOLCHAIN}" rustc)"
env "${RUST_LINKER_ENV}=${TARGET_CLANG}" \
    "RUSTC=${PINNED_RUSTC}" \
    "RUSTFLAGS=-C link-arg=-Wl,-soname,libtriplex_native_media.so -C link-arg=-Wl,-z,max-page-size=16384" \
    rustup run "${RUST_TOOLCHAIN}" cargo build \
        --manifest-path "${AGENT_CORE_ROOT}/Cargo.toml" \
        --release \
        --target "${RUST_TARGET}" \
        --locked
install -m 0755 \
    "${AGENT_CORE_ROOT}/target/${RUST_TARGET}/release/libtriplex_agent_core.so" \
    "${STAGE_ROOT}/lib/libtriplex_native_media.so"

readonly BUILD_INFO="${STAGE_ROOT}/BUILD-INFO.txt"
{
    echo "android_abi=${ABI}"
    echo "android_api=${API_LEVEL}"
    echo "pjsip_tag=${PJSIP_TAG}"
    echo "pjsip_commit=${PJSIP_COMMIT}"
    echo "mbedtls_version=${MBEDTLS_VERSION}"
    echo "mbedtls_archive_sha256=${MBEDTLS_SHA256}"
    echo "rust_toolchain=${RUST_TOOLCHAIN}"
} > "${BUILD_INFO}"

(
    cd "${STAGE_ROOT}"
    find include lib -type f -print0 | LC_ALL=C sort -z | xargs -0 shasum -a 256
    shasum -a 256 BUILD-INFO.txt
) > "${STAGE_ROOT}/manifest.sha256"

echo "Prepared verified native dependencies at ${STAGE_ROOT}"
